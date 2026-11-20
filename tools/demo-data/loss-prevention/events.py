"""Step `events`: fourteen days of a simulated pilot at Kampala Road Cafe, sent to the ledger.

Normal trade (sales, kitchen tickets, cash sessions, pours, stock counts, and what the cameras would report about
them) plus planted incidents, one or more for each rule. The data is synthetic: it shows how the module reports,
not how accurately it detects anything.

POS, kitchen and stock events are sent by the admin user (source `demo-pilot`); camera and meter events by the
simulated edge agent (source `krd-edge`). Event ids are derived from the source key, so a re-run is all duplicates.
"""
import math
import random
import uuid
from datetime import date, datetime, timedelta, timezone

from api import ORG, STATE, log, save

TZ = timezone(timedelta(hours=3))  # Africa/Kampala, no daylight saving
NS = uuid.UUID("5d0c3a52-8f3e-4a7e-9a55-2f3c1b7e0d11")
DAYS = 14
M, S = timedelta(minutes=1), timedelta(seconds=1)

T1_MENU = [  # (product name in DEMO, price, station)
    ("Cappuccino", 7000, "Coffee bar"), ("Café Late", 7000, "Coffee bar"), ("Café Americano", 5000, "Coffee bar"),
    ("Espresso", 4000, "Coffee bar"), ("African Tea", 5000, "Coffee bar"), ("Fresh Orange Juice", 8000, "Coffee bar"),
    ("Butter Croissant", 6000, None), ("Club Sandwich", 18000, "Kitchen"), ("Beef Burger + Chips", 22000, "Kitchen"),
    ("Fish & Chips", 20000, "Kitchen"), ("Caesar Salad", 16000, "Kitchen"), ("Chicken Wrap", 14000, "Kitchen"),
]
BAR_DRINKS = [("gnt", "Gin & Tonic", 15000, "gin"), ("vsoda", "Vodka Soda", 14000, "vodka"),
              ("whisky1", "Whisky (single, 30 ml)", 18000, "whisky")]
SPIRIT_NAMES = {"gin": "House Gin (750 ml bottle)", "vodka": "House Vodka (750 ml bottle)",
                "whisky": "House Whisky (750 ml bottle)"}
COST_PER_ML = {"gin": 60, "vodka": 55, "whisky": 90}
CASHIERS = ["sarah", "grace", "peter", "aisha"]


def iso(t):
    return t.isoformat(timespec="seconds")


class Gen:
    def __init__(self, products, start):
        st = STATE
        self.ids, self.zones, self.cams, self.meters = st["ids"], st["zones"], st["cameras"], st["meters"]
        self.products = products
        self.start = start
        self.rng = random.Random(20261106)
        self.pos, self.edge = [], []
        self.seq = 0
        # per day look-ups for placing planted incidents in quiet moments
        self.t1_pos, self.bar_sales, self.pours, self.pass_x = {}, {}, {}, {}
        self.sold_ml = {}
        self.shift_of = {}
        self.planted = []

    # ------------------------------------------------------------------ envelopes
    def user(self, key):
        return self.ids["user_" + key]

    def _ev(self, sink, source, sid, type_, at, level, esrc, payload, status="observed", **kw):
        e = {"eventId": str(uuid.uuid5(NS, f"{ORG}|{source}|{sid}")), "source": source, "sourceEventId": sid,
             "type": type_, "schemaVersion": 1, "occurredAt": iso(at), "evidenceLevel": level,
             "evidenceSource": esrc, "observationStatus": status, "payload": payload,
             "branchId": self.ids["branch"]}
        e.update({k: v for k, v in kw.items() if v is not None})
        sink.append(e)
        return e

    def posev(self, sid, type_, at, level, payload, esrc="pos", **kw):
        return self._ev(self.pos, "demo-pilot", sid, type_, at, level, esrc, payload, **kw)

    def vis(self, zone, cam, type_, at, payload, conf=None):
        self.seq += 1
        payload = dict(payload, cameraId=self.cams[cam])
        return self._ev(self.edge, "krd-edge", f"v{self.seq}", type_, at, "zone", "camera", payload,
                        deviceId=self.zones[zone], confidence=conf or round(self.rng.uniform(0.8, 0.97), 2))

    def cross(self, zone, cam, at, cls, n, direction="out"):
        if n > 0:
            self.vis(zone, cam, "vision.line_crossed", at, {"class": cls, "direction": direction, "count": n})
            if zone == "pass":
                self.pass_x.setdefault(at.date(), []).append(at)

    def dwell(self, zone, cam, start, end, cls="person"):
        self.vis(zone, cam, "vision.dwell", end, {"class": cls, "startedAt": iso(start), "endedAt": iso(end),
                                                    "durationSeconds": int((end - start).total_seconds()),
                                                    "maxCount": 1})

    def drawer_open(self, at):
        self.vis("drawer", "till", "vision.state_changed", at, {"object": "drawer", "from": "closed", "to": "open",
                                                                 "secondsInPreviousState": 60})

    def pour(self, spirit, at, ml, tilt=True):
        self.seq += 1
        self._ev(self.edge, "krd-edge", f"p{self.seq}", "pour.measured", at, "item", "sensor",
                 {"volumeMl": round(ml, 1), "productId": self.ids["prod_" + spirit], "startedAt": iso(at - 3 * S),
                  "endedAt": iso(at)}, deviceId=self.meters[spirit])
        self.pours.setdefault(at.date(), []).append(at)
        if tilt:
            self.tilt(at - 2 * S)

    def tilt(self, at):
        self.vis("bar", "bar", "vision.handling", at, {"object": "bottle", "action": "tilted",
                                                        "durationSeconds": round(self.rng.uniform(1.8, 3.2), 1)})

    def note_t1(self, at):
        self.t1_pos.setdefault(at.date(), []).append(at)

    # ------------------------------------------------------------------ POS building blocks
    def session(self, till, actor, start, end, diff, tag):
        sid = f"{tag}"
        tid = self.ids[till]
        self.posev(f"sess:{sid}:opened", "cash_session.opened", start, "session",
                   {"openingFloat": 100000, "tillId": tid}, actorId=self.user(actor), sessionId=sid)
        return {"sid": sid, "till": till, "tid": tid, "actor": actor, "start": start, "end": end, "diff": diff,
                "cash": 0}

    def close(self, sh):
        expected = 100000 + sh["cash"]
        self.posev(f"sess:{sh['sid']}:closed", "cash_session.closed", sh["end"], "session",
                   {"expected": expected, "counted": expected + sh["diff"], "difference": sh["diff"],
                    "varianceReason": None if sh["diff"] == 0 else "count difference", "tillId": sh["tid"]},
                   actorId=self.user(sh["actor"]), sessionId=sh["sid"])
        if sh["till"] == "till1":
            for t in (sh["start"] + 20 * S, sh["end"] + 20 * S):
                self.drawer_open(t)
                self.note_t1(t)

    def line(self, oid, n, name, price, qty, disc=0, spirit=None):
        pid = self.products.get(name) or self.ids.get("prod_" + name)
        ingr = ([{"productId": self.ids["prod_" + spirit], "quantity": 30 * qty, "totalValue": 30 * qty *
                  COST_PER_ML[spirit]}] if spirit else [{"productId": pid, "quantity": qty}])
        return {"lineId": f"{oid}-{n}", "productId": pid, "description": name, "quantity": qty, "unitPrice": price,
                "discountAmount": disc, "total": price * qty - disc, "ingredients": ingr}

    def sale(self, sh, oid, at, lines, table=None):
        subtotal = sum(l["unitPrice"] * l["quantity"] for l in lines)
        disc = sum(l["discountAmount"] for l in lines)
        p = {"invoiceNumber": f"KRD-DEMO-{oid}", "tillId": sh["tid"], "subtotal": subtotal, "discountTotal": disc,
             "taxAmount": 0, "total": subtotal - disc, "lines": lines}
        if table:
            p["tableId"] = self.ids["tables"][str(table)]
        self.posev(f"sale:{oid}", "sale.completed", at, "order", p, actorId=self.user(sh["actor"]),
                   transactionId=f"inv-{oid}", orderId=oid, sessionId=sh["sid"])
        sh["cash"] += subtotal - disc
        if sh["till"] == "till1":
            self.drawer_open(at + 3 * S)
            self.note_t1(at)

    def ticket(self, oid, station, items, created, order_type, table=None, cancel_after=None, remade=None,
               suppress_pass=False, deliver=True):
        """A kitchen ticket and its stages; returns the dispatch time (None when it never left)."""
        tk = f"{oid}-{'K' if station == 'Kitchen' else 'C'}"
        base = {"station": station, "itemCount": items}
        p = dict(base, ticketNo=tk[-8:], orderType=order_type)
        if table:
            p["tableId"] = self.ids["tables"][str(table)]
        self.posev(f"kt:{tk}:created", "kitchen.ticket_created", created, "ticket", p, esrc="kds", ticketId=tk,
                   orderId=oid)
        food = station == "Kitchen"
        t_prep = created + (self.rng.uniform(7, 14) if food else self.rng.uniform(2.5, 5)) * M
        self.posev(f"kt:{tk}:start", "kitchen.stage", created + 1 * M, "ticket",
                   dict(base, stage="preparation_started"), esrc="kds", ticketId=tk, orderId=oid)
        self.posev(f"kt:{tk}:prepared", "kitchen.stage", t_prep, "ticket", dict(base, stage="prepared"), esrc="kds",
                   ticketId=tk, orderId=oid)
        if cancel_after is not None:
            self.posev(f"kt:{tk}:cancelled", "kitchen.stage", t_prep + cancel_after * M, "ticket",
                       dict(base, stage="cancelled", afterReady=False), esrc="kds", ticketId=tk, orderId=oid)
            return None
        t_out = t_prep + self.rng.uniform(0.5, 1.8) * M
        self.posev(f"kt:{tk}:dispatched", "kitchen.stage", t_out, "ticket", dict(base, stage="dispatched"),
                   esrc="kds", ticketId=tk, orderId=oid)
        self.handover(station, t_out, items, table, suppress_pass, deliver)
        if remade is not None:  # sent back by the guest, made again
            t_re = t_out + 6 * M
            self.posev(f"kt:{tk}:remade", "kitchen.stage", t_re, "ticket",
                       dict(base, stage="remade", recallCount=1, recallReason=remade[1], afterReady=remade[0]),
                       esrc="kds", ticketId=tk, orderId=oid)
            self.handover(station, t_re + 9 * M, items, table, False, deliver)
        return t_out

    def handover(self, station, t_out, items, table, suppress_pass, deliver):
        if station == "Kitchen":
            if not suppress_pass:
                self.cross("pass", "pass", t_out + 30 * S, "plate", items)
        elif station == "Coffee bar":
            self.cross("pickup_coffee", "pickup", t_out + 20 * S, "cup", items)
        if deliver and table and table <= 4:
            self.cross(f"table{table}", "dining", t_out + 75 * S, "plate", items, "in")

    def t1_order(self, sh, at, dine_table=None, discount=0.0, food_only=False, unpaid=False, cancel=None,
                 suppress_pass=False, void_fired=False, remade=None):
        """One counter order: queue, ticket(s), payment, presence at the till, hand-over, door counts."""
        r = self.rng
        self.seq += 1
        oid = f"{at:%m%d}-{self.seq:05d}"
        menu = [m for m in T1_MENU if m[2] == "Kitchen"] if food_only else T1_MENU
        picks = r.sample(menu, r.choice([1, 1, 2, 2, 3]))
        if food_only and not any(m[2] == "Kitchen" for m in picks):
            picks = [T1_MENU[7]]
        lines, by_station = [], {}
        for n, (name, price, station) in enumerate(picks, 1):
            qty = 2 if r.random() < 0.15 else 1
            d = round(price * qty * 0.25) if discount and r.random() < discount else (
                round(price * qty * 0.10) if r.random() < 0.06 else 0)
            lines.append(self.line(oid, n, name, price, qty, d))
            if station == "Coffee bar" and dine_table:
                station = "Table drinks"  # carried to the table, not through the pick-up counter (see README)
            if station:
                by_station[station] = by_station.get(station, 0) + qty
        party = r.choice([1, 1, 2, 2, 3]) if dine_table else 1
        self.cross("door", "door", at - 90 * S, "person", party, "in")
        dine = dine_table is not None
        otype = "dine_in" if dine else "takeaway"
        if not dine:  # waits in the till queue, orders and pays at once
            wait = r.uniform(15, 200)
            self.dwell("queue", "till", at - wait * S, at)
            pay = at + 40 * S
            self.dwell("checkout", "till", at - 5 * S, pay + 10 * S)
            created = pay + 30 * S
        else:
            created = at + 1 * M
            pay = at + r.uniform(35, 55) * M
        last_out = created
        for station, items in by_station.items():
            out = self.ticket(oid, station, items, created, otype, dine_table, cancel_after=cancel,
                              remade=remade if station == "Kitchen" else None,
                              suppress_pass=suppress_pass)
            last_out = max(last_out, out or created)
        if void_fired:  # an item voided after the kitchen had it: the guest came back to the till
            food = next((l for l, (n, p, st) in zip(lines, picks) if st == "Kitchen"), lines[0])
            t = last_out + 2 * M
            self.dwell("checkout", "till", t - 40 * S, t + 15 * S)
            self.posev(f"void:{food['lineId']}", "sale.line_voided", t, "item",
                       {"quantity": 1, "firedQuantity": 1, "whole": food["quantity"] == 1,
                        "reason": "Guest changed their mind", "tillId": sh["tid"],
                        "approvedById": self.user("mike"), "description": food["description"],
                        "amount": food["unitPrice"]},
                       actorId=self.user(sh["actor"]), orderId=oid, lineId=food["lineId"], sessionId=sh["sid"])
            self.note_t1(t)
            self.planted.append(("C01", t))
        if cancel is not None:
            t = created + 12 * M + cancel * M
            self.dwell("checkout", "till", t - 40 * S, t + 15 * S)
            fired = [{"description": l["description"], "quantity": l["quantity"], "firedQuantity": l["quantity"]}
                     for l, (n, p, st) in zip(lines, picks) if st == "Kitchen"]
            self.posev(f"cancel:{oid}", "order.cancelled", t, "order",
                       {"reason": "Guest left", "firedLines": fired, "tillId": sh["tid"],
                        "firedAmount": sum(l["unitPrice"] * l["quantity"] for l, (n, p, st) in zip(lines, picks)
                                           if st == "Kitchen")},
                       actorId=self.user(sh["actor"]), orderId=oid, sessionId=sh["sid"])
            self.note_t1(t)
            return oid, (at - 2 * M, t + 3 * M, party)
        if unpaid:
            return oid, (at - 2 * M, last_out + 30 * M, party)
        if dine:
            self.dwell("checkout", "till", pay - 40 * S, pay + 10 * S)
        self.sale(sh, oid, pay, lines, dine_table)
        if not dine:
            self.cross("pickup_till", "pickup", pay + r.uniform(20, 40) * S, "item",
                       sum(l["quantity"] for l in lines))
        self.cross("door", "door", pay + 3 * M, "person", party, "out")
        return oid, (at - 2 * M, pay + 3 * M, party)

    def bar_sale(self, sh, at, over=0.0):
        r = self.rng
        self.seq += 1
        oid = f"{at:%m%d}-{self.seq:05d}"
        lines = []
        for n, (key, name, price, spirit) in enumerate(r.sample(BAR_DRINKS, r.choice([1, 1, 2])), 1):
            qty = 2 if r.random() < 0.25 else 1
            lines.append(self.line(oid, n, key, price, qty, spirit=spirit))
            lines[-1]["description"] = name
            for u in range(qty):
                ml = r.uniform(43, 47) if r.random() < over else r.gauss(30, 0.5)
                self.pour(spirit, at - (60 + 25 * (n + u)) * S, ml)
            self.sold_ml.setdefault(at.date(), {}).setdefault(spirit, 0)
            self.sold_ml[at.date()][spirit] += 30 * qty
        self.sale(sh, oid, at, lines)
        self.bar_sales.setdefault(at.date(), []).append(at)
        self.cross("door", "door", at - 4 * M, "person", len(lines), "in")
        self.cross("door", "door", at + r.uniform(20, 50) * M, "person", len(lines), "out")

    def refund(self, sh, at, amount, quiet=False):
        self.seq += 1
        rid = f"rf-{at:%m%d}-{self.seq:05d}"
        if sh["till"] == "till1":
            if not quiet:
                self.dwell("checkout", "till", at - 50 * S, at + 15 * S)
            self.drawer_open(at + 4 * S)
            self.note_t1(at)
        self.posev(f"refund:{rid}", "refund.issued", at, "order",
                   {"amount": amount, "reason": "Item returned", "stockDisposition": "discard",
                    "approvedById": self.user("mike"), "tillId": sh["tid"]},
                   actorId=self.user(sh["actor"]), transactionId=rid, sessionId=sh["sid"])
        sh["cash"] -= amount

    def override(self, sh, at, approver):
        self.seq += 1
        self.posev(f"override:{self.seq}", "override.approved", at, "session",
                   {"kind": "price_override", "approverId": self.user(approver), "endpoint": "/pos/orders"},
                   actorId=self.user(sh["actor"]), sessionId=sh["sid"])

    def no_sale(self, sh, at):
        self.seq += 1
        self.posev(f"nosale:{self.seq}", "cash_drawer.opened", at, "session",
                   {"reason": "no_sale", "tillId": sh["tid"]}, actorId=self.user(sh["actor"]), sessionId=sh["sid"])
        if sh["till"] == "till1":
            self.drawer_open(at + 2 * S)
            self.note_t1(at)

    # ------------------------------------------------------------------ time helpers
    def at(self, d, h, m=0):
        return datetime.combine(d, datetime.min.time(), TZ) + timedelta(hours=h, minutes=m)

    def quiet(self, d, times, lo, hi, before, after, step=3):
        """A moment in [lo, hi] with none of `times` within `before` minutes before or `after` minutes after."""
        t = lo
        while t <= hi:
            if all(not (t - before * M <= x <= t + after * M) for x in times):
                return t
            t += step * M
        raise SystemExit(f"No quiet moment on {d} between {lo:%H:%M} and {hi:%H:%M}")

    # ------------------------------------------------------------------ the fourteen days
    def build(self):
        r = self.rng
        level = {"gin": 2200, "vodka": 2600, "whisky": 1900}
        peter_short_days = {1, 4, 6, 8, 10, 12}
        for i in range(DAYS):
            d = self.start + timedelta(days=i)
            busy = {n: [] for n in range(1, 7)}
            # Table 4 is kept free on days 10 and 11 for the V-K3 incident. The service-vision engine reads the day
            # before's tickets but not its sales, so a table used the day before looks occupied all day (engine bug).
            reserved = {4} if i in (10, 11) else set()
            shifts = []
            # Counter till: two shifts; bar till: two shifts. Four cashiers rotate, so all four work each day.
            for till, ci, (h0, m0), (h1, m1), tag in (
                    ("till1", i % 4, (7, 30), (14, 30), "am"), ("till1", (i + 2) % 4, (14, 30), (21, 0), "pm"),
                    ("till2", (i + 1) % 4, (11, 0), (16, 0), "am"), ("till2", (i + 3) % 4, (16, 0), (22, 0), "pm")):
                actor = CASHIERS[ci]
                diff = 0
                if actor == "peter" and i in peter_short_days and till == "till1":
                    diff = -18000 if i == 6 else -r.choice([1500, 2000, 2500, 3000])
                elif r.random() < 0.15:
                    diff = r.choice([-500, 500, 1000])
                sh = self.session(till, actor, self.at(d, h0, m0), self.at(d, h1, m1), diff,
                                  f"{d:%m%d}-{till}-{tag}")
                self.shift_of[(i, till, tag)] = sh
                shifts.append(sh)

            for sh in shifts:
                start, end = sh["start"], sh["end"]
                if sh["till"] == "till1":
                    n = r.randint(26, 32)
                    disc = 0.55 if (sh["actor"] == "aisha" and i == 11) else 0.0
                    for t in sorted(start + 10 * M + (end - start - 25 * M) * r.random() for _ in range(n)):
                        table = None
                        if r.random() < 0.45 and self.at(d, 8, 30) <= t < end - 70 * M:
                            free = [k for k in range(1, 7) if k not in reserved
                                    and all(not (a <= t + 2 * M and t - 2 * M <= b) for a, b, _ in busy[k])]
                            table = r.choice(free) if free else None
                        oid, (a, b, party) = self.t1_order(sh, t, table, discount=disc)
                        if table:
                            busy[table].append((a, b, party))
                else:
                    n = r.randint(20, 26)
                    over = 0.35 if i == 3 else 0.0
                    for t in sorted(start + 10 * M + (end - start - 25 * M) * r.random() for _ in range(n)):
                        self.bar_sale(sh, t, over)
                # A little normal friction: a refund, an override, a void before firing, a no-sale.
                if r.random() < 0.35:
                    self.refund(sh, start + (end - start) * r.uniform(0.2, 0.8), r.choice([5000, 7000, 12000]))
                if r.random() < 0.4:
                    self.override(sh, start + (end - start) * r.uniform(0.1, 0.9),
                                  "mike" if r.random() < 0.5 else "mary")
                if r.random() < 0.2:
                    self.no_sale(sh, start + (end - start) * r.uniform(0.1, 0.9))

            self.plant(i, d, busy)

            # Normal cancellations before the kitchen had anything (no fired lines).
            sh = self.shift_of[(i, "till1", "am")]
            if r.random() < 0.5:
                t = sh["start"] + r.uniform(60, 300) * M
                self.seq += 1
                self.dwell("checkout", "till", t - 30 * S, t + 10 * S)
                self.posev(f"cancel:pre-{self.seq}", "order.cancelled", t, "order",
                           {"reason": "Wrong order", "firedLines": [], "tillId": sh["tid"]},
                           actorId=self.user(sh["actor"]), orderId=f"pre-{self.seq}", sessionId=sh["sid"])
                self.note_t1(t)

            for sh in shifts:
                self.close(sh)

            # Occupancy of tables 1-4 every 15 minutes, of the till queue every 10 minutes.
            t = self.at(d, 7, 30)
            while t < self.at(d, 21, 0):
                for k in (1, 2, 3, 4):
                    mid = t + 450 * S
                    cnt = sum(p for a, b, p in busy[k] if a <= mid <= b)
                    self.vis(f"table{k}", "dining", "vision.occupancy", t + 900 * S,
                             {"count": cnt, "class": "person", "periodSeconds": 900})
                t += 15 * M
            q = [e for e in self.edge if e["type"] == "vision.dwell" and e.get("deviceId") == self.zones["queue"]
                 and e["occurredAt"].startswith(d.isoformat())]
            spans = [(datetime.fromisoformat(e["payload"]["startedAt"]),
                      datetime.fromisoformat(e["payload"]["endedAt"])) for e in q]
            t = self.at(d, 7, 30)
            while t < self.at(d, 21, 0):
                mid = t + 300 * S
                self.vis("queue", "till", "vision.occupancy", t + 600 * S,
                         {"count": sum(1 for a, b in spans if a <= mid + 120 * S and b >= mid - 120 * S),
                          "class": "person", "periodSeconds": 600})
                t += 10 * M

            # Walk-ins who leave without buying.
            for _ in range(r.randint(8, 14)):
                t = self.at(d, 8, 0) + r.uniform(0, 780) * M
                self.cross("door", "door", t, "person", 1, "in")
                self.cross("door", "door", t + r.uniform(1, 4) * M, "person", 1, "out")

            # Bar stock count at close.
            self.stock_count(i, d, level)
        self.camera_gap()
        self.coffee_shortfall()

    def plant(self, i, d, busy):
        r = self.rng
        sh = lambda till, tag: self.shift_of[(i, till, tag)]
        t1 = lambda tag: sh("till1", tag)

        def peter_t1():
            return next(s for s in (t1("am"), t1("pm")) if s["actor"] == "peter")

        def free_table(lo, hi):
            for k in (1, 2, 3, 4, 5, 6):
                if not (i in (10, 11) and k == 4) and all(not (a <= hi and lo <= b) for a, b, _ in busy[k]):
                    return k
            return None

        # C01: items voided after the kitchen fired them (days 3, 7, 12).
        if i in (3, 7, 12):
            s = t1("pm")
            t = s["start"] + 40 * M
            k = free_table(t - 3 * M, t + 70 * M)
            _, span = self.t1_order(s, t, k, food_only=True, void_fired=True)
            if k:
                busy[k].append(span)
        # C02: orders cancelled after the kitchen prepared them (days 5, 10).
        if i in (5, 10):
            s = t1("am")
            t = s["start"] + 150 * M
            k = free_table(t - 3 * M, t + 40 * M)
            _, span = self.t1_order(s, t, k, food_only=True, cancel=3)
            if k:
                busy[k].append(span)
            self.planted.append(("C02", t))
        # K01: food served to a table and never paid or cancelled (days 5, 11).
        if i in (5, 11):
            s = t1("pm")
            t = s["start"] + 200 * M
            k = 5 if i == 5 else 6  # tables without a camera: an unpaid order stays open to the end of the run
            _, span = self.t1_order(s, t, k, food_only=True, unpaid=True)
            busy[k].append(span)
            self.planted.append(("K01", t))
        # C10 + K02: plates sent back after they were ready (day 2: one; day 7: five, two of them after ready).
        if i in (2, 7):
            s = t1("pm")
            flags = [True] if i == 2 else [True, True, False, False, False]
            for n, after_ready in enumerate(flags):
                t = s["start"] + (30 + 70 * n) * M
                self.t1_order(s, t, None, food_only=True, remade=(after_ready, "Guest complaint: cold"))
            self.planted.append(("K02/C10", d))
        # V-K2: a ticket marked served with no plate seen leaving the pass (days 4, 10).
        if i in (4, 10):
            s = t1("am")
            times = self.pass_x.get(d, [])
            t = self.quiet(d, times, s["start"] + 20 * M, s["end"] - 60 * M, 12, 12, 1)
            self.t1_order(s, t - 12 * M, None, food_only=True, suppress_pass=True)
            self.planted.append(("V-K2", t))
        # V-K1: more plates out of the pass than the tickets account for (day 7, 19:00-20:00).
        if i == 7:
            lo = self.at(d, 19, 0)
            items = sum(e["payload"]["itemCount"] for e in self.pos if e["type"] == "kitchen.stage"
                        and e["payload"].get("stage") == "dispatched" and e["payload"]["station"] == "Kitchen"
                        and lo <= datetime.fromisoformat(e["occurredAt"]) < lo + 60 * M)
            extra = max(6, math.ceil(items * 0.3) + 4)
            for n, part in enumerate((extra // 3, extra // 3, extra - 2 * (extra // 3))):
                self.cross("pass", "pass", lo + (12 + 15 * n) * M, "plate", part)
            self.planted.append(("V-K1", lo))
        # V-K3: plates carried to table 4 with no open order (day 11).
        if i == 11:
            self.cross("table4", "dining", self.at(d, 16, 40), "plate", 2, "in")
            self.planted.append(("V-K3", self.at(d, 16, 40)))
        # V-F1: more items handed over at the takeaway counter than were sold (day 6, 15:00-16:00).
        if i == 6:
            lo = self.at(d, 15, 0)
            sold = sum(l["quantity"] for e in self.pos if e["type"] == "sale.completed"
                       and e["payload"]["tillId"] == self.ids["till1"]
                       and lo - 5 * M <= datetime.fromisoformat(e["occurredAt"]) < lo + 65 * M
                       for l in e["payload"]["lines"])
            extra = math.ceil(sold * 0.35) + 5
            self.cross("pickup_till", "pickup", lo + 25 * M, "item", extra // 2)
            self.cross("pickup_till", "pickup", lo + 41 * M, "item", extra - extra // 2)
            self.planted.append(("V-F1", lo))
        # C11 + cashier-vision rules, all on Peter's counter shifts.
        p = None
        try:
            p = peter_t1()
        except StopIteration:
            pass
        if p:
            times = self.t1_pos.setdefault(d, [])
            if i in (4, 10):  # V-C1: drawer seen open with nothing recorded near it
                t = self.quiet(d, times, p["start"] + 40 * M, p["end"] - 40 * M, 4, 5)
                self.drawer_open(t)
                times.append(t)
                self.planted.append(("V-C1", t))
            if i == 8:  # C04: six refunds in one shift; V-C2: one with nobody at the till
                for n in range(5):
                    self.refund(p, p["start"] + (50 + 60 * n) * M, r.choice([6000, 9000, 14000]))
                t = self.quiet(d, times, p["start"] + 60 * M, p["end"] - 40 * M, 5, 5)
                self.refund(p, t, 16000, quiet=True)
                times.append(t)
                self.planted.append(("C04+V-C2", t))
            if i == 12:  # C11: seven "No sale" drawer openings in one shift
                for n in range(7):
                    self.no_sale(p, p["start"] + (35 + 50 * n) * M)
                self.planted.append(("C11", d))
        if i in (3, 9):  # V-C3: a customer served for minutes at the till, no sale recorded
            s = t1("pm")
            times = self.t1_pos.setdefault(d, [])
            t = self.quiet(d, times, s["start"] + 30 * M, s["end"] - 30 * M, 8, 9)
            self.dwell("checkout", "till", t, t + r.randint(150, 210) * S)
            times.append(t)
            self.planted.append(("V-C3", t))
        if i == 5:  # C03: one refund above 50,000
            s = next(x for x in self.shift_of.values() if x["actor"] == "grace" and x["start"].date() == d)
            self.refund(s, s["start"] + 100 * M, 86000)
            self.planted.append(("C03", d))
        if i == 12:  # C05 + C06: Grace has seven overrides, all approved by Mike
            s = next(x for x in self.shift_of.values() if x["actor"] == "grace" and x["start"].date() == d)
            for n in range(7):
                self.override(s, s["start"] + (20 + 35 * n) * M, "mike")
            self.planted.append(("C05/C06", d))
        if i == 9:
            # V-B1 + B04: gin poured at the back bar in a quiet spell with no drink sold
            # after closing time, nothing rung up: nine metered gin pours, three of them seen by the bar camera
            t = self.at(d, 22, 15)
            for n in range(9):
                self.pour("gin", t + 3 * n * M, r.gauss(30, 0.5), tilt=n in (0, 3, 6))
            self.planted.append(("V-B1+B04+B01", t))
        if i == 6:  # V-B2: a bottle tipped near a drink sale, no meter reading (unmetered bottle)
            sales, pours = self.bar_sales.get(d, []), self.pours.get(d, [])
            done = 0
            for st in sales:
                t = st - 7 * M
                if all(abs((x - t).total_seconds()) > 60 for x in pours) and done < 2:
                    self.tilt(t)
                    done += 1
                    self.planted.append(("V-B2", t))
        if i == 3:
            self.planted.append(("B05+B04+B01", d))
        if i in (8, 9, 10):
            self.planted.append(("B02", d))

    def stock_count(self, i, d, level):
        sold = self.sold_ml.get(d, {})
        poured = {}
        for e in self.edge:
            if e["type"] == "pour.measured" and e["occurredAt"].startswith(d.isoformat()):
                key = next(k for k, v in self.meters.items() if v == e["deviceId"])
                poured[key] = poured.get(key, 0) + e["payload"]["volumeMl"]
        for spirit in ("gin", "vodka", "whisky"):
            opening = level[spirit]
            s = sold.get(spirit, 0)
            system = opening - s
            reason = "unexplained"
            variance = round(s - poured.get(spirit, s), 0)  # counted short by what was poured beyond the sales
            variance += self.rng.choice([-10, -5, 0, 0, 5])
            if spirit == "vodka" and i in (8, 9, 10):
                variance, reason = -40, "spillage"
            counted = system + variance
            self.posev(f"count:{d}:{spirit}", "stock_count.line", self.at(d, 22, 45), "item",
                       {"productId": self.ids["prod_" + spirit], "productName": SPIRIT_NAMES[spirit],
                        "countCode": f"BC-KRD-{d:%m%d}", "systemMl": system, "countedMl": counted,
                        "varianceMl": variance, "reasonCode": reason, "toleranceMl": 30,
                        "costPerMl": COST_PER_ML[spirit], "openingMl": opening, "soldMl": s},
                       esrc="stock", sessionId=f"BC-KRD-{d:%m%d}")
            level[spirit] = counted if counted > 1500 else counted + 3000  # a new bottle set opened

    def camera_gap(self):
        d = self.start + timedelta(days=7)
        a, b = self.at(d, 13, 0), self.at(d, 13, 40)
        self.edge = [e for e in self.edge if not (e.get("deviceId") == self.zones["door"]
                                                  and e["type"] == "vision.line_crossed"
                                                  and a <= datetime.fromisoformat(e["occurredAt"]) <= b)]
        self.vis("door", "door", "vision.gap", b, {"from": iso(a), "to": iso(b), "reason": "stream_lost"})
        self.edge[-1]["observationStatus"] = "unavailable"
        self.planted.append(("camera gap", a))

    def coffee_shortfall(self):
        """V-F2: on day 10, 10:00-11:00, cups prepared at the coffee bar but not seen handed over."""
        d = self.start + timedelta(days=10)
        lo = self.at(d, 10, 0)
        inside = [e for e in self.edge if e.get("deviceId") == self.zones["pickup_coffee"]
                  and lo <= datetime.fromisoformat(e["occurredAt"]) < lo + 65 * M]
        prepared = sum(e["payload"]["count"] for e in inside)
        need = max(5, math.ceil(prepared * 0.45) + 2)
        drop, removed = set(), 0
        for e in inside:
            if removed >= need:
                break
            drop.add(e["eventId"])
            removed += e["payload"]["count"]
        self.edge = [e for e in self.edge if e["eventId"] not in drop]
        self.planted.append(("V-F2", lo))


def run(admin_session, agent_session):
    if "start" not in STATE:
        STATE["start"] = (date.today() - timedelta(days=DAYS)).isoformat()
        save()
    start = date.fromisoformat(STATE["start"])
    from api import rows
    products = {p["name"]: p["id"] for p in rows(admin_session.call("GET", "/products?pageSize=500"))}
    missing = [m[0] for m in T1_MENU if m[0] not in products]
    if missing:
        raise SystemExit(f"DEMO lacks menu products: {missing}")
    g = Gen(products, start)
    g.build()
    log(f"  {len(g.pos)} POS/kitchen/stock events, {len(g.edge)} camera/meter events, {start} + {DAYS} days")
    totals = {"accepted": 0, "duplicates": 0, "rejected": 0}
    reasons = {}
    for sess, events in ((admin_session, g.pos), (agent_session, g.edge)):
        events.sort(key=lambda e: e["occurredAt"])
        for k in range(0, len(events), 500):
            res = sess.call("POST", "/loss-prevention/events", {"events": events[k:k + 500]})
            for key in totals:
                totals[key] += res[key]
            for x in res["results"]:
                if x["status"] == "rejected":
                    reasons[x["reason"]] = reasons.get(x["reason"], 0) + 1
    log("  ingest:", totals)
    for reason, n in reasons.items():
        log(f"    rejected {n}: {reason}")
    STATE["planted"] = [(k, str(v)) for k, v in g.planted]
    save()

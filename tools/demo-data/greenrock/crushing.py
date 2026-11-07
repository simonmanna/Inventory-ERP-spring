"""Two crushing lines, grading profiles and 30 days of posted runs (three shifts). Feed comes from the pit's ROM pad, so
each run's cost carries its pit. Outputs always add up to the feed tonnes."""
import datetime as dt
import random

from core import A, ensure, ids, once, rows, save
from quarry import PITS, schedule, working_days, TODAY

STOCKPILES = [("SP-DUST", "Stone Dust stockpile", "WH-SAND", "AGG-DUST"), ("SP-0510", "5-10mm stockpile", "WH-AGG", "AGG-0510"),
              ("SP-1020", "10-20mm stockpile", "WH-AGG-1020", "AGG-1020"), ("SP-2040", "20-40mm stockpile", "WH-FIN", "AGG-2040"),
              ("SP-CRUN", "Crusher Run stockpile", "WH-FIN-CRUN", "AGG-CRUN"), ("SP-MSAND", "Manufactured Sand stockpile", "WH-SAND-MS", "AGG-MSAND"),
              ("SP-REJ", "Rejects holding", "WH-REJ", "AGG-REJ")]
LINE1 = [  # stockpile, product, size, role, split %, market value (allocation weight)
    ("SP-2040", "AGG-2040", "20-40mm", "main", 20, 65000), ("SP-1020", "AGG-1020", "10-20mm", "co_product", 35, 68000),
    ("SP-0510", "AGG-0510", "5-10mm", "co_product", 20, 72000), ("SP-DUST", "AGG-DUST", "0-5mm", "co_product", 15, 38000),
    ("SP-CRUN", "AGG-CRUN", "All-in", "co_product", 5, 42000), ("SP-REJ", "AGG-REJ", "Reject", "waste", 5, None)]
LINE2 = [("SP-MSAND", "AGG-MSAND", "0-4mm washed", "main", 62, 58000), ("SP-DUST", "AGG-DUST", "0-5mm", "co_product", 30, 38000),
         ("SP-REJ", "AGG-REJ", "Slimes / reject", "waste", 8, None)]
PLANTS = {
    "CP-1": ("Main Crushing Line (Primary + Secondary + Screening)", 60, [
        ("Primary Crusher", "primary", 60, [("JAW-1", "Primary Jaw Crusher", "jaw_crusher", 60, 110)]),
        ("Secondary Crusher", "secondary", 60, [("CONE-1", "Secondary Cone Crusher", "cone_crusher", 60, 160)]),
        ("Screening Plant", "screening", 60, [("SCR-1", "Triple-deck Screen", "screen", 60, 22)])]),
    "CP-2": ("Sand & Washing Line", 25, [
        ("VSI Sand Crusher", "tertiary", 25, [("VSI-2", "VSI Sand Maker", "vsi_crusher", 25, 132)]),
        ("Washing Plant", "washing", 25, [("WSH-2", "Sand Screw Washer", "washer", 25, 30)])]),
}


def stockpile_and_plant():
    # One inventory location can back one stockpile only, so warehouses holding two fractions get a second bay.
    for code, name in (("WH-AGG-1020", "Crushed Aggregates Warehouse - 10-20mm bay"),
                       ("WH-FIN-CRUN", "Finished Products Warehouse - Crusher Run bay"),
                       ("WH-SAND-MS", "Sand & Fines Warehouse - Manufactured Sand bay")):
        ensure("location", code, "/inventory/locations", "/inventory/locations",
               {"code": code, "name": name, "type": "warehouse", "branchId": ids("branch")["GR-QRY"]},
               params={"pageSize": 200})
    sp = ids("outpile")
    for code, name, lc, _ in STOCKPILES:
        if code not in sp:
            sp[code] = A.post("/quarry/stockpiles", {
                "siteId": ids("site")["PIT-A"], "code": code, "name": name, "kind": "product",
                "locationId": ids("location")[lc], "capacityT": 30000, "notes": "DEMO"})["id"]
            save()
    pl = ids("plant")
    for code, (name, cap, stages) in PLANTS.items():
        if code not in pl:
            pl[code] = A.post("/manufacturing/crushing/plants", {
                "code": code, "name": name + " (DEMO)", "siteId": ids("site")["PIT-A"],
                "feedStockpileId": ids("stockpile")["PIT-A"], "designCapacityTph": cap, "dieselCostPerLitre": 5400,
                "electricityCostPerKwh": 780, "labourRatePerHour": 9000, "notes": "DEMO",
                "stages": [{"name": s, "kind": k, "capacityTph": c, "units": [
                    {"code": u, "name": n, "kind": kd, "capacityTph": cc, "powerKw": kw, "meterKind": "both"}
                    for u, n, kd, cc, kw in us]} for s, k, c, us in stages]})["id"]
            save()


def profiles():
    pr, p, sp = ids("profile"), ids("product"), ids("outpile")
    for code, plant, lines, name in (("GP-1", "CP-1", LINE1, "Granite aggregate split"), ("GP-2", "CP-2", LINE2, "Manufactured sand split")):
        if code in pr:
            continue
        stage = len(PLANTS[plant][2])
        made = A.post("/manufacturing/crushing/grading-profiles", {
            "plantId": ids("plant")[plant], "code": code, "name": name, "feedProductId": p["RAW-GRANITE"],
            "allocationMethod": "market_value", "notes": "DEMO",
            "lines": [{"productId": p[pc], "sizeLabel": size, "role": role, "stageSequence": stage, "expectedPct": pct,
                       "stocked": True, "stockpileId": sp[s], "valuation": "allocated" if mv else "zero",
                       "marketValue": mv} for s, pc, size, role, pct, mv in lines]})
        A.post("/manufacturing/crushing/grading-profiles/%s/activate" % made["id"])
        pr[code] = made["id"]
        save()


def run_body(plant, profile, lines, day, shift, pit, feed, op, down, reason, notes, rng, wear=1.0):
    pdata = A.get("/manufacturing/crushing/plants/" + ids("plant")[plant])
    prof = A.get("/manufacturing/crushing/grading-profiles/" + ids("profile")[profile])
    by_product = {x["productId"]: x["id"] for x in prof["lines"]}
    # Actual split varies around the expected one; the waste line absorbs the rounding, so outputs = feed exactly.
    actual = [round(feed * pct / 100 * rng.uniform(0.93, 1.07), 3) for _, _, _, _, pct, _ in lines[:-1]]
    actual.append(round(feed - sum(actual), 3))
    outs = [{"gradingLineId": by_product[ids("product")[l[1]]], "actualTonnes": t} for l, t in zip(lines, actual)]
    meters = []
    for st in pdata["stages"]:
        for u in st["units"]:
            kw = float(u.get("powerKw") or 0)
            meters.append({"unitId": u["id"], "runHours": op, "kwh": round(kw * op * 0.72, 2),
                           "dieselLitres": round(op * 14, 2) if u["kind"] in ("jaw_crusher", "vsi_crusher") else 0})
    first = pdata["stages"][0]["units"][0]["id"]
    return {"plantId": ids("plant")[plant], "profileId": ids("profile")[profile], "runDate": day.isoformat(),
            "shift": shift, "operatorName": "Rose Akello (supervisor)", "feedStockpileId": ids("stockpile")[pit],
            "feedTonnes": feed, "feedMethod": "belt_scale", "outputMethod": "belt_scale", "operatingHours": op,
            "downtimeHours": down, "downtimeReason": reason, "labourHours": round((op + down) * 6, 1),
            "notes": ("DEMO. Feed from %s. " % PITS[pit][0]) + (notes or ""), "outputs": outs, "meters": meters,
            "costs": [{"kind": "wear_parts", "unitId": first, "amount": round(feed * 1100 * wear), "description": "Jaw and mantle wear"},
                      {"kind": "maintenance", "amount": round(feed * 450), "description": "Routine plant maintenance"},
                      {"kind": "other", "amount": round(feed * 350), "description": "Production overheads"}]}


def post_run(key, body):
    def go():
        k = key + "|" + body["feedStockpileId"]
        made = A.post("/manufacturing/crushing/runs", body, key=k)
        A.post("/manufacturing/crushing/runs/%s/post" % made["id"], key=k + "-post")
        return {"id": made["id"], "code": made.get("code"), "day": body["runDate"], "feed": body["feedTonnes"],
                "pit": body["notes"]}
    return once(key, go)


ROM_RESERVE = 400  # every pad keeps ROM stock, so inventory screens show raw material on hand


def set_product_costs():
    """The sales margin reports use the product's current cost price: set it from the crushing cost report."""
    report = A.get("/manufacturing/crushing/reports/cost-per-tonne")
    for row in report.get("products", []):
        code = row.get("productCode")
        if code in ids("product") and float(row.get("costPerTonne") or 0) > 0:
            A.patch("/products/" + ids("product")[code], {"costPrice": round(float(row["costPerTonne"]), 2)},
                    key="cost-%s-%s" % (code, row["costPerTonne"]))


def pick_pit(balance, key):
    """Finished runs replay the plan; a new run takes the pit that really holds the most ROM stock right now."""
    if key in ids("steps"):
        return max(balance, key=balance.get)
    stock = {"PIT-" + x["stockpileCode"][-1]: float(x["onHandTonnes"]) for x in rows(A.get("/quarry/rom-stock"))
             if x.get("stockpileCode", "").startswith("ROM-")}
    for k in balance:
        balance[k] = stock.get(k, 0.0)
    return max(balance, key=balance.get)


def runs():
    rng = random.Random(77)
    sched = schedule()
    days = working_days()
    balance = {k: 0.0 for k in PITS}
    for idx, d in enumerate(days):
        for pit in PITS:
            _, g, o = sched[pit][idx]
            balance[pit] += g - o
        if idx == 0:
            continue  # first day builds up ROM stock
        for n, shift in enumerate(("morning", "afternoon", "night")):
            if shift == "night" and d.weekday() % 3:
                continue
            feed = float(rng.randrange(240, 320, 10))
            pit = pick_pit(balance, "run-1-%s-%s" % (d, shift))
            down = round(rng.choice([0, 0, 0.5, 0.5, 1, 1.5]), 1)
            op = round(max(4.0, feed / 52.0), 1)
            reason = "Feeder chute blockage" if down >= 1 else ("Screen deck cleaning" if down else None)
            if balance[pit] < feed + ROM_RESERVE:
                continue
            balance[pit] -= feed
            post_run("run-1-%s-%s" % (d, shift), run_body("CP-1", "GP-1", LINE1, d, shift, pit, feed, op, down, reason, None, rng))
        if idx % 2 == 0:
            feed = float(rng.randrange(90, 130, 10))
            pit = pick_pit(balance, "run-2-%s" % d)
            if balance[pit] >= feed + ROM_RESERVE:
                balance[pit] -= feed
                post_run("run-2-%s" % d, run_body("CP-2", "GP-2", LINE2, d, "morning", pit, feed, 5.5, 0.5, "Washer pump priming", None, rng))
    print("  ROM left after runs:", {k: round(v) for k, v in balance.items()})


def scenarios():
    """Named batches: the brief's 1,000 MT sample split, and the 500 planned / 380 actual downtime run."""
    pl = ids("plant")["CP-1"]
    prof = A.get("/manufacturing/crushing/grading-profiles/" + ids("profile")["GP-1"])
    by = {x["productId"]: x["id"] for x in prof["lines"]}
    p = ids("product")
    rng = random.Random(5)
    day = TODAY - dt.timedelta(days=9)
    b = run_body("CP-1", "GP-1", LINE1, day, "day", "PIT-B", 1000.0, 20.0, 1.0, "Planned conveyor belt check",
                 "Sample campaign batch: 1,000 MT in, 150/200/350/200/50/50 MT out.", rng)
    b["outputs"] = [{"gradingLineId": by[p[c]], "actualTonnes": t} for c, t in (
        ("AGG-DUST", 150), ("AGG-0510", 200), ("AGG-1020", 350), ("AGG-2040", 200), ("AGG-CRUN", 50), ("AGG-REJ", 50))]
    post_run("run-scn-1000", b)
    day2 = TODAY - dt.timedelta(days=5)
    b2 = run_body("CP-1", "GP-1", LINE1, day2, "afternoon", "PIT-A", 380.0, 6.3, 2.0, "Secondary crusher mantle failure",
                  "PLANNED 500 MT, ACTUAL 380 MT: 120 MT short because of 2.0 h downtime.", rng, wear=1.6)
    post_run("run-scn-downtime", b2)


def run():
    # The run plan reads live ROM stock, so a finished phase must not be replayed: it would add runs it skipped before.
    if ids("steps").get("crushing-complete"):
        print("  already complete")
        return
    stockpile_and_plant()
    profiles()
    scenarios()
    runs()
    set_product_costs()
    once("crushing-complete", lambda: True)
    print("  runs", len([k for k in ids("steps") if k.startswith("run-")]))

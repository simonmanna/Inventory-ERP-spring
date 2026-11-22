"""CRM demo for one organisation, through the running API only (no SQL).

Usage:  CRM_PASSWORD='<admin password>' python3 seed.py --confirm DEMO
Back up the database first. Safe to re-run: lookups, the team and customers are matched by name; deals, activities,
quotations and cases are recorded in state.<ORG>.json and not repeated (delete that file only together with the data).
Environment: ERP_BASE (default http://127.0.0.1:3001/api/v1, localhost only), CRM_ORG (DEMO),
CRM_EMAIL (admin@demo.test), CRM_PASSWORD (Admin@123, the local seed default).

What it makes (a granite / stone-works sales team): sources, tags, lost reasons, one sales team with a monthly target,
six fictional prospect customers, ~14 opportunities across every stage (some won, two lost), six leads (one converted),
planned and completed activities (overdue / today / upcoming), three quotations and three customer cases.
"""
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from datetime import date, datetime, timedelta, timezone
from urllib.parse import urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("ERP_BASE", "http://127.0.0.1:3001/api/v1")
ORG = os.environ.get("CRM_ORG", "DEMO")
EMAIL = os.environ.get("CRM_EMAIL", "admin@demo.test")
PASSWORD = os.environ.get("CRM_PASSWORD", "Admin@123")
STATE_FILE = os.path.join(HERE, f"state.{ORG}.json")
STATE = json.load(open(STATE_FILE)) if os.path.exists(STATE_FILE) else {}
TOKEN = None


def log(msg):
    print(msg, flush=True)


def save():
    with open(STATE_FILE, "w") as f:
        json.dump(STATE, f, indent=1, sort_keys=True)


def call(method, path, body=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                                 method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Idempotency-Key", str(uuid.uuid4()))
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=120) as res:
            txt = res.read().decode()
            return json.loads(txt) if txt else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} -> {e.code}: {e.read().decode()[:700]}")


def rows(data):
    return data if isinstance(data, list) else (data.get("data") or data.get("rows") or data.get("items") or [])


def day(offset):
    return (date.today() + timedelta(days=offset)).isoformat()


def at(days, hour=10):
    """ISO instant `days` from today at `hour`:00 UTC (negative = past)."""
    d = datetime.now(timezone.utc).replace(hour=hour, minute=0, second=0, microsecond=0) + timedelta(days=days)
    return d.isoformat().replace("+00:00", "Z")


def once(key, fn):
    """Runs fn() once per state key; returns the recorded id."""
    if key not in STATE:
        STATE[key] = fn()
        save()
        log(f"  + {key}")
    return STATE[key]


# ---------------------------------------------------------------- data

SOURCES = ["Website", "Referral", "Walk-in showroom", "Trade fair", "Architect"]
TAGS = [("Hotel", "violet"), ("Residential", "emerald"), ("Commercial", "sky"), ("Government", "amber"),
        ("Repeat buyer", "rose")]
LOST_REASONS = ["Price too high", "Chose a competitor", "Project postponed"]

CUSTOMERS = [  # fictional prospects
    ("Victoria Bay Hotel Ltd", "procurement@victoriabay.test", "+256 700 100 201"),
    ("Greenhill Residences", "info@greenhill.test", "+256 700 100 202"),
    ("Mukono Builders Ltd", "orders@mukonobuilders.test", "+256 700 100 203"),
    ("Kampala Office Park", "facilities@kop.test", "+256 700 100 204"),
    ("Jinja Riverside Lodge", "manager@riverside.test", "+256 700 100 205"),
    ("Ntinda Family Homes", "sales@ntindahomes.test", "+256 700 100 206"),
]

# key, name, customer, amount (UGX), stage index (0 New .. 3 Negotiation), owner, source, tags, priority, close in days
OPPS = [
    ("o-acme-kitchen", "Granite countertops — HQ staff kitchen", "Acme Retail Ltd", 18_500_000, 0, "admin", "Referral", ["Commercial", "Repeat buyer"], 3, 12),
    ("o-acme-stairs", "Stair treads & skirting — new branch", "Acme Retail Ltd", 9_600_000, 2, "manager", "Referral", ["Commercial", "Repeat buyer"], 2, 6),
    ("o-vb-lobby", "Lobby floor — Imperial Red tiles 240 m²", "Victoria Bay Hotel Ltd", 42_000_000, 1, "admin", "Architect", ["Hotel"], 3, 25),
    ("o-vb-bath", "Guest bathroom vanities (48 rooms)", "Victoria Bay Hotel Ltd", 61_000_000, 3, "manager", "Architect", ["Hotel"], 3, 9),
    ("o-gh-phase2", "Phase 2 kitchens — 24 apartments", "Greenhill Residences", 54_000_000, 2, "supervisor", "Referral", ["Residential"], 2, 30),
    ("o-gh-steps", "Entrance steps Kuppam Green", "Greenhill Residences", 7_800_000, 0, "supervisor", "Referral", ["Residential"], 1, 40),
    ("o-mb-slabs", "Slab supply — Mukono estate", "Mukono Builders Ltd", 33_500_000, 1, "manager", "Trade fair", ["Residential"], 2, 18),
    ("o-kop-reception", "Reception desks & cladding", "Kampala Office Park", 27_000_000, 3, "admin", "Website", ["Commercial"], 2, 4),
    ("o-kop-polish", "Annual floor re-polishing contract", "Kampala Office Park", 12_000_000, 0, "supervisor", "Website", ["Commercial"], 1, 55),
    ("o-jrl-terrace", "Terrace paving Silver Grey", "Jinja Riverside Lodge", 22_400_000, 1, "admin", "Walk-in showroom", ["Hotel"], 2, 21),
]
WON = [  # key, name, customer, amount, owner, source, tags
    ("w-acme-tiles", "Showroom floor tiles", "Acme Retail Ltd", 14_200_000, "admin", "Referral", ["Commercial", "Repeat buyer"]),
    ("w-nfh-kitchens", "Show-house kitchens (3 units)", "Ntinda Family Homes", 19_750_000, "manager", "Walk-in showroom", ["Residential"]),
    ("w-jrl-bar", "Bar counter top — Absolute Black", "Jinja Riverside Lodge", 6_300_000, "supervisor", "Referral", ["Hotel"]),
]
LOST = [  # key, name, customer, amount, owner, reason, note
    ("l-mb-tiles", "Show-flat floor tiles", "Mukono Builders Ltd", 11_000_000, "manager", "Price too high", "Went with ceramic tiles at half the price."),
    ("l-nfh-stairs", "Stair cladding phase 1", "Ntinda Family Homes", 8_900_000, "supervisor", "Project postponed", "Client paused phase 1 until next year."),
]
LEADS = [  # key, name, company, contact, phone, amount, source, tags, owner
    ("ld-school", "New dormitory washrooms", "Mbarara Hill School", "Mr. Tumusiime", "+256 772 300 401", 24_000_000, "Website", ["Government"], "admin"),
    ("ld-clinic", "Clinic reception & floors", "Bukoto Medical Centre", "Dr. Achieng", "+256 772 300 402", 16_500_000, "Referral", ["Commercial"], "manager"),
    ("ld-villa", "Villa kitchen & bathrooms", None, "Mrs. Nankya", "+256 772 300 403", 13_000_000, "Walk-in showroom", ["Residential"], "supervisor"),
    ("ld-church", "Altar and memorial plaques", "St. Kizito Parish", "Fr. Ssemwogerere", "+256 772 300 404", 5_500_000, "Referral", [], "admin"),
    ("ld-mall", "Food-court counters", "Lakeside Mall Developers", "Ms. Auma", "+256 772 300 405", 38_000_000, "Trade fair", ["Commercial"], "manager"),
    ("ld-hotel2", "Spa floors and walls", "Sunset Spa & Resort", "Mr. Okot", "+256 772 300 406", 29_000_000, "Architect", ["Hotel"], "admin"),
]
# opportunity key, type, title, due days, owner, done outcome (None = still planned)
ACTIVITIES = [
    ("o-vb-bath", "meeting", "Site visit — measure vanity tops", -2, "manager", None),
    ("o-kop-reception", "call", "Confirm slab colour with facilities manager", 0, "admin", None),
    ("o-acme-kitchen", "call", "Send Baltic Brown vs Steel Grey samples", 1, "admin", None),
    ("o-vb-lobby", "email", "Send revised quotation with sealing", 3, "admin", None),
    ("o-gh-phase2", "meeting", "Present kitchen layouts to developer", 5, "supervisor", None),
    ("o-mb-slabs", "task", "Check slab stock for 3 trucks", -1, "manager", None),
    ("o-jrl-terrace", "call", "Follow up on terrace sample", 7, "admin", None),
    ("o-acme-stairs", "meeting", "Showroom visit with branch manager", -6, "manager", "Liked Kuppam Green; asked for quote"),
    ("o-vb-lobby", "call", "Intro call with project architect", -10, "admin", "Needs 240 m² by end of next month"),
    ("o-gh-phase2", "email", "Sent catalogue and price list", -8, "supervisor", "Shortlisted 3 colours"),
]


# ---------------------------------------------------------------- steps

def main():
    global TOKEN
    if urlparse(BASE).hostname not in ("localhost", "127.0.0.1"):
        raise SystemExit(f"Refusing non-local target {BASE}. Run on the server itself.")
    if len(sys.argv) < 3 or sys.argv[1] != "--confirm" or sys.argv[2] != ORG:
        raise SystemExit(f"Pass --confirm {ORG} to write CRM demo data to tenant {ORG} on {BASE}.")
    TOKEN = call("POST", "/auth/login", {"organizationCode": ORG, "email": EMAIL, "password": PASSWORD})["accessToken"]

    users = {u.get("email"): u["id"] for u in rows(call("GET", "/users?pageSize=200"))}
    owner = {"admin": users[EMAIL],
             "manager": users.get(f"manager@{EMAIL.split('@')[1]}", users[EMAIL]),
             "supervisor": users.get(f"mary@{EMAIL.split('@')[1]}", users[EMAIL])}

    log("== lookups")
    stages = [s for s in call("GET", "/crm/config/stages") if s.get("isActive") and not s.get("isWon")]
    lk = call("GET", "/crm/config/lookups")
    have = {k: {r["name"]: r["id"] for r in lk.get(k, [])} for k in ("sources", "tags", "lostReasons")}
    for name in SOURCES:
        if name not in have["sources"]:
            have["sources"][name] = call("POST", "/crm/config/sources", {"name": name})["id"]
    for name, color in TAGS:
        if name not in have["tags"]:
            have["tags"][name] = call("POST", "/crm/config/tags", {"name": name, "color": color})["id"]
    for name in LOST_REASONS:
        if name not in have["lostReasons"]:
            have["lostReasons"][name] = call("POST", "/crm/config/lost-reasons", {"name": name})["id"]
    teams = {t["name"]: t["id"] for t in lk.get("teams", [])}
    team = teams.get("Stone Works Sales") or call("POST", "/crm/config/teams", {
        "name": "Stone Works Sales", "leaderId": owner["admin"], "memberIds": sorted(set(owner.values())),
        "monthlyTarget": 120_000_000, "color": "sky"})["id"]

    log("== customers")
    partners = {p["name"]: p["id"] for p in rows(call("GET", "/partners?pageSize=200"))}
    for name, email, phone in CUSTOMERS:
        if name not in partners:
            partners[name] = call("POST", "/partners", {"name": name, "isCompany": True, "isCustomer": True,
                                                        "email": email, "phone": phone})["id"]

    def opp(key, name, customer, amount, stage, who, source, tags, priority, close):
        body = {"name": name, "partnerId": partners[customer], "amount": amount, "ownerId": owner[who],
                "teamId": team, "sourceId": have["sources"][source], "priority": priority,
                "tagIds": [have["tags"][t] for t in tags], "expectedClose": day(close),
                "stageId": stages[min(stage, len(stages) - 1)]["id"]}
        return call("POST", "/crm/opportunities", body)["id"]

    log("== opportunities")
    for o in OPPS:
        once(o[0], lambda o=o: opp(*o))
    for key, name, customer, amount, who, source, tags in WON:
        def won(key=key, name=name, customer=customer, amount=amount, who=who, source=source, tags=tags):
            oid = opp(key, name, customer, amount, len(stages) - 1, who, source, tags, 2, -3)
            call("POST", f"/crm/opportunities/{oid}/won", {})
            return oid
        once(key, won)
    for key, name, customer, amount, who, reason, note in LOST:
        def lost(key=key, name=name, customer=customer, amount=amount, who=who, reason=reason, note=note):
            oid = opp(key, name, customer, amount, 1, who, "Website", [], 1, -5)
            call("POST", f"/crm/opportunities/{oid}/lost", {"lostReasonId": have["lostReasons"][reason], "lostNote": note})
            return oid
        once(key, lost)

    log("== leads")
    for key, name, company, contact, phone, amount, source, tags, who in LEADS:
        once(key, lambda: call("POST", "/crm/leads", {
            "name": name, "companyName": company, "contactName": contact, "phone": phone, "amount": amount,
            "ownerId": owner[who], "teamId": team, "sourceId": have["sources"][source],
            "tagIds": [have["tags"][t] for t in tags]})["id"])
    # The hotel spa lead qualified: convert it, creating the customer.
    once("ld-hotel2-converted", lambda: call("POST", f"/crm/leads/{STATE['ld-hotel2']}/convert",
                                             {"createCustomer": True, "stageId": stages[1]["id"]})["id"])

    log("== activities")
    for i, (key, typ, title, due, who, outcome) in enumerate(ACTIVITIES):
        def act(key=key, typ=typ, title=title, due=due, who=who, outcome=outcome):
            a = call("POST", "/crm/planner", {"dealId": STATE[key], "type": typ, "title": title,
                                              "dueAt": at(due, 9 + (len(title) % 7)), "assignedToId": owner[who]})
            if outcome:
                call("POST", f"/crm/planner/{a['id']}/done", {"outcome": outcome})
            return a["id"]
        once(f"act-{i}", act)

    log("== quotations")
    quotes = [("q-vb-lobby", "o-vb-lobby", "Victoria Bay Hotel Ltd", [
                  ("Imperial Red granite floor tiles 600x600x20 mm, polished", 240, 160_000),
                  ("Sealing and treatment", 240, 12_000), ("Delivery & offloading — Entebbe", 1, 1_200_000)]),
              ("q-kop-reception", "o-kop-reception", "Kampala Office Park", [
                  ("Absolute Black reception desk top, 20 mm, bullnose edge", 2, 6_800_000),
                  ("Wall cladding Silver Grey 600x300", 85, 140_000), ("Installation", 1, 1_500_000)]),
              ("q-acme-stairs", "o-acme-stairs", "Acme Retail Ltd", [
                  ("Kuppam Green stair treads 1200x350", 18, 320_000), ("Granite skirting 100 mm", 40, 55_000)])]
    for key, deal, customer, lines in quotes:
        once(key, lambda deal=deal, customer=customer, lines=lines: call("POST", "/crm/quotations", {
            "partnerId": partners[customer], "dealId": STATE[deal], "validUntil": day(30),
            "lines": [{"description": d, "quantity": q, "unitPrice": p} for d, q, p in lines]})["id"])
    once("q-acme-stairs-sent", lambda: call("POST", f"/crm/quotations/{STATE['q-acme-stairs']}/submit", None)["id"])

    log("== cases")
    cases = [("c-acme-chip", "Acme Retail Ltd", "complaint", "quality", "high",
              "Chipped edge on delivered countertop", "One countertop arrived with a 3 cm chip on the front edge."),
             ("c-jrl-delay", "Jinja Riverside Lodge", "complaint", "delivery", "normal",
              "Bar top delivery two days late", "Truck arrived Thursday instead of Tuesday; installer was idle."),
             ("c-nfh-reseal", "Ntinda Family Homes", "service_request", "service", "low",
              "Re-seal show-house kitchen tops", "Request to re-seal before the open day.")]
    for key, customer, ctype, cat, prio, subject, desc in cases:
        once(key, lambda customer=customer, ctype=ctype, cat=cat, prio=prio, subject=subject, desc=desc: call(
            "POST", "/crm/cases", {"partnerId": partners[customer], "caseType": ctype, "category": cat,
                                    "priority": prio, "subject": subject, "description": desc})["id"])
    log("done")


if __name__ == "__main__":
    main()

"""Quality-control demo (ADR-S087) for one organisation, through the running API only (no SQL).

Usage:  QC_PASSWORD='<admin password>' python3 seed.py --confirm DEMO
Back up the database first. Safe to re-run: teams, the worksheet template, control points, tags and root causes are
matched by name; checks and alerts are recorded in state.<ORG>.json and not repeated (delete that file only together
with the data). Environment: ERP_BASE (default http://127.0.0.1:3001/api/v1, localhost only), QC_ORG (DEMO),
QC_EMAIL (admin@demo.test), QC_PASSWORD (Admin@123, the local seed default).

What it makes (a concrete-block plant): two quality teams, a curing worksheet, six control points on block / paver
products (strength and height measures with SPC, a blocking visual check, a curing worksheet, a delivery photo and a
cement-bag receipt check), ~100 executed checks with realistic measurements and failures (control charts, Pareto),
a few pending checks for the operator queue, tags, root causes and five quality alerts across the stages, one of
them escalated to an NCR. Execution dates are the run date (the API sets them), so the trend shows one period.
"""
import json
import os
import random
import sys
import urllib.error
import urllib.request
import uuid
from urllib.parse import quote, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("ERP_BASE", "http://127.0.0.1:3001/api/v1")
ORG = os.environ.get("QC_ORG", "DEMO")
EMAIL = os.environ.get("QC_EMAIL", "admin@demo.test")
PASSWORD = os.environ.get("QC_PASSWORD", "Admin@123")
STATE_FILE = os.path.join(HERE, f"state.{ORG}.json")
STATE = json.load(open(STATE_FILE)) if os.path.exists(STATE_FILE) else {}
TOKEN = None
QC = "/quality-control"


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


def once(key, fn):
    """Runs fn() once per state key; returns the recorded value."""
    if key not in STATE:
        STATE[key] = fn()
        save()
        log(f"  + {key}")
    return STATE[key]


def by_name(items, name, field="name"):
    return next((i for i in items if (i.get(field) or "").lower() == name.lower()), None)


# ---------------------------------------------------------------- lookups

def products():
    """Block / paver products (and a cement product for receipts); falls back to the first stockable products."""
    found = {}
    for q in ("block", "paver", "brick", "kerb", "slab"):
        for p in call("GET", f"/products/search?q={quote(q)}&pageSize=10") or []:
            if p.get("isActive", True):
                found.setdefault(p["id"], p)
    blocks = list(found.values())[:4]
    if not blocks:
        blocks = [p for p in rows(call("GET", "/products?pageSize=50&productType=stockable"))][:3]
    if not blocks:
        raise SystemExit("No products in this tenant to attach control points to.")
    cement = next(iter(call("GET", "/products/search?q=cement&pageSize=5") or []), None)
    return blocks, cement


def failure_classes():
    classes = call("GET", f"{QC}/failure-classes")
    pick = {}
    for key, words in {"strength": ("strength",), "dimensional": ("dimension",), "material": ("material",),
                       "moisture": ("moisture",), "packaging": ("packag",), "process": ("process",)}.items():
        pick[key] = next((c["id"] for c in classes if any(w in c["name"].lower() for w in words)), classes[0]["id"])
    return pick


# ---------------------------------------------------------------- configuration

def team(name, description, members):
    existing = by_name(call("GET", f"{QC}/teams"), name)
    if existing:
        return existing["id"]
    return call("POST", f"{QC}/teams", {"name": name, "description": description, "memberUserIds": members})["id"]


def worksheet():
    name = "Block curing checklist"
    existing = by_name(rows(call("GET", f"{QC}/worksheet-templates")), name)
    if existing:
        return existing["id"]
    return call("POST", f"{QC}/worksheet-templates", {
        "name": name, "description": "Checked when a curing rack is released.",
        "fields": [
            {"key": "curing_hours", "label": "Curing time (hours)", "type": "number", "required": True,
             "min": 24, "decimals": 0},
            {"key": "sprayed", "label": "Blocks sprayed twice a day", "type": "checkbox", "required": True,
             "expected": True},
            {"key": "surface", "label": "Surface finish", "type": "select", "required": True,
             "options": ["Smooth", "Slightly rough", "Honeycombed"], "passValues": ["Smooth", "Slightly rough"]},
            {"key": "remarks", "label": "Remarks", "type": "text", "maxLength": 500},
        ]})["id"]


def point(title, body):
    existing = by_name(rows(call("GET", f"{QC}/control-points?search={quote(title)}&pageSize=50")), title, "title")
    if existing:
        return existing["id"]
    return call("POST", f"{QC}/control-points", {"title": title, **body})["id"]


def lookup(path, name, body):
    existing = by_name(call("GET", path), name)
    return existing["id"] if existing else call("POST", path, {"name": name, **body})["id"]


# ---------------------------------------------------------------- checks

def check(key, body, execute=None):
    """A manual check (linked to a control point), executed once when {@code execute} is given."""
    def make():
        c = call("POST", f"{QC}/checks", body)
        if execute is not None:
            c = call("POST", f"{QC}/checks/{c['id']}/execute", execute)
        return {"id": c["id"], "number": c["checkNumber"], "status": c["status"]}
    return once(key, make)


def main():
    global TOKEN
    if urlparse(BASE).hostname not in ("localhost", "127.0.0.1"):
        raise SystemExit(f"Refusing non-local target {BASE}. Run on the server itself.")
    if len(sys.argv) < 3 or sys.argv[1] != "--confirm" or sys.argv[2] != ORG:
        raise SystemExit(f"Pass --confirm {ORG} to write quality-control demo data to tenant {ORG} on {BASE}.")
    TOKEN = call("POST", "/auth/login", {"organizationCode": ORG, "email": EMAIL, "password": PASSWORD})["accessToken"]
    rnd = random.Random(20261124)  # same data on every tenant

    users = {u.get("email"): u["id"] for u in rows(call("GET", "/users?pageSize=200"))}
    admin = users[EMAIL]
    manager = users.get(f"manager@{EMAIL.split('@')[1]}", admin)
    blocks, cement = products()
    block_ids = [p["id"] for p in blocks]
    main_product = blocks[0]
    log(f"== products: {', '.join(p['name'] for p in blocks)}" + (f"; cement: {cement['name']}" if cement else ""))
    fc = failure_classes()

    log("== teams and worksheet")
    plant_team = team("Block Plant QC", "Production quality on the block and paver lines.", sorted({admin, manager}))
    receiving_team = team("Receiving Inspection", "Incoming cement, sand and aggregate.", [admin])
    sheet = worksheet()

    log("== control points")
    strength = point("Compressive strength (7-day)", {
        "operation": "manufacturing", "checkType": "measure", "productIds": block_ids, "teamId": plant_team,
        "responsibleUserId": manager, "instructions": "Crush three blocks from the batch after 7 days of curing and"
        " record each result as a sample (MPa).", "failureMessage": "Hold the batch, inform the plant manager and"
        " re-test at 14 days.", "failureClassRequired": True, "norm": 7.0, "toleranceMin": 5.5, "toleranceMax": 9.0,
        "unit": "MPa", "decimals": 1, "blocking": False, "blockOnFailure": True, "autoAlert": True,
        "spcSubgroup": "individual"})
    height = point("Block height", {
        "operation": "manufacturing", "checkType": "measure", "productIds": block_ids, "teamId": plant_team,
        "instructions": "Measure five consecutive blocks off the press with the calliper.", "norm": 200,
        "toleranceMin": 197, "toleranceMax": 203, "unit": "mm", "decimals": 1, "failureClassRequired": True,
        "blocking": False, "autoAlert": True, "spcSubgroup": "consecutive", "spcSubgroupSize": 5})
    visual = point("Visual: cracks, chips and edges", {
        "operation": "manufacturing", "checkType": "pass_fail", "productIds": block_ids, "teamId": plant_team,
        "instructions": "Inspect a full pallet: no through-cracks, chipped corners under 10 mm, straight arrises.",
        "failureMessage": "Set the pallet aside for re-sorting.", "failureClassRequired": True, "blocking": True,
        "autoAlert": True})
    curing = point("Curing checklist", {
        "operation": "work_order", "checkType": "worksheet", "productIds": block_ids, "teamId": plant_team,
        "worksheetTemplateId": sheet, "blocking": False, "autoAlert": True})
    point("Load photo before dispatch", {
        "operation": "delivery", "checkType": "picture", "productIds": block_ids, "teamId": plant_team,
        "instructions": "Photograph the loaded truck showing pallets strapped and wrapped.", "blocking": False,
        "autoAlert": False})
    if cement:
        point("Cement bag weight", {
            "operation": "receipt", "checkType": "measure", "productIds": [cement["id"]], "teamId": receiving_team,
            "instructions": "Weigh 5 bags at random from the delivery.", "norm": 50, "toleranceMin": 49.5,
            "toleranceMax": 50.5, "unit": "kg", "decimals": 2, "receiptMode": "pre_receipt", "frequency": "random",
            "randomPct": 25, "blocking": False, "autoAlert": True})

    def batch(i):
        return f"B-2611-{i // 3 + 1:02d}"

    log("== strength measurements (SPC)")
    failed_strength = []
    for i in range(36):
        v = round(rnd.gauss(7.2, 0.45), 1)
        if i in (17, 29):
            v = 5.1 if i == 17 else 5.3
        ok = 5.5 <= v <= 9.0
        c = check(f"strength:{i}", {"controlPointId": strength, "checkType": "measure", "productId": main_product["id"],
                                    "batchNumber": batch(i), "includeInSpc": True, "teamId": plant_team,
                                    "title": f"Compressive strength — {batch(i)}"},
                  {"measureValue": v, **({} if ok else {"failureClassId": fc["strength"]})})
        if not ok:
            failed_strength.append(c)

    log("== height measurements (SPC, subgroups of 5)")
    failed_height = []
    for i in range(30):
        v = round(rnd.gauss(200.2, 0.9), 1)
        if i == 22:
            v = 203.6
        ok = 197 <= v <= 203
        c = check(f"height:{i}", {"controlPointId": height, "checkType": "measure", "productId": blocks[-1]["id"],
                                  "batchNumber": batch(i), "includeInSpc": True, "teamId": plant_team,
                                  "title": f"Block height — {batch(i)}"},
                  {"measureValue": v, **({} if ok else {"failureClassId": fc["dimensional"]})})
        if not ok:
            failed_height.append(c)

    log("== visual inspections")
    reasons = {3: "dimensional", 8: "dimensional", 11: "strength", 15: "material", 19: "dimensional",
               21: "process"}
    failed_visual = []
    for i in range(24):
        product = blocks[i % len(blocks)]
        cls = reasons.get(i)
        c = check(f"visual:{i}", {"controlPointId": visual, "checkType": "pass_fail", "productId": product["id"],
                                  "batchNumber": batch(i), "teamId": plant_team,
                                  "title": f"Visual inspection — {product['name']} {batch(i)}"},
                  {"result": "failed", "failureClassId": fc[cls], "notes": "Chipped corners on the outer row."}
                  if cls else {"result": "passed"})
        if cls:
            failed_visual.append(c)

    log("== curing worksheets")
    answers = [
        {"curing_hours": 72, "sprayed": True, "surface": "Smooth"},
        {"curing_hours": 48, "sprayed": True, "surface": "Slightly rough"},
        {"curing_hours": 72, "sprayed": True, "surface": "Smooth", "remarks": "Rack 4"},
        {"curing_hours": 30, "sprayed": False, "surface": "Honeycombed", "remarks": "Sprayer broken on night shift"},
        {"curing_hours": 96, "sprayed": True, "surface": "Smooth"},
    ]
    failed_sheet = []
    for i, a in enumerate(answers):
        c = check(f"curing:{i}", {"controlPointId": curing, "checkType": "worksheet", "productId": main_product["id"],
                                  "batchNumber": batch(i * 3), "teamId": plant_team,
                                  "title": f"Curing checklist — rack {i + 1}"},
                  {"worksheetAnswers": a, **({"failureClassId": fc["process"]} if a["surface"] == "Honeycombed"
                                             else {})})
        if c["status"] == "failed":
            failed_sheet.append(c)

    log("== pending checks (operator queue)")
    for i in range(2):
        check(f"pending:strength:{i}", {"controlPointId": strength, "checkType": "measure", "includeInSpc": True,
                                        "productId": main_product["id"], "batchNumber": f"B-2611-{20 + i}",
                                        "teamId": plant_team, "title": f"Compressive strength — B-2611-{20 + i}"})
    check("pending:visual", {"controlPointId": visual, "checkType": "pass_fail", "productId": main_product["id"],
                             "batchNumber": "B-2611-21", "teamId": plant_team,
                             "title": "Visual inspection — B-2611-21"})
    check("pending:curing", {"controlPointId": curing, "checkType": "worksheet", "productId": main_product["id"],
                             "batchNumber": "B-2611-21", "teamId": plant_team, "title": "Curing checklist — rack 6"})

    log("== alert tags and root causes")
    tags = {n: lookup(f"{QC}/tags", n, {"color": c}) for n, c in
            (("Recurring", "#d97706"), ("Customer complaint", "#dc2626"), ("Supplier", "#2563eb"))}
    causes = {n: lookup(f"{QC}/root-causes", n, {"description": d}) for n, d in (
        ("Low cement content", "Mix design below 1:6 or cement short-weighed at the batching plant."),
        ("Worn mould liner", "Mould liner worn past tolerance, producing chipped arrises."),
        ("Curing too short", "Blocks released or stacked before the minimum curing time."),
        ("Under-vibrated mould", "Vibration time or amplitude too low, leaving voids."))}
    stages = {s["name"]: s["id"] for s in call("GET", f"{QC}/alert-stages?active=true")}

    def alert(key, body, stage=None, comment=None, escalate=None):
        def make():
            a = call("POST", f"{QC}/alerts", body)
            if stage and stages.get(stage) and stages[stage] != a["stageId"]:
                call("POST", f"{QC}/alerts/{a['id']}/move", {"stageId": stages[stage], "comment": comment})
            if escalate:
                call("POST", f"{QC}/alerts/{a['id']}/escalate", escalate)
            return a["id"]
        return once(key, make)

    log("== alerts")
    if failed_strength:
        alert("alert:strength", {
            "title": f"Low 7-day strength on {main_product['name']}", "checkId": failed_strength[0]["id"],
            "priority": 3, "teamId": plant_team, "responsibleUserId": manager, "tagIds": [tags["Supplier"]],
            "rootCauseId": causes["Low cement content"],
            "description": "Two batches crushed below 5.5 MPa this month.",
            "correctiveAction": "Re-test the held batches at 14 days; recalibrate the cement weigh hopper.",
            "preventiveAction": "Weekly hopper calibration; check cement bag weights on receipt."},
            stage="Confirmed", comment="Both low results came from the same cement delivery.",
            escalate={"severity": "major", "ownerUserId": manager})
    if failed_visual:
        alert("alert:visual", {
            "title": "Chipped corners on paver pallets", "checkId": failed_visual[0]["id"], "priority": 2,
            "teamId": plant_team, "tagIds": [tags["Recurring"]], "rootCauseId": causes["Worn mould liner"],
            "correctiveAction": "Replace the liner on mould 3."},
            stage="Action Proposed", comment="Liner on mould 3 measured 2 mm worn.")
    if failed_height:
        alert("alert:height", {
            "title": "Block height over tolerance", "checkId": failed_height[0]["id"], "priority": 1,
            "teamId": plant_team, "rootCauseId": causes["Under-vibrated mould"],
            "correctiveAction": "Adjusted tamper head stroke."},
            stage="Solved", comment="Next 20 blocks all within 199–201 mm.")
    if failed_sheet:
        alert("alert:curing", {
            "title": "Rack released after 30 h with broken sprayer", "checkId": failed_sheet[0]["id"], "priority": 2,
            "teamId": plant_team, "rootCauseId": causes["Curing too short"]},
            stage="Confirmed", comment="Night shift confirmed the sprayer fault.")
    alert("alert:customer", {
        "title": "Customer complaint: colour variation between pallets", "priority": 1, "teamId": plant_team,
        "productId": main_product["id"], "batchNumber": "B-2611-04", "tagIds": [tags["Customer complaint"]],
        "description": "Site reported two shades of grey in one delivery of pavers."})

    log(f"done: {len(STATE)} records in {STATE_FILE}")


if __name__ == "__main__":
    main()

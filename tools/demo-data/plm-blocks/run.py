"""Cement-block PLM demo for one organisation, through the running API only (no SQL).

Usage:  PLM_ADMIN_PASSWORD=... PLM_ENGINEER_PASSWORD=... python3 run.py --confirm DEMO

PLM_ENGINEER_PASSWORD (10 characters or more) is the password of the engineer user this script creates.

Builds a block plant's product lifecycle story in Manufacturing -> Product lifecycle: materials and nine block and
paver products with BOMs per 100 pieces, a block-plant engineer who raises requests and changes (the organisation's
admin reviews, approves and applies them: nobody approves their own change), lifecycle states from concept to
obsolete with history, documents, four change requests and five change orders (applied, in review, scheduled,
ready to apply).

Safe to re-run: categories, products, BOMs, the role, the user and PLM items are looked up and skipped when they
exist; every other step is recorded in state.<ORG>.json and not repeated. Back up the database first.
"""
import calendar
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import date, timedelta
from urllib.parse import quote, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("ERP_BASE", "http://127.0.0.1:3001/api/v1")
ORG = os.environ.get("PLM_ORG", "DEMO")
ADMIN_EMAIL = os.environ.get("PLM_ADMIN_EMAIL", "admin@demo.test")
ADMIN_PASSWORD = os.environ.get("PLM_ADMIN_PASSWORD", "Admin@123")
ENGINEER_EMAIL = os.environ.get("PLM_ENGINEER_EMAIL", "ruth.achieng@demo.test")
ENGINEER_PASSWORD = os.environ.get("PLM_ENGINEER_PASSWORD")
STATE_FILE = os.path.join(HERE, f"state.{ORG}.json")
STATE = json.load(open(STATE_FILE)) if os.path.exists(STATE_FILE) else {}
PLM = "/manufacturing/plm"

FG_CATEGORY = "Concrete Blocks & Pavers"
RM_CATEGORY = "Block Plant Materials"
ENGINEER_ROLE = "Block Plant Engineering"
ENGINEER_PERMISSIONS = ["plm:read", "plm:request", "plm:change", "bom:read", "bom:create", "bom:update",
                        "product:read", "product_category:read", "uom:read",
                        "inventory:read"]

# code, name, unit, cost (UGX), description
MATERIALS = [
    ("BLK-RM-CEM", "Cement CEM II 42.5N, 50 kg bag", "BAG", 33000, "Portland-limestone cement for the block mix."),
    ("BLK-RM-DUST", "Quarry dust 0-5 mm", "TON", 35000, "Fine aggregate from the crusher, used instead of river sand."),
    ("BLK-RM-CHIP", "Aggregate chippings 6-10 mm", "TON", 55000, "Coarse aggregate for hollow and solid blocks."),
    ("BLK-RM-PLAS", "Plasticiser, water-reducing admixture", "L", 12000,
     "About 0.35 L per 100 blocks; the same strength with less cement."),
    ("BLK-RM-POZZ", "Pozzolana, natural volcanic ash", "TON", 60000,
     "Partial cement replacement under trial in the 8 inch block."),
    ("BLK-RM-PUM", "Pumice aggregate 2-8 mm", "TON", 90000, "Lightweight aggregate for the lightweight block prototype."),
]

# code, name, price, cost (UGX per piece), weight (kg), description
BLOCKS = [
    ("BLK-H4", "Hollow concrete block 4 inch (450 × 225 × 100 mm)", 1800, 1300, 12.5,
     "Partition and boundary walls; three cores."),
    ("BLK-H6", "Hollow concrete block 6 inch (450 × 225 × 150 mm)", 2500, 1850, 17,
     "Standard wall block for houses; three cores. The plant's best seller."),
    ("BLK-H8", "Hollow concrete block 8 inch (450 × 225 × 200 mm)", 3200, 2400, 22,
     "Load-bearing walls and foundations; two cores."),
    ("BLK-S6", "Solid concrete block 6 inch (450 × 225 × 150 mm)", 3400, 2700, 27,
     "Foundations and retaining walls below ground."),
    ("BLK-S4", "Solid concrete block 4 inch (450 × 225 × 100 mm)", 2600, 2000, 19, "Solid partition block."),
    ("BLK-H9", "Hollow concrete block 9 inch (450 × 225 × 225 mm)", 3500, 2700, 25, "Pressed on the old 9 inch mould."),
    ("BLK-H6-LW", "Lightweight hollow block 6 inch, pumice", 3100, 2300, 12,
     "Pumice aggregate: about 12 kg instead of 17 kg, for faster laying and lighter frames."),
    ("BLK-PAV80", "Interlocking paver 80 mm, zigzag (225 × 112.5 × 80 mm)", 1100, 750, 4.3,
     "Driveways and parking; about 40 pavers per m²."),
    ("BLK-KERB", "Kerbstone 500 × 250 × 125 mm", 9500, 6500, 33, "Road and parking kerb."),
]

# product: (BOM name, [(material, quantity per 100 pieces)], activate)
BOMS = {
    "BLK-H4": ("4 inch hollow block, batch of 100", [("BLK-RM-CEM", 2.2), ("BLK-RM-DUST", 0.75),
                                                       ("BLK-RM-CHIP", 0.30)], True),
    "BLK-H6": ("6 inch hollow block, batch of 100", [("BLK-RM-CEM", 3.0), ("BLK-RM-DUST", 1.05),
                                                       ("BLK-RM-CHIP", 0.45)], True),
    "BLK-H8": ("8 inch hollow block, batch of 100", [("BLK-RM-CEM", 4.0), ("BLK-RM-DUST", 1.35),
                                                       ("BLK-RM-CHIP", 0.60)], True),
    "BLK-S6": ("6 inch solid block, batch of 100", [("BLK-RM-CEM", 4.5), ("BLK-RM-DUST", 1.50),
                                                      ("BLK-RM-CHIP", 0.75)], True),
    "BLK-S4": ("4 inch solid block, batch of 100", [("BLK-RM-CEM", 3.5), ("BLK-RM-DUST", 1.15),
                                                      ("BLK-RM-CHIP", 0.50)], True),
    "BLK-H9": ("9 inch hollow block, batch of 100", [("BLK-RM-CEM", 4.5), ("BLK-RM-DUST", 1.50),
                                                       ("BLK-RM-CHIP", 0.70)], True),
    "BLK-H6-LW": ("Lightweight 6 inch block trial mix, batch of 100", [("BLK-RM-CEM", 3.0), ("BLK-RM-PUM", 0.90),
                                                                       ("BLK-RM-DUST", 0.30)], True),
    "BLK-PAV80": ("80 mm zigzag paver, batch of 100", [("BLK-RM-CEM", 1.2), ("BLK-RM-DUST", 0.25),
                                                      ("BLK-RM-CHIP", 0.15)], False),
}

# product: (state at registration, revision, description)
ITEMS = {
    "BLK-H4": ("released", "A", "Partition block, in production since 2023."),
    "BLK-H6": ("released", "A", "Best seller; about 60 % of the plant's output."),
    "BLK-H8": ("released", "A", "Load-bearing block for frames and foundations."),
    "BLK-S6": ("released", "A", "Solid block for foundations."),
    "BLK-S4": ("released", "A", "Solid partition block; the 4 inch hollow block sells better."),
    "BLK-H9": ("released", "C", "Old 9 inch block."),
    "BLK-H6-LW": ("concept", "P1", "New product: lightweight wall block with pumice aggregate."),
    "BLK-PAV80": ("concept", "D1", "New product: interlocking paver for driveways."),
    "BLK-KERB": ("concept", "-", "Idea from a road contractor's enquiry; no mould yet."),
}

# product, to state, note, replacement
TRANSITIONS = [
    ("BLK-H6-LW", "design", "Trial mixes with pumice aggregate to cut the block weight by about a third.", None),
    ("BLK-H6-LW", "prototype", "200 prototype blocks pressed. 7-day crushing average 3.1 N/mm² (target 3.5).", None),
    ("BLK-PAV80", "design", "Zigzag 80 mm paver for driveways and parking; mould quotation received.", None),
    ("BLK-H9", "phase_out", "The 9 inch mould is worn and contractors now order 8 inch. Sell the remaining stock.",
     "BLK-H8"),
    ("BLK-H9", "obsolete", "Last 9 inch blocks sold; the mould was scrapped.", "BLK-H8"),
]

# key: document fields; release = release it now (otherwise a change order releases it, or it stays a draft)
DOCUMENTS = {
    "mix_h6": dict(title="Mix design: 6 inch hollow block", docType="specification", revision="B", product="BLK-H6",
                   description="Per 100 blocks: 2.7 bags cement, 1.08 t quarry dust, 0.45 t chippings, 0.35 L "
                               "plasticiser. Target 28-day strength 3.5 N/mm² or more."),
    "mould_h6": dict(title="Mould drawing: 6 inch hollow block, three cores", docType="drawing", revision="A",
                     product="BLK-H6", release=True,
                     description="450 × 225 × 150 mm, 25 mm shells, three cores."),
    "crush_test": dict(title="Block crushing test at 7 and 28 days", docType="test_procedure", revision="A",
                       release=True,
                       description="Crush six blocks per batch. Record the load and the loaded area. A batch below "
                                   "3.5 N/mm² at 28 days is rejected or sold as second grade."),
    "plas_ds": dict(title="Plasticiser datasheet", docType="datasheet", revision="A", product="BLK-RM-PLAS",
                    release=True, description="Water-reducing admixture: dosage, mixing order, shelf life."),
    "pozz_coa": dict(title="Pozzolana certificate of analysis", docType="certificate", revision="A",
                     product="BLK-RM-POZZ", release=True, description="Silica, alumina and fineness of the lot."),
    "curing": dict(title="Curing procedure: water curing and stacking", docType="work_instruction", revision="2",
                   description="Spray-cure 7 days under plastic sheeting, then stack 21 days before dispatch."),
    "lw_plan": dict(title="Lightweight block trial plan: pumice aggregate", docType="specification", revision="P1",
                    product="BLK-H6-LW",
                    description="Three mixes, 200 blocks each; crushing at 7 and 28 days; weight per block."),
}


def log(*a):
    print(*a, flush=True)


def save():
    with open(STATE_FILE, "w") as f:
        json.dump(STATE, f, indent=1, sort_keys=True)


def once(key, fn):
    """Runs fn() the first time only; its result is kept in the state file under `key`."""
    if key not in STATE:
        STATE[key] = fn()
        save()
    return STATE[key]


def rows(data):
    if isinstance(data, list):
        return data
    return data.get("data") or data.get("rows") or data.get("items") or []


def next_month_first():
    t = date.today()
    return (t.replace(day=1) + timedelta(days=calendar.monthrange(t.year, t.month)[1])).isoformat()


class Api:
    """One logged-in user of the tenant."""

    def __init__(self, email, password):
        self.token = None
        res = self.call("POST", "/auth/login", {"organizationCode": ORG, "email": email, "password": password})
        if not res or not res.get("accessToken"):
            raise SystemExit(f"Login of {email} returned no access token.")
        self.token = res["accessToken"]

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(BASE + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(req, timeout=120) as res:
                text = res.read().decode()
                return json.loads(text) if text else None
        except urllib.error.HTTPError as e:
            raise SystemExit(f"{method} {path} -> {e.code}: {e.read().decode()[:700]}")


# ---------------------------------------------------------------------------------------------------- set-up steps

def engineer_account(admin):
    """The block-plant engineer: raises requests and change orders, never approves them."""
    roles = rows(admin.call("GET", "/roles"))
    role = next((r for r in roles if r.get("name") == ENGINEER_ROLE), None)
    if role is None:
        role = admin.call("POST", "/roles", {"name": ENGINEER_ROLE, "permissions": ENGINEER_PERMISSIONS,
                                             "description": "Block plant: raise PLM requests and change orders, "
                                                            "edit draft BOMs. Approval and apply stay with managers."})
        log("role", ENGINEER_ROLE)
    users = rows(admin.call("GET", "/users?search=" + quote(ENGINEER_EMAIL) + "&pageSize=50"))
    if not any(u.get("email") == ENGINEER_EMAIL for u in users):
        admin.call("POST", "/users", {"email": ENGINEER_EMAIL, "password": ENGINEER_PASSWORD, "firstName": "Ruth",
                                      "lastName": "Achieng", "isActive": True, "roleIds": [role["id"]]})
        log("user", ENGINEER_EMAIL)


def lookups(admin):
    uom = {u["code"]: u["id"] for u in rows(admin.call("GET", "/uoms?pageSize=500"))}
    cats = {c["name"]: c["id"] for c in rows(admin.call("GET", "/product-categories?pageSize=500"))}
    for name in (FG_CATEGORY, RM_CATEGORY):
        if name not in cats:
            cats[name] = admin.call("POST", "/product-categories", {"name": name})["id"]
    return uom, cats


def catalogue(admin, uom, cats):
    have = {p["code"]: p["id"] for p in rows(admin.call("GET", "/products?pageSize=500&search=BLK-"))
            if str(p.get("code", "")).startswith("BLK-")}
    for code, name, unit, cost, desc in MATERIALS:
        if code not in have:
            have[code] = admin.call("POST", "/products", {
                "code": code, "sku": code, "name": name, "description": desc, "productType": "stockable",
                "categoryId": cats[RM_CATEGORY], "uomId": uom[unit], "purchaseUomId": uom[unit],
                "salesUomId": uom[unit], "costPrice": cost, "salesPrice": 0, "trackInventory": True,
                "canBeSold": False, "manufacturingRole": "raw"})["id"]
            log("product", code)
    for code, name, price, cost, kg, desc in BLOCKS:
        if code not in have:
            have[code] = admin.call("POST", "/products", {
                "code": code, "sku": code, "name": name, "description": desc, "productType": "stockable",
                "categoryId": cats[FG_CATEGORY], "uomId": uom["UNIT"], "purchaseUomId": uom["UNIT"],
                "salesUomId": uom["UNIT"], "salesPrice": price, "costPrice": cost, "weight": kg,
                "trackInventory": True, "canBeSold": True, "manufacturingRole": "finished"})["id"]
            log("product", code)
    return have


def boms(admin, uom, products):
    """One BOM per block (batch of 100); returns the active BOM id per product code."""
    units = {code: unit for code, _, unit, _, _ in MATERIALS}
    active = {}
    for code, (name, lines, activate) in BOMS.items():
        found = admin.call("GET", f"/manufacturing/boms?outputProductId={products[code]}")
        bom = next((b for b in found if b.get("status") == "active"), None) or (found[0] if found else None)
        if bom is None:
            bom = admin.call("POST", "/manufacturing/boms", {
                "name": name, "outputProductId": products[code], "outputQuantity": 100, "outputUomId": uom["UNIT"],
                "expectedYieldPct": 98, "estimatedDurationMins": 45,
                "notes": "Pan mixer 3 minutes, press, then the curing procedure.",
                "lines": [{"componentProductId": products[m], "quantity": q, "uomId": uom[units[m]], "sequence": i}
                          for i, (m, q) in enumerate(lines, 1)]})
            log("bom", code)
        if activate and bom.get("status") != "active":
            bom = admin.call("POST", f"/manufacturing/boms/{bom['id']}/activate")
            log("bom active", code)
        if bom.get("status") == "active":
            active[code] = bom["id"]
    return active


def plm_items(admin, products, me):
    have = {}
    for i in rows(admin.call("GET", f"{PLM}/items")):
        pid = i.get("productId") or (i.get("product") or {}).get("id")
        have[pid] = i["id"]
    out = {}
    for code, (state, revision, desc) in ITEMS.items():
        pid = products[code]
        if pid not in have:
            have[pid] = admin.call("POST", f"{PLM}/items", {"productId": pid, "lifecycleState": state,
                                                             "revision": revision, "ownerId": me,
                                                             "description": desc})["id"]
            log("plm item", code, state)
        out[code] = have[pid]
    for code, to, note, repl in TRANSITIONS:
        once(f"transition.{code}.{to}", lambda: admin.call("POST", f"{PLM}/items/{out[code]}/transition", {
            "toState": to, "note": note, **({"replacementProductId": products[repl]} if repl else {})})
             .get("lifecycleState") or True)
        log("transition", code, to)
    return out


def documents(admin, products):
    out = {}
    for key, d in DOCUMENTS.items():
        def create(d=d):
            body = {k: v for k, v in d.items() if k not in ("product", "release")}
            if d.get("product"):
                body["productId"] = products[d["product"]]
            return admin.call("POST", f"{PLM}/documents", body)["id"]
        out[key] = once(f"doc.{key}", create)
        if d.get("release"):
            once(f"doc.{key}.released", lambda: status(admin.call("POST", f"{PLM}/documents/{out[key]}/release")))
    log("documents", len(out))
    return out


# ------------------------------------------------------------------------------------------ requests and changes

def stage_ids(admin):
    stages = sorted(admin.call("GET", f"{PLM}/stages"), key=lambda s: s["sequence"])
    review = next(s for s in stages if s.get("requiresApproval"))
    return review["id"]


def bom_lines(uom, products, lines):
    units = {code: unit for code, _, unit, _, _ in MATERIALS}
    return [{"componentProductId": products[m], "quantity": q, "uomId": uom[units[m]], "sequence": i}
            for i, (m, q) in enumerate(lines, 1)]


def status(res):
    return (res or {}).get("status") or True


def change_order(key, eng, admin, review, create, patch=None, bom=None, docs=(), comment=None, approve=None,
                 apply=False):
    """Raise a change as the engineer, edit its draft BOM, send it to review; the admin approves and applies."""
    eco = once(f"eco.{key}", create)
    if patch:
        once(f"eco.{key}.patch", lambda: status(eng.call("PATCH", f"{PLM}/changes/{eco}", patch)))
    if bom:
        def edit_bom():
            new_bom = eng.call("GET", f"{PLM}/changes/{eco}")["newBomId"]
            return status(eng.call("PATCH", f"/manufacturing/boms/{new_bom}", {"lines": bom}))
        once(f"eco.{key}.bom", edit_bom)
    if docs:
        once(f"eco.{key}.docs", lambda: status(eng.call("POST", f"{PLM}/changes/{eco}/documents",
                                                       {"documentIds": list(docs)})))
    if comment:
        once(f"eco.{key}.comment", lambda: status(eng.call("POST", f"{PLM}/changes/{eco}/comments",
                                                          {"message": comment})))
    once(f"eco.{key}.review", lambda: status(eng.call("POST", f"{PLM}/changes/{eco}/stage", {"stageId": review})))
    if approve:
        once(f"eco.{key}.approved", lambda: status(admin.call("POST", f"{PLM}/changes/{eco}/decisions",
                                                              {"decision": "approved", "comment": approve})))
    if apply:
        once(f"eco.{key}.applied", lambda: status(admin.call("POST", f"{PLM}/changes/{eco}/apply", {})))
    row = admin.call("GET", f"{PLM}/changes/{eco}")
    log("change", row.get("number"), row.get("status"), row.get("title"))
    return eco


def requests_and_changes(admin, eng, uom, products, active, docs):
    review = stage_ids(admin)
    pid = products

    # 1. Cost reduction: request -> accepted -> converted -> approved -> applied (revision B).
    ecr = once("ecr.cement", lambda: eng.call("POST", f"{PLM}/requests", {
        "title": "Cement price up 12 %: cut cement in the 6 inch block with a plasticiser", "productId": pid["BLK-H6"],
        "bomId": active["BLK-H6"], "reasonCode": "cost_reduction", "priority": "high",
        "description": "The supplier raised the bag price from 29,500 to 33,000 UGX. A water-reducing plasticiser "
                       "should keep the strength with 10 % less cement."})["id"])
    once("ecr.cement.review", lambda: status(admin.call("POST", f"{PLM}/requests/{ecr}/review", {
        "decision": "accept", "note": "Go ahead with a lab trial; keep the 28-day strength at 3.5 N/mm² or more."})))
    change_order("h6_mix", eng, admin, review,
                 lambda: eng.call("POST", f"{PLM}/requests/{ecr}/convert", {
                     "type": "bom", "bomId": active["BLK-H6"],
                     "title": "6 inch hollow block: plasticiser mix, cement 3.0 to 2.7 bags per 100"})["id"],
                 patch={"newRevision": "B"},
                 bom=bom_lines(uom, pid, [("BLK-RM-CEM", 2.7), ("BLK-RM-DUST", 1.08), ("BLK-RM-CHIP", 0.45),
                                          ("BLK-RM-PLAS", 0.35)]),
                 docs=[docs["mix_h6"]],
                 comment="Lab trial on 12 blocks: 7-day average 3.9 N/mm², 28-day 4.6 N/mm². Saves about 46 UGX "
                         "per block.",
                 approve="Strength is well above target. Switch over now; use up the old mix on the line.",
                 apply=True)

    # 2. Quality complaint: accepted, still being investigated (no change order yet).
    chips = once("ecr.chips", lambda: eng.call("POST", f"{PLM}/requests", {
        "title": "Chipped corners on 6 inch blocks delivered to site", "productId": pid["BLK-H6"],
        "reasonCode": "quality", "priority": "normal",
        "description": "Two contractors reported 3-4 % chipped blocks per truck. Suspect handling before 7 days of "
                       "curing."})["id"])
    once("ecr.chips.review", lambda: status(admin.call("POST", f"{PLM}/requests/{chips}/review", {
        "decision": "accept", "note": "Check curing time and pallet strapping before changing the mix."})))

    # 3. Rejected idea.
    sand = once("ecr.sand", lambda: eng.call("POST", f"{PLM}/requests", {
        "title": "Use river sand instead of quarry dust", "productId": pid["BLK-H6"], "reasonCode": "supplier",
        "priority": "low", "description": "Smoother face finish that some customers ask for."})["id"])
    once("ecr.sand.review", lambda: status(admin.call("POST", f"{PLM}/requests/{sand}/review", {
        "decision": "reject", "note": "River sand deliveries are irregular in the rainy season and cost more per "
                                      "tonne. Keep quarry dust."})))

    # 4. Customer enquiry raised by the admin: waits for someone else to review it.
    once("ecr.three_cores", lambda: admin.call("POST", f"{PLM}/requests", {
        "title": "Contractor asks for an 8 inch block with three cores", "productId": pid["BLK-H8"],
        "reasonCode": "customer", "priority": "normal",
        "description": "A site in Mukono wants lighter 8 inch blocks for a four-storey frame: three cores instead "
                       "of two."})["id"])

    # 5. In review: pozzolana in the 8 inch block, waiting for the admin's approval.
    change_order("h8_pozz", eng, admin, review,
                 lambda: eng.call("POST", f"{PLM}/changes", {
                     "type": "bom", "bomId": active["BLK-H8"], "priority": "normal", "newRevision": "B",
                     "title": "8 inch hollow block: replace 15 % of the cement with pozzolana",
                     "reason": "Lower cost and lower clinker content.",
                     "stockDisposition": "use_up", "wipDisposition": "continue"})["id"],
                 bom=bom_lines(uom, pid, [("BLK-RM-CEM", 3.4), ("BLK-RM-POZZ", 0.03), ("BLK-RM-DUST", 1.35),
                                          ("BLK-RM-CHIP", 0.60)]),
                 comment="Trial batch of 300 blocks pressed. 7-day crushing 3.6 N/mm²; 28-day results next week.")

    # 6. Approved, scheduled: lighter 4 inch mould insert from the first day of next month.
    change_order("h4_mould", eng, admin, review,
                 lambda: eng.call("POST", f"{PLM}/changes", {
                     "type": "bom", "bomId": active["BLK-H4"], "priority": "normal", "newRevision": "B",
                     "title": "4 inch hollow block: lighter mould insert, cement 2.2 to 2.0 bags per 100",
                     "reason": "New mould insert with thinner webs; block weight 12.5 to 11.6 kg.",
                     "effectivity": "date", "effectiveDate": next_month_first(),
                     "stockDisposition": "use_up", "wipDisposition": "continue"})["id"],
                 bom=bom_lines(uom, pid, [("BLK-RM-CEM", 2.0), ("BLK-RM-DUST", 0.70), ("BLK-RM-CHIP", 0.28)]),
                 approve="Approved. Switch over when the new insert is fitted on the 1st.", apply=True)

    # 7. Applied: phase out the 4 inch solid block; the 4 inch hollow block replaces it.
    s4 = STATE["items"]["BLK-S4"]
    once("item.s4.replacement", lambda: status(admin.call("PATCH", f"{PLM}/items/{s4}",
                                                          {"replacementProductId": pid["BLK-H4"]})))
    change_order("s4_phase_out", eng, admin, review,
                 lambda: eng.call("POST", f"{PLM}/changes", {
                     "type": "product", "productId": pid["BLK-S4"], "targetLifecycleState": "phase_out",
                     "priority": "low", "title": "Phase out the 4 inch solid block",
                     "reason": "Sales fell below 2,000 a month; the 4 inch hollow block does the same job."})["id"],
                 approve="Agreed. Sell the stock on hand; no new production.", apply=True)

    # 8. Approved, ready to apply: curing procedure revision 2.
    change_order("curing_rev2", eng, admin, review,
                 lambda: eng.call("POST", f"{PLM}/changes", {
                     "type": "document", "documentIds": [docs["curing"]], "priority": "high",
                     "title": "Curing procedure rev 2: 7 days water curing, 21 days stacking",
                     "reason": "Chipped corners on site: blocks were dispatched after 14 days."})["id"],
                 approve="Approved. Release it at the Monday toolbox talk.")


def main():
    if urlparse(BASE).hostname not in ("localhost", "127.0.0.1"):
        raise SystemExit(f"Refusing non-local target {BASE}. Run on the server itself.")
    if len(sys.argv) != 3 or sys.argv[1] != "--confirm" or sys.argv[2] != ORG:
        raise SystemExit(f"Pass --confirm {ORG} to write to tenant {ORG} on {BASE}.")
    if not ENGINEER_PASSWORD or len(ENGINEER_PASSWORD) < 10:
        raise SystemExit("Set PLM_ENGINEER_PASSWORD (10 characters or more) for the engineer user.")
    admin = Api(ADMIN_EMAIL, ADMIN_PASSWORD)
    engineer_account(admin)
    eng = Api(ENGINEER_EMAIL, ENGINEER_PASSWORD)
    uom, cats = lookups(admin)
    products = catalogue(admin, uom, cats)
    active = boms(admin, uom, products)
    me = next(u["id"] for u in rows(admin.call("GET", "/users?search=" + quote(ENGINEER_EMAIL) + "&pageSize=50"))
              if u.get("email") == ENGINEER_EMAIL)
    STATE["items"] = plm_items(admin, products, me)
    save()
    docs = documents(admin, products)
    requests_and_changes(admin, eng, uom, products, active, docs)
    overview = admin.call("GET", f"{PLM}/overview")
    log("overview", json.dumps(overview)[:600])
    log("done")


if __name__ == "__main__":
    main()

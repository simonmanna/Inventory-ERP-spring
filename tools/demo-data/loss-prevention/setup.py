"""Step `setup`: the pilot site's tills, staff, bar products, rule thresholds, edge agent, cameras, zones and meters.

Everything goes through the API. Each object is recorded in the state file and skipped on a re-run.
"""
import secrets

from api import STATE, Session, log, rows, save

BRANCH_CODE = "KRD"  # Kampala Road Cafe: the pilot site
STAFF = [  # (key, email, first, last); Sarah Cashier already exists in DEMO
    ("grace", "grace.nakato@demo.test", "Grace", "Nakato"),
    ("peter", "peter.okello@demo.test", "Peter", "Okello"),
    ("aisha", "aisha.namutebi@demo.test", "Aisha", "Namutebi"),
]
SPIRITS = [  # (key, code, name, cost per ml)
    ("gin", "LPD-GIN", "House Gin (750 ml bottle)", 60),
    ("vodka", "LPD-VODKA", "House Vodka (750 ml bottle)", 55),
    ("whisky", "LPD-WHISKY", "House Whisky (750 ml bottle)", 90),
]
DRINKS = [  # (key, code, name, price, spirit)
    ("gnt", "LPD-GNT", "Gin & Tonic", 15000, "gin"),
    ("vsoda", "LPD-VSODA", "Vodka Soda", 14000, "vodka"),
    ("whisky1", "LPD-WHISKY1", "Whisky (single, 30 ml)", 18000, "whisky"),
]
RULES = {"C03": {"amount": 50000}, "C08": {"amount": 10000}}  # UGX thresholds; both rules are off until set


def run(s: Session):
    ids = STATE.setdefault("ids", {})
    branches = {b["code"]: b["id"] for b in rows(s.call("GET", "/branches?pageSize=100"))}
    ids["branch"] = branches[BRANCH_CODE]
    save()

    # Tills: the counter (watched by the till camera) and the bar.
    have = {r["code"]: r["id"] for r in rows(s.call("GET", "/cash-registers"))}
    for key, code, name in (("till1", "KRD-T1", "KRD Counter Till"), ("till2", "KRD-T2", "KRD Bar Till")):
        if code not in have:
            have[code] = s.call("POST", "/cash-registers", {"code": code, "name": name, "branchId": ids["branch"]})["id"]
            log("  till", code)
        ids[key] = have[code]
    save()

    # Staff: three more cashiers, so "well above the branch" has a median to compare with.
    users = {u["email"]: u for u in rows(s.call("GET", "/users?pageSize=200"))}
    roles = {r["name"]: r["id"] for r in rows(s.call("GET", "/roles?pageSize=100"))}
    for key, email, first, last in STAFF:
        if email not in users:
            users[email] = s.call("POST", "/users", {"email": email, "password": "Lp-" + secrets.token_urlsafe(18),
                                                    "firstName": first, "lastName": last, "isActive": True,
                                                    "roleIds": [roles["Cashier"]]})
            log("  user", email)
        ids["user_" + key] = users[email]["id"]
    for key, email in (("sarah", "sarah@demo.test"), ("mike", "manager@demo.test"), ("mary", "mary@demo.test")):
        ids["user_" + key] = users[email]["id"]
    save()

    # Bar products: spirits kept in millilitres (the recipe and meter unit) and the drinks made from them.
    uom = {u["code"]: u["id"] for u in rows(s.call("GET", "/uoms"))}
    prods = {p["code"]: p["id"] for p in rows(s.call("GET", "/products?pageSize=100&search=LPD-"))
             if str(p.get("code", "")).startswith("LPD-")}
    for key, code, name, cost in SPIRITS:
        if code not in prods:
            prods[code] = s.call("POST", "/products", {
                "code": code, "sku": code, "name": name, "productType": "stockable", "uomId": uom["ML"],
                "salesUomId": uom["ML"], "purchaseUomId": uom["ML"], "salesPrice": 0, "costPrice": cost,
                "trackInventory": False, "canBeSold": False, "isActive": True})["id"]
            log("  product", code)
        ids["prod_" + key] = prods[code]
    for key, code, name, price, _ in DRINKS:
        if code not in prods:
            prods[code] = s.call("POST", "/products", {
                "code": code, "sku": code, "name": name, "productType": "consumable", "uomId": uom["UNIT"],
                "salesUomId": uom["UNIT"], "purchaseUomId": uom["UNIT"], "salesPrice": price, "costPrice": 0,
                "trackInventory": False, "canBeSold": True, "isActive": True})["id"]
            log("  product", code)
        ids["prod_" + key] = prods[code]
    save()

    # Rule thresholds in UGX.
    for code, params in RULES.items():
        rule = s.call("GET", f"/loss-prevention/rules/{code}")
        if rule["params"] != params:
            s.call("PUT", f"/loss-prevention/rules/{code}", {"enabled": True, "severity": rule["severity"],
                                                              "params": params})
            log("  rule", code, params)

    # Edge agent: created by the admin, then enrolled as the box on site would be.
    if "agent" not in STATE:
        agent = s.call("POST", "/loss-prevention/agents", {"branchId": ids["branch"], "name": "KRD edge box (demo)"})
        code = agent.get("enrolmentCode") or s.call(
            "POST", f"/loss-prevention/agents/{agent['id']}/enrolment-code")["enrolmentCode"]
        enrolled = Session().call("POST", "/loss-prevention/agents/enrol", {
            "code": code, "hostname": "krd-edge-demo", "agentVersion": "demo-replay"})
        STATE["agent"] = {"id": agent["id"], "clientId": enrolled["clientId"],
                          "clientSecret": enrolled["clientSecret"]}
        save()
        log("  agent", agent["id"])
    agent_id = STATE["agent"]["id"]

    cams = STATE.setdefault("cameras", {})
    for key, name, alias in (("till", "Counter till", "till"), ("bar", "Bar counter", "bar"),
                             ("pass", "Kitchen pass", "pass"), ("pickup", "Pick-up counter", "pickup"),
                             ("door", "Front door", "door"), ("dining", "Dining room", "dining")):
        if key not in cams:
            cams[key] = s.call("POST", "/loss-prevention/cameras", {
                "agentId": agent_id, "name": name, "streamAlias": alias, "enabled": True, "analysed": True,
                "notes": "Demo camera: its observations are replayed, not seen."})["id"]
            log("  camera", name)
    save()

    zones = STATE.setdefault("zones", {})
    box = [[0.2, 0.2], [0.8, 0.2], [0.8, 0.8], [0.2, 0.8]]
    line = [[0.1, 0.5], [0.9, 0.5]]
    plan = [
        ("drawer", "till", "Cash drawer", "cash_drawer", box, None, "till", ids["till1"]),
        ("checkout", "till", "Customer side of the till", "checkout", box, None, "till", ids["till1"]),
        ("queue", "till", "Till queue", "queue", box, None, "till", ids["till1"]),
        ("bar", "bar", "Back bar", "bar_counter", box, None, "station", "bar"),
        ("pass", "pass", "Kitchen pass line", "kitchen_pass", line, "left", "station", "Kitchen"),
        ("pickup_coffee", "pickup", "Coffee hand-over", "cafe_counter", line, "left", "station", "Coffee bar"),
        ("pickup_till", "pickup", "Takeaway hand-over", "cafe_counter", line, "left", "till", ids["till1"]),
        ("door", "door", "Entrance line", "entrance", line, "right", None, None),
    ]
    tables = {t["number"]: t["id"] for t in s.call("GET", "/pos/tables")}
    ids["tables"] = {str(n): tables[n] for n in (1, 2, 3, 4, 5, 6)}
    for n in (1, 2, 3, 4):
        plan.append((f"table{n}", "dining", f"Table {n}", "dining", box, None, "table", tables[n]))
    for key, cam, name, ztype, poly, side, ltype, lid in plan:
        if key not in zones:
            body = {"cameraId": cams[cam], "name": name, "zoneType": ztype, "polygon": poly, "enabled": True}
            if side:
                body["inSide"] = side
            if ltype:
                body["linkedType"], body["linkedId"] = ltype, lid
            zones[key] = s.call("POST", "/loss-prevention/zones", body)["id"]
            log("  zone", name)
    save()

    meters = STATE.setdefault("meters", {})
    for key, _, name, _ in SPIRITS:
        if key not in meters:
            meters[key] = s.call("POST", "/loss-prevention/measurement-devices", {
                "branchId": ids["branch"], "agentId": agent_id, "name": f"Pour spout: {name.split(' (')[0]}",
                "kind": "pour_spout", "vendor": "demo", "externalId": f"krd-spout-{key}",
                "productId": ids["prod_" + key], "station": "bar", "standardPourMl": 30, "enabled": True})["id"]
            log("  meter", key)
    save()
    log("setup done")

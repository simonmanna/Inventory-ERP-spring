"""Multi-stage processing demo (ADR-S060): granite from purchase to sale at each stage, through the running API only.

Usage: python run.py --confirm MENA     Back up the database first. Run once: each run buys and processes a new lot.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import date
from urllib.parse import urlencode, urlparse

BASE = os.environ.get("ERP_BASE", "http://127.0.0.1:3001/api/v1")
ORG = os.environ.get("PROCESSING_ORG", "MENA")
EMAIL = os.environ.get("PROCESSING_EMAIL", "admin@menagranite.test")
PASSWORD = os.environ.get("PROCESSING_PASSWORD", "MenaDemo@2026")
TODAY = date.today().isoformat()
TAG = time.strftime("%y%m%d%H%M")
TOKEN = None


def call(method, path, body=None, params=None, key=False):
    url = BASE + path + ("?" + urlencode(params) if params else "")
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    if key:
        req.add_header("Idempotency-Key", str(uuid.uuid4()))
    try:
        with urllib.request.urlopen(req, timeout=120) as res:
            raw = res.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} -> {e.code}: {e.read().decode()[:700]}")


def rows(data):
    return data if isinstance(data, list) else data.get("data") or data.get("rows") or data.get("items") or []


def product(code, name, role, price, uom, category):
    return call("POST", "/products", {
        "code": f"{code}-{TAG}", "name": f"{name} {TAG}", "sku": f"{code}-{TAG}", "categoryId": category, "uomId": uom, "salesUomId": uom,
        "purchaseUomId": uom, "productType": "stockable", "trackInventory": True, "costingMethod": "AVCO", "salesPrice": price, "costPrice": 0,
        "allowFractionalSale": True, "batchTracking": True, "stockPolicy": "block", "manufacturingRole": role, "canBeSold": True})["id"]


def template(code, name, in_id, out_id, pct, location):
    t = call("POST", "/manufacturing/processing/templates", {
        "code": f"{code}-{TAG}", "name": name, "allocationMethod": "market_value", "yieldBasisUnit": "t", "expectedYieldPct": pct,
        "defaultInputLocationId": location, "defaultOutputLocationId": location,
        "inputs": [{"productId": in_id, "role": "traceable"}], "outputs": [{"productId": out_id, "role": "main", "expectedPct": pct}]})
    return call("POST", f"/manufacturing/processing/templates/{t['id']}/activate")


def job(t, lot, qty, out, loss, cost):
    return call("POST", "/manufacturing/processing/jobs/post", {
        "job": {"templateId": t["id"], "inputs": [{"templateInputId": t["inputs"][0]["id"], "batchNumber": lot, "quantity": qty}]},
        "costs": [{"category": "machine", "amount": cost, "description": "Machine and labour"}],
        "output": {"lines": [{"templateOutputId": t["outputs"][0]["id"], "quantity": out, "grade": "A"}], "lossBasis": loss, "complete": True}}, key=True)


def main():
    global TOKEN
    if urlparse(BASE).hostname not in ("localhost", "127.0.0.1"):
        raise SystemExit(f"Refusing non-local target {BASE}.")
    if len(sys.argv) != 3 or sys.argv[1] != "--confirm" or sys.argv[2] != ORG:
        raise SystemExit(f"Pass --confirm {ORG} to write to demo tenant {ORG} on {BASE}.")
    TOKEN = call("POST", "/auth/login", {"organizationCode": ORG, "email": EMAIL, "password": PASSWORD})["accessToken"]

    uoms = rows(call("GET", "/uoms", params={"pageSize": 200}))
    uom = next((u["id"] for u in uoms if str(u.get("code", "")).lower() in ("t", "ton", "tonne", "mt")), uoms[0]["id"])
    categories = rows(call("GET", "/product-categories"))
    category = categories[0]["id"] if categories else None
    locations = rows(call("GET", "/inventory/locations", params={"pageSize": 200}))
    warehouse = next(l["id"] for l in locations if l.get("type") == "warehouse")
    partners = rows(call("GET", "/partners", params={"pageSize": 200}))
    supplier = next(p["id"] for p in partners if p.get("isSupplier"))
    customer = next(p["id"] for p in partners if p.get("isCustomer"))

    raw = product("PS-RAW", "Raw Granite", "raw", 30, uom, category)
    block = product("PS-BLK", "Granite Blocks", "semi_finished", 55, uom, category)
    polished = product("PS-POL", "Polished Granite", "semi_finished", 100, uom, category)
    tile = product("PS-TIL", "Granite Tiles", "finished", 180, uom, category)

    lot = f"GR-{TAG}"
    call("POST", "/procurement/goods-receipts/adhoc", {"partnerId": supplier, "warehouseId": warehouse, "receivedAt": f"{TODAY}T08:00:00Z", "lines": [
        {"productId": raw, "description": "Raw granite", "quantity": 100, "unitCost": 20, "batchNumber": lot}]})

    cut = template("CUT", f"Primary cutting {TAG}", raw, block, 80, warehouse)
    polish = template("POLISH", f"Polishing {TAG}", block, polished, 90, warehouse)
    finish = template("TILE", f"Tile cutting {TAG}", polished, tile, 90, warehouse)
    call("POST", "/manufacturing/processing/routes", {"code": f"GRANITE-{TAG}", "name": f"Granite to tiles {TAG}", "steps": [
        {"templateCode": cut["code"]}, {"templateCode": polish["code"]}, {"templateCode": finish["code"]}]})

    j1 = job(cut, lot, 60, 48, 12, 500)
    block_lot = j1["outputs"][0]["batchNumber"]
    j2 = job(polish, block_lot, 20, 18, 2, 400)
    polished_lot = j2["outputs"][0]["batchNumber"]
    j3 = job(finish, polished_lot, 10, 9, 1, 450)

    # Sell at three stages. Automatic lot picking finds the exact lot on each.
    for product_id, qty, price in ((block, 8, 55), (polished, 3, 100), (tile, 4, 180)):
        inv = call("POST", "/invoices", {"partnerId": customer, "issueDate": TODAY, "lines": [{"productId": product_id, "quantity": qty, "unitPrice": price}]})
        call("POST", f"/invoices/{inv['id']}/post", None, key=True)

    for j in (j1, j2, j3):
        print(j["code"], j["templateName"], j["outputs"][0]["batchNumber"], "unit cost", j["outputs"][0]["unitCost"])
    print("Done. Open Manufacturing > Processing Dashboard.")


if __name__ == "__main__":
    main()

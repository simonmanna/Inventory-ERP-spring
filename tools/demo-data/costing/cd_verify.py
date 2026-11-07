"""Reads the seeded story back through the API and checks every number the walkthrough quotes. Exit 2 on a failure."""
import json
import os

from base import A, HERE, TODAY, ids, rows

TOMORROW = "2026-11-07"
# order key -> planned unit cost of the batch, good output, unit cost
ORDERS = {"BLK-A": (12500, 1930.93), "BLK-B": (12500, 1942.13), "BLK-C": (12500, 1906.53), "BLK-D": (11800, 2049.18)}
fails, seen = [], {}


def check(name, actual, expected, tol=0.01):
    ok = abs(float(actual) - float(expected)) <= tol
    seen[name] = {"actual": str(actual), "expected": expected, "ok": ok}
    print("  %s %s: %s (expected %s)" % ("ok  " if ok else "FAIL", name, actual, expected))
    if not ok:
        fails.append(name)


def run():
    s = ids("steps")
    for key, (good, unit) in ORDERS.items():
        o = A.get("/manufacturing/costing/orders/" + s["mo:" + key])
        check(key + " good qty", o["producedQty"], good)
        check(key + " unit cost", o["unitCost"], unit)
    blk = A.get("/manufacturing/boms/%s/cost" % s["bom:blk"])
    check("BOM roll-up per 1,000 blocks", blk["totalCost"], 1933956.27, 1)
    labour = sum(float(x["cost"]) for x in blk["breakdown"] if x["kind"] == "labour")
    check("BOM conversion per 1,000 blocks", labour, 257500)
    by_item = {r["sku"]: r for r in A.get("/procurement/landed-cost-reports/by-item", **{"from": TODAY, "to": TOMORROW})}
    check("Landed on cement", by_item["CD-CEM"]["allocated"], 8149780.01, 0.05)
    check("Landed on sand", by_item["CD-SAND"]["allocated"], 5040000)
    check("Landed on stone dust", by_item["CD-DUST"]["allocated"], 350000)
    recon = {r["key"]: r for r in A.get("/procurement/landed-cost-reports/reconciliation")["rows"]}
    check("Clearing 2155 difference", recon["2155"]["difference"], 0)
    check("Clearing 2155 balance", recon["2155"]["gl"], 0)
    check("WIP after cost adjustment run", recon["wip"]["gl"], 0)
    check("Stock sub-ledger vs GL, whole org", recon["stock"]["difference"], 0, 1)
    costed = [r for r in A.get("/manufacturing/costing/reports/margin", groupBy="product",
                               **{"from": TODAY, "to": TOMORROW})["rows"] if r["key"] == ids("product")["CD-BLK6"]]
    check("Blocks sold", costed[0]["quantity"], 16000)
    check("Block revenue", costed[0]["revenue"], 38400000)
    orders = [p for p in rows(A.get("/procurement/purchase-orders", pageSize=200))
              if str(p.get("description", "")).startswith("Costing demo")]
    check("Demo purchase orders", len(orders), 13)
    with open(os.path.join(HERE, "verify-result.json"), "w") as f:
        json.dump(seen, f, indent=1)
    print("  %d checks, %d failed" % (len(seen), len(fails)))
    if fails:
        raise SystemExit(2)

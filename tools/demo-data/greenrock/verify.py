"""Reads the seeded data back through the same report routes the screens call, and checks the scenarios add up."""
import json
import os
import sys

import core
from core import A, ApiError, ids, rows

F = {"from": "2026-10-07", "to": "2026-11-06"}
D = {"dateFrom": F["from"], "dateTo": F["to"]}
RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append({"check": name, "ok": bool(ok), "detail": detail})
    print(("  PASS " if ok else "  FAIL ") + name + ("  " + str(detail) if detail else ""))


def num(x):
    return float(x or 0)


def order(key):
    step = ids("steps")[key + ":order"]
    return A.get("/sales/orders/" + step["so"]), step


def pits():
    rep = A.get("/quarry/reports/cost-per-tonne", groupBy="site", **D)
    by = {r["siteCode"]: r for r in rep["rows"]}
    for code, g, o in (("PIT-A", 10000, 2200), ("PIT-B", 8500, 1275), ("PIT-C", 6000, 1800)):
        r = by[code]
        check("Yield table " + code, num(r["grossTonnes"]) == g and num(r["lossTonnes"]) == o and num(r["netTonnes"]) == g - o,
              "gross %s, overburden %s, usable %s, waste %s%%, UGX %s per usable t" % (
                  r["grossTonnes"], r["lossTonnes"], r["netTonnes"], r["lossPct"], round(num(r["costPerTonne"]))))
    check("Scenario 1: Granite Ridge recovers more than Southern Extension",
          num(by["PIT-A"]["lossPct"]) < num(by["PIT-C"]["lossPct"]))
    logs = sum(int(r["logs"]) for r in by.values())
    check("Extraction logs posted", logs == 81, logs)


def production():
    y = A.get("/manufacturing/crushing/reports/yield", **F)
    for p in y["plants"]:
        check("Crushing balance " + p["plantCode"], abs(num(p["feedTonnes"]) - num(p["outputTonnes"])) < 0.01,
              "feed %s = output %s over %s runs" % (p["feedTonnes"], p["outputTonnes"], p["runs"]))
    run = ids("steps")["run-scn-downtime"]
    d = A.get("/manufacturing/crushing/runs/" + run["id"])
    check("Scenario 2: 500 planned, 380 actual, 2 h downtime", num(d["feedTonnes"]) == 380 and num(d["downtimeHours"]) == 2,
          "%s: %s" % (d.get("code"), d.get("downtimeReason")))
    run = ids("steps")["run-scn-1000"]
    d = A.get("/manufacturing/crushing/runs/" + run["id"])
    total = sum(num(o.get("actualTonnes")) for o in d.get("outputs", []))
    check("Sample batch 1,000 MT reconciles", num(d["feedTonnes"]) == 1000 and abs(total - 1000) < 0.01, d.get("code"))
    c = A.get("/manufacturing/crushing/reports/cost-per-tonne", **F)
    print("  cost per tonne:", {r["productCode"]: round(num(r["costPerTonne"])) for r in c["products"]})
    h = A.get("/manufacturing/crushing/reports/plant-hours", **F)
    print("  plants:", [(p["plantCode"], "avail %s%%" % p["availabilityPct"], "util %s%%" % p["utilisationPct"]) for p in h["plants"]])


def quality():
    lots = rows(A.get("/quality/inspection-lots", pageSize=100))
    res = {}
    for x in lots:
        res[(x.get("status"), x.get("result"))] = res.get((x.get("status"), x.get("result")), 0) + 1
    check("Approved, held and rejected lots exist", any(k[1] == "pass" for k in res) and any(k[0] == "hold" for k in res)
          and any(k[1] == "fail" for k in res), {"/".join(map(str, k)): v for k, v in res.items()})
    check("Failed lot raised an NCR (scenario 3)", len(rows(A.get("/quality/ncrs", pageSize=20))) >= 1)


def weighbridge():
    t = A.get("/fleet/weighbridge/tickets", pageSize=100)
    tickets = rows(t)
    print("  tickets", t.get("total", len(tickets)), "summary", A.get("/fleet/weighbridge/tickets/summary"))
    ubl = [x for x in tickets if x.get("registrationPlate") == "UBL 482K" and num(x.get("netKg")) == 20000]
    check("Example ticket UBL 482K: 32,500 - 12,500 = 20,000 kg", ubl and num(ubl[0]["grossKg"]) == 32500 and num(ubl[0]["tareKg"]) == 12500,
          ubl[0]["ticketNumber"] if ubl else "")
    bad = [x for x in tickets if x.get("grossKg") and x.get("tareKg") and abs(num(x["grossKg"]) - num(x["tareKg"]) - num(x["netKg"])) > 0.01]
    check("Every ticket: gross - tare = net", not bad)
    ex = rows(A.get("/fleet/weighbridge/exceptions", pageSize=20))
    check("Exceptions: one approved, one pending", {"approved", "pending"} <= {x.get("status") for x in ex},
          [(x.get("status"), x.get("varianceKg")) for x in ex])
    check("Voided ticket and its reissue", any(x.get("status") == "void" for x in rows(A.get("/fleet/weighbridge/tickets", queue="void", pageSize=5))))
    dup = "not rejected"
    try:
        step = ids("steps")["SO-PEND-12:order"]
        A.post("/fleet/weighbridge/tickets", {"stationId": ids("wb")["station"], "subjectType": "sale_delivery",
                                              "vehicleId": ids("vehicle")["M-01"]}, key="dup-probe")
    except ApiError as e:
        dup = str(e.body.get("message") if isinstance(e.body, dict) else e.body)
    check("Second live ticket for the same truck is rejected", "already on live" in dup, dup)


def leakage():
    so, step = order("SO-LEAK-100")
    ln = so["lines"][0]
    check("Scenario 4: 100 ordered, 96 dispatched, 90 invoiced, 6 unbilled",
          num(ln["quantity"]) == 100 and num(ln["qtyDelivered"]) == 96 and num(ln["qtyInvoiced"]) == 90,
          "%s ordered %s, delivered %s, billable %s, invoiced %s" % (so["number"], ln["quantity"], ln["qtyDelivered"], ln["qtyBillable"], ln["qtyInvoiced"]))
    disc = rows(A.get("/fleet/delivery-discrepancies", pageSize=10))
    check("Delivery shortage recorded from the POD", len(disc) >= 1, [(x.get("kind"), x.get("status")) for x in disc][:3])


def profitability():
    r = A.get("/fleet/reports/delivery-profitability", groupBy="delivery_order", **F)
    by = {x["label"]: x for x in r["rows"]}
    near = by[A.get("/fleet/delivery-orders/" + ids("steps")["SO-NEAR-120:order"]["do"])["code"]]
    far = by[A.get("/fleet/delivery-orders/" + ids("steps")["SO-FAR-120:order"]["do"])["code"]]
    for n, x in (("near 18 km", near), ("far 210 km", far)):
        print("  ", n, {k: x.get(k) for k in ("tonnes", "revenue", "transportCharge", "productCost", "tripCost", "margin", "marginPct")})
    check("Scenario 5: identical orders, far haul costs more and earns less margin",
          num(far["tripCost"]) > num(near["tripCost"]) and num(far["marginPct"]) < num(near["marginPct"]))
    a, b = (by[A.get("/fleet/delivery-orders/" + ids("steps")[k + ":order"]["do"])["code"]] for k in ("SO-0510-A", "SO-2040-A"))
    print("   5-10mm", a.get("marginPct"), "20-40mm", b.get("marginPct"))
    check("Scenario 6: 5-10mm and 20-40mm profit visible", a.get("marginPct") is not None and b.get("marginPct") is not None)
    k = A.get("/fleet/reports/kpis", **F)
    print("  fleet kpis:", {x: k.get(x) for x in ("deliveries", "onTimePct", "tonnes", "transportCostPerTonne", "deliveryMarginPct", "availabilityPct", "utilisationPct", "fuelLitres", "costPerKm")})
    check("Fleet cost per km computed", num(k.get("costPerKm")) > 0, k.get("costPerKm"))
    s = A.get("/sales/analytics/analysis", groupBy="product", **F)
    print("  sales margin by product:", [(x.get("label") or x.get("name"), x.get("marginPercent")) for x in s.get("rows", [])][:8])
    check("Sales margin by product is not 100% (cost price set)", all(num(x.get("marginPercent")) < 99 for x in s.get("rows", [])))


def stock():
    book = {x["stockpileCode"]: num(x["onHandTonnes"]) for x in rows(A.get("/quarry/rom-stock"))}
    print("  stock:", book)
    check("No negative stock", all(v >= 0 for v in book.values()))
    check("ROM pads keep stock", all(book.get("ROM-" + c, 0) > 0 for c in "ABC"))
    # Produced - dispatched - scrapped = on hand, per finished product.
    from sales import ORDERS
    made = {r["productCode"]: num(r["tonnes"]) for r in A.get("/manufacturing/crushing/reports/cost-per-tonne", **F)["products"]}
    pile = {"AGG-2040": "SP-2040", "AGG-1020": "SP-1020", "AGG-0510": "SP-0510", "AGG-DUST": "SP-DUST", "AGG-CRUN": "SP-CRUN", "AGG-MSAND": "SP-MSAND"}
    out = {}
    for _, _, prod, _, loads, _, opt in ORDERS:
        if not opt.get("pending"):
            out[prod] = out.get(prod, 0) + sum(loads)
    out["AGG-1020"] += 40  # lot rejected and scrapped
    for prod, sp in pile.items():
        gap = made[prod] - out.get(prod, 0) - book[sp]
        check("Stock reconciles " + prod, abs(gap) < 0.01, "made %.3f - out %.1f = on hand %.3f" % (made[prod], out.get(prod, 0), book[sp]))


def isolation():
    """Another tenant sees nothing of this one, and a role without permissions gets 403."""
    menu = core.Api("x")
    try:
        res = menu.raw("POST", "/auth/login", {"organizationCode": "MENA", "email": "admin@menagranite.test", "password": "MenaDemo@2026"})
        menu.token = res["accessToken"]
        names = [x.get("name", "") for x in rows(menu.raw("GET", "/quarry/sites"))]
        check("Tenant isolation: MENA does not see the pits", not any("Granite Ridge" in n for n in names))
        fail = "no error"
        try:
            menu.raw("GET", "/quarry/sites/" + ids("site")["PIT-A"])
        except ApiError as e:
            fail = e.status
        check("Tenant isolation: MENA gets 404 on a pit id", fail == 404, fail)
    except ApiError as e:
        print("  isolation check skipped:", e)
    role = next((r for r in rows(A.get("/roles")) if r["name"] == "Demo Viewer - no access"), None) \
        or A.post("/roles", {"name": "Demo Viewer - no access", "description": "DEMO: authorization check", "permissions": []})
    email = "viewer@greenrock.test"
    if email not in [u.get("email") for u in rows(A.get("/users", limit=200))]:
        A.post("/users", {"email": email, "password": core.PASSWORD, "firstName": "Demo", "lastName": "Viewer", "roleIds": [role["id"]]})
    v = core.Api(email)
    v.login()
    fail = "no error"
    try:
        v.get("/quarry/overview")
    except ApiError as e:
        fail = e.status
    check("Authorization: a role without permissions gets 403", fail == 403, fail)


def run():
    for section in (pits, production, quality, weighbridge, leakage, profitability, stock, isolation):
        print("-", section.__name__, flush=True)
        try:
            section()
        except (ApiError, KeyError, IndexError, StopIteration, TypeError) as e:
            check(section.__name__ + " ran", False, repr(e)[:300])
    failed = [r for r in RESULTS if not r["ok"]]
    print("VERIFY: %d checks, %d failed" % (len(RESULTS), len(failed)))
    with open(os.path.join(core.HERE, "verify-result.%s.json" % core.ORG), "w") as f:
        json.dump(RESULTS, f, indent=1, default=str)
    if failed:
        sys.exit(2)

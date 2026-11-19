"""Sell at any stage (ADR-S060): Steel Grey granite from raw slab to sealed countertop, a sale after every step.

Buys 45 m² of raw slab, runs four processing jobs (cut, edge, polish, seal), each adding machine / labour / material
cost, and posts an invoice after each stage so every stage shows its own cost of sales and margin.
"""
from api import STATE, call, day, log, products, rows, save

CATEGORY = "Granite Processing Stages"
# code, name, manufacturing role, price per m², description
STAGES = [
    ("PRC-0-RAW", "Steel Grey Granite - Stage 0: Raw slab", "raw", 150000,
     "Raw quarry slab as bought (about 2.8 x 1.6 m, 20 mm). Sellable as is."),
    ("PRC-1-CUT", "Steel Grey Granite - Stage 1: Cut to size", "semi_finished", 190000,
     "Slab cut to the customer dimensions (after cutting). Raw edges, unpolished."),
    ("PRC-2-EDG", "Steel Grey Granite - Stage 2: Edged", "semi_finished", 240000,
     "Cut pieces with profiled edges (bullnose / bevel), drilled where needed."),
    ("PRC-3-POL", "Steel Grey Granite - Stage 3: Polished", "semi_finished", 300000,
     "Edged pieces with surface polished to a high gloss."),
    ("PRC-4-FIN", "Steel Grey Granite - Stage 4: Sealed countertop (finished)", "finished", 360000,
     "Finished countertop: cut, edged, polished and sealed with stain-proof impregnator. Ready to install."),
    ("PRC-OFFCUT", "Steel Grey Granite offcuts", "semi_finished", 40000,
     "Usable offcuts from cutting (window sills, small tops, mosaic). By-product of stage 1."),
]
IMAGE_FROM = {"PRC-0-RAW": "GR-SLB-SLV", "PRC-1-CUT": "GR-SLB-SLV", "PRC-OFFCUT": "GR-SLB-SLV"}  # others: GR-CTP-STG


def steps(p):
    """code, name, description, input, outputs, expected yield %, cost rates per m²."""
    return [
        ("ST1-CUT", "1. Cutting to size",
         "Cut raw slabs into the requested dimensions. ~85% good pieces, ~10% usable offcuts, ~5% kerf/dust loss.",
         "PRC-0-RAW", [{"productId": p["PRC-1-CUT"], "role": "main", "expectedPct": 85, "marketValue": 190000},
                       {"productId": p["PRC-OFFCUT"], "role": "by_product", "expectedPct": 10, "valuation": "nrv",
                        "nrvUnitValue": 40000}], 95, [("machine", 12000), ("labour", 6000)]),
        ("ST2-EDGE", "2. Edge profiling & drilling",
         "Profile edges (bullnose/bevel) and drill tap/fixing holes. ~2% chipping loss.",
         "PRC-1-CUT", [{"productId": p["PRC-2-EDG"], "role": "main", "expectedPct": 98}], 98,
         [("machine", 15000), ("labour", 10000)]),
        ("ST3-POLISH", "3. Surface polishing", "Grind and polish the surface to a high gloss. ~1% loss.",
         "PRC-2-EDG", [{"productId": p["PRC-3-POL"], "role": "main", "expectedPct": 99}], 99,
         [("machine", 14000), ("labour", 8000), ("consumable", 6000)]),
        ("ST4-SEAL", "4. Sealing & treatment", "Apply stain-proof impregnator and final QC. No loss.",
         "PRC-3-POL", [{"productId": p["PRC-4-FIN"], "role": "main", "expectedPct": 100}], 100,
         [("consumable", 9000), ("labour", 4000)]),
    ]


# step, input m², output m² per template output, loss m², costs, day offset, then a sale (product, m², price)
FLOW = [
    ("ST1-CUT", 40, [34, 4], 2, [("machine", "Bridge saw, 40 m2 x 12,000", 480000),
                                 ("labour", "Cutting crew", 240000)], -15),
    ("ST2-EDGE", 22, [21.56], 0.44, [("machine", "Edge profiler + drilling", 330000),
                                     ("labour", "Edging crew", 220000)], -12),
    ("ST3-POLISH", 14, [13.86], 0.14, [("machine", "Polishing line", 196000), ("labour", "Polishers", 112000),
                                       ("consumable", "Polishing pads & powder", 84000)], -9),
    ("ST4-SEAL", 8, [8], 0, [("consumable", "Stain-proof impregnator", 72000),
                             ("labour", "Sealing + final QC", 32000)], -7),
]
SALES = [  # key, product, m², price, day offset
    ("sale-raw", "PRC-0-RAW", 4.5, 150000, -14), ("sale-cut", "PRC-1-CUT", 8, 190000, -13),
    ("sale-edged", "PRC-2-EDG", 5, 240000, -11), ("sale-polished", "PRC-3-POL", 4, 300000, -8),
    ("sale-finished", "PRC-4-FIN", 3.6, 360000, -6), ("sale-offcuts", "PRC-OFFCUT", 2, 40000, -6),
]
LOT = "SG-DEMO-01"


def ensure_products(ctx):
    have = products("PRC-")
    images = {c: i for c, i in ((p["code"], p.get("image")) for p in
                                rows(call("GET", "/products?pageSize=100&search=GR-")))}
    for code, name, role, price, desc in STAGES:
        if code in have:
            continue
        have[code] = call("POST", "/products", {
            "code": code, "sku": code, "name": name, "description": desc, "productType": "stockable",
            "categoryId": ctx.category(CATEGORY), "uomId": ctx.uom["M2"], "salesUomId": ctx.uom["M2"],
            "purchaseUomId": ctx.uom["M2"], "salesPrice": price, "costPrice": 0, "trackInventory": True,
            "canBeSold": True, "isActive": True, "allowFractionalSale": True, "costingMethod": "AVCO",
            "batchTracking": True, "stockPolicy": "block", "manufacturingRole": role,
            "image": images.get(IMAGE_FROM.get(code, "GR-CTP-STG"))})["id"]
        log("  created", code)
    return have


def ensure_steps(ctx, p):
    existing = {t["code"]: t for t in rows(call("GET", "/manufacturing/processing/templates"))}
    out = {}
    for code, name, desc, inp, outs, yld, rates in steps(p):
        if code in existing:
            out[code] = call("GET", f"/manufacturing/processing/templates/{existing[code]['id']}")
            continue
        t = call("POST", "/manufacturing/processing/templates", {
            "code": code, "name": name, "description": desc, "allocationMethod": "market_value",
            "yieldBasisUnit": "m2", "expectedYieldPct": yld, "defaultInputLocationId": ctx.warehouse,
            "defaultOutputLocationId": ctx.warehouse,
            "costRates": [{"category": c, "ratePerBasis": r} for c, r in rates],
            "inputs": [{"productId": p[inp], "role": "traceable"}], "outputs": outs})
        out[code] = call("POST", f"/manufacturing/processing/templates/{t['id']}/activate")
        log("  step", code)
    if not any(r["code"] == "RT-COUNTERTOP" for r in rows(call("GET", "/manufacturing/processing/routes"))):
        call("POST", "/manufacturing/processing/routes", {
            "code": "RT-COUNTERTOP", "name": "Granite countertop: raw slab to sealed top",
            "description": "Usual order of steps. Sell after any step.", "isActive": True,
            "steps": [{"templateCode": s[0]} for s in steps(p)]})
    return out


def run(ctx):
    p = ensure_products(ctx)
    t = ensure_steps(ctx, p)
    done = STATE.setdefault("processing", {})
    if "grn" not in done:
        g = call("POST", "/procurement/goods-receipts/adhoc", {
            "partnerId": ctx.supplier, "warehouseId": ctx.warehouse, "receivedAt": f"{day(-17)}T08:00:00Z",
            "lines": [{"productId": p["PRC-0-RAW"], "quantity": 45, "unitCost": 90000, "batchNumber": LOT,
                       "description": "10 raw Steel Grey slabs 2.8 x 1.6 m (approx. 45 m2)"}]}, key=True)
        done["grn"] = g["id"]
        save()
        log("  received 45 m² raw slab, lot", LOT)
    lot = LOT
    sales = iter(SALES)
    sell(ctx, p, done, next(sales))
    for step, qty, outs, loss, costs, offset in FLOW:
        if step not in done:
            tpl = t[step]
            j = call("POST", "/manufacturing/processing/jobs/post", {
                "job": {"templateId": tpl["id"], "locationId": ctx.warehouse, "outputLocationId": ctx.warehouse,
                        "jobDate": day(offset), "inputs": [{"templateInputId": tpl["inputs"][0]["id"],
                                                            "batchNumber": lot, "quantity": qty}]},
                "costs": [{"category": c, "description": d, "amount": a, "costDate": day(offset)} for c, d, a in costs],
                "output": {"lines": [{"templateOutputId": tpl["outputs"][i]["id"], "quantity": q}
                                     for i, q in enumerate(outs)],
                           "lossBasis": loss, "complete": True, "date": day(offset)}}, key=True)
            main = j["outputs"][0]
            done[step] = {"job": j.get("code"), "lot": main["batchNumber"], "unitCost": main.get("unitCost")}
            save()
            log(f"  {step}: {j.get('code')} lot {main['batchNumber']} cost/m² {float(main.get('unitCost') or 0):,.0f}")
        lot = done[step]["lot"]
        sell(ctx, p, done, next(sales))
        if step == "ST1-CUT":
            sell(ctx, p, done, SALES[-1])  # offcuts, by-product of cutting


def sell(ctx, p, done, sale):
    key, code, qty, price, offset = sale
    if key in done:
        return
    inv = call("POST", "/invoices", {
        "partnerId": ctx.customer, "issueDate": day(offset), "paymentMode": "credit",
        "notes": f"Granite sold at stage {code}", "lines": [
            {"productId": p[code], "quantity": qty, "unitPrice": price, "lineType": "product"}]}, key=True)
    posted = call("POST", f"/invoices/{inv['id']}/post", key=True)
    done[key] = posted.get("documentNumber") or posted.get("number") or inv["id"]
    save()
    log(f"  sold {qty} m² {code} at {price:,} -> {done[key]}")

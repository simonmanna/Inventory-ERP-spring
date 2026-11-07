"""The costing story: the same block made from three sourcing rounds, so one BOM shows three true unit costs.

Round A  local suppliers, delivered price, no extra charges.
Round B  cheaper invoice prices, but the buyer pays haulage and offloading: landed cost is the real price.
Round C  imported cement in USD, shipment charges split by weight and by value.
Round D  same materials as C, but the press scraps blocks: the yield loss shows in the unit cost.
A late clearing bill then arrives after part of the import is consumed: on-hand and consumed shares split.
Raw materials are AVCO, as in the rest of MENA; each round consumes its whole purchase, so a round's cost is its
supplier's landed cost. The mix and block are FIFO, so every production run stays its own cost layer through to COGS."""
from base import A, C, FACT, MAIN, TODAY, ApiError, core, ids, once, pid, rows, save, sup

STATE = core.STATE

RATE = 3700
LATER = "2026-12-06"


def po(key, supplier, lines, currency=None, rate=None):
    """PO -> send -> receive with a supplier-named batch -> goods bill. lines: (product, qty, unit price, batch).

    The PO and receipt routes do not replay on the Idempotency-Key, so every sub-step is recorded before the next one:
    a failure and re-run must never create a second receipt."""
    def go():
        out = STATE.setdefault("partial", {}).setdefault(key, {})
        if "po" not in out:
            body = {"partnerId": sup(supplier), "warehouseId": FACT, "expectedDeliveryDate": TODAY,
                    "description": "Costing demo " + key, "paymentType": "credit",
                    "lines": [{"productId": pid(c), "description": c, "quantity": q, "unitPrice": u}
                              for c, q, u, _ in lines]}
            # The PO column defaults to USD whatever the base currency, so a local order must name UGX.
            body["currencyCode"], body["exchangeRate"] = currency or "UGX", rate or 1
            out["po"] = A.post("/procurement/purchase-orders", body)["id"]
            save()
            try:
                A.post("/procurement/purchase-orders/%s/send" % out["po"], {})
            except ApiError as e:
                print("   (po send: %s)" % str(e)[:200])
        if "grn" not in out:
            full = A.get("/procurement/purchase-orders/" + out["po"])
            line_ids = {x["productId"]: x["id"] for x in full.get("lines", [])}
            A.post("/procurement/purchase-orders/%s/receive" % out["po"], {
                "warehouseId": FACT, "receivedAt": TODAY, "notes": "Costing demo " + key,
                "lines": [{"purchaseOrderLineId": line_ids[pid(c)], "productId": pid(c), "description": c,
                           "quantity": q, **({"batchNumber": b} if b else {})} for c, q, _, b in lines]})
            grns = [g for g in rows(A.get("/procurement/goods-receipts", pageSize=200))
                    if g.get("purchaseOrderId") == out["po"]]
            out["grn"] = grns[0]["id"]
            save()
        if "bill" not in out:
            bill = {"partnerId": sup(supplier), "issueDate": TODAY, "reference": "INV-CD-" + key,
                    "purchaseOrderIds": [out["po"]],
                    "lines": [{"productId": pid(c), "description": c, "quantity": q, "unitPrice": u}
                              for c, q, u, _ in lines]}
            if currency:
                bill["currencyId"] = currency
            out["bill"] = A.post("/vendor-bills", bill)["id"]
            save()
        post_bill(out["bill"])
        return dict(out)
    return once("po:" + key, go)


def post_bill(bill_id):
    last = None
    for path, who in (("/vendor-bills/%s/post", A), ("/vendor-bills/%s/approve", C), ("/vendor-bills/%s/post", C)):
        try:
            r = who.post(path % bill_id, {})
            if (r or {}).get("status") in ("posted", "open", "partially_paid"):
                return
        except ApiError as e:
            last = e
    if A.get("/vendor-bills/" + bill_id).get("status") not in ("posted", "open", "partially_paid", "paid"):
        raise last


def voucher(key, grns, charges, notes, shipment=None):
    """Draft -> submit (maker) -> approve (checker) -> post. Each charge raises its own supplier bill."""
    def go():
        base = {"date": TODAY, "notes": notes,
                "charges": [{"treatment": "capitalize", "billMode": "generate", "invoiceDate": TODAY, "dueDate": LATER,
                             **c, "supplierId": sup(c["supplierId"])} for c in charges]}
        base.update({"shipmentId": shipment} if shipment else {"goodsReceiptIds": grns})
        made = A.post("/procurement/landed-cost-vouchers", base)
        A.post("/procurement/landed-cost-vouchers/%s/submit" % made["id"], {})
        C.post("/procurement/landed-cost-vouchers/%s/approve" % made["id"], {})
        try:
            A.post("/procurement/landed-cost-vouchers/%s/post" % made["id"], {})
        except ApiError as e:
            if e.status != 403:
                raise
            C.post("/procurement/landed-cost-vouchers/%s/post" % made["id"], {})
        return made["id"]
    return once("voucher:" + key, go)


def make(key, bom_key, qty, loc_out, batch, minutes, good=None, scrap=0):
    """Order -> confirm -> start (issues materials FIFO) -> work orders with hours -> absorb rates -> complete."""
    def go():
        bom = ids("steps")["bom:" + bom_key]
        order = A.post("/manufacturing/production-orders", {
            "bomId": bom, "plannedQty": qty, "locationId": FACT, "outputLocationId": loc_out,
            "notes": "Costing demo " + key})["id"]
        A.post("/manufacturing/production-orders/%s/confirm" % order, {})
        A.post("/manufacturing/production-orders/%s/start" % order, {})
        wos = rows(A.get("/manufacturing/production-orders/%s/work-orders" % order))
        if not wos:
            wos = A.post("/manufacturing/production-orders/%s/work-orders/generate" % order, {}, key="wo-gen-" + key)
            wos = rows(wos) if not isinstance(wos, list) else wos
        for wo in sorted(wos, key=lambda w: w.get("operationSequence", 0)):
            A.post("/manufacturing/work-orders/%s/start" % wo["id"], {})
            A.post("/manufacturing/work-orders/%s/complete" % wo["id"], {
                "labourMins": minutes[wo["name"]], "qtyGood": good if good is not None else qty, "qtyScrap": scrap,
                "notes": "Costing demo " + key})
        A.post("/manufacturing/costing/orders/%s/absorb" % order, {})
        A.post("/manufacturing/production-orders/%s/complete" % order, {
            "qtyProduced": good if good is not None else qty, "scrapQty": scrap, "batchNumber": batch,
            "date": TODAY, "notes": "Costing demo " + key})
        return order
    return once("mo:" + key, go)


def run():
    # ---- common consumables, enough for every round
    po("ADMIX-LOCAL", "S-ABRAS", [("CD-ADMIX", 100, 18000, None), ("CD-WRAP", 240, 28000, None)])
    po("WATER", "S-WATER", [("CD-WATER", 90000, 6, None)])

    # ---- Round A: delivered prices from local suppliers
    po("A-CEM", "S-HIMA", [("CD-CEM", 300, 36500, None)])
    po("A-SAND", "S-SAND", [("CD-SAND", 90, 42000, None)])
    po("A-DUST", "S-NAMANVE", [("CD-DUST", 70, 34000, None)])
    # The plan: frozen while Round A stock is what the BOM would cost.
    once("standard:mix", lambda: A.post("/manufacturing/boms/%s/standard-cost" % ids("steps")["bom:mix"], {})["id"])
    once("standard:blk", lambda: A.post("/manufacturing/boms/%s/standard-cost" % ids("steps")["bom:blk"], {})["id"])
    make("MIX-A", "mix", 100, FACT, "MIX-A", {"Batching and mixing": 600})
    make("BLK-A", "blk", 12500, MAIN, "BLK-A-LOCAL", {"Pressing": 1875, "Curing": 750, "Palletising": 562})

    # ---- Round B: cheaper invoices, buyer pays the transport
    b_cem = po("B-CEM", "S-KASESE", [("CD-CEM", 300, 33500, None)])
    b_sand = po("B-SAND", "S-MASAKA", [("CD-SAND", 90, 26000, None)])
    b_dust = po("B-DUST", "S-MUKONO", [("CD-DUST", 70, 31000, None)])
    voucher("B-CEM", [b_cem["grn"]], [
        {"kind": "freight", "description": "Haulage Kasese to Namasuba, 300 bags", "supplierId": "S-TIPPER",
         "supplierInvoiceRef": "CD-TH-1001", "amount": 960000, "allocationMethod": "quantity"},
        {"kind": "handling", "description": "Offloading 300 bags", "supplierId": "S-CLEAR",
         "supplierInvoiceRef": "CD-CF-2001", "amount": 120000, "allocationMethod": "quantity"}],
        "Cement from Kasese: transport and offloading paid by buyer")
    voucher("B-SAND", [b_sand["grn"]], [
        {"kind": "freight", "description": "Haulage Masaka to Namasuba, 90 t", "supplierId": "S-TIPPER",
         "supplierInvoiceRef": "CD-TH-1002", "amount": 1260000, "allocationMethod": "quantity"}],
        "Sand from Masaka: long haul paid by buyer")
    voucher("B-DUST", [b_dust["grn"]], [
        {"kind": "freight", "description": "Haulage Mukono to Namasuba, 70 t", "supplierId": "S-TIPPER",
         "supplierInvoiceRef": "CD-TH-1003", "amount": 350000, "allocationMethod": "quantity"}],
        "Stone dust from Mukono: haulage paid by buyer")
    make("MIX-B", "mix", 100, FACT, "MIX-B", {"Batching and mixing": 600})
    make("BLK-B", "blk", 12500, MAIN, "BLK-B-HAULED", {"Pressing": 1875, "Curing": 750, "Palletising": 562})

    # ---- Round C: import shipment in USD with charges in USD and UGX
    c_cem = po("C-CEM", "S-MOMBASA", [("CD-CEM", 900, 7.80, None)], "USD", RATE)
    c_adm = po("C-ADMIX", "S-ADMIX", [("CD-ADMIX", 150, 5.00, None)], "USD", RATE)
    shipment = once("shipment:C", lambda: A.post("/procurement/shipments", {
        "reference": "SHP-CD-MOMBASA-01", "supplierId": sup("S-MOMBASA"), "transportMode": "road",
        "billOfLading": "CD-BL-5501", "origin": "Mombasa, Kenya", "incoterm": "FOB", "status": "arrived",
        "arrivedAt": TODAY, "notes": "Costing demo import shipment"})["id"])
    for key, grn in (("cem", c_cem["grn"]), ("adm", c_adm["grn"])):
        once("shipment-receipt:" + key, lambda g=grn: A.post("/procurement/shipments/%s/receipts" % shipment,
                                                              {"goodsReceiptId": g}) and g)
    voucher("C-SHIP", None, [
        {"kind": "freight", "description": "Road freight Mombasa to Namasuba (USD 1,050 at 3,720)",
         "supplierId": "S-TIPPER", "supplierInvoiceRef": "CD-TH-1004", "amount": 3906000,
         "allocationMethod": "weight"},
        {"kind": "duty", "description": "Customs duty and clearing", "supplierId": "S-CLEAR",
         "supplierInvoiceRef": "CD-CF-2002", "amount": 2250000, "allocationMethod": "value"},
        {"kind": "insurance", "description": "Marine and transit insurance", "supplierId": "S-INSURE",
         "supplierInvoiceRef": "CD-EM-3001", "amount": 270000, "allocationMethod": "value"}],
        "Import shipment: freight by weight, duty and insurance by value", shipment=shipment)
    c_sand = po("C-SAND", "S-MASAKA", [("CD-SAND", 270, 26000, None)])
    voucher("C-SAND", [c_sand["grn"]], [
        {"kind": "freight", "description": "Haulage Masaka to Namasuba, 270 t", "supplierId": "S-TIPPER",
         "supplierInvoiceRef": "CD-TH-1005", "amount": 3780000, "allocationMethod": "quantity"}],
        "Sand for the import rounds: best landed sand price")
    po("C-DUST", "S-NAMANVE", [("CD-DUST", 210, 34000, None)])
    make("MIX-C", "mix", 100, FACT, "MIX-C", {"Batching and mixing": 600})
    make("BLK-C", "blk", 12500, MAIN, "BLK-C-IMPORT", {"Pressing": 1875, "Curing": 750, "Palletising": 562})

    # ---- Round D: same materials as C, poor press run. Planned 12,500, good 11,800, 700 scrapped.
    make("MIX-D", "mix", 100, FACT, "MIX-D", {"Batching and mixing": 600})
    make("BLK-D", "blk", 12500, MAIN, "BLK-D-SCRAP", {"Pressing": 2100, "Curing": 750, "Palletising": 562},
         good=11800, scrap=700)

    # ---- Late bill: 600 of 900 import bags are already consumed, so 2/3 cannot go to stock
    voucher("C-LATE", [c_cem["grn"]], [
        {"kind": "other", "description": "Late port demurrage bill", "supplierId": "S-CLEAR",
         "supplierInvoiceRef": "CD-CF-2003", "amount": 900000, "allocationMethod": "quantity"}],
        "Late demurrage after two production runs: consumed share leaves stock")

    # ---- Top-up from Hima at a higher price while import stock is on hand: AVCO blends two suppliers
    po("E-CEM", "S-HIMA", [("CD-CEM", 300, 37000, None)])

    # ---- Sales: FIFO carries each round's cost into the margin
    def sell(key, customer, qty, price):
        def go():
            inv = A.post("/invoices", {
                "partnerId": sup(customer), "issueDate": TODAY, "dueDate": LATER, "reference": "CD-" + key,
                "lines": [{"productId": pid("CD-BLK6"), "description": "Hollow block 6 inch - costing demo",
                           "quantity": qty, "unitPrice": price}]})
            A.post("/invoices/%s/post" % inv["id"], {})
            return inv["id"]
        return once("sale:" + key, go)
    sell("SALE-1", "C-CANAN", 10000, 2400)
    sell("SALE-2", "C-OPHIR", 6000, 2400)
    save()

    # ---- Period close: push the late cost out of WIP of the completed orders into their output layers
    once("cost-adjustment", lambda: A.post("/costing/cost-adjustment-runs", {"from": "2026-11-01", "to": "2026-11-30"})["id"])

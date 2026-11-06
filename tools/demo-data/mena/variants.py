"""Product variants demo (ADR-S058): shared variant attributes, products with attribute lines (generated variants),
price extras, fixed variant prices / costs, SKUs and barcodes, an archived combination, and documents that carry
the variant: purchase order -> receipt -> vendor bill, stock count, sales order -> delivery -> invoice, an open
quotation and a draft sales order to edit.

New products only (codes VAR-*): existing products and their stock are untouched.

    ERP_BASE=http://localhost:3001/api/v1 python run.py variants
"""
from api import rows
import flows as F
import seed as S

D = "2026-11-06"   # stock services can't backdate: documents are dated the run day

# name, displayType, [(value, html colour)]
ATTRIBUTES = [
    ("Stone colour", "color", [("Royal Red", "#9B2D30"), ("Spotted Green", "#4F6B4A"), ("Galaxy Black", "#1C1C1C"),
                               ("Viscount White", "#ECEBE6"), ("Wave Gray", "#8A8D91"), ("Dragon Fire", "#B5532A")]),
    ("Thickness", "pills", [("20 mm", None), ("30 mm", None)]),
    ("Edge profile", "select", [("Straight", None), ("Bullnose", None), ("Ogee", None)]),
    ("Block size", "radio", [("4 inch", None), ("6 inch", None), ("8 inch", None)]),
    ("Surface finish", "pills", [("Polished", None), ("Honed", None), ("Flamed", None)]),
    ("Tile size", "pills", [("30x60 cm", None), ("60x60 cm", None)]),
    ("Vanity width", "pills", [("60 cm", None), ("90 cm", None), ("120 cm", None)]),
]

SKU_CODE = {"Royal Red": "RR", "Spotted Green": "SG", "Galaxy Black": "GB", "Viscount White": "VW",
            "Wave Gray": "WG", "Dragon Fire": "DF", "20 mm": "20", "30 mm": "30", "Straight": "S", "Bullnose": "B",
            "Ogee": "O", "4 inch": "4", "6 inch": "6", "8 inch": "8", "Polished": "P", "Honed": "H", "Flamed": "F",
            "30x60 cm": "3060", "60x60 cm": "6060", "60 cm": "60", "90 cm": "90", "120 cm": "120"}

# code, name, category, uom, sales price, cost, {attribute: [values]}, {(attribute, value): price extra}
PRODUCTS = {
    "MENA": [
        ("VAR-WTOP", "Granite kitchen worktop", "Granite Slabs", "SQM", 180000, 95000,
         {"Stone colour": ["Royal Red", "Spotted Green", "Galaxy Black", "Viscount White"],
          "Thickness": ["20 mm", "30 mm"], "Edge profile": ["Straight", "Bullnose"]},
         {("Stone colour", "Spotted Green"): 20000, ("Stone colour", "Galaxy Black"): 40000,
          ("Stone colour", "Viscount White"): 85000, ("Thickness", "30 mm"): 45000, ("Edge profile", "Bullnose"): 15000}),
        ("VAR-BLOCK", "Hollow concrete block (by size)", "Concrete Blocks", "UNIT", 1800, 1150,
         {"Block size": ["4 inch", "6 inch", "8 inch"]},
         {("Block size", "6 inch"): 500, ("Block size", "8 inch"): 1000}),
        ("VAR-TILE", "Granite floor tile", "Granite Slabs", "SQM", 95000, 52000,
         {"Stone colour": ["Royal Red", "Spotted Green", "Wave Gray", "Dragon Fire"],
          "Surface finish": ["Polished", "Honed", "Flamed"], "Tile size": ["30x60 cm", "60x60 cm"]},
         {("Stone colour", "Spotted Green"): 25000, ("Stone colour", "Wave Gray"): 30000,
          ("Surface finish", "Polished"): 12000, ("Surface finish", "Flamed"): 8000, ("Tile size", "60x60 cm"): 6000}),
    ],
    "MENA-SHOW": [
        ("VAR-VANITY", "Granite vanity top", "Granite Slabs", "UNIT", 450000, 260000,
         {"Stone colour": ["Royal Red", "Galaxy Black", "Viscount White"], "Vanity width": ["60 cm", "90 cm", "120 cm"]},
         {("Stone colour", "Galaxy Black"): 60000, ("Stone colour", "Viscount White"): 150000,
          ("Vanity width", "90 cm"): 120000, ("Vanity width", "120 cm"): 260000}),
    ],
}

# Fixed overrides: (product code, variant name) -> {salesPrice?, costPrice?}
OVERRIDES = {
    "MENA": {
        ("VAR-WTOP", "Viscount White, 20 mm, Straight"): {"costPrice": 160000},    # imported slab cost
        # fixed price instead of the calculated 325,000 (180k + 85k + 45k + 15k), fixed imported cost
        ("VAR-WTOP", "Viscount White, 30 mm, Bullnose"): {"salesPrice": 340000, "costPrice": 175000},
        ("VAR-BLOCK", "6 inch"): {"costPrice": 1400},
        ("VAR-BLOCK", "8 inch"): {"costPrice": 1750},
    },
    "MENA-SHOW": {
        ("VAR-VANITY", "Viscount White, 120 cm"): {"salesPrice": 820000},
    },
}


def _ids(org, kind):
    return S.st(org, kind)


def attributes(org):
    """Shared attribute masters (by name); value ids kept under state['vvalue']['<attr>|<value>']."""
    a = S.api(org)
    attrs, vals = _ids(org, "vattr"), _ids(org, "vvalue")
    existing = {x["name"]: x for x in a.get("/variant-attributes")}
    for name, kind, values in ATTRIBUTES:
        cur = existing.get(name)
        if cur is None:
            cur = a.post("/variant-attributes", {"name": name, "displayType": kind, "values": [
                {"name": v, **({"htmlColor": c} if c else {})} for v, c in values]})
        attrs[name] = cur["id"]
        for v in cur.get("values", []):
            vals[name + "|" + v["name"]] = v["id"]
    S.save()


def configure(org, code, name, cat, uom, price, cost, lines, extras):
    a = S.api(org)
    u, pc = _ids(org, "uom"), _ids(org, "pcat")
    pid = S.ensure(org, "product", code, "/products", "/products", {
        "code": code, "name": name, "sku": code, "categoryId": pc[cat], "uomId": u[uom], "salesUomId": u[uom],
        "purchaseUomId": u[uom], "productType": "stockable", "trackInventory": True, "costingMethod": "AVCO",
        "salesPrice": price, "costPrice": cost, "allowFractionalSale": uom != "UNIT"})
    attrs, vals = _ids(org, "vattr"), _ids(org, "vvalue")
    cfg = a.put("/products/%s/attribute-lines" % pid, {"lines": [
        {"attributeId": attrs[at], "valueIds": [vals[at + "|" + v] for v in vs]} for at, vs in lines.items()]})
    for line in cfg["lines"]:
        at = line["attributeName"]
        want = [{"valueId": v["valueId"], "priceExtra": extras.get((at, v["name"]), 0)} for v in line["values"]]
        if any(float(v.get("priceExtra") or 0) != w["priceExtra"] for v, w in zip(line["values"], want)):
            cfg = a.patch("/products/%s/attribute-lines/%s/values" % (pid, line["id"]), {"values": want})
    return pid, cfg


def finish_variants(org, code, pid, overrides, seq0):
    """SKU + barcode per variant, fixed prices / costs; returns {variant name: id}."""
    a = S.api(org)
    out = {}
    for i, v in enumerate(a.get("/products/%s/variants" % pid)):
        out[v["name"]] = v["id"]
        if not v.get("isActive"):
            continue
        sku = code.replace("VAR-", "") + "-" + "-".join(SKU_CODE[x.strip()] for x in v["name"].split(","))
        body = {}
        if v.get("sku") != sku:
            body["sku"] = sku
        if not v.get("barcode"):
            body["barcode"] = "619%010d" % (seq0 + i)
        body.update({k: x for k, x in overrides.get((code, v["name"]), {}).items()
                     if float(v.get("fixedPrice" if k == "salesPrice" else "fixedCost") or -1) != x})
        if body:
            a.patch("/products/%s/variants/%s" % (pid, v["id"]), body)
    S.st(org, "variant").update({code + "|" + n: i for n, i in out.items()})
    S.save()
    return out


def purchase_variants(org, key, supplier, loc, lines):
    """PO (lines carry the variant) -> send -> receive all -> vendor bill with variants -> post (maker-checker)."""
    a = S.api(org)
    p, par, l, var = _ids(org, "product"), _ids(org, "partner"), _ids(org, "location"), _ids(org, "variant")

    def run():
        out = S.STATE[org].setdefault("partial", {}).setdefault(key, {})
        if not out.get("po"):
            po = a.post("/procurement/purchase-orders", {
                "partnerId": par[supplier], "warehouseId": l[loc], "expectedDeliveryDate": D, "description": key,
                "paymentType": "credit", "lines": [
                    {"productId": p[c], "variantId": var[c + "|" + vn], "description": "%s (%s)" % (c, vn),
                     "quantity": q, "unitPrice": u} for c, vn, q, u in lines]})
            out["po"] = po["id"]
            S.save()
            a.post("/procurement/purchase-orders/%s/send" % po["id"], {})
        if not out.get("grn"):
            po = a.get("/procurement/purchase-orders/" + out["po"])
            a.post("/procurement/purchase-orders/%s/receive" % out["po"], {
                "warehouseId": l[loc], "receivedAt": D + "T09:00:00Z", "lines": [
                    {"purchaseOrderLineId": x["id"], "productId": x["productId"], "description": x.get("description"),
                     "quantity": x["quantity"]} for x in po["lines"]]})
            grns = [g for g in rows(a.get("/procurement/goods-receipts", pageSize=200))
                    if g.get("purchaseOrderId") == out["po"]]
            out["grn"] = grns[0]["id"]
            S.save()
        if not out.get("bill"):
            bl = a.post("/vendor-bills", {
                "partnerId": par[supplier], "issueDate": D, "reference": "INV-" + key, "purchaseOrderIds": [out["po"]],
                "lines": [{"productId": p[c], "variantId": var[c + "|" + vn], "description": "%s (%s)" % (c, vn),
                           "quantity": q, "unitPrice": u} for c, vn, q, u in lines]})
            out["bill"] = bl["id"]
            S.save()
        post_bill(org, out["bill"])   # also on a re-run after a failed post
        return out
    return F.once(org, key, run)


def post_bill(org, bill_id):
    """Post as maker, else approve + post as checker (keys versioned: an earlier refused post stays pending)."""
    a, c = S.api(org), S.checker(org)
    if a.get("/vendor-bills/" + bill_id).get("status") in ("posted", "open", "partially_paid", "paid"):
        return
    for path, who in (("/vendor-bills/%s/post", a), ("/vendor-bills/%s/approve", c), ("/vendor-bills/%s/post", c)):
        try:
            who.post(path % bill_id, {}, key="var2-" + path % bill_id + who.who)
        except S.ApiError as e:
            print("   (%s: %s)" % (path % bill_id, str(e)[:160]))
        if a.get("/vendor-bills/" + bill_id).get("status") in ("posted", "open", "partially_paid", "paid"):
            return
    raise S.ApiError("POST", "/vendor-bills/%s/post" % bill_id, 0, "bill not posted")


def count_variants(org, key, loc, items, reason):
    """Stock count per variant (adjustment -> approve)."""
    a = S.api(org)
    p, l, var, uid = _ids(org, "product"), _ids(org, "location"), _ids(org, "variant"), S.STATE[org]["userId"]

    def run():
        adj = a.post("/inventory/adjustments", {"locationId": l[loc], "responsibleById": uid, "approvedById": uid,
                                                "reason": "initial_count", "notes": reason, "items": [
                {"productId": p[c], "variantId": var[c + "|" + vn], "qtyActual": q} for c, vn, q in items]})
        a.post("/inventory/adjustments/%s/approve" % adj["id"], {})
        return adj["id"]
    return F.once(org, key, run)


def draft_documents(org, customer, loc, so_lines, qt_lines, tag):
    """An open quotation (submitted + sent) and a draft sales order, both with variant lines, to edit in the web."""
    a = S.api(org)
    p, par, l, var = _ids(org, "product"), _ids(org, "partner"), _ids(org, "location"), _ids(org, "variant")

    def quote():
        q = a.post("/sales/quotations", {"partnerId": par[customer], "validUntil": "2026-11-30",
                                         "notes": "Variant demo: prices come from each variant (colour, size, finish).",
                                         "lines": [{"productId": p[c], "variantId": var[c + "|" + vn], "quantity": n}
                                                   for c, vn, n in qt_lines]})
        a.post("/sales/quotations/%s/submit" % q["id"], {})
        a.post("/sales/quotations/%s/send" % q["id"], {})
        return q["id"]
    F.once(org, tag + "-QT", quote)

    def order():
        return a.post("/sales/orders", {"partnerId": par[customer], "locationId": l[loc],
                                        "customerRef": tag + " draft", "requestedDate": "2026-11-12", "lines": [
                {"productId": p[c], "variantId": var[c + "|" + vn], "quantity": n} for c, vn, n in so_lines]})["id"]
    F.once(org, tag + "-SO-DRAFT", order)


def archive_demo(org):
    """Drop 'Flamed' from the floor tile: its 8 combinations are archived (kept for history); archive one by hand."""
    a = S.api(org)
    pid, attrs, vals = _ids(org, "product")["VAR-TILE"], _ids(org, "vattr"), _ids(org, "vvalue")

    def run():
        lines = {"Stone colour": ["Royal Red", "Spotted Green", "Wave Gray", "Dragon Fire"],
                 "Surface finish": ["Polished", "Honed"], "Tile size": ["30x60 cm", "60x60 cm"]}
        a.put("/products/%s/attribute-lines" % pid, {"lines": [
            {"attributeId": attrs[at], "valueIds": [vals[at + "|" + v] for v in vs]} for at, vs in lines.items()]})
        vid = _ids(org, "variant")["VAR-TILE|Dragon Fire, Honed, 30x60 cm"]
        a.patch("/products/%s/variants/%s" % (pid, vid), {"isActive": False})
        return pid
    F.once(org, "VAR-TILE-ARCHIVE", run)


def invoice_rest(org, key):
    """Bill what the first invoice missed (trip-close quantities arrive through an after-commit event)."""
    import time
    a = S.api(org)
    sid = S.STATE[org]["steps"][key]["so"]

    def run():
        out = []
        for attempt in range(5):
            lines = a.get("/sales/orders/" + sid).get("lines", [])
            if attempt and all(float(l.get("invoicedQty") or 0) >= float(l.get("quantity") or 0) for l in lines):
                break
            try:
                inv = a.call("POST", "/sales/orders/%s/invoices" % sid, {"issueDate": D},
                             key="var-rest-%s-%s" % (sid, attempt))
            except S.ApiError as e:
                if "Nothing to invoice" not in str(e):
                    raise
                time.sleep(3)
                continue
            i = inv.get("invoice") or inv
            a.post("/invoices/%s/post" % i["id"], {})
            out.append(i["id"])
        return out
    F.once(org, key + "-REST", run)


def summary(org):
    a = S.api(org)
    for code, *_ in PRODUCTS[org]:
        pid = _ids(org, "product")[code]
        vs = a.get("/products/%s/variants" % pid)
        active = [v for v in vs if v["isActive"]]
        stocked = [v for v in vs if float(v.get("onHand") or 0)]
        print("  %-10s %2d variants (%d active, %d archived), %d with stock: %s" % (
            code, len(vs), len(active), len(vs) - len(active), len(stocked),
            ", ".join("%s=%s" % (v["name"], v["onHand"]) for v in stocked)))


def variants():
    for org in PRODUCTS:
        attributes(org)
    seq = 1000000
    for org, prods in PRODUCTS.items():
        for code, name, cat, uom, price, cost, lines, extras in prods:
            # Once per product: a later step (archive_demo) changes the lines and must not be undone on a re-run.
            pid = F.once(org, "VAR-CONF-" + code, lambda: configure(org, code, name, cat, uom, price, cost, lines,
                                                                     extras)[0])
            finish_variants(org, code, pid, OVERRIDES.get(org, {}), seq)
            seq += 1000

    o = "MENA"
    purchase_variants(o, "HQ-PO-VAR-STONE-01", "S-IMPORT", "HQ-MAIN", [
        ("VAR-WTOP", "Royal Red, 20 mm, Straight", 40, 90000),
        ("VAR-WTOP", "Galaxy Black, 20 mm, Straight", 30, 118000),
        ("VAR-WTOP", "Spotted Green, 30 mm, Bullnose", 15, 126000),
        ("VAR-WTOP", "Viscount White, 30 mm, Bullnose", 20, 168000),
        ("VAR-TILE", "Royal Red, Polished, 60x60 cm", 120, 54000),
        ("VAR-TILE", "Spotted Green, Honed, 30x60 cm", 80, 61000),
        ("VAR-TILE", "Wave Gray, Polished, 60x60 cm", 100, 66000),
        ("VAR-TILE", "Dragon Fire, Flamed, 60x60 cm", 40, 58000),   # archived below; its stock stays visible
    ])
    count_variants(o, "HQ-COUNT-VAR-BLOCK-01", "HQ-MAIN", [
        ("VAR-BLOCK", "4 inch", 5000), ("VAR-BLOCK", "6 inch", 3000), ("VAR-BLOCK", "8 inch", 1500)],
        "Opening count of sized hollow blocks")
    var = _ids(o, "variant")
    F.sales_order(o, "HQ-SO-VAR-CANAN-01", "C-CANAN", [
        ("VAR-WTOP", 12, None, var["VAR-WTOP|Royal Red, 20 mm, Straight"]),
        ("VAR-WTOP", 6, None, var["VAR-WTOP|Viscount White, 30 mm, Bullnose"]),
        ("VAR-BLOCK", 1200, None, var["VAR-BLOCK|6 inch"]),
        ("VAR-BLOCK", 800, None, var["VAR-BLOCK|8 inch"])], "HQ-MAIN", D, transporter="S-TIPPER", rate=150000)
    invoice_rest(o, "HQ-SO-VAR-CANAN-01")
    F.sales_order(o, "HQ-SO-VAR-ZERI-01", "C-ZERI", [
        ("VAR-TILE", 45, None, var["VAR-TILE|Wave Gray, Polished, 60x60 cm"]),
        ("VAR-TILE", 30, None, var["VAR-TILE|Royal Red, Polished, 60x60 cm"])], "HQ-MAIN", D, transporter="S-TIPPER", rate=150000)
    invoice_rest(o, "HQ-SO-VAR-ZERI-01")
    archive_demo(o)
    draft_documents(o, "C-OPHIR", "HQ-MAIN",
                    so_lines=[("VAR-WTOP", "Galaxy Black, 20 mm, Straight", 8), ("VAR-BLOCK", "4 inch", 600)],
                    qt_lines=[("VAR-WTOP", "Spotted Green, 30 mm, Bullnose", 10),
                              ("VAR-WTOP", "Viscount White, 30 mm, Bullnose", 4),
                              ("VAR-TILE", "Spotted Green, Honed, 30x60 cm", 60)], tag="HQ-VAR-OPHIR")

    o = "MENA-SHOW"
    count_variants(o, "SHW-COUNT-VAR-VANITY-01", "SHW-KBL", [
        ("VAR-VANITY", "Royal Red, 60 cm", 6), ("VAR-VANITY", "Royal Red, 90 cm", 4),
        ("VAR-VANITY", "Galaxy Black, 90 cm", 5), ("VAR-VANITY", "Viscount White, 120 cm", 2)],
        "Showroom count of vanity tops")
    var = _ids(o, "variant")
    F.sales_order(o, "SHW-SO-VAR-KARIM-01", "C-KARIM", [
        ("VAR-VANITY", 2, None, var["VAR-VANITY|Galaxy Black, 90 cm"]),
        ("VAR-VANITY", 1, None, var["VAR-VANITY|Viscount White, 120 cm"])], "SHW-KBL", D, transporter="S-TIPPER", rate=60000)
    invoice_rest(o, "SHW-SO-VAR-KARIM-01")
    draft_documents(o, "C-MICH", "SHW-KBL", so_lines=[("VAR-VANITY", "Royal Red, 60 cm", 2)],
                    qt_lines=[("VAR-VANITY", "Royal Red, 90 cm", 1), ("VAR-VANITY", "Galaxy Black, 60 cm", 1)],
                    tag="SHW-VAR-MICH")
    for org in PRODUCTS:
        print("==", org)
        summary(org)

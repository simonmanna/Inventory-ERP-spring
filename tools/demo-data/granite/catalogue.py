"""Granite and aggregate products with photos, sell-by-size (m, m², m³) and variants by colour, size and finish."""
import os

from api import IMAGES, attributes, call, configure_variants, log, products, rows, sizing, upload

ATTRS = {
    "Thickness": ("select", [("18 mm", None), ("20 mm", None), ("30 mm", None)]),
    "Finish": ("pills", [("Polished", None), ("Honed", None), ("Flamed", None)]),
    "Tile Size": ("pills", [("30 x 30 cm", None), ("60 x 60 cm", None), ("60 x 120 cm", None)]),
    "Edge": ("pills", [("Straight", None), ("Bullnose", None), ("Ogee", None)]),
    "Step Length": ("pills", [("1.0 m", None), ("1.2 m", None), ("1.5 m", None)]),
    "Skirting Height": ("pills", [("10 cm", None), ("15 cm", None)]),
    "Colour": ("color", [("Tan Brown", "#6B4A3A"), ("Absolute Black", "#111111"), ("Steel Grey", "#6B7280"),
                         ("Multicolour Red", "#9B3B2A"), ("Kuppam Green", "#5E6B5A")]),
    "Grade": ("select", [("Stone dust 0-5 mm", None), ("Crushed stone 20-25 mm", None),
                         ("Crushed stone 26-27 mm", None), ("Hardcore 40 mm+", None)]),
}
SLAB = [("Thickness", [("20 mm", 0), ("30 mm", 60000)]), ("Finish", [("Polished", 0), ("Honed", 0), ("Flamed", -10000)])]
TILE = [("Tile Size", [("30 x 30 cm", 0), ("60 x 60 cm", 10000), ("60 x 120 cm", 25000)]),
        ("Finish", [("Polished", 0), ("Honed", 0)])]
TOP = [("Thickness", [("20 mm", 0), ("30 mm", 70000)]), ("Edge", [("Straight", 0), ("Bullnose", 25000), ("Ogee", 45000)])]

# code, name, image, category, sizing mode, unit, price, cost, default (L, W, H), variant lines, description
PRODUCTS = [
    ("GR-SLB-PAR", "Paradiso Wave Granite Slab", "1", "Granite Slabs", "area", "M2", 280000, 165000, (2.8, 1.6, None), SLAB,
     "Full granite slab with pink, grey and black waves. Sold per m² (pieces × length × width)."),
    ("GR-SLB-SLV", "Silver Grey Granite Slab", "2", "Granite Slabs", "area", "M2", 180000, 105000, (2.8, 1.6, None), SLAB,
     "Light grey fine-grain granite slab for floors, walls and worktops. Sold per m²."),
    ("GR-SLB-SGR", "Sea Green Granite Slab", "4", "Granite Slabs", "area", "M2", 200000, 118000, (2.8, 1.6, None), SLAB,
     "Grey-green granite slab with dark veins. Sold per m²."),
    ("GR-SLB-MCR", "Multicolour Red Granite Slab", "5", "Granite Slabs", "area", "M2", 260000, 152000, (2.8, 1.6, None), SLAB,
     "Red, pink and blue-grey wave pattern granite slab. Sold per m²."),
    ("GR-TIL-VIS", "Viscount White Granite Tile", "3", "Granite Floor Tiles", "area", "M2", 165000, 95000, (0.6, 0.6, None),
     TILE, "White and grey swirl granite tile. Sold per m²; choose a tile size."),
    ("GR-TIL-ABK", "Absolute Black Granite Floor Tile", "7", "Granite Floor Tiles", "area", "M2", 150000, 88000,
     (0.6, 0.6, None), TILE, "Polished black granite floor tile. Sold per m²; choose a tile size."),
    ("GR-TIL-IMR", "Imperial Red Granite Floor Tile", "12", "Granite Floor Tiles", "area", "M2", 170000, 99000,
     (0.6, 0.6, None), TILE, "Warm red-tan granite floor tile. Sold per m²; choose a tile size."),
    ("GR-TIL-RSP", "Rosy Pink Granite Floor Tile", "15", "Granite Floor Tiles", "area", "M2", 160000, 93000, (0.6, 0.6, None),
     TILE, "Pink, black and white speckled granite floor tile. Sold per m²; choose a tile size."),
    ("GR-CTP-BBR", "Baltic Brown Granite Countertop", "8", "Granite Countertops", "area", "M2", 320000, 185000,
     (3.0, 0.6, None), TOP, "Kitchen countertop, brown and black granite, cut to size. Sold per m² (default 3.0 × 0.6 m)."),
    ("GR-CTP-STG", "Steel Grey Granite Countertop", "9", "Granite Countertops", "area", "M2", 300000, 175000,
     (3.0, 0.6, None), TOP, "Kitchen countertop, steel grey granite with white flecks, cut to size. Sold per m²."),
    ("GR-STP-KGR", "Kuppam Green Granite Steps", "10", "Granite Stairs & Skirting", "length", "M", 120000, 70000,
     (1.2, None, None), [("Step Length", [("1.0 m", 0), ("1.2 m", 0), ("1.5 m", 0)]), ("Finish", [("Polished", 0), ("Honed", 0)])],
     "Granite stair tread with nosing, 30 cm deep. Sold per running metre (pieces × length)."),
    ("GR-STP-TRD", "Granite Stair Treads", "13", "Granite Stairs & Skirting", "length", "M", 110000, 64000, (1.2, None, None),
     [("Colour", [("Tan Brown", 0), ("Absolute Black", 15000), ("Steel Grey", 5000)]),
      ("Step Length", [("1.0 m", 0), ("1.2 m", 0), ("1.5 m", 0)])],
     "Granite stair tread, 30 cm deep, in three colours. Sold per running metre."),
    ("GR-SKT", "Granite Skirting", "16", "Granite Stairs & Skirting", "length", "M", 45000, 26000, (2.4, None, None),
     [("Colour", [("Multicolour Red", 0), ("Absolute Black", 5000), ("Steel Grey", 0), ("Kuppam Green", 0)]),
      ("Skirting Height", [("10 cm", 0), ("15 cm", 12000)])],
     "Polished granite skirting strip. Sold per running metre."),
    ("AGG-CRS", "Crushed Stone Aggregates", "6", "Aggregates", "volume", "M3", 75000, 42000, (4.0, 2.2, 1.0),
     [("Grade", [("Stone dust 0-5 mm", -20000), ("Crushed stone 20-25 mm", 0), ("Crushed stone 26-27 mm", 0),
                 ("Hardcore 40 mm+", -15000)])],
     "Aggregates for roads, drainage and concrete. Sold per m³ (loads × length × width × height), per tonne or per truck."),
]
TILE_DIMS = {"30 x 30 cm": (0.3, 0.3), "60 x 60 cm": (0.6, 0.6), "60 x 120 cm": (1.2, 0.6)}
STEP_DIMS = {"1.0 m": 1.0, "1.2 m": 1.2, "1.5 m": 1.5}


def ensure_units(ctx):
    """Square metre (Area category) plus the quantity units the services use."""
    if "M2" not in ctx.uom:
        cats = {c["name"]: c for c in rows(call("GET", "/uom-categories"))}
        if "Area" not in cats:
            cats["Area"] = call("POST", "/uom-categories", {"name": "Area"})
        ctx.uom["M2"] = call("POST", "/uoms", {"code": "M2", "name": "Square metre", "symbol": "m²",
                                               "categoryId": cats["Area"]["id"], "category": "area", "factor": 1,
                                               "ratio": 1, "uomType": "reference", "isBase": True})["id"]
        call("PATCH", f"/uom-categories/{cats['Area']['id']}", {"referenceUomId": ctx.uom["M2"]})
    missing = [c for c in ("M", "M3", "UNIT") if c not in ctx.uom]
    if missing:
        raise SystemExit(f"Units {missing} are missing in this organisation (they come with the reference data).")


def variant_dims(product, v):
    a, body = v["attributes"], {}
    if "Tile Size" in a:
        body["length"], body["width"] = TILE_DIMS[a["Tile Size"]]
    if "Step Length" in a:
        body["length"] = STEP_DIMS[a["Step Length"]]
    if "Skirting Height" in a:
        body["length"] = 2.4
    if "Thickness" in a:
        body["length"], body["width"] = (3.0, 0.6) if "Countertop" in product else (2.8, 1.6)
    return body


def run(ctx):
    ensure_units(ctx)
    att, val = attributes(ATTRS)
    have = products("GR-") | products("AGG-")
    for code, name, img, cat, mode, uom, price, cost, (dl, dw, dh), lines, desc in PRODUCTS:
        if code in have:
            log("  skip", code)
            continue
        body = {"code": code, "sku": code, "name": name, "description": desc, "productType": "stockable",
                "categoryId": ctx.category(cat), "uomId": ctx.uom[uom], "salesUomId": ctx.uom[uom],
                "purchaseUomId": ctx.uom[uom], "salesPrice": price, "costPrice": cost, "trackInventory": True,
                "canBeSold": True, "isActive": True, "stockPolicy": "warn", "allowFractionalSale": True,
                "image": upload(os.path.join(IMAGES, f"{img}.jpg"))["id"]}
        if mode == "volume":  # also per tonne (1.5 t per m³) and per truck (10 m³), when those units exist
            units = []
            if "TON" in ctx.uom:
                units.append({"uomId": ctx.uom["TON"], "factor": 0.666667, "isSales": True, "isPurchase": True})
            if "TRUCK" in ctx.uom:
                units.append({"uomId": ctx.uom["TRUCK"], "factor": 10, "isSales": True, "isPurchase": True,
                              "salePrice": 720000})
            if units:
                body["units"] = units
        elif mode == "length":  # also per piece of one standard length
            body["units"] = [{"uomId": ctx.uom["UNIT"], "factor": dl, "isSales": True, "isPurchase": False,
                              "salePrice": round(price * dl)}]
        pid = call("POST", "/products", body)["id"]
        sizing(pid, mode, dl, dw, dh)
        configure_variants(pid, lines, att, val)
        for i, v in enumerate(call("GET", f"/products/{pid}/variants"), 1):
            call("PATCH", f"/products/{pid}/variants/{v['id']}", {"sku": f"{code}-{i:02d}",
                 "description": f"{name} - {v['name']}", **variant_dims(name, v)})
        log("  created", code)
    call("PUT", "/sales/settings", {"quoteLineImages": True})


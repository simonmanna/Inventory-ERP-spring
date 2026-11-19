"""Marble / granite processing services (cutting, edging, polishing, CNC, drilling, sealing, ...) as service products."""
from api import attributes, call, configure_variants, log, products, rows, sizing

CATEGORY = "Stone Processing Services"
UNITS = [("CUT", "Cut", "cut"), ("SLAB", "Slab", "slab"), ("HOLE", "Hole", "hole"), ("LETTER", "Letter", "letter")]
ATTRS = {
    "Material": ("pills", ["Marble", "Granite", "Quartz"]),
    "Slab Thickness": ("pills", ["20 mm", "30 mm"]),
    "Edge Profile": ("select", ["Pencil round", "Half bullnose", "Full bullnose", "Bevel", "Ogee", "Double ogee"]),
    "Polish Finish": ("pills", ["High gloss", "Honed (matt)", "Leathered"]),
    "Complexity": ("pills", ["Simple", "Medium", "Complex"]),
    "Hole Size": ("pills", ["6-12 mm (fixing)", "35 mm (tap)", "50-65 mm (waste/soap)"]),
    "Sealer": ("pills", ["Standard sealer", "Stain-proof impregnator", "Anti-slip treatment"]),
    "Lettering": ("pills", ["Engraved", "Engraved + gold leaf", "Engraved + silver leaf"]),
    "Cut-out Type": ("select", ["Top-mount sink", "Under-mount sink (polished)", "Vanity basin", "Hob / cooktop"]),
    "Damage": ("pills", ["Minor (chips, scratches)", "Moderate (cracks, stains)", "Major (breaks)"]),
}


def mat(granite, quartz):
    return ("Material", [("Marble", 0), ("Granite", granite), ("Quartz", quartz)])


THICK = lambda extra: ("Slab Thickness", [("20 mm", 0), ("30 mm", extra)])  # noqa: E731

# code, name, unit, sizing (mode, L, W) or None, price, description, variant lines [(attr, [(value, priceExtra)])]
SERVICES = [
    ("SRV-CUT-PC", "Stone cutting (per cut)", "CUT", None, 15000,
     "Straight cut of a slab to the requested dimension. Charged per cut.", [mat(5000, 8000), THICK(5000)]),
    ("SRV-CUT-M2", "Stone cutting (per m²)", "M2", ("area", 1.0, 1.0), 25000,
     "Cutting slabs into requested dimensions, charged per square metre of cut stone (pieces × length × width).",
     [mat(5000, 10000), THICK(7000)]),
    ("SRV-CUT-SLAB", "Slab cutting (per slab)", "SLAB", None, 80000,
     "Cutting a full slab into the customer's pieces. Charged per slab.", [mat(20000, 30000), THICK(20000)]),
    ("SRV-EDGE", "Edge profiling", "M", ("length", 1.0, None), 30000,
     "Rounded, bevelled or decorative edges. Charged per running metre of edge.",
     [("Edge Profile", [("Pencil round", 0), ("Half bullnose", 5000), ("Full bullnose", 10000), ("Bevel", 5000),
                        ("Ogee", 20000), ("Double ogee", 35000)]), mat(5000, 5000)]),
    ("SRV-POLISH", "Surface polishing", "M2", ("area", 1.0, 1.0), 35000,
     "Smooth, shiny (or honed / leathered) finish on marble and granite surfaces. Charged per m².",
     [("Polish Finish", [("High gloss", 0), ("Honed (matt)", -5000), ("Leathered", 15000)]), mat(10000, 5000)]),
    ("SRV-CNC", "CNC / custom cutting", "HR", None, 120000,
     "Customized shapes, patterns and designs on the CNC machine. Charged per machine hour.",
     [("Complexity", [("Simple", 0), ("Medium", 40000), ("Complex", 90000)])]),
    ("SRV-DRILL", "Drilling", "HOLE", None, 10000, "Holes for sinks, taps and other fittings. Charged per hole.",
     [("Hole Size", [("6-12 mm (fixing)", 0), ("35 mm (tap)", 5000), ("50-65 mm (waste/soap)", 10000)]),
      mat(3000, 5000)]),
    ("SRV-SEAL", "Sealing and treatment", "M2", ("area", 1.0, 1.0), 18000,
     "Protective coating applied to stone surfaces. Charged per m².",
     [("Sealer", [("Standard sealer", 0), ("Stain-proof impregnator", 12000), ("Anti-slip treatment", 20000)])]),
    ("SRV-ENGRAVE", "Marble engraving and lettering", "LETTER", None, 5000,
     "Engraved names, dates and inscriptions (memorials, plaques, signage). Charged per letter.",
     [("Lettering", [("Engraved", 0), ("Engraved + gold leaf", 4000), ("Engraved + silver leaf", 3000)]),
      mat(1000, 2000)]),
    ("SRV-FAB", "Custom countertop fabrication", "M2", ("area", 3.0, 0.6), 150000,
     "Templating, cutting, edging and finishing a countertop from the customer's or our stone. Charged per m² "
     "(stone sold separately).", [mat(20000, 40000), THICK(30000)]),
    ("SRV-WATERJET", "Waterjet cutting", "M", ("length", 1.0, None), 60000,
     "High-precision waterjet cutting for curves, inlays and logos. Charged per running metre of cut.",
     [mat(15000, 20000), THICK(20000)]),
    ("SRV-REPAIR", "Marble repair and restoration", "HR", None, 60000,
     "Filling chips and cracks, removing stains, re-bonding broken pieces. Charged per hour on site or in the workshop.",
     [("Damage", [("Minor (chips, scratches)", 0), ("Moderate (cracks, stains)", 20000), ("Major (breaks)", 50000)])]),
    ("SRV-REPOLISH", "Re-polishing old marble", "M2", ("area", 1.0, 1.0), 28000,
     "Grinding and re-polishing worn marble floors and tops back to a shine. Charged per m².",
     [("Polish Finish", [("High gloss", 0), ("Honed (matt)", -4000)])]),
    ("SRV-CHAMFER", "Chamfering and bevelling", "M", ("length", 1.0, None), 15000,
     "Angled safety chamfer or decorative bevel along an edge. Charged per running metre.", [mat(3000, 3000)]),
    ("SRV-CUTOUT", "Sink and basin cut-out", "UNIT", None, 70000,
     "Cut-out in a countertop or vanity top for a sink, basin or hob. Charged per cut-out.",
     [("Cut-out Type", [("Top-mount sink", 0), ("Under-mount sink (polished)", 50000), ("Vanity basin", 10000),
                        ("Hob / cooktop", 10000)]), mat(10000, 20000)]),
]


def ensure_units(ctx):
    cats = {c["name"]: c["id"] for c in rows(call("GET", "/uom-categories"))}
    for code, name, sym in UNITS:
        if code not in ctx.uom:
            ctx.uom[code] = call("POST", "/uoms", {"code": code, "name": name, "symbol": sym,
                                                   "categoryId": cats["Quantity"], "category": "unit", "factor": 1,
                                                   "ratio": 1, "uomType": "reference"})["id"]
    if "HR" not in ctx.uom:
        raise SystemExit("Unit HR (hour) is missing in this organisation (it comes with the reference data).")


def run(ctx):
    ensure_units(ctx)
    att, val = attributes({n: (d, [(v, None) for v in vs]) for n, (d, vs) in ATTRS.items()})
    have = products("SRV-")
    for code, name, uom, sz, price, desc, lines in SERVICES:
        if code in have:
            log("  skip", code)
            continue
        pid = call("POST", "/products", {
            "code": code, "sku": code, "name": name, "description": desc, "productType": "service",
            "categoryId": ctx.category(CATEGORY), "uomId": ctx.uom[uom], "salesUomId": ctx.uom[uom],
            "salesPrice": price, "costPrice": round(price * 0.45), "trackInventory": False, "canBeSold": True,
            "isActive": True, "allowFractionalSale": True})["id"]
        if sz:
            sizing(pid, *sz)
        configure_variants(pid, lines, att, val)
        for i, v in enumerate(call("GET", f"/products/{pid}/variants"), 1):
            call("PATCH", f"/products/{pid}/variants/{v['id']}",
                 {"sku": f"{code}-{i:02d}", "description": f"{name} - {v['name']}"})
        log("  created", code)

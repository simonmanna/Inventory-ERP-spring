"""Demo products, new suppliers, work centres with rates, routings, multi-level BOMs."""
from base import A, FACT, MAIN, PCAT, UOM, ids, once, rows, save

# code, name, category, uom, sale price, costing method, weight kg
PRODUCTS = [
    ("CD-CEM", "Cement 50kg bag - costing demo", "Raw Materials", "BAG", 0, "AVCO", 50),
    ("CD-SAND", "Building sand - costing demo", "Raw Materials", "TON", 0, "AVCO", 1000),
    ("CD-DUST", "Stone dust 0-6mm - costing demo", "Raw Materials", "TON", 0, "AVCO", 1000),
    ("CD-ADMIX", "Plasticiser admixture - costing demo", "Raw Materials", "L", 0, "AVCO", 1),
    ("CD-WATER", "Process water - costing demo", "Raw Materials", "L", 0, "AVCO", 1),
    ("CD-WRAP", "Pallet wrap roll - costing demo", "Consumables & Spares", "UNIT", 0, "AVCO", 2),
    ("CD-MIX", "Concrete mix C15 (semi-finished) - costing demo", "Raw Materials", "M3", 0, "FIFO", 0),
    ("CD-BLK6", "Hollow block 6 inch - costing demo", "Concrete Blocks", "UNIT", 2400, "FIFO", 18),
]
SUPPLIERS = [
    ("S-KASESE", "Kasese Cement Traders (DEMO)"),
    ("S-MOMBASA", "Mombasa Bulk Cement Imports (DEMO)"),
    ("S-ADMIX", "Nairobi Admixtures Ltd (DEMO)"),
    ("S-MASAKA", "Masaka Sand Pits (DEMO)"),
    ("S-NAMANVE", "Namanve Stone Crushers (DEMO)"),
    ("S-MUKONO", "Mukono Stone Crushers (DEMO)"),
    ("S-INSURE", "Eastern Marine Insurance (DEMO)"),
    ("S-WATER", "Kampala Water Bowser Services (DEMO)"),
]
# code, name, labour / machine / overhead rate per hour
CENTRES = [
    ("CD-MIXER", "Batching mixer - costing demo", 18000, 35000, 12000),
    ("CD-PRESS", "Block press - costing demo", 25000, 48000, 20000),
    ("CD-CURE", "Curing yard - costing demo", 4000, 0, 6000),
    ("CD-PACK", "Palletising - costing demo", 15000, 0, 5000),
]


def run():
    prods = ids("product")
    for code, name, cat, uom, sale, method, kg in PRODUCTS:
        if code not in prods:
            found = [p for p in rows(A.get("/products", search=code, pageSize=50)) if p.get("code") == code]
            body = {"code": code, "name": name, "sku": code, "categoryId": PCAT[cat], "uomId": UOM[uom],
                    "salesUomId": UOM[uom], "purchaseUomId": UOM[uom], "productType": "stockable", "salesPrice": sale,
                    "costPrice": 0, "trackInventory": True, "costingMethod": method, "batchTracking": method == "FIFO",
                    "weight": kg, "allowFractionalSale": uom not in ("UNIT", "BAG")}
            if method == "FIFO":
                body["pickingStrategy"] = "FIFO"
            prods[code] = found[0]["id"] if found else A.post("/products", body)["id"]
            save()
    parts = ids("partner")
    for code, name in SUPPLIERS:
        if code not in parts:
            parts[code] = A.post("/partners", {"code": code, "name": name, "isCompany": True, "isSupplier": True,
                                               "isCustomer": False})["id"]
            save()
    wc = ids("wc")
    for code, name, labour, machine, overhead in CENTRES:
        if code not in wc:
            found = [w for w in rows(A.get("/manufacturing/work-centers")) if w.get("code") == code]
            wc[code] = found[0]["id"] if found else A.post("/manufacturing/work-centers", {
                "code": code, "name": name, "description": "Costing demo"})["id"]
            save()
        once("rate:" + code, lambda c=code, l=labour, m=machine, o=overhead: A.put(
            "/manufacturing/costing/rates/" + wc[c], {
                "labourRatePerHour": l, "machineRatePerHour": m, "overheadRatePerHour": o,
                "overheadBasis": "machine_hours" if m else "labour_hours", "notes": "Costing demo rates"})["id"])
        once("wccost:" + code, lambda c=code, r=labour + machine + overhead: A.patch(
            "/manufacturing/work-centers/" + wc[c], {"costPerHour": r}) and r)
    p = prods
    mix_route = once("routing:mix", lambda: A.post("/manufacturing/routings", {
        "name": "Concrete mix route - costing demo", "operations": [
            {"sequence": 10, "name": "Batching and mixing", "workCenterId": wc["CD-MIXER"], "durationMins": 60,
             "setupMins": 0}]})["id"])
    blk_route = once("routing:blk", lambda: A.post("/manufacturing/routings", {
        "name": "Block making route - costing demo", "operations": [
            {"sequence": 10, "name": "Pressing", "workCenterId": wc["CD-PRESS"], "durationMins": 150, "setupMins": 0},
            {"sequence": 20, "name": "Curing", "workCenterId": wc["CD-CURE"], "durationMins": 60, "setupMins": 0},
            {"sequence": 30, "name": "Palletising", "workCenterId": wc["CD-PACK"], "durationMins": 45,
             "setupMins": 0}]})["id"])

    def bom(key, body):
        def go():
            b = A.post("/manufacturing/boms", body)
            A.post("/manufacturing/boms/%s/activate" % b["id"], {})
            return b["id"]
        return once("bom:" + key, go)

    bom("mix", {"name": "Concrete mix C15 per 10 m3 - costing demo", "outputProductId": p["CD-MIX"],
                "outputQuantity": 10, "overheadCost": 0, "isDefault": True, "routingId": mix_route,
                "defaultLocationId": FACT, "defaultOutputLocationId": FACT, "lines": [
                    {"componentProductId": p["CD-CEM"], "quantity": 30, "scrapPct": 0, "sequence": 1},
                    {"componentProductId": p["CD-SAND"], "quantity": 9, "scrapPct": 0, "sequence": 2},
                    {"componentProductId": p["CD-DUST"], "quantity": 7, "scrapPct": 0, "sequence": 3},
                    {"componentProductId": p["CD-ADMIX"], "quantity": 5, "scrapPct": 0, "sequence": 4},
                    {"componentProductId": p["CD-WATER"], "quantity": 1800, "scrapPct": 0, "sequence": 5}]})
    bom("blk", {"name": "Hollow block 6in per 1,000 - costing demo", "outputProductId": p["CD-BLK6"],
                "outputQuantity": 1000, "overheadCost": 60000, "isDefault": True, "routingId": blk_route,
                "defaultLocationId": FACT, "defaultOutputLocationId": MAIN, "lines": [
                    {"componentProductId": p["CD-MIX"], "quantity": 8, "scrapPct": 0, "sequence": 1},
                    {"componentProductId": p["CD-WRAP"], "quantity": 4, "scrapPct": 0, "sequence": 2}]})
    print("  masters ok", len(prods), "products")

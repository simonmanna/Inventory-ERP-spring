"""Customer sites with distances, freight bands (delivery charge rules), price lists, weighbridge station."""
from core import A, ApiError, ids, once, rows, save
from masters import CUSTOMERS

BANDS = [("0-10 KM", 0, 10, 3500), ("11-25 KM", 10.1, 25, 6000), ("26-50 KM", 25.1, 50, 10500),
         ("51-100 KM", 50.1, 100, 19000), ("Above 100 KM", 100.1, 2000, 36000)]
BASE = {"AGG-DUST": 38000, "AGG-0510": 72000, "AGG-1020": 68000, "AGG-2040": 65000, "AGG-CRUN": 42000,
        "AGG-MSAND": 58000, "AGG-REJ": 6000}


def units():
    u = ids("uom")
    ton = next(x for x in rows(A.get("/uoms", pageSize=200)) if x["code"] == "UNIT")
    km = next(x for x in rows(A.get("/uoms", pageSize=200)) if x["code"] == "M")
    for code, name, sym, base in (("TRIP", "Trip", "trip", ton), ("KM", "Kilometre", "km", km)):
        if code not in u:
            u[code] = A.post("/uoms", {"code": code, "name": name, "symbol": sym, "categoryId": base.get("categoryId"),
                                       "factor": 1000 if code == "KM" else 1, "uomType": "bigger",
                                       "ratio": 1000 if code == "KM" else 1})["id"]
            save()


def sites():
    s, par = ids("site_customer"), ids("partner")
    for code, _, phone, site, zone, km in CUSTOMERS:
        if code not in s:
            s[code] = A.post("/fleet/customer-sites", {
                "partnerId": par[code], "name": site, "code": "S-" + code, "address": site + ", Uganda (DEMO)",
                "city": site.split()[0], "contactName": "Site storekeeper", "contactPhone": phone, "zone": zone,
                "distanceKm": km, "notes": "DEMO site"})["id"]
            save()


def freight():
    for name, lo, hi, rate in BANDS:
        once("band-" + name, lambda n=name, a=lo, b=hi, r=rate: A.post("/fleet/delivery-charge-rules", {
            "name": "Freight " + n, "zone": n, "minDistanceKm": a, "maxDistanceKm": b, "basis": "per_tonne",
            "rate": r, "notes": "DEMO distance band"})["id"])


def pricelists():
    p, par = ids("product"), ids("partner")

    def base():
        made = A.post("/sales/pricelists", {"code": "PL-LIST", "name": "GreenRock List (DEMO)", "type": "wholesale"})
        for c, v in BASE.items():
            A.post("/sales/pricelists/%s/items" % made["id"], {"scope": "product", "productId": p[c], "compute": "fixed",
                                                               "value": v, "minQuantity": 0, "priority": 1})
            # Volume discounts: the same material gets cheaper at 50 and 150 tonnes.
            A.post("/sales/pricelists/%s/items" % made["id"], {"scope": "product", "productId": p[c], "compute": "discount",
                                                               "value": 3, "minQuantity": 50, "priority": 2})
            A.post("/sales/pricelists/%s/items" % made["id"], {"scope": "product", "productId": p[c], "compute": "discount",
                                                               "value": 6, "minQuantity": 150, "priority": 3})
        A.post("/sales/pricelists/%s/assign" % made["id"], {"partnerIds": [par[c] for c in ("C-MUK", "C-JIN", "C-MAS", "C-GUL", "C-WAK")]})
        return made["id"]
    once("pl-list", base)

    def contract():
        made = A.post("/sales/pricelists", {"code": "PL-KRW", "name": "Kampala Roadworks Agreement (DEMO)", "type": "contract"})
        for c, v in (("AGG-2040", 59000), ("AGG-1020", 62000), ("AGG-CRUN", 37000)):
            A.post("/sales/pricelists/%s/items" % made["id"], {"scope": "product", "productId": p[c], "compute": "fixed",
                                                               "value": v, "minQuantity": 0, "priority": 5})
        A.post("/sales/pricelists/%s/assign" % made["id"], {"partnerIds": [par["C-KRW"]]})
        return made["id"]
    once("pl-contract", contract)


def station():
    w = ids("wb")
    if "station" not in w:
        w["station"] = A.post("/fleet/weighbridge/stations", {
            "code": "WB-1", "name": "Main Gate Weighbridge (DEMO)", "branchId": ids("branch")["GR-QRY"], "tolerancePct": 2,
            "toleranceKg": 200, "tareValidityDays": 30, "minWeightKg": 500, "maxWeightKg": 90000,
            "notes": "DEMO. Weights come from a simulator device, no physical indicator is connected."})["id"]
        save()
    if "device" not in w:
        w["device"] = A.post("/fleet/weighbridge/devices", {
            "stationId": w["station"], "code": "SIM-1", "name": "Simulator indicator (DEMO)", "adapterType": "simulator",
            "config": {}, "readTimeoutMs": 5000})["id"]
        save()
    for c in BASE:
        once("wb-factor-" + c, lambda x=c: A.put("/fleet/weighbridge/product-settings/" + ids("product")[x],
                                                 {"kgPerUnit": 1000, "tolerancePct": 2, "toleranceKg": 200}) and True)


def run():
    units()
    sites()
    freight()
    pricelists()
    station()
    print("  sites", len(ids("site_customer")))

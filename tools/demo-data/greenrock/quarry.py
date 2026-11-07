"""Pits (one quarry site each, because the quarry reports compare by site), plans and 30 days of posted extraction.

Totals reproduce the brief's yield table exactly:
Granite Ridge 10,000 / 2,200, Eastern Rock Face 8,500 / 1,275, Southern Extension 6,000 / 1,800."""
import datetime as dt
import random

from core import A, ensure, ids, once, save
from masters import VEHICLES

TODAY = dt.date(2026, 11, 6)
START = TODAY - dt.timedelta(days=30)
PITS = {  # code: name, location, grade, gross, overburden, overburden % start, end, density, excavator, operator, km
    "PIT-A": ("Pit A: Granite Ridge", "WH-ROM-A", "Grade A - hard, low fines", 10000, 2200, 22.0, 22.0, 2.70, "EX-01", "GR-010"),
    "PIT-B": ("Pit B: Eastern Rock Face", "WH-ROM-B", "Grade A/B - massive", 8500, 1275, 17.0, 13.0, 2.65, "EX-02", "GR-011"),
    "PIT-C": ("Pit C: Southern Quarry Extension", "WH-ROM-C", "Grade C - weathered cap", 6000, 1800, 24.0, 36.0, 2.55, "EX-03", "GR-010"),
}


def working_days():
    d, out = START, []
    while d <= TODAY:
        if d.weekday() != 6:
            out.append(d)
        d += dt.timedelta(days=1)
    return out


def schedule():
    """Per pit and day: gross and overburden tonnes that add up exactly to the table. Stable across re-runs."""
    days = working_days()
    rng = random.Random(20261106)
    plan = {}
    for code, (_, _, _, gross, over, p0, p1, *_) in PITS.items():
        weights = [rng.uniform(0.8, 1.2) for _ in days]
        unit = gross / sum(weights)
        g = [int(round(w * unit / 25.0)) * 25 for w in weights]
        g[-1] += gross - sum(g)
        o = [round(x * (p0 + (p1 - p0) * i / (len(days) - 1)) / 100 / 5) * 5 for i, x in enumerate(g)]
        # scale overburden on to the exact table total, keeping the trend
        delta = over - sum(o)
        step = 5 if delta > 0 else -5
        i = 0
        while delta != 0:
            o[i % len(o)] += step
            delta -= step
            i += 1
        plan[code] = list(zip(days, g, o))
    return plan


def setup():
    p, loc, em, veh = ids("product"), ids("location"), ids("employee"), ids("vehicle")
    A.put("/quarry/settings", {"allocationBasis": "net_tonnes", "fuelCostPerLitre": 5400, "labourRatePerHour": 9000,
                               "equipmentRatePerHour": 120000, "distinctExplosiveIssuer": True})
    cat = ids("fleetcat")
    for code, rate in (("EXCAVATOR", 165000), ("LOADER", 110000), ("DOZER", 185000)):
        once("rate-" + code, lambda c=code, r=rate: A.post("/quarry/equipment-rates", {
            "categoryId": cat[c], "ratePerHour": r, "notes": "DEMO hire-equivalent rate"})["id"])
    for code, (name, lc, grade, gross, _, _, _, density, _, _) in PITS.items():
        site = ensure("site", code, "/quarry/sites", "/quarry/sites", {
            "code": code, "name": name, "status": "active", "branchId": ids("branch")["GR-QRY"],
            "address": "Central Uganda (DEMO)", "areaHectares": 18, "licenceNumber": "DEMO-ML-" + code,
            "licenceAuthority": "DEMO licence, not a real authority", "licenceExpiresOn": "2030-12-31",
            "notes": "DEMO pit. Estimated reserves: %d,000 t. Suitability: %s" % (gross * 60 / 1000, grade)},
            params={"pageSize": 50})
        mat = ensure("material", code, "/quarry/materials", "/quarry/materials", {
            "code": "GRN-" + code[-1], "name": "Granite " + grade, "rockType": "Granite", "grade": grade,
            "densityTPerM3": density, "productId": p["RAW-GRANITE"], "notes": "DEMO"}, field="__state_only__")
        ensure("stockpile", code, "/quarry/stockpiles", "/quarry/stockpiles", {
            "siteId": site, "code": "ROM-" + code[-1], "name": "ROM pad " + name, "kind": "rom",
            "locationId": loc[lc], "capacityT": 40000, "isDefault": True, "notes": "DEMO"}, field="__state_only__")
        ensure("pitzone", code, "/quarry/sites", "/quarry/zones", {
            "siteId": site, "kind": "pit", "code": code + "-P", "name": name, "materialId": mat,
            "geology": grade, "benchHeightM": 10, "status": "active"}, field="__state_only__")
        ensure("sectorzone", code, "/quarry/sites", "/quarry/zones", {
            "siteId": site, "parentId": ids("pitzone")[code], "kind": "zone", "code": code + "-Z1",
            "name": "Main sector", "materialId": mat, "status": "active"}, field="__state_only__")
        ensure("benchzone", code, "/quarry/sites", "/quarry/zones", {
            "siteId": site, "parentId": ids("sectorzone")[code], "kind": "bench", "code": code + "-B1",
            "name": "Bench 1", "materialId": mat, "benchHeightM": 10, "status": "active"}, field="__state_only__")
        target = {"PIT-A": 10500, "PIT-B": 8200, "PIT-C": 7000}[code]
        once("plan-" + code, lambda s=site, t=target, c=code, n=name: A.post("/quarry/extraction-plans", {
            "code": "PLAN-" + c, "name": "30-day extraction target " + n, "siteId": s,
            "periodStart": START.isoformat(), "periodEnd": TODAY.isoformat(), "targetTonnes": t,
            "budgetCostPerTonne": 11000, "status": "active", "notes": "DEMO"})["id"])
    save()


def haulage():
    """Face-to-pad haulage records for the last days (loads x capacity, no fleet trip)."""
    trucks = ["T-01", "T-02", "T-03", "T-04"]
    for code in PITS:
        for back in range(0, 5):
            day = TODAY - dt.timedelta(days=back)
            if day.weekday() == 6:
                continue
            once("haul-%s-%s" % (code, day), lambda c=code, d=day, b=back: A.post("/quarry/haulage-trips", {
                "siteId": ids("site")[c], "fromZoneId": ids("benchzone")[c], "destination": "rom_stockpile",
                "stockpileId": ids("stockpile")[c], "materialId": ids("material")[c], "hauledOn": d.isoformat(),
                "shift": "day", "truckVehicleId": ids("vehicle")[trucks[b % 4]], "method": "loads", "loadCount": 8,
                "capacityT": 25, "notes": "DEMO haulage"})["id"])


def extraction():
    em, veh = ids("employee"), ids("vehicle")
    sched = schedule()
    meter = {v[0]: float(v[8]) for v in VEHICLES}  # hour meters run on, so fleet utilisation reports have real hours
    trucks = ["T-01", "T-02", "T-03", "T-04"]
    shifts = ["day", "day", "night"]
    for code, days in sched.items():
        _, _, _, _, _, _, _, _, exc, op = PITS[code]
        for i, (d, gross, over) in enumerate(days):
            key = "log-%s-%s" % (code, d)
            hours = round(min(11.5, gross / 45.0 + 2), 1)
            dozer = round(min(10, over / 22.0 + 0.5), 1)
            body = {
                "siteId": ids("site")[code], "zoneId": ids("benchzone")[code], "materialId": ids("material")[code],
                "stockpileId": ids("stockpile")[code], "extractedOn": d.isoformat(), "shift": shifts[i % 3],
                "supervisorEmployeeId": em["GR-002"], "notes": "DEMO extraction log",
                "otherCost": 150000 + over * 900, "otherCostNote": "ROM transport to plant and overburden haul",
                "loads": [{"method": "loads", "truckVehicleId": veh[trucks[i % 4]], "loadCount": gross // 25,
                           "capacityT": 25, "notes": "Face to ROM pad"}],
                "resources": [
                    {"kind": "equipment", "vehicleId": veh[exc], "employeeId": em[op], "role": "Excavator", "hours": hours,
                     "meterStart": round(meter[exc], 1), "meterEnd": round(meter[exc] + hours, 1),
                     "fuelLitres": round(hours * 26, 1)},
                    {"kind": "equipment", "vehicleId": veh["DZ-01"], "role": "Overburden stripping", "hours": dozer,
                     "meterStart": round(meter["DZ-01"], 1), "meterEnd": round(meter["DZ-01"] + dozer, 1),
                     "fuelLitres": round(dozer * 32, 1)},
                    {"kind": "labour", "employeeId": em["GR-012"], "role": "Pit crew", "hours": 8},
                ],
                "losses": [{"kind": "overburden", "tonnes": over, "reason": "Overburden stripped ahead of bench"}],
                "post": True}
            meter[exc] += hours
            meter["DZ-01"] += dozer
            once(key, lambda b=body, k=key: A.post("/quarry/extraction-logs", b, key=k)["id"])


def run():
    setup()
    extraction()
    haulage()
    print("  logs", len([k for k in ids("steps") if k.startswith("log-")]))

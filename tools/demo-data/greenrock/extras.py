"""Filler for the remaining Manufacturing & Operations screens. Illustrative data: it goes through the API like the rest,
but a step that fails is reported and skipped, so one refused record does not stop the others."""
import datetime as dt
import time

from core import A, C, ApiError, ids, once, rows, save

TODAY = dt.date(2026, 11, 6)
FAILED = []


def soft(key, fn):
    """Run a step once; on an API refusal report it and carry on (a later run retries it)."""
    try:
        return once("x:" + key, fn)
    except (ApiError, KeyError, IndexError, TypeError, StopIteration) as e:
        FAILED.append(key)
        print("   SKIP %s: %s" % (key, str(e)[:230]), flush=True)
        return None


def iso(day, hour=8):
    return "%sT%02d:00:00Z" % (day.isoformat(), hour)


def back(n):
    return TODAY - dt.timedelta(days=n)


def product(code, name, price=1000, cost=500, uom="UNIT", tracked=True):
    p = ids("product")
    if code not in p:
        p[code] = A.post("/products", {
            "code": code, "name": name + " (DEMO)", "sku": code, "categoryId": ids("pcat")["Raw Material"], "uomId": ids("uom")[uom],
            "salesUomId": ids("uom")[uom], "purchaseUomId": ids("uom")[uom], "productType": "stockable" if tracked else "service",
            "trackInventory": tracked, "costingMethod": "AVCO", "salesPrice": price, "costPrice": cost,
            "allowFractionalSale": True})["id"]
        save()
    return p[code]


def stock_in(key, location, items):
    uid = ids("self").get("userId") or A.user.get("id")
    soft("stock-" + key, lambda: A.post("/inventory/direct-stock/in", {
        "locationId": location, "responsibleById": uid, "approvedById": uid, "notes": "DEMO stock",
        "items": [{"productId": p, "quantity": q, "unitCost": c} for p, q, c in items]}) and True)


def veh(code):
    return ids("vehicle")[code]


def emp(code):
    return ids("employee")[code]


# ------------------------------------------------------------------------------------------------ quarry

def quarry():
    site = ids("site")["PIT-A"]
    loc = ids("location")
    ex1 = soft("prod-anfo", lambda: product("EXPL-ANFO", "ANFO bulk explosive", 4500, 3200, "KG"))
    ex2 = soft("prod-det", lambda: product("EXPL-DET", "Electronic detonator", 12000, 8500, "UNIT"))

    def magazine(code, name):
        return A.post("/quarry/magazines", {"siteId": site, "code": code, "name": name, "licenceNumber": "DEMO-MAG-" + code,
                                            "licenceAuthority": "DEMO authority", "licenceExpiresOn": "2029-12-31", "capacityKg": 5000,
                                            "keeperEmployeeId": emp("GR-002"), "notes": "DEMO magazine"})["id"]
    m1 = soft("mag-1", lambda: magazine("MAG-1", "Main Explosives Magazine"))
    soft("mag-2", lambda: magazine("MAG-2", "Detonator Store"))
    if m1:
        mag = A.get("/quarry/magazines/" + m1)
        stock_in("explosives", mag.get("locationId"), [(ex1, 4000, 3200), (ex2, 600, 8500)])
    e1 = soft("expl-anfo", lambda: A.post("/quarry/explosives", {"productId": ex1, "kind": "blasting_agent", "hazardClass": "1.5D", "unNumber": "UN0331", "netExplosiveKg": 1, "controlled": True})["id"])
    e2 = soft("expl-det", lambda: A.post("/quarry/explosives", {"productId": ex2, "kind": "detonator", "hazardClass": "1.1B", "unNumber": "UN0255", "netExplosiveKg": 0.002, "controlled": True})["id"])
    for code, name, rate, kind in (("Royalty on granite", "royalty", 2500, None), ("District levy", "levy", 600, None)):
        soft("roy-" + code, lambda c=code, n=name, r=rate: A.post("/quarry/royalty-rates", {
            "name": c, "kind": n, "basis": "net_tonnes", "ratePerTonne": r, "authorityPartnerId": ids("partner")["S-MINING"],
            "effectiveFrom": "2026-11-07", "notes": "DEMO rate, applies from tomorrow"})["id"])
    for i, (pit, days, status) in enumerate((("PIT-A", 20, "closed"), ("PIT-B", 14, "closed"), ("PIT-C", 8, "fired"),
                                             ("PIT-A", 3, "fired"), ("PIT-B", -3, "planned"), ("PIT-C", -6, "planned"), ("PIT-A", -9, "cancelled"))):
        day = back(days)

        def blast(p=pit, d=day, s=status, n=i):
            b = A.post("/quarry/blasts", {
                "siteId": ids("site")[p], "zoneId": ids("benchzone")[p], "materialId": ids("material")[p], "plannedAt": iso(d, 11),
                "patternType": "staggered", "holeCount": 48 + n * 6, "holeDiameterMm": 102, "averageDepthM": 10.5, "burdenM": 3,
                "spacingM": 3.5, "subDrillM": 1, "stemmingM": 2.5, "drillVehicleId": veh("EX-01"), "drillHours": 16, "drillingCost": 2400000,
                "otherCost": 300000, "estimatedTonnes": 6000 + n * 500, "shotfirerEmployeeId": emp("GR-002"), "shotfirerLicenceNo": "DEMO-SF-01",
                "blastPermitNo": "DEMO-BP-%02d" % n, "permitExpiresOn": "2027-06-30", "exclusionZoneM": 500, "notes": "DEMO blast"})
            bid = b["id"]
            if s in ("fired", "closed"):
                A.post("/quarry/blasts/%s/fire" % bid, {"firedAt": iso(d, 12), "warningSignalAt": iso(d, 11), "allClearAt": iso(d, 13),
                                                        "misfireCount": n % 2, "vibrationPpvMmS": 6.2 + n, "airblastDb": 118 + n, "weather": "Dry, light wind"})
            if s == "closed":
                A.post("/quarry/blasts/%s/close" % bid, {})
            if s == "cancelled":
                A.post("/quarry/blasts/%s/cancel" % bid, {"reason": "Permit window moved (DEMO)"})
            return bid
        soft("blast-%d" % i, blast)
    planned = ids("steps").get("x:blast-4")

    def issue(reconcile, nth):
        def go():
            have = rows(A.get("/quarry/explosive-issues", pageSize=20))
            r = have[nth] if len(have) > nth else A.post("/quarry/explosive-issues", {
                "blastId": planned, "magazineId": m1, "purpose": "Bench blast (DEMO)", "shotfirerEmployeeId": emp("GR-002"),
                "shotfirerLicenceNo": "DEMO-SF-01", "lines": [{"explosiveId": e1, "quantity": 900}, {"explosiveId": e2, "quantity": 48}]})
            rid = r["id"]
            current = A.get("/quarry/explosive-issues/" + rid)
            req = current.get("approvalRequestId")
            if req and current.get("status") == "pending_approval":
                C.post("/approvals/%s/decide" % req, {"status": "approved", "comment": "DEMO approval"})
                time.sleep(3)  # the issue follows the approval through an after-commit event
            if reconcile:
                detail = C.post("/quarry/explosive-issues/%s/issue" % rid, {}, key="exi-issue-%d" % nth)
                lines = detail.get("lines") or A.get("/quarry/explosive-issues/" + rid)["lines"]
                C.post("/quarry/explosive-issues/%s/reconcile" % rid, {"lines": [{"lineId": lines[0]["id"], "returnedQty": 40}]}, key="exi-rec3-%d" % nth)
            return rid
        return go
    soft("issue-1", issue(True, 0))
    soft("issue-2", issue(False, 1))
    for i, (pit, kind, t) in enumerate((("PIT-C", "overburden", 240), ("PIT-A", "waste", 80), ("PIT-B", "spillage", 25), ("PIT-C", "unusable_rock", 130))):
        soft("loss-%d" % i, lambda p=pit, k=kind, q=t, n=i: A.post("/quarry/losses", {
            "siteId": ids("site")[p], "zoneId": ids("benchzone")[p], "lossOn": back(3 + n * 4).isoformat(), "kind": k, "tonnes": q,
            "reason": "DEMO standalone loss"})["id"])


# ------------------------------------------------------------------------------------------------ quality

def quality():
    me = A.user.get("id") or ids("self").get("userId")
    p = ids("product")
    titles = [("Oversize in 10-20mm stockpile", "major", "AGG-1020", "in_process"), ("Customer complaint: fines in 5-10mm", "minor", "AGG-0510", "customer"),
              ("Clay contamination from Southern Extension", "critical", "RAW-GRANITE", "internal"), ("Moisture above limit in sand", "minor", "AGG-MSAND", "final"),
              ("Wrong grade loaded at gate", "major", "AGG-2040", "internal")]
    for i, (t, sev, prod, src) in enumerate(titles):
        soft("ncr-%d" % i, lambda a=t, s=sev, b=prod, c=src, n=i: A.post("/quality/ncrs", {
            "title": a, "description": "DEMO nonconformance", "source": c, "severity": s, "productId": p[b], "batchNumber": "DEMO-%03d" % n,
            "quantityAffected": 20 + n * 15, "disposition": "Re-screen and re-test", "rootCause": "Screen deck wear (DEMO)", "ownerUserId": me})["id"])
    for i, title in enumerate(("Replace worn screen decks on a schedule", "Add washing step for Southern Extension feed", "Weekly sieve check at the gate", "Train loaders on grade separation")):
        def capa(t=title, n=i):
            ncr = ids("steps").get("x:ncr-%d" % n)
            return A.post("/quality/capas", {
                "title": t, "ncrId": ncr, "rootCause": "Worn wear parts (DEMO)", "rootCauseMethod": "five_why",
                "ownerUserId": me, "dueDate": back(-14 + n * 10).isoformat(),
                "actions": [{"kind": "corrective", "description": t, "ownerUserId": me, "dueDate": back(-7).isoformat()},
                            {"kind": "preventive", "description": "Add to the monthly checklist", "ownerUserId": me, "dueDate": back(-21).isoformat()}]})["id"]
        soft("capa-%d" % i, capa)
    def lab():
        lot = A.post("/quality/inspection-lots", {"stage": "final", "productId": p["AGG-2040"], "batchNumber": "DEMO-LAB-1", "quantity": 50,
                                                  "planId": ids("qplan")["AGG-2040"], "sourceRef": "Lab import demo", "applyHoldPolicy": False})
        number = lot.get("lotNumber") or lot.get("code")
        csv = "lotNumber,parameterCode,sampleNo,numericValue,labReference\n" + "\n".join(
            "%s,%s,1,%s,DEMO-LAB-01" % (number, c, v) for c, v in (("MOIST", 1.4), ("FINES", 1.1), ("CLAY", 0.3), ("ACV", 23.5)))
        return A.post("/quality/lab-imports/csv", {"content": csv, "filename": "demo-lab.csv"}) and True
    soft("lab-csv", lab)


# ------------------------------------------------------------------------------------------------ production

def production():
    wc, loc, p = ids("wc"), ids("location"), ids("product")
    for code, name in (("WC-MIX", "Concrete Mixer"), ("WC-PRESS", "Block Press"), ("WC-SAW", "Gang Saw"), ("WC-POL", "Polishing Line"), ("WC-CUT", "Bridge Cutter"), ("WC-PACK", "Packing Bay")):
        if code not in wc:
            r = soft("wc-" + code, lambda c=code, n=name: A.post("/manufacturing/work-centers", {"code": c, "name": n + " (DEMO)", "description": "DEMO work centre"})["id"])
            if r:
                wc[code] = r
                save()
    shifts = ids("shift")
    for code, name, a, b in (("MORN", "Morning Shift", 360, 840), ("AFT", "Afternoon Shift", 840, 1320), ("NIGHT", "Night Shift", 1320, 1440)):
        if code not in shifts:
            r = soft("shift-" + code, lambda c=code, n=name, s=a, e=b: A.post("/manufacturing/shop-floor/shifts", {"code": c, "name": n, "startMinute": s, "endMinute": e, "breakMinutes": 30, "isActive": True})["id"])
            if r:
                shifts[code] = r
                save()
    routing = soft("routing-block", lambda: A.post("/manufacturing/routings", {"name": "Block making route (DEMO)", "operations": [
        {"sequence": 10, "name": "Mixing", "workCenterId": wc["WC-MIX"], "durationMins": 60, "setupMins": 10},
        {"sequence": 20, "name": "Pressing", "workCenterId": wc["WC-PRESS"], "durationMins": 240, "setupMins": 15},
        {"sequence": 30, "name": "Packing", "workCenterId": wc["WC-PACK"], "durationMins": 45}]})["id"])
    route2 = soft("routing-agg", lambda: A.post("/manufacturing/routings", {"name": "Bagged aggregate route (DEMO)", "operations": [
        {"sequence": 10, "name": "Screening", "workCenterId": wc["WC-CUT"], "durationMins": 90},
        {"sequence": 20, "name": "Packing", "workCenterId": wc["WC-PACK"], "durationMins": 60}]})["id"])
    for code, name in (("OVERSIZE", "Oversize rejects"), ("BROKEN", "Broken in handling"), ("SETUP", "Setup waste")):
        soft("scrap-" + code, lambda c=code, n=name: A.post("/manufacturing/scrap-reasons", {"code": c, "name": n})["id"])
    # A simple product made from seeded aggregates, stocked in a yard the orders can draw from.
    bag = soft("prod-bag", lambda: product("BAG-AGG", "Bagged Aggregate 25kg", 5000, 1800, "BAG"))
    wh = loc["WH-AGG"]
    bom = soft("bom-bag", lambda: _bom(bag, wh, route2))
    made = []
    plans = [("completed", 200, 12), ("completed", 240, 9), ("completed", 180, 6), ("completed", 220, 4), ("completed", 150, 2), ("started", 300, 1), ("confirmed", 260, 0), ("draft", 400, -3)]
    for i, (state, qty, days) in enumerate(plans):
        o = soft("po-%d" % i, lambda s=state, q=qty, d=days, n=i: _order(bom, q, wh, s, d, n))
        if o:
            made.append(o)
    # Work-order execution on the open order, clocking and a shift report.
    _execution()
    # Planning data
    for code, name in (("MORN", 6), ("AFT", 5)):
        for d in range(0, 6):
            soft("sp-%s-%d" % (code, d), lambda c=code, n=d: _shift_plan(c, n))
    soft("tgt-wc", lambda: A.post("/manufacturing/planning/targets", {"scope": "work_center", "workCenterId": wc["WC-PRESS"], "periodFrom": "2026-11-01", "periodTo": "2026-11-30", "targetQty": 5000, "notes": "DEMO"})["id"])
    soft("tgt-plant", lambda: A.post("/manufacturing/planning/targets", {"scope": "crushing_plant", "plantId": ids("plant")["CP-1"], "periodFrom": "2026-10-07", "periodTo": "2026-11-30", "targetQty": 26000, "notes": "DEMO"})["id"])
    soft("tgt-prod", lambda: A.post("/manufacturing/planning/targets", {"scope": "product", "productId": p["AGG-2040"], "periodFrom": "2026-11-01", "periodTo": "2026-11-30", "targetQty": 4000, "notes": "DEMO"})["id"])
    soft("mrp-run", lambda: A.post("/manufacturing/mrp/runs", {"horizonDays": 60})["id"])
    soft("std-cost", lambda: _standard_costs(bom))
    _precast(wh, routing)


def _bom(bag, wh, route):
    b = A.post("/manufacturing/boms", {
        "name": "Bagged Aggregate 25kg (DEMO)", "outputProductId": bag, "outputQuantity": 40, "expectedYieldPct": 98, "overheadCost": 15000,
        "defaultLocationId": wh, "defaultOutputLocationId": wh, "estimatedDurationMins": 120, "isDefault": True, "routingId": route,
        "lines": [{"componentProductId": ids("product")["AGG-0510"], "quantity": 1, "sequence": 1}]})
    A.post("/manufacturing/boms/%s/activate" % b["id"], {})
    return b["id"]


def _order(bom, qty, wh, state, days, n):
    d = back(days)
    o = A.post("/manufacturing/production-orders", {"bomId": bom, "plannedQty": qty, "locationId": wh, "outputLocationId": wh,
                                                    "scheduledFor": d.isoformat(), "batchNumber": "BAG-%03d" % n, "notes": "DEMO production order"})
    oid = o["id"]
    if state == "draft":
        return oid
    A.post("/manufacturing/production-orders/%s/confirm" % oid, key="po-confirm-%d" % n)
    if state == "confirmed":
        return oid
    A.post("/manufacturing/production-orders/%s/start" % oid, {}, key="po-start-%d" % n)
    if state == "started":
        return oid
    A.post("/manufacturing/production-orders/%s/complete" % oid, {"qtyProduced": round(qty * (0.94 + n * 0.01), 1), "scrapQty": round(qty * 0.02, 1), "date": d.isoformat(), "notes": "DEMO"}, key="po-done-%d" % n)
    return oid


def _execution():
    floor = "/manufacturing/shop-floor"
    for code, name, cat in (("BREAKDOWN", "Machine breakdown", "breakdown"), ("NOFEED", "No material", "material"), ("CHANGE", "Changeover", "setup"), ("POWER", "Power cut", "utility")):
        soft("reason-" + code, lambda a=code, b=name, c=cat: A.post(floor + "/downtime-reasons", {"code": a, "name": b, "category": c, "isActive": True}).get("id", True))

    def report():
        order = ids("steps")["x:po-5"]
        wos = rows(A.get("/manufacturing/production-orders/%s/work-orders" % order)) \
            or A.post("/manufacturing/production-orders/%s/work-orders/generate" % order, key="wo-gen-5")
        if ids("steps").get("x:po-6"):
            A.post("/manufacturing/production-orders/%s/work-orders/generate" % ids("steps")["x:po-6"], key="wo-gen-6")
        wo = [w for w in wos if w.get("status") not in ("done", "completed", "cancelled")][0]
        wcid = wo.get("workCenterId") or ids("wc")["WC-PACK"]
        shift = ids("shift")["MORN"]
        try:
            A.post(floor + "/work-orders/%s/start" % wo["id"], {})
        except ApiError as e:
            print("   wo start:", str(e)[:120])
        A.post(floor + "/clock-entries", {"workCenterId": wcid, "workOrderId": wo["id"], "crewCode": "A", "role": "Operator", "notes": "DEMO"})
        A.post(floor + "/production-reports", {"workOrderId": wo["id"], "qtyGood": 80, "qtyScrap": 3, "scrapReasonCode": "OVERSIZE", "notes": "DEMO"})
        A.post(floor + "/downtime", {"workCenterId": wcid, "workOrderId": wo["id"], "reasonCode": "BREAKDOWN", "minutes": 35, "notes": "DEMO stoppage"})
        for n in range(1, 4):
            A.post(floor + "/downtime", {"workCenterId": ids("wc")["WC-PRESS"], "reasonCode": ("POWER", "CHANGE", "NOFEED")[n - 1], "minutes": 20 + n * 15, "notes": "DEMO stoppage"})
        for day in range(0, 3):
            r = A.post(floor + "/shift-reports", {"workCenterId": wcid, "shiftId": shift, "shiftDate": back(day).isoformat()})
            A.post(floor + "/shift-reports/%s/submit" % r["id"], {"notes": "DEMO shift report"})
        return True
    soft("execution", report)


def _shift_plan(code, n):
    product_code = "AGG-2040" if n % 2 else "AGG-1020"
    return A.post("/manufacturing/planning/shift-plans", {
        "shiftDate": back(-n).isoformat(), "shiftId": ids("shift")[code], "workCenterId": ids("wc")["WC-PACK"],
        "productId": ids("product")[product_code], "targetQty": 200 + n * 10, "crewSize": 5, "notes": "DEMO"})["id"]


def _standard_costs(bom):
    A.post("/manufacturing/boms/%s/standard-cost" % bom, {"notes": "DEMO roll-up"})
    A.post("/manufacturing/boms/%s/product-standard" % bom, {"effectiveFrom": "2026-12-01", "notes": "DEMO standard"})
    return True


def _precast(wh, routing):
    cement = soft("prod-cement", lambda: product("RM-CEMENT", "Cement 50kg", 34000, 29000, "BAG"))
    block = soft("prod-block", lambda: product("PC-BLOCK6", "Precast Hollow Block 6in", 2400, 1200, "UNIT"))
    paver = soft("prod-paver", lambda: product("PC-PAVER", "Interlock Paver 60mm", 1800, 900, "UNIT"))
    stock_in("cement", wh, [(cement, 900, 29000)])
    for pid, kind, l, w, h, cls, name in ((block, "hollow", 450, 150, 225, "C7", "block"), (paver, "paver", 200, 100, 60, "C30", "paver")):
        soft("spec-" + name, lambda a=pid, b=kind, c=l, d=w, e=h, f=cls: A.put("/manufacturing/precast/product-specs/" + a, {
            "blockType": b, "lengthMm": c, "widthMm": d, "heightMm": e, "strengthClass": f, "strengthMpa": 7, "weightKg": 18, "unitsPerPallet": 60, "unitsPerCycle": 8, "isActive": True}) and True)
    area = soft("area-1", lambda: A.post("/manufacturing/precast/curing-areas", {"code": "CUR-1", "name": "Curing Yard 1 (DEMO)", "kind": "yard", "capacityPallets": 400, "locationId": wh, "targetTemperatureC": 28})["id"])
    soft("area-2", lambda: A.post("/manufacturing/precast/curing-areas", {"code": "CUR-2", "name": "Curing Chamber (DEMO)", "kind": "chamber", "capacityPallets": 120, "locationId": wh, "targetTemperatureC": 45})["id"])

    def mix(pid, name, code):
        m = A.post("/manufacturing/precast/mix-designs", {
            "name": name, "productId": pid, "unitsPerBatch": 100, "cementProductId": cement, "cementPerBag": 25, "batchVolumeM3": 1.2, "waterCementRatio": 0.5,
            "minCuringDays": 7, "defaultCuringAreaId": area, "expectedYieldPct": 97, "routingId": routing, "pressWorkCenterId": ids("wc")["WC-PRESS"],
            "defaultLocationId": wh, "defaultOutputLocationId": wh, "isDefault": True, "notes": "DEMO",
            "lines": [{"productId": cement, "qtyPerBatch": 4}, {"productId": ids("product")["AGG-DUST"], "qtyPerBatch": 1.2}]})
        A.post("/manufacturing/precast/mix-designs/%s/activate" % m["id"], {})
        return m["id"]
    m1 = soft("mix-block", lambda: mix(block, "Block 6in mix (DEMO)", "b"))
    m2 = soft("mix-paver", lambda: mix(paver, "Paver mix (DEMO)", "p"))
    floor = "/manufacturing/shop-floor"
    for i, (m, units, days) in enumerate(((m1, 1000, 12), (m1, 800, 8), (m2, 600, 5), (m2, 500, 2))):
        def run(mx=m, u=units, d=days, n=i):
            batch = "PC-%03d" % n
            have = [x for x in rows(A.get("/manufacturing/precast/orders", pageSize=50)) if x.get("batchNumber") == batch]
            oid = have[0]["id"] if have else A.post("/manufacturing/precast/orders", {
                "mixDesignId": mx, "basis": "units", "value": u, "locationId": wh, "outputLocationId": wh, "scheduledFor": back(d).isoformat(),
                "batchNumber": batch, "confirm": True, "notes": "DEMO"})["id"]
            return oid
        soft("pc-order-%d" % i, run)
    for b in rows(A.get("/manufacturing/precast/curing-batches", pageSize=10))[:4]:
        soft("cure-%s" % b["id"], lambda x=b["id"]: A.post("/manufacturing/precast/curing-batches/%s/readings" % x, {"temperatureC": 29, "humidityPct": 76, "readAt": TODAY.isoformat(), "notes": "DEMO"}) and True)


# ------------------------------------------------------------------------------------------------ logistics

def logistics():
    par = ids("partner")
    t = soft("transporter", lambda: A.post("/partners", {"code": "S-HAUL", "name": "Kampala Haulage Contractors (DEMO)", "isCompany": True, "isCustomer": False, "isSupplier": True, "phone": "+256700100201"})["id"])
    if t:
        par["S-HAUL"] = t
        save()
    for name, basis, rate in (("Per tonne local", "per_tonne", 7500), ("Per trip upcountry", "per_trip", 950000)):
        soft("rate-" + name, lambda n=name, b=basis, r=rate: A.post("/fleet/transporter-rates", {"partnerId": t, "name": n, "basis": b, "rate": r, "effectiveFrom": "2026-10-01", "notes": "DEMO"})["id"])
    # Third-party deliveries, so freight bills exist, plus open orders for the dispatch board.
    p = ids("product")
    for i, (cust, prod, qty, plate, drv) in enumerate((("C-WAK", "AGG-1020", 30, "UAX 118P", "Mukasa"), ("C-MUK", "AGG-2040", 30, "UBD 742H", "Ochieng"), ("C-KRW", "AGG-DUST", 30, "UAT 903B", "Kizza"))):
        soft("haul-%d" % i, lambda c=cust, x=prod, q=qty, pl=plate, d=drv, n=i: _transporter_trip(c, x, q, pl, d, t, n))
    for i, (cust, prod, qty) in enumerate((("C-GUL", "AGG-1020", 60), ("C-MAS", "AGG-MSAND", 30), ("C-JIN", "AGG-CRUN", 45), ("C-WAK", "AGG-0510", 30), ("C-KRW", "AGG-2040", 90))):
        soft("open-do-%d" % i, lambda c=cust, x=prod, q=qty, n=i: A.post("/fleet/delivery-orders", {
            "partnerId": par[c], "siteId": ids("site_customer")[c], "locationId": ids("location")["WH-FIN"], "requestedDate": back(-1 - n).isoformat(),
            "windowFrom": "08:00", "windowTo": "17:00", "priority": ["normal", "high", "urgent", "low", "normal"][n], "contactName": "Site storekeeper",
            "contactPhone": "+256700300400", "notes": "DEMO open delivery", "lines": [{"productId": p[x], "quantity": q, "unitPrice": 60000, "description": x}]})["id"])
    for r in (5,):
        soft("freight-gen", lambda: A.post("/fleet/freight-bills/generate", {"transporterPartnerId": t, "from": back(1).isoformat(), "to": TODAY.isoformat()}) and True)
    soft("wb-req", lambda: A.put("/fleet/weighbridge/dispatch-requirements", {"mode": "off", "notes": "DEMO: weighbridge optional"}) and True)


def _transporter_trip(cust, prod, qty, plate, driver, partner, n):
    p, par = ids("product"), ids("partner")
    do = A.post("/fleet/delivery-orders", {"partnerId": par[cust], "siteId": ids("site_customer")[cust], "locationId": ids("location")["WH-FIN"],
                                           "requestedDate": TODAY.isoformat(), "notes": "DEMO transporter delivery",
                                           "lines": [{"productId": p[prod], "quantity": qty, "unitPrice": 60000, "description": prod}]})
    trip = A.post("/fleet/delivery-orders/%s/trips" % do["id"], {"plannedDate": TODAY.isoformat(), "lines": [{"deliveryOrderLineId": do["lines"][0]["id"], "quantity": qty}]})
    tid = trip.get("id") or trip["trip"]["id"]
    A.post("/fleet/trips/%s/assign" % tid, {"carrier": "transporter", "transporterPartnerId": partner, "freightBasis": "per_tonne", "freightRate": 7500,
                                            "transporterPlate": plate, "transporterDriverName": driver})
    d = A.post("/fleet/trips/%s/dispatch" % tid, {})
    pod = [{"loadLineId": x["id"], "deliveredQty": x.get("dispatchedQty") or x.get("quantity")} for x in d.get("lines", [])]
    A.post("/fleet/trips/%s/proof-of-delivery" % tid, {"receiverName": "Site storekeeper", "lines": pod})
    A.post("/fleet/trips/%s/close" % tid, {"distanceKm": 40})
    return tid


# ------------------------------------------------------------------------------------------------ fleet

def fleet():
    cat, par = ids("fleetcat"), ids("partner")
    trucks = ["T-01", "T-02", "T-03", "T-04", "M-01", "M-02", "EX-01", "WL-01", "DZ-01"]
    docs = (("insurance", "Demo Insurance Co", 8000000, 200), ("road_licence", "Demo Revenue Authority", 1200000, 25), ("inspection", "Demo Inspection Centre", 400000, -12))
    for v in trucks:
        for dt_, issuer, cost, days in docs:
            soft("comp-%s-%s" % (v, dt_), lambda a=v, b=dt_, c=issuer, e=cost, d=days: A.post("/fleet/vehicles/%s/compliance-documents" % veh(a), {
                "docType": b, "documentNumber": "DEMO-%s-%s" % (a, b[:3].upper()), "issuer": c, "issuedOn": back(120).isoformat(), "expiresOn": back(-d).isoformat(),
                "reminderLeadDays": 30, "costAmount": e, "notes": "DEMO document"})["id"])
    soft("tpl-truck", lambda: _template("SVC-10K", "10,000 km truck service", cat["TIPPER-HD"], {"intervalKm": 10000, "leadKm": 1000}, ["TIPPER-HD"]))
    soft("tpl-plant", lambda: _template("SVC-250H", "250 hour plant service", cat["EXCAVATOR"], {"intervalHours": 250, "leadHours": 25}, ["EXCAVATOR"]))
    soft("tpl-loader", lambda: _template("SVC-500H", "500 hour loader service", cat["LOADER"], {"intervalHours": 500, "leadHours": 50}, ["LOADER"]))
    soft("tpl-90d", lambda: _template("SVC-90D", "90 day medium truck check", cat["TRUCK-MD"], {"intervalDays": 90, "leadDays": 10}, ["TRUCK-MD"]))
    items = [{"code": "BRK", "label": "Brakes", "section": "Safety", "severity": "critical"}, {"code": "TYR", "label": "Tyres", "section": "Safety", "severity": "major"},
             {"code": "LGT", "label": "Lights", "section": "Safety", "severity": "minor"}, {"code": "OIL", "label": "Oil level", "section": "Engine", "severity": "major"},
             {"code": "TAR", "label": "Load tarpaulin", "section": "Body", "severity": "minor"}]
    tpl = soft("insp-tpl", lambda: A.post("/fleet/inspection-templates", {"code": "PRE-TRIP", "name": "Pre-trip inspection (DEMO)", "kind": "pre_trip", "items": items, "isActive": True})["id"])
    soft("insp-tpl2", lambda: A.post("/fleet/inspection-templates", {"code": "MONTHLY", "name": "Monthly plant check (DEMO)", "kind": "periodic", "categoryId": cat["EXCAVATOR"], "items": items[:4], "isActive": True})["id"])
    for i, v in enumerate(("T-01", "T-02", "T-03", "T-04", "M-01", "M-02", "T-01", "T-03")):
        bad = {"TAR": "minor", "LGT": "minor"}.get("TAR" if i in (1, 4) else ("LGT" if i == 6 else ""), None)

        def inspect(a=v, n=i, f=bad):
            res = [{"code": x["code"], "result": "pass"} for x in items]
            if f:
                res[4 if n in (1, 4) else 2] = {"code": "TAR" if n in (1, 4) else "LGT", "result": "fail", "severity": "minor", "notes": "DEMO finding"}
            return A.post("/fleet/inspections", {"vehicleId": veh(a), "templateId": tpl, "inspectorEmployeeId": emp("GR-006"), "inspectedAt": iso(back(n * 3), 7), "notes": "DEMO", "items": res})["id"]
        soft("insp-%d" % i, inspect)
    for i, (v, t, sev) in enumerate((("T-03", "Windscreen crack", "minor"), ("M-02", "Oil leak at sump", "major"), ("WL-02", "Bucket tooth missing", "minor"))):
        soft("defect-%d" % i, lambda a=v, b=t, c=sev: A.post("/fleet/defects", {"vehicleId": veh(a), "title": b, "description": "DEMO defect", "severity": c, "reportedByEmployeeId": emp("GR-006")})["id"])
    positions = ("A1L", "A1R", "A2LO", "A2RO")
    for i in range(10):
        def tyre(n=i):
            serial = "DEMO-TYR-%03d" % n
            have = [x for x in rows(A.get("/fleet/tyres", search=serial)) if x.get("serialNumber") == serial]
            t = have[0] if have else A.post("/fleet/tyres", {"serialNumber": serial, "brand": "Triangle" if n % 2 else "Bridgestone", "model": "TR668", "size": "315/80R22.5", "tyreType": "drive" if n % 3 else "steer",
                                        "purchaseCost": 1450000, "supplierPartnerId": par["S-FUEL"], "purchasedOn": back(90).isoformat(), "invoiceReference": "DEMO-INV-T%d" % n, "newTreadDepthMm": 18, "minTreadDepthMm": 3, "notes": "DEMO tyre"})
            if n < 8:
                A.post("/fleet/tyres/%s/fit" % t["id"], {"vehicleId": veh(["T-01", "T-02"][n // 4]), "position": positions[n % 4], "at": iso(TODAY, 5), "notes": "DEMO fit"})
            return t["id"]
        soft("tyre-%d" % i, tyre)
    for i, (no, label, v, e) in enumerate((("7000-0001", "Tipper 01 card", "T-01", "GR-013"), ("7000-0002", "Tipper 02 card", "T-02", "GR-014"), ("7000-0003", "Medium truck card", "M-01", "GR-017"), ("7000-0004", "Blocked spare card", None, None))):
        soft("card-%d" % i, lambda a=no, b=label, c=v, d=e, n=i: A.post("/fleet/fuel-cards", {"cardNumber": a, "label": b, "providerPartnerId": par["S-FUEL"], "vehicleId": veh(c) if c else None, "employeeId": emp(d) if d else None,
                                                                                              "status": "blocked" if n == 3 else "active", "expiresOn": "2028-06-30", "limitAmount": 5000000, "limitPeriod": "monthly", "notes": "DEMO card"})["id"])
    diesel = soft("prod-diesel", lambda: product("FUEL-DSL", "Diesel", 5600, 5400, "L"))

    def tanks(code, name, cap, qty, lc):
        stock_in("tank-" + code, ids("location")[lc], [(diesel, qty, 5400)])
        t = A.post("/fleet/fuel-tanks", {"code": code, "name": name, "kind": "tank", "locationId": ids("location")[lc], "productId": diesel, "capacityL": cap, "reorderLevelL": cap * 0.2, "branchId": ids("branch")["GR-QRY"], "notes": "DEMO tank"})
        A.post("/fleet/fuel-tanks/%s/dips" % t["id"], {"dipLitres": qty - 35, "dippedAt": iso(back(1), 7), "notes": "DEMO dip"})
        return t["id"]
    t1 = soft("tank-1", lambda: tanks("TK-1", "Main Diesel Tank", 40000, 18000, "WH-REJ"))
    if t1:
        for i, (v, l) in enumerate((("EX-01", 340), ("EX-02", 320), ("WL-01", 180), ("DZ-01", 400))):
            soft("tank-issue-%d" % i, lambda a=v, b=l, n=i: A.post("/fleet/fuel-logs", {"vehicleId": veh(a), "source": "tank", "tankId": t1, "litres": b, "fuelType": "diesel", "hours": float(A.get("/fleet/vehicles/" + veh(a)).get("currentHours") or 10000) + 1 + n, "post": True, "notes": "DEMO tank issue"})["id"])
    drv = ids("driver")
    for v, driver, status in (("T-03", "T-03", "open"), ("M-02", "M-02", "open"), ("T-04", "T-04", "done")):
        def checkout(a=v, d=driver, s=status):
            c = A.post("/fleet/vehicles/%s/check-out" % veh(a), {"driverId": drv[d], "odometer": ids("odometer").get(a), "fuelLevelPct": 70, "condition": "good", "purpose": "Daily deliveries (DEMO)"})
            if s == "done":
                A.post("/fleet/checkouts/%s/check-in" % c["id"], {"odometer": ids("odometer").get(a) + 40, "fuelLevelPct": 35, "condition": "good", "notes": "DEMO"})
            return c["id"]
        soft("checkout-" + v, checkout)
    for code, name, basis, amount in (("TRIP", "Trip allowance", "per_trip", 25000), ("DAY", "Daily meal allowance", "per_day", 20000), ("NIGHT", "Night out allowance", "per_night", 50000)):
        soft("allow-" + code, lambda a=code, b=name, c=basis, e=amount: A.post("/fleet/allowance-rules", {"code": a, "name": b, "basis": c, "amount": e, "effectiveFrom": "2026-10-01", "isActive": True, "notes": "DEMO"})["id"])
    for i, v in enumerate(("T-01", "T-02", "T-03", "T-04", "M-01")):
        def claim(a=v, n=i):
            c = A.post("/fleet/claims", {"driverId": drv[a], "vehicleId": veh(a), "claimDate": back(n + 1).isoformat(), "routeZone": "26-50 KM", "description": "Delivery run (DEMO)", "lines": [
                {"lineType": "allowance", "basis": "per_trip", "quantity": 2, "description": "2 trips"},
                {"lineType": "expense", "expenseType": "toll", "amount": 15000 + n * 5000, "description": "Road toll"}]})
            if n < 3:
                A.post("/fleet/claims/%s/submit" % c["id"], {})
            return c["id"]
        soft("claim-%d" % i, claim)
    for i, (v, d, t, sev, cost, status) in enumerate((("T-02", "GR-014", "collision", "major", 6800000, None), ("M-01", "GR-017", "property_damage", "minor", 950000, None), ("T-04", "GR-016", "load_spill", "minor", 400000, None), ("WL-02", None, "other", "minor", 700000, None))):
        def incident(a=v, b=d, c=t, e=sev, f=cost, n=i):
            r = A.post("/fleet/incidents", {"vehicleId": veh(a), "driverEmployeeId": emp(b) if b else None, "occurredAt": iso(back(4 + n * 6), 10), "location": "Jinja Road (DEMO)", "incidentType": c, "severity": e, "description": "DEMO incident",
                                            "damageDescription": "Body damage (DEMO)", "estimatedCost": f, "atFault": "shared", "policeReportNumber": "DEMO-POL-%d" % n, "policeStation": "Demo Police Post", "notes": "DEMO"})
            if n == 0:
                A.post("/fleet/incidents/%s/claims" % r["id"], {"claimNumber": "DEMO-CLM-1", "insurerName": "Demo Insurance Co", "policyNumber": "DEMO-POL", "submittedOn": back(2).isoformat(), "amountClaimed": f, "excessAmount": 500000, "notes": "DEMO"})
            return r["id"]
        soft("incident-%d" % i, incident)
    for i, (v, d, off, amount) in enumerate((("T-01", "GR-013", "Overloading at weigh station", 400000), ("T-03", "GR-015", "Speeding", 200000), ("M-02", "GR-018", "Expired road licence", 150000), ("T-04", "GR-016", "No reflective triangle", 100000))):
        soft("fine-%d" % i, lambda a=v, b=d, c=off, e=amount, n=i: A.post("/fleet/fines", {"vehicleId": veh(a), "driverEmployeeId": emp(b), "offenceAt": iso(back(3 + n * 5), 9), "location": "Kampala (DEMO)", "offence": c, "authority": "Demo Traffic Police", "noticeNumber": "DEMO-FN-%d" % n, "amount": e, "dueDate": back(-20).isoformat(), "notes": "DEMO"})["id"])
    for i, v in enumerate(("T-01", "T-02", "T-03", "T-04", "EX-01")):
        soft("tele-%d" % i, lambda a=v, n=i: A.post("/fleet/telematics/devices", {"vehicleId": veh(a), "provider": "mock", "externalUnitId": "DEMO-UNIT-%d" % n, "name": "Tracker " + a, "imei": "35000000000%04d" % n, "simNumber": "+25670000%04d" % n, "syncOdometer": False, "syncHours": False, "notes": "DEMO"})["id"])
    soft("tele-sync", lambda: A.post("/fleet/telematics/sync", {}) and True)
    soft("plant-1", lambda: _plant())


def _template(code, name, category, intervals, cats):
    t = A.post("/fleet/service-templates", dict({"code": code, "name": name, "categoryId": category, "tasks": ["Change oil", "Replace filters", "Grease points"], "isActive": True}, **intervals))
    A.post("/fleet/service-templates/%s/apply" % t["id"], {"categoryId": category})
    return t["id"]


def _plant():
    data = A.post("/fleet/vehicles", {"code": "FP-GEN", "name": "Standby Generator 500 kVA (DEMO)", "categoryId": ids("fleetcat")["CRUSHER"], "ownership": "owned", "make": "Perkins", "model": "2506", "year": 2022, "fuelType": "diesel", "meterType": "hours", "openingHours": 3200, "branchId": ids("branch")["GR-QRY"], "notes": "DEMO fixed plant"})
    return data["id"]


# ------------------------------------------------------------------------------------------------ rentals + repair

def rental():
    par = ids("partner")
    prods = []
    for code, name, price in (("RNT-SCAF", "Scaffold Frame Set", 15000), ("RNT-MIX", "Concrete Mixer 350L", 90000), ("RNT-COMP", "Plate Compactor", 70000)):
        x = soft("prod-" + code, lambda a=code, b=name, c=price: product(a, b, c, c * 8, "UNIT"))
        prods.append(x)
    soft("rental-loc", lambda: A.post("/rental/locations/bootstrap", {}) and True)
    for i, p in enumerate(prods):
        soft("rental-rate-%d" % i, lambda a=p, n=i: A.post("/rental/rates", {"productId": a, "name": "Daily rate", "period": "day", "minUnits": 1, "price": [15000, 90000, 70000][n], "priority": 1}).get("id", True))
        soft("rental-units-%d" % i, lambda a=p, n=i: A.post("/rental/units/generate", {"productId": a, "count": 5, "codeTemplate": ["SCF", "MIX", "CMP"][n] + "-{n}"}) and True)
    soft("rental-pkg", lambda: A.post("/rental/packages", {"name": "Site starter package (DEMO)", "code": "PKG-1", "productId": prods[1], "price": 150000, "items": [{"productId": prods[0], "quantity": 2}, {"productId": prods[1], "quantity": 1}]}).get("id", True))
    rent_stock = next((x["id"] for x in rows(A.get("/inventory/locations", pageSize=300)) if x.get("code") == "RENT-STOCK"), None)
    if rent_stock:
        stock_in("rental-units", rent_stock, [(x, 10, 400000) for x in prods])
    for i, cust in enumerate(("C-MUK", "C-WAK", "C-KRW", "C-JIN")):
        def agreement(c=cust, n=i):
            start, end = back(10 - n * 2), back(-5 - n)
            have = [x for x in rows(A.get("/rental/agreements", pageSize=30)) if x.get("partnerId") == par[c]]
            if have:
                if n < 3 and have[0].get("status") == "confirmed":
                    A.post("/rental/agreements/%s/checkout" % have[0]["id"], {}, key="rent-out-%d" % n)
                return have[0]["id"]
            a = A.post("/rental/agreements", {"partnerId": par[c], "startAt": iso(start), "dueAt": iso(end), "notes": "DEMO rental",
                                              "lines": [{"productId": prods[n % 3], "quantity": 1 + n % 2}]})
            A.post("/rental/agreements/%s/confirm" % a["id"], {})
            if n < 3:
                A.post("/rental/agreements/%s/checkout" % a["id"], {})
            return a["id"]
        soft("rental-agr-%d" % i, agreement)


def repair():
    par = ids("partner")
    for code, name, rate in (("LAB-MECH", "Mechanic", 25000), ("LAB-ELEC", "Electrician", 30000), ("LAB-WELD", "Welder", 28000)):
        soft("lab-" + code, lambda a=code, b=name, c=rate: A.post("/repair/labour-types", {"code": a, "name": b, "hourlyRate": c, "rate": c}).get("id", True))
    techs = []
    for i, e in enumerate(("GR-010", "GR-011", "GR-012")):
        techs.append(soft("tech-%d" % i, lambda a=e, n=i: A.post("/repair/technicians", {"employeeId": emp(a), "name": "Technician %d" % (n + 1), "code": "TECH-%d" % n, "skills": ["mechanical"], "hourlyRate": 25000}).get("id")))
    for i, (c, title, status) in enumerate((("C-MUK", "Concrete mixer gearbox repair", None), ("C-WAK", "Pump overhaul", None), ("C-JIN", "Compactor engine service", None), ("C-KRW", "Hydraulic ram reseal", None), ("C-MAS", "Vibrating table repair", None))):
        def order(a=c, b=title, n=i):
            o = A.post("/repair/orders", {"partnerId": par[a], "customerId": par[a], "title": b, "description": b + " (DEMO)", "problemDescription": b, "priority": "medium", "promisedDate": back(-5 - n).isoformat(), "notes": "DEMO repair order",
                                          "items": [{"description": b, "quantity": 1, "serialNumber": "DEMO-SN-%d" % n}]})
            return o["id"]
        oid = soft("repair-%d" % i, order)
        if oid and i < 3:
            soft("repair-job-%d" % i, lambda x=oid, n=i: A.post("/repair/orders/%s/jobs" % x, {"title": "Strip and inspect", "description": "DEMO job", "technicianId": techs[n % 3], "estimatedHours": 4}).get("id", True))
    soft("warranty-1", lambda: A.post("/repair/warranties", {"partnerId": par["C-MUK"], "customerId": par["C-MUK"], "name": "12 month parts warranty (DEMO)", "serialNumber": "DEMO-SN-0", "startDate": back(30).isoformat(), "endDate": back(-335).isoformat(), "startsOn": back(30).isoformat(), "endsOn": back(-335).isoformat(), "notes": "DEMO"}).get("id", True))
    soft("contract-1", lambda: A.post("/repair/contracts", {"partnerId": par["C-KRW"], "customerId": par["C-KRW"], "name": "Plant service contract (DEMO)", "title": "Plant service contract (DEMO)", "startDate": back(30).isoformat(), "endDate": back(-335).isoformat(), "amount": 12000000, "notes": "DEMO"}).get("id", True))


def planning():
    p = ids("product")
    bom = ids("steps").get("x:bom-bag")
    for i, (code, qty, days) in enumerate((("BAG-AGG", 500, 7), ("BAG-AGG", 800, 14), ("BAG-AGG", 300, 21), ("BAG-AGG", 650, 28))):
        def request(c=code, q=qty, d=days, n=i):
            r = A.post("/manufacturing/requests", {"neededBy": back(-d).isoformat(), "notes": "DEMO request", "lines": [
                {"productId": p[c], "quantity": q, "bomId": bom, "neededBy": back(-d).isoformat()}]})
            if n < 3:
                A.post("/manufacturing/requests/%s/submit" % r["id"], {})
            if n < 2:
                C.post("/manufacturing/requests/%s/approve" % r["id"], {})
            return r["id"]
        soft("req-%d" % i, request)
    for i, (name, start, end, state) in enumerate((("November plan (DEMO)", 0, 24, "confirmed"), ("December plan (DEMO)", 25, 55, "draft"))):
        def plan(a=name, b=start, c=end, d=state, n=i):
            pl = A.post("/manufacturing/plans", {"name": a, "dateFrom": back(-b).isoformat(), "dateTo": back(-c).isoformat(), "locationId": ids("location")["WH-AGG"], "notes": "DEMO"})
            A.post("/manufacturing/plans/%s/lines" % pl["id"], {"lines": [{"productId": p["BAG-AGG"], "bomId": bom, "targetQty": 1000 + n * 200, "scheduledDate": back(-b - 3).isoformat(), "notes": "DEMO"}]})
            if d == "confirmed":
                A.post("/manufacturing/plans/%s/confirm" % pl["id"], {})
            return pl["id"]
        soft("plan-%d" % i, plan)
    soft("eco-1", lambda: A.post("/manufacturing/boms/%s/revise" % bom, {"reason": "Lighter bag (DEMO)"}) and True)


def _press_route():
    r = A.post("/manufacturing/routings", {"name": "Press and cure route (DEMO)", "operations": [
        {"sequence": 10, "name": "Pressing", "workCenterId": ids("wc")["WC-PRESS"], "durationMins": 240},
        {"sequence": 20, "name": "Curing", "workCenterId": ids("wc")["WC-PACK"], "durationMins": 60}]})
    setup = A.get("/manufacturing/routings/%s/stage-setup" % r["id"])
    print("   stage setup sample:", str(setup)[:400])
    return r["id"]


def precast2():
    """Pressing needs its earlier stages done, so these orders use a press-only route and reach the curing board."""
    wh = ids("location")["WH-AGG"]
    route = soft("routing-press2", lambda: _press_route())
    cement, area = ids("product").get("RM-CEMENT"), ids("steps").get("x:area-1")
    for i, (code, name, units, days) in enumerate((("PC-BLOCK6", "Block 6in press-only mix (DEMO)", 1000, 9), ("PC-PAVER", "Paver press-only mix (DEMO)", 700, 5), ("PC-BLOCK6", "Block 6in press-only mix B (DEMO)", 600, 2))):
        def go(c=code, nm=name, u=units, d=days, n=i):
            m = A.post("/manufacturing/precast/mix-designs", {
                "name": nm, "productId": ids("product")[c], "unitsPerBatch": 100, "cementProductId": cement, "cementPerBag": 25, "batchVolumeM3": 1.2, "waterCementRatio": 0.5,
                "minCuringDays": 7, "defaultCuringAreaId": area, "expectedYieldPct": 97, "routingId": route, "pressWorkCenterId": ids("wc")["WC-PRESS"], "defaultLocationId": wh,
                "defaultOutputLocationId": wh, "isDefault": False, "notes": "DEMO",
                "lines": [{"productId": cement, "qtyPerBatch": 4}, {"productId": ids("product")["AGG-DUST"], "qtyPerBatch": 1.2}]})
            A.post("/manufacturing/precast/mix-designs/%s/activate" % m["id"], {})
            o = A.post("/manufacturing/precast/orders", {"mixDesignId": m["id"], "basis": "units", "value": u, "locationId": wh, "outputLocationId": wh,
                                                         "scheduledFor": back(d).isoformat(), "batchNumber": "PX-%03d" % n, "confirm": True, "notes": "DEMO"})
            A.post("/manufacturing/precast/orders/%s/pressing-runs" % o["id"], {"qtyGood": round(u * 0.96), "qtyBroken": round(u * 0.03), "scrapReasonCode": "BROKEN", "cycles": 20, "pallets": 12,
                                                                                "curingAreaId": area, "shift": "Morning", "lastRun": True, "date": back(d).isoformat(), "notes": "DEMO"}, key="px-press-%d" % n)
            return o["id"]
        soft("px-%d" % i, go)
    for b in rows(A.get("/manufacturing/precast/curing-batches", pageSize=10))[:6]:
        soft("cure-%s" % b["id"], lambda x=b["id"]: A.post("/manufacturing/precast/curing-batches/%s/readings" % x, {"temperatureC": 29, "humidityPct": 76, "readAt": TODAY.isoformat(), "notes": "DEMO"}) and True)


def fleet2():
    for i, (v, last, days) in enumerate((("T-01", 50, 30), ("T-03", 45, 30), ("EX-01", 100, 90), ("WL-01", 95, 90), ("M-01", 25, 30), ("DZ-01", 80, 90))):
        soft("svc-%d" % i, lambda a=v, b=last, c=days: A.post("/fleet/vehicles/%s/service-schedules" % veh(a), {
            "name": "Monthly safety service (DEMO)", "intervalDays": c, "leadDays": 7, "lastDoneOn": back(b).isoformat(), "tasks": ["Check brakes", "Check fluids"], "notes": "DEMO: overdue on purpose"})["id"])
    soft("reminders", lambda: A.post("/fleet/reminders/run", {}) and True)

    def driver_login():
        email = "driver@menagranite.test"
        role = next(r for r in rows(A.get("/roles")) if r["name"] == "Administrator")
        users = {u["email"]: u["id"] for u in rows(A.get("/users", limit=200))}
        uid = users.get(email) or A.post("/users", {"email": email, "password": "MenaDemo@2026", "firstName": "Ivan", "lastName": "Tumusiime", "roleIds": [role["id"]]})["id"]
        A.post("/hr/employees/%s/link-user" % emp("GR-016"), {"userId": uid})
        return uid
    soft("driver-login", driver_login)

    def driver_trip():
        do = ids("steps")["x:open-do-3"]
        d = A.get("/fleet/delivery-orders/" + do)
        t = A.post("/fleet/delivery-orders/%s/trips" % do, {"plannedDate": back(-1).isoformat(), "lines": [{"deliveryOrderLineId": d["lines"][0]["id"], "quantity": 30}]})
        tid = t.get("id") or t["trip"]["id"]
        A.post("/fleet/trips/%s/assign" % tid, {"carrier": "own", "vehicleId": veh("T-04"), "driverId": ids("driver")["T-04"]})
        return tid
    soft("driver-trip", driver_trip)


def rental2():
    agr = [x for x in rows(A.get("/rental/agreements", pageSize=30)) if x.get("status") in ("active", "checked_out", "out")]
    if agr:
        def ret():
            d = A.get("/rental/agreements/" + agr[-1]["id"])
            lines = d.get("lines") or d.get("items") or []
            return A.post("/rental/returns", {"agreementId": d["id"], "notes": "DEMO return", "items": [
                {"agreementLineId": x["id"], "lineId": x["id"], "unitId": x.get("unitId"), "productId": x.get("productId"), "quantity": x.get("quantity", 1), "condition": "good", "conditionGrade": "good"} for x in lines]})["id"]
        soft("rental-return", ret)


def repair2():
    orders = [ids("steps").get("x:repair-%d" % i) for i in range(5)]
    for i, o in enumerate(orders[:3]):
        soft("warranty-o%d" % i, lambda a=o, n=i: A.post("/repair/warranties", {"repairOrderId": a, "warrantyType": "repair_warranty", "coverageStart": back(10 + n).isoformat(), "coverageEnd": back(-350).isoformat(), "warrantyPeriodMonths": 12, "coveredParts": True, "coveredLabour": n % 2 == 0, "terms": "DEMO terms", "status": "active"}).get("id", True))


def run():
    for section in (quarry, quality, production, planning, precast2, logistics, fleet, fleet2, rental, rental2, repair, repair2):
        print("-", section.__name__, flush=True)
        try:
            section()
        except (ApiError, KeyError, IndexError, TypeError) as e:
            FAILED.append(section.__name__)
            print("   SECTION STOPPED %s: %s" % (section.__name__, str(e)[:260]), flush=True)
    print("  extras skipped:", len(FAILED))

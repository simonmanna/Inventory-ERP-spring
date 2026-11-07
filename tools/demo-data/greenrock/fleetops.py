"""Fuel logs (backdated, posted), downtime and maintenance work orders across the fleet."""
import datetime as dt
import random

from core import A, ApiError, ids, once, rows
from masters import VEHICLES

OPENING = {v[0]: v[8] for v in VEHICLES}
TRUCK = {"T-01", "T-02", "T-03", "T-04", "M-01", "M-02"}


def fuel():
    """Plant fuel over the period; truck fuel matches the kilometres the trucks drove in this demo window."""
    rng = random.Random(9)
    v = ids("vehicle")
    for code in OPENING:
        if code in TRUCK:
            continue
        for n in range(6):
            day = dt.date(2026, 10, 8) + dt.timedelta(days=n * 5 + rng.randint(0, 2))
            litres = rng.randrange(220, 420, 20)
            # Hour meter at that date: between the opening reading and the reading the extraction logs reached.
            start = float(OPENING[code])
            latest = float(A.get("/fleet/vehicles/" + v[code]).get("currentHours") or start)
            top = latest if latest > start else start + 300
            hours = round(start + (top - start) * (n + 1) / 7, 1)
            once("fuel-%s-%d" % (code, n), lambda c=code, d=day, l=litres, i=n, h=hours: A.post("/fleet/fuel-logs", {
                "vehicleId": v[c], "fuelledAt": d.isoformat() + "T06:30:00Z", "source": "station", "litres": l, "hours": h,
                "unitPrice": 5400, "fullTank": False, "fuelType": "diesel", "supplierId": ids("partner")["S-FUEL"],
                "stationName": "Central Fuel Depot (DEMO)", "paymentMethod": "credit",
                "receiptNumber": "DEMO-FUEL-%s-%d" % (c, i), "post": True, "notes": "DEMO fuel log"})["id"])
    for code in sorted(TRUCK):
        end = ids("odometer").get(code)
        km = (end or 0) - float(OPENING[code])
        if km <= 0:
            continue
        rate = 0.48 if code.startswith("T") else 0.28  # litres per km, loaded out and empty back
        once("fuel-%s" % code, lambda c=code, e=end, l=round(km * rate, 1): A.post("/fleet/fuel-logs", {
            "vehicleId": v[c], "source": "station", "litres": l, "unitPrice": 5400, "fullTank": True, "odometer": e,
            "fuelType": "diesel", "supplierId": ids("partner")["S-FUEL"], "stationName": "Central Fuel Depot (DEMO)",
            "paymentMethod": "credit", "receiptNumber": "DEMO-FUEL-" + c, "post": True,
            "notes": "DEMO: fuel for the delivery kilometres of this window"})["id"])


def maintenance():
    v, em = ids("vehicle"), ids("employee")
    jobs = [("EX-03", "breakdown", "unplanned", "hydraulic", "Hydraulic hose burst at Southern Extension", "2026-10-29", 26, 4200000),
            ("CR-01", "breakdown", "unplanned", "other", "Secondary crusher mantle failure", "2026-11-01", 18, 7800000),
            ("T-02", "preventive", "planned", "preventive_service", "Tipper 02 10,000 km service", "2026-10-21", 8, 1650000),
            ("WL-01", "corrective", "unplanned", "tyres", "Wheel Loader 01 tyre replacement", "2026-10-25", 6, 3200000),
            ("DZ-01", "preventive", "planned", "preventive_service", "Bulldozer undercarriage inspection", "2026-10-16", 10, 2400000)]
    for i, (code, typ, kind, reason, title, day, hours, bill) in enumerate(jobs):
        def go(c=code, t=typ, k=kind, r=reason, ti=title, d=day, h=hours, b=bill, n=i):
            start = dt.datetime.fromisoformat(d + "T07:00:00+00:00")
            # Pick up what an interrupted run left behind: these two routes are not idempotent.
            open_dt = rows(A.get("/fleet/downtime", vehicleId=v[c], open="true"))
            dtm = open_dt[0] if open_dt else A.post("/fleet/downtime", {
                "vehicleId": v[c], "kind": k, "reasonCode": r, "description": "DEMO. " + ti,
                "startedAt": start.strftime("%Y-%m-%dT%H:%M:%SZ")})
            old = [x for x in rows(A.get("/fleet/work-orders", vehicleId=v[c])) if x.get("title") == ti]
            wo = old[0] if old else A.post("/fleet/work-orders", {
                "vehicleId": v[c], "type": t, "priority": "high" if k == "unplanned" else "normal", "title": ti,
                "description": "DEMO work order", "dueOn": d, "assignedEmployeeId": em["GR-006"],
                "tasks": ["Diagnose", "Repair", "Road test"], "downtimeId": dtm["id"]})
            wid = wo["id"]
            try:
                A.post("/fleet/work-orders/%s/start" % wid, key="wo-%d-start" % n)
            except ApiError as e:
                if "in progress" not in str(e):
                    print("   wo start", str(e)[:160])
            detail = A.get("/fleet/work-orders/" + wid)
            if not detail.get("lines"):  # an interrupted run may have booked these already
                A.post("/fleet/work-orders/%s/labour" % wid, {"employeeId": em["GR-006"], "hours": min(h, 16), "rate": 15000,
                                                              "workDate": d, "description": "Workshop labour"})
                A.post("/fleet/work-orders/%s/bills" % wid, {"partnerId": ids("partner")["S-FUEL"], "supplierReference": "DEMO-WS-%d" % n,
                                                             "billDate": d, "amount": b, "description": "External workshop: " + ti})
            for task in detail.get("tasks") or []:
                if task.get("status") not in ("done", "skipped"):
                    A.patch("/fleet/work-orders/%s/tasks/%s" % (wid, task["id"]), {"status": "done"})
            end = start + dt.timedelta(hours=h)
            A.post("/fleet/work-orders/%s/complete" % wid, {"resolution": "Repaired and returned to service (DEMO)"})
            A.post("/fleet/work-orders/%s/close" % wid, key="wo-%d-close" % n)
            try:
                A.post("/fleet/downtime/%s/end" % dtm["id"], {"endedAt": end.strftime("%Y-%m-%dT%H:%M:%SZ"), "notes": "DEMO"})
            except ApiError as e:
                if "already" not in str(e) and "ended" not in str(e):
                    print("   downtime end", str(e)[:160])
            try:
                A.post("/fleet/vehicles/%s/status" % v[c], {"status": "active", "reason": "Back in service"}, key="wo-%d-status" % n)
            except ApiError:
                pass
            return wid
        once("wo-%d" % i, go)


def run():
    fuel()
    maintenance()
    print("  fuel logs", len([k for k in ids("steps") if k.startswith("fuel-")]))

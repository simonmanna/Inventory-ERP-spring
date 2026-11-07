"""Specifications, inspection plans and lots linked to posted crushing runs and to extraction pits.

Values are illustrative demo measurements, not certified laboratory results."""
import datetime as dt
import random

from core import A, C, ids, once, rows, save

PARAMS = [("MOIST", "Moisture content", "%", 0, 3, 1.5), ("FINES", "Fineness (passing 0.075 mm)", "%", 0, 2.5, 1.2),
          ("CLAY", "Contamination (clay lumps)", "%", 0, 1, 0.3), ("ACV", "Aggregate crushing value", "%", 0, 30, 24)]
SIEVES = {"AGG-2040": [(50, 100, 100), (40, 90, 100), (20, 0, 15), (10, 0, 5)],
          "AGG-1020": [(25, 100, 100), (20, 85, 100), (10, 0, 15), (5, 0, 5)],
          "AGG-0510": [(14, 100, 100), (10, 85, 100), (5, 0, 15), (2.36, 0, 5)],
          "AGG-DUST": [(6.3, 100, 100), (5, 85, 100), (0.6, 20, 55), (0.075, 0, 16)],
          "AGG-MSAND": [(6.3, 100, 100), (5, 90, 100), (0.6, 25, 60), (0.075, 0, 10)]}
LOC = {"AGG-2040": "WH-FIN", "AGG-1020": "WH-AGG-1020", "AGG-0510": "WH-AGG", "AGG-DUST": "WH-SAND", "AGG-MSAND": "WH-SAND-MS"}


def specs():
    p = ids("product")
    sp, pl = ids("spec"), ids("qplan")
    for code in list(SIEVES) + ["RAW-GRANITE"]:
        if code in sp:
            continue
        params = [{"code": c, "name": n, "kind": "numeric", "unit": u, "minValue": lo, "maxValue": hi, "targetValue": t,
                   "decimals": 2, "testMethod": "Site lab procedure (DEMO)", "isCritical": c in ("CLAY", "FINES"),
                   "sortOrder": i} for i, (c, n, u, lo, hi, t) in enumerate(PARAMS)]
        if code in SIEVES:
            params.append({"code": "GRADING", "name": "Particle size distribution", "kind": "sieve", "sortOrder": 9,
                           "testMethod": "Sieve analysis (DEMO)",
                           "sieveEnvelope": [{"sieveMm": s, "minPassingPct": a, "maxPassingPct": b} for s, a, b in SIEVES[code]]})
        made = A.post("/quality/specifications", {"code": "SPEC-" + code, "name": "Acceptance criteria " + code + " (DEMO)",
                                                  "productId": p[code], "grade": "Demo grade", "standard": "Internal demo criteria",
                                                  "effectiveFrom": "2026-10-01", "parameters": params})
        A.post("/quality/specifications/%s/activate" % made["id"])
        sp[code] = made["id"]
        stage = "final" if code in SIEVES else "in_process"
        pl[code] = A.post("/quality/inspection-plans", {
            "code": "IP-" + code, "name": "Inspection " + code, "stage": stage, "productId": p[code],
            "specificationId": made["id"], "samplingMethod": "fixed", "sampleSize": 1, "holdPolicy": "none",
            "autoNcr": True, "isActive": True, "stageKey": "ROM" if code == "RAW-GRANITE" else None})["id"]
        save()


def lot(key, product, qty, batch, ref, values, stage="final", loc=None, sieve=None, decide=None, tested=None):
    def go():
        # A lot left behind by an interrupted run is picked up again instead of being created twice.
        found = [x for x in rows(A.get("/quality/inspection-lots", search=batch, pageSize=50))
                 if x.get("sourceRef") == ref and x.get("productId") == ids("product")[product]]
        lid = found[0]["id"] if found else A.post("/quality/inspection-lots", {
            "stage": stage, "productId": ids("product")[product], "batchNumber": batch, "quantity": qty,
            "locationId": ids("location")[loc] if loc else None, "planId": ids("qplan")[product], "sourceRef": ref,
            "stageKey": "ROM" if stage == "in_process" else None, "applyHoldPolicy": False,
            "notes": "DEMO measurements, not certified results"})["id"]
        if values and not found:
            res = [{"parameterCode": c, "sampleNo": 1, "numericValue": v, "testedAt": tested} for c, v in values.items()]
            if sieve:
                res.append({"parameterCode": "GRADING", "sampleNo": 1, "testedAt": tested,
                            "sieve": [{"sieveMm": s, "passingPct": v} for s, v in sieve]})
            A.post("/quality/inspection-lots/%s/results" % lid, {"results": res, "notes": "DEMO"})
        if decide:
            # Segregation of duties: the accountant-level second user decides what the admin recorded.
            path, body = decide
            C.post("/quality/inspection-lots/%s/%s" % (lid, path), body)
        return lid
    return once(key, go)


def good_values(rng, pit_factor=1.0):
    return {"MOIST": round(rng.uniform(0.8, 2.2) * pit_factor, 2), "FINES": round(rng.uniform(0.7, 1.9) * pit_factor, 2),
            "CLAY": round(rng.uniform(0.1, 0.6) * pit_factor, 2), "ACV": round(rng.uniform(21, 27), 2)}


def good_sieve(code, rng):
    out = []
    for s, a, b in SIEVES[code]:
        out.append((s, round(rng.uniform(a + (b - a) * 0.3, b - (b - a) * 0.2), 1) if b > a else b))
    return out


def lots():
    rng = random.Random(11)
    steps = ids("steps")
    runs = [v for k, v in sorted(steps.items()) if k.startswith("run-1-") and isinstance(v, dict)]
    products = ["AGG-2040", "AGG-1020", "AGG-0510", "AGG-DUST"]
    # Approved: every fourth run gets a passing final inspection, released.
    for i, r in enumerate(runs[::4]):
        code = products[i % 4]
        tested = r["day"] + "T14:00:00Z"
        lot("lot-pass-%d" % i, code, round(float(r["feed"]) * 0.2, 1), r["code"], "Crushing run " + r["code"],
            good_values(rng), sieve=good_sieve(code, rng), tested=tested,
            decide=("release", {"reason": "Within acceptance criteria"}))
    # ROM quality by pit, linked to the extraction day by its reference. Southern Extension is the weakest.
    for pit, factor, material in (("PIT-A", 0.8, "Granite Ridge"), ("PIT-B", 0.9, "Eastern Rock Face"), ("PIT-C", 1.45, "Southern Extension")):
        for n, day in enumerate(("2026-10-12", "2026-10-22", "2026-11-02")):
            vals = good_values(rng, factor + n * (0.12 if pit == "PIT-C" else 0))
            lot("lot-rom-%s-%d" % (pit, n), "RAW-GRANITE", 300, "%s-%s" % (pit, day), "Extraction log %s %s (%s)" % (pit, day, material),
                vals, stage="in_process", tested=day + "T10:00:00Z")
    # Requires further inspection: results in, held.
    for i, r in enumerate((runs[2], runs[3], runs[5])):
        lot("lot-hold-%d" % i, "AGG-1020", 60, r["code"], "Crushing run " + r["code"],
            {"MOIST": 2.9, "FINES": 2.4}, tested=r["day"] + "T15:00:00Z",
            decide=("hold", {"reason": "Borderline fines, re-sampling requested"}))
    # Scenario 3: batch rejected outside the acceptance criteria, scrapped through the normal decision.
    r = steps["run-scn-downtime"]
    lot("lot-reject", "AGG-1020", 40, r["code"], "Crushing run " + r["code"],
        {"MOIST": 2.8, "FINES": 4.6, "CLAY": 1.9, "ACV": 27.5}, loc="WH-AGG-1020",
        sieve=[(25, 100), (20, 70), (10, 30), (5, 12)], tested=r["day"] + "T16:00:00Z",
        decide=("reject", {"quantity": 40, "disposition": "scrap",
                           "reason": "Fines and clay contamination outside acceptance criteria after mantle failure"}))


def run():
    specs()
    lots()
    print("  lots", len([k for k in ids("steps") if k.startswith("lot-")]))

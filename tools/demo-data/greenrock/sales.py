"""Order to cash through the real services: sales order -> delivery order -> trip -> weighbridge ticket -> dispatch ->
proof of delivery -> invoice (goods) -> delivery-order invoice (freight). Scenarios are data, see ORDERS."""
import time

from core import A, C, ApiError, ids, once, rows, save
from masters import CUSTOMERS, VEHICLES

OPENING = {v[0]: v[8] for v in VEHICLES}
KM = {c[0]: c[5] for c in CUSTOMERS}
LOC = {"AGG-2040": "WH-FIN", "AGG-1020": "WH-AGG-1020", "AGG-0510": "WH-AGG", "AGG-DUST": "WH-SAND",
       "AGG-CRUN": "WH-FIN-CRUN", "AGG-MSAND": "WH-SAND-MS", "AGG-REJ": "WH-REJ"}
TARE = {"T-01": 12500, "T-02": 12650, "T-03": 12400, "T-04": 12580, "M-01": 6900, "M-02": 7050}
TRUCKS = ["T-01", "T-02", "T-03", "T-04"]
TODAY = "2026-11-06"

# key, customer, product, ordered t, net tonnes per trip, requested date, options:
#   invoice_after: bill after that many trips; leave_rest: never bill the remainder (revenue leakage)
#   short: shortage on the POD; manual: manual weigh on trip n; void: void + reissue on trip n
#   over: loaded above the ordered quantity; pending: leave that exception undecided; open: stop before the POD
ORDERS = [
    ("SO-LEAK-100", "C-JIN", "AGG-2040", 100, [30, 30, 30, 6], TODAY,
     {"invoice_after": 3, "leave_rest": True,
      "note": "SCENARIO 4: 100 MT ordered, 96 MT dispatched, 90 MT invoiced, 6 MT dispatched but not billed"}),
    ("SO-NEAR-120", "C-WAK", "AGG-1020", 120, [30, 30, 30, 30], TODAY, {"note": "SCENARIO 5: identical order, 18 km haul"}),
    ("SO-FAR-120", "C-GUL", "AGG-1020", 120, [30, 30, 30, 30], TODAY, {"note": "SCENARIO 5: identical order, 210 km haul"}),
    ("SO-0510-A", "C-MUK", "AGG-0510", 60, [30, 30], TODAY, {"note": "SCENARIO 6: 5-10mm margin"}),
    ("SO-2040-A", "C-MUK", "AGG-2040", 60, [30, 30], TODAY, {"note": "SCENARIO 6: 20-40mm margin"}),
    ("SO-KRW-01", "C-KRW", "AGG-2040", 200, [30, 30, 30, 30, 30, 30, 20], TODAY,
     {"note": "Contract price, high volume, short haul"}),
    ("SO-KRW-02", "C-KRW", "AGG-CRUN", 60, [30, 30], TODAY, {}),
    ("SO-MAS-01", "C-MAS", "AGG-MSAND", 45, [30, 15], TODAY,
     {"short": {"trip": 1, "tonnes": 1.4, "reason": "spillage"}, "note": "SCENARIO: delivery shortage on proof of delivery"}),
    ("SO-MAS-02", "C-MAS", "AGG-0510", 30, [30], "2026-11-04", {"note": "Delivered after the requested date"}),
    ("SO-WAK-01", "C-WAK", "AGG-MSAND", 30, [30], TODAY, {}),
    ("SO-WAK-02", "C-WAK", "AGG-DUST", 60, [30, 30], TODAY, {"manual": 1, "note": "SCENARIO: manual weight entry with a reason"}),
    ("SO-MUK-01", "C-MUK", "AGG-DUST", 24, [12, 12], "2026-11-05",
     {"trucks": ["M-01", "M-02"], "note": "Delivered after the requested date"}),
    ("SO-JIN-02", "C-JIN", "AGG-1020", 58, [30, 28], TODAY, {"void": 0, "note": "SCENARIO: ticket voided and reissued"}),
    ("SO-JIN-03", "C-JIN", "AGG-0510", 30, [33.5], TODAY,
     {"over": True, "note": "SCENARIO: ordered 30 t, loaded 33.5 t, exception approved by the second user before dispatch"}),
    ("SO-UBL-20", "C-KRW", "AGG-2040", 20, [20], TODAY,
     {"trucks": ["T-01"], "note": "Example ticket: UBL 482K, gross 32,500 kg, tare 12,500 kg, net 20,000 kg"}),
    ("SO-GUL-02", "C-GUL", "AGG-2040", 90, [30, 30, 30], TODAY,
     {"trucks": ["T-02", "T-03", "T-04"], "note": "High volume, long haul: lower price and costly freight"}),
    ("SO-KRW-03", "C-KRW", "AGG-1020", 30, [30], TODAY,
     {"trucks": ["T-02"], "open": True, "tare": 13180,
      "note": "Open, in transit. Empty truck weighed 13,180 kg against a stored tare of 12,650 kg"}),
    ("SO-PEND-12", "C-MUK", "AGG-0510", 12, [14.1], TODAY,
     {"trucks": ["M-01"], "over": True, "pending": True, "open": True, "planned": 12,
      "note": "SCENARIO: ordered 12 t, loaded 14.1 t, weighbridge exception waiting for approval"}),
]


def load_factors():
    for c in LOC:
        once("factor-" + c, lambda x=c: A.put("/fleet/product-load-factors/" + ids("product")[x],
                                              {"tonnesPerUnit": 1}) and True)


def stored_tares():
    """Stored tares of the fleet: the Tares screen is where weighed empties are compared to the record."""
    v = ids("vehicle")
    for truck, kg in TARE.items():
        once("tare-" + truck, lambda t=truck, k=kg: A.post("/fleet/weighbridge/tares", {
            "vehicleId": v[t], "stationId": ids("wb")["station"], "manualKg": k,
            "manualReason": "Fleet tare survey (DEMO)"})["id"])


def odometer(truck):
    """Running odometer per truck: trip distance, cost per km and L/100 km all come from these readings."""
    return ids("odometer").setdefault(truck, float(OPENING[truck]))


def weigh(ticket, first_kg, second_kg, manual=False):
    dev = ids("wb")["device"]
    if manual:
        reason = "Indicator offline during morning shift"
        A.post("/fleet/weighbridge/tickets/%s/first-weigh" % ticket, {"manualKg": first_kg, "manualReason": reason,
                                                                      "note": "DEMO manual entry"})
        return A.post("/fleet/weighbridge/tickets/%s/second-weigh" % ticket, {"manualKg": second_kg, "manualReason": reason,
                                                                              "note": "DEMO manual entry"})
    A.post("/fleet/weighbridge/devices/%s/simulator-weight" % dev, {"weightKg": first_kg}, key="sim-%s-1" % ticket)
    A.post("/fleet/weighbridge/tickets/%s/first-weigh" % ticket, {"deviceId": dev})
    A.post("/fleet/weighbridge/devices/%s/simulator-weight" % dev, {"weightKg": second_kg}, key="sim-%s-2" % ticket)
    return A.post("/fleet/weighbridge/tickets/%s/second-weigh" % ticket, {"deviceId": dev})


def deliver(key, customer, product, ordered, loads, date, opt):
    p, par, loc = ids("product"), ids("partner"), ids("location")
    lc = loc[LOC[product]]

    def order():
        so = A.post("/sales/orders", {
            "partnerId": par[customer], "branchId": ids("branch")["GR-QRY"], "locationId": lc, "customerRef": key,
            "requestedDate": date, "notes": "DEMO. " + opt.get("note", ""),
            "lines": [{"productId": p[product], "quantity": ordered, "description": product}]}, key="so-" + key)
        A.post("/sales/orders/%s/confirm" % so["id"], key="so-confirm-" + key)
        do = A.post("/sales/orders/%s/delivery-orders" % so["id"], {
            "locationId": lc, "requestedDate": date, "siteId": ids("site_customer")[customer],
            "notes": opt.get("note", "DEMO")}, key="do-" + key)
        return {"so": so["id"], "number": so.get("number"), "do": do["id"]}
    head = once(key + ":order", order)
    line = A.get("/fleet/delivery-orders/" + head["do"])["lines"][0]["id"]
    trucks = opt.get("trucks") or TRUCKS
    for i, net in enumerate(loads):
        if opt.get("invoice_after") == i:
            once(key + ":invoice", lambda: invoice(key, head, date, sum(loads[:i]), with_freight=False))
        deliver_trip("%s:trip%d" % (key, i), head, line, i, net, trucks[i % len(trucks)], customer, product, lc, date, opt)
    if opt.get("open"):
        return head
    if opt.get("leave_rest"):
        # The remaining 4 t are cancelled by closing the delivery order; only then can its freight be invoiced.
        once(key + ":doclose", lambda: A.post("/fleet/delivery-orders/%s/close" % head["do"], {
            "reason": "Customer accepted 96 MT against the 100 MT order"}) and True)
        once(key + ":freight", lambda: freight_invoice(key + "-closed", head, date))
    else:
        # A shortage stays billable as dispatched until its discrepancy is decided; it is left pending for the demo.
        once(key + ":invoice", lambda: invoice(key, head, date, sum(loads)))
    return head


def deliver_trip(trip_key, head, line, i, net, truck, customer, product, lc, date, opt):
    v, d, par, p = ids("vehicle"), ids("driver"), ids("partner"), ids("product")
    planned = opt.get("planned") or (min(net, 30.0) if opt.get("over") else net)

    def plan():
        t = A.post("/fleet/delivery-orders/%s/trips" % head["do"], {
            "plannedDate": date, "lines": [{"deliveryOrderLineId": line, "quantity": planned}]}, key=trip_key)
        tid = t.get("id") or (t.get("trip") or {}).get("id")
        A.post("/fleet/trips/%s/assign" % tid, {"carrier": "own", "vehicleId": v[truck], "driverId": d[truck]}, key=trip_key + "a")
        return tid
    tid = once(trip_key + ":plan", plan)

    def ticket():
        # Linking the trip at creation fails for orders of the sales module: the pre-fill copies the SalesOrder id into
        # salesOrderId, which is validated against the legacy Order table ("Sales order not found"). See the README.
        # Creating the ticket first and linking the trip with a header edit keeps the real foreign keys.
        made = A.post("/fleet/weighbridge/tickets", {
            "stationId": ids("wb")["station"], "subjectType": "sale_delivery", "vehicleId": v[truck], "driverId": d[truck],
            "partnerId": par[customer], "productId": p[product], "orderedQty": planned,
            "orderedUomId": ids("uom")["TON"], "locationId": lc, "referenceNumber": head.get("number"),
            "notes": "DEMO ticket"}, key=trip_key + "t")["id"]
        A.patch("/fleet/weighbridge/tickets/" + made, {"deliveryOrderId": head["do"], "tripId": tid}, key=trip_key + "tl")
        return made
    wt = once(trip_key + ":ticket", ticket)
    tare = opt.get("tare") or TARE[truck]
    gross = tare + int(round(net * 1000))
    once(trip_key + ":weigh", lambda: weigh(wt, tare, gross, manual=opt.get("manual") == i) and True)
    if opt.get("void") == i:
        def void():
            r = A.post("/fleet/weighbridge/tickets/%s/void" % wt, {
                "reason": "Wrong truck plate captured, ticket reissued", "reissue": True, "copyWeights": True})
            return (r.get("reissued") or r.get("ticket") or r).get("id")
        once(trip_key + ":void", void)
    if opt.get("pending"):
        return  # stays in the exception queue, nothing dispatched
    if opt.get("over"):
        def approve():
            ex = [x for x in rows(A.get("/fleet/weighbridge/exceptions", status="pending", pageSize=50))
                  if x.get("ticketId") == wt]
            C.post("/fleet/weighbridge/exceptions/%s/approve" % ex[0]["id"], {"notes": "Customer accepted the overload (DEMO)"})
            return True
        once(trip_key + ":approve", approve)

    def dispatch():
        t = A.get("/fleet/trips/" + tid)
        if t.get("status") not in ("in_transit", "delivered", "closed", "dispatched"):
            A.post("/fleet/trips/%s/dispatch" % tid, {"odometer": odometer(truck)}, key=trip_key + "d")
        save()
        return True
    once(trip_key + ":dispatch", dispatch)
    if opt.get("open"):
        return

    def close():
        t = A.get("/fleet/trips/" + tid)
        short = opt.get("short") if (opt.get("short") or {}).get("trip") == i else None
        pod = []
        for x in t.get("lines") or []:
            q = float(x.get("dispatchedQty") or x.get("loadedQty") or x.get("quantity"))
            row = {"loadLineId": x["id"], "deliveredQty": round(q - (short["tonnes"] if short else 0), 3)}
            if short:
                row["reason"] = short["reason"]
                row["notes"] = "Tarpaulin torn, spillage on murram section"
            pod.append(row)
        A.post("/fleet/trips/%s/proof-of-delivery" % tid, {"receiverName": "Site storekeeper", "receiverPhone": "+256700300400",
                                                           "lines": pod, "notes": "DEMO POD"}, key=trip_key + "p")
        end = odometer(truck) + KM[customer] * 2
        A.post("/fleet/trips/%s/close" % tid, {"endOdometer": end}, key=trip_key + "c")
        ids("odometer")[truck] = end
        return True
    once(trip_key + ":close", close)


def invoice(key, head, date, expected, with_freight=True):
    """Goods invoice, once the billable quantity has caught up: it follows trip close through an after-commit event."""
    sid = head["so"]
    for _ in range(40):
        lines = A.get("/sales/orders/" + sid)["lines"]
        if abs(sum(float(x.get("qtyBillable") or 0) for x in lines) - float(expected)) < 0.01:
            break
        time.sleep(1.5)
    else:
        raise RuntimeError("Billable quantity of %s never reached %s" % (key, expected))
    r = A.post("/sales/orders/%s/invoices" % sid, {"issueDate": date}, key="inv-" + key)
    inv_id = (r.get("invoice") or r).get("id")
    A.post("/invoices/%s/post" % inv_id, key="inv-post-" + key)
    out = {"invoice": inv_id}
    if with_freight:
        out["freight"] = freight_invoice(key, head, date)
    return out


def freight_invoice(key, head, date):
    """Second invoice per delivery order: the distance-band transport charge (no goods, they are billed above)."""
    try:
        r = A.post("/fleet/delivery-orders/%s/invoice" % head["do"], {"issueDate": date, "includeCharges": True}, key="fr-" + key)
    except ApiError as e:
        # "pending discrepancy": SO-MAS-01's freight stays unbilled until someone decides the shortage in the demo.
        if any(x in str(e) for x in ("Nothing to invoice", "pending discrepancy", "OPERATION_PENDING")):
            return None
        raise
    fid = (r.get("document") or r.get("invoice") or r).get("id")
    if fid and A.get("/invoices/" + fid).get("status") == "draft":
        A.post("/invoices/%s/post" % fid, key="fr-post-" + key)
    return fid


def run():
    load_factors()
    stored_tares()
    for key, cust, prod, q, loads, date, opt in ORDERS:
        deliver(key, cust, prod, q, loads, date, opt)
    print("  orders", len(ORDERS))

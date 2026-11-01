"""Reusable business flows over the API. Each flow records its document ids in STATE under a step key, so a re-run
skips steps that already completed."""
from api import rows
import seed as S


def once(org, key, fn):
    done = S.STATE[org].setdefault("steps", {})
    if key in done:
        return done[key]
    out = fn()
    done[key] = out
    S.save()
    print("  ok", org, key)
    return out


def ids(org, kind):
    return S.st(org, kind)


def method_for(account_code):
    return "cash" if account_code in ("1100", "PETTY") else "mobile_money" if account_code.startswith("MOMO")         else "bank"


def tonne_products(org):
    import data as D
    return {ids(org, "product")[c] for c, _, _, u, *_ in D.PRODUCTS[org] if u == "TON"}


def acc(org, code):
    return ids(org, "account")[code]


# ------------------------------------------------------------------------------------------------ purchasing

def purchase(org, key, supplier, lines, loc, date, receive=None, bill=True, pay=None, pay_acc="1210",
             landed=None, landed_supplier=None, inspect=None, branch=None):
    """PO -> send -> receive (GRN) -> optional inspection / landed cost -> vendor bill -> supplier payment.

    lines: [(product code, qty, unit price)]; receive: {code: qty} (default all); pay: amount or None."""
    a = S.api(org)
    p, par = ids(org, "product"), ids(org, "partner")

    def run():
        out = S.STATE[org].setdefault("partial", {}).setdefault(key, {})
        if not out.get("po"):
            po = a.post("/procurement/purchase-orders", {
                "partnerId": par[supplier], "branchId": branch, "warehouseId": ids(org, "location")[loc],
                "expectedDeliveryDate": date, "description": key, "paymentType": "credit",
                "lines": [{"productId": p[c], "description": c, "quantity": q, "unitPrice": u} for c, q, u in lines]})
            out["po"] = po["id"]
            S.save()
            try:
                a.post("/procurement/purchase-orders/%s/send" % out["po"], {})
            except S.ApiError as e:
                print("   (po send skipped: %s)" % str(e)[:160])
        po_id = out["po"]
        rec = receive or {c: q for c, q, _ in lines}
        if not out.get("grn"):
            grns = [g for g in rows(a.get("/procurement/goods-receipts", pageSize=200)) if g.get("purchaseOrderId") == po_id]
            if not grns:
                po = a.get("/procurement/purchase-orders/" + po_id)
                pol = {l["productId"]: l["id"] for l in po.get("lines", [])}
                a.post("/procurement/purchase-orders/%s/receive" % po_id, {
                    "warehouseId": ids(org, "location")[loc], "receivedAt": date + "T09:00:00Z",
                    "lines": [{"purchaseOrderLineId": pol.get(p[c]), "productId": p[c], "description": c,
                               "quantity": q} for c, q in rec.items()]})
                grns = [g for g in rows(a.get("/procurement/goods-receipts", pageSize=200))
                        if g.get("purchaseOrderId") == po_id]
            out["grn"] = grns[0]["id"]
            S.save()
        if landed and not out.get("landed"):
            lc = a.post("/procurement/landed-costs", {
                "goodsReceiptId": out["grn"], "creditAccountId": acc(org, "2100"), "allocationMethod": "value",
                "date": date, "notes": "Freight, clearing and duty on " + key,
                "charges": [{"kind": k, "description": d, "amount": v} for k, d, v in landed]})
            out["landed"] = lc["id"]
            S.save()
            try:
                a.post("/procurement/landed-costs/%s/post" % out["landed"], {})
            except S.ApiError as e:
                print("   (landed post: %s)" % str(e)[:200])
        if bill and not out.get("bill"):
            bl = a.post("/vendor-bills", {
                "partnerId": par[supplier], "issueDate": date, "reference": "INV-" + key, "branchId": branch,
                "purchaseOrderIds": [po_id],
                "lines": [{"productId": p[c], "description": c, "quantity": rec.get(c, 0), "unitPrice": u}
                          for c, q, u in lines if rec.get(c, 0)]})
            out["bill"] = bl["id"]
            S.save()
            post_bill(org, bl["id"])
        if pay and not out.get("payment"):
            # The receipt voucher raised the payable at GRN time; the PO route settles it (the bill carries no residual).
            out["payment"] = a.post("/procurement/purchase-orders/%s/pay" % po_id, {
                "amount": pay, "paidAt": date + "T12:00:00Z", "method": "bank", "reference": "PAY-" + key}).get("id")
        return out
    return once(org, key, run)


def post_bill(org, bill_id):
    a = S.api(org)
    for path, who in (("/vendor-bills/%s/post", a), ("/vendor-bills/%s/approve", S.checker(org)),
                      ("/vendor-bills/%s/post", S.checker(org))):
        try:
            r = who.post(path % bill_id, {})
            if (r or {}).get("status") in ("posted", "open", "partially_paid"):
                return r
        except S.ApiError as e:
            last = e
    b = a.get("/vendor-bills/" + bill_id)
    if b.get("status") not in ("posted", "open", "partially_paid", "paid"):
        raise last


# ------------------------------------------------------------------------------------------------ production

def bom(org, key, output, out_qty, lines, overhead=0, loc=None, out_loc=None, yield_pct=None):
    a = S.api(org)
    p, l = ids(org, "product"), ids(org, "location")

    def run():
        b = a.post("/manufacturing/boms", {
            "name": key, "outputProductId": p[output], "outputQuantity": out_qty, "overheadCost": overhead,
            "expectedYieldPct": yield_pct, "isDefault": True,
            "defaultLocationId": l.get(loc), "defaultOutputLocationId": l.get(out_loc),
            "lines": [{"componentProductId": p[c], "quantity": q, "scrapPct": s, "sequence": i + 1}
                      for i, (c, q, s) in enumerate(lines)]})
        try:
            a.post("/manufacturing/boms/%s/activate" % b["id"], {})
        except S.ApiError as e:
            print("   (bom activate: %s)" % str(e)[:160])
        return b["id"]
    return once(org, "bom:" + key, run)


def produce(org, key, bom_key, qty, loc, out_loc, produced=None, scrap=0, outputs=None, date=None):
    """Production order -> confirm -> start (issue materials per BOM) -> complete."""
    a = S.api(org)
    l = ids(org, "location")

    def run():
        body = {"bomId": S.STATE[org]["steps"]["bom:" + bom_key], "locationId": l[loc], "outputLocationId": l[out_loc],
                "plannedQty": qty, "notes": key, "scheduledFor": date}
        if outputs:
            body["outputs"] = [{"productId": ids(org, "product")[c], "qtyExpected": q, "kind": k,
                                "costAllocationPct": pct} for c, q, k, pct in outputs]
        mo = a.post("/manufacturing/production-orders", body)
        mid = mo["id"]
        for step in ("confirm", "start"):
            a.post("/manufacturing/production-orders/%s/%s" % (mid, step), {})
        done = {"qtyProduced": produced if produced is not None else qty, "scrapQty": scrap, "notes": key}
        if outputs:
            mo = a.get("/manufacturing/production-orders/" + mid)
            outs = mo.get("outputs") or []
            want = {ids(org, "product")[c]: q for c, q, k, pct in outputs}
            done["outputs"] = [{"lineId": o["id"], "qtyProduced": want.get(o["productId"], o.get("qtyExpected"))}
                               for o in outs]
        a.post("/manufacturing/production-orders/%s/complete" % mid, done)
        return mid
    return once(org, key, run)


# ------------------------------------------------------------------------------------------------ sales

def invoice(org, key, customer, lines, date, pay=None, pay_acc="1100", method="cash", branch=None, due=None):
    """Direct sales invoice (issues stock from the org's first warehouse) -> post -> optional receipt."""
    a = S.api(org)
    p, par = ids(org, "product"), ids(org, "partner")

    def run():
        inv = a.post("/invoices", {
            "partnerId": par[customer], "issueDate": date, "dueDate": due or date, "reference": key,
            "lines": [{"productId": p[c], "description": c, "quantity": q, "unitPrice": u} for c, q, u in lines]})
        a.post("/invoices/%s/post" % inv["id"], {})
        out = {"invoice": inv["id"]}
        if pay:
            total = sum(q * u for _, q, u in lines)
            amt = total if pay is True else pay
            out["receipt"] = a.post("/payments", {
                "partnerId": par[customer], "paymentDate": date, "amount": round(amt), "paymentMethod": method,
                "accountId": acc(org, pay_acc), "reference": "RCPT-" + key, "branchId": branch,
                "allocations": [{"documentId": inv["id"], "amount": round(amt)}]})["id"]
        return out
    return once(org, key, run)


def receipt(org, key, customer, amount, date, pay_acc="1210", method="bank", alloc=None):
    a = S.api(org)

    def run():
        body = {"partnerId": ids(org, "partner")[customer], "paymentDate": date, "amount": amount,
                "paymentMethod": method, "accountId": acc(org, pay_acc), "reference": key}
        if alloc:
            body["allocations"] = alloc
        return a.post("/payments", body)["id"]
    return once(org, key, run)


def supplier_payment(org, key, supplier, amount, date, pay_acc="1210", alloc=None):
    a = S.api(org)

    def run():
        body = {"partnerId": ids(org, "partner")[supplier], "paymentDate": date, "amount": amount,
                "paymentMethod": "bank", "accountId": acc(org, pay_acc), "reference": key}
        if alloc:
            body["allocations"] = alloc
        return a.post("/supplier-payments", body)["id"]
    return once(org, key, run)


def sales_order(org, key, customer, lines, loc, date, trips=1, transporter=None, rate=0, branch=None,
                pay=None, pay_acc="1210", deliver=None):
    """Sales order -> confirm -> delivery order -> trips (transporter) -> dispatch -> POD -> close -> invoice.

    deliver: {code: qty} actually delivered (partial delivery); default all."""
    a = S.api(org)
    p, par, l = ids(org, "product"), ids(org, "partner"), ids(org, "location")

    def run():
        part = S.STATE[org].setdefault("partial", {}).get(key)
        if part and part.get("so"):
            return finish(part)
        so = a.post("/sales/orders", {
            "partnerId": par[customer], "branchId": branch, "locationId": l[loc], "customerRef": key,
            "requestedDate": date,
            "lines": [{"productId": p[c], "description": c, "quantity": q, "unitPrice": u} for c, q, u in lines]})
        sid = so["id"]
        a.post("/sales/orders/%s/confirm" % sid, {})
        out = {"so": sid}
        S.STATE[org]["partial"][key] = out
        S.save()
        return finish(out)

    def finish(out):
        sid = out["so"]
        stocked = [(c, q) for c, q, u in lines if not c.startswith("SVC-")]
        if stocked and not out.get("trips"):
            did = out.get("do")
            if not did:
                did = a.post("/sales/orders/%s/delivery-orders" % sid, {"locationId": l[loc],
                                                                       "requestedDate": date})["id"]
                out["do"] = did
                S.save()
            dod = a.get("/fleet/delivery-orders/" + did)
            dol = dod.get("lines", [])
            want = {p[c]: q for c, q in (deliver or {}).items()}
            trips = [x["id"] for x in dod.get("trips", []) if x.get("status") != "cancelled"]
            done_trips = {x["id"] for x in dod.get("trips", []) if x.get("status") in ("closed", "delivered")}
            for x in dol:
                qty = float(want.get(x["productId"], x.get("orderedQty"))) - float(x.get("plannedQty") or 0)
                size = 40.0 if qty > 40 and x.get("productId") in tonne_products(org) else qty
                while qty > 0.0001:
                    q = min(size, qty)
                    qty -= q
                    t = a.post("/fleet/delivery-orders/%s/trips" % did, {
                        "plannedDate": date, "lines": [{"deliveryOrderLineId": x["id"], "quantity": round(q, 3)}]})
                    trips.append(t.get("id") or (t.get("trip") or {}).get("id"))
            for i, trip in enumerate(trips):
                if trip in done_trips:
                    continue
                a.post("/fleet/trips/%s/assign" % trip, {
                    "carrier": "transporter", "transporterPartnerId": par.get(transporter) if transporter else None,
                    "freightBasis": "per_trip", "freightRate": rate,
                    "transporterPlate": ["UBK 351S", "UAZ 455M", "UBA 812T"][i % 3],
                    "transporterDriverName": ["Ssebunya", "Wasswa", "Etyang"][i % 3]})
                d = a.post("/fleet/trips/%s/dispatch" % trip, {})
                pod = [{"loadLineId": y["id"], "deliveredQty": y.get("dispatchedQty") or y.get("quantity")}
                       for y in d.get("lines", [])]
                a.post("/fleet/trips/%s/proof-of-delivery" % trip, {"receiverName": "Site storekeeper",
                                                                    "receiverPhone": "+256700300400", "lines": pod})
                a.post("/fleet/trips/%s/close" % trip, {})
            out["trips"] = trips
            if deliver:
                try:
                    a.post("/fleet/delivery-orders/%s/close" % did, {"reason": "Customer took partial quantity"})
                except S.ApiError as e:
                    print("   (DO close: %s)" % str(e)[:160])
            S.STATE[org].setdefault("partial", {})[key] = out
            S.save()
        import time
        existing = (a.get("/sales/orders/" + sid).get("relations") or {}).get("invoices") or []
        for attempt in range(0 if existing else 8):
            try:
                inv = a.call("POST", "/sales/orders/%s/invoices" % sid, {"issueDate": date},
                             key="soinv-%s-%s" % (sid, time.time()))
                break
            except S.ApiError as e:
                if "Nothing to invoice" not in str(e) or attempt == 7:
                    raise
                time.sleep(3)   # billable quantities follow trip close through an after-commit event
        inv_id = existing[0]["id"] if existing else (inv.get("invoice") or inv).get("id")
        try:
            a.post("/invoices/%s/post" % inv_id, {})
        except S.ApiError as e:
            if "posted" not in str(e):
                print("   (invoice post: %s)" % str(e)[:160])
        out["invoice"] = inv_id
        amount = pay
        if amount:
            residual = float(a.get("/invoices/" + inv_id).get("amountResidual") or 0)
            if residual < amount:
                print("   (invoice %s residual %s < planned receipt %s)" % (key, residual, amount))
                amount = round(residual)
            out["receipt"] = a.post("/payments", {
                "partnerId": par[customer], "paymentDate": date, "amount": amount, "paymentMethod": method_for(pay_acc),
                "accountId": acc(org, pay_acc), "reference": "RCPT-" + key,
                "allocations": [{"documentId": inv_id, "amount": amount}]})["id"]
        return out
    return once(org, key, run)


# ------------------------------------------------------------------------------------------------ finance

def expense(org, key, title, amount, date, account, supplier=None, pay_acc="1210", method="bank"):
    """Expense claim -> approve (accountant) -> pay."""
    a = S.api(org)

    def run():
        e = a.post("/expenses", {"title": title, "amount": amount, "expenseDate": date, "paymentType": "CREDIT",
                                 "description": key, "supplierId": ids(org, "partner").get(supplier),
                                 "categoryId": ids(org, "expcat")[account]})
        if e.get("status") == "DRAFT":
            S.checker(org).post("/expenses/%s/approve" % e["id"], {})
        S.checker(org).post("/expenses/%s/pay" % e["id"], {"paymentMethod": method, "accountId": acc(org, pay_acc),
                                                           "reference": key})
        return e["id"]
    return once(org, key, run)


def journal(org, key, date, desc, lines, cost_center=None):
    """lines: [(account code, debit, credit)]"""
    def run():
        cc = ids(org, "costcenter").get(cost_center) if cost_center else None
        return S.post_je(org, {"journalCode": "GEN", "date": date, "description": desc,
                               "lines": [{"accountId": acc(org, c), "debit": d, "credit": cr, "costCenterId": cc}
                                         for c, d, cr in lines]})["id"]
    return once(org, key, run)


def ic_journal(org, key, target, date, desc, amount, source_acc, target_code):
    def run():
        return S.api(org).post("/intercompany/journals", {
            "targetOrganizationId": S.STATE[target]["orgId"], "date": date, "description": desc,
            "amount": amount, "sourceAccountId": acc(org, source_acc), "targetAccountCode": target_code}).get("id")
    return once(org, key, run)

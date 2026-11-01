"""Corrections found while verifying the first run, done the way an accountant would (void / re-enter, reclass,
invoice the remainder). Kept as a phase so the story of each fix stays visible in the audit trail."""
import time

import flows as F
import seed as S
from api import rows

EXPENSE_KEYS = ["QRY-EXP-UMEME-OCT", "QRY-EXP-REPAIR-01", "QRY-EXP-SECURITY", "HQ-EXP-RENT-NOV", "HQ-EXP-UMEME-OCT",
                "HQ-EXP-MKT-01", "HQ-EXP-TRANS-01", "SHOW-EXP-RENT-NOV", "SHOW-EXP-UMEME-OCT"]


def fixups():
    # 1. Expenses posted to 5200 (no category ledger): void, then the scenarios re-enter them with a category.
    for org in S.D.ORGS:
        steps = S.STATE[org].get("steps", {})
        for k in EXPENSE_KEYS:
            if k in steps and not S.STATE[org].setdefault("voided", {}).get(k):
                S.checker(org).post("/expenses/%s/void" % steps[k],
                                    {"voidReason": "Re-entered under the right expense category"})
                S.STATE[org]["voided"][k] = steps.pop(k)
                S.save()
                print("  voided", org, k)
    # 2. PO payments credited the 1200 'Bank' header via the default_bank mapping: point the mapping at Stanbic and
    #    reclass what was posted.
    for org in ("MENA", "MENA-QRY"):
        a = S.api(org)
        a.put("/account-mappings/default_bank", {"accountId": F.acc(org, "1210")})
        tb = a.get("/reports/accounting/trial-balance", **{"from": "2026-01-01", "to": "2026-12-31"})
        bal = next((float(r["balance"]) for r in tb["rows"] if r["code"] == "1200"), 0)
        if bal < 0:
            F.journal(org, "FIX-RECLASS-1200", "2026-11-02", "Reclass PO payments from Bank header to Stanbic",
                      [("1200", -bal, 0), ("1210", 0, -bal)])
    # 3. Orders invoiced before the last trip's billable update landed: invoice the remainder.
    for org, key in (("MENA", "HQ-SO-ZERI-01"), ("MENA-QRY", "QRY-SO-SPEAR-01"), ("MENA-QRY", "QRY-SO-TEXA-01")):
        def run(org=org, key=key):
            a = S.api(org)
            sid = S.STATE[org]["steps"][key]["so"]
            a.post("/sales/orders/%s/rebuild" % sid, {})
            inv = a.call("POST", "/sales/orders/%s/invoices" % sid, {"issueDate": "2026-11-02"},
                         key="soinv2-%s-%s" % (sid, time.time()))
            iid = (inv.get("invoice") or inv).get("id")
            try:
                a.post("/invoices/%s/post" % iid, {})
            except S.ApiError as e:
                if "posted" not in str(e):
                    raise
            return iid
        F.once(org, "FIX-INV2-" + key, run)
    # 4. ACME took 120 of 160 t of 10-14mm: close the line short so the order completes.
    def short():
        a = S.api("MENA-QRY")
        so = a.get("/sales/orders/" + S.STATE["MENA-QRY"]["steps"]["QRY-SO-ACME-01"]["so"])
        line = next(l for l in so["lines"] if l["productId"] == S.st("MENA-QRY", "product")["AGG-1014"])
        return a.post("/sales/orders/%s/lines/%s/close-short" % (so["id"], line["id"]),
                      {"reason": "Customer took 120 t; balance of 40 t cancelled"}).get("status")
    F.once("MENA-QRY", "FIX-ACME-SHORT", short)
    # 5. Duplicate ACME order left by an aborted first run: cancel it.
    def orphan():
        a = S.api("MENA-QRY")
        keep = S.STATE["MENA-QRY"]["steps"]["QRY-SO-ACME-01"]["so"]
        out = []
        for so in rows(a.get("/sales/orders", pageSize=100)):
            if so.get("customerRef") == "QRY-SO-ACME-01" and so["id"] != keep and so["status"] == "confirmed":
                for do in rows(a.get("/fleet/delivery-orders", pageSize=100)):
                    if do.get("sourceId") == so["id"] and do.get("status") not in ("cancelled", "closed"):
                        a.post("/fleet/delivery-orders/%s/cancel" % do["id"], {"reason": "Duplicate entry"})
                a.post("/sales/orders/%s/cancel" % so["id"], {"reason": "Duplicate entry"})
                out.append(so["number"])
        return out
    F.once("MENA-QRY", "FIX-ORPHAN-SO", orphan)


UNALLOCATED = {
    "MENA-QRY": [("QRY-RCPT-ACME-OPEN", "in"), ("QRY-RCPT-KASULE-OPEN", "in"), ("QRY-RCPT-IC-HQ-OPEN", "in"),
                 ("QRY-SPAY-PETRO-OPEN", "out"), ("QRY-SPAY-EXPL-OPEN", "out")],
    "MENA": [("HQ-RCPT-CANAN-OPEN", "in"), ("HQ-RCPT-ZERI-OPEN", "in"), ("HQ-RCPT-IC-SHOW-OPEN", "in"),
             ("HQ-SPAY-IC-QRY-OPEN", "out"), ("HQ-SPAY-HIMA-OPEN", "out")],
    "MENA-SHOW": [("SHOW-RCPT-MAPUK-OPEN", "in"), ("SHOW-SPAY-IC-HQ-OPEN", "out")],
}
OCT_ACCRUALS = {"MENA-QRY": ["QRY-JE-PAYROLL-OCT"],
                "MENA": ["HQ-JE-PAYROLL-OCT", "HQ-JE-DEPR-OCT"],
                "MENA-SHOW": ["SHOW-JE-PAYROLL-OCT"]}


def open_docs(org, partner_id, direction):
    """Posted documents of the partner with a residual, oldest first (opening items first)."""
    a = S.api(org)
    if direction == "in":
        docs = rows(a.get("/invoices", partnerId=partner_id, pageSize=100))
    else:
        docs = [d for d in rows(a.get("/vendor-bills", pageSize=200)) if d.get("partnerId") == partner_id]
    docs = [d for d in docs if d.get("status") == "posted" and float(d.get("amountResidual") or 0) > 0.5]
    return sorted(docs, key=lambda d: (d.get("issueDate") or "", d.get("documentNumber") or ""))


def fixups2():
    # 6. Receipts / payments entered on account: void and re-enter allocated to the open items (oldest first).
    for org, keys in UNALLOCATED.items():
        a = S.api(org)
        for key, direction in keys:
            def run(org=org, key=key, direction=direction, a=a):
                old = a.get("/payments/" + S.STATE[org]["steps"][key])
                if float(old.get("allocatedAmount") or 0) > 0:
                    return old["id"]
                if old.get("status") != "void":
                    S.checker(org).post("/payments/%s/void" % old["id"], {"reason": "Re-entered against the invoices"})
                left, alloc = float(old["amount"]), []
                for d in open_docs(org, old["partnerId"], direction):
                    take = min(left, float(d["amountResidual"]))
                    if take > 0:
                        alloc.append({"documentId": d["id"], "amount": round(take)})
                        left -= take
                body = {"partnerId": old["partnerId"], "paymentDate": "2026-11-02", "amount": float(old["amount"]),
                        "paymentMethod": old.get("paymentMethod") or "bank", "accountId": old.get("accountId"),
                        "reference": key + " (allocated)", "allocations": alloc}
                return a.post("/payments" if direction == "in" else "/supplier-payments", body)["id"]
            F.once(org, "FIX-ALLOC-" + key, run)
    # 7. Crushing BOMs burn diesel at the stockpile yard while diesel is kept in the fuel store: move fuel to the yard.
    def fuel():
        import scenarios as SC
        return SC.transfer("MENA-QRY", "QRY-STORE", "QRY-YARD", [("SP-DIESEL", 3000)])
    F.once("MENA-QRY", "FIX-TRF-DIESEL-YARD", fuel)
    # 8. October payroll / depreciation / interest were booked on 1 Nov: they belong to the pre-go-live period, so
    #    reverse them and carry them in the opening position (opening equity) at 31 Oct instead.
    for org, keys in OCT_ACCRUALS.items():
        for key in keys:
            def run(org=org, key=key):
                a = S.api(org)
                je = a.get("/journal-entries/" + S.STATE[org]["steps"][key])
                S.checker(org).post("/journal-entries/%s/reverse" % je["id"],
                                    {"date": "2026-11-01", "description": "Move to opening position: "
                                     + (je.get("description") or key)})
                lines = []
                for l in je["lines"]:
                    code = l.get("account", {}).get("code") if isinstance(l.get("account"), dict) else None
                    acct = l["accountId"]
                    if code and code[0] in "56":
                        acct = F.acc(org, "3100")
                    lines.append({"accountId": acct, "debit": float(l.get("debit") or 0),
                                  "credit": float(l.get("credit") or 0), "description": l.get("description")})
                return S.post_je(org, {"journalCode": "GEN", "date": "2026-10-31",
                                       "description": "Opening position: " + (je.get("description") or key),
                                       "lines": lines})["id"]
            F.once(org, "FIX-OPENING-" + key, run)

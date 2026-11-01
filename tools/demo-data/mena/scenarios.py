"""The business story for go-live week (1-2 Nov 2026), one function per area. Volumes and customers follow the October
sheets; prices are the marked-up demo prices of data.py."""
import flows as F

D1, D2 = "2026-11-01", "2026-11-02"


def masters_production():
    # MENA Quarries: blasting -> ROM; crushing ROM -> aggregate co-products; block cutting for HQ.
    F.bom("MENA-QRY", "Blast & haul ROM (per 1,000 t)", "QRY-ROM", 1000,
          [("SP-EXPL", 300, 0), ("SP-DETON", 60, 0), ("SP-DIESEL", 850, 0)], overhead=4200000,
          loc="QRY-STORE", out_loc="QRY-YARD")
    F.bom("MENA-QRY", "Crush & screen 10-14mm (per 100 t)", "AGG-1014", 100,
          [("QRY-ROM", 112, 0), ("SP-DIESEL", 90, 0)], overhead=650000, loc="QRY-YARD", out_loc="QRY-YARD")
    F.bom("MENA-QRY", "Crush & screen 14-25mm (per 100 t)", "AGG-1425", 100,
          [("QRY-ROM", 110, 0), ("SP-DIESEL", 85, 0)], overhead=600000, loc="QRY-YARD", out_loc="QRY-YARD")
    F.bom("MENA-QRY", "Crush stone dust 0-6mm (per 100 t)", "AGG-DUST06", 100,
          [("QRY-ROM", 104, 0), ("SP-DIESEL", 60, 0)], overhead=380000, loc="QRY-YARD", out_loc="QRY-YARD")
    F.bom("MENA-QRY", "Cut raw granite block Royal Red (per 10 t)", "QRY-GRB-RR", 10,
          [("SP-DIESEL", 160, 0)], overhead=780000, loc="QRY-STORE", out_loc="QRY-YARD")
    # MENA Granite HQ: blocks and slab processing.
    F.bom("MENA", "Hollow block 6in (per 1,000)", "BLK-6", 1000,
          [("RM-CEM", 25, 0), ("RM-SAND", 3, 0), ("RM-DUST", 5, 0), ("RM-WATER", 1000, 0)], overhead=310000,
          loc="HQ-MAIN", out_loc="HQ-MAIN", yield_pct=98)
    F.bom("MENA", "Hollow block 8in (per 1,000)", "BLK-8", 1000,
          [("RM-CEM", 32, 0), ("RM-SAND", 4, 0), ("RM-DUST", 6.5, 0), ("RM-WATER", 1300, 0)], overhead=380000,
          loc="HQ-MAIN", out_loc="HQ-MAIN", yield_pct=98)
    F.bom("MENA", "Hollow block 4in (per 1,000)", "BLK-4", 1000,
          [("RM-CEM", 18, 0), ("RM-SAND", 2.2, 0), ("RM-DUST", 3.6, 0), ("RM-WATER", 750, 0)], overhead=240000,
          loc="HQ-MAIN", out_loc="HQ-MAIN", yield_pct=98)
    F.bom("MENA", "Saw & polish Royal Red slab (per 90 m2)", "GRN-RR", 90,
          [("RM-GRB-RR", 10, 0), ("RM-ABRAS", 1.2, 0), ("RM-RESIN", 6, 0), ("SP-DIESEL", 40, 0)],
          overhead=1150000, loc="HQ-MAIN", out_loc="HQ-MAIN")
    F.bom("MENA", "Saw & polish Spotted Green slab (per 90 m2)", "GRN-SG", 90,
          [("RM-GRB-SG", 10, 0), ("RM-ABRAS", 1.3, 0), ("RM-RESIN", 6, 0), ("SP-DIESEL", 40, 0)],
          overhead=1250000, loc="HQ-MAIN", out_loc="HQ-MAIN")
    F.bom("MENA", "Granite skirting from offcuts (per 100 rm)", "FIN-SKIRT", 100,
          [("GRN-RR", 9, 0), ("RM-ABRAS", 0.3, 0)], overhead=250000, loc="HQ-MAIN", out_loc="HQ-MAIN")


def quarry():
    o = "MENA-QRY"
    # Purchasing: diesel (partial payment), explosives (licensed supplier), crusher spares.
    F.purchase(o, "QRY-PO-DIESEL-01", "S-PETRO", [("SP-DIESEL", 20000, 5350)], "QRY-STORE", D1, pay=60000000)
    F.purchase(o, "QRY-PO-EXPL-01", "S-EXPL", [("SP-EXPL", 1500, 14200), ("SP-DETON", 300, 8800)], "QRY-STORE", D1)
    F.purchase(o, "QRY-PO-SPARES-01", "S-HMP", [("SP-JAW", 2, 4950000), ("SP-BELT", 1, 2700000)], "QRY-STORE", D1,
               pay=12600000)
    # Production
    F.produce(o, "QRY-MO-BLAST-01", "Blast & haul ROM (per 1,000 t)", 4000, "QRY-STORE", "QRY-YARD")
    F.produce(o, "QRY-MO-1014-01", "Crush & screen 10-14mm (per 100 t)", 800, "QRY-YARD", "QRY-YARD",
              produced=780, scrap=20)
    F.produce(o, "QRY-MO-1425-01", "Crush & screen 14-25mm (per 100 t)", 1200, "QRY-YARD", "QRY-YARD")
    F.produce(o, "QRY-MO-DUST-01", "Crush stone dust 0-6mm (per 100 t)", 1000, "QRY-YARD", "QRY-YARD")
    F.produce(o, "QRY-MO-GRB-01", "Cut raw granite block Royal Red (per 10 t)", 60, "QRY-STORE", "QRY-YARD")
    # Sales: ACME volume order (partial delivery), Spear On Road CRR with transport, Texa hardcore, Malik cash order.
    F.sales_order(o, "QRY-SO-ACME-01", "C-ACME", [("AGG-1014", 160, 42000), ("AGG-1425", 400, 38000)], "QRY-YARD",
                  D2, transporter="S-KTL", rate=4200000, deliver={"AGG-1014": 120, "AGG-1425": 400})
    F.sales_order(o, "QRY-SO-SPEAR-01", "C-SPEAR", [("AGG-CRR", 200, 40000), ("SVC-TRANS", 5, 850000)], "QRY-YARD",
                  D2, transporter="S-KTL", rate=3750000, pay=6000000)
    F.sales_order(o, "QRY-SO-TEXA-01", "C-TEXA", [("AGG-HARD", 80, 36000), ("SVC-TRANS", 2, 850000)], "QRY-YARD", D2,
                  transporter="S-KTL", rate=1500000)
    F.sales_order(o, "QRY-SO-MALIK-01", "C-MALIK", [("AGG-HARD", 40, 36000), ("AGG-1425", 40, 38000),
                                                     ("SVC-TRANS", 2, 850000)], "QRY-YARD", D2, transporter="S-KTL",
                  rate=1610000, pay=4660000, pay_acc="1100")
    # Walk-in cash sales at the gate (direct invoice, issue from the stockpile yard)
    for i, (code, qty, price, acct) in enumerate([("AGG-DUST06", 40, 38000, "1100"), ("AGG-DUST06", 40, 38000, "MOMO-MTN"),
                                                  ("AGG-1425", 40, 38000, "1100"), ("AGG-0610", 5, 40000, "1100"),
                                                  ("AGG-DUST08", 80, 36000, "MOMO-MTN"), ("AGG-1420", 40, 41000, "1100")]):
        F.invoice(o, "QRY-CASH-%02d" % (i + 1), "C-WALKIN", [(code, qty, price)], D2, pay=True, pay_acc=acct,
                  method="mobile_money" if acct.startswith("MOMO") else "cash")
    # Collections against opening receivables
    F.receipt(o, "QRY-RCPT-ACME-OPEN", "C-ACME", 50000000, D2)
    F.receipt(o, "QRY-RCPT-KASULE-OPEN", "C-KASULE", 1944510, D2, pay_acc="1100", method="cash")
    # Supplier payments on opening payables
    F.supplier_payment(o, "QRY-SPAY-PETRO-OPEN", "S-PETRO", 18600000, D2)
    F.supplier_payment(o, "QRY-SPAY-EXPL-OPEN", "S-EXPL", 6000000, D2)
    # Expenses
    F.expense(o, "QRY-EXP-UMEME-OCT", "Umeme - crusher power October", 14800000, D1, "6110", "S-UMEME")
    F.expense(o, "QRY-EXP-REPAIR-01", "Excavator hydraulic hose repair", 3450000, D1, "6130", "S-HMP")
    F.expense(o, "QRY-EXP-SECURITY", "Quarry security - November", 2400000, D1, "6150", pay_acc="1100", method="cash")
    F.journal(o, "QRY-JE-PAYROLL-OCT", D1, "Quarry payroll October 2026 (accrued)",
              [("5710", 38500000, 0), ("2180", 0, 32400000), ("2210", 0, 4200000), ("2230", 0, 1900000)],
              cost_center="Quarry Operations")
    F.journal(o, "QRY-JE-LOANINT-OCT", D1, "Equipment loan interest October",
              [("6200", 15000000, 0), ("1210", 0, 15000000)])


def intercompany():
    # MENA Quarries sells aggregate + raw granite blocks to MENA Granite HQ (invoice mirrors as a posted bill in HQ).
    F.invoice("MENA-QRY", "IC-QRY-HQ-01", "IC-HQ", [("AGG-DUST06", 300, 34000), ("AGG-1014", 120, 37000),
                                                     ("AGG-1425", 100, 34000), ("QRY-GRB-RR", 50, 210000),
                                                     ("QRY-GRB-SG", 30, 240000)], D2, due="2026-12-02")
    # HQ receives the goods into Namasuba main store (ad-hoc receipt, Dr stock / Cr GRNI; the mirrored bill clears GRNI)
    F.purchase("MENA", "HQ-PO-IC-QRY-01", "IC-QRY", [("RM-DUST", 300, 34000), ("RM-AGG1014", 120, 37000),
                                                     ("RM-AGG1425", 100, 34000), ("RM-GRB-RR", 50, 210000),
                                                     ("RM-GRB-SG", 30, 240000)], "HQ-MAIN", D2, bill=False)
    # HQ sells slabs, skirting and blocks to the showroom company.
    F.invoice("MENA", "IC-HQ-SHOW-01", "IC-SHOW", [("GRN-RR", 40, 99000), ("GRN-SG", 20, 135000),
                                                   ("GRN-GSB", 25, 148000), ("FIN-SKIRT", 60, 28800),
                                                   ("BLK-6", 2000, 2070)], D2, due="2026-12-02")
    F.purchase("MENA-SHOW", "SHOW-PO-IC-HQ-01", "IC-HQ", [("GRN-RR", 40, 99000), ("GRN-SG", 20, 135000),
                                                         ("GRN-GSB", 25, 148000), ("FIN-SKIRT", 60, 28800),
                                                         ("BLK-6", 2000, 2070)], "SHW-KBL", D2, bill=False)
    # Shared services: HQ recharges management fees (finance, HR, IT) - posts in both books at once.
    F.ic_journal("MENA", "IC-MGMT-QRY-OCT", "MENA-QRY", D1, "Group management fee October 2026", 12000000, "4300",
                 "6170")
    F.ic_journal("MENA", "IC-MGMT-SHOW-OCT", "MENA-SHOW", D1, "Group management fee October 2026", 3500000, "4300",
                 "6170")
    # Settlements of opening intercompany balances
    F.supplier_payment("MENA", "HQ-SPAY-IC-QRY-OPEN", "IC-QRY", 30000000, D2)
    F.receipt("MENA-QRY", "QRY-RCPT-IC-HQ-OPEN", "IC-HQ", 30000000, D2)
    F.supplier_payment("MENA-SHOW", "SHOW-SPAY-IC-HQ-OPEN", "IC-HQ", 15000000, D2, pay_acc="1220")
    F.receipt("MENA", "HQ-RCPT-IC-SHOW-OPEN", "IC-SHOW", 15000000, D2)


def hq():
    o = "MENA"
    F.purchase(o, "HQ-PO-CEMENT-01", "S-HIMA", [("RM-CEM", 1200, 36000)], "HQ-MAIN", D1,
               receive={"RM-CEM": 1195}, pay=25000000)
    F.purchase(o, "HQ-PO-SAND-01", "S-SAND", [("RM-SAND", 160, 41000)], "HQ-MAIN", D1, pay=6560000)
    F.purchase(o, "HQ-PO-ABRAS-01", "S-ABRAS", [("RM-ABRAS", 20, 86000), ("RM-RESIN", 100, 37500)], "HQ-MAIN", D1)
    # Imported slabs with landed cost (freight, clearing, duty) allocated by value
    F.purchase(o, "HQ-PO-IMPORT-VW-01", "S-IMPORT", [("GRN-VW", 160, 128000), ("GRN-WG", 60, 86000)], "HQ-MAIN",
               D1, landed=[("freight", "Sea + road freight Mombasa-Kampala", 6800000),
                           ("duty", "Import duty & levies", 5900000),
                           ("handling", "Clearing & forwarding", 2300000)])
    # Production
    F.produce(o, "HQ-MO-BLK6-01", "Hollow block 6in (per 1,000)", 10000, "HQ-MAIN", "HQ-MAIN", produced=9850,
              scrap=150)
    F.produce(o, "HQ-MO-BLK8-01", "Hollow block 8in (per 1,000)", 8000, "HQ-MAIN", "HQ-MAIN")
    F.produce(o, "HQ-MO-BLK4-01", "Hollow block 4in (per 1,000)", 5000, "HQ-MAIN", "HQ-MAIN")
    F.produce(o, "HQ-MO-SLAB-RR-01", "Saw & polish Royal Red slab (per 90 m2)", 270, "HQ-MAIN", "HQ-MAIN",
              produced=262, scrap=8)
    F.produce(o, "HQ-MO-SLAB-SG-01", "Saw & polish Spotted Green slab (per 90 m2)", 180, "HQ-MAIN", "HQ-MAIN")
    F.produce(o, "HQ-MO-SKIRT-01", "Granite skirting from offcuts (per 100 rm)", 200, "HQ-MAIN", "HQ-MAIN")
    # Stock to the Ntinda showroom branch
    F.once(o, "HQ-TRF-NTD-01", lambda: transfer(o, "HQ-MAIN", "HQ-NTD",
                                                [("GRN-RR", 60), ("GRN-SG", 30), ("GRN-VW", 40), ("FIN-SKIRT", 80)]))
    # Sales: block orders delivered by trip, Canan mixed order, Mr Paul granite job, walk-in cash.
    F.sales_order(o, "HQ-SO-ZERI-01", "C-ZERI", [("BLK-8", 2000, 2800), ("SVC-TRANS", 1, 90000)], "HQ-MAIN", D2,
                  transporter="S-TIPPER", rate=60000, pay=5690000)
    F.sales_order(o, "HQ-SO-FIDEL-01", "C-FIDEL", [("BLK-8", 3000, 2800), ("BLK-4", 700, 1800)], "HQ-MAIN", D2,
                  transporter="S-TIPPER", rate=120000)
    F.sales_order(o, "HQ-SO-CANAN-01", "C-CANAN", [("RM-AGG1425", 40, 48000), ("BLK-8", 1500, 2750),
                                                   ("BLK-6", 1000, 2250)], "HQ-MAIN", D2, transporter="S-TIPPER",
                  rate=180000)
    F.sales_order(o, "HQ-SO-PAUL-01", "C-PAUL", [("GRN-GSB", 67, 165000), ("FIN-MOLD", 53, 12000)], "HQ-MAIN", D2,
                  transporter="S-TIPPER", rate=90000, pay=6000000)
    F.sales_order(o, "HQ-SO-SHAY-01", "C-SHAY", [("BLK-8", 2000, 2700), ("BLK-4", 200, 1800)], "HQ-MAIN", D2,
                  transporter="S-TIPPER", rate=90000, pay=5760000)
    for i, (lines, acct) in enumerate([([("BLK-6", 1000, 2300)], "1100"), ([("BLK-4", 350, 1800)], "1100"),
                                       ([("BLK-8", 400, 2800), ("SVC-OFFLOAD", 1, 40000)], "MOMO-MTN"),
                                       ([("GRN-RR", 6.5, 115000), ("FIN-MOLD", 6.5, 12000)], "1100"),
                                       ([("GRN-DF", 5.65, 120000)], "1100"), ([("GRN-SBB", 11.2, 125000)], "1210")]):
        F.invoice(o, "HQ-CASH-%02d" % (i + 1), "C-WALKIN", lines, D2, pay=True, pay_acc=acct,
                  method={"1100": "cash", "1210": "bank"}.get(acct, "mobile_money"))
    # Return: 50 damaged 8in blocks from Shay -> credit note (stock back), refund against the invoice balance
    F.once(o, "HQ-CN-SHAY-01", lambda: credit_note(o, "C-SHAY", [("BLK-8", 50, 2700)], D2, "HQ-SO-SHAY-01"))
    # Collections
    F.receipt(o, "HQ-RCPT-CANAN-OPEN", "C-CANAN", 18000000, D2)
    F.receipt(o, "HQ-RCPT-ZERI-OPEN", "C-ZERI", 9600000, D2)
    F.supplier_payment(o, "HQ-SPAY-HIMA-OPEN", "S-HIMA", 21900000, D2)
    # Expenses and accruals
    F.expense(o, "HQ-EXP-RENT-NOV", "Namasuba yard & factory rent - November", 6500000, D1, "6120", "S-LAND")
    F.expense(o, "HQ-EXP-UMEME-OCT", "Umeme - factory power October", 9200000, D1, "6110", "S-UMEME")
    F.expense(o, "HQ-EXP-MKT-01", "Showroom banners & radio adverts", 2800000, D1, "6190", pay_acc="1100",
              method="cash")
    F.expense(o, "HQ-EXP-TRANS-01", "Hired tipper - block deliveries", 1850000, D2, "6140", pay_acc="1100",
              method="cash")
    F.journal(o, "HQ-JE-PAYROLL-OCT", D1, "HQ payroll October 2026 (accrued)",
              [("5710", 46800000, 0), ("2180", 0, 39300000), ("2210", 0, 5300000), ("2230", 0, 2200000)],
              cost_center="Administration")
    F.journal(o, "HQ-JE-DEPR-OCT", D1, "Depreciation October 2026 (plant, vehicles, buildings)",
              [("5600", 18900000, 0), ("1490", 0, 18900000)])
    F.journal(o, "HQ-JE-LOANINT-OCT", D1, "Equipment loan interest October",
              [("6200", 10000000, 0), ("1210", 0, 10000000)])


def showroom():
    o = "MENA-SHOW"
    sales = [("C-KARIM", [("GRN-RR", 32.6, 125000)], "1220", "bank"),
             ("C-LOYAL", [("GRN-RR", 36.5, 125000)], "1100", "cash"),
             ("C-MICH", [("GRN-SG", 7.36, 170000), ("GRN-VW", 0.95, 295000), ("FIN-SKIRT", 0.72, 36000),
                         ("FIN-MOLD", 20.15, 13000)], "1100", "cash"),
             ("C-LUZIGE", [("GRN-VW", 0.9, 295000)], "MOMO-MTN", "mobile_money"),
             ("C-WALKIN", [("GRN-DF", 1.6, 140000), ("FIN-MOLD", 1.5, 13000)], "1100", "cash"),
             ("C-WALKIN", [("BLK-6", 500, 2500), ("SVC-TRANS", 1, 100000)], "1100", "cash"),
             ("C-WALKIN", [("GRN-GSB", 3.7, 185000), ("FIN-SKIRT", 0.37, 36000)], "MOMO-MTN", "mobile_money")]
    for i, (c, lines, acct, m) in enumerate(sales):
        F.invoice(o, "SHOW-CASH-%02d" % (i + 1), c, lines, D2, pay=True, pay_acc=acct, method=m)
    # Credit sale: Siimba (contractor) Viscount White + skirting, 15 days
    F.invoice(o, "SHOW-INV-SIIMBA-01", "C-SIIMBA", [("GRN-VW", 12, 290000), ("FIN-SKIRT", 8, 36000)], D2,
              due="2026-11-17")
    F.invoice(o, "SHOW-INV-WARA-01", "C-WARA", [("GRN-SG", 2.49, 165000)], D2, due="2026-11-09", pay=200000,
              pay_acc="1100")
    F.receipt(o, "SHOW-RCPT-MAPUK-OPEN", "C-MAPUK", 4000000, D2, pay_acc="1220")
    F.expense(o, "SHOW-EXP-RENT-NOV", "Kabalagala showroom rent - November", 3800000, D1, "6120", "S-LAND",
              pay_acc="1220")
    F.expense(o, "SHOW-EXP-UMEME-OCT", "Umeme - showroom October", 650000, D1, "6110", "S-UMEME", pay_acc="1100",
              method="cash")
    F.journal(o, "SHOW-JE-PAYROLL-OCT", D1, "Showroom payroll October 2026 (accrued)",
              [("5710", 7400000, 0), ("2180", 0, 6500000), ("2210", 0, 600000), ("2230", 0, 300000)],
              cost_center="Showroom Sales")


def quotations():
    """Open quotation (from the Canan Developers sheet layout) waiting for the customer: draft -> submit -> send."""
    import seed as S
    o = "MENA-QRY"
    lines = [("AGG-CRR", 40, 40000), ("AGG-DUST06", 40, 38000), ("AGG-DUST08", 40, 36000), ("AGG-0610", 40, 40000),
             ("AGG-1014", 40, 42000), ("AGG-1420", 40, 41000), ("AGG-1425", 40, 38000), ("AGG-HARD", 40, 36000),
             ("SVC-TRANS", 8, 850000)]

    def run():
        a, p = S.api(o), S.st(o, "product")
        q = a.post("/sales/quotations", {
            "partnerId": S.st(o, "partner")["C-SARAJ"], "validUntil": "2026-11-30",
            "notes": "Supply of aggregates, Mutungo site. Prices in UGX; full payment with order.",
            "lines": [{"productId": p[c], "description": c, "quantity": n, "unitPrice": u} for c, n, u in lines]})
        a.post("/sales/quotations/%s/submit" % q["id"], {})
        a.post("/sales/quotations/%s/send" % q["id"], {})
        return q["id"]
    F.once(o, "QRY-QT-SARAJ-01", run)


# ------------------------------------------------------------------------------------------------ helpers

def transfer(org, src, dst, items):
    import seed as S
    a = S.api(org)
    l, p, uid = S.st(org, "location"), S.st(org, "product"), S.STATE[org]["userId"]
    t = a.post("/inventory/transfers", {"fromLocationId": l[src], "toLocationId": l[dst], "responsibleById": uid,
                                        "approvedById": uid, "notes": "Restock Ntinda showroom",
                                        "items": [{"productId": p[c], "qtyRequested": q} for c, q in items]})
    tid = t["id"]
    for step in ("approve", "dispatch", "receive"):
        try:
            a.post("/inventory/transfers/%s/%s" % (tid, step), {})
        except S.ApiError as e:
            print("   (transfer %s: %s)" % (step, str(e)[:200]))
    return tid


def credit_note(org, customer, lines, date, so_key):
    import seed as S
    a = S.api(org)
    p = S.st(org, "product")
    inv = S.STATE[org]["steps"][so_key]["invoice"]
    cn = a.post("/credit-notes", {"partnerId": S.st(org, "partner")[customer], "issueDate": date,
                                  "reference": "Damaged blocks returned", "reversedDocumentId": inv,
                                  "lines": [{"productId": p[c], "description": "Return: " + c, "quantity": q,
                                             "unitPrice": u} for c, q, u in lines]})
    a.post("/credit-notes/%s/post" % cn["id"], {})
    return cn["id"]


ALL = [masters_production, quarry, intercompany, hq, showroom]

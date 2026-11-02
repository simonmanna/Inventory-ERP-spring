"""CRM demo story per company: sales reps, teams, tags, leads, a pipeline across every stage (won and lost
included), quotations linked to opportunities, planned / logged activities and customer service cases.

Everything goes through the CRM API (stage history, audit, events). Record timestamps are the run time; due dates
and expected close dates spread around go-live week so the planner shows overdue, today and upcoming work."""
from api import rows
import flows as F
import seed as S

# Demo-only sales reps (local database), same password as the admins.
REPS = {
    "MENA": [("sarah.nakato", "Sarah", "Nakato"), ("brian.okello", "Brian", "Okello"),
             ("grace.atim", "Grace", "Atim")],
    "MENA-QRY": [("daniel.mugisha", "Daniel", "Mugisha"), ("esther.namuli", "Esther", "Namuli")],
    "MENA-SHOW": [("ivan.ssekandi", "Ivan", "Ssekandi"), ("joan.akello", "Joan", "Akello")],
}
SALES_ROLE = "Sales Representative"
SALES_PERMISSIONS = ["crm:activity:read", "crm:activity:write", "crm:case:read", "crm:case:write",
                     "crm:dashboard:read", "crm:deal:read", "crm:deal:write", "crm:lead:read", "crm:lead:write",
                     "crm:pricing:read", "crm:quotation:read", "crm:quotation:write", "crm:report:read",
                     "sales:quotation:read", "sales:quotation:write", "sales:quotation:send", "sales:order:read",
                     "partners.view", "partners.create", "partners.edit", "products.view"]
DOMAIN = {"MENA": "menagranite.test", "MENA-QRY": "menaquarries.test", "MENA-SHOW": "menashowroom.test"}

# name, colour, leader rep (index), member reps, monthly target (UGX)
TEAMS = {
    "MENA": [("Projects & Contractors", "indigo", 0, [0, 1], 450000000),
             ("Ntinda Showroom Sales", "teal", 2, [2], 120000000)],
    "MENA-QRY": [("Aggregates Key Accounts", "amber", 0, [0, 1], 600000000)],
    "MENA-SHOW": [("Kabalagala Retail", "rose", 0, [0, 1], 90000000)],
}

TAGS = [("Road project", "orange"), ("Government tender", "blue"), ("Residential", "lime"),
        ("Commercial", "violet"), ("Repeat customer", "emerald"), ("High volume", "amber"), ("Urgent", "rose")]

# Leads (no customer yet): name, company, contact, email, phone, city, source, rep, priority, tags, notes
LEADS = {
    "MENA": [
        ("Kira Municipal offices - granite flooring", "Kira Municipal Council", "Eng. Robert Kato",
         "procurement@kiramc.test", "+256 700 100 201", "Kira", "Website", 0, 2, ["Government tender", "Commercial"],
         "Tender notice: 1,800 m2 polished granite for new municipal offices. Bid closes 20 Nov."),
        ("Mukono apartments - kitchen tops", "Pearl Heights Developers", "Aisha Nantongo",
         "aisha@pearlheights.test", "+256 701 100 202", "Mukono", "Referral", 1, 1, ["Residential"],
         "48 apartments, Galaxy-style black tops wanted. Referred by Canan Developers."),
        ("Entebbe hotel lobby refurbishment", "Lakeview Resort Entebbe", "Peter Ochieng",
         "peter.o@lakeviewresort.test", "+256 702 100 203", "Entebbe", "Trade show", 0, 2, ["Commercial"],
         "Met at the Kampala Build Expo. Wants Viscon White samples."),
        ("Bweyogerere warehouse - hollow blocks", None, "Moses Wasswa", None, "+256 703 100 204", "Bweyogerere",
         "Phone", 1, 0, ["High volume"], "Asked for price of 25,000 x 6in blocks delivered."),
        ("Najjera villa - staircase & skirting", None, "Dr. Ruth Nabirye", "ruth.nabirye@mail.test",
         "+256 704 100 205", "Najjera", "Walk-in", 2, 1, ["Residential"], "Visited Ntinda showroom, liked Royal Red."),
        ("Gulu school block - flooring", "St. Joseph's College Layibi", "Br. Thomas Okot", "bursar@layibi.test",
         "+256 705 100 206", "Gulu", "Email", 0, 0, ["Commercial"], "Budget approval expected in December."),
    ],
    "MENA-QRY": [
        ("Kampala-Jinja expressway lot 2 - aggregates", "China Road & Bridge Corp. (UG)", "Li Wei",
         "liwei.procure@crbc-ug.test", "+256 706 200 301", "Mukono", "Campaign", 0, 3,
         ["Road project", "Government tender", "High volume"], "Potential 40,000 t of 14-25mm + CRR over 8 months."),
        ("Nakifuma ready-mix plant", "Mukono ReadyMix Ltd", "Hassan Kiggundu", "hassan@mukonoreadymix.test",
         "+256 707 200 302", "Nakifuma", "Referral", 1, 2, ["High volume"], "New batching plant, needs 10-14mm weekly."),
        ("Kayunga district roads - hardcore", "Kayunga District Local Govt.", "Eng. Sam Byaruhanga",
         "works@kayunga.test", "+256 708 200 303", "Kayunga", "Website", 0, 1, ["Road project", "Government tender"],
         "Murram roads upgrade, hardcore and CRR."),
        ("Seeta estate - stone dust", None, "Joseph Lubega", None, "+256 709 200 304", "Seeta", "Phone", 1, 0,
         ["Residential"], "Paving contractor, asks for 0-6mm dust price per trip."),
    ],
    "MENA-SHOW": [
        ("Kabalagala restaurant - bar counter", "Cafe Javas Kabalagala", "Mercy Achieng", "mercy@cafejavas.test",
         "+256 710 300 401", "Kabalagala", "Walk-in", 0, 1, ["Commercial"], "Wants 6 m bar counter in Spotted Green."),
        ("Muyenga home - kitchen & bathrooms", None, "Mr & Mrs Kintu", "kintu.family@mail.test",
         "+256 711 300 402", "Muyenga", "Social media", 1, 1, ["Residential"], "Instagram enquiry, sent photos of RR."),
        ("Ggaba church - altar & steps", "Ggaba Parish", "Fr. John Mukasa", None, "+256 712 300 403", "Ggaba",
         "Referral", 0, 0, ["Commercial"], "Parish committee meeting next week."),
    ],
}

# Opportunities on existing customers:
# key, name, partner, stage name | won | lost, amount, close date, rep, priority, tags, notes, lost reason
OPPS = {
    "MENA": [
        ("canan-ph2", "Canan Developers - Kololo phase 2 slabs", "C-CANAN", "Negotiation", 186000000, "2026-11-25",
         0, 3, ["Repeat customer", "Residential"], "1,100 m2 Galaxy + Royal Red, pricing per m2 under discussion.", None),
        ("zeri-blocks", "Zerigaber - 60k hollow blocks Q4", "C-ZERI", "Proposition", 156000000, "2026-12-05", 1, 2,
         ["High volume", "Commercial"], "Monthly call-off of 15,000 x 8in blocks.", None),
        ("fidel-showroom", "Fidel Establishment - office tower lobby", "C-FIDEL", "Qualified", 92000000,
         "2026-12-15", 0, 1, ["Commercial"], "Viscon White lobby + Desert Fire stairs.", None),
        ("paul-villa", "Mr Paul - Kololo villa finishes", "C-PAUL", "won", 48500000, "2026-11-01", 2, 2,
         ["Residential"], "Accepted RR + skirting package; delivery from Ntinda.", None),
        ("shay-blocks", "Shay Builders - Namugongo site blocks", "C-SHAY", "New", 21000000, "2026-11-30", 1, 0,
         ["Residential"], "First enquiry, 6in blocks + transport.", None),
        ("ophir-kitchens", "Ophir Contractors - 24 kitchen tops", "C-OPHIR", "lost", 38000000, "2026-10-30", 2, 1,
         ["Residential"], "Lost on price to imported quartz.", "Too expensive"),
        ("mawa-retail", "Mawa Interiors - quarterly granite supply", "C-MAWA", "Negotiation", 64000000,
         "2026-11-18", 2, 2, ["Repeat customer"], "Framework agreement, 10 % volume discount requested.", None),
    ],
    "MENA-QRY": [
        ("acme-q1", "ACME Construction - Q1 2027 aggregates contract", "C-ACME", "Negotiation", 410000000,
         "2026-12-10", 0, 3, ["High volume", "Repeat customer"], "8,000 t 14-25mm + 2,500 t 10-14mm.", None),
        ("spear-road", "Spear On Road - Matugga road CRR", "C-SPEAR", "Proposition", 128000000, "2026-11-28", 1, 2,
         ["Road project"], "3,200 t CRR delivered, own trucks partly.", None),
        ("texa-hardcore", "Texa Solution - Kira site hardcore", "C-TEXA", "won", 29000000, "2026-11-01", 1, 1,
         [], "Signed PO for 800 t hardcore.", None),
        ("metaz-dust", "Metazone - paving stone dust", "C-METAZ", "Qualified", 34000000, "2026-12-01", 0, 1,
         ["Commercial"], "Paving yard expansion, 0-6mm dust.", None),
        ("saraj-mutungo", "Sarajan - Mutungo site full aggregates", "C-SARAJ", "Proposition", 15300000,
         "2026-11-30", 0, 2, ["Repeat customer"], "Quotation sent; waiting for site manager approval.", None),
        ("kasule-bridge", "Eng. Kasule - culvert & bridge works", "C-KASULE", "lost", 22000000, "2026-10-29", 1, 0,
         ["Road project"], "Client chose a quarry closer to site (Lugazi).", "Chose a competitor"),
    ],
    "MENA-SHOW": [
        ("siimba-office", "Siimba Company - office reception", "C-SIIMBA", "Proposition", 27500000, "2026-11-22",
         0, 2, ["Commercial"], "Reception desk + flooring 80 m2 Spotted Green.", None),
        ("mapuk-units", "Mapuk Developers - 12 townhouses", "C-MAPUK", "Negotiation", 72000000, "2026-12-05", 1, 3,
         ["Residential", "High volume"], "Kitchen tops, window sills, stairs for 12 units.", None),
        ("wara-steps", "Wara Construction - school steps", "C-WARA", "won", 9800000, "2026-11-01", 1, 1,
         [], "Paid deposit; cutting scheduled.", None),
        ("luzige-tiles", "Luzige Interiors - showroom display", "C-LUZIGE", "Qualified", 14500000, "2026-11-29",
         0, 1, ["Commercial"], "Display panels of 5 granite types.", None),
        ("karim-kitchen", "Karim - home kitchen top", "C-KARIM", "lost", 4200000, "2026-10-31", 0, 0,
         ["Residential"], "Postponed until next year.", "Timing not right"),
    ],
}

# Activities: opp key | partner code | None, type, title, due (UTC), rep, done outcome or None
ACTIVITIES = {
    "MENA": [
        ("canan-ph2", "meeting", "Site visit Kololo phase 2 - measure stairs", "2026-11-03T07:00:00Z", 0, None),
        ("canan-ph2", "call", "Agree final m2 price with Canan MD", "2026-10-31T09:00:00Z", 0,
         "Asked for 4 % off Galaxy; will revert after approval."),
        ("zeri-blocks", "email", "Send block test certificates (UNBS)", "2026-11-02T08:00:00Z", 1, None),
        ("zeri-blocks", "task", "Prepare call-off delivery schedule", "2026-11-06T06:00:00Z", 1, None),
        ("fidel-showroom", "meeting", "Present samples at Fidel head office", "2026-11-05T08:30:00Z", 0, None),
        ("paul-villa", "call", "Confirm delivery date with Mr Paul", "2026-11-01T10:00:00Z", 2,
         "Delivery confirmed for 4 Nov, site access from 8am."),
        ("shay-blocks", "call", "Qualify Shay Builders volume and payment terms", "2026-10-30T07:00:00Z", 1, None),
        ("mawa-retail", "meeting", "Negotiate framework discount", "2026-11-04T11:00:00Z", 2, None),
        ("ophir-kitchens", "note", "Ophir chose quartz at UGX 1.2m per top", None, 2, None),
    ],
    "MENA-QRY": [
        ("acme-q1", "meeting", "Contract review with ACME procurement", "2026-11-04T07:30:00Z", 0, None),
        ("acme-q1", "task", "Draft volume rebate schedule", "2026-11-03T06:00:00Z", 0, None),
        ("spear-road", "call", "Follow up CRR quotation", "2026-10-31T08:00:00Z", 1,
         "Spear wants trial 200 t before signing."),
        ("spear-road", "task", "Book trucks for 200 t trial delivery", "2026-11-03T05:00:00Z", 1, None),
        ("metaz-dust", "email", "Send dust gradation report", "2026-11-01T09:00:00Z", 0, None),
        ("saraj-mutungo", "call", "Chase site manager on quotation", "2026-11-05T07:00:00Z", 0, None),
        ("texa-hardcore", "meeting", "Kick-off: delivery windows at Kira site", "2026-11-02T06:00:00Z", 1,
         "Deliveries Mon-Sat 7am-5pm, max 5 trips/day."),
    ],
    "MENA-SHOW": [
        ("siimba-office", "meeting", "Measure reception area at Siimba", "2026-11-03T08:00:00Z", 0, None),
        ("mapuk-units", "call", "Negotiate unit price for 12 townhouses", "2026-11-04T09:00:00Z", 1, None),
        ("mapuk-units", "email", "Send revised drawings and quotation", "2026-10-31T12:00:00Z", 1,
         "Sent; Mapuk to confirm sills width."),
        ("wara-steps", "task", "Schedule cutting & polishing slot", "2026-11-02T06:30:00Z", 1, None),
        ("luzige-tiles", "call", "Agree display panel sizes", "2026-11-06T10:00:00Z", 0, None),
    ],
}

# Work for the company admin (the demo sign-in), so "My activities" is not empty: appended, indexes stay stable.
ADMIN = None
ACTIVITIES["MENA"] += [
    ("canan-ph2", "task", "Approve 4 % discount request for Canan", "2026-11-02T12:00:00Z", ADMIN, None),
    ("zeri-blocks", "meeting", "Weekly pipeline review with sales team", "2026-11-03T05:30:00Z", ADMIN, None),
    ("shay-blocks", "call", "Call Shay Builders director", "2026-10-31T07:00:00Z", ADMIN, None)]
ACTIVITIES["MENA-QRY"] += [
    ("acme-q1", "task", "Sign off ACME rebate schedule", "2026-11-02T13:00:00Z", ADMIN, None),
    ("kasule-bridge", "call", "Win-back call to Eng. Kasule", "2026-10-30T08:00:00Z", ADMIN, None),
    ("spear-road", "meeting", "Visit Matugga road site", "2026-11-07T06:00:00Z", ADMIN, None)]
ACTIVITIES["MENA-SHOW"] += [
    ("mapuk-units", "task", "Approve Mapuk townhouse pricing", "2026-11-02T11:00:00Z", ADMIN, None),
    ("karim-kitchen", "call", "Check back with Karim in January", "2027-01-12T07:00:00Z", ADMIN, None),
    ("siimba-office", "email", "Send Siimba sample photos", "2026-10-31T09:00:00Z", ADMIN, None)]

# Opportunities the admin (sales manager in the demo) owns, so the default "My pipeline" view has cards.
ADMIN_OWNS = {"MENA": ["canan-ph2", "zeri-blocks", "paul-villa", "fidel-showroom"],
              "MENA-QRY": ["acme-q1", "metaz-dust", "texa-hardcore"],
              "MENA-SHOW": ["mapuk-units", "luzige-tiles", "wara-steps"]}

# Cases: key, partner, type, category, priority, subject, description, rep, status path [(status, note)], notes
CASES = {
    "MENA": [
        ("c-canan-chip", "C-CANAN", "complaint", "quality", "high", "Chipped edges on 6 Royal Red slabs",
         "Delivery of 30 Oct: 6 slabs chipped at the edges, client wants replacement.", 0,
         [("in_progress", "Inspection booked for 3 Nov.")], ["Photos received from site engineer."]),
        ("c-zeri-late", "C-ZERI", "complaint", "delivery", "normal", "Block delivery arrived 2 days late",
         "Truck breakdown on Entebbe road.", 1,
         [("in_progress", None), ("resolved", "Apologised; next 2 deliveries free transport.")], []),
        ("c-paul-fix", "C-PAUL", "service_request", "service", "normal", "Install skirting at Kololo villa",
         "Client asks for our fitting team for 120 rm skirting.", 2, [], []),
    ],
    "MENA-QRY": [
        ("c-acme-grade", "C-ACME", "complaint", "quality", "urgent", "14-25mm batch out of grading spec",
         "Lab test shows 12 % oversize in delivery DN of 2 Nov.", 0,
         [("in_progress", "Screen mesh checked; resampling stockpile.")], ["Lab report attached by ACME QA."]),
        ("c-spear-inv", "C-SPEAR", "complaint", "billing", "normal", "Transport charged twice on invoice",
         "Customer says trip charge appears on two invoices.", 1,
         [("in_progress", None), ("resolved", "Duplicate trip line reversed; credit note issued."),
          ("closed", None)], []),
    ],
    "MENA-SHOW": [
        ("c-loyal-stain", "C-LOYAL", "complaint", "quality", "normal", "Stain on polished Spotted Green top",
         "Oil stain after one week; asks how to clean.", 0, [], []),
        ("c-mich-cut", "C-MICH", "service_request", "service", "low", "Cut sink hole in existing top",
         "Customer brings the top to the showroom.", 1, [("in_progress", "Booked for Thursday.")], []),
    ],
}

# Quotations linked to opportunities: opp key, valid until, lines [(product code, qty, unit price)]
QUOTES = {
    "MENA": [("zeri-blocks", "2026-11-30", [("BLK-8", 50000, 2800), ("BLK-6", 10000, 2300), ("SVC-TRANS", 20, 90000)])],
    "MENA-QRY": [("spear-road", "2026-11-30", [("AGG-CRR", 3200, 40000)])],
    "MENA-SHOW": [("siimba-office", "2026-11-30", [("GRN-SG", 80, 170000), ("FIN-SKIRT", 60, 36000)])],
}


def _crm(org):
    return S.STATE[org].setdefault("crm", {})


def _reps(org):
    """Sales reps: created once with the org's sales role (falls back to any non-admin role)."""
    a, c = S.api(org), _crm(org)
    if len(c.get("reps", [])) == len(REPS[org]):
        return c["reps"]
    roles = rows(a.get("/roles"))
    role = next((r for r in roles if r["name"] == SALES_ROLE), None) \
        or a.post("/roles", {"name": SALES_ROLE, "description": "CRM pipeline, quotations and customer cases",
                             "permissions": SALES_PERMISSIONS})
    existing = {u["email"]: u["id"] for u in rows(a.get("/users", limit=200))}
    out = []
    for login, first, last in REPS[org]:
        email = "%s@%s" % (login, DOMAIN[org])
        uid = existing.get(email) or a.post("/users", {"email": email, "password": S.D.PASSWORD, "firstName": first,
                                                        "lastName": last, "roleIds": [role["id"]]})["id"]
        out.append(uid)
    c["reps"] = out
    S.save()
    print("  ok", org, "reps (%s)" % role["name"])
    return out


def _config(org, reps):
    a, c = S.api(org), _crm(org)
    look = a.get("/crm/config/lookups")
    if "tags" not in c:
        have = {t["name"]: t["id"] for t in look.get("tags", [])}
        c["tags"] = {n: have.get(n) or a.post("/crm/config/tags", {"name": n, "color": col})["id"] for n, col in TAGS}
    if "teams" not in c:
        have = {t["name"]: t["id"] for t in look.get("teams", [])}
        c["teams"] = {}
        for name, col, lead, members, target in TEAMS[org]:
            c["teams"][name] = have.get(name) or a.post("/crm/config/teams", {
                "name": name, "color": col, "leaderId": reps[lead], "memberIds": [reps[m] for m in members],
                "monthlyTarget": target})["id"]
    S.save()
    look = a.get("/crm/config/lookups")
    return ({s["name"]: s["id"] for s in look["stages"]}, {s["name"]: s["id"] for s in look["sources"]},
            {s["name"]: s["id"] for s in look["lostReasons"]})


def crm():
    for org in S.D.ORGS:
        print("==", org)
        a, c = S.api(org), _crm(org)
        reps = _reps(org)
        stages, sources, reasons = _config(org, reps)
        tags, par = c["tags"], S.st(org, "partner")
        deals = c.setdefault("deals", {})

        for name, company, contact, email, phone, city, src, rep, prio, tg, notes in LEADS[org]:
            if name not in deals:
                deals[name] = F.once(org, "CRM-LEAD-" + name, lambda: a.post("/crm/leads", {
                    "name": name, "companyName": company, "contactName": contact, "email": email, "phone": phone,
                    "city": city, "country": "Uganda", "sourceId": sources.get(src), "ownerId": reps[rep],
                    "priority": prio, "tagIds": [tags[t] for t in tg], "notes": notes})["id"])
        # Two leads qualified this week -> opportunities (one becomes a new customer record).
        first = LEADS[org][0][0]
        F.once(org, "CRM-CONVERT-" + first, lambda: a.post("/crm/leads/%s/convert" % deals[first], {
            "createCustomer": True, "stageId": stages["Qualified"]})["id"])

        for key, name, cust, stage, amount, close, rep, prio, tg, notes, reason in OPPS[org]:
            def make(name=name, cust=cust, stage=stage, amount=amount, close=close, rep=rep, prio=prio, tg=tg,
                     notes=notes, reason=reason):
                body = {"name": name, "partnerId": par[cust], "amount": amount, "expectedClose": close,
                        "ownerId": reps[rep], "priority": prio, "tagIds": [tags[t] for t in tg], "notes": notes}
                if stage in stages:
                    body["stageId"] = stages[stage]
                else:
                    body["stageId"] = stages["Negotiation"]
                d = a.post("/crm/opportunities", body)
                if stage == "won":
                    a.post("/crm/opportunities/%s/won" % d["id"], {})
                elif stage == "lost":
                    a.post("/crm/opportunities/%s/lost" % d["id"], {"lostReasonId": reasons.get(reason),
                                                                    "lostNote": notes})
                return d["id"]
            deals[key] = F.once(org, "CRM-OPP-" + key, make)

        for key in ADMIN_OWNS[org]:
            F.once(org, "CRM-OWN-" + key, lambda key=key: a.patch("/crm/opportunities/%s" % deals[key], {
                "ownerId": S.STATE[org]["userId"]})["id"])

        for i, (ref, typ, title, due, rep, outcome) in enumerate(ACTIVITIES[org]):
            def act(ref=ref, typ=typ, title=title, due=due, rep=rep, outcome=outcome):
                x = a.post("/crm/planner", {"type": typ, "title": title, "dealId": deals[ref], "dueAt": due,
                                            "assignedToId": S.STATE[org]["userId"] if rep is None else reps[rep], "body": None if typ != "note" else title})
                if outcome:
                    a.post("/crm/planner/%s/done" % x["id"], {"outcome": outcome})
                return x["id"]
            F.once(org, "CRM-ACT-%02d" % i, act)

        for key, cust, typ, cat, prio, subject, desc, rep, path, notes in CASES[org]:
            def case(cust=cust, typ=typ, cat=cat, prio=prio, subject=subject, desc=desc, rep=rep, path=path,
                     notes=notes):
                x = a.post("/crm/cases", {"partnerId": par[cust], "caseType": typ, "category": cat, "priority": prio,
                                          "subject": subject, "description": desc, "assigneeId": reps[rep]})
                for n in notes:
                    a.post("/crm/cases/%s/notes" % x["id"], {"body": n})
                for status, note in path:
                    a.post("/crm/cases/%s/status" % x["id"], {"status": status, "note": note})
                return x["id"]
            F.once(org, "CRM-CASE-" + key, case)

        p = S.st(org, "product")
        for ref, valid, lines in QUOTES[org]:
            def quote(ref=ref, valid=valid, lines=lines):
                deal = a.get("/crm/opportunities/%s" % deals[ref])
                q = a.post("/sales/quotations", {
                    "partnerId": deal["partnerId"], "dealId": deals[ref], "validUntil": valid,
                    "notes": "Prices in UGX, VAT inclusive. Delivery within Kampala metropolitan area.",
                    "lines": [{"productId": p[code], "description": code, "quantity": n, "unitPrice": u}
                              for code, n, u in lines]})
                a.post("/sales/quotations/%s/submit" % q["id"], {})
                try:
                    a.post("/sales/quotations/%s/send" % q["id"], {})
                except S.ApiError as e:
                    print("   (quotation send skipped: %s)" % str(e)[:160])
                return q["id"]
            F.once(org, "CRM-QT-" + ref, quote)
        S.save()

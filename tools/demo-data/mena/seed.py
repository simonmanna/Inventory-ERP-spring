"""MENA group demo seeder: drives the running API (real services, real GL postings) for three sister companies.

    python seed.py setup          # master data for all orgs (idempotent by code)
    python seed.py <phase> ...    # see PHASES at the bottom

State (ids by code) is kept in state.json beside this file so phases can run separately.
"""
import json
import os
import sys

from api import Api, ApiError, rows
import data as D

HERE = os.path.dirname(os.path.abspath(__file__))
STATE_FILE = os.path.join(HERE, "state.json")
STATE = json.load(open(STATE_FILE)) if os.path.exists(STATE_FILE) else {}


def save():
    json.dump(STATE, open(STATE_FILE, "w"), indent=1, sort_keys=True)


def st(org, kind):
    return STATE.setdefault(org, {}).setdefault(kind, {})


_apis = {}
TOKENS_FILE = os.path.join(HERE, ".tokens.json")


def login(email, org):
    """Login with a small on-disk token cache (access tokens live 15 min; the login route is rate limited)."""
    import time
    cache = json.load(open(TOKENS_FILE)) if os.path.exists(TOKENS_FILE) else {}
    k = org + "|" + email
    if k in cache and time.time() - cache[k]["at"] < 600:
        return cache[k]["res"]
    res = Api().post("/auth/login", {"organizationCode": org, "email": email, "password": D.PASSWORD})
    res = {"accessToken": res["accessToken"], "user": {"id": res.get("user", {}).get("id")}}
    cache[k] = {"at": time.time(), "res": res}
    json.dump(cache, open(TOKENS_FILE, "w"))
    return res


def api(org):
    if org not in _apis:
        a = Api()
        res = login(D.ORGS[org]["email"], org)
        a.who = D.ORGS[org]["email"] + "#2"
        a.token = res["accessToken"]
        STATE.setdefault(org, {})["userId"] = res.get("user", {}).get("id")
        u = res.get("user", {})
        STATE[org]["orgId"] = STATE[org].get("orgId") or u.get("organizationId")
        _apis[org] = a
    return _apis[org]


_checkers = {}


def checker(org):
    """The company's chief accountant: posts / approves what the admin (maker) created."""
    if org not in _checkers:
        a = Api()
        res = login(D.CHECKERS[org], org)
        a.who = D.CHECKERS[org] + "#2"
        a.token = res["accessToken"]
        STATE.setdefault(org, {})["checkerId"] = res.get("user", {}).get("id")
        _checkers[org] = a
    return _checkers[org]


def post_je(org, body):
    """Create a GL journal as the admin, post it as the accountant (maker-checker)."""
    je = api(org).post("/journal-entries", body)
    if je.get("status") != "posted":
        checker(org).post("/journal-entries/%s/post" % je["id"])
    return je


def by_code(items, code_field="code"):
    return {i[code_field]: i for i in items if i.get(code_field)}


def ensure(org, kind, code, list_path, create_path, body, code_field="code", params=None):
    """Create once; remember id. Looks the code up in the list first so re-runs never duplicate."""
    s = st(org, kind)
    if code in s:
        return s[code]
    a = api(org)
    existing = by_code(rows(a.get(list_path, pageSize=500, **(params or {}))), code_field)
    if code in existing:
        s[code] = existing[code]["id"]
    else:
        s[code] = a.post(create_path, body)["id"]
    save()
    return s[code]


# ---------------------------------------------------------------------------------------------------- setup

def setup_org(org):
    a = api(org)
    print("==", org)
    # Accounts
    cats = rows(a.get("/account-categories"))
    cat = {}
    for c in cats:
        cat[c.get("key") or c["name"].lower().replace(" ", "_").replace("-", "_")] = c["id"]
    accts = by_code(rows(a.get("/accounts", pageSize=500)))
    acc = st(org, "account")
    for code, x in accts.items():
        acc[code] = x["id"]
    for code, name, ckey, parent in D.ACCOUNTS_COMMON:
        if code not in acc:
            acc[code] = a.post("/accounts", {"code": code, "name": name, "categoryId": cat[ckey],
                                             "parentAccountId": acc.get(parent), "isPostable": True})["id"]
    save()
    # Bank accounts (treasury registers over the GL accounts)
    for code, name, bank in (("1210", "Stanbic Current", "Stanbic Bank Uganda"),
                             ("1220", "Centenary Collections", "Centenary Bank")):
        ensure(org, "bank", code, "/bank-accounts", "/bank-accounts",
               {"accountId": acc[code], "name": name, "bankName": bank}, code_field="name") \
            if False else None
    banks = by_code(rows(a.get("/bank-accounts")), "accountId")
    for code, name, bank in (("1210", "Stanbic Current", "Stanbic Bank Uganda"),
                             ("1220", "Centenary Collections", "Centenary Bank")):
        if acc[code] not in banks:
            a.post("/bank-accounts", {"accountId": acc[code], "name": name, "bankName": bank})
    # Branches
    br = st(org, "branch")
    existing = by_code(rows(a.get("/branches", pageSize=100)))
    for code, x in existing.items():
        br[code] = x["id"]
    for code, name in D.BRANCHES[org]:
        if code not in br:
            br[code] = a.post("/branches", {"code": code, "name": name, "timezone": "Africa/Kampala"})["id"]
    save()
    # Locations
    for code, name, bcode in D.LOCATIONS[org]:
        ensure(org, "location", code, "/inventory/locations", "/inventory/locations",
               {"code": code, "name": name, "type": "warehouse", "branchId": br[bcode]})
    # Cost centres
    cc = st(org, "costcenter")
    existing = {x["name"]: x["id"] for x in rows(a.get("/cost-centers", pageSize=200))}
    for i, name in enumerate(D.COST_CENTERS[org]):
        if name not in cc:
            cc[name] = existing.get(name) or a.post("/cost-centers", {"code": "CC%02d" % (i + 1), "name": name})["id"]
    save()
    # Payment terms
    for code, name, days in D.PAYMENT_TERMS:
        ensure(org, "term", code, "/payment-terms", "/payment-terms",
               {"code": code, "name": name, "netDays": days, "method": "net_days" if days else "immediate"})
    # Units
    uoms = by_code(rows(a.get("/uoms", pageSize=200)))
    u = st(org, "uom")
    for code, x in uoms.items():
        u[code] = x["id"]
    for code, name, sym, like in D.EXTRA_UOMS:
        if code not in u:
            base = uoms[like]
            u[code] = a.post("/uoms", {"code": code, "name": name, "symbol": sym, "categoryId": base.get("categoryId"),
                                       "factor": 1, "uomType": "bigger", "ratio": 1})["id"]
    save()
    # Product categories
    pc = st(org, "pcat")
    existing = {x["name"]: x["id"] for x in rows(a.get("/product-categories", pageSize=200))}
    for name, income in D.CATEGORIES[org].items():
        if name not in pc:
            pc[name] = existing.get(name) or a.post("/product-categories", {
                "name": name, "incomeAccountId": acc.get(income) if income else None})["id"]
    save()
    # Expense categories, each with the GL account its expenses debit
    ec = st(org, "expcat")
    existing = {x["name"]: x["id"] for x in rows(a.get("/expense-categories"))}
    for code, name in D.EXPENSE_CATEGORIES:
        if code not in ec:
            ec[code] = existing.get(name) or a.post("/expense-categories", {"name": name,
                                                                           "ledgerAccountId": acc[code]})["id"]
    save()
    # Partners
    for code, name, cust, supp, term, phone in D.PARTNERS[org]:
        ensure(org, "partner", code, "/partners", "/partners",
               {"code": code, "name": name, "isCompany": not code.startswith("C-WALKIN"), "isCustomer": cust,
                "isSupplier": supp, "phone": phone, "paymentTermId": st(org, "term")[term]})
    # Products
    for code, name, catname, uom, sale, cost, stocked in D.PRODUCTS[org]:
        ensure(org, "product", code, "/products", "/products",
               {"code": code, "name": name, "sku": code, "categoryId": pc[catname], "uomId": u[uom],
                "salesUomId": u[uom], "purchaseUomId": u[uom],
                "productType": "stockable" if stocked else "service", "trackInventory": stocked,
                "costingMethod": "AVCO", "salesPrice": sale, "costPrice": cost,
                "allowFractionalSale": uom not in ("UNIT", "BAG")})
    print("  accounts", len(acc), "partners", len(st(org, "partner")), "products", len(st(org, "product")))


def setup():
    for org in D.ORGS:
        setup_org(org)


def opening():
    import opening as O
    for org in D.ORGS:
        a = api(org)
        done = STATE[org].setdefault("done", [])
        uid = STATE[org]["userId"]
        loc, prod, acc, par = st(org, "location"), st(org, "product"), st(org, "account"), st(org, "partner")
        if "stock_in" not in done:
            by_loc = {}
            for code, l, qty, cost in O.STOCK[org]:
                by_loc.setdefault(l, []).append({"productId": prod[code], "quantity": qty, "unitCost": cost})
            for l, items in by_loc.items():
                a.post("/inventory/direct-stock/in", {"locationId": loc[l], "responsibleById": uid,
                                                      "approvedById": uid, "items": items,
                                                      "notes": "Opening stock at 31 Oct 2026 (go-live)"})
            done.append("stock_in")
            save()
        if "opening_balances" not in done:
            stock_value = sum(q * c for _, _, q, c in O.STOCK[org])
            lines = [{"accountId": acc[c], "debit": v if v > 0 else 0, "credit": -v if v < 0 else 0,
                      "description": "Opening balance"} for c, v in O.GL[org].items()]
            # Opening stock came in through direct stock-in (Dr stock, Cr GRNI): clear GRNI into opening equity.
            lines.append({"accountId": acc["2150"], "debit": stock_value, "credit": 0,
                          "description": "Clear GRNI from opening stock-in"})
            a.post("/opening-balances", {
                "date": O.OPENING_DATE, "reference": "GO-LIVE-2026-10-31", "accounts": lines,
                "customers": [{"partnerId": par[p], "amount": v, "dueDate": "2026-11-30",
                               "reference": "Opening AR " + p} for p, v in O.AR[org].items()],
                "suppliers": [{"partnerId": par[p], "amount": v, "dueDate": "2026-11-30",
                               "reference": "Opening AP " + p} for p, v in O.AP[org].items()]})
            done.append("opening_balances")
            save()
        if "reclass_4200" not in done:
            # Direct stock-in credits Stock Adjustment Income (4200), so move the opening stock value out of P&L into
            # the GRNI line the opening entry debited.
            stock_value = sum(q * c for _, _, q, c in O.STOCK[org])
            draft = [e for e in rows(a.get("/journal-entries", pageSize=50))
                     if e.get("description") == "Reclass opening stock-in from stock adjustment income"]
            if draft:
                checker(org).post("/journal-entries/%s/post" % draft[0]["id"])
            else:
                post_je(org, {"journalCode": "GEN", "date": "2026-11-01",
                              "description": "Reclass opening stock-in from stock adjustment income",
                              "lines": [{"accountId": acc["4200"], "debit": stock_value, "credit": 0},
                                        {"accountId": acc["2150"], "debit": 0, "credit": stock_value}]})
            done.append("reclass_4200")
            save()
        print(org, "opening done")
    # Intercompany: tell each org which partner stands for each sister company.
    ic = {"MENA": {"MENA-QRY": "IC-QRY", "MENA-SHOW": "IC-SHOW"}, "MENA-QRY": {"MENA": "IC-HQ"},
          "MENA-SHOW": {"MENA": "IC-HQ"}}
    for org, m in ic.items():
        for other, pcode in m.items():
            api(org).put("/intercompany/partners/" + STATE[other]["orgId"], {"partnerId": st(org, "partner")[pcode]})


PHASES = {"setup": setup, "opening": opening}

if __name__ == "__main__":
    for ph in sys.argv[1:] or ["setup"]:
        try:
            PHASES[ph]()
        except ApiError as e:
            print("FAILED in", ph, ":", e)
            raise SystemExit(1)

"""Branch cash-sale demo setup for one organisation, through the running API only (no SQL).

Usage:  CS_PASSWORD='<admin password>' python3 setup.py --confirm DEMO
Fills every branch sales profile of the organisation (Sales -> Branch Profiles) so a showroom can take cash, MTN / Airtel
mobile money and bank payments and sell to walk-in customers:

- tender accounts: cash = account CS_CASH_CODE (1100), MTN / Airtel / bank = the accounts of the "MTN Mobile Money",
  "Airtel Money" and "Bank" payment modes (falling back to account codes MOMO-MTN, MOMO-AIRTEL, 1200);
- walk-in customer: the partner named "Walk-in Customer" (created when missing).

Safe to re-run: only empty profile fields are filled (pass --overwrite to replace set ones); a tender whose account
cannot be found is left empty and reported. Back up the database first.
Environment: ERP_BASE (default http://127.0.0.1:3001/api/v1, localhost only), CS_ORG (DEMO), CS_EMAIL (admin@demo.test),
CS_PASSWORD (Admin@123, the local seed default), CS_CASH_CODE (1100).
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from urllib.parse import urlparse

BASE = os.environ.get("ERP_BASE", "http://127.0.0.1:3001/api/v1")
ORG = os.environ.get("CS_ORG", "DEMO")
EMAIL = os.environ.get("CS_EMAIL", "admin@demo.test")
PASSWORD = os.environ.get("CS_PASSWORD", "Admin@123")
CASH_CODE = os.environ.get("CS_CASH_CODE", "1100")
WALK_IN = "Walk-in Customer"
TOKEN = None

# profile field -> (payment-mode label, fallback account code)
TENDERS = {
    "momoMtnAccountId": ("MTN Mobile Money", "MOMO-MTN"),
    "momoAirtelAccountId": ("Airtel Money", "MOMO-AIRTEL"),
    "bankClearingAccountId": ("Bank", "1200"),
}


def log(msg):
    print(msg, flush=True)


def call(method, path, body=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                                 method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Idempotency-Key", str(uuid.uuid4()))
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=120) as res:
            txt = res.read().decode()
            return json.loads(txt) if txt else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} -> {e.code}: {e.read().decode()[:700]}")


def rows(data):
    return data if isinstance(data, list) else (data.get("data") or data.get("rows") or data.get("items") or [])


def account_by_code(code):
    found = rows(call("GET", "/accounts?search=" + urllib.parse.quote(code) + "&pageSize=100"))
    return next((a["id"] for a in found if a.get("code") == code and a.get("isActive", True)), None)


def walk_in_partner():
    found = rows(call("GET", "/partners?search=" + urllib.parse.quote(WALK_IN)))
    hit = next((p for p in found if p.get("name") == WALK_IN), None)
    if hit:
        return hit["id"]
    log(f"creating partner {WALK_IN!r}")
    return call("POST", "/partners", {"name": WALK_IN, "isCustomer": True})["id"]


def main():
    global TOKEN
    args = sys.argv[1:]
    if args[:2] != ["--confirm", ORG]:
        raise SystemExit(f"Refusing to run: pass --confirm {ORG} (and back up the database first)")
    overwrite = "--overwrite" in args
    host = urlparse(BASE).hostname
    if host not in ("127.0.0.1", "localhost"):
        raise SystemExit(f"Refusing to run against {host}: ERP_BASE must be localhost")
    TOKEN = call("POST", "/auth/login", {"organizationCode": ORG, "email": EMAIL, "password": PASSWORD})["accessToken"]

    modes = {m.get("label"): m for m in call("GET", "/sales/cash-sales/payment-modes")}
    accounts = {"cashAccountId": account_by_code(CASH_CODE)}
    for field, (label, code) in TENDERS.items():
        accounts[field] = (modes.get(label) or {}).get("accountId") or account_by_code(code)
    for field, acc in accounts.items():
        if not acc:
            log(f"! no account found for {field}; left empty")
    walk_in = walk_in_partner()

    profiles = call("GET", "/sales/branch-profiles")
    if not profiles:
        log("no branch sales profiles: create them under Sales -> Branch Profiles first")
    for p in profiles:
        patch = {}
        for field, acc in list(accounts.items()) + [("walkInPartnerId", walk_in)]:
            if acc and (overwrite or not p.get(field)) and p.get(field) != acc:
                patch[field] = acc
        if not patch:
            log(f"= {p.get('branchName')}: already set")
            continue
        call("PATCH", f"/sales/branch-profiles/{p['id']}", patch)
        log(f"+ {p.get('branchName')}: set {', '.join(sorted(patch))}")
    log("done")


if __name__ == "__main__":
    main()

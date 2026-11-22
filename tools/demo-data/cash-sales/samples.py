"""Sample branch cash sales for one organisation, through the running API only (no SQL).

Usage:  CS_PASSWORD='<admin password>' python3 samples.py --confirm DEMO
Run setup.py first (tender accounts + walk-in customer on the branch profiles). Back up the database first.
Every sale is recorded in state.<ORG>.json and not repeated on a re-run (delete that file only together with the data).
Environment: ERP_BASE (default http://127.0.0.1:3001/api/v1, localhost only), CS_ORG (DEMO), CS_EMAIL (admin@demo.test),
CS_PASSWORD (Admin@123, the local seed default).

What it makes (one sale per stage of the showroom -> head-office flow, products from the DEMO cafe catalogue):
  1. Head Office, Acme Retail: cash + MTN, delivered            -> approved & invoiced in one step (invoice paid)
  2. Kampala Road, walk-in: cash, collected                     -> approved & invoiced in one step
  3. Acacia Mall, Kato Hardware: bank transfer, scheduled       -> submitted (head office: "Approve & invoice")
  4. Entebbe, Nalubega Interiors: Airtel Money, dispatched      -> under review
  5. Head Office, walk-in: cash                                 -> confirmed, not yet submitted
  6. Kampala Road, Ssenyonga Builders: MTN captured             -> draft
  7. Acacia Mall, Ssenyonga Builders: cash                      -> returned for correction
  8. Entebbe, Acme Retail: cash + MTN                           -> approved only ("Generate invoice" pending)
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import date, timedelta
from urllib.parse import urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("ERP_BASE", "http://127.0.0.1:3001/api/v1")
ORG = os.environ.get("CS_ORG", "DEMO")
EMAIL = os.environ.get("CS_EMAIL", "admin@demo.test")
PASSWORD = os.environ.get("CS_PASSWORD", "Admin@123")
STATE_FILE = os.path.join(HERE, f"state.{ORG}.json")
STATE = json.load(open(STATE_FILE)) if os.path.exists(STATE_FILE) else {}
TOKEN = None

CUSTOMERS = {  # fictional
    "Kato Hardware Ltd": "0772410118",
    "Nalubega Interiors": "0701556240",
    "Ssenyonga Builders": "0759302871",
}


def log(msg):
    print(msg, flush=True)


def save():
    with open(STATE_FILE, "w") as f:
        json.dump(STATE, f, indent=1, sort_keys=True)


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


def partner(name):
    found = rows(call("GET", "/partners?search=" + urllib.parse.quote(name)))
    hit = next((p for p in found if p.get("name") == name), None)
    if hit:
        return hit["id"]
    log(f"  creating customer {name!r}")
    return call("POST", "/partners", {"name": name, "isCustomer": True, "phone": CUSTOMERS.get(name)})["id"]


PRODUCTS = {}


def product(code):
    if code not in PRODUCTS:
        found = rows(call("GET", "/products?search=" + urllib.parse.quote(code)))
        hit = next((p for p in found if p.get("code") == code), None)
        if not hit:
            raise SystemExit(f"product {code} not found")
        PRODUCTS[code] = hit["id"]
    return PRODUCTS[code]


def main():
    global TOKEN
    args = sys.argv[1:]
    if args[:2] != ["--confirm", ORG]:
        raise SystemExit(f"Refusing to run: pass --confirm {ORG} (and back up the database first)")
    host = urlparse(BASE).hostname
    if host not in ("127.0.0.1", "localhost"):
        raise SystemExit(f"Refusing to run against {host}: ERP_BASE must be localhost")
    TOKEN = call("POST", "/auth/login", {"organizationCode": ORG, "email": EMAIL, "password": PASSWORD})["accessToken"]
    branches = {p["branchName"]: p for p in call("GET", "/sales/branch-profiles")}
    modes = {m["label"]: m["id"] for m in call("GET", "/sales/cash-sales/payment-modes")}
    tomorrow = (date.today() + timedelta(days=1)).isoformat()

    def sale(key, branch, customer, lines, payments, delivery=None, steps=(), after=None):
        if key in STATE:
            log(f"= {key}: {STATE[key]['number']} (already made)")
            return
        prof = branches.get(branch)
        if not prof:
            raise SystemExit(f"branch profile {branch!r} not found")
        body = {"branchId": prof["branchId"], "lines": [{"productId": product(c), "quantity": q} for c, q in lines]}
        if customer:
            body["partnerId"] = partner(customer)
            body["customerName"] = customer
            body["customerPhone"] = CUSTOMERS.get(customer)
        s = call("POST", "/sales/cash-sales", body)
        sid, total = s["id"], float(s["grandTotal"])
        STATE[key] = {"id": sid, "number": s["number"]}
        save()
        paid = 0.0
        for i, (mode, extra) in enumerate(payments):
            amount = round(total - paid, 2) if i == len(payments) - 1 else extra.pop("amount")
            paid += amount
            call("POST", f"/sales/cash-sales/{sid}/payments", {"paymentModeId": modes[mode], "amount": amount, **extra})
        if delivery:
            call("PATCH", f"/sales/cash-sales/{sid}/delivery", {k: v for k, v in delivery.items() if k != "after"})
        for step in steps:
            call("POST", f"/sales/cash-sales/{sid}/{step[0]}", step[1] if len(step) > 1 else None)
            if step[0] == "confirm" and delivery and delivery.get("after"):
                call("PATCH", f"/sales/cash-sales/{sid}/delivery", delivery["after"])
        out = call("GET", f"/sales/cash-sales/{sid}")
        STATE[key]["status"] = f"{out['executionStatus']}/{out['processingStatus']}"
        STATE[key]["invoiceId"] = out.get("currentInvoiceId")
        save()
        log(f"+ {key}: {s['number']} {STATE[key]['status']} total {total:,.0f}")

    sale("ho-acme-delivered", "Head Office", "Acme Retail Ltd", [("P-WATER", 24), ("P-SODA", 12)],
         [("Cash", {"amount": 50000, "reference": "RCPT-1042", "payerName": "Mukasa Ivan"}),
          ("MTN Mobile Money", {"reference": "MP261122.0914.B30551", "payerName": "Mukasa Ivan",
                                "payerAccount": "0772188420"})],
         {"deliveryMethod": "delivery", "deliveryAddress": "Acme Retail, Plot 7 Jinja Road, Kampala",
          "deliveryContactName": "Mukasa Ivan", "deliveryContactPhone": "0772188420", "deliveryStatus": "scheduled",
          "deliveryScheduledDate": date.today().isoformat(),
          "after": {"deliveryStatus": "delivered", "deliveryDriver": "Okello Moses", "deliveryVehicle": "UBK 512T",
                    "deliveryReceivedBy": "Mukasa Ivan (store keeper)"}},
         [("confirm",), ("submit",), ("approve-and-invoice",)])
    sale("krd-walkin-collected", "Kampala Road Cafe", None, [("P-CROISSANT", 4), ("P-MUFFIN", 2)],
         [("Cash", {"payerName": "Walk-in"})],
         {"deliveryMethod": "pickup", "after": {"deliveryStatus": "delivered", "deliveryReceivedBy": "Customer at counter"}},
         [("confirm",), ("submit",), ("approve-and-invoice",)])
    sale("acm-kato-bank-submitted", "Acacia Mall Outlet", "Kato Hardware Ltd", [("P-BURGER", 3), ("P-CAESAR", 2)],
         [("Bank", {"reference": "STB-TRF-778120", "payerName": "Kato Hardware Ltd", "payerAccount": "9030014477821",
                    "notes": "Stanbic transfer, slip attached at the counter"})],
         {"deliveryMethod": "delivery", "deliveryAddress": "Kato Hardware, Nakawa market road",
          "deliveryContactPhone": "0772410118", "deliveryStatus": "scheduled", "deliveryScheduledDate": tomorrow,
          "deliveryLandmark": "Opposite Nakawa taxi stage"},
         [("confirm",), ("submit",)])
    sale("ebb-nalubega-airtel-review", "Entebbe Airport Kiosk", "Nalubega Interiors", [("P-WINGS", 2), ("P-APPLE-PIE", 4)],
         [("Airtel Money", {"reference": "AM.2611.553201", "payerName": "Nalubega Sarah", "payerAccount": "0701556240"})],
         {"deliveryMethod": "delivery", "deliveryAddress": "Nalubega Interiors, Kitoro, Entebbe",
          "after": {"deliveryStatus": "dispatched", "deliveryDriver": "Wasswa Paul", "deliveryVehicle": "UAX 903K"}},
         [("confirm",), ("submit",), ("start-review",)])
    sale("ho-walkin-confirmed", "Head Office", None, [("P-ICED-TEA", 6)], [("Cash", {})], None, [("confirm",)])
    sale("krd-ssenyonga-draft", "Kampala Road Cafe", "Ssenyonga Builders", [("P-OMELETTE", 2), ("P-BFST-AVOCADO", 1)],
         [("MTN Mobile Money", {"reference": "MP261122.1102.C77810", "payerName": "Ssenyonga Robert"})])
    sale("acm-ssenyonga-returned", "Acacia Mall Outlet", "Ssenyonga Builders", [("P-PUDDING", 3)],
         [("Cash", {"payerName": "Ssenyonga Robert"})], None,
         [("confirm",), ("submit",), ("start-review",),
          ("return", {"reason": "Customer TIN missing on the sale; add it and resubmit"})])
    sale("ebb-acme-approved", "Entebbe Airport Kiosk", "Acme Retail Ltd", [("P-BFST-AVOCADO", 2), ("P-CROISSANT", 2)],
         [("Cash", {"amount": 20000, "payerName": "Acme driver"}),
          ("MTN Mobile Money", {"reference": "MP261122.1120.D11873", "payerName": "Acme Retail accounts"})],
         {"deliveryMethod": "pickup", "after": {"deliveryStatus": "delivered", "deliveryReceivedBy": "Acme driver"}},
         [("confirm",), ("submit",), ("start-review",), ("approve",)])
    log("done")


if __name__ == "__main__":
    main()

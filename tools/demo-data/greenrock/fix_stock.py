"""Tops up any demo stockpile that deliveries or production took below zero, with a normal stock-in, so screens show no
negative stock. Only touches locations created by this seeder (codes WH-*)."""
from core import A, ids, once, rows


def run():
    uid = A.user.get("id")
    if not uid:
        A.login()
        uid = A.user.get("id")
    codes = {v: k for k, v in ids("location").items()}
    products = {v: k for k, v in ids("product").items()}
    fixed = 0
    for row in rows(A.get("/quarry/rom-stock")):
        qty = float(row.get("onHandTonnes") or 0)
        loc = codes.get(row.get("locationId"), "")
        if qty < 0 and loc.startswith("WH-") and row.get("productId") in products:
            top_up = round(-qty + 60, 3)
            cost = float(row.get("averageCost") or 0) or 25000
            once("topup-%s-%s" % (loc, products[row["productId"]]), lambda r=row, q=top_up, c=cost: A.post("/inventory/direct-stock/in", {
                "locationId": r["locationId"], "responsibleById": uid, "approvedById": uid, "notes": "DEMO top-up after demo deliveries",
                "items": [{"productId": r["productId"], "quantity": q, "unitCost": c}]}) and True)
            print("  topped up", loc, products[row["productId"]], "by", top_up)
            fixed += 1
    print("  negative stockpiles fixed:", fixed)

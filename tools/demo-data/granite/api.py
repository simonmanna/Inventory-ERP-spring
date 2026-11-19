"""API client for the granite demo: login, JSON calls, idempotency keys, image upload, per-org state file."""
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from datetime import date, timedelta
from urllib.parse import urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("ERP_BASE", "http://127.0.0.1:3001/api/v1")
ORG = os.environ.get("GRANITE_ORG", "DEMO")
EMAIL = os.environ.get("GRANITE_EMAIL", "admin@demo.test")
PASSWORD = os.environ.get("GRANITE_PASSWORD", "Admin@123")
IMAGES = os.path.join(HERE, "images")
STATE_FILE = os.path.join(HERE, f"state.{ORG}.json")
TOKEN = None
STATE = json.load(open(STATE_FILE)) if os.path.exists(STATE_FILE) else {}


def save():
    with open(STATE_FILE, "w") as f:
        json.dump(STATE, f, indent=1, sort_keys=True)


def day(offset):
    """ISO date `offset` days from today (negative = past), so documents land in an open period."""
    return (date.today() + timedelta(days=offset)).isoformat()


def call(method, path, body=None, key=False, raw=None, ctype="application/json"):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", ctype)
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    if key:
        req.add_header("Idempotency-Key", str(uuid.uuid4()))
    try:
        with urllib.request.urlopen(req, timeout=120) as res:
            txt = res.read().decode()
            return json.loads(txt) if txt else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} -> {e.code}: {e.read().decode()[:700]}")


def rows(data):
    if isinstance(data, list):
        return data
    return data.get("data") or data.get("rows") or data.get("items") or []


def upload(path):
    """Uploads a product image; returns the file record (its id goes into Product.image)."""
    b = uuid.uuid4().hex
    name = os.path.basename(path)
    body = b"".join([
        f'--{b}\r\nContent-Disposition: form-data; name="ownerType"\r\n\r\nproduct\r\n'.encode(),
        f'--{b}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: image/jpeg\r\n\r\n'.encode(),
        open(path, "rb").read(), f"\r\n--{b}--\r\n".encode()])
    return call("POST", "/files/upload", raw=body, ctype=f"multipart/form-data; boundary={b}")


def products(prefix):
    """Existing products whose code starts with `prefix`, by code."""
    return {p["code"]: p["id"] for p in rows(call("GET", f"/products?pageSize=100&search={prefix}"))
            if str(p.get("code", "")).startswith(prefix)}


def login(argv):
    global TOKEN
    if urlparse(BASE).hostname not in ("localhost", "127.0.0.1"):
        raise SystemExit(f"Refusing non-local target {BASE}. Run on the server itself.")
    if len(argv) < 3 or argv[1] != "--confirm" or argv[2] != ORG:
        raise SystemExit(f"Pass --confirm {ORG} [steps...] to write to tenant {ORG} on {BASE}.")
    TOKEN = call("POST", "/auth/login", {"organizationCode": ORG, "email": EMAIL, "password": PASSWORD})["accessToken"]


class Ctx:
    """Org look-ups shared by the steps: units, categories, attributes, warehouse, partners, branch."""

    def __init__(self):
        self.uom = {u["code"]: u["id"] for u in rows(call("GET", "/uoms"))}
        self.pcat = {c["name"]: c["id"] for c in rows(call("GET", "/product-categories"))}
        locs = rows(call("GET", "/inventory/locations?pageSize=200"))
        self.warehouse = next(l["id"] for l in locs if l.get("type") == "warehouse")
        partners = rows(call("GET", "/partners?pageSize=200"))
        self.customer = next((p["id"] for p in partners if p.get("name") == "Acme Retail"),
                             next(p["id"] for p in partners if p.get("isCustomer")))
        self.supplier = next(p["id"] for p in partners if p.get("isSupplier"))
        branches = rows(call("GET", "/branches?pageSize=100"))
        self.branch = branches[0]["id"] if branches else None

    def category(self, name):
        if name not in self.pcat:
            self.pcat[name] = call("POST", "/product-categories", {"name": name})["id"]
        return self.pcat[name]


def attributes(spec):
    """Creates missing variant attributes / values; returns ({attr: id}, {attr: {value: id}})."""
    have = {a["name"]: a for a in call("GET", "/variant-attributes")}
    for name, (display, values) in spec.items():
        if name not in have:
            have[name] = call("POST", "/variant-attributes", {"name": name, "displayType": display, "values": [
                {"name": v, **({"htmlColor": c} if c else {})} for v, c in values]})
    return ({n: a["id"] for n, a in have.items()},
            {n: {v["name"]: v["id"] for v in a["values"]} for n, a in have.items()})


def configure_variants(pid, lines, att, val):
    """Sets a product's attribute lines [(attr, [(value, priceExtra)])] and the non-zero price extras."""
    cfg = call("PUT", f"/products/{pid}/attribute-lines", {"lines": [
        {"attributeId": att[a], "valueIds": [val[a][v] for v, _ in vals]} for a, vals in lines]})
    for line in cfg["lines"]:
        a = next(n for n, i in att.items() if i == line["attributeId"])
        extras = [{"valueId": val[a][v], "priceExtra": e} for v, e in dict(lines)[a] if e]
        if extras:
            call("PATCH", f"/products/{pid}/attribute-lines/{line['id']}/values", {"values": extras})


def sizing(pid, mode, length, width=None, height=None):
    body = {"sizingMode": mode, "sizingUnit": "m", "defaultLength": length}
    if width:
        body["defaultWidth"] = width
    if height:
        body["defaultHeight"] = height
    call("PUT", f"/products/{pid}/sizing", body)


def variant(pid, **want):
    """The variant of `pid` whose attributes match `want` (underscores in keys stand for spaces)."""
    for v in call("GET", f"/products/{pid}/variants"):
        if all(v["attributes"].get(k.replace("_", " ")) == x for k, x in want.items()):
            return v
    raise SystemExit(f"No variant {want} on product {pid}")


def log(*a):
    print(*a, file=sys.stdout, flush=True)

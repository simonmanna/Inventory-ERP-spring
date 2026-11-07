"""Plumbing for the GreenRock demo seeder: guarded API client, state file, idempotent steps."""
import hashlib
import json
import os
import time
import urllib.error
import urllib.request
from urllib.parse import urlencode, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("ERP_BASE", "http://127.0.0.1:3001/api/v1")
ORG = os.environ.get("GREENROCK_ORG", "GRC")
# Demo-only sign-ins, local database. MENA is the existing demo group company (tools/demo-data/mena).
_LOGINS = {"MENA": ("MenaDemo@2026", "admin@menagranite.test", "accounts@menagranite.test")}
PASSWORD, ADMIN, CHECKER = _LOGINS.get(ORG, ("GreenRockDemo@2026", "admin@greenrock.test", "accounts@greenrock.test"))
STATE_FILE = os.path.join(HERE, f"state.{ORG}.json")
LOG_FILE = os.path.join(HERE, "seed-log.jsonl")
STATE = json.load(open(STATE_FILE)) if os.path.exists(STATE_FILE) else {}


def guard(confirm_org):
    """Refuse remote targets and anything but the exact demo tenant the operator named."""
    host = urlparse(BASE).hostname
    if host not in ("localhost", "127.0.0.1"):
        raise SystemExit(f"Refusing non-local target {BASE}: this seeder only runs against a local demo stack.")
    if confirm_org != ORG:
        raise SystemExit(f"Pass --confirm {ORG} to write to demo tenant {ORG} on {BASE}.")


def save():
    tmp = STATE_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(STATE, f, indent=1, sort_keys=True)
    os.replace(tmp, STATE_FILE)


def ids(kind):
    return STATE.setdefault(kind, {})


class ApiError(Exception):
    def __init__(self, method, path, status, body):
        super().__init__(f"{method} {path} -> {status}: {str(body)[:700]}")
        self.status, self.body = status, body


class Api:
    def __init__(self, email):
        self.email, self.token, self.at, self.user = email, None, 0, {}

    def login(self):
        """Tokens live 15 minutes and login is rate limited, so a token is reused across runs for 10 minutes."""
        path = os.path.join(HERE, f".tokens.{ORG}.json")
        cache = json.load(open(path)) if os.path.exists(path) else {}
        hit = cache.get(self.email)
        if hit and time.time() - hit["at"] < 600:
            self.token, self.at, self.user = hit["token"], hit["at"], hit["user"]
            return
        res = self.raw("POST", "/auth/login", {"organizationCode": ORG, "email": self.email, "password": PASSWORD})
        self.token, self.at = res["accessToken"], time.time()
        self.user = res.get("user") or {}
        cache[self.email] = {"token": self.token, "at": self.at, "user": self.user}
        with open(path, "w") as f:
            json.dump(cache, f)

    def raw(self, method, path, body=None, key=None, params=None):
        url = BASE + path
        if params:
            url += "?" + urlencode({k: v for k, v in params.items() if v is not None})
        req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method)
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        if method != "GET":
            seed = f"{ORG}|{self.email}|" + (key or (method + path + json.dumps(body, sort_keys=True, default=str)))
            req.add_header("Idempotency-Key", "gr-" + hashlib.sha1(seed.encode()).hexdigest()[:32])
        raw, status = "null", 0
        for attempt in range(5):
            try:
                with urllib.request.urlopen(req, timeout=180) as r:
                    raw, status = r.read().decode() or "null", r.status
                break
            except urllib.error.HTTPError as e:
                raw, status = e.read().decode() or "null", e.code
                if status != 429:
                    break
                time.sleep(15 * (attempt + 1))
        try:
            out = json.loads(raw)
        except ValueError:
            out = raw
        if status >= 400:
            with open(LOG_FILE, "a", encoding="utf-8") as f:
                f.write(json.dumps({"m": method, "p": path, "s": status, "req": body, "res": out}, default=str) + "\n")
            raise ApiError(method, path, status, out)
        return out

    def call(self, method, path, body=None, key=None, params=None):
        if not self.token or time.time() - self.at > 600:
            self.login()
        return self.raw(method, path, body, key, params)

    def get(self, path, **params):
        return self.call("GET", path, params=params)

    def post(self, path, body=None, key=None):
        return self.call("POST", path, {} if body is None else body, key)

    def put(self, path, body=None, key=None):
        return self.call("PUT", path, body, key)

    def patch(self, path, body=None, key=None):
        return self.call("PATCH", path, body, key)


def rows(res):
    if isinstance(res, list):
        return res
    if isinstance(res, dict):
        for k in ("data", "items", "rows", "content"):
            if isinstance(res.get(k), list):
                return res[k]
    return []


A = Api(ADMIN)
C = Api(CHECKER)


def once(key, fn):
    """Run a step one time; the result is remembered in the state file so a re-run skips it."""
    done = STATE.setdefault("steps", {})
    if key in done:
        return done[key]
    out = fn()
    done[key] = out if out is not None else True
    save()
    print("  ok", key, flush=True)
    return done[key]


def ensure(kind, code, list_path, create_path, body, field="code", params=None):
    s = ids(kind)
    if code in s:
        return s[code]
    found = [x for x in rows(A.get(list_path, **(params or {}))) if x.get(field) == code]
    s[code] = found[0]["id"] if found else A.post(create_path, body)["id"]
    save()
    return s[code]


def bootstrap(body):
    """Public route: create the demo tenant. A tenant that already exists is reused."""
    try:
        return Api("bootstrap").raw("POST", "/organizations/bootstrap", body)
    except ApiError as e:
        if e.status == 409 or "already" in str(e.body).lower():
            return None
        raise

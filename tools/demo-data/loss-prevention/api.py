"""API client for the loss-prevention demo: login, JSON calls, idempotency keys, per-org state file."""
import json
import os
import urllib.error
import urllib.parse
import urllib.request
import uuid
from urllib.parse import urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("ERP_BASE", "http://127.0.0.1:3001/api/v1")
ORG = os.environ.get("LP_DEMO_ORG", "DEMO")
EMAIL = os.environ.get("LP_DEMO_EMAIL", "admin@demo.test")
PASSWORD = os.environ.get("LP_DEMO_PASSWORD", "Admin@123")
STATE_FILE = os.path.join(HERE, os.environ.get("LP_DEMO_STATE", f"state.{ORG}.json"))
STATE = json.load(open(STATE_FILE)) if os.path.exists(STATE_FILE) else {}


class Session:
    """One bearer token: the admin user, or the simulated edge agent's API client."""

    def __init__(self, token=None):
        self.token = token

    def call(self, method, path, body=None, key=False, form=None, ok404=False):
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            ctype = "application/x-www-form-urlencoded"
        else:
            data = json.dumps(body).encode() if body is not None else None
            ctype = "application/json"
        req = urllib.request.Request(BASE + path, data=data, method=method)
        req.add_header("Content-Type", ctype)
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        if key:
            req.add_header("Idempotency-Key", str(uuid.uuid4()))
        try:
            with urllib.request.urlopen(req, timeout=300) as res:
                txt = res.read().decode()
                return json.loads(txt) if txt else None
        except urllib.error.HTTPError as e:
            if ok404 and e.code == 404:
                return None
            raise SystemExit(f"{method} {path} -> {e.code}: {e.read().decode()[:900]}")


def save():
    with open(STATE_FILE, "w") as f:
        json.dump(STATE, f, indent=1, sort_keys=True)
    os.chmod(STATE_FILE, 0o600)  # holds the simulated agent's client secret


def rows(data):
    if isinstance(data, list):
        return data
    return data.get("data") or data.get("rows") or data.get("items") or []


def check_target(argv):
    if urlparse(BASE).hostname not in ("localhost", "127.0.0.1"):
        raise SystemExit(f"Refusing non-local target {BASE}. Run on the server itself.")
    if len(argv) < 3 or argv[1] != "--confirm" or argv[2] != ORG:
        raise SystemExit(f"Pass --confirm {ORG} [steps...] to write to tenant {ORG} on {BASE}.")


def admin():
    s = Session()
    s.token = s.call("POST", "/auth/login", {"organizationCode": ORG, "email": EMAIL,
                                              "password": PASSWORD})["accessToken"]
    return s


def agent_session():
    a = STATE["agent"]
    tok = Session().call("POST", "/oauth/token", form={"grant_type": "client_credentials", "client_id":
                                                        a["clientId"], "client_secret": a["clientSecret"]})
    return Session(tok["access_token"])


def log(*a):
    print(*a, flush=True)

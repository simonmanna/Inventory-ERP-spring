"""Tiny stdlib HTTP client for the demo seeder. Every write carries a deterministic Idempotency-Key so a re-run
replays instead of duplicating; every call is appended to a JSONL log for the run report."""
import hashlib
import json
import os
import urllib.error
import urllib.request

BASE = os.environ.get("ERP_BASE", "http://localhost:3011/api/v1")
LOG = os.environ.get("ERP_LOG", "seed-log.jsonl")


class ApiError(Exception):
    def __init__(self, method, path, status, body):
        super().__init__(f"{method} {path} -> {status}: {str(body)[:600]}")
        self.status = status
        self.body = body


class Api:
    def __init__(self, base=BASE):
        self.base = base
        self.token = None
        self.branch = None
        self.who = ""

    def _log(self, rec):
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, default=str) + "\n")

    def call(self, method, path, body=None, key=None, params=None, ok=(200, 201, 204)):
        url = self.base + path
        if params:
            from urllib.parse import urlencode
            url += "?" + urlencode({k: v for k, v in params.items() if v is not None})
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        if self.branch:
            req.add_header("X-Branch-Id", self.branch)
        if method in ("POST", "PUT", "PATCH", "DELETE"):
            seed = self.who + "|" + (key or (method + path + json.dumps(body, sort_keys=True, default=str)))
            req.add_header("Idempotency-Key", "mena-" + hashlib.sha1(seed.encode()).hexdigest()[:32])
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                raw = r.read().decode() or "null"
                status = r.status
        except urllib.error.HTTPError as e:
            raw = e.read().decode() or "null"
            status = e.code
        try:
            out = json.loads(raw)
        except ValueError:
            out = raw
        self._log({"m": method, "p": path, "s": status, "req": body, "res": out if status >= 400 else None})
        if status not in ok:
            raise ApiError(method, path, status, out)
        return out

    def get(self, path, **params):
        return self.call("GET", path, params=params)

    def post(self, path, body=None, key=None):
        return self.call("POST", path, body, key)

    def patch(self, path, body=None, key=None):
        return self.call("PATCH", path, body, key)

    def put(self, path, body=None, key=None):
        return self.call("PUT", path, body, key)


def rows(res):
    """List endpoints return either a plain array or a {data|items: [...]} envelope."""
    if isinstance(res, list):
        return res
    if isinstance(res, dict):
        for k in ("data", "items", "rows", "content"):
            if isinstance(res.get(k), list):
                return res[k]
    return []

"""Demo footage for loss prevention (ADR-S077 LP1/LP1c): uploads synthetic video for the clips a tenant's cases asked
for, through the edge agent's own upload route, and a still of each till camera at the moment the drawer is open.

Everything goes through the running API, as an enrolled edge box would: the backend checks each upload against its
SHA-256, encrypts it and records it in the access trail. Every frame says it is synthetic. See README.md.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

BASE = os.environ.get("ERP_BASE", "http://127.0.0.1:3001/api/v1")
ORG = os.environ.get("LP_ORG", "DEMO")
EMAIL = os.environ.get("LP_EMAIL", "admin@demo.test")
PASSWORD = os.environ.get("LP_PASSWORD", "Admin@123")
CHUNK = 1 << 20
STILL_OFFSET_SECONDS = 22  # the drawer is open from 18 s to 30 s in a "till" clip
HERE = Path(__file__).resolve().parent


def call(method: str, path: str, token: str | None = None, body: object = None, raw: bytes | None = None,
         ctype: str | None = None) -> tuple[int, object]:
    data, headers = None, {}
    if body is not None:
        data, headers["Content-Type"] = json.dumps(body).encode(), "application/json"
    if raw is not None:
        data, headers["Content-Type"] = raw, ctype or "application/octet-stream"
    if token:
        headers["Authorization"] = "Bearer " + token
    if method != "GET":
        headers["Idempotency-Key"] = str(uuid.uuid4())
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            text = resp.read()
            return resp.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:300]


def agent_token(client_id: str, secret: str) -> str:
    basic = base64.b64encode(f"{client_id}:{secret}".encode()).decode()
    req = urllib.request.Request(BASE + "/oauth/token", method="POST",
                                 data=urllib.parse.urlencode({"grant_type": "client_credentials",
                                                              "scope": "lp:ingest"}).encode(),
                                 headers={"Content-Type": "application/x-www-form-urlencoded",
                                          "Authorization": "Basic " + basic})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())["access_token"]


def evidence(token: str, **params: str) -> list[dict]:
    status, page = call("GET", "/loss-prevention/evidence?" + urllib.parse.urlencode({**params, "pageSize": 200}),
                        token)
    if status != 200:
        raise SystemExit(f"GET /loss-prevention/evidence: {status} {page}")
    return page["data"]


def upload(agent: str, evidence_id: str, path: Path, ctype: str) -> str:
    blob = path.read_bytes()
    sha = hashlib.sha256(blob).hexdigest()
    status, res = call("GET", f"/loss-prevention/evidence/{evidence_id}/upload", agent)
    offset = res["receivedBytes"] if status == 200 and isinstance(res, dict) else 0
    while offset < len(blob):
        part = blob[offset:offset + CHUNK]
        q = urllib.parse.urlencode({"offset": offset, "total": len(blob), "sha256": sha, "contentType": ctype})
        status, res = call("POST", f"/loss-prevention/evidence/{evidence_id}/upload?{q}", agent, raw=part)
        if status != 200:
            raise SystemExit(f"upload {evidence_id} at {offset}: {status} {res}")
        offset += len(part)
    return sha


def epoch(iso: str) -> int:
    return int(dt.datetime.fromisoformat(iso.replace("Z", "+00:00")).replace(tzinfo=dt.timezone.utc).timestamp())


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--confirm", required=True, help="the organization code, typed again (safety)")
    ap.add_argument("--agent-state", help="a JSON file holding {agent: {clientId, clientSecret}} (the LP pilot seeder's"
                    " state file); otherwise LP_AGENT_CLIENT_ID and LP_AGENT_SECRET")
    ap.add_argument("--no-stills", action="store_true", help="upload the clips only")
    args = ap.parse_args()
    if args.confirm != ORG:
        raise SystemExit(f"--confirm must be {ORG}")
    if urllib.parse.urlparse(BASE).hostname not in ("127.0.0.1", "localhost"):
        raise SystemExit("ERP_BASE must be on this machine (127.0.0.1 or localhost)")
    if args.agent_state:
        a = json.loads(Path(args.agent_state).read_text())["agent"]
        client_id, secret = a["clientId"], a["clientSecret"]
    else:
        client_id, secret = os.environ.get("LP_AGENT_CLIENT_ID"), os.environ.get("LP_AGENT_SECRET")
    if not client_id or not secret:
        raise SystemExit("give --agent-state or LP_AGENT_CLIENT_ID and LP_AGENT_SECRET (the edge agent's credentials)")

    agent = agent_token(client_id, secret)
    status, login = call("POST", "/auth/login", body={"organizationCode": ORG, "email": EMAIL, "password": PASSWORD})
    if status not in (200, 201):
        raise SystemExit(f"login as {EMAIL}: {status} {login}")
    admin = login["accessToken"]
    make = HERE / "make_clip.sh"

    with tempfile.TemporaryDirectory(prefix="lp-footage-") as tmp:
        work = Path(tmp)
        pending = [e for state in ("requested", "uploading") for e in evidence(admin, state=state, kind="clip")]
        till_clips: dict[str, tuple[Path, dict]] = {}
        for e in sorted(pending, key=lambda x: (x["startedAt"], x.get("cameraName") or "")):
            camera = e.get("cameraName") or e["cameraId"]
            scene = "till" if "till" in camera.lower() else "pickup"
            seconds = max(5, epoch(e["endedAt"]) - epoch(e["startedAt"]))
            out = work / f"{e['id']}.mp4"
            subprocess.run([str(make), str(out), str(epoch(e["startedAt"])), str(seconds), camera, scene], check=True)
            sha = upload(agent, e["id"], out, "video/mp4")
            print(f"clip  {e['startedAt']}  {camera:<20} {sha[:12]}")
            for case_id in e.get("caseIds") or []:
                if scene == "till":
                    till_clips.setdefault(case_id, (out, e))
        if not pending:
            print("no clip is waiting for an upload")

        if args.no_stills:
            return
        for case_id, (clip, e) in till_clips.items():
            if evidence(admin, caseId=case_id, kind="snapshot"):
                continue  # the case has a still already
            at = dt.datetime.fromtimestamp(epoch(e["startedAt"]) + STILL_OFFSET_SECONDS, dt.timezone.utc)
            status, still = call("POST", "/loss-prevention/evidence/still", admin, body={
                "cameraId": e["cameraId"], "at": at.strftime("%Y-%m-%dT%H:%M:%SZ"), "caseId": case_id,
                "reason": "Drawer open - demo still"})
            if status != 201:
                print(f"still refused for case {case_id}: {status} {still}", file=sys.stderr)
                continue
            jpg = work / f"{still['id']}.jpg"
            subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-ss", str(STILL_OFFSET_SECONDS),
                            "-i", str(clip), "-frames:v", "1", "-q:v", "3", str(jpg)], check=True)
            sha = upload(agent, still["id"], jpg, "image/jpeg")
            print(f"still {at.isoformat()}  {e.get('cameraName')}  {sha[:12]}")


if __name__ == "__main__":
    main()

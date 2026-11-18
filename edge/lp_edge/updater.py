"""Host-side updater: moves the agent's container to the release its configuration asks for, or back.

It runs on the host (a systemd timer, every 5 minutes), not in the agent's container, because only the host can
replace the container. It never trusts the agent: it reads the desired release from the local health endpoint and
checks its Ed25519 signature itself (``version|image|digest``). Then it pulls ``image@digest``, writes
``LP_EDGE_IMAGE`` to the compose env file, recreates the service, and waits for the health endpoint to report the new
version. If that does not happen within the timeout it puts the previous image back.

    lp-edge-updater --compose-file /opt/lp-edge/compose.yml --env-file /opt/lp-edge/.env --trust-key /etc/lp-edge/vendor.pub
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from lp_edge.files import write_private
from lp_edge.signing import TrustStore, release_message

log = logging.getLogger("lp_edge.updater")

IMAGE = re.compile(r"^[a-z0-9]+(?:[._/-][a-z0-9]+)*(?::[0-9]+)?(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*(?::[A-Za-z0-9._-]+)?$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
ENV_KEY = "LP_EDGE_IMAGE"
PREVIOUS_KEY = "LP_EDGE_PREVIOUS_IMAGE"

Runner = Callable[[Sequence[str]], tuple[int, str]]
Fetch = Callable[[str], tuple[int, dict[str, Any] | None]]


def run_command(args: Sequence[str]) -> tuple[int, str]:
    completed = subprocess.run(list(args), capture_output=True, text=True, timeout=900, check=False)  # noqa: S603
    return completed.returncode, (completed.stdout + completed.stderr)[-2000:]


def fetch_json(url: str) -> tuple[int, dict[str, Any] | None]:
    try:
        with urllib.request.urlopen(url, timeout=5) as response:  # noqa: S310 - loopback only
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        return exc.code, None
    except (urllib.error.URLError, OSError, ValueError):
        return 0, None


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, _, value = line.partition("=")
                values[key.strip()] = value.strip()
    return values


def write_env(path: Path, updates: dict[str, str]) -> None:
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    seen = set()
    out = []
    for line in lines:
        key = line.partition("=")[0].strip()
        if key in updates and not line.lstrip().startswith("#"):
            out.append(f"{key}={updates[key]}")
            seen.add(key)
        else:
            out.append(line)
    out += [f"{key}={value}" for key, value in updates.items() if key not in seen]
    write_private(path, ("\n".join(out) + "\n").encode("utf-8"))


@dataclass
class Updater:
    trust: TrustStore
    compose_file: Path
    env_file: Path
    service: str = "lp-edge"
    health_url: str = "http://127.0.0.1:8765"
    timeout: float = 180.0
    runner: Runner = run_command
    fetch: Fetch = fetch_json
    sleep: Callable[[float], None] = time.sleep
    monotonic: Callable[[], float] = time.monotonic

    def check(self) -> str:
        """One pass. Returns ``no_release``, ``unreachable``, ``invalid``, ``up_to_date``, ``updated``,
        ``pull_failed`` or ``rolled_back``."""
        status, release = self.fetch(self.health_url.rstrip("/") + "/release")
        if status == 404:
            return "no_release"
        if status != 200 or not isinstance(release, dict):
            log.warning("the agent's health endpoint did not answer")
            return "unreachable"
        version, image, digest = (str(release.get(k) or "") for k in ("version", "image", "digest"))
        signature = str(release.get("signature") or "")
        if not (version and IMAGE.match(image) and DIGEST.match(digest)):
            log.error("the desired release is malformed; ignored")
            return "invalid"
        if not self.trust.verify(release_message(version, image, digest), signature):
            log.error("the signature of release %s does not verify; ignored", version)
            return "invalid"
        desired = f"{image}@{digest}"
        current = read_env(self.env_file).get(ENV_KEY)
        if current == desired:
            return "up_to_date"
        log.info("updating the agent to release %s", version)
        code, output = self.runner(["docker", "pull", desired])
        if code != 0:
            log.error("docker pull failed: %s", output.strip()[-500:])
            return "pull_failed"
        updates = {ENV_KEY: desired}
        if current:
            updates[PREVIOUS_KEY] = current
        write_env(self.env_file, updates)
        if self._recreate() and self._healthy(version):
            log.info("the agent runs release %s", version)
            return "updated"
        log.error("release %s did not become healthy; rolling back", version)
        if current:
            write_env(self.env_file, {ENV_KEY: current})
            self._recreate()
        return "rolled_back"

    def _recreate(self) -> bool:
        code, output = self.runner(
            [
                "docker",
                "compose",
                "-f",
                str(self.compose_file),
                "--env-file",
                str(self.env_file),
                "up",
                "-d",
                "--no-deps",
                self.service,
            ]
        )
        if code != 0:
            log.error("docker compose up failed: %s", output.strip()[-500:])
        return code == 0

    def _healthy(self, version: str) -> bool:
        deadline = self.monotonic() + self.timeout
        while self.monotonic() < deadline:
            status, body = self.fetch(self.health_url.rstrip("/") + "/health")
            if status == 200 and isinstance(body, dict) and body.get("version") == version:
                return True
            self.sleep(5.0)
        return False


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="lp-edge-updater", description="Update the lp-edge container (host side).")
    parser.add_argument("--compose-file", required=True, type=Path)
    parser.add_argument("--env-file", required=True, type=Path)
    parser.add_argument("--service", default="lp-edge")
    parser.add_argument("--trust-key", default=os.environ.get("LP_EDGE_TRUST_KEY"))
    parser.add_argument("--health-url", default=f"http://127.0.0.1:{os.environ.get('LP_EDGE_HEALTH_PORT', '8765')}")
    parser.add_argument("--timeout", type=float, default=180.0)
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    trust = TrustStore.parse(args.trust_key)
    if trust is None:
        log.error("no trust key (--trust-key or LP_EDGE_TRUST_KEY): releases cannot be verified")
        return 2
    outcome = Updater(
        trust, args.compose_file, args.env_file, args.service, args.health_url, args.timeout
    ).check()
    print(outcome)
    return 0 if outcome in ("no_release", "up_to_date", "updated") else 1


if __name__ == "__main__":
    sys.exit(main())

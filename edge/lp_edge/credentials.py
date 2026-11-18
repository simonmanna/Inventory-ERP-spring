"""The agent's client credentials, obtained at enrolment and kept in ``<data>/credentials.json`` (mode 0600)."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from urllib.parse import urlsplit

from lp_edge.files import write_private


def origin_of(url: str) -> str:
    parts = urlsplit(url)
    return f"{parts.scheme.lower()}://{parts.netloc.lower()}"


@dataclass(frozen=True)
class Credentials:
    """An API client bound to one agent and one branch (scope ``lp:ingest``)."""

    server: str  # origin, e.g. https://erp.example.com
    api_base: str  # path prefix, e.g. /api/v1
    token_url: str  # a path under the same origin (or an absolute URL on it)
    client_id: str
    client_secret: str = field(repr=False)
    scope: str = "lp:ingest"
    agent_id: str = ""
    organization_id: str = ""
    branch_id: str = ""
    enrolled_at: str = ""

    @property
    def api_url(self) -> str:
        return self.server.rstrip("/") + "/" + self.api_base.strip("/")

    @property
    def token_endpoint(self) -> str:
        url = self.token_url
        if "://" in url:
            if origin_of(url) != origin_of(self.server):
                raise ValueError("tokenUrl is not on the server's origin")
            return url
        if not url.startswith("/"):
            url = "/" + self.api_base.strip("/") + "/" + url
        return self.server.rstrip("/") + url


def load(path: Path) -> Credentials | None:
    if not path.exists():
        return None
    data = json.loads(path.read_text(encoding="utf-8"))
    known = {name for name in Credentials.__dataclass_fields__}
    return Credentials(**{key: value for key, value in data.items() if key in known})


def save(path: Path, credentials: Credentials) -> None:
    write_private(path, json.dumps(asdict(credentials), indent=2).encode("utf-8") + b"\n")

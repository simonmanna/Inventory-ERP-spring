"""Enrolment: exchange the one-time code from the ERP for this agent's client credentials.

An administrator creates the agent for a branch in the ERP and gets a code (``XXXX-XXXX-XXXX``, 15 minutes, once).
The installer runs ``lp-edge enrol --server https://erp.example.com`` on the box and types the code. Enrolling again
with a new code replaces the secret; the old one stops working.
"""

from __future__ import annotations

import logging
import socket
import time
from typing import Any
from urllib.parse import urlsplit

from lp_edge import __version__
from lp_edge.api import ApiClient, ApiError
from lp_edge.credentials import Credentials, save
from lp_edge.settings import Settings
from lp_edge.timeutil import iso

log = logging.getLogger(__name__)

DEFAULT_API_BASE = "/api/v1"
_REQUIRED = ("agentId", "organizationId", "branchId", "clientId", "clientSecret")


class EnrolmentError(Exception):
    pass


def normalise_code(code: str) -> str:
    """The code as typed, without spaces and in capitals (the server ignores dashes and case)."""
    cleaned = "".join(code.split()).upper()
    if not cleaned:
        raise EnrolmentError("the enrolment code is empty")
    return cleaned


def split_server(server: str) -> tuple[str, str]:
    """``https://erp.example.com`` or ``https://erp.example.com/api/v1`` as (origin, API prefix)."""
    parts = urlsplit(server.strip())
    if parts.scheme not in ("https", "http") or not parts.hostname:
        raise EnrolmentError("the server must be a URL such as https://erp.example.com")
    if parts.username or parts.password:
        raise EnrolmentError("the server URL must not contain a user name or password")
    origin = f"{parts.scheme}://{parts.netloc}"
    path = parts.path.rstrip("/")
    return origin, path or DEFAULT_API_BASE


def enrol(
    server: str,
    code: str,
    settings: Settings,
    *,
    hostname: str | None = None,
    session: Any = None,
) -> Credentials:
    """Enrol this box and save its credentials; returns them."""
    origin, api_base = split_server(server)
    api = ApiClient(
        origin + api_base, tokens=None, session=session, verify=settings.tls_verify, allow_http=settings.allow_http
    )
    try:
        body = api.enrol(normalise_code(code), hostname or socket.gethostname(), __version__)
    except ApiError as exc:
        raise EnrolmentError(exc.message if exc.status else f"the server could not be reached ({exc.message})") from None
    if not isinstance(body, dict):
        raise EnrolmentError("the server's answer is not a JSON object")
    missing = [name for name in _REQUIRED if not body.get(name)]
    if missing:
        raise EnrolmentError("the server's answer lacks " + ", ".join(missing))
    answered_base = str(body.get("apiBase") or api_base)
    if not answered_base.startswith("/"):
        raise EnrolmentError("apiBase must be a path such as /api/v1")
    credentials = Credentials(
        server=origin,
        api_base=answered_base.rstrip("/") or DEFAULT_API_BASE,
        token_url=str(body.get("tokenUrl") or answered_base.rstrip("/") + "/oauth/token"),
        client_id=str(body["clientId"]),
        client_secret=str(body["clientSecret"]),
        scope=str(body.get("scope") or "lp:ingest"),
        agent_id=str(body["agentId"]),
        organization_id=str(body["organizationId"]),
        branch_id=str(body["branchId"]),
        enrolled_at=iso(time.time()),
    )
    try:
        credentials.token_endpoint  # noqa: B018 - validates that the token URL is on the same origin
    except ValueError as exc:
        raise EnrolmentError(str(exc)) from None
    save(settings.credentials_file, credentials)
    log.info("enrolled as agent %s for branch %s", credentials.agent_id, credentials.branch_id)
    return credentials

"""HTTPS client for the agent protocol (``/loss-prevention/agents/...``, ``/loss-prevention/events``, evidence upload).

The agent only makes outbound calls. Each call carries a bearer token from the OAuth client-credentials grant
(scope ``lp:ingest``); a 401 fetches a new token once. Errors become :class:`ApiError` with the ERP's message.
Nothing here logs a token, a secret or a request body.
"""

from __future__ import annotations

import hashlib
import logging
import threading
import time
from collections.abc import Mapping
from pathlib import Path
from typing import Any
from urllib.parse import quote, urlsplit

import requests

from lp_edge import __version__
from lp_edge.credentials import Credentials, origin_of

log = logging.getLogger(__name__)

USER_AGENT = f"lp-edge/{__version__}"
LP = "/loss-prevention"
DEFAULT_TIMEOUT = (10.0, 30.0)
MAX_MODEL_BYTES = 2 * 1024**3


class ApiError(Exception):
    """A call that did not succeed. ``status`` 0 means the server was not reached."""

    def __init__(
        self, status: int, message: str, *, body: Any = None, retry_after: float | None = None
    ) -> None:
        super().__init__(f"HTTP {status}: {message}" if status else message)
        self.status = status
        self.message = message
        self.body = body
        self.retry_after = retry_after

    @property
    def retryable(self) -> bool:
        """Worth trying again later: not reached, timed out, rate limited, or a server error."""
        return self.status in (0, 408, 429) or self.status >= 500


class CredentialsRejected(ApiError):
    """The token endpoint refused the client: the agent was revoked, or enrolled again with a new secret."""


def error_message(body: Any, fallback: str) -> str:
    if isinstance(body, Mapping):
        message = body.get("message") or body.get("error_description") or body.get("error")
        if isinstance(message, list):
            return "; ".join(str(m) for m in message)
        if message:
            return str(message)
    if isinstance(body, str) and body.strip():
        return body.strip()[:500]
    return fallback


def retry_after(headers: Mapping[str, str]) -> float | None:
    """Seconds from ``Retry-After`` (or the throttler's ``Retry-After-<name>`` variants)."""
    for name, value in headers.items():
        if name.lower().startswith("retry-after"):
            try:
                return max(0.0, float(value))
            except (TypeError, ValueError):
                continue
    return None


def _body(response: Any) -> Any:
    try:
        return response.json()
    except ValueError:
        text = getattr(response, "text", "") or ""
        return text[:2000] if text else None


def check_transport(url: str, allow_http: bool) -> None:
    """Refuse plain HTTP except to the local host, or when explicitly allowed for development."""
    parts = urlsplit(url)
    if parts.scheme == "https":
        return
    if parts.scheme == "http" and (allow_http or parts.hostname in ("localhost", "127.0.0.1", "::1")):
        return
    raise ValueError(f"refusing to talk to {parts.scheme}://{parts.hostname}: the agent only uses HTTPS")


class TokenProvider:
    """Bearer tokens from the client-credentials grant, cached until a minute before they expire."""

    def __init__(
        self,
        credentials: Credentials,
        session: Any,
        *,
        verify: bool | str = True,
        timeout: tuple[float, float] = DEFAULT_TIMEOUT,
        monotonic: Any = time.monotonic,
    ) -> None:
        self._credentials = credentials
        self._session = session
        self._verify = verify
        self._timeout = timeout
        self._monotonic = monotonic
        self._lock = threading.Lock()
        self._token: str | None = None
        self._expires = 0.0

    def get(self) -> str:
        with self._lock:
            now = self._monotonic()
            if self._token is None or now >= self._expires:
                self._fetch(now)
            assert self._token is not None
            return self._token

    def invalidate(self) -> None:
        with self._lock:
            self._token = None

    def _fetch(self, now: float) -> None:
        creds = self._credentials
        try:
            response = self._session.request(
                "POST",
                creds.token_endpoint,
                data={"grant_type": "client_credentials", "scope": creds.scope or "lp:ingest"},
                auth=(creds.client_id, creds.client_secret),
                headers={"Accept": "application/json", "User-Agent": USER_AGENT},
                timeout=self._timeout,
                verify=self._verify,
            )
        except requests.RequestException as exc:
            raise ApiError(0, f"token request failed: {type(exc).__name__}") from None
        body = _body(response)
        if response.status_code in (400, 401) and isinstance(body, Mapping) and body.get("error") in (
            "invalid_client",
            "unauthorized_client",
            "invalid_grant",
        ):
            raise CredentialsRejected(
                response.status_code,
                "the server refused the agent's credentials: it was revoked or enrolled again; enrol this box again",
                body=None,
            )
        if response.status_code != 200 or not isinstance(body, Mapping) or not body.get("access_token"):
            raise ApiError(
                response.status_code,
                error_message(body, "token request failed"),
                retry_after=retry_after(response.headers),
            )
        try:
            expires_in = float(body.get("expires_in") or 3600)
        except (TypeError, ValueError):
            expires_in = 3600.0
        self._token = str(body["access_token"])
        self._expires = now + max(30.0, expires_in - 60.0)


class ApiClient:
    """The agent's calls. ``api_url`` is the origin plus the API prefix, e.g. ``https://erp.example.com/api/v1``."""

    def __init__(
        self,
        api_url: str,
        *,
        tokens: TokenProvider | None = None,
        session: Any = None,
        verify: bool | str = True,
        allow_http: bool = False,
    ) -> None:
        check_transport(api_url, allow_http)
        self._allow_http = allow_http
        self._base = api_url.rstrip("/")
        self._origin = origin_of(api_url)
        self._tokens = tokens
        self._session = session if session is not None else requests.Session()
        self._verify = verify

    @property
    def base_url(self) -> str:
        return self._base

    def call(
        self,
        method: str,
        path: str,
        *,
        json: Any = None,
        data: bytes | None = None,
        content_type: str | None = None,
        params: Mapping[str, Any] | None = None,
        timeout: tuple[float, float] = DEFAULT_TIMEOUT,
        auth: bool = True,
    ) -> Any:
        """One request; the parsed JSON body of a 2xx answer, else :class:`ApiError`."""
        url = self._base + path
        for attempt in (1, 2):
            headers = {"Accept": "application/json", "User-Agent": USER_AGENT}
            if auth:
                if self._tokens is None:
                    raise ApiError(0, "the agent is not enrolled")
                headers["Authorization"] = "Bearer " + self._tokens.get()
            if content_type:
                headers["Content-Type"] = content_type
            try:
                response = self._session.request(
                    method,
                    url,
                    json=json,
                    data=data,
                    params=params,
                    headers=headers,
                    timeout=timeout,
                    verify=self._verify,
                )
            except requests.RequestException as exc:
                raise ApiError(0, f"{method} {path} failed: {type(exc).__name__}") from None
            if response.status_code == 401 and auth and attempt == 1 and self._tokens is not None:
                self._tokens.invalidate()
                continue
            body = _body(response)
            if 200 <= response.status_code < 300:
                return body
            raise ApiError(
                response.status_code,
                error_message(body, getattr(response, "reason", "") or "request failed"),
                body=body,
                retry_after=retry_after(response.headers),
            )
        raise ApiError(401, "Authentication required")

    # -- enrolment and configuration ----------------------------------------------------------------------------

    def enrol(self, code: str, hostname: str | None, agent_version: str) -> dict:
        body = {"code": code, "agentVersion": agent_version}
        if hostname:
            body["hostname"] = hostname
        return self.call("POST", f"{LP}/agents/enrol", json=body, auth=False)

    def get_config(self) -> dict:
        return self.call("GET", f"{LP}/agents/config")

    def heartbeat(self, body: dict) -> dict:
        return self.call("POST", f"{LP}/agents/heartbeat", json=body) or {}

    # -- commands ----------------------------------------------------------------------------------------------

    def poll_commands(self, wait_seconds: int) -> list[dict]:
        wait = max(0, int(wait_seconds))
        body = self.call(
            "GET",
            f"{LP}/agents/commands",
            params={"waitSeconds": wait},
            timeout=(10.0, wait + 20.0),
        )
        commands = (body or {}).get("commands") if isinstance(body, Mapping) else None
        return [c for c in commands or [] if isinstance(c, Mapping)]

    def command_result(self, command_id: str, status: str, message: str | None = None, code: str | None = None) -> None:
        body: dict[str, Any] = {"status": status}
        if message:
            body["message"] = message[:1000]
        if code:
            body["code"] = code
        self.call("POST", f"{LP}/agents/commands/{quote(command_id, safe='')}/result", json=body)

    def post_snapshot(self, command_id: str, jpeg: bytes) -> None:
        self.call(
            "POST",
            f"{LP}/agents/snapshots/{quote(command_id, safe='')}",
            data=jpeg,
            content_type="image/jpeg",
            timeout=(10.0, 60.0),
        )

    def post_live_frame(self, session_id: str, jpeg: bytes) -> bool:
        body = self.call(
            "POST",
            f"{LP}/agents/live/{quote(session_id, safe='')}/frames",
            data=jpeg,
            content_type="image/jpeg",
            timeout=(5.0, 15.0),
        )
        return bool((body or {}).get("continue", False)) if isinstance(body, Mapping) else False

    # -- observations and clips ---------------------------------------------------------------------------------

    def send_events(self, events: list[dict]) -> dict:
        return self.call("POST", f"{LP}/events", json={"events": events}, timeout=(10.0, 60.0)) or {}

    def register_clips(self, clips: list[dict]) -> dict:
        return self.call("POST", f"{LP}/agents/clips", json={"clips": clips}) or {}

    def upload_status(self, evidence_id: str) -> dict:
        return self.call("GET", f"{LP}/evidence/{quote(evidence_id, safe='')}/upload") or {}

    def upload_chunk(
        self,
        evidence_id: str,
        offset: int,
        total: int,
        sha256: str,
        chunk: bytes,
        content_type: str | None = None,
    ) -> dict:
        """One chunk. ``content_type`` is the clip's own type; the backend records it for a clip cut from the buffer
        (a clip registered at capture keeps the type it was registered with) and assumes ``video/mp4`` without it."""
        params: dict[str, Any] = {"offset": offset, "total": total, "sha256": sha256}
        if content_type:
            params["contentType"] = content_type
        return (
            self.call(
                "POST",
                f"{LP}/evidence/{quote(evidence_id, safe='')}/upload",
                params=params,
                data=chunk,
                content_type="application/octet-stream",
                timeout=(10.0, 120.0),
            )
            or {}
        )

    # -- downloads ----------------------------------------------------------------------------------------------

    def download(self, url: str, dest: Path, *, max_bytes: int = MAX_MODEL_BYTES) -> str:
        """Stream ``url`` (absolute, or a path on the server) to ``dest``; returns its SHA-256 (lowercase hex).

        The bearer token is sent only to the backend's own origin.
        """
        if "://" not in url:
            url = self._origin + (url if url.startswith("/") else "/" + url)
        check_transport(url, self._allow_http)
        headers = {"User-Agent": USER_AGENT}
        if origin_of(url) == self._origin and self._tokens is not None:
            headers["Authorization"] = "Bearer " + self._tokens.get()
        digest = hashlib.sha256()
        size = 0
        try:
            with self._session.request(
                "GET", url, headers=headers, timeout=(10.0, 120.0), verify=self._verify, stream=True
            ) as response:
                if response.status_code != 200:
                    raise ApiError(response.status_code, f"download failed ({response.status_code})")
                dest.parent.mkdir(parents=True, exist_ok=True)
                with open(dest, "wb") as handle:
                    for block in response.iter_content(1 << 20):
                        if not block:
                            continue
                        size += len(block)
                        if size > max_bytes:
                            raise ApiError(0, "download is larger than allowed")
                        digest.update(block)
                        handle.write(block)
        except requests.RequestException as exc:
            raise ApiError(0, f"download failed: {type(exc).__name__}") from None
        return digest.hexdigest()

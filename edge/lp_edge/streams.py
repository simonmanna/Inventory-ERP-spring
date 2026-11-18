"""Stream addresses, kept on this box only.

The ERP knows a camera by a **stream alias** (``till-1``). The address and its password are entered here, in a YAML
file readable by the agent's user only (``<data>/streams.yaml``, or ``LP_EDGE_STREAMS_FILE``)::

    streams:
      till-1: rtsp://viewer:secret@192.168.1.20:554/Streaming/Channels/102
      bar-1: rtsp://viewer:secret@192.168.1.21:554/stream2

``lp-edge streams set till-1`` asks for the address without echoing it. The file is read again when it changes.
Addresses never appear in a request, an answer or a log.
"""

from __future__ import annotations

import logging
import os
import re
import threading
from pathlib import Path
from urllib.parse import urlsplit

import yaml

from lp_edge.files import write_private

log = logging.getLogger(__name__)

ALIAS = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
SCHEMES = ("rtsp", "rtsps", "http", "https", "file")


def check_alias(alias: str) -> str:
    if not ALIAS.match(alias):
        raise ValueError("a stream alias is 1 to 64 lowercase letters, digits, '-' or '_'")
    return alias


def check_url(url: str) -> str:
    url = url.strip()
    scheme = urlsplit(url).scheme.lower()
    if scheme not in SCHEMES:
        raise ValueError("the stream address must start with rtsp://, rtsps://, http://, https:// or file:")
    return url


def describe(url: str) -> str:
    """What may be shown about an address: its scheme, and whether it carries credentials."""
    parts = urlsplit(url)
    credentials = " with credentials" if (parts.username or parts.password) else ""
    return f"{parts.scheme}{credentials}"


class StreamRegistry:
    def __init__(self, path: Path) -> None:
        self._path = path
        self._lock = threading.Lock()
        self._streams: dict[str, str] = {}
        self._mtime: float | None = None

    def url_for(self, alias: str) -> str | None:
        self._reload()
        return self._streams.get(alias)

    def aliases(self) -> list[str]:
        self._reload()
        return sorted(self._streams)

    def set(self, alias: str, url: str) -> None:
        check_alias(alias)
        check_url(url)
        with self._lock:
            streams = self._read()
            streams[alias] = url.strip()
            self._write(streams)

    def remove(self, alias: str) -> bool:
        with self._lock:
            streams = self._read()
            if alias not in streams:
                return False
            del streams[alias]
            self._write(streams)
            return True

    def _reload(self) -> None:
        with self._lock:
            try:
                mtime = self._path.stat().st_mtime
            except FileNotFoundError:
                self._streams, self._mtime = {}, None
                return
            if mtime == self._mtime:
                return
            self._streams = self._read()
            self._mtime = mtime

    def _read(self) -> dict[str, str]:
        try:
            raw = yaml.safe_load(self._path.read_text(encoding="utf-8")) or {}
        except FileNotFoundError:
            return {}
        except (OSError, yaml.YAMLError) as exc:
            log.error("the streams file cannot be read: %s", type(exc).__name__)
            return {}
        self._warn_if_readable()
        streams: dict[str, str] = {}
        entries = raw.get("streams") if isinstance(raw, dict) else None
        for alias, value in (entries or {}).items():
            url = value.get("url") if isinstance(value, dict) else value
            try:
                streams[check_alias(str(alias))] = check_url(str(url))
            except ValueError as exc:
                log.error("stream %s in the streams file is ignored: %s", alias, exc)
        return streams

    def _write(self, streams: dict[str, str]) -> None:
        header = "# Stream addresses of this box. They never leave it. Managed with `lp-edge streams`.\n"
        body = yaml.safe_dump({"streams": dict(sorted(streams.items()))}, default_flow_style=False, sort_keys=True)
        write_private(self._path, (header + body).encode("utf-8"))
        self._mtime = None

    def _warn_if_readable(self) -> None:
        try:
            mode = os.stat(self._path).st_mode
        except OSError:
            return
        if mode & 0o077:
            log.warning("the streams file can be read by other users; run: chmod 600 %s", self._path)

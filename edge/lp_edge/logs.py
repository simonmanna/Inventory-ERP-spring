"""Logging that never writes a secret.

The agent's own code never logs a stream address, a token or a client secret. As a second line of defence every log
line goes through :func:`redact`, which masks credentials in URLs, bearer and basic tokens, and secret-looking
``key=value`` pairs.
"""

from __future__ import annotations

import json
import logging
import os
import re
import sys
from datetime import datetime, timezone

_RULES: list[tuple[re.Pattern[str], str]] = [
    # user:password@ (or a token@) in any URL
    (re.compile(r"(?i)\b([a-z][a-z0-9+.\-]*://)[^\s/?#@]+@"), r"\1***@"),
    (re.compile(r"(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=\-]+"), r"\1 ***"),
    (
        re.compile(
            r"(?i)((?:client_?secret|access_?token|refresh_?token|password|passwd|pwd)[\"']?\s*[:=]\s*[\"']?)"
            r"[^\"'\s,&;}]+"
        ),
        r"\1***",
    ),
]


def redact(text: str) -> str:
    """``text`` with credentials masked."""
    for pattern, replacement in _RULES:
        text = pattern.sub(replacement, text)
    return text


class TextFormatter(logging.Formatter):
    def __init__(self) -> None:
        super().__init__("%(asctime)s %(levelname)s %(name)s: %(message)s")

    def format(self, record: logging.LogRecord) -> str:
        return redact(super().format(record))


class JsonFormatter(logging.Formatter):
    """One JSON object per line, for log shippers."""

    def format(self, record: logging.LogRecord) -> str:
        entry = {
            "time": datetime.fromtimestamp(record.created, tz=timezone.utc).isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        if record.exc_info:
            entry["exception"] = self.formatException(record.exc_info)
        return redact(json.dumps(entry, ensure_ascii=False))


class StderrHandler(logging.StreamHandler):
    """Writes to whatever ``sys.stderr`` is when a line is logged (not the stream of the moment it was set up)."""

    def __init__(self) -> None:
        logging.Handler.__init__(self)

    @property
    def stream(self):  # type: ignore[override]
        return sys.stderr


def setup(level: str = "INFO", fmt: str = "text") -> None:
    """Send the agent's logs to stderr, masked, as text or JSON lines. A second call replaces the first."""
    handler = StderrHandler()
    handler.setFormatter(JsonFormatter() if fmt == "json" else TextFormatter())
    root = logging.getLogger()
    for existing in list(root.handlers):
        if isinstance(existing, StderrHandler):
            root.removeHandler(existing)
    root.addHandler(handler)
    root.setLevel(getattr(logging, level.upper(), logging.INFO))
    # urllib3 logs full URLs at DEBUG; keep it quiet.
    logging.getLogger("urllib3").setLevel(logging.WARNING)


def quiet_native_video_logs() -> None:
    """OpenCV and FFmpeg print to stderr on their own; keep them to errors only (set before cv2 is imported)."""
    os.environ.setdefault("OPENCV_LOG_LEVEL", "ERROR")
    os.environ.setdefault("OPENCV_FFMPEG_LOGLEVEL", "-8")
    # RTSP over TCP: fewer broken frames through NAT and Wi-Fi than UDP.
    os.environ.setdefault("OPENCV_FFMPEG_CAPTURE_OPTIONS", "rtsp_transport;tcp")

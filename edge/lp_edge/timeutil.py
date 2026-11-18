"""Time helpers: a clock that tests can replace, and the ISO-8601 instants the ledger accepts."""

from __future__ import annotations

import re
import time
from datetime import datetime, timezone
from typing import Protocol

UTC = timezone.utc
_LONG_FRACTION = re.compile(r"(\.\d{6})\d+")


class Clock(Protocol):
    """Wall time in epoch seconds, and a monotonic time for intervals."""

    def time(self) -> float: ...

    def monotonic(self) -> float: ...


class SystemClock:
    """The host clock."""

    def time(self) -> float:
        return time.time()

    def monotonic(self) -> float:
        return time.monotonic()


def iso(ts: float) -> str:
    """Epoch seconds as a UTC instant with milliseconds, e.g. ``2026-11-17T10:00:00.123Z``."""
    dt = datetime.fromtimestamp(ts, tz=UTC)
    return f"{dt:%Y-%m-%dT%H:%M:%S}.{dt.microsecond // 1000:03d}Z"


def iso_or_none(ts: float | None) -> str | None:
    return None if ts is None else iso(ts)


def parse_iso(value: str) -> float:
    """An ISO-8601 instant with an offset (``Z`` or ``+hh:mm``) as epoch seconds."""
    if not isinstance(value, str) or not value.strip():
        raise ValueError("instant is empty")
    text = value.strip()
    if text[-1] in "Zz":
        text = text[:-1] + "+00:00"
    text = _LONG_FRACTION.sub(r"\1", text)
    dt = datetime.fromisoformat(text)
    if dt.tzinfo is None:
        raise ValueError(f"instant has no offset: {value!r}")
    return dt.timestamp()

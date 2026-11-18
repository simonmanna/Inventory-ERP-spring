"""What every pour-meter adapter shares: the reading, units, field mapping, vendor times and the adapter contract.

An adapter turns the vendor's records into :class:`Reading` objects. It never knows the ledger's device ids: the
runner maps ``external_id`` to the device the backend lists for this agent.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import sqlite3
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone, tzinfo
from typing import Any

from lp_edge.events import MAX_ID_LENGTH, MAX_POUR_ML
from lp_edge.store import Store

UTC = timezone.utc

#: Millilitres in one unit of each volume unit an adapter may read.
UNITS: dict[str, float] = {
    "ml": 1.0,
    "cl": 10.0,
    "l": 1000.0,
    "oz_us": 29.5735295625,
    "oz_uk": 28.4130625,
}

#: An epoch number above this is in milliseconds (in seconds it would be past the year 5000).
_EPOCH_MS_ABOVE = 1e11
_NUMBER = re.compile(r"^[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?$")


class ConfigError(ValueError):
    """The adapter configuration on this box is wrong."""


class AdapterError(Exception):
    """An adapter could not read its source this time (unreachable, unreadable, not the expected shape)."""


class BadRecord(ValueError):
    """One vendor record could not be read; it is counted and skipped."""


@dataclass(frozen=True)
class Reading:
    """One pour as the vendor reported it, in millilitres and epoch seconds."""

    external_id: str
    volume_ml: float
    started_at: float | None
    ended_at: float | None
    product_id: str | None
    vendor_id: str | None
    raw_hash: str


def to_ml(value: float, unit: str) -> float:
    """``value`` in ``unit`` as millilitres. An unknown unit is a configuration error."""
    factor = UNITS.get(unit)
    if factor is None:
        raise ConfigError(f"unit must be one of {', '.join(UNITS)}")
    return float(value) * factor


def pick(record: Mapping[str, Any], path: str) -> Any:
    """The value at ``path`` in ``record``: a key, or a dotted path into nested objects (``pour.volume``)."""
    if path in record:
        return record[path]
    value: Any = record
    for part in path.split("."):
        if not isinstance(value, Mapping) or part not in value:
            return None
        value = value[part]
    return value


def parse_time(value: Any, tz: tzinfo = UTC) -> float:
    """A vendor time as epoch seconds: ISO-8601 (a time without offset is in ``tz``), or epoch seconds or ms."""
    if isinstance(value, bool) or value is None:
        raise BadRecord("time is missing")
    if isinstance(value, str):
        text = value.strip()
        if not text:
            raise BadRecord("time is empty")
        if _NUMBER.match(text):
            value = float(text)
        else:
            if text[-1] in "Zz":
                text = text[:-1] + "+00:00"
            text = re.sub(r"(\.\d{6})\d+", r"\1", text)
            try:
                dt = datetime.fromisoformat(text)
            except ValueError as exc:
                raise BadRecord(f"time {value!r} is not ISO-8601") from exc
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=tz)
            return dt.timestamp()
    if not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
        raise BadRecord(f"time {value!r} is not a time")
    return float(value) / 1000.0 if value > _EPOCH_MS_ABOVE else float(value)


def raw_hash(record: Mapping[str, Any]) -> str:
    """The first 16 hex digits of the SHA-256 of the record (keys sorted): the reading's id when the vendor has none."""
    text = json.dumps(record, sort_keys=True, separators=(",", ":"), ensure_ascii=False, default=str)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


@dataclass(frozen=True)
class FieldMap:
    """The vendor's names for the fields of a reading."""

    external_id: str
    volume: str
    id: str | None = None
    started_at: str | None = None
    ended_at: str | None = None
    at: str | None = None
    product_id: str | None = None

    @classmethod
    def parse(cls, data: Any) -> FieldMap:
        if not isinstance(data, Mapping):
            raise ConfigError("fields must be an object")
        known = {"externalId", "volume", "id", "startedAt", "endedAt", "at", "productId"}
        unknown = set(data) - known
        if unknown:
            raise ConfigError(f"fields has unknown keys: {', '.join(sorted(unknown))}")
        for name, value in data.items():
            if not isinstance(value, str) or not value.strip():
                raise ConfigError(f"fields.{name} must be a field name")
        for name in ("externalId", "volume"):
            if name not in data:
                raise ConfigError(f"fields.{name} is required")
        return cls(
            external_id=data["externalId"],
            volume=data["volume"],
            id=data.get("id"),
            started_at=data.get("startedAt"),
            ended_at=data.get("endedAt"),
            at=data.get("at"),
            product_id=data.get("productId"),
        )


@dataclass(frozen=True)
class AdapterSpec:
    """One entry of the adapters file, checked."""

    name: str
    type: str
    fields: FieldMap
    poll_seconds: float = 5.0
    unit: str = "ml"
    tz: tzinfo = UTC
    gap_after_seconds: float = 60.0
    all_devices: bool = False
    options: Mapping[str, Any] = field(default_factory=dict)


def _optional_text(value: Any, name: str) -> str | None:
    if value is None:
        return None
    text = value.strip() if isinstance(value, str) else ""
    if not text:
        raise BadRecord(f"{name} is not text")
    return text


class Adapter:
    """Reads one vendor source. ``poll`` raises :class:`AdapterError` when the source cannot be read at all.

    After a poll the runner stores the readings and calls ``stage(conn)`` inside the same SQLite transaction (cursors,
    processed-file markers), then ``finish()`` once it is committed (moving files away).
    """

    def __init__(self, spec: AdapterSpec, store: Store) -> None:
        self.spec = spec
        self.store = store
        self.bad_records = 0

    @property
    def name(self) -> str:
        return self.spec.name

    def poll(self, now: float) -> list[Reading]:
        raise NotImplementedError

    def stage(self, conn: sqlite3.Connection) -> None:
        """Record, in the outbox transaction, that the last poll's readings are stored."""

    def finish(self) -> None:
        """Clean up after the last poll's readings were committed."""

    def meta_key(self, suffix: str) -> str:
        return f"meter:{self.name}:{suffix}"

    def to_reading(self, record: Any) -> Reading:
        """The vendor ``record`` as a reading; raises :class:`BadRecord` when it does not hold one."""
        if not isinstance(record, Mapping):
            raise BadRecord("a record is not an object")
        spec = self.spec
        fields = spec.fields
        external = pick(record, fields.external_id)
        if isinstance(external, bool) or external is None or not str(external).strip():
            raise BadRecord("the device id is missing")
        external_id = str(external).strip()
        if len(external_id) > MAX_ID_LENGTH:
            raise BadRecord("the device id is too long")
        volume = pick(record, fields.volume)
        if isinstance(volume, str) and _NUMBER.match(volume.strip()):
            volume = float(volume.strip())
        if isinstance(volume, bool) or not isinstance(volume, (int, float)) or not math.isfinite(volume):
            raise BadRecord("the volume is not a number")
        volume_ml = round(to_ml(volume, spec.unit), 3)
        if not 0 < volume_ml <= MAX_POUR_ML:
            raise BadRecord(f"the volume {volume_ml} ml is not above 0 and at most {MAX_POUR_ML}")
        started = self._time(record, fields.started_at)
        ended = self._time(record, fields.ended_at)
        if ended is None:
            ended = self._time(record, fields.at)
        if started is not None and ended is not None and ended < started:
            raise BadRecord("the pour ends before it starts")
        vendor = pick(record, fields.id) if fields.id else None
        vendor_text = "" if vendor is None or isinstance(vendor, bool) else str(vendor).strip()
        vendor_id = vendor_text or None
        product_id = _optional_text(pick(record, fields.product_id), "productId") if fields.product_id else None
        if product_id is not None and len(product_id) > MAX_ID_LENGTH:
            raise BadRecord("productId is too long")
        return Reading(
            external_id=external_id,
            volume_ml=volume_ml,
            started_at=started,
            ended_at=ended,
            product_id=product_id,
            vendor_id=vendor_id,
            raw_hash=raw_hash(record),
        )

    def _time(self, record: Mapping[str, Any], path: str | None) -> float | None:
        if path is None:
            return None
        value = pick(record, path)
        if value is None or value == "":
            return None
        return parse_time(value, self.spec.tz)

"""The adapters file (``LP_MEASUREMENT_ADAPTERS``): which vendor sources this box reads, and how.

A JSON array of entries. An entry that is not right is logged and skipped; the others still run. A file that cannot
be read at all is an error for the caller to report.
"""

from __future__ import annotations

import json
import logging
import math
import re
from collections.abc import Mapping
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from lp_edge.measurements.base import UNITS, UTC, Adapter, AdapterSpec, ConfigError, FieldMap
from lp_edge.measurements.csv_dir import CsvDirAdapter
from lp_edge.measurements.http_poll import Fetch, HttpPollAdapter
from lp_edge.measurements.jsonl_dir import JsonlDirAdapter
from lp_edge.store import Store

log = logging.getLogger(__name__)

NAME = re.compile(r"^[a-z0-9_-]{1,40}$")
TYPES = ("jsonl_dir", "csv_dir", "http_poll")
KEYS = frozenset(
    {
        "name", "type", "path", "glob", "url", "itemsKey", "cursorParam", "pollSeconds", "unit", "timezone",
        "gapAfterSeconds", "fields", "devices", "delimiter",
    }
)
OPTION_KEYS = ("path", "glob", "url", "itemsKey", "cursorParam", "delimiter")


def _seconds(entry: Mapping[str, Any], name: str, default: float, low: float, high: float) -> float:
    value = entry.get(name, default)
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ConfigError(f"{name} must be a number")
    if not low <= value <= high:
        raise ConfigError(f"{name} must be between {low:g} and {high:g}")
    return float(value)


def parse_spec(entry: Any) -> AdapterSpec:
    """One entry of the file, checked; raises :class:`ConfigError` with what is wrong."""
    if not isinstance(entry, Mapping):
        raise ConfigError("an adapter entry must be an object")
    name = entry.get("name")
    if not isinstance(name, str) or not NAME.match(name):
        raise ConfigError("name must be 1 to 40 of a-z, 0-9, _ and -")
    unknown = set(entry) - KEYS
    if unknown:
        raise ConfigError(f"unknown keys: {', '.join(sorted(unknown))}")
    adapter_type = entry.get("type")
    if adapter_type not in TYPES:
        raise ConfigError(f"type must be one of {', '.join(TYPES)}")
    unit = entry.get("unit", "ml")
    if unit not in UNITS:
        raise ConfigError(f"unit must be one of {', '.join(UNITS)}")
    tz_name = entry.get("timezone")
    tz = UTC
    if tz_name is not None:
        if not isinstance(tz_name, str) or not tz_name:
            raise ConfigError("timezone must be an IANA zone name")
        if tz_name.upper() not in ("UTC", "Z"):
            try:
                tz = ZoneInfo(tz_name)
            except (ZoneInfoNotFoundError, ValueError) as exc:
                raise ConfigError(f"timezone {tz_name!r} is not known on this box") from exc
    devices = entry.get("devices")
    if devices not in (None, "*"):
        raise ConfigError('devices must be "*" when given')
    return AdapterSpec(
        name=name,
        type=adapter_type,
        fields=FieldMap.parse(entry.get("fields")),
        poll_seconds=_seconds(entry, "pollSeconds", 5.0, 1.0, 3600.0),
        unit=unit,
        tz=tz,
        gap_after_seconds=_seconds(entry, "gapAfterSeconds", 60.0, 5.0, 86_400.0),
        all_devices=devices == "*",
        options={key: entry[key] for key in OPTION_KEYS if key in entry},
    )


def build_adapter(spec: AdapterSpec, store: Store, fetch: Fetch | None = None) -> Adapter:
    if spec.type == "jsonl_dir":
        return JsonlDirAdapter(spec, store)
    if spec.type == "csv_dir":
        return CsvDirAdapter(spec, store)
    if spec.type == "http_poll":
        return HttpPollAdapter(spec, store, fetch=fetch)
    raise ConfigError(f"unknown type {spec.type!r}")


def load_adapters(entries: Any, store: Store, fetch: Fetch | None = None) -> list[Adapter]:
    """The adapters of the parsed file; a wrong entry is logged and skipped."""
    if not isinstance(entries, list):
        raise ConfigError("the adapters file must hold a JSON array")
    adapters: list[Adapter] = []
    names: set[str] = set()
    for index, entry in enumerate(entries):
        label = entry.get("name") if isinstance(entry, Mapping) and isinstance(entry.get("name"), str) else f"#{index}"
        try:
            spec = parse_spec(entry)
            if spec.name in names:
                raise ConfigError("the name is used by an earlier adapter")
            adapters.append(build_adapter(spec, store, fetch))
        except ConfigError as exc:
            log.error("meter adapter %s ignored: %s", label, exc)
            continue
        names.add(spec.name)
    return adapters


def load_adapters_file(path: Path, store: Store, fetch: Fetch | None = None) -> list[Adapter]:
    """The adapters of the file at ``path``; raises :class:`ConfigError` when it cannot be read."""
    try:
        entries = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ConfigError(f"the meter adapters file {path} could not be read: {exc.strerror or exc}") from exc
    except ValueError as exc:
        raise ConfigError(f"the meter adapters file {path} is not JSON: {exc}") from exc
    return load_adapters(entries, store, fetch)

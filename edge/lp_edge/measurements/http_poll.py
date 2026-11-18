"""``http_poll``: a local JSON endpoint of the vendor's software, read with a cursor.

The endpoint answers a JSON array of records, or an object holding the array under ``itemsKey``. When
``cursorParam`` is set, each request carries the cursor in that query parameter: the vendor id of the last record
seen when ``fields.id`` is mapped, else the latest pour time seen (ISO-8601, UTC). The cursor is stored in the agent's
SQLite store in the same transaction as the readings, so a restart neither skips nor (beyond the idempotent
``sourceEventId``) repeats a reading.
"""

from __future__ import annotations

import json
import logging
import sqlite3
import urllib.parse
import urllib.request
from collections.abc import Callable, Mapping

from lp_edge.measurements.base import Adapter, AdapterError, AdapterSpec, BadRecord, ConfigError, Reading, pick
from lp_edge.store import Store
from lp_edge.timeutil import iso

log = logging.getLogger(__name__)

TIMEOUT_SECONDS = 5.0
MAX_BYTES = 10 * 1024 * 1024

#: ``fetch(url, timeout)`` returns the body of a successful GET, or raises.
Fetch = Callable[[str, float], bytes]


def urllib_fetch(url: str, timeout: float) -> bytes:
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310 - a configured local URL
        body = response.read(MAX_BYTES + 1)
    if len(body) > MAX_BYTES:
        raise AdapterError(f"the answer is larger than {MAX_BYTES} bytes")
    return body


def with_param(url: str, name: str, value: str) -> str:
    """``url`` with the query parameter ``name`` set to ``value`` (replacing it when present)."""
    parts = urllib.parse.urlsplit(url)
    query = [(k, v) for k, v in urllib.parse.parse_qsl(parts.query, keep_blank_values=True) if k != name]
    query.append((name, value))
    return urllib.parse.urlunsplit(parts._replace(query=urllib.parse.urlencode(query)))


class HttpPollAdapter(Adapter):
    def __init__(self, spec: AdapterSpec, store: Store, fetch: Fetch | None = None) -> None:
        super().__init__(spec, store)
        url = spec.options.get("url")
        if not isinstance(url, str) or urllib.parse.urlsplit(url).scheme not in ("http", "https"):
            raise ConfigError("url must be an http:// or https:// address")
        self.url = url
        items_key = spec.options.get("itemsKey")
        if items_key is not None and (not isinstance(items_key, str) or not items_key):
            raise ConfigError("itemsKey must be a field name")
        self.items_key = items_key
        cursor_param = spec.options.get("cursorParam")
        if cursor_param is not None and (not isinstance(cursor_param, str) or not cursor_param):
            raise ConfigError("cursorParam must be a query parameter name")
        self.cursor_param = cursor_param
        self.fetch = fetch or urllib_fetch
        self._next_cursor: str | None = None

    @property
    def cursor(self) -> str | None:
        return self.store.get_meta(self.meta_key("cursor"))

    def poll(self, now: float) -> list[Reading]:
        self._next_cursor = None
        url = self.url
        cursor = self.cursor
        if self.cursor_param and cursor is not None:
            url = with_param(url, self.cursor_param, cursor)
        try:
            body = self.fetch(url, TIMEOUT_SECONDS)
        except AdapterError:
            raise
        except Exception as exc:  # noqa: BLE001 - any failure of the source is the adapter being down
            raise AdapterError(f"the endpoint could not be read: {exc}") from exc
        try:
            data = json.loads(body)
        except ValueError as exc:
            raise AdapterError("the endpoint did not answer JSON") from exc
        items = pick(data, self.items_key) if self.items_key and isinstance(data, Mapping) else data
        if not isinstance(items, list):
            raise AdapterError("the endpoint did not answer a list of readings")
        readings: list[Reading] = []
        last_id: str | None = None
        latest: float | None = None
        bad = 0
        for item in items:
            if self.spec.fields.id and isinstance(item, Mapping):
                vendor_id = pick(item, self.spec.fields.id)
                if vendor_id is not None and str(vendor_id).strip():
                    last_id = str(vendor_id).strip()
            try:
                reading = self.to_reading(item)
            except BadRecord as exc:
                bad += 1
                log.debug("meter adapter %s: a record skipped: %s", self.name, exc)
                continue
            readings.append(reading)
            at = reading.ended_at if reading.ended_at is not None else reading.started_at
            if at is not None and (latest is None or at > latest):
                latest = at
        if bad:
            self.bad_records += bad
            log.warning("meter adapter %s: %d records could not be read and were skipped", self.name, bad)
        if self.spec.fields.id:
            self._next_cursor = last_id
        elif latest is not None:
            self._next_cursor = iso(latest)
        return readings

    def stage(self, conn: sqlite3.Connection) -> None:
        if self._next_cursor is not None:
            conn.execute(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                (self.meta_key("cursor"), self._next_cursor),
            )

    def finish(self) -> None:
        self._next_cursor = None

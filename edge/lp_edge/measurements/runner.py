"""Runs the meter adapters: polls each when due, turns readings into ledger events, reports adapter outages.

Readings are queued in the agent's outbox under the key ``meter:<adapter>`` (in place of a camera id), in one
transaction with the adapter's cursor or processed-file marker. A reading whose ``externalId`` is not one of this
agent's devices in the configuration (or whose device is disabled) is counted and dropped, never sent: the backend
would refuse it.

An adapter that fails without a break for ``gapAfterSeconds`` reports ``measurement.gap`` ``adapter_down`` for each
of its devices once it reads again, at shutdown, and every :data:`GAP_REPORT_SECONDS` while it is still failing. Its
devices are the configured devices whose ``externalId`` it has produced at least once (remembered in the store), or
every device of the agent when its entry says ``"devices": "*"``.
"""

from __future__ import annotations

import hashlib
import json
import logging
import threading
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from typing import Any

from lp_edge.events import MAX_ID_LENGTH, MEASUREMENT_GAP, POUR_MEASURED, measurement_envelope
from lp_edge.measurements.base import Adapter, Reading
from lp_edge.outbox import Outbox
from lp_edge.remote_config import MeasurementDevice
from lp_edge.store import Store
from lp_edge.timeutil import Clock, iso, iso_or_none

log = logging.getLogger(__name__)

OUTBOX_PREFIX = "meter:"
GAP_REPORT_SECONDS = 900.0
UNKNOWN_LOG_SECONDS = 3600.0
TICK_SECONDS = 1.0


@dataclass
class _AdapterState:
    adapter: Adapter
    known: set[str] = field(default_factory=set)
    due: float = 0.0
    failing_since: float | None = None
    gap_from: float | None = None
    last_error: str | None = None
    last_success_at: float | None = None
    failures: int = 0


class MeasurementRunner:
    def __init__(
        self,
        adapters: Iterable[Adapter],
        store: Store,
        outbox: Outbox,
        *,
        agent_id: str,
        branch_id: Callable[[], str],
        on_queued: Callable[[], None] = lambda: None,
    ) -> None:
        self.store = store
        self.outbox = outbox
        self.agent_id = agent_id
        self._branch_id = branch_id
        self._on_queued = on_queued
        self._states = [_AdapterState(a, known=self._load_known(a)) for a in adapters]
        self._devices: dict[str, MeasurementDevice] | None = None  # externalId -> device; None until configured
        self._lock = threading.Lock()
        self._unknown_logged: dict[str, float] = {}
        self.readings_queued = 0
        self.unknown_devices = 0
        self.disabled_devices = 0
        self.gaps_queued = 0

    @property
    def adapters(self) -> list[Adapter]:
        return [s.adapter for s in self._states]

    # -- configuration ---------------------------------------------------------------------------------------------

    def set_devices(self, devices: Iterable[MeasurementDevice]) -> None:
        """The devices the backend lists for this agent; nothing is read before the first call."""
        mapping = {d.external_id: d for d in devices}
        with self._lock:
            self._devices = mapping

    def _load_known(self, adapter: Adapter) -> set[str]:
        raw = self.store.get_meta(adapter.meta_key("devices"))
        try:
            known = json.loads(raw) if raw else []
        except ValueError:
            known = []
        return {str(k) for k in known} if isinstance(known, list) else set()

    # -- polling ---------------------------------------------------------------------------------------------------

    def run(self, stop: threading.Event, clock: Clock, paused: Callable[[], bool] = lambda: False) -> None:
        """Poll until ``stop``; nothing is read while ``paused()`` (the box's clock is off). Reports gaps at the end."""
        while not stop.is_set():
            if not paused():
                try:
                    self.poll_once(clock.time())
                except Exception:  # noqa: BLE001 - the thread must survive anything
                    log.exception("reading the meters failed")
            stop.wait(TICK_SECONDS)
        try:
            self.flush_gaps(clock.time())
        except Exception:  # noqa: BLE001
            log.exception("the meter gaps could not be stored at shutdown")

    def poll_once(self, now: float) -> int:
        """Poll every adapter that is due; the number of events queued."""
        with self._lock:
            devices = self._devices
        if devices is None:
            return 0
        queued = 0
        for state in self._states:
            if now < state.due:
                continue
            state.due = now + state.adapter.spec.poll_seconds
            queued += self._poll(state, devices, now)
        return queued

    def _poll(self, state: _AdapterState, devices: dict[str, MeasurementDevice], now: float) -> int:
        adapter = state.adapter
        try:
            readings = adapter.poll(now)
        except Exception as exc:  # noqa: BLE001 - any failure of a source is that adapter being down
            return self._failed(state, devices, now, exc)
        events, learned = self._reading_events(state, readings, devices, now)
        queued = 0
        with self.store.transaction() as conn:
            for event in events:
                if self.outbox.add(event, OUTBOX_PREFIX + adapter.name, conn=conn, ignore_duplicate=True):
                    queued += 1
            if learned:
                state.known |= learned
                conn.execute(
                    "INSERT INTO meta (key, value) VALUES (?, ?) "
                    "ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                    (adapter.meta_key("devices"), json.dumps(sorted(state.known))),
                )
            adapter.stage(conn)
        adapter.finish()
        self.readings_queued += queued
        if state.failing_since is not None:
            queued += self._recovered(state, devices, now)
        state.last_success_at = now
        if queued:
            self._on_queued()
        return queued

    def _reading_events(
        self, state: _AdapterState, readings: list[Reading], devices: dict[str, MeasurementDevice], now: float
    ) -> tuple[list[dict[str, Any]], set[str]]:
        events: list[dict[str, Any]] = []
        learned: set[str] = set()
        branch_id = self._branch_id()
        for reading in readings:
            device = devices.get(reading.external_id)
            if device is None:
                self.unknown_devices += 1
                last = self._unknown_logged.get(reading.external_id)
                if last is None or now - last >= UNKNOWN_LOG_SECONDS:
                    self._unknown_logged[reading.external_id] = now
                    log.warning("meter adapter %s: device %r is not one of this agent's devices; its readings are "
                                "dropped", state.adapter.name, reading.external_id[:200])
                continue
            if reading.external_id not in state.known:
                learned.add(reading.external_id)
            if not device.enabled:
                self.disabled_devices += 1
                continue
            try:
                events.append(self._reading_event(state.adapter, reading, device, branch_id, now))
            except ValueError as exc:
                state.adapter.bad_records += 1
                log.warning("meter adapter %s: a reading of %s was dropped: %s", state.adapter.name, device.id, exc)
        return events, learned

    def source_event_id(self, adapter_name: str, reading: Reading) -> str:
        """``<agentId>:meter:<adapter>:<vendor id, else hash of the reading>``: the same vendor record is one event."""
        prefix = f"{self.agent_id}:meter:{adapter_name}:"
        key = reading.vendor_id or reading.raw_hash
        if len(prefix) + len(key) > MAX_ID_LENGTH:
            key = hashlib.sha256(key.encode("utf-8")).hexdigest()[:16]
        return prefix + key

    def _reading_event(
        self, adapter: Adapter, reading: Reading, device: MeasurementDevice, branch_id: str, now: float
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {"volumeMl": reading.volume_ml}
        if reading.product_id:
            payload["productId"] = reading.product_id
        if reading.started_at is not None:
            payload["startedAt"] = iso(reading.started_at)
        if reading.ended_at is not None:
            payload["endedAt"] = iso(reading.ended_at)
        if reading.vendor_id and len(reading.vendor_id) <= MAX_ID_LENGTH:
            payload["vendorEventId"] = reading.vendor_id
        if reading.ended_at is not None:
            occurred = reading.ended_at
        elif reading.started_at is not None:
            occurred = reading.started_at
        else:
            occurred = now
        return measurement_envelope(
            POUR_MEASURED,
            payload,
            device_id=device.id,
            occurred_at=occurred,
            source_event_id=self.source_event_id(adapter.name, reading),
            branch_id=branch_id,
        )

    # -- outages ---------------------------------------------------------------------------------------------------

    def _failed(self, state: _AdapterState, devices: dict[str, MeasurementDevice], now: float, exc: Exception) -> int:
        state.failures += 1
        state.last_error = str(exc)[:500]
        if state.failing_since is None:
            state.failing_since = now
            state.gap_from = now
            log.warning("meter adapter %s cannot read its source: %s", state.adapter.name, state.last_error)
            return 0
        assert state.gap_from is not None
        if now - state.failing_since >= state.adapter.spec.gap_after_seconds and now - state.gap_from >= (
            GAP_REPORT_SECONDS
        ):
            queued = self._queue_gap(state, devices, state.gap_from, now)
            state.gap_from = now
            if queued:
                self._on_queued()
            return queued
        return 0

    def _recovered(self, state: _AdapterState, devices: dict[str, MeasurementDevice], now: float) -> int:
        assert state.failing_since is not None and state.gap_from is not None
        queued = 0
        if now - state.failing_since >= state.adapter.spec.gap_after_seconds:
            queued = self._queue_gap(state, devices, state.gap_from, now)
        log.info("meter adapter %s reads its source again (down for %.0f s)", state.adapter.name,
                 now - state.failing_since)
        state.failing_since = state.gap_from = None
        state.last_error = None
        return queued

    def flush_gaps(self, now: float) -> int:
        """At shutdown: report the outage of every adapter failing for at least its ``gapAfterSeconds``."""
        with self._lock:
            devices = self._devices
        if devices is None:
            return 0
        queued = 0
        for state in self._states:
            if state.failing_since is None or state.gap_from is None:
                continue
            if now - state.failing_since >= state.adapter.spec.gap_after_seconds and now > state.gap_from:
                queued += self._queue_gap(state, devices, state.gap_from, now)
                state.gap_from = now
        return queued

    def _gap_devices(self, state: _AdapterState, devices: dict[str, MeasurementDevice]) -> list[MeasurementDevice]:
        if state.adapter.spec.all_devices:
            chosen = list(devices.values())
        else:
            chosen = [d for ext, d in devices.items() if ext in state.known]
        return sorted((d for d in chosen if d.enabled), key=lambda d: d.id)

    def _queue_gap(
        self, state: _AdapterState, devices: dict[str, MeasurementDevice], start: float, end: float
    ) -> int:
        adapter = state.adapter
        targets = self._gap_devices(state, devices)
        if not targets:
            log.info("meter adapter %s was down from %s to %s; no device of it to report", adapter.name, iso(start),
                     iso(end))
            return 0
        branch_id = self._branch_id()
        payload = {"from": iso(start), "to": iso(end), "reason": "adapter_down"}
        queued = 0
        with self.store.transaction() as conn:
            for device in targets:
                event = measurement_envelope(
                    MEASUREMENT_GAP,
                    dict(payload),
                    device_id=device.id,
                    occurred_at=start,
                    source_event_id=f"{self.agent_id}:meter:{adapter.name}:gap:{device.id}:{int(start * 1000)}",
                    branch_id=branch_id,
                )
                if self.outbox.add(event, OUTBOX_PREFIX + adapter.name, conn=conn, ignore_duplicate=True):
                    queued += 1
        self.gaps_queued += queued
        log.warning("meter adapter %s was down from %s to %s: reported for %d devices", adapter.name, iso(start),
                    iso(end), queued)
        return queued

    # -- state -----------------------------------------------------------------------------------------------------

    def holdback(self) -> float | None:
        """The earliest instant not yet reported of a failing adapter: the agent's watermark must not pass it."""
        marks = [s.gap_from for s in self._states if s.gap_from is not None]
        return min(marks) if marks else None

    def counters(self) -> dict[str, Any]:
        return {
            "readingsQueued": self.readings_queued,
            "unknownDevices": self.unknown_devices,
            "disabledDevices": self.disabled_devices,
            "badRecords": sum(s.adapter.bad_records for s in self._states),
            "adapterFailures": sum(s.failures for s in self._states),
            "gapsQueued": self.gaps_queued,
            "configured": self._devices is not None,
            "adapters": [
                {
                    "name": s.adapter.name,
                    "type": s.adapter.spec.type,
                    "failingSince": iso_or_none(s.failing_since),
                    "lastError": s.last_error,
                    "lastSuccessAt": iso_or_none(s.last_success_at),
                    "badRecords": s.adapter.bad_records,
                }
                for s in self._states
            ],
        }

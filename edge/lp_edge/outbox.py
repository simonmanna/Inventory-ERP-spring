"""The outbox: observations stored before they are sent, sent in order, deleted once the ledger has them.

The ledger stores each event once (``source`` + ``sourceEventId``), so a batch that is sent twice after a lost answer
does no harm. Each event gets its own outcome: ``accepted`` and ``duplicate`` leave the outbox; ``rejected`` moves
the event to the ``rejected`` table with the reason.
"""

from __future__ import annotations

import json
import logging
import sqlite3
import threading
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from typing import Any

from lp_edge.api import ApiError
from lp_edge.store import Store
from lp_edge.timeutil import Clock, SystemClock, parse_iso

log = logging.getLogger(__name__)

MAX_BATCH = 500
DEFAULT_BATCH = 200


@dataclass(frozen=True)
class OutboxItem:
    seq: int
    event_id: str
    body: dict[str, Any]


@dataclass
class SendSummary:
    accepted: int = 0
    duplicates: int = 0
    rejected: int = 0
    kept: int = 0


class Outbox:
    def __init__(self, store: Store, clock: Clock | None = None) -> None:
        self._store = store
        self._clock = clock or SystemClock()

    def add(
        self,
        event: dict[str, Any],
        camera_id: str | None,
        conn: sqlite3.Connection | None = None,
        *,
        ignore_duplicate: bool = False,
    ) -> bool:
        """Store ``event``; inside ``conn``'s transaction when given (to keep it atomic with a clip plan).

        ``camera_id`` is the key the event is queued under: a camera id, or ``meter:<adapter>`` for meter readings.
        With ``ignore_duplicate`` an event whose ``eventId`` is already queued is skipped (``False`` is returned).
        """
        row = (
            event["eventId"],
            camera_id,
            parse_iso(event["occurredAt"]),
            json.dumps(event, separators=(",", ":"), ensure_ascii=False),
            self._clock.time(),
        )
        verb = "INSERT OR IGNORE" if ignore_duplicate else "INSERT"
        sql = f"{verb} INTO outbox (event_id, camera_id, occurred_at, body, created_at) VALUES (?, ?, ?, ?, ?)"
        if conn is not None:
            return conn.execute(sql, row).rowcount > 0
        return self._store.execute(sql, row) > 0

    def length(self) -> int:
        return int(self._store.scalar("SELECT COUNT(*) FROM outbox") or 0)

    def max_seq(self) -> int:
        return int(self._store.scalar("SELECT COALESCE(MAX(seq), 0) FROM outbox") or 0)

    def oldest_unsent(self, camera_id: str | None = None) -> float | None:
        """The earliest ``occurredAt`` still waiting, of one camera (or meter adapter key) or of all."""
        if camera_id is None:
            return self._store.scalar("SELECT MIN(occurred_at) FROM outbox")
        return self._store.scalar("SELECT MIN(occurred_at) FROM outbox WHERE camera_id = ?", (camera_id,))

    def oldest_unsent_with_prefix(self, prefix: str) -> float | None:
        """The earliest ``occurredAt`` still waiting among keys starting with ``prefix`` (e.g. ``meter:``)."""
        return self._store.scalar(
            "SELECT MIN(occurred_at) FROM outbox WHERE substr(camera_id, 1, ?) = ?", (len(prefix), prefix)
        )

    def batch(self, limit: int = DEFAULT_BATCH) -> list[OutboxItem]:
        rows = self._store.query(
            "SELECT seq, event_id, body FROM outbox ORDER BY seq LIMIT ?", (max(1, min(limit, MAX_BATCH)),)
        )
        return [OutboxItem(row["seq"], row["event_id"], json.loads(row["body"])) for row in rows]

    def settle(self, items: list[OutboxItem], response: dict[str, Any]) -> SendSummary:
        """Apply the ingest answer: drop accepted and duplicate events, move rejected ones aside."""
        outcomes = {}
        for result in response.get("results") or []:
            if isinstance(result, dict) and result.get("eventId"):
                outcomes[str(result["eventId"])] = result
        summary = SendSummary()
        now = self._clock.time()
        with self._store.transaction() as conn:
            for item in items:
                result = outcomes.get(item.event_id)
                status = (result or {}).get("status")
                if status in ("accepted", "duplicate"):
                    conn.execute("DELETE FROM outbox WHERE seq = ?", (item.seq,))
                    if status == "accepted":
                        summary.accepted += 1
                    else:
                        summary.duplicates += 1
                elif status == "rejected":
                    self._move_to_rejected(conn, item, str(result.get("reason") or "rejected"), now)
                    summary.rejected += 1
                    log.warning("observation %s rejected: %s", item.event_id, result.get("reason"))
                else:
                    conn.execute(
                        "UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE seq = ?",
                        ("no result for this event in the answer", item.seq),
                    )
                    summary.kept += 1
        return summary

    def reject(self, items: Iterable[OutboxItem], reason: str) -> None:
        now = self._clock.time()
        with self._store.transaction() as conn:
            for item in items:
                self._move_to_rejected(conn, item, reason, now)

    def record_failure(self, items: Iterable[OutboxItem], error: str) -> None:
        with self._store.transaction() as conn:
            for item in items:
                conn.execute(
                    "UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE seq = ?", (error[:500], item.seq)
                )

    def withdraw_after(self, seq: int, reason: str, *, keep_prefix: str | None = None) -> int:
        """Move every event stored after ``seq`` out of the queue (made while the clock was off).

        Events queued under a key starting with ``keep_prefix`` stay (meter readings carry the vendor's times, not
        this box's clock).
        """
        now = self._clock.time()
        sql = "SELECT seq, event_id, body FROM outbox WHERE seq > ?"
        params: tuple[Any, ...] = (seq,)
        if keep_prefix:
            sql += " AND (camera_id IS NULL OR substr(camera_id, 1, ?) != ?)"
            params = (seq, len(keep_prefix), keep_prefix)
        with self._store.transaction() as conn:
            rows = conn.execute(sql, params).fetchall()
            for row in rows:
                self._move_to_rejected(conn, OutboxItem(row["seq"], row["event_id"], json.loads(row["body"])), reason, now)
        return len(rows)

    def rejected_count(self) -> int:
        return int(self._store.scalar("SELECT COUNT(*) FROM rejected") or 0)

    def purge_rejected(self, older_than: float) -> int:
        return self._store.execute("DELETE FROM rejected WHERE rejected_at < ?", (older_than,))

    @staticmethod
    def _move_to_rejected(conn: sqlite3.Connection, item: OutboxItem, reason: str, now: float) -> None:
        conn.execute(
            "INSERT INTO rejected (event_id, body, reason, rejected_at) VALUES (?, ?, ?, ?) "
            "ON CONFLICT (event_id) DO UPDATE SET reason = excluded.reason, rejected_at = excluded.rejected_at",
            (item.event_id, json.dumps(item.body, separators=(",", ":")), reason[:1000], now),
        )
        conn.execute("DELETE FROM outbox WHERE seq = ?", (item.seq,))


class OutboxSender:
    """Sends the outbox in order, in batches, with exponential backoff (1 s doubling up to 60 s)."""

    def __init__(
        self,
        outbox: Outbox,
        send: Callable[[list[dict]], dict],
        *,
        batch_size: int = DEFAULT_BATCH,
        max_backoff: float = 60.0,
        can_send: Callable[[], bool] = lambda: True,
    ) -> None:
        self._outbox = outbox
        self._send = send
        self._batch_size = batch_size
        self._max_backoff = max_backoff
        self._can_send = can_send
        self._backoff = 0.0
        self.last_error: str | None = None
        self.last_success_at: float | None = None

    def send_once(self) -> SendSummary | None:
        """Send one batch. ``None`` when there is nothing to send; raises :class:`ApiError` on a failed call."""
        items = self._outbox.batch(self._batch_size)
        if not items:
            return None
        summary = SendSummary()
        self._send_items(items, summary)
        return summary

    def _send_items(self, items: list[OutboxItem], summary: SendSummary) -> None:
        try:
            response = self._send([item.body for item in items])
        except ApiError as exc:
            if exc.status in (400, 413, 422):
                # The batch as a whole was refused: find the event that causes it.
                if len(items) == 1:
                    self._outbox.reject(items, exc.message)
                    summary.rejected += 1
                    log.warning("observation %s refused: %s", items[0].event_id, exc.message)
                    return
                middle = len(items) // 2
                self._send_items(items[:middle], summary)
                self._send_items(items[middle:], summary)
                return
            self._outbox.record_failure(items, exc.message)
            raise
        part = self._outbox.settle(items, response if isinstance(response, dict) else {})
        summary.accepted += part.accepted
        summary.duplicates += part.duplicates
        summary.rejected += part.rejected
        summary.kept += part.kept

    def run(self, stop: threading.Event, wake: threading.Event, clock: Clock | None = None) -> None:
        """Send until ``stop``; sleeps when the outbox is empty until ``wake`` is set or a few seconds pass."""
        clock = clock or SystemClock()
        while not stop.is_set():
            if not self._can_send():
                wake.wait(2.0)
                wake.clear()
                continue
            try:
                summary = self.send_once()
            except ApiError as exc:
                self.last_error = exc.message
                delay = self.next_delay(exc)
                log.warning("sending observations failed (%s); retrying in %.0f s", exc, delay)
                stop.wait(delay)
                continue
            except Exception:  # noqa: BLE001 - the sender must survive anything
                log.exception("sending observations failed")
                stop.wait(self.next_delay(None))
                continue
            self._backoff = 0.0
            self.last_error = None
            if summary is None:
                wake.wait(5.0)
                wake.clear()
            else:
                self.last_success_at = clock.time()
                if summary.kept:
                    stop.wait(1.0)

    def next_delay(self, error: ApiError | None) -> float:
        self._backoff = min(self._max_backoff, max(1.0, self._backoff * 2))
        if error is not None and error.retry_after is not None:
            return max(self._backoff, min(error.retry_after, 3600.0))
        return self._backoff

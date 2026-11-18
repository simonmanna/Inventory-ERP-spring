"""Commands from the backend, from the long poll or from a heartbeat answer, each run once.

A command is ``{ id, kind, payload, createdAt, expiresAt }``. Its id is recorded before it runs, so a redelivery (the
backend sends a command again when no result came within 2 minutes, and heartbeat answers repeat pending ones) is
ignored. The result goes to ``POST /agents/commands/{id}/result`` as ``{ status, message?, code? }``; a result that
could not be sent is sent again later. A snapshot completes its command by uploading the picture.
"""

from __future__ import annotations

import logging
import threading
from collections.abc import Callable, Iterable, Mapping
from concurrent.futures import ThreadPoolExecutor
from typing import Any

from lp_edge.api import ApiError
from lp_edge.store import Store
from lp_edge.timeutil import Clock, SystemClock, parse_iso

log = logging.getLogger(__name__)

#: Returned by a handler that completed the command itself (no result call is needed).
REPORTED = object()

Report = Callable[[str, str, "str | None", "str | None"], None]
Handler = Callable[[Mapping[str, Any]], Any]


class CommandError(Exception):
    """A command that could not be carried out; ``code`` ``not_found`` when the clip or period is not on the box."""

    def __init__(self, message: str, code: str | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.code = code


class CommandDispatcher:
    def __init__(self, store: Store, report: Report, *, clock: Clock | None = None, workers: int = 4) -> None:
        self._store = store
        self._report = report
        self._clock = clock or SystemClock()
        self._handlers: dict[str, Handler] = {}
        self._running: set[str] = set()
        self._lock = threading.Lock()
        self._executor = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="command")
        # Commands interrupted by a restart: forget them so their redelivery runs them again.
        store.execute("DELETE FROM commands WHERE state = 'running'")

    def register(self, kind: str, handler: Handler) -> None:
        self._handlers[kind] = handler

    def submit(self, commands: Iterable[Mapping[str, Any]]) -> int:
        """Start the commands not seen before; returns how many started."""
        started = 0
        for command in commands:
            command_id = str(command.get("id") or "")
            kind = str(command.get("kind") or "")
            if not command_id:
                continue
            now = self._clock.time()
            with self._lock:
                if command_id in self._running:
                    continue
                if self._store.query_one("SELECT 1 FROM commands WHERE id = ?", (command_id,)) is not None:
                    continue
                self._store.execute(
                    "INSERT INTO commands (id, kind, state, received_at) VALUES (?, ?, 'running', ?)",
                    (command_id, kind, now),
                )
                self._running.add(command_id)
            if _expired(command, now):
                self._finish(command_id, "failed", "the command expired before the agent ran it", None)
            elif kind not in self._handlers:
                self._finish(command_id, "failed", f"this agent does not know the command {kind!r}", None)
            else:
                log.info("command %s (%s) received", command_id, kind)
                self._executor.submit(self._run, command)
                started += 1
        return started

    def run_now(self, command: Mapping[str, Any]) -> None:
        """Run one command in the calling thread (tests and tools)."""
        self._run(command)

    def _run(self, command: Mapping[str, Any]) -> None:
        command_id = str(command.get("id"))
        handler = self._handlers[str(command.get("kind"))]
        try:
            result = handler(command)
        except CommandError as exc:
            log.warning("command %s failed: %s", command_id, exc.message)
            self._finish(command_id, "failed", exc.message, exc.code)
        except Exception as exc:  # noqa: BLE001 - every command gets a result
            log.exception("command %s failed", command_id)
            self._finish(command_id, "failed", f"the agent failed: {type(exc).__name__}", None)
        else:
            if result is REPORTED:
                self._finish(command_id, "done", None, None, reported=True)
            else:
                self._finish(command_id, "done", result if isinstance(result, str) else None, None)

    def _finish(
        self, command_id: str, status: str, message: str | None, code: str | None, *, reported: bool = False
    ) -> None:
        self._store.execute(
            "UPDATE commands SET state = ?, message = ?, code = ?, finished_at = ?, reported = ? WHERE id = ?",
            (status, message, code, self._clock.time(), 1 if reported else 0, command_id),
        )
        with self._lock:
            self._running.discard(command_id)
        if not reported:
            self._send(command_id, status, message, code)

    def _send(self, command_id: str, status: str, message: str | None, code: str | None) -> bool:
        try:
            self._report(command_id, status, message, code)
        except ApiError as exc:
            if exc.retryable or exc.status in (401, 403):
                log.warning("the result of command %s will be sent again: %s", command_id, exc.message)
                return False
            log.warning("the result of command %s was refused: %s", command_id, exc.message)
        self._store.execute("UPDATE commands SET reported = 1 WHERE id = ?", (command_id,))
        return True

    def report_pending(self) -> int:
        """Send results that did not reach the backend; returns how many were sent."""
        rows = self._store.query(
            "SELECT id, state, message, code FROM commands WHERE reported = 0 AND state IN ('done', 'failed')"
        )
        return sum(1 for row in rows if self._send(row["id"], row["state"], row["message"], row["code"]))

    def purge(self, older_than: float) -> int:
        return self._store.execute(
            "DELETE FROM commands WHERE reported = 1 AND COALESCE(finished_at, received_at) < ?", (older_than,)
        )

    def shutdown(self) -> None:
        self._executor.shutdown(wait=False, cancel_futures=True)


def _expired(command: Mapping[str, Any], now: float) -> bool:
    expires = command.get("expiresAt")
    if not expires:
        return False
    try:
        return parse_iso(str(expires)) < now
    except ValueError:
        return False

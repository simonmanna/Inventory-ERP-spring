"""The agent's local state in one SQLite file: the outbox of unsent observations, clips, commands, markers.

Every write is a short transaction with ``synchronous=FULL``, so a power cut loses nothing that was committed: the
queue resumes where it stopped.
"""

from __future__ import annotations

import os
import sqlite3
import threading
from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from pathlib import Path
from typing import Any

SCHEMA_VERSION = "1"

_SCHEMA = (
    """
    CREATE TABLE IF NOT EXISTS meta (
        key   TEXT PRIMARY KEY,
        value TEXT NOT NULL
    )
    """,
    # Observations not yet accepted by the backend, in the order they were made.
    """
    CREATE TABLE IF NOT EXISTS outbox (
        seq         INTEGER PRIMARY KEY AUTOINCREMENT,
        event_id    TEXT NOT NULL UNIQUE,
        camera_id   TEXT,
        occurred_at REAL NOT NULL,
        body        TEXT NOT NULL,
        created_at  REAL NOT NULL,
        attempts    INTEGER NOT NULL DEFAULT 0,
        last_error  TEXT
    )
    """,
    "CREATE INDEX IF NOT EXISTS outbox_camera_idx ON outbox (camera_id, occurred_at)",
    "CREATE INDEX IF NOT EXISTS outbox_occurred_idx ON outbox (occurred_at)",
    # Observations the backend refused (or the agent withdrew), kept for diagnosis.
    """
    CREATE TABLE IF NOT EXISTS rejected (
        event_id    TEXT PRIMARY KEY,
        body        TEXT NOT NULL,
        reason      TEXT,
        rejected_at REAL NOT NULL
    )
    """,
    # Clips kept on this box: flagged with an observation, or cut from the buffer on request.
    """
    CREATE TABLE IF NOT EXISTS clips (
        clip_id      TEXT PRIMARY KEY,
        camera_id    TEXT NOT NULL,
        zone_id      TEXT,
        origin       TEXT NOT NULL,
        state        TEXT NOT NULL,
        started_at   REAL NOT NULL,
        ended_at     REAL NOT NULL,
        ready_at     REAL NOT NULL,
        event_ids    TEXT NOT NULL DEFAULT '[]',
        evidence_id  TEXT,
        file_name    TEXT,
        sha256       TEXT,
        byte_size    INTEGER,
        content_type TEXT,
        reason       TEXT,
        uploaded_at  REAL,
        created_at   REAL NOT NULL,
        updated_at   REAL NOT NULL
    )
    """,
    "CREATE INDEX IF NOT EXISTS clips_state_idx ON clips (state, ready_at)",
    "CREATE INDEX IF NOT EXISTS clips_evidence_idx ON clips (evidence_id)",
    # Commands seen, so a redelivered id is ignored, and results that still have to reach the backend.
    """
    CREATE TABLE IF NOT EXISTS commands (
        id          TEXT PRIMARY KEY,
        kind        TEXT NOT NULL,
        state       TEXT NOT NULL,
        message     TEXT,
        code        TEXT,
        reported    INTEGER NOT NULL DEFAULT 0,
        received_at REAL NOT NULL,
        finished_at REAL
    )
    """,
)


class Store:
    """A thread-safe SQLite connection with the agent's schema."""

    def __init__(self, path: Path | str) -> None:
        path = Path(path)
        if str(path) != ":memory:":
            path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(str(path), timeout=30, check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA synchronous=FULL")
        self._conn.execute("PRAGMA busy_timeout=30000")
        if str(path) != ":memory:":
            try:
                os.chmod(path, 0o600)
            except OSError:
                pass
        with self.transaction() as conn:
            for statement in _SCHEMA:
                conn.execute(statement)
            conn.execute(
                "INSERT INTO meta (key, value) VALUES ('schema', ?) ON CONFLICT (key) DO NOTHING", (SCHEMA_VERSION,)
            )

    @contextmanager
    def transaction(self) -> Iterator[sqlite3.Connection]:
        """One write transaction; rolled back if the block raises. Nested use joins the outer transaction."""
        with self._lock:
            if self._conn.in_transaction:
                yield self._conn
                return
            self._conn.execute("BEGIN IMMEDIATE")
            try:
                yield self._conn
            except BaseException:
                self._conn.execute("ROLLBACK")
                raise
            self._conn.execute("COMMIT")

    def execute(self, sql: str, params: Sequence[Any] = ()) -> int:
        with self.transaction() as conn:
            return conn.execute(sql, params).rowcount

    def query(self, sql: str, params: Sequence[Any] = ()) -> list[sqlite3.Row]:
        with self._lock:
            return list(self._conn.execute(sql, params).fetchall())

    def query_one(self, sql: str, params: Sequence[Any] = ()) -> sqlite3.Row | None:
        with self._lock:
            return self._conn.execute(sql, params).fetchone()

    def scalar(self, sql: str, params: Sequence[Any] = ()) -> Any:
        row = self.query_one(sql, params)
        return None if row is None else row[0]

    def get_meta(self, key: str) -> str | None:
        return self.scalar("SELECT value FROM meta WHERE key = ?", (key,))

    def put_meta(self, key: str, value: str) -> None:
        self.execute(
            "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            (key, value),
        )

    def delete_meta(self, key: str) -> None:
        self.execute("DELETE FROM meta WHERE key = ?", (key,))

    def meta_with_prefix(self, prefix: str) -> dict[str, str]:
        rows = self.query("SELECT key, value FROM meta WHERE key LIKE ? ESCAPE '\\'", (_like_prefix(prefix),))
        return {row["key"]: row["value"] for row in rows}

    def close(self) -> None:
        with self._lock:
            self._conn.close()


def _like_prefix(prefix: str) -> str:
    return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

"""Adapters that read the files a vendor's software writes into a directory.

Files are read oldest first, once each, then moved to ``<path>/done/``. A file modified in the last
:data:`SETTLE_SECONDS` may still be being written and waits for the next poll. Before the move, a marker in the
agent's SQLite store (written in the same transaction as the readings) records that the file's readings are queued,
so a crash between the two does not read the file again; the readings' ``sourceEventId`` makes a second read harmless
anyway.
"""

from __future__ import annotations

import logging
import sqlite3
from collections.abc import Iterator
from pathlib import Path
from typing import Any

from lp_edge.measurements.base import Adapter, AdapterError, AdapterSpec, BadRecord, ConfigError, Reading
from lp_edge.store import Store

log = logging.getLogger(__name__)

SETTLE_SECONDS = 2.0
#: Files are read until a poll has this many readings; the rest wait for the next poll.
MAX_READINGS_PER_POLL = 5000
DONE_DIR = "done"


class DirectoryAdapter(Adapter):
    default_glob = "*"

    def __init__(self, spec: AdapterSpec, store: Store) -> None:
        super().__init__(spec, store)
        raw = spec.options.get("path")
        if not isinstance(raw, str) or not raw.strip():
            raise ConfigError("path is required")
        self.path = Path(raw)
        glob = spec.options.get("glob") or self.default_glob
        if not isinstance(glob, str) or "/" in glob or ".." in glob:
            raise ConfigError("glob must be a file pattern inside path")
        self.glob = glob
        self._pending: list[tuple[Path, str]] = []
        self._seen: dict[Path, tuple[str, float]] = {}  # file -> (signature, first poll that saw it so)

    def records(self, text: str) -> Iterator[Any]:
        """The records of one file; a :class:`BadRecord` instance stands for a line that could not be read."""
        raise NotImplementedError

    def poll(self, now: float) -> list[Reading]:
        self._pending = []
        if not self.path.is_dir():
            raise AdapterError(f"the directory {self.path} does not exist")
        try:
            files = []
            for path in self.path.glob(self.glob):
                try:
                    stat = path.stat()
                except FileNotFoundError:
                    continue
                if path.is_file():
                    files.append((stat.st_mtime, path.name, path, stat))
        except OSError as exc:
            raise AdapterError(f"the directory {self.path} could not be listed: {exc}") from exc
        files.sort(key=lambda f: (f[0], f[1]))
        seen: dict[Path, tuple[str, float]] = {}
        readings: list[Reading] = []
        for mtime, _, path, stat in files:
            signature = f"{stat.st_size}:{stat.st_mtime_ns}"
            first_seen = self._seen.get(path)
            since = first_seen[1] if first_seen is not None and first_seen[0] == signature else now
            seen[path] = (signature, since)
            # Settled: not modified for a while, or unchanged across polls (a writer whose clock is ahead of ours).
            if now - mtime < SETTLE_SECONDS and now - since < SETTLE_SECONDS:
                continue
            if len(readings) >= MAX_READINGS_PER_POLL:
                break
            if self.store.get_meta(self._marker(path)) == signature:
                self._pending.append((path, signature))  # queued before a crash; only the move is left
                continue
            try:
                text = path.read_text(encoding="utf-8-sig", errors="replace")
            except FileNotFoundError:
                continue
            except OSError as exc:
                raise AdapterError(f"{path.name} could not be read: {exc}") from exc
            bad = 0
            for record in self.records(text):
                try:
                    if isinstance(record, BadRecord):
                        raise record
                    readings.append(self.to_reading(record))
                except BadRecord as exc:
                    bad += 1
                    log.debug("meter adapter %s: %s, a record skipped: %s", self.name, path.name, exc)
            if bad:
                self.bad_records += bad
                log.warning("meter adapter %s: %d records of %s could not be read and were skipped",
                            self.name, bad, path.name)
            self._pending.append((path, signature))
        self._seen = seen
        return readings

    def stage(self, conn: sqlite3.Connection) -> None:
        for path, signature in self._pending:
            conn.execute(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                (self._marker(path), signature),
            )

    def finish(self) -> None:
        pending, self._pending = self._pending, []
        if not pending:
            return
        done = self.path / DONE_DIR
        for path, _ in pending:
            try:
                done.mkdir(exist_ok=True)
                target = done / path.name
                n = 1
                while target.exists():
                    target = done / f"{path.stem}.{n}{path.suffix}"
                    n += 1
                path.rename(target)
            except FileNotFoundError:
                pass
            except OSError as exc:
                # The marker stays, so the file is not read again; the move is retried at the next poll.
                log.error("meter adapter %s: %s could not be moved to %s/: %s", self.name, path.name, DONE_DIR, exc)
                continue
            self.store.delete_meta(self._marker(path))
            self._seen.pop(path, None)

    def _marker(self, path: Path) -> str:
        return self.meta_key(f"file:{path.name}")

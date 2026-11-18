"""Clips kept on this box until an investigator asks for them.

A **flagged** clip belongs to an observation (a drawer opening, a long dwell): its id goes out with the observation at
once, and when its post-roll has passed it is cut from the ring buffer, encoded, hashed (SHA-256 at capture) and
registered with ``POST /agents/clips``. A **range** clip is cut on request for an evidence record
(``clip_upload`` with ``clipId`` null) and registers its SHA-256 with its first upload chunk.

The clip file is sealed in 1 MiB chunks (AES-256-GCM), so an upload can resume at any offset without decrypting the
whole clip. Clips stay ``bufferDays`` and are then deleted, uploaded or not.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import sqlite3
import struct
import tempfile
import uuid
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from lp_edge.crypto import OVERHEAD, Cipher
from lp_edge.events import ClipPlan
from lp_edge.files import ensure_private_dir, safe_name
from lp_edge.ringbuffer import RingBuffer
from lp_edge.store import Store
from lp_edge.timeutil import Clock, SystemClock, iso
from lp_edge.video import MAX_CLIP_BYTES, EncodedClip, NoFrames, encode_clip

log = logging.getLogger(__name__)

CHUNK = 1 << 20
MAGIC = b"LPCLIP1\n"
HEADER = struct.Struct(">IQ")  # plaintext chunk size, plaintext length
LENGTH = struct.Struct(">I")
SETTLE_SECONDS = 3.0

Encoder = Callable[..., EncodedClip]


class ClipUnavailable(Exception):
    """The clip, or the period asked, is no longer (or never was) on this box."""


@dataclass(frozen=True)
class ClipRow:
    clip_id: str
    camera_id: str
    zone_id: str | None
    origin: str
    state: str
    started_at: float
    ended_at: float
    ready_at: float
    event_ids: list[str]
    evidence_id: str | None
    file_name: str | None
    sha256: str | None
    byte_size: int | None
    content_type: str | None
    reason: str | None

    @classmethod
    def of(cls, row: sqlite3.Row) -> ClipRow:
        return cls(
            clip_id=row["clip_id"],
            camera_id=row["camera_id"],
            zone_id=row["zone_id"],
            origin=row["origin"],
            state=row["state"],
            started_at=row["started_at"],
            ended_at=row["ended_at"],
            ready_at=row["ready_at"],
            event_ids=json.loads(row["event_ids"] or "[]"),
            evidence_id=row["evidence_id"],
            file_name=row["file_name"],
            sha256=row["sha256"],
            byte_size=row["byte_size"],
            content_type=row["content_type"],
            reason=row["reason"],
        )

    @property
    def available(self) -> bool:
        return self.state in ("stored", "registered", "uploaded") and self.file_name is not None


class ClipStore:
    def __init__(
        self,
        store: Store,
        root: Path,
        cipher: Cipher,
        ring: RingBuffer,
        *,
        tmp_dir: Path,
        fps: float,
        clock: Clock | None = None,
        encode: Encoder = encode_clip,
        max_bytes: int = MAX_CLIP_BYTES,
    ) -> None:
        self._store = store
        self._root = root
        self._cipher = cipher
        self._ring = ring
        self._tmp = tmp_dir
        self._fps = fps
        self._clock = clock or SystemClock()
        self._encode = encode
        self.max_bytes = max_bytes
        ensure_private_dir(root)

    # -- planning and production ---------------------------------------------------------------------------------

    def plan(
        self, camera_id: str, zone_id: str | None, plan: ClipPlan, event_id: str, conn: sqlite3.Connection | None = None
    ) -> None:
        now = self._clock.time()
        row = (
            plan.clip_id,
            camera_id,
            zone_id,
            "flagged",
            "pending",
            plan.started_at,
            plan.ended_at,
            plan.ended_at + SETTLE_SECONDS,
            json.dumps([event_id]),
            now,
            now,
        )
        sql = (
            "INSERT INTO clips (clip_id, camera_id, zone_id, origin, state, started_at, ended_at, ready_at, event_ids,"
            " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )
        if conn is not None:
            conn.execute(sql, row)
        else:
            self._store.execute(sql, row)

    def due(self, now: float) -> list[ClipRow]:
        rows = self._store.query(
            "SELECT * FROM clips WHERE state = 'pending' AND ready_at <= ? ORDER BY ready_at LIMIT 20", (now,)
        )
        return [ClipRow.of(r) for r in rows]

    def produce(self, row: ClipRow) -> ClipRow:
        """Cut, encode, hash and seal ``row``'s clip; the row comes back ``stored`` or ``failed``."""

        def frames() -> Iterable[tuple[float, bytes]]:
            return self._ring.frames(row.camera_id, row.started_at, row.ended_at)

        ensure_private_dir(self._tmp)
        try:
            encoded = self._encode(frames, self._tmp, fps=self._fps, max_bytes=self.max_bytes)
        except NoFrames:
            return self._fail(row, "no video in the buffer for that period")
        except Exception as exc:  # noqa: BLE001 - one bad clip must not stop the others
            log.exception("clip %s could not be encoded", row.clip_id)
            return self._fail(row, f"encoding failed: {type(exc).__name__}")
        try:
            file_name = f"{safe_name(row.clip_id)}.clip"
            sha256, size = self._seal(row.clip_id, encoded.path, self._root / file_name)
        finally:
            encoded.path.unlink(missing_ok=True)
        self._store.execute(
            "UPDATE clips SET state = 'stored', file_name = ?, sha256 = ?, byte_size = ?, content_type = ?,"
            " started_at = ?, ended_at = ?, reason = NULL, updated_at = ? WHERE clip_id = ?",
            (
                file_name,
                sha256,
                size,
                encoded.content_type,
                encoded.started_at,
                max(encoded.ended_at, encoded.started_at),
                self._clock.time(),
                row.clip_id,
            ),
        )
        return self.find(row.clip_id) or row

    def _fail(self, row: ClipRow, reason: str) -> ClipRow:
        self._store.execute(
            "UPDATE clips SET state = 'failed', reason = ?, updated_at = ? WHERE clip_id = ?",
            (reason, self._clock.time(), row.clip_id),
        )
        log.warning("clip %s not kept: %s", row.clip_id, reason)
        return self.find(row.clip_id) or row

    def _seal(self, clip_id: str, source: Path, target: Path) -> tuple[str, int]:
        digest = hashlib.sha256()
        total = source.stat().st_size
        fd, tmp = tempfile.mkstemp(prefix=f".{target.name}.", dir=str(target.parent))
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as out, open(source, "rb") as src:
                out.write(MAGIC + HEADER.pack(CHUNK, total))
                index = 0
                while True:
                    chunk = src.read(CHUNK)
                    if not chunk:
                        break
                    digest.update(chunk)
                    sealed = self._cipher.seal(chunk, _chunk_aad(clip_id, index))
                    out.write(LENGTH.pack(len(sealed)) + sealed)
                    index += 1
                out.flush()
                os.fsync(out.fileno())
            os.replace(tmp, target)
        except BaseException:
            Path(tmp).unlink(missing_ok=True)
            raise
        return digest.hexdigest(), total

    # -- range clips -------------------------------------------------------------------------------------------------

    def cut_range(self, evidence_id: str, camera_id: str, start: float, end: float) -> ClipRow:
        """The clip for ``evidence_id``, cut from the buffer now (or the one cut for it before)."""
        existing = self.find_by_evidence(evidence_id)
        if existing is not None and existing.available:
            return existing
        if existing is not None:
            self._store.execute("DELETE FROM clips WHERE clip_id = ?", (existing.clip_id,))
        clip_id = str(uuid.uuid4())
        now = self._clock.time()
        self._store.execute(
            "INSERT INTO clips (clip_id, camera_id, zone_id, origin, state, started_at, ended_at, ready_at,"
            " event_ids, evidence_id, created_at, updated_at) VALUES (?, ?, NULL, 'range', 'pending', ?, ?, ?, '[]',"
            " ?, ?, ?)",
            (clip_id, camera_id, start, end, end + SETTLE_SECONDS, evidence_id, now, now),
        )
        row = self.find(clip_id)
        assert row is not None
        produced = self.produce(row)
        if not produced.available:
            self._store.execute("DELETE FROM clips WHERE clip_id = ?", (clip_id,))
            raise ClipUnavailable(produced.reason or "no video in the buffer for that period")
        return produced

    # -- registration ------------------------------------------------------------------------------------------------

    def pending_registration(self, limit: int = 100) -> list[ClipRow]:
        rows = self._store.query(
            "SELECT * FROM clips WHERE state = 'stored' AND origin = 'flagged' ORDER BY started_at LIMIT ?",
            (max(1, min(100, limit)),),
        )
        return [ClipRow.of(r) for r in rows]

    @staticmethod
    def registration(row: ClipRow) -> dict[str, Any]:
        body: dict[str, Any] = {
            "clipId": row.clip_id,
            "cameraId": row.camera_id,
            "startedAt": iso(row.started_at),
            "endedAt": iso(row.ended_at),
            "sha256": row.sha256,
            "byteSize": row.byte_size,
            "contentType": row.content_type,
            "eventIds": row.event_ids,
        }
        if row.zone_id:
            body["zoneId"] = row.zone_id
        return body

    def apply_registration(self, rows: list[ClipRow], response: dict[str, Any]) -> None:
        """Record the answer to ``POST /agents/clips``: ``registered`` or ``duplicate`` keep the evidence id."""
        results = {
            str(r.get("clipId")): r for r in (response.get("results") or []) if isinstance(r, dict) and r.get("clipId")
        }
        now = self._clock.time()
        with self._store.transaction() as conn:
            for row in rows:
                result = results.get(row.clip_id)
                status = (result or {}).get("status", "registered" if not results else None)
                if status in ("registered", "duplicate"):
                    conn.execute(
                        "UPDATE clips SET state = 'registered', evidence_id = COALESCE(?, evidence_id), reason = NULL,"
                        " updated_at = ? WHERE clip_id = ?",
                        ((result or {}).get("evidenceId"), now, row.clip_id),
                    )
                elif status == "rejected":
                    reason = str((result or {}).get("reason") or "rejected")
                    conn.execute(
                        "UPDATE clips SET state = 'rejected', reason = ?, updated_at = ? WHERE clip_id = ?",
                        (reason, now, row.clip_id),
                    )
                    log.warning("clip %s was not registered: %s", row.clip_id, reason)

    # -- lookup and reading ------------------------------------------------------------------------------------------

    def find(self, clip_id: str) -> ClipRow | None:
        row = self._store.query_one("SELECT * FROM clips WHERE clip_id = ?", (clip_id,))
        return ClipRow.of(row) if row else None

    def find_by_evidence(self, evidence_id: str) -> ClipRow | None:
        row = self._store.query_one(
            "SELECT * FROM clips WHERE evidence_id = ? ORDER BY created_at DESC LIMIT 1", (evidence_id,)
        )
        return ClipRow.of(row) if row else None

    def read(self, row: ClipRow, offset: int, length: int) -> bytes:
        """``length`` plaintext bytes of the clip from ``offset``."""
        if not row.available or row.file_name is None:
            raise ClipUnavailable(f"Clip {row.clip_id} is no longer on the agent")
        path = self._root / row.file_name
        if not path.exists():
            raise ClipUnavailable(f"Clip {row.clip_id} is no longer on the agent")
        out = bytearray()
        with open(path, "rb") as handle:
            if handle.read(len(MAGIC)) != MAGIC:
                raise ClipUnavailable(f"Clip {row.clip_id} is damaged")
            chunk_size, total = HEADER.unpack(handle.read(HEADER.size))
            base = len(MAGIC) + HEADER.size
            end = min(total, offset + length)
            position = offset
            while position < end:
                index = position // chunk_size
                handle.seek(base + index * (LENGTH.size + OVERHEAD + chunk_size))
                (size,) = LENGTH.unpack(handle.read(LENGTH.size))
                plain = self._cipher.open(handle.read(size), _chunk_aad(row.clip_id, index))
                start_in_chunk = position - index * chunk_size
                piece = plain[start_in_chunk : start_in_chunk + (end - position)]
                if not piece:
                    break
                out += piece
                position += len(piece)
        return bytes(out)

    def mark_uploaded(self, clip_id: str, evidence_id: str) -> None:
        self._store.execute(
            "UPDATE clips SET state = 'uploaded', evidence_id = ?, uploaded_at = ?, updated_at = ? WHERE clip_id = ?",
            (evidence_id, self._clock.time(), self._clock.time(), clip_id),
        )

    # -- retention ---------------------------------------------------------------------------------------------------

    def purge(self, cutoff: float) -> int:
        """Delete clips (and plans) created before ``cutoff``."""
        rows = self._store.query("SELECT clip_id, file_name FROM clips WHERE created_at < ?", (cutoff,))
        for row in rows:
            if row["file_name"]:
                (self._root / row["file_name"]).unlink(missing_ok=True)
        with self._store.transaction() as conn:
            conn.executemany("DELETE FROM clips WHERE clip_id = ?", [(row["clip_id"],) for row in rows])
        return len(rows)

    def counts(self) -> dict[str, int]:
        rows = self._store.query("SELECT state, COUNT(*) AS n FROM clips GROUP BY state")
        return {row["state"]: row["n"] for row in rows}


def _chunk_aad(clip_id: str, index: int) -> bytes:
    return clip_id.encode("utf-8") + b"|" + struct.pack(">I", index)

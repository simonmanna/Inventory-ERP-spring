"""The encrypted ring buffer: recent video of each enabled camera, on the box's own disk.

Each buffered frame is a JPEG sealed with AES-256-GCM (its camera and time are authenticated with it) and appended to
a one-minute segment file ``<buffer>/<camera>/<YYYYMMDD>/<HHMMSS>.seg`` (UTC). Segments older than ``bufferDays`` are
deleted, and the oldest go first when the disk runs short. A record cut short by a power failure is ignored when read.
"""

from __future__ import annotations

import logging
import math
import os
import struct
import threading
import time
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import BinaryIO

from lp_edge.crypto import Cipher, IntegrityError
from lp_edge.files import ensure_private_dir, safe_name

log = logging.getLogger(__name__)

MAGIC = b"LPRB1\n"
RECORD = struct.Struct(">dI")  # capture time (epoch seconds), length of the sealed JPEG
MAX_RECORD = 32 * 1024 * 1024


def _aad(camera_id: str, ts: float) -> bytes:
    return camera_id.encode("utf-8") + b"|" + struct.pack(">d", ts)


@dataclass
class _Writer:
    start: float
    path: Path
    handle: BinaryIO
    synced_at: float


@dataclass(frozen=True)
class Segment:
    camera: str  # directory name (the camera id made safe)
    start: float
    path: Path


class RingBuffer:
    def __init__(
        self,
        root: Path,
        cipher: Cipher,
        *,
        segment_seconds: int = 60,
        fsync_seconds: float = 10.0,
        monotonic: Callable[[], float] = time.monotonic,
    ) -> None:
        self._root = root
        self._cipher = cipher
        self._segment_seconds = segment_seconds
        self._fsync_seconds = fsync_seconds
        self._monotonic = monotonic
        self._writers: dict[str, _Writer] = {}
        self._lock = threading.Lock()
        ensure_private_dir(root)

    # -- writing ---------------------------------------------------------------------------------------------------

    def append(self, camera_id: str, ts: float, jpeg: bytes) -> None:
        start = math.floor(ts / self._segment_seconds) * self._segment_seconds
        blob = self._cipher.seal(jpeg, _aad(camera_id, ts))
        with self._lock:
            writer = self._writers.get(camera_id)
            if writer is None or writer.start != start:
                if writer is not None:
                    self._close(writer)
                writer = self._open(camera_id, start)
                self._writers[camera_id] = writer
            writer.handle.write(RECORD.pack(ts, len(blob)) + blob)
            writer.handle.flush()
            now = self._monotonic()
            if now - writer.synced_at >= self._fsync_seconds:
                os.fsync(writer.handle.fileno())
                writer.synced_at = now

    def close_camera(self, camera_id: str) -> None:
        with self._lock:
            writer = self._writers.pop(camera_id, None)
            if writer is not None:
                self._close(writer)

    def close(self) -> None:
        with self._lock:
            for writer in self._writers.values():
                self._close(writer)
            self._writers.clear()

    def _open(self, camera_id: str, start: float) -> _Writer:
        path = self._segment_path(camera_id, start)
        ensure_private_dir(path.parent)
        handle = open(path, "ab")  # noqa: SIM115 - kept open between appends
        try:
            os.fchmod(handle.fileno(), 0o600)
        except OSError:
            pass
        if handle.tell() == 0:
            handle.write(MAGIC)
        return _Writer(start, path, handle, self._monotonic())

    @staticmethod
    def _close(writer: _Writer) -> None:
        try:
            writer.handle.flush()
            os.fsync(writer.handle.fileno())
        except OSError:
            pass
        writer.handle.close()

    def _segment_path(self, camera_id: str, start: float) -> Path:
        stamp = datetime.fromtimestamp(start, tz=timezone.utc)
        return self._root / safe_name(camera_id) / f"{stamp:%Y%m%d}" / f"{stamp:%H%M%S}.seg"

    # -- reading ---------------------------------------------------------------------------------------------------

    def frames(self, camera_id: str, start: float, end: float) -> Iterator[tuple[float, bytes]]:
        """The buffered JPEG frames of ``camera_id`` captured from ``start`` to ``end``, in time order."""
        segment = math.floor(start / self._segment_seconds) * self._segment_seconds
        while segment <= end:
            path = self._segment_path(camera_id, segment)
            if path.exists():
                yield from sorted(self._read(camera_id, path, start, end), key=lambda r: r[0])
            segment += self._segment_seconds

    def has_frames(self, camera_id: str, start: float, end: float) -> bool:
        return next(iter(self.frames(camera_id, start, end)), None) is not None

    def _read(self, camera_id: str, path: Path, start: float, end: float) -> list[tuple[float, bytes]]:
        records: list[tuple[float, bytes]] = []
        try:
            with open(path, "rb") as handle:
                if handle.read(len(MAGIC)) != MAGIC:
                    log.warning("buffer segment %s is not a segment file", path.name)
                    return records
                while True:
                    head = handle.read(RECORD.size)
                    if len(head) < RECORD.size:
                        break
                    ts, length = RECORD.unpack(head)
                    if length > MAX_RECORD:
                        log.warning("buffer segment %s is damaged; reading stops there", path.name)
                        break
                    blob = handle.read(length)
                    if len(blob) < length:
                        break  # the last record is still being written, or was cut by a power failure
                    if ts < start or ts > end:
                        continue
                    try:
                        records.append((ts, self._cipher.open(blob, _aad(camera_id, ts))))
                    except IntegrityError:
                        log.warning("a frame in buffer segment %s failed its integrity check", path.name)
        except FileNotFoundError:
            pass
        return records

    # -- retention ---------------------------------------------------------------------------------------------------

    def segments(self) -> list[Segment]:
        found: list[Segment] = []
        if not self._root.exists():
            return found
        for camera_dir in self._root.iterdir():
            if not camera_dir.is_dir():
                continue
            for day_dir in camera_dir.iterdir():
                if not day_dir.is_dir():
                    continue
                for path in day_dir.glob("*.seg"):
                    try:
                        stamp = datetime.strptime(day_dir.name + path.stem, "%Y%m%d%H%M%S")
                    except ValueError:
                        continue
                    found.append(Segment(camera_dir.name, stamp.replace(tzinfo=timezone.utc).timestamp(), path))
        found.sort(key=lambda s: s.start)
        return found

    def purge_older_than(self, cutoff: float) -> int:
        """Delete segments that end before ``cutoff``; returns how many."""
        removed = 0
        for segment in self.segments():
            if segment.start + self._segment_seconds <= cutoff and self._delete(segment):
                removed += 1
        self._remove_empty_dirs()
        return removed

    def purge_oldest(self, count: int) -> int:
        """Delete the ``count`` oldest segments of any camera (the disk is short); returns how many."""
        removed = 0
        for segment in self.segments():
            if removed >= count:
                break
            if self._delete(segment):
                removed += 1
        self._remove_empty_dirs()
        return removed

    def oldest(self) -> float | None:
        segments = self.segments()
        return segments[0].start if segments else None

    def _delete(self, segment: Segment) -> bool:
        with self._lock:
            if any(w.path == segment.path for w in self._writers.values()):
                return False
            segment.path.unlink(missing_ok=True)
            return True

    def _remove_empty_dirs(self) -> None:
        if not self._root.exists():
            return
        for camera_dir in self._root.iterdir():
            if not camera_dir.is_dir():
                continue
            for day_dir in camera_dir.iterdir():
                if day_dir.is_dir() and not any(day_dir.iterdir()):
                    try:
                        day_dir.rmdir()
                    except OSError:
                        pass

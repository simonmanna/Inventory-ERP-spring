"""OpenCV helpers: stream intake, JPEG, zone masks and clip encoding.

OpenCV (``opencv-python-headless``, which bundles an LGPL FFmpeg) and NumPy are imported on first use, so the rest
of the agent can be imported and tested without them.
"""

from __future__ import annotations

import logging
import math
import time
import uuid
from collections.abc import Callable, Iterable, Iterator, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from lp_edge.timeutil import iso

log = logging.getLogger(__name__)

#: Containers tried in order. WebM (VP8) plays in browsers; MP4 (MPEG-4 Part 2) and Matroska (MJPEG) are fallbacks
#: for OpenCV builds without libvpx. All three are content types the backend accepts.
CLIP_FORMATS: tuple[tuple[str, str, str], ...] = (
    ("VP80", ".webm", "video/webm"),
    ("mp4v", ".mp4", "video/mp4"),
    ("MJPG", ".mkv", "video/x-matroska"),
)

MAX_CLIP_BYTES = 64 * 1024 * 1024


class NoFrames(Exception):
    """The buffer holds no picture for the period asked."""


class ClipTooLarge(Exception):
    pass


def cv2_module() -> Any:
    try:
        import cv2  # noqa: PLC0415
    except ImportError as exc:  # pragma: no cover - the image always has it
        raise RuntimeError("OpenCV (opencv-python-headless) is not installed") from exc
    return cv2


def np_module() -> Any:
    import numpy  # noqa: PLC0415

    return numpy


def resize_to_width(frame: Any, max_width: int) -> Any:
    """``frame`` scaled down (never up) to at most ``max_width`` pixels wide."""
    height, width = frame.shape[:2]
    if width <= max_width:
        return frame
    cv2 = cv2_module()
    new_height = max(1, int(round(height * max_width / width)))
    return cv2.resize(frame, (max_width, new_height), interpolation=cv2.INTER_AREA)


def encode_jpeg(frame: Any, quality: int = 80) -> bytes:
    cv2 = cv2_module()
    ok, buffer = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), int(quality)])
    if not ok:
        raise RuntimeError("JPEG encoding failed")
    return buffer.tobytes()


def decode_jpeg(data: bytes) -> Any:
    cv2 = cv2_module()
    np = np_module()
    frame = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), cv2.IMREAD_COLOR)
    if frame is None:
        raise ValueError("not a JPEG picture")
    return frame


def jpeg_within(frame: Any, max_bytes: int, quality: int = 85, min_quality: int = 35) -> bytes:
    """A JPEG of ``frame`` no larger than ``max_bytes``: lower quality first, then smaller."""
    cv2 = cv2_module()
    image = frame
    current = quality
    for _ in range(16):
        data = encode_jpeg(image, current)
        if len(data) <= max_bytes:
            return data
        if current > min_quality:
            current = max(min_quality, current - 10)
            continue
        height, width = image.shape[:2]
        if width <= 64 or height <= 64:
            break
        image = cv2.resize(image, (int(width * 0.75), int(height * 0.75)), interpolation=cv2.INTER_AREA)
    raise ValueError("the picture does not fit the size limit")


def polygon_mask(polygon: Sequence[tuple[float, float]], height: int, width: int) -> Any:
    """A uint8 mask (255 inside) of a normalised polygon on a ``height`` × ``width`` picture."""
    cv2 = cv2_module()
    np = np_module()
    mask = np.zeros((height, width), dtype=np.uint8)
    points = np.array(
        [[int(round(x * (width - 1))), int(round(y * (height - 1)))] for x, y in polygon], dtype=np.int32
    )
    cv2.fillPoly(mask, [points], 255)
    return mask


class CaptureSource:
    """One camera stream through OpenCV's FFmpeg backend (RTSP, HTTP, or a ``file:`` for trials).

    The address is held in memory only and never logged.
    """

    def __init__(self, url: str, *, open_timeout_ms: int = 10_000, read_timeout_ms: int = 10_000) -> None:
        self._url = url
        self._is_file = url.startswith("file:")
        self._open_timeout_ms = open_timeout_ms
        self._read_timeout_ms = read_timeout_ms
        self._cap: Any = None
        self._frame_interval = 0.0
        self._next_read = 0.0

    def __repr__(self) -> str:
        return "CaptureSource(<address hidden>)"

    def open(self) -> bool:
        cv2 = cv2_module()
        target = self._url
        if self._is_file:
            target = target[len("file://") :] if target.startswith("file://") else target[len("file:") :]
        params = [
            cv2.CAP_PROP_OPEN_TIMEOUT_MSEC,
            self._open_timeout_ms,
            cv2.CAP_PROP_READ_TIMEOUT_MSEC,
            self._read_timeout_ms,
        ]
        try:
            cap = cv2.VideoCapture(target, cv2.CAP_FFMPEG, params)
        except (TypeError, cv2.error):
            cap = cv2.VideoCapture(target, cv2.CAP_FFMPEG)
        if not cap.isOpened():
            cap.release()
            return False
        if self._is_file:
            fps = cap.get(cv2.CAP_PROP_FPS) or 0.0
            self._frame_interval = 1.0 / fps if 0 < fps < 120 else 1.0 / 15
        self._cap = cap
        return True

    def read(self) -> tuple[bool, Any]:
        if self._cap is None:
            return False, None
        if self._is_file:
            # A recorded file plays at its own pace, as a camera would.
            delay = self._next_read - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            self._next_read = time.monotonic() + self._frame_interval
        return self._cap.read()

    def close(self) -> None:
        if self._cap is not None:
            try:
                self._cap.release()
            finally:
                self._cap = None


@dataclass(frozen=True)
class EncodedClip:
    path: Path
    content_type: str
    started_at: float
    ended_at: float
    frames: int


def stamp(frame: Any, ts: float) -> Any:
    """Burn the capture time (UTC) into the bottom-left corner, as CCTV recorders do."""
    cv2 = cv2_module()
    text = iso(ts).replace("T", " ")
    height, width = frame.shape[:2]
    scale = max(0.35, min(1.0, width / 1280.0))
    (tw, th), base = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, scale, 1)
    cv2.rectangle(frame, (0, height - th - base - 6), (tw + 8, height), (0, 0, 0), -1)
    cv2.putText(frame, text, (4, height - base - 3), cv2.FONT_HERSHEY_SIMPLEX, scale, (255, 255, 255), 1, cv2.LINE_AA)
    return frame


def stamp_jpeg(jpeg: bytes, ts: float, quality: int = 90) -> bytes:
    """A buffered JPEG with its capture time burnt in, for a still kept as evidence (LP1c). Deterministic, so an
    upload resumed after a restart sends the same bytes."""
    return encode_jpeg(stamp(decode_jpeg(jpeg), ts), quality)


def encode_clip(
    frames: Callable[[], Iterable[tuple[float, bytes]]],
    out_dir: Path,
    *,
    fps: float,
    max_bytes: int = MAX_CLIP_BYTES,
    max_hold_seconds: float = 2.0,
) -> EncodedClip:
    """Encode buffered JPEG frames (``frames()`` yields them in time order, and can be called again) as a video.

    The output runs at a constant ``fps``: a frame is repeated until the next one, for at most ``max_hold_seconds``
    (a longer hole in the buffer is skipped, not filled with a frozen picture; the burned-in time shows it). The
    resolution is chosen so the clip fits ``max_bytes``; if it does not, it is encoded again smaller.
    """
    cv2 = cv2_module()
    first = next(iter(frames()), None)
    if first is None:
        raise NoFrames("no picture in the buffer for that period")
    probe = decode_jpeg(first[1])
    height, width = probe.shape[:2]
    out_dir.mkdir(parents=True, exist_ok=True)
    timestamps = [ts for ts, _ in _timestamps(frames())]
    if not timestamps:
        raise NoFrames("no picture in the buffer for that period")
    duration = max(1.0, timestamps[-1] - timestamps[0] + 1.0 / fps)
    shrink = 1.0
    for _ in range(5):
        # OpenCV's FFmpeg writer targets about width × height × fps bits per second.
        allowed_pixels = max_bytes * 8 * 0.8 / duration / fps
        scale = min(1.0, math.sqrt(allowed_pixels / float(width * height))) * shrink
        size = (max(64, int(width * scale) // 2 * 2), max(64, int(height * scale) // 2 * 2))
        writer, path, content_type = _open_writer(cv2, out_dir, fps, size)
        try:
            count, first_ts, last_ts = _write(cv2, writer, frames(), fps, size, max_hold_seconds)
        finally:
            writer.release()
        if count == 0:
            path.unlink(missing_ok=True)
            raise NoFrames("no picture in the buffer for that period")
        if path.stat().st_size <= max_bytes:
            return EncodedClip(path, content_type, first_ts, last_ts, count)
        log.info("clip of %d bytes is over the limit; encoding it smaller", path.stat().st_size)
        path.unlink(missing_ok=True)
        shrink *= 0.7
    raise ClipTooLarge("the clip does not fit the size limit")


def _timestamps(frames: Iterable[tuple[float, bytes]]) -> Iterator[tuple[float, None]]:
    for ts, _ in frames:
        yield ts, None


def _open_writer(cv2: Any, out_dir: Path, fps: float, size: tuple[int, int]) -> tuple[Any, Path, str]:
    for fourcc, extension, content_type in CLIP_FORMATS:
        path = out_dir / f"{uuid.uuid4().hex}{extension}"
        writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*fourcc), fps, size)
        if writer.isOpened():
            return writer, path, content_type
        writer.release()
        path.unlink(missing_ok=True)
    raise RuntimeError("no video encoder is available in this OpenCV build")


def _write(
    cv2: Any, writer: Any, frames: Iterable[tuple[float, bytes]], fps: float, size: tuple[int, int], max_hold: float
) -> tuple[int, float, float]:
    step = 1.0 / fps
    tick: float | None = None
    held: Any = None
    count = 0
    first_ts = last_ts = 0.0
    for ts, data in frames:
        frame = decode_jpeg(data)
        if (frame.shape[1], frame.shape[0]) != size:
            frame = cv2.resize(frame, size, interpolation=cv2.INTER_AREA)
        frame = stamp(frame, ts)
        if tick is None:
            tick = ts
            first_ts = ts
        if held is not None and ts - tick > max_hold:
            # a hole in the buffer: show what was there, then jump
            writer.write(held)
            count += 1
            tick = ts
        while held is not None and tick < ts:
            writer.write(held)
            count += 1
            tick += step
        held = frame
        last_ts = ts
    if held is not None:
        writer.write(held)
        count += 1
    return count, first_ts, last_ts

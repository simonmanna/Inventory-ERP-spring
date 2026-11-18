"""A camera: its stream, its buffer, its analysis, and how far it has been watched.

:class:`CameraObserver` holds the zone profiles of an analysed camera, its gaps and its **watermark**: the latest
instant T such that every observation of the camera about anything up to T has been made. It is no later than the
last frame analysed, nor than any profile's hold (an occupancy period not yet reported, a change awaiting
confirmation). While the camera is not observed (stream lost, agent down, clock off, camera disabled) the watermark
stays where observation stopped; when observation resumes, one ``vision.gap`` per zone covers the hole, and only then
does the watermark move on. The agent combines it with the oldest unsent observation of the camera.

:class:`CameraWorker` runs the threads: a reader that always holds the newest frame (and reconnects with backoff),
and a processor that buffers frames at ``LP_EDGE_BUFFER_FPS`` and analyses them at ``LP_EDGE_ANALYSIS_FPS``.
"""

from __future__ import annotations

import collections
import logging
import threading
from collections.abc import Callable
from concurrent.futures import Future
from dataclasses import dataclass
from typing import Any, Protocol

from lp_edge.detectors.base import Detector
from lp_edge.events import Observation, gap
from lp_edge.profiles import FrameContext, ProfileEnv, ZoneProfile, build_profile, unwrap
from lp_edge.profiles.cash_drawer import CashDrawerProfile
from lp_edge.remote_config import Camera
from lp_edge.timeutil import Clock, SystemClock, iso_or_none
from lp_edge.tracker import ByteTracker

log = logging.getLogger(__name__)

Emit = Callable[[str, list[Observation]], None]


@dataclass(frozen=True)
class Unobserved:
    """Why and since when a camera has not been observed. ``reason`` ``None``: observation has not begun yet."""

    reason: str | None
    since: float


@dataclass(frozen=True)
class Needs:
    classes: frozenset[str]
    frame: bool
    motion: bool


class CameraObserver:
    """Profiles, gaps and watermark of one analysed camera. Thread-safe; no I/O of its own."""

    def __init__(
        self,
        camera: Camera,
        *,
        env: ProfileEnv,
        emit: Emit,
        detector_classes: frozenset[str] | None,
        unobserved: Unobserved,
        build: Callable[..., ZoneProfile] = build_profile,
        start_grace: float = 5.0,
    ) -> None:
        self._lock = threading.RLock()
        self._env = env
        self._emit = emit
        self._build = build
        self._start_grace = start_grace
        self.camera = camera
        self._detector_classes = detector_classes
        self._profiles: dict[str, ZoneProfile] = {}
        self._fingerprints: dict[str, str] = {}
        self._unobserved: Unobserved | None = unobserved
        self._last_frame: float | None = None
        self._calibrations: list[tuple[str, Future]] = []
        self._rebuild(camera, detector_classes)

    # -- configuration -----------------------------------------------------------------------------------------

    def configure(self, camera: Camera, detector_classes: frozenset[str] | None) -> None:
        """Apply new zones (or a new detector): changed zones get new profiles; others keep their state."""
        with self._lock:
            self.camera = camera
            if detector_classes != self._detector_classes:
                self._detector_classes = detector_classes
                self._fingerprints = {}
            self._rebuild(camera, detector_classes)

    def _rebuild(self, camera: Camera, detector_classes: frozenset[str] | None) -> None:
        wanted = {zone.id: zone for zone in camera.zones}
        flushed: list[Observation] = []
        for zone_id in list(self._profiles):
            zone = wanted.get(zone_id)
            if zone is None or zone.fingerprint != self._fingerprints.get(zone_id):
                flushed += self._profiles.pop(zone_id).stop(self._last_frame)
                self._fingerprints.pop(zone_id, None)
        for zone_id, zone in wanted.items():
            if zone_id not in self._profiles:
                self._profiles[zone_id] = self._build(zone, camera.id, self._env, detector_classes)
                self._fingerprints[zone_id] = zone.fingerprint
        if flushed and self._unobserved is None:
            self._emit(camera.id, flushed)

    def needs(self) -> Needs:
        with self._lock:
            profiles = list(self._profiles.values())
        return Needs(
            classes=frozenset().union(*(p.wanted_classes() for p in profiles)) if profiles else frozenset(),
            frame=any(p.needs_frame() for p in profiles),
            motion=any(p.needs_motion() for p in profiles),
        )

    # -- observation -------------------------------------------------------------------------------------------

    @property
    def observing(self) -> bool:
        return self._unobserved is None

    @property
    def unobserved(self) -> Unobserved | None:
        return self._unobserved

    @property
    def last_frame(self) -> float | None:
        return self._last_frame

    def frame(self, context: FrameContext) -> None:
        """Analyse one frame; closes any open gap first."""
        with self._lock:
            observations: list[Observation] = []
            if self._unobserved is not None:
                hole = self._unobserved
                self._unobserved = None
                reason = hole.reason
                if reason is None and context.ts - hole.since > self._start_grace:
                    # A camera that took long to deliver its first picture was not watched meanwhile.
                    reason = "stream_lost"
                if reason is not None and context.ts > hole.since:
                    for zone in self.camera.zones:
                        observations.append(gap(zone.id, self.camera.id, reason, hole.since, context.ts))
                for profile in self._profiles.values():
                    profile.start(context.ts)
            for zone_id, profile in self._profiles.items():
                if profile.started_at is None:
                    profile.start(context.ts)
                try:
                    observations += profile.process(context)
                except Exception:  # noqa: BLE001 - one zone must not stop the others
                    log.exception("zone %s failed on a frame", zone_id)
            observations += self._run_calibrations(context)
            self._last_frame = context.ts
            if observations:
                self._emit(self.camera.id, observations)

    def interrupt(self, reason: str, since: float | None = None, *, report: bool = True) -> None:
        """Observation stops. ``report`` false drops what the profiles held (their times cannot be trusted)."""
        with self._lock:
            if self._unobserved is not None:
                return
            flushed: list[Observation] = []
            for profile in self._profiles.values():
                observations = profile.stop(self._last_frame)
                if report:
                    flushed += observations
            start = since if since is not None else self._last_frame
            self._unobserved = Unobserved(reason, start if start is not None else 0.0)
            if flushed:
                self._emit(self.camera.id, flushed)
            for _, future in self._calibrations:
                if not future.done():
                    future.set_exception(RuntimeError("the camera stopped before it could be calibrated"))
            self._calibrations = []

    def watermark(self) -> float:
        with self._lock:
            if self._unobserved is not None:
                return self._unobserved.since
            values = [self._last_frame] if self._last_frame is not None else []
            values += [h for h in (p.hold() for p in self._profiles.values()) if h is not None]
            return min(values) if values else 0.0

    # -- calibration ---------------------------------------------------------------------------------------------

    def request_calibration(self, zone_id: str) -> Future:
        """Take the next frame as the resting picture of drawer zone ``zone_id``."""
        future: Future = Future()
        with self._lock:
            profile = self._profiles.get(zone_id)
            if profile is None or not isinstance(unwrap(profile), CashDrawerProfile):
                future.set_exception(ValueError("only a cash_drawer zone of this camera can be calibrated"))
            elif self._unobserved is not None:
                future.set_exception(RuntimeError("the camera is not delivering pictures"))
            else:
                self._calibrations.append((zone_id, future))
        return future

    def _run_calibrations(self, context: FrameContext) -> list[Observation]:
        if not self._calibrations or context.frame is None:
            return []
        observations: list[Observation] = []
        for zone_id, future in self._calibrations:
            profile = self._profiles.get(zone_id)
            drawer = None if profile is None else unwrap(profile)
            try:
                if not isinstance(drawer, CashDrawerProfile):
                    raise ValueError("the zone is no longer a cash_drawer zone of this camera")
                observations += drawer.calibrate(context.frame, context.ts)
                future.set_result(True)
            except Exception as exc:  # noqa: BLE001
                future.set_exception(exc)
        self._calibrations = []
        return observations


class FrameSource(Protocol):
    def open(self) -> bool: ...

    def read(self) -> tuple[bool, Any]: ...

    def close(self) -> None: ...


class CameraWorker:
    """The threads of one enabled camera."""

    def __init__(
        self,
        camera: Camera,
        *,
        open_source: Callable[[], FrameSource | None],
        observer: CameraObserver | None,
        buffer: Callable[[str, float, Any], None] | None,
        detector: Callable[[], Detector | None],
        analysis_allowed: Callable[[], bool],
        analysis_fps: float,
        buffer_fps: float,
        analysis_max_width: int,
        stall_seconds: float,
        clock: Clock | None = None,
        resize: Callable[[Any, int], Any] | None = None,
        motion_factory: Callable[[], Any] | None = None,
    ) -> None:
        self.camera = camera
        self.observer = observer
        self._open_source = open_source
        self._buffer = buffer
        self._detector = detector
        self._analysis_allowed = analysis_allowed
        self._analysis_interval = 1.0 / analysis_fps
        self._buffer_interval = 1.0 / buffer_fps
        self._analysis_max_width = analysis_max_width
        self._stall_seconds = stall_seconds
        self._clock = clock or SystemClock()
        self._resize = resize
        self._motion_factory = motion_factory
        self._motion: Any = None
        self._tracker = ByteTracker()
        self._lock = threading.Lock()
        self._new_frame = threading.Event()
        self._stop = threading.Event()
        self._reconnect = threading.Event()
        self._latest: tuple[float, Any] | None = None
        self._last_read: float | None = None
        self._started_at = self._clock.time()
        self._analysed: collections.deque[float] = collections.deque(maxlen=200)
        self._read_count: collections.deque[float] = collections.deque(maxlen=400)
        self._missing_source_logged = False
        self._threads: list[threading.Thread] = []
        self._analysis_failures = 0

    # -- lifecycle -------------------------------------------------------------------------------------------------

    def start(self) -> None:
        name = self.camera.stream_alias or self.camera.id
        self._threads = [
            threading.Thread(target=self._read_loop, name=f"read-{name}", daemon=True),
            threading.Thread(target=self._process_loop, name=f"process-{name}", daemon=True),
        ]
        for thread in self._threads:
            thread.start()

    def reconnect(self) -> None:
        """Close the stream and open it again (its address changed)."""
        self._reconnect.set()

    def stop(self, timeout: float = 10.0) -> None:
        self._stop.set()
        self._new_frame.set()
        for thread in self._threads:
            thread.join(timeout)

    # -- what others read -------------------------------------------------------------------------------------------

    def latest_frame(self, max_age: float = 10.0) -> tuple[float, Any] | None:
        with self._lock:
            latest = self._latest
        if latest is None or self._clock.time() - latest[0] > max_age:
            return None
        return latest

    @property
    def stream_state(self) -> str:
        now = self._clock.time()
        last = self._last_read
        if last is not None and now - last <= self._stall_seconds:
            return "ok"
        if last is None and now - self._started_at <= max(15.0, 3 * self._stall_seconds):
            return "connecting"
        return "lost"

    def fps(self) -> float:
        samples = self._analysed if self.observer is not None else self._read_count
        now = self._clock.time()
        recent = [t for t in list(samples) if now - t <= 10.0]
        if len(recent) < 2:
            return 0.0
        span = max(recent[-1] - recent[0], 1e-6)
        return round((len(recent) - 1) / span, 2)

    def status(self) -> dict[str, Any]:
        return {
            "cameraId": self.camera.id,
            "streamState": self.stream_state,
            "fps": self.fps(),
            "lastFrameAt": iso_or_none(self._last_read),
        }

    # -- threads -----------------------------------------------------------------------------------------------------

    def _read_loop(self) -> None:
        backoff = 1.0
        while not self._stop.is_set():
            self._reconnect.clear()
            source = self._open_source()
            if source is None:
                if not self._missing_source_logged:
                    log.error(
                        "camera %s: no stream address for alias %r on this box; add it with `lp-edge streams set %s`",
                        self.camera.id,
                        self.camera.stream_alias,
                        self.camera.stream_alias,
                    )
                    self._missing_source_logged = True
                self._stop.wait(10.0)
                continue
            self._missing_source_logged = False
            try:
                if not source.open():
                    log.warning("camera %s: the stream did not open; retrying in %.0f s", self.camera.id, backoff)
                else:
                    failures = 0
                    while not self._stop.is_set() and not self._reconnect.is_set():
                        ok, frame = source.read()
                        if not ok or frame is None:
                            failures += 1
                            if failures >= 3:
                                log.warning("camera %s: the stream stopped; reconnecting", self.camera.id)
                                break
                            continue
                        failures = 0
                        backoff = 1.0
                        ts = self._clock.time()
                        with self._lock:
                            self._latest = (ts, frame)
                            self._last_read = ts
                        self._read_count.append(ts)
                        self._new_frame.set()
            except Exception:  # noqa: BLE001 - a broken stream must never end the thread
                log.exception("camera %s: reading failed", self.camera.id)
            finally:
                try:
                    source.close()
                except Exception:  # noqa: BLE001
                    pass
            if self._reconnect.is_set():
                backoff = 1.0
                continue
            self._stop.wait(backoff)
            backoff = min(30.0, backoff * 2)

    def _process_loop(self) -> None:
        next_buffer = 0.0
        next_analysis = 0.0
        last_ts: float | None = None
        while not self._stop.is_set():
            self._new_frame.wait(0.5)
            self._new_frame.clear()
            with self._lock:
                latest = self._latest
            if latest is None or latest[0] == last_ts:
                self._check_stall()
                continue
            ts, frame = latest
            last_ts = ts
            try:
                if self._buffer is not None and ts >= next_buffer:
                    self._buffer(self.camera.id, ts, frame)
                    next_buffer = ts + self._buffer_interval
            except Exception:  # noqa: BLE001
                log.exception("camera %s: buffering failed", self.camera.id)
            if self.observer is not None and ts >= next_analysis:
                next_analysis = ts + self._analysis_interval
                if self._analysis_allowed():
                    self._analyse(ts, frame)

    def _check_stall(self) -> None:
        observer = self.observer
        if observer is None or not observer.observing:
            return
        last = self._last_read
        if last is not None and self._clock.time() - last > self._stall_seconds:
            log.warning("camera %s: no picture for %.0f s", self.camera.id, self._clock.time() - last)
            observer.interrupt("stream_lost")

    def _analyse(self, ts: float, frame: Any) -> None:
        observer = self.observer
        assert observer is not None
        try:
            small = self._resize(frame, self._analysis_max_width) if self._resize is not None else frame
            needs = observer.needs()
            tracks: list[Any] = []
            detector = self._detector()
            if detector is not None and needs.classes & set(detector.classes):
                detections = [d for d in detector.detect(small) if d.cls in needs.classes]
                tracks = self._tracker.update(detections, ts)
            motion = None
            if needs.motion and self._motion_factory is not None:
                if self._motion is None:
                    self._motion = self._motion_factory()
                self._motion.update(small)
                motion = self._motion
            height, width = (small.shape[0], small.shape[1]) if hasattr(small, "shape") else (9, 16)
            context = FrameContext(
                ts=ts, frame=small if needs.frame else None, tracks=tracks, motion=motion, width=width, height=height
            )
        except Exception:  # noqa: BLE001
            self._analysis_failures += 1
            if self._analysis_failures in (1, 10) or self._analysis_failures % 100 == 0:
                log.exception("camera %s: analysis failed (%d times)", self.camera.id, self._analysis_failures)
            observer.interrupt("agent_down", report=False)
            return
        self._analysis_failures = 0
        observer.frame(context)
        self._analysed.append(ts)

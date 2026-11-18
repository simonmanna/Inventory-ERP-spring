"""What every zone profile shares.

A profile turns frames (and the camera's tracks) into observations about one zone. It reports counts and events,
never conclusions; the backend's rules decide what they mean.

The camera's watermark depends on profiles through :meth:`ZoneProfile.hold`: the earliest instant the profile may
still send an observation about. A drawer opening waiting for confirmation, an occupancy period not yet closed, a
presence whose end is not yet confirmed: each holds the watermark back so the backend never concludes "nothing
happened" about a moment the agent has not finished reporting.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from typing import Any, Protocol

from lp_edge.events import ClipPlan, Observation, gap
from lp_edge.geometry import anchor, boundary_distance, point_in_polygon
from lp_edge.remote_config import Zone
from lp_edge.tracker import Track


class MotionView(Protocol):
    def ratio(self, polygon: Sequence[tuple[float, float]]) -> float: ...


@dataclass
class FrameContext:
    """One analysed frame of a camera."""

    ts: float
    frame: Any = None  # BGR picture at analysis size, when a profile needs pixels
    tracks: Sequence[Track] = ()
    motion: MotionView | None = None
    width: int = 16  # analysis picture size (only its aspect ratio matters to profiles)
    height: int = 9


class ReferenceStore(Protocol):
    def load(self, zone_id: str, fingerprint: str) -> tuple[Any, str] | None: ...

    def save(self, zone_id: str, fingerprint: str, reference: Any, kind: str) -> None: ...


@dataclass
class ProfileEnv:
    """What a profile gets from its agent."""

    max_clip_seconds: float = 600.0
    references: ReferenceStore | None = None
    new_clip_id: Callable[[], str] = field(default=lambda: str(uuid.uuid4()))


class ZoneProfile:
    """Base class. Subclasses implement :meth:`process` and, when they keep state, :meth:`_reset` and :meth:`hold`."""

    def __init__(self, zone: Zone, camera_id: str, env: ProfileEnv, detector_classes: frozenset[str] | None) -> None:
        self.zone = zone
        self.camera_id = camera_id
        self.env = env
        self.detector_classes = detector_classes
        self.started_at: float | None = None
        self.min_confidence = zone.num("minConfidence", 0.5)

    # -- what the camera must provide ------------------------------------------------------------------------

    def wanted_classes(self) -> frozenset[str]:
        """Detector classes this profile reads (empty: it needs no detector)."""
        return frozenset()

    def needs_frame(self) -> bool:
        return False

    def needs_motion(self) -> bool:
        return False

    # -- lifecycle ---------------------------------------------------------------------------------------------

    def start(self, ts: float) -> None:
        """Observation of the zone begins (or resumes after a gap) at ``ts``."""
        self.started_at = ts
        self._reset(ts)

    def stop(self, ts: float | None) -> list[Observation]:
        """Observation is interrupted after ``ts`` (the last frame seen); returns what can still be reported."""
        observations = self._flush(ts) if (ts is not None and self.started_at is not None) else []
        self.started_at = None
        return observations

    def process(self, frame: FrameContext) -> list[Observation]:
        raise NotImplementedError

    def hold(self) -> float | None:
        """The earliest instant this profile may still report about, or ``None``; only while observing."""
        if self.started_at is None:
            return None
        return self._hold()

    def _hold(self) -> float | None:
        return None

    def _reset(self, ts: float) -> None:
        pass

    def _flush(self, ts: float) -> list[Observation]:
        return []

    # -- helpers -------------------------------------------------------------------------------------------------

    def observation(
        self,
        event_type: str,
        ts: float,
        payload: dict[str, Any],
        confidence: float | None,
        clip: ClipPlan | None = None,
    ) -> Observation:
        return Observation(event_type, self.zone.id, self.camera_id, ts, payload, confidence, "observed", clip)

    def clip_around(self, ts: float) -> ClipPlan:
        """A clip from ``preRollSeconds`` before ``ts`` to ``postRollSeconds`` after."""
        pre = max(0.0, self.zone.num("preRollSeconds", 10))
        post = max(0.0, self.zone.num("postRollSeconds", 10))
        return self.clip_span(ts - pre, ts + post, keep="start")

    def clip_span(self, start: float, end: float, keep: str = "end") -> ClipPlan:
        """A clip of ``start``–``end``, cut to the longest clip allowed (keeping its ``end`` or its ``start``)."""
        longest = self.env.max_clip_seconds
        if end - start > longest:
            if keep == "end":
                start = end - longest
            else:
                end = start + longest
        return ClipPlan(self.env.new_clip_id(), start, end)


def unwrap(profile: ZoneProfile) -> ZoneProfile:
    """The profile itself, or the one a wrapper runs (activity research, LP7, runs a zone's profile unchanged)."""
    inner = getattr(profile, "inner", None)
    return inner if isinstance(inner, ZoneProfile) else profile


def inside(zone: Zone, track: Track, mode: str) -> bool:
    return point_in_polygon(anchor(track.box, mode), zone.polygon)


def region(zone: Zone, track: Track, mode: str, margin: float) -> str | None:
    """``in`` or ``out`` of a polygon zone, or ``None`` within ``margin`` of its edge."""
    point = anchor(track.box, mode)
    if boundary_distance(point, zone.polygon) < margin:
        return None
    return "in" if point_in_polygon(point, zone.polygon) else "out"


class UnavailableProfile(ZoneProfile):
    """A zone this agent cannot observe (no detector for its classes, or a shape its type cannot use).

    It reports itself as ``vision.gap`` (``disabled``) in periods of ``chunkSeconds``, so the camera's watermark
    never claims the zone was watched.
    """

    def __init__(
        self,
        zone: Zone,
        camera_id: str,
        env: ProfileEnv,
        detector_classes: frozenset[str] | None,
        reason: str,
        chunk_seconds: float = 300.0,
    ) -> None:
        super().__init__(zone, camera_id, env, detector_classes)
        self.reason = reason
        self.chunk_seconds = chunk_seconds
        self._from: float | None = None

    def _reset(self, ts: float) -> None:
        self._from = ts

    def process(self, frame: FrameContext) -> list[Observation]:
        if self._from is not None and frame.ts - self._from >= self.chunk_seconds:
            observation = gap(self.zone.id, self.camera_id, "disabled", self._from, frame.ts, detail=self.reason)
            self._from = frame.ts
            return [observation]
        return []

    def _flush(self, ts: float) -> list[Observation]:
        if self._from is not None and ts > self._from:
            return [gap(self.zone.id, self.camera_id, "disabled", self._from, ts, detail=self.reason)]
        return []

    def _hold(self) -> float | None:
        return self._from

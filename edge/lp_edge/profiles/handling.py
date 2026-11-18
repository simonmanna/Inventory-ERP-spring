"""Bar counter zones (LP3): bottles taken from, returned to, and tilted over the zone.

The polygon covers where the bottles stand. For each tracked object of the zone's ``objects`` (``bottle``):

- ``taken`` when an object that stood in the zone leaves it;
- ``returned`` when an object enters the zone and stays ``minReturnSeconds`` (2 s), reported at its entry;
- ``tilted`` when an object from the zone is wider than tall on the picture (width ÷ height at least
  ``tiltAspectRatio``, 0.9) for ``minTiltSeconds`` (1 s) or more; reported at the start, with ``durationSeconds``.

These are counts for LP3; they are heuristics until a pilot measures them.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field

from lp_edge.events import HANDLING, Observation
from lp_edge.profiles.base import FrameContext, ProfileEnv, ZoneProfile, region
from lp_edge.remote_config import Zone
from lp_edge.tracker import Track

FORGET_SECONDS = 30.0


@dataclass
class _ObjectState:
    region: str
    seen: float
    settled: bool  # stood in the zone (first seen inside, or returned)
    entered_at: float | None = None
    tilt_since: float | None = None
    tilt_ratio: list[float] = field(default_factory=list)


@dataclass(frozen=True)
class HandlingEvent:
    track: Track
    action: str
    at: float
    duration: float | None = None


class HandlingTracker:
    """Pure logic over tracks; see the module description."""

    def __init__(
        self,
        zone: Zone,
        *,
        anchor_mode: str = "center",
        margin: float = 0.01,
        min_return_seconds: float = 2.0,
        tilt_aspect_ratio: float = 0.9,
        min_tilt_seconds: float = 1.0,
    ) -> None:
        self.zone = zone
        self.anchor_mode = anchor_mode
        self.margin = margin
        self.min_return_seconds = max(0.0, min_return_seconds)
        self.tilt_aspect_ratio = tilt_aspect_ratio
        self.min_tilt_seconds = max(0.0, min_tilt_seconds)
        self._objects: dict[int, _ObjectState] = {}

    def reset(self) -> None:
        self._objects = {}

    def pending_since(self) -> float | None:
        pending = [s.entered_at for s in self._objects.values() if s.entered_at is not None]
        pending += [s.tilt_since for s in self._objects.values() if s.tilt_since is not None]
        return min(pending) if pending else None

    def update(self, tracks: Iterable[Track], ts: float, aspect: float) -> list[HandlingEvent]:
        events: list[HandlingEvent] = []
        for track in tracks:
            where = region(self.zone, track, self.anchor_mode, self.margin)
            state = self._objects.get(track.id)
            if state is None:
                if where is None:
                    continue
                state = _ObjectState(region=where, seen=ts, settled=where == "in")
                self._objects[track.id] = state
            state.seen = ts
            if where is not None and where != state.region:
                if where == "out":
                    if state.settled:
                        events.append(HandlingEvent(track, "taken", ts))
                    state.settled = False
                    state.entered_at = None
                else:
                    state.entered_at = ts
                state.region = where
            if state.entered_at is not None and state.region == "in" and ts - state.entered_at >= self.min_return_seconds:
                events.append(HandlingEvent(track, "returned", state.entered_at))
                state.entered_at = None
                state.settled = True
            events.extend(self._tilt(track, state, ts, aspect))
        for track_id in [k for k, s in self._objects.items() if ts - s.seen > FORGET_SECONDS]:
            del self._objects[track_id]
        return events

    def _tilt(self, track: Track, state: _ObjectState, ts: float, aspect: float) -> list[HandlingEvent]:
        x1, y1, x2, y2 = track.box
        if y2 <= y1:
            return []
        ratio = (x2 - x1) * aspect / (y2 - y1)
        from_zone = state.settled or state.region == "out"
        if ratio >= self.tilt_aspect_ratio and from_zone:
            if state.tilt_since is None:
                state.tilt_since = ts
            return []
        if state.tilt_since is not None and ratio < self.tilt_aspect_ratio * 0.8:
            start, state.tilt_since = state.tilt_since, None
            if ts - start >= self.min_tilt_seconds:
                return [HandlingEvent(track, "tilted", start, ts - start)]
        return []


class HandlingProfile(ZoneProfile):
    def __init__(
        self,
        zone: Zone,
        camera_id: str,
        env: ProfileEnv,
        detector_classes: frozenset[str] | None,
        objects: list[str] | None = None,
    ) -> None:
        super().__init__(zone, camera_id, env, detector_classes)
        self.objects = objects if objects is not None else zone.names("objects", ["bottle"])
        self.tracker = HandlingTracker(
            zone,
            anchor_mode=zone.text("anchor", "center"),
            margin=zone.num("edgeMargin", 0.01),
            min_return_seconds=zone.num("minReturnSeconds", 2),
            tilt_aspect_ratio=zone.num("tiltAspectRatio", 0.9),
            min_tilt_seconds=zone.num("minTiltSeconds", 1),
        )
        self.keep_clips = zone.flag("keepClips", False)

    def wanted_classes(self) -> frozenset[str]:
        return frozenset(self.objects)

    def _reset(self, ts: float) -> None:
        self.tracker.reset()

    def _hold(self) -> float | None:
        return self.tracker.pending_since()

    def process(self, frame: FrameContext) -> list[Observation]:
        aspect = frame.width / frame.height if frame.height else 16 / 9
        tracks = [t for t in frame.tracks if t.cls in self.objects and t.mean_score >= self.min_confidence]
        observations = []
        for event in self.tracker.update(tracks, frame.ts, aspect):
            payload: dict[str, object] = {"object": event.track.cls, "action": event.action, "trackId": event.track.id}
            if event.duration is not None:
                payload["durationSeconds"] = round(event.duration, 3)
            clip = self.clip_around(event.at) if self.keep_clips else None
            observations.append(self.observation(HANDLING, event.at, payload, event.track.mean_score, clip))
        return observations

"""Counting zones: ``entrance``, ``kitchen_pass``, ``cafe_counter``, ``storage`` and ``dining``.

A tracked object of the zone's ``classes`` that crosses the zone is reported as ``vision.line_crossed`` with a
``direction``:

- on a **counting line** (two points, A → B) the direction is ``in`` when the object ends on the ``inSide`` of the
  line (``left`` or ``right`` as seen walking from A to B on the picture; ``right`` when not set) and ``out`` when it
  ends on the other side. An object passing beyond either end of the line is not counted;
- on a **polygon** the direction is ``in`` when the object's anchor enters it and ``out`` when it leaves.

An object must be ``edgeMargin`` (0.01 of the picture) clear of the line or edge before its side counts, so jitter on
the line is not counted. With ``occupancyEverySeconds`` (``dining``: 300 s) a polygon zone also reports, per class,
the most objects inside during each period. Clips are kept only with ``keepClips`` true: the buffer already holds the
video, and an investigator can ask for any period.
"""

from __future__ import annotations

import logging
from collections.abc import Iterable

from lp_edge.events import LINE_CROSSED, OCCUPANCY, Observation
from lp_edge.geometry import anchor, line_distance, projection
from lp_edge.profiles.base import FrameContext, ProfileEnv, ZoneProfile, inside, region
from lp_edge.profiles.occupancy import OccupancyWindow, Period
from lp_edge.remote_config import ZONE_DEFAULTS, Zone
from lp_edge.tracker import Track

log = logging.getLogger(__name__)

COUNTING_TYPES = ("entrance", "kitchen_pass", "cafe_counter", "storage", "dining")
FEET_ANCHOR_TYPES = ("entrance", "storage")
FORGET_SECONDS = 10.0


class CrossingCounter:
    """Which side each track is on, and when it changes. Pure logic."""

    def __init__(self, zone: Zone, anchor_mode: str, margin: float = 0.01, span_tolerance: float = 0.05) -> None:
        self.zone = zone
        self.anchor_mode = anchor_mode
        self.margin = max(0.0, margin)
        self.span_tolerance = span_tolerance
        self.in_side = zone.in_side or "right"
        self._sides: dict[int, tuple[str, float]] = {}

    def reset(self) -> None:
        self._sides = {}

    def update(self, tracks: Iterable[Track], ts: float) -> list[tuple[Track, str]]:
        crossings = []
        for track in tracks:
            side = self._side(track)
            if side is None:
                continue
            if side == "beyond":
                self._sides.pop(track.id, None)
                continue
            previous = self._sides.get(track.id)
            self._sides[track.id] = (side, ts)
            if previous is not None and previous[0] != side:
                crossings.append((track, side))
        for track_id in [k for k, (_, seen) in self._sides.items() if ts - seen > FORGET_SECONDS]:
            del self._sides[track_id]
        return crossings

    def _side(self, track: Track) -> str | None:
        """``in``, ``out``, ``beyond`` (past the end of a line) or ``None`` (too close to tell)."""
        if not self.zone.is_line:
            return region(self.zone, track, self.anchor_mode, self.margin)
        a, b = self.zone.polygon
        point = anchor(track.box, self.anchor_mode)
        along = projection(a, b, point)
        if along < -self.span_tolerance or along > 1 + self.span_tolerance:
            return "beyond"
        distance = line_distance(a, b, point)
        if abs(distance) < self.margin:
            return None
        side = "right" if distance > 0 else "left"
        return "in" if side == self.in_side else "out"


class CountingProfile(ZoneProfile):
    def __init__(
        self,
        zone: Zone,
        camera_id: str,
        env: ProfileEnv,
        detector_classes: frozenset[str] | None,
        classes: list[str] | None = None,
    ) -> None:
        super().__init__(zone, camera_id, env, detector_classes)
        configured = zone.names("classes", ZONE_DEFAULTS.get(zone.zone_type, {}).get("classes", ["person"]))
        self.classes = classes if classes is not None else configured
        default_crossing = [c for c in self.classes if c != "person"] if zone.zone_type == "dining" else self.classes
        self.crossing_classes = set(zone.names("crossingClasses", default_crossing)) & set(self.classes)
        default_anchor = "bottom" if zone.zone_type in FEET_ANCHOR_TYPES else "center"
        self.anchor_mode = zone.text("anchor", default_anchor)
        self.counter = CrossingCounter(zone, self.anchor_mode, zone.num("edgeMargin", 0.01))
        self.keep_clips = zone.flag("keepClips", False)
        every = zone.params.get("occupancyEverySeconds")
        self.windows: dict[str, OccupancyWindow] = {}
        if every is not None and not zone.is_line:
            period = max(10.0, zone.num("occupancyEverySeconds", 300))
            self.windows = {cls: OccupancyWindow(period) for cls in self.classes}

    def wanted_classes(self) -> frozenset[str]:
        return frozenset(self.classes)

    def _reset(self, ts: float) -> None:
        self.counter.reset()
        for window in self.windows.values():
            window.start(ts)

    def process(self, frame: FrameContext) -> list[Observation]:
        tracks = [t for t in frame.tracks if t.cls in self.classes and t.mean_score >= self.min_confidence]
        observations = []
        for track, direction in self.counter.update([t for t in tracks if t.cls in self.crossing_classes], frame.ts):
            payload = {"class": track.cls, "direction": direction, "count": 1, "trackId": track.id}
            clip = self.clip_around(frame.ts) if self.keep_clips else None
            observations.append(self.observation(LINE_CROSSED, frame.ts, payload, track.mean_score, clip))
        for cls, window in self.windows.items():
            present = [t for t in tracks if t.cls == cls and inside(self.zone, t, self.anchor_mode)]
            score = sum(t.mean_score for t in present) / len(present) if present else None
            for period in window.add(frame.ts, len(present), score):
                observations.append(self._occupancy(cls, period))
        return observations

    def _flush(self, ts: float) -> list[Observation]:
        observations = []
        for cls, window in self.windows.items():
            period = window.flush(ts)
            if period is not None:
                observations.append(self._occupancy(cls, period))
        return observations

    def _hold(self) -> float | None:
        starts = [w.start_ts for w in self.windows.values() if w.start_ts is not None]
        return min(starts) if starts else None

    def _occupancy(self, cls: str, period: Period) -> Observation:
        confidence = period.mean_score if (period.max_count > 0 and period.mean_score is not None) else 0.8
        payload = {"count": period.max_count, "class": cls, "periodSeconds": round(period.seconds, 3)}
        return self.observation(OCCUPANCY, period.ended_at, payload, confidence)

"""Baseline activity recognizers (LP7, research): simple rules over object tracks, so the research pipeline (segments,
``vision.activity``, reviews, scores) runs end to end before any model is chosen. No model ships.

Both read only objects that are not ``person`` and only where they are on the picture:

- ``object_carried_out``: an object that stood inside the zone (``in`` side of a counting line, or inside a polygon)
  leaves it. Standing means staying within 0.03 of the picture of one place for at least 1 s; an object carried
  through without stopping is not reported. The segment runs from the last moment it stood inside to the first frame
  it is seen outside;
- ``object_left_behind``: an object stays still inside the zone (its anchor moves less than 0.02 of the picture) for
  ``leftBehindSeconds`` (60 s) or more. The segment is the still period, reported when the object moves, leaves or is
  lost.

These are deliberately crude; their precision is what the research reviews measure.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass

from lp_edge.activities.base import BaseRecognizer, Segment
from lp_edge.geometry import Point, anchor, line_distance, projection
from lp_edge.profiles.base import FrameContext, region
from lp_edge.remote_config import Zone
from lp_edge.tracker import Track

FORGET_SECONDS = 10.0
LOST_SECONDS = 5.0
MOVE_DISTANCE = 0.03
STILL_DISTANCE = 0.02
REST_SECONDS = 1.0


def side(zone: Zone, track: Track, anchor_mode: str = "center", margin: float = 0.01) -> str | None:
    """``in`` or ``out`` of the zone (a polygon, or the ``inSide`` of a counting line), or ``None`` when too close to
    tell or beyond the ends of the line."""
    if not zone.is_line:
        return region(zone, track, anchor_mode, margin)
    a, b = zone.polygon
    point = anchor(track.box, anchor_mode)
    along = projection(a, b, point)
    if along < -0.05 or along > 1.05:
        return None
    distance = line_distance(a, b, point)
    if abs(distance) < margin:
        return None
    named = "right" if distance > 0 else "left"
    return "in" if named == (zone.in_side or "right") else "out"


def _distance(a: Point, b: Point) -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])


@dataclass
class _Carried:
    place: Point  # where it has been since ``since``
    since: float
    rest_at: float | None  # the last moment it had stood in one place for REST_SECONDS
    seen: float


class ObjectCarriedOut(BaseRecognizer):
    name = "object_carried_out"
    version = "1"
    labels = ("object_carried_out",)

    def __init__(self, anchor_mode: str = "center", rest_seconds: float = REST_SECONDS) -> None:
        self.anchor_mode = anchor_mode
        self.rest_seconds = max(0.0, rest_seconds)
        self._inside: dict[int, _Carried] = {}

    @classmethod
    def from_zone(cls, zone: Zone) -> ObjectCarriedOut:
        return cls(zone.text("anchor", "center"))

    def reset(self) -> None:
        self._inside = {}

    def observe(self, frame: FrameContext, tracks: Sequence[Track], zone: Zone) -> list[Segment]:
        segments = []
        for track in tracks:
            if track.cls == "person":
                continue
            where = side(zone, track, self.anchor_mode)
            point = anchor(track.box, self.anchor_mode)
            state = self._inside.get(track.id)
            if state is not None and frame.ts - state.seen > FORGET_SECONDS:
                del self._inside[track.id]
                state = None
            if where == "in":
                if state is None:
                    self._inside[track.id] = _Carried(point, frame.ts, None, frame.ts)
                    continue
                state.seen = frame.ts
                if _distance(point, state.place) > MOVE_DISTANCE:
                    state.place, state.since = point, frame.ts
                elif frame.ts - state.since >= self.rest_seconds:
                    state.rest_at = frame.ts
                continue
            if where == "out" and state is not None:
                del self._inside[track.id]
                if state.rest_at is not None:
                    segments.append(Segment(self.labels[0], state.rest_at, frame.ts, round(track.mean_score, 3),
                                            (track.id,)))
        for track_id in [k for k, s in self._inside.items() if frame.ts - s.seen > FORGET_SECONDS]:
            del self._inside[track_id]
        return segments


@dataclass
class _Still:
    point: Point
    since: float
    last: float
    score: float


class ObjectLeftBehind(BaseRecognizer):
    name = "object_left_behind"
    version = "1"
    labels = ("object_left_behind",)

    def __init__(self, anchor_mode: str = "center", min_still_seconds: float = 60.0) -> None:
        self.anchor_mode = anchor_mode
        self.min_still = max(0.0, min_still_seconds)
        self._still: dict[int, _Still] = {}

    @classmethod
    def from_zone(cls, zone: Zone) -> ObjectLeftBehind:
        return cls(zone.text("anchor", "center"), zone.num("leftBehindSeconds", 60))

    def reset(self) -> None:
        self._still = {}

    def pending_since(self) -> float | None:
        qualified = [s.last for s in self._still.values() if s.last - s.since >= self.min_still]
        return min(qualified) if qualified else None

    def observe(self, frame: FrameContext, tracks: Sequence[Track], zone: Zone) -> list[Segment]:
        segments = []
        for track in tracks:
            if track.cls == "person":
                continue
            where = side(zone, track, self.anchor_mode)
            point = anchor(track.box, self.anchor_mode)
            still = self._still.get(track.id)
            if still is not None and frame.ts - still.last > LOST_SECONDS:
                segments += self._close(track.id)
                still = None
            if where != "in":
                if where == "out" and still is not None:
                    segments += self._close(track.id)
                continue
            if still is not None and _distance(point, still.point) <= STILL_DISTANCE:
                still.last = frame.ts
                still.score = track.mean_score
                continue
            if still is not None:
                segments += self._close(track.id)
            self._still[track.id] = _Still(point, frame.ts, frame.ts, track.mean_score)
        for track_id in [k for k, s in self._still.items() if frame.ts - s.last > LOST_SECONDS]:
            segments += self._close(track_id)
        return segments

    def flush(self, ts: float) -> list[Segment]:
        segments = []
        for track_id in list(self._still):
            segments += self._close(track_id)
        return segments

    def _close(self, track_id: int) -> list[Segment]:
        still = self._still.pop(track_id)
        if still.last - still.since < self.min_still:
            return []
        return [Segment(self.labels[0], still.since, still.last, round(still.score, 3), (track_id,))]

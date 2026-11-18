"""Scan areas (LP5, feeds rules V-R1 and V-R2): items passed over a checkout's scanner, and items carried past it.

The polygon covers the scanner: where an item is held to be read. ``flowDirection`` (``left_to_right``,
``right_to_left``, ``top_to_bottom``, ``bottom_to_top``) says which way items travel on the picture, from the
unscanned side (the belt, the basket) to the bagging side. Along that direction the picture falls in three parts:
before the scanner (the **input** side), the scanner's own band, and beyond it (the **output** side). Across it, the
checkout's lane is the scanner's extent widened by ``laneMargin`` (0.15 of the picture) on each side; what moves
outside the lane is ignored.

For each tracked object of the zone's ``classes`` (``item``):

- seen on the input side, then on the output side, having been inside the polygon on the way: one
  ``vision.line_crossed`` with direction ``out`` and ``count`` 1 (an item passed over the scanner);
- the same without ever being inside the polygon: ``vision.handling`` with action ``bypassed`` (an item carried past
  the scanner), ``durationSeconds`` from leaving the input side, and a clip of the whole passage with the zone's
  ``preRollSeconds`` and ``postRollSeconds``;
- seen on the output side, then on the input side through the polygon: ``vision.line_crossed`` direction ``in`` (an
  item taken back over the scanner). An item taken back around the scanner is not reported.

An object must be ``edgeMargin`` (0.01 of the picture) clear of the scanner's band before its side counts, so jitter
at the edge is not counted. An object first seen over the scanner counts only once it has been on one side and then
on the other. Whether the scanner *read* the item is the till's business: the backend compares these counts with the
sale. They are heuristics until a pilot measures them (target: count error at most 5 % per transaction batch).
"""

from __future__ import annotations

import logging
from collections.abc import Iterable, Sequence
from dataclasses import dataclass

from lp_edge.events import HANDLING, LINE_CROSSED, Observation
from lp_edge.geometry import Point, anchor, point_in_polygon
from lp_edge.profiles.base import FrameContext, ProfileEnv, ZoneProfile
from lp_edge.remote_config import FLOW_DIRECTIONS, ZONE_DEFAULTS, Zone
from lp_edge.tracker import Track

log = logging.getLogger(__name__)

FORGET_SECONDS = 10.0
INPUT = "input"
OUTPUT = "output"
BAND = "band"
AWAY = "away"


def flow_coordinates(point: Point, direction: str) -> Point:
    """``(along, across)``: ``along`` grows from the unscanned side to the bagging side, both from 0 to 1."""
    x, y = point
    if direction == "right_to_left":
        return 1.0 - x, y
    if direction == "top_to_bottom":
        return y, x
    if direction == "bottom_to_top":
        return 1.0 - y, x
    return x, y


@dataclass
class _Item:
    side: str | None  # the last side (input or output) the item was seen on; None until it has been on one
    left_at: float | None  # the last time it was seen on that side
    touched: bool  # inside the polygon since it left that side
    seen: float


@dataclass(frozen=True)
class Transit:
    """An object that went from one side of the scanner to the other."""

    track: Track
    direction: str  # "out": input → output; "in": output → input
    scanned: bool  # it went through the polygon
    started_at: float  # last seen on the side it came from
    at: float  # first seen on the other side


class ScanTransits:
    """Which side of the scanner each track is on, and when it reaches the other. Pure logic."""

    def __init__(
        self,
        polygon: Sequence[Point],
        direction: str = "left_to_right",
        lane_margin: float = 0.15,
        margin: float = 0.01,
        anchor_mode: str = "center",
    ) -> None:
        if len(polygon) < 3:
            raise ValueError("a scan area is a polygon of 3 or more points")
        if direction not in FLOW_DIRECTIONS:
            raise ValueError("flowDirection must be one of " + ", ".join(FLOW_DIRECTIONS))
        self.polygon = tuple(polygon)
        self.direction = direction
        self.margin = max(0.0, margin)
        self.anchor_mode = anchor_mode
        coords = [flow_coordinates(p, direction) for p in self.polygon]
        self.start = min(c[0] for c in coords)
        self.end = max(c[0] for c in coords)
        lane = max(0.0, lane_margin)
        self.lane = (min(c[1] for c in coords) - lane, max(c[1] for c in coords) + lane)
        self._items: dict[int, _Item] = {}

    def reset(self) -> None:
        self._items = {}

    def where(self, point: Point) -> str:
        """``input``, ``output``, ``band`` (over the scanner or beside it, or within the margin) or ``away``."""
        along, across = flow_coordinates(point, self.direction)
        if across < self.lane[0] or across > self.lane[1]:
            return AWAY
        if along < self.start - self.margin:
            return INPUT
        if along > self.end + self.margin:
            return OUTPUT
        return BAND

    def update(self, tracks: Iterable[Track], ts: float) -> list[Transit]:
        transits: list[Transit] = []
        for track in tracks:
            point = anchor(track.box, self.anchor_mode)
            where = self.where(point)
            item = self._items.get(track.id)
            if item is None or ts - item.seen > FORGET_SECONDS:
                item = _Item(side=None, left_at=None, touched=False, seen=ts)
                self._items[track.id] = item
            item.seen = ts
            if where == AWAY:
                continue
            if where == BAND:
                if item.side is not None and point_in_polygon(point, self.polygon):
                    item.touched = True
                continue
            if item.side is None or where == item.side:
                # first seen on a side, or back on the side it was on without reaching the other: start again
                item.side, item.left_at, item.touched = where, ts, False
                continue
            direction = "out" if where == OUTPUT else "in"
            assert item.left_at is not None
            transits.append(Transit(track, direction, item.touched, item.left_at, ts))
            item.side, item.left_at, item.touched = where, ts, False
        for track_id in [k for k, item in self._items.items() if ts - item.seen > FORGET_SECONDS]:
            del self._items[track_id]
        return transits


class ScanProfile(ZoneProfile):
    def __init__(
        self,
        zone: Zone,
        camera_id: str,
        env: ProfileEnv,
        detector_classes: frozenset[str] | None,
        classes: list[str] | None = None,
    ) -> None:
        super().__init__(zone, camera_id, env, detector_classes)
        configured = zone.names("classes", ZONE_DEFAULTS["scan_area"]["classes"])
        self.classes = classes if classes is not None else configured
        direction = zone.text("flowDirection", "left_to_right")
        if direction not in FLOW_DIRECTIONS:
            log.warning("zone %s: flowDirection %r is not known; using left_to_right", zone.id, direction)
            direction = "left_to_right"
        self.transits = ScanTransits(
            zone.polygon,
            direction,
            min(1.0, max(0.0, zone.num("laneMargin", 0.15))),
            zone.num("edgeMargin", 0.01),
            zone.text("anchor", "center"),
        )
        self.keep_clips = zone.flag("keepClips", False)

    def wanted_classes(self) -> frozenset[str]:
        return frozenset(self.classes)

    def _reset(self, ts: float) -> None:
        self.transits.reset()

    def process(self, frame: FrameContext) -> list[Observation]:
        tracks = [t for t in frame.tracks if t.cls in self.classes and t.mean_score >= self.min_confidence]
        observations = []
        for transit in self.transits.update(tracks, frame.ts):
            track = transit.track
            if transit.scanned:
                payload: dict[str, object] = {"class": track.cls, "direction": transit.direction, "count": 1,
                                              "trackId": track.id}
                clip = self.clip_around(transit.at) if self.keep_clips else None
                observations.append(self.observation(LINE_CROSSED, transit.at, payload, track.mean_score, clip))
            elif transit.direction == "out":
                payload = {"object": track.cls, "action": "bypassed", "trackId": track.id,
                           "durationSeconds": round(transit.at - transit.started_at, 3)}
                pre = max(0.0, self.zone.num("preRollSeconds", 10))
                post = max(0.0, self.zone.num("postRollSeconds", 10))
                clip = self.clip_span(transit.started_at - pre, transit.at + post, keep="end")
                observations.append(self.observation(HANDLING, transit.at, payload, track.mean_score, clip))
        return observations

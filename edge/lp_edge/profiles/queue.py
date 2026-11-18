"""Queue zones (LP6b): how long people wait in a line, and how many wait.

The polygon covers the waiting line. A tracked object of the zone's ``classes`` (``person``) whose anchor (``anchor``,
default ``bottom``: the feet) is inside the polygon is waiting. The wait ends when it is seen outside the polygon
(``edgeMargin``, 0.01 of the picture, clear of the edge) or not seen inside, nor at the edge, for 10 s; it ends at
the last moment it was seen there. Someone standing on the edge keeps waiting. The zone reports:

- ``vision.dwell`` for each wait of ``minWaitSeconds`` (5 s) or more, at its end (``occurredAt`` = ``endedAt``):
  ``class``, ``startedAt``, ``endedAt``, ``durationSeconds``, ``maxCount`` 1 and ``method`` ``track``. Someone who
  only walks through the line does not wait. A wait still open when observation stops ends at the last moment it was
  seen;
- ``vision.occupancy`` every ``occupancyEverySeconds`` (60 s), per class: the most objects inside during the period,
  0 included, at the period's end.

Waits are per track: the tracker keeps an identity through short misses; someone lost for longer comes back as a new
wait. The zone counts people; it never identifies anyone and keeps no clip.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any

from lp_edge.events import DWELL, OCCUPANCY, Observation
from lp_edge.profiles.base import FrameContext, ProfileEnv, ZoneProfile, inside, region
from lp_edge.profiles.occupancy import OccupancyWindow, Period
from lp_edge.remote_config import ZONE_DEFAULTS, Zone
from lp_edge.timeutil import iso
from lp_edge.tracker import Track

FORGET_SECONDS = 10.0
EMPTY_CONFIDENCE = 0.8


@dataclass
class _Open:
    cls: str
    started_at: float
    last_inside: float
    score_total: float
    score_count: int


@dataclass(frozen=True)
class Wait:
    track_id: int
    cls: str
    started_at: float
    ended_at: float
    mean_score: float | None

    @property
    def seconds(self) -> float:
        return self.ended_at - self.started_at


class QueueWaits:
    """One wait per track, from its first sighting inside to its last. Pure logic."""

    def __init__(self, zone: Zone, anchor_mode: str = "bottom", margin: float = 0.01,
                 lost_seconds: float = FORGET_SECONDS) -> None:
        self.zone = zone
        self.anchor_mode = anchor_mode
        self.margin = max(0.0, margin)
        self.lost_seconds = max(0.0, lost_seconds)
        self._open: dict[int, _Open] = {}

    def reset(self) -> None:
        self._open = {}

    def pending_since(self) -> float | None:
        """The earliest end an open wait may still be reported with."""
        return min((w.last_inside for w in self._open.values()), default=None)

    def update(self, tracks: Iterable[Track], ts: float) -> list[Wait]:
        ended: list[Wait] = []
        for track in tracks:
            wait = self._open.get(track.id)
            if wait is not None and ts - wait.last_inside > self.lost_seconds:
                ended.append(self._end(track.id))  # lost for too long: a new wait
                wait = None
            where = region(self.zone, track, self.anchor_mode, self.margin)
            if where == "in":
                if wait is None:
                    self._open[track.id] = _Open(track.cls, ts, ts, track.mean_score, 1)
                else:
                    wait.last_inside = ts
                    wait.score_total += track.mean_score
                    wait.score_count += 1
            elif where is None and wait is not None:
                wait.last_inside = ts  # on the edge: still waiting
            elif where == "out" and wait is not None:
                ended.append(self._end(track.id))
        for track_id in [k for k, w in self._open.items() if ts - w.last_inside > self.lost_seconds]:
            ended.append(self._end(track_id))
        return ended

    def flush(self) -> list[Wait]:
        """Observation stops: every open wait ends at the last moment it was seen inside."""
        ended = [self._end(track_id) for track_id in list(self._open)]
        self._open = {}
        return ended

    def _end(self, track_id: int) -> Wait:
        w = self._open.pop(track_id)
        mean = w.score_total / w.score_count if w.score_count else None
        return Wait(track_id, w.cls, w.started_at, w.last_inside, mean)


class QueueProfile(ZoneProfile):
    def __init__(
        self,
        zone: Zone,
        camera_id: str,
        env: ProfileEnv,
        detector_classes: frozenset[str] | None,
        classes: list[str] | None = None,
    ) -> None:
        super().__init__(zone, camera_id, env, detector_classes)
        configured = zone.names("classes", ZONE_DEFAULTS["queue"]["classes"])
        self.classes = classes if classes is not None else configured
        self.anchor_mode = zone.text("anchor", "bottom")
        self.min_wait = max(0.0, zone.num("minWaitSeconds", 5))
        self.waits = QueueWaits(zone, self.anchor_mode, zone.num("edgeMargin", 0.01))
        period = max(10.0, zone.num("occupancyEverySeconds", 60))
        self.windows = {cls: OccupancyWindow(period) for cls in self.classes}

    def wanted_classes(self) -> frozenset[str]:
        return frozenset(self.classes)

    def _reset(self, ts: float) -> None:
        self.waits.reset()
        for window in self.windows.values():
            window.start(ts)

    def process(self, frame: FrameContext) -> list[Observation]:
        tracks = [t for t in frame.tracks if t.cls in self.classes and t.mean_score >= self.min_confidence]
        observations = []
        for cls, window in self.windows.items():
            present = [t for t in tracks if t.cls == cls and inside(self.zone, t, self.anchor_mode)]
            score = sum(t.mean_score for t in present) / len(present) if present else None
            for period in window.add(frame.ts, len(present), score):
                observations.append(self._occupancy(cls, period))
        observations += [self._dwell(w) for w in self.waits.update(tracks, frame.ts) if w.seconds >= self.min_wait]
        return observations

    def _flush(self, ts: float) -> list[Observation]:
        observations = []
        for cls, window in self.windows.items():
            period = window.flush(ts)
            if period is not None:
                observations.append(self._occupancy(cls, period))
        observations += [self._dwell(w) for w in self.waits.flush() if w.seconds >= self.min_wait]
        return observations

    def _hold(self) -> float | None:
        holds = [w.start_ts for w in self.windows.values() if w.start_ts is not None]
        pending = self.waits.pending_since()
        if pending is not None:
            holds.append(pending)
        return min(holds) if holds else None

    def _occupancy(self, cls: str, period: Period) -> Observation:
        confidence = period.mean_score if (period.max_count > 0 and period.mean_score is not None) else EMPTY_CONFIDENCE
        payload = {"count": period.max_count, "class": cls, "periodSeconds": round(period.seconds, 3)}
        return self.observation(OCCUPANCY, period.ended_at, payload, confidence)

    def _dwell(self, wait: Wait) -> Observation:
        payload: dict[str, Any] = {
            "class": wait.cls,
            "startedAt": iso(wait.started_at),
            "endedAt": iso(wait.ended_at),
            "durationSeconds": round(wait.seconds, 3),
            "maxCount": 1,
            "method": "track",
        }
        return self.observation(DWELL, wait.ended_at, payload, wait.mean_score)

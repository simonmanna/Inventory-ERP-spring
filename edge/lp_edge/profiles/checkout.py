"""Checkout zones (feeds V-C2 and V-C3): is a customer at the till?

Presence is counted from people the detector finds with their anchor point (box centre by default; ``anchor``
``bottom`` uses the feet) inside the polygon, or, with ``presenceMethod`` ``motion`` or when no person detector is
installed, from movement inside the polygon.

The zone reports:

- ``vision.occupancy`` every ``occupancyEverySeconds``: the most people seen during the period (0 included), at the
  period's end. A presence still in progress is visible to the backend through these.
- ``vision.dwell`` when a presence ends (no one for ``minAbsenceSeconds``), at its end: ``startedAt``, ``endedAt``,
  ``durationSeconds``, ``maxCount``. A presence shorter than ``minPresenceSeconds`` is not one; a presence longer than
  ``maxDwellSeconds`` (30 minutes) is reported in parts. A dwell of ``clipMinDwellSeconds`` or more keeps a clip.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from lp_edge.events import DWELL, OCCUPANCY, Observation
from lp_edge.profiles.base import FrameContext, ProfileEnv, ZoneProfile, inside
from lp_edge.profiles.occupancy import OccupancyWindow, Period
from lp_edge.remote_config import Zone
from lp_edge.timeutil import iso

MOTION_CONFIDENCE = 0.6
EMPTY_CONFIDENCE = 0.8


@dataclass(frozen=True)
class Presence:
    started_at: float
    ended_at: float
    max_count: int
    mean_score: float | None
    split: bool = False
    interrupted: bool = False


class PresenceTracker:
    """Absent ↔ present with debouncing. Pure logic."""

    def __init__(self, min_presence: float, min_absence: float, max_dwell: float) -> None:
        self.min_presence = max(0.0, min_presence)
        self.min_absence = max(0.0, min_absence)
        self.max_dwell = max(60.0, max_dwell)
        self.reset()

    def reset(self) -> None:
        self.present = False
        self._candidate: float | None = None
        self._started = 0.0
        self._last_seen = 0.0
        self._absent_since: float | None = None
        self._max = 0
        self._score_total = 0.0
        self._score_count = 0

    def pending_end(self) -> float | None:
        """While an absence waits for confirmation: the end the presence will be reported with."""
        return self._last_seen if (self.present and self._absent_since is not None) else None

    def update(self, ts: float, count: int, score: float | None) -> list[Presence]:
        ended: list[Presence] = []
        if not self.present:
            if count <= 0:
                # a detector misses a person now and then: a candidate survives gaps shorter than minAbsenceSeconds
                if self._candidate is not None and ts - self._last_seen >= self.min_absence:
                    self._candidate = None
                return ended
            if self._candidate is None:
                self._candidate = ts
                self._max = 0
                self._score_total = 0.0
                self._score_count = 0
            self._see(ts, count, score)
            if ts - self._candidate >= self.min_presence:
                self.present = True
                self._started = self._candidate
                self._candidate = None
            return ended
        if count > 0:
            self._absent_since = None
            self._see(ts, count, score)
            if ts - self._started >= self.max_dwell:
                ended.append(self._presence(ts, split=True))
                self._started = ts
                self._max = count
                self._score_total = score or 0.0
                self._score_count = 1 if score is not None else 0
            return ended
        if self._absent_since is None:
            self._absent_since = ts
        if ts - self._absent_since >= self.min_absence:
            ended.append(self._presence(self._last_seen))
            self.reset()
        return ended

    def flush(self) -> list[Presence]:
        """Observation stops: a presence in progress ends at the last moment someone was seen."""
        ended = [self._presence(self._last_seen, interrupted=True)] if self.present else []
        self.reset()
        return ended

    def _see(self, ts: float, count: int, score: float | None) -> None:
        self._last_seen = ts
        self._max = max(self._max, count)
        if score is not None:
            self._score_total += score
            self._score_count += 1

    def _presence(self, end: float, split: bool = False, interrupted: bool = False) -> Presence:
        mean = self._score_total / self._score_count if self._score_count else None
        return Presence(self._started, max(end, self._started), max(1, self._max), mean, split, interrupted)


class CheckoutProfile(ZoneProfile):
    def __init__(self, zone: Zone, camera_id: str, env: ProfileEnv, detector_classes: frozenset[str] | None) -> None:
        super().__init__(zone, camera_id, env, detector_classes)
        requested = zone.text("presenceMethod", "detector")
        has_person = detector_classes is not None and "person" in detector_classes
        self.method = "detector" if requested == "detector" and has_person else "motion"
        self.anchor_mode = zone.text("anchor", "center")
        self.motion_threshold = zone.num("motionThreshold", 0.02)
        self.presence = PresenceTracker(
            zone.num("minPresenceSeconds", 3), zone.num("minAbsenceSeconds", 3), zone.num("maxDwellSeconds", 1800)
        )
        self.occupancy = OccupancyWindow(max(10.0, zone.num("occupancyEverySeconds", 60)))
        self.clip_min_dwell = zone.num("clipMinDwellSeconds", 90)

    def wanted_classes(self) -> frozenset[str]:
        return frozenset({"person"}) if self.method == "detector" else frozenset()

    def needs_motion(self) -> bool:
        return self.method == "motion"

    def _reset(self, ts: float) -> None:
        self.presence.reset()
        self.occupancy.start(ts)

    def process(self, frame: FrameContext) -> list[Observation]:
        count, score = self._count(frame)
        observations = [self._occupancy(p) for p in self.occupancy.add(frame.ts, count, score)]
        observations += [self._dwell(p) for p in self.presence.update(frame.ts, count, score)]
        return observations

    def _flush(self, ts: float) -> list[Observation]:
        observations = []
        period = self.occupancy.flush(ts)
        if period is not None:
            observations.append(self._occupancy(period))
        observations += [self._dwell(p) for p in self.presence.flush()]
        return observations

    def _hold(self) -> float | None:
        holds = [h for h in (self.occupancy.start_ts, self.presence.pending_end()) if h is not None]
        return min(holds) if holds else None

    def _count(self, frame: FrameContext) -> tuple[int, float | None]:
        if self.method == "detector":
            people = [
                t
                for t in frame.tracks
                if t.cls == "person" and t.mean_score >= self.min_confidence and inside(self.zone, t, self.anchor_mode)
            ]
            if not people:
                return 0, None
            return len(people), sum(t.mean_score for t in people) / len(people)
        ratio = frame.motion.ratio(self.zone.polygon) if frame.motion is not None else 0.0
        return (1 if ratio >= self.motion_threshold else 0), None

    def _occupancy(self, period: Period) -> Observation:
        if self.method == "motion":
            confidence = MOTION_CONFIDENCE
        elif period.max_count > 0 and period.mean_score is not None:
            confidence = period.mean_score
        else:
            confidence = EMPTY_CONFIDENCE
        payload = {
            "count": period.max_count,
            "class": "person",
            "periodSeconds": round(period.seconds, 3),
            "method": self.method,
        }
        return self.observation(OCCUPANCY, period.ended_at, payload, confidence)

    def _dwell(self, presence: Presence) -> Observation:
        duration = presence.ended_at - presence.started_at
        payload: dict[str, Any] = {
            "class": "person",
            "startedAt": iso(presence.started_at),
            "endedAt": iso(presence.ended_at),
            "durationSeconds": round(duration, 3),
            "maxCount": presence.max_count,
            "method": self.method,
        }
        if presence.split:
            payload["split"] = True
        if presence.interrupted:
            payload["interrupted"] = True
        clip = None
        if duration >= self.clip_min_dwell:
            pre = max(0.0, self.zone.num("preRollSeconds", 10))
            post = max(0.0, self.zone.num("postRollSeconds", 10))
            clip = self.clip_span(presence.started_at - pre, presence.ended_at + post, keep="end")
        if self.method == "motion" or presence.mean_score is None:
            confidence = MOTION_CONFIDENCE
        else:
            confidence = presence.mean_score
        return self.observation(DWELL, presence.ended_at, payload, confidence, clip)

"""Activity research on a zone (LP7, shadow mode): the zone's profile runs unchanged, and beside it the zone's
activity recognizers, whose segments are sent as ``vision.activity``.

Only a zone whose ``params.activityResearch`` is true is wrapped (:func:`lp_edge.profiles.build_profile`). The
recognizers see the frame's tracks of the classes the zone's profile reads, above the zone's ``minConfidence``; a
profile that reads no class (a drawer) gives them nothing to see. A recognizer that fails is logged and skipped for
that frame: it never costs the zone's own observations.

``vision.activity`` payload: ``activity``, ``startedAt``, ``endedAt``, ``recognizer`` (``name@version``), ``score``
(0 to 1, when the recognizer gives one) and ``trackIds``. ``occurredAt`` is the segment's end.
"""

from __future__ import annotations

import logging
import math
from collections.abc import Sequence
from typing import Any

from lp_edge.activities.base import Recognizer, Segment, valid_name
from lp_edge.activities.loader import recognizers_for
from lp_edge.events import ACTIVITY, MAX_ID_LENGTH, MAX_TRACK_IDS, Observation
from lp_edge.profiles.base import FrameContext, ZoneProfile
from lp_edge.timeutil import iso

log = logging.getLogger(__name__)


class ActivityResearchProfile(ZoneProfile):
    def __init__(self, inner: ZoneProfile, recognizers: Sequence[Recognizer]) -> None:
        super().__init__(inner.zone, inner.camera_id, inner.env, inner.detector_classes)
        self.inner = inner
        self.recognizers = list(recognizers)
        self._failing: set[str] = set()

    def wanted_classes(self) -> frozenset[str]:
        return self.inner.wanted_classes()

    def needs_frame(self) -> bool:
        return self.inner.needs_frame()

    def needs_motion(self) -> bool:
        return self.inner.needs_motion()

    def start(self, ts: float) -> None:
        self.inner.start(ts)
        super().start(ts)

    def stop(self, ts: float | None) -> list[Observation]:
        observations = self.inner.stop(ts)
        return observations + super().stop(ts)

    def process(self, frame: FrameContext) -> list[Observation]:
        observations = self.inner.process(frame)
        classes = self.inner.wanted_classes()
        tracks = [t for t in frame.tracks if t.cls in classes and t.mean_score >= self.min_confidence]
        for recognizer in self.recognizers:
            try:
                segments = recognizer.observe(frame, tracks, self.zone)
            except Exception:  # noqa: BLE001  (research must never cost the zone's own observations)
                self._failed(recognizer, "failed on a frame")
                continue
            observations += self._activities(recognizer, segments)
        return observations

    def _failed(self, recognizer: Recognizer, what: str) -> None:
        """Logs a recognizer's failure with its traceback once, then quietly: it is called on every frame."""
        if recognizer.name in self._failing:
            log.debug("zone %s: activity recognizer %s %s", self.zone.id, recognizer.name, what, exc_info=True)
            return
        self._failing.add(recognizer.name)
        log.exception("zone %s: activity recognizer %s %s (further failures are logged at debug level)",
                      self.zone.id, recognizer.name, what)

    def _reset(self, ts: float) -> None:
        for recognizer in self.recognizers:
            reset = getattr(recognizer, "reset", None)
            if callable(reset):
                reset()

    def _flush(self, ts: float) -> list[Observation]:
        observations: list[Observation] = []
        for recognizer in self.recognizers:
            try:
                segments = recognizer.flush(ts)
            except Exception:  # noqa: BLE001
                self._failed(recognizer, "failed to flush")
                continue
            observations += self._activities(recognizer, segments)
        return observations

    def _hold(self) -> float | None:
        holds = []
        inner = self.inner.hold()
        if inner is not None:
            holds.append(inner)
        for recognizer in self.recognizers:
            pending = getattr(recognizer, "pending_since", None)
            value = pending() if callable(pending) else None
            if isinstance(value, (int, float)) and math.isfinite(value):
                holds.append(float(value))
        return min(holds) if holds else None

    def _activities(self, recognizer: Recognizer, segments: Sequence[Segment]) -> list[Observation]:
        out = []
        label = f"{recognizer.name}@{recognizer.version}"[:MAX_ID_LENGTH]
        for segment in segments:
            if not valid_name(segment.activity):
                log.warning("zone %s: recognizer %s reported an invalid activity name %r", self.zone.id, label,
                            segment.activity)
                continue
            ended = max(segment.ended_at, segment.started_at)
            payload: dict[str, Any] = {"activity": segment.activity, "startedAt": iso(segment.started_at),
                                       "endedAt": iso(ended), "recognizer": label}
            score = segment.score
            valid_score = (score is not None and not isinstance(score, bool) and isinstance(score, (int, float))
                           and math.isfinite(score) and 0 <= score <= 1)
            if valid_score:
                payload["score"] = round(float(score), 3)
            if segment.track_ids:
                payload["trackIds"] = list(segment.track_ids)[:MAX_TRACK_IDS]
            out.append(Observation(ACTIVITY, self.zone.id, self.camera_id, ended, payload,
                                   round(float(score), 3) if valid_score else None))
        return out


def with_research(profile: ZoneProfile) -> ZoneProfile:
    """``profile`` with the zone's activity recognizers beside it; ``profile`` itself when none can run."""
    recognizers = recognizers_for(profile.zone)
    if not recognizers:
        log.warning("zone %s: activity research is on but no activity recognizer can run", profile.zone.id)
        return profile
    return ActivityResearchProfile(profile, recognizers)

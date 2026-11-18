"""Periodic counts (``vision.occupancy``): the most objects seen in a zone during each period.

Periods end on wall-clock multiples of ``every`` seconds (a 60 s period ends on the minute). The first period after
observation starts is shorter; ``periodSeconds`` is always the period actually observed. A period is reported when it
ends, at its end (``occurredAt`` = period end), with count 0 when nothing was seen, so the backend can tell "nobody
there" from "not reported yet": until a period is reported the camera's watermark stays at its start.
"""

from __future__ import annotations

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class Period:
    started_at: float
    ended_at: float
    max_count: int
    mean_score: float | None

    @property
    def seconds(self) -> float:
        return self.ended_at - self.started_at


class OccupancyWindow:
    def __init__(self, every: float) -> None:
        if every <= 0:
            raise ValueError("the period must be positive")
        self.every = float(every)
        self._start: float | None = None
        self._end = 0.0
        self._max = 0
        self._score_total = 0.0
        self._score_count = 0

    @property
    def start_ts(self) -> float | None:
        """Start of the period in progress: the end of the last one reported."""
        return self._start

    def start(self, ts: float) -> None:
        self._start = ts
        self._end = (math.floor(ts / self.every) + 1) * self.every
        self._clear()

    def stop(self) -> None:
        self._start = None

    def add(self, ts: float, count: int, score: float | None = None) -> list[Period]:
        """Count one frame; returns the periods that ended before it."""
        if self._start is None:
            self.start(ts)
        closed = []
        while ts >= self._end:
            closed.append(self._close(self._end))
            self._start = self._end
            self._end += self.every
        if count > self._max:
            self._max = count
            self._score_total = 0.0
            self._score_count = 0
        if count == self._max and count > 0 and score is not None:
            self._score_total += score
            self._score_count += 1
        return closed

    def flush(self, ts: float) -> Period | None:
        """Observation stops after ``ts``: the period in progress, cut at ``ts``."""
        if self._start is None or ts <= self._start:
            self._start = None
            return None
        period = self._close(min(ts, self._end))
        self._start = None
        return period

    def _close(self, end: float) -> Period:
        assert self._start is not None
        mean = self._score_total / self._score_count if self._score_count else None
        period = Period(self._start, end, self._max, mean)
        self._clear()
        return period

    def _clear(self) -> None:
        self._max = 0
        self._score_total = 0.0
        self._score_count = 0

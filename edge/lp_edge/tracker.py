"""A ByteTrack-style multi-object tracker: gives each detected object an identity across frames so it counts once.

As in ByteTrack (Zhang et al., 2022) detections are associated in two passes: confident detections first, against
every track; then the low-score detections (occlusion, blur) against the tracks still unmatched, so a briefly
uncertain object keeps its identity. Association is greedy on IoU with a constant-velocity prediction; with the few
objects a zone holds this is as good as the Hungarian method and needs no extra dependency.
"""

from __future__ import annotations

import itertools
from collections.abc import Iterable, Sequence
from dataclasses import dataclass

from lp_edge.detectors.base import Detection
from lp_edge.geometry import Box, iou


@dataclass
class Track:
    id: int
    cls: str
    box: Box
    score: float
    first_seen: float
    last_seen: float
    hits: int = 1
    confirmed: bool = False
    matched: bool = True
    velocity: tuple[float, float, float, float] = (0.0, 0.0, 0.0, 0.0)
    score_total: float = 0.0

    @property
    def mean_score(self) -> float:
        return self.score_total / self.hits if self.hits else self.score

    def predict(self, ts: float, horizon: float) -> Box:
        dt = max(0.0, min(ts - self.last_seen, horizon))
        vx1, vy1, vx2, vy2 = self.velocity
        x1, y1, x2, y2 = self.box
        return (x1 + vx1 * dt, y1 + vy1 * dt, x2 + vx2 * dt, y2 + vy2 * dt)


class ByteTracker:
    def __init__(
        self,
        *,
        high_threshold: float = 0.5,
        low_threshold: float = 0.1,
        new_track_threshold: float = 0.6,
        match_iou: float = 0.3,
        low_match_iou: float = 0.5,
        max_lost_seconds: float = 1.5,
        min_hits: int = 2,
    ) -> None:
        self.high_threshold = high_threshold
        self.low_threshold = low_threshold
        self.new_track_threshold = new_track_threshold
        self.match_iou = match_iou
        self.low_match_iou = low_match_iou
        self.max_lost_seconds = max_lost_seconds
        self.min_hits = min_hits
        self._tracks: list[Track] = []
        self._ids = itertools.count(1)

    @property
    def tracks(self) -> list[Track]:
        return list(self._tracks)

    def reset(self) -> None:
        self._tracks = []

    def update(self, detections: Iterable[Detection], ts: float) -> list[Track]:
        """Associate this frame's detections; returns the confirmed tracks seen in it."""
        detections = list(detections)
        for track in self._tracks:
            track.matched = False
        predicted = {track.id: track.predict(ts, self.max_lost_seconds) for track in self._tracks}
        high = [d for d in detections if d.score >= self.high_threshold]
        low = [d for d in detections if self.low_threshold <= d.score < self.high_threshold]

        pairs, unmatched_tracks, unmatched_high = _associate(self._tracks, high, predicted, self.match_iou)
        for track, detection in pairs:
            self._apply(track, detection, ts)
        second = [t for t in unmatched_tracks if t.confirmed]
        pairs, _, _ = _associate(second, low, predicted, self.low_match_iou)
        for track, detection in pairs:
            self._apply(track, detection, ts)

        survivors = []
        for track in self._tracks:
            if track.matched:
                survivors.append(track)
            elif track.confirmed and ts - track.last_seen <= self.max_lost_seconds:
                survivors.append(track)
            # an unconfirmed track that misses a frame is dropped, as in ByteTrack
        for detection in unmatched_high:
            if detection.score >= self.new_track_threshold:
                survivors.append(
                    Track(
                        id=next(self._ids),
                        cls=detection.cls,
                        box=detection.box,
                        score=detection.score,
                        first_seen=ts,
                        last_seen=ts,
                        score_total=detection.score,
                        confirmed=self.min_hits <= 1,
                    )
                )
        self._tracks = survivors
        return [t for t in self._tracks if t.matched and t.confirmed]

    def _apply(self, track: Track, detection: Detection, ts: float) -> None:
        dt = ts - track.last_seen
        if dt > 0:
            measured = tuple((n - o) / dt for n, o in zip(detection.box, track.box))
            track.velocity = tuple(0.5 * v + 0.5 * m for v, m in zip(track.velocity, measured))  # type: ignore[assignment]
        track.box = detection.box
        track.score = detection.score
        track.score_total += detection.score
        track.hits += 1
        track.last_seen = ts
        track.matched = True
        if track.hits >= self.min_hits:
            track.confirmed = True


def _associate(
    tracks: Sequence[Track], detections: Sequence[Detection], predicted: dict[int, Box], min_iou: float
) -> tuple[list[tuple[Track, Detection]], list[Track], list[Detection]]:
    candidates = []
    for ti, track in enumerate(tracks):
        box = predicted.get(track.id, track.box)
        for di, detection in enumerate(detections):
            if detection.cls != track.cls:
                continue
            overlap = iou(box, detection.box)
            if overlap >= min_iou:
                candidates.append((overlap, ti, di))
    candidates.sort(key=lambda c: c[0], reverse=True)
    used_tracks: set[int] = set()
    used_detections: set[int] = set()
    pairs = []
    for _, ti, di in candidates:
        if ti in used_tracks or di in used_detections:
            continue
        used_tracks.add(ti)
        used_detections.add(di)
        pairs.append((tracks[ti], detections[di]))
    rest_tracks = [t for i, t in enumerate(tracks) if i not in used_tracks]
    rest_detections = [d for i, d in enumerate(detections) if i not in used_detections]
    return pairs, rest_tracks, rest_detections

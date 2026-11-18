"""The measures of a detector evaluation, as plain functions over plain data (no NumPy).

Definitions follow the loss-prevention design document, "Accuracy targets":

- precision: correct detections ÷ all detections;
- recall: correct detections ÷ all actual events (or objects);
- count error: |detected count − true count| ÷ true count, per shift (or per transaction batch);
- false alert rate: incorrect events per shift per zone.

Box-level detection is scored the usual way (a prediction is correct when its class matches and its IoU with an
unmatched labelled box is at least the threshold, highest score first), with average precision as the area under the
interpolated precision-recall curve. Event-level scoring matches a predicted event to a labelled one of the same zone,
type and attributes within a time tolerance, closest first.
"""

from __future__ import annotations

import math
from collections import defaultdict
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any

Box = tuple[float, float, float, float]


def iou(a: Box, b: Box) -> float:
    w = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    h = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = w * h
    union = max(0.0, a[2] - a[0]) * max(0.0, a[3] - a[1]) + max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def ratio(numerator: float, denominator: float) -> float | None:
    """``numerator ÷ denominator``, or ``None`` when there is nothing to divide by."""
    return numerator / denominator if denominator else None


def percentile(values: Sequence[float], q: float) -> float | None:
    """Linear-interpolated percentile (``q`` in 0–100) of ``values``; ``None`` for no values."""
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) - 1) * q / 100.0
    low = math.floor(rank)
    high = math.ceil(rank)
    if low == high:
        return ordered[int(rank)]
    return ordered[low] + (ordered[high] - ordered[low]) * (rank - low)


# --------------------------------------------------------------------------------------------- boxes


@dataclass(frozen=True)
class PredBox:
    cls: str
    score: float
    box: Box
    track: int | None = None


@dataclass(frozen=True)
class TruthBox:
    cls: str
    box: Box
    track: str | None = None


@dataclass
class ClassScore:
    """Detections of one class over every labelled frame."""

    truths: int = 0
    scored: list[tuple[float, bool]] = field(default_factory=list)  # (score, correct)

    @property
    def true_positives(self) -> int:
        return sum(1 for _, ok in self.scored if ok)

    @property
    def false_positives(self) -> int:
        return sum(1 for _, ok in self.scored if not ok)

    @property
    def false_negatives(self) -> int:
        return self.truths - self.true_positives

    def summary(self) -> dict[str, Any]:
        return {
            "labelled": self.truths,
            "detected": len(self.scored),
            "truePositives": self.true_positives,
            "falsePositives": self.false_positives,
            "falseNegatives": self.false_negatives,
            "precision": ratio(self.true_positives, len(self.scored)),
            "recall": ratio(self.true_positives, self.truths),
            "averagePrecision": average_precision(self.scored, self.truths),
        }


def match_frame(preds: Sequence[PredBox], truths: Sequence[TruthBox], iou_threshold: float) -> list[tuple[int, int]]:
    """Pairs ``(pred index, truth index)``: same class, IoU ≥ threshold, highest score first, each used once."""
    used: set[int] = set()
    pairs = []
    for p_index in sorted(range(len(preds)), key=lambda i: -preds[i].score):
        pred = preds[p_index]
        best, best_iou = None, iou_threshold
        for t_index, truth in enumerate(truths):
            if t_index in used or truth.cls != pred.cls:
                continue
            overlap = iou(pred.box, truth.box)
            if overlap >= best_iou:
                best, best_iou = t_index, overlap
        if best is not None:
            used.add(best)
            pairs.append((p_index, best))
    return pairs


def score_frames(
    frames: Iterable[tuple[Sequence[PredBox], Sequence[TruthBox]]],
    iou_threshold: float = 0.5,
    classes: Iterable[str] | None = None,
) -> dict[str, ClassScore]:
    """Per class: every prediction marked correct or not, and the number of labelled boxes.

    ``classes`` limits scoring to the classes that were labelled (a model's other classes are not errors).
    """
    wanted = set(classes) if classes is not None else None
    scores: dict[str, ClassScore] = defaultdict(ClassScore)
    for preds, truths in frames:
        preds = [p for p in preds if wanted is None or p.cls in wanted]
        truths = [t for t in truths if wanted is None or t.cls in wanted]
        matched = {p for p, _ in match_frame(preds, truths, iou_threshold)}
        for truth in truths:
            scores[truth.cls].truths += 1
        for index, pred in enumerate(preds):
            scores[pred.cls].scored.append((pred.score, index in matched))
    return dict(scores)


def average_precision(scored: Sequence[tuple[float, bool]], truths: int) -> float | None:
    """Area under the precision-recall curve, precision made monotone (all-point interpolation)."""
    if truths == 0:
        return None
    ordered = sorted(scored, key=lambda s: -s[0])
    tp = fp = 0
    recalls, precisions = [0.0], [1.0]
    for _, correct in ordered:
        if correct:
            tp += 1
        else:
            fp += 1
        recalls.append(tp / truths)
        precisions.append(tp / (tp + fp))
    for i in range(len(precisions) - 2, -1, -1):
        precisions[i] = max(precisions[i], precisions[i + 1])
    area = 0.0
    for i in range(1, len(recalls)):
        area += (recalls[i] - recalls[i - 1]) * precisions[i]
    return area


def tracking(frames: Iterable[tuple[Sequence[PredBox], Sequence[TruthBox]]], iou_threshold: float = 0.5) -> dict:
    """Identity switches and MOTA over labelled frames in time order.

    Only labelled boxes with a ``track`` take part. An identity switch is a labelled object matched to a different
    track id than the last time it was matched. MOTA = 1 − (misses + false tracks + switches) ÷ labelled boxes.
    """
    last: dict[str, int] = {}
    switches = misses = false_tracks = labelled = 0
    for preds, truths in frames:
        truths = [t for t in truths if t.track is not None]
        preds = [p for p in preds if p.track is not None]
        if not truths:
            continue
        labelled += len(truths)
        pairs = match_frame(preds, truths, iou_threshold)
        misses += len(truths) - len(pairs)
        false_tracks += len(preds) - len(pairs)
        for p_index, t_index in pairs:
            key = str(truths[t_index].track)
            track_id = preds[p_index].track
            assert track_id is not None
            if key in last and last[key] != track_id:
                switches += 1
            last[key] = track_id
    mota = None if labelled == 0 else 1.0 - (misses + false_tracks + switches) / labelled
    return {"labelled": labelled, "identitySwitches": switches, "misses": misses, "falseTracks": false_tracks,
            "mota": mota}


# --------------------------------------------------------------------------------------------- events


@dataclass(frozen=True)
class Event:
    """A labelled or a predicted event: ``type`` in a ``zone`` at ``t`` (seconds from the clip start)."""

    zone: str
    type: str
    t: float
    end: float | None = None
    attrs: Mapping[str, str] = field(default_factory=dict)


def _agrees(pred: Event, truth: Event) -> bool:
    """Same zone and type; every attribute the label states is the same in the prediction."""
    if pred.zone != truth.zone or pred.type != truth.type:
        return False
    return all(pred.attrs.get(k) == v for k, v in truth.attrs.items())


def _distance(pred: Event, truth: Event) -> float:
    """Seconds between two events. Two spans (``end`` set on both) match when their temporal IoU is at least 0.3,
    whatever the tolerance; a larger overlap ranks first."""
    if pred.end is not None and truth.end is not None:
        inter = max(0.0, min(pred.end, truth.end) - max(pred.t, truth.t))
        union = max(pred.end, truth.end) - min(pred.t, truth.t)
        overlap = inter / union if union > 0 else 0.0
        return math.inf if overlap < 0.3 else (1.0 - overlap) * 1e-6
    return abs(pred.t - truth.t)


@dataclass
class EventMatch:
    true_positives: int
    false_positives: list[Event]
    missed: list[Event]
    #: (predicted, labelled) of each match
    pairs: list[tuple[Event, Event]] = field(default_factory=list)

    @property
    def predicted(self) -> int:
        return self.true_positives + len(self.false_positives)

    @property
    def labelled(self) -> int:
        return self.true_positives + len(self.missed)


def match_events(preds: Sequence[Event], truths: Sequence[Event], tolerance: float) -> EventMatch:
    """Closest-first one-to-one matching within ``tolerance`` seconds (spans: temporal IoU ≥ 0.3)."""
    candidates = []
    for p_index, pred in enumerate(preds):
        for t_index, truth in enumerate(truths):
            if _agrees(pred, truth):
                distance = _distance(pred, truth)
                if distance <= tolerance:
                    candidates.append((distance, p_index, t_index))
    candidates.sort()
    used_p: set[int] = set()
    used_t: set[int] = set()
    pairs = []
    for _, p_index, t_index in candidates:
        if p_index in used_p or t_index in used_t:
            continue
        used_p.add(p_index)
        used_t.add(t_index)
        pairs.append((preds[p_index], truths[t_index]))
    return EventMatch(
        true_positives=len(used_p),
        false_positives=[p for i, p in enumerate(preds) if i not in used_p],
        missed=[t for i, t in enumerate(truths) if i not in used_t],
        pairs=pairs,
    )


def count_error(predicted: int, true: int) -> float | None:
    """|predicted − true| ÷ true; 0 when both are 0; ``None`` when only the true count is 0 (undefined)."""
    if true == 0:
        return 0.0 if predicted == 0 else None
    return abs(predicted - true) / true

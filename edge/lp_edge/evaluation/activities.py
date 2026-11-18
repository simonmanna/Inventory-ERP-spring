"""Activity recognition research (LP7): the recognizers' segments against labelled ones. Research only.

A clip's labels may list ``activities``: ``{zone, activity, start, end}`` in the clip's time. The ``vision.activity``
observations the agent made on the clip (zones with ``activityResearch`` true) are matched one to one with them, per
zone and activity, when their temporal IoU (overlap ÷ union of the two spans) is at least 0.5, largest overlap first.
Per activity, and over all of them: precision, recall and F1. Only clips whose labels list ``activities`` (an empty
list included) are scored; on them every unmatched segment is a false one.

The section never enters a verdict: no backend rule reads an activity, and no candidate is eligible or not because of
it (``report.overall`` does not read it).
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any

from lp_edge.evaluation.dataset import ActivityLabel, Clip
from lp_edge.evaluation.metrics import ratio
from lp_edge.events import ACTIVITY, Observation
from lp_edge.timeutil import parse_iso

MIN_IOU = 0.5
NOTE = "research only: never part of a verdict or of a candidate's eligibility"


@dataclass(frozen=True)
class PredictedSegment:
    zone: str
    activity: str
    start: float
    end: float
    recognizer: str = ""
    score: float | None = None


def temporal_iou(a_start: float, a_end: float, b_start: float, b_end: float) -> float:
    """Overlap ÷ union of two spans; 0 when they do not overlap."""
    inter = max(0.0, min(a_end, b_end) - max(a_start, b_start))
    union = max(a_end, b_end) - min(a_start, b_start)
    return inter / union if union > 0 else 0.0


def predicted_segments(observations: Sequence[Observation]) -> list[PredictedSegment]:
    """The ``vision.activity`` observations of a clip as segments in the clip's time."""
    out = []
    for o in observations:
        if o.type != ACTIVITY:
            continue
        p = o.payload
        out.append(PredictedSegment(o.zone_id, str(p["activity"]), parse_iso(p["startedAt"]), parse_iso(p["endedAt"]),
                                    str(p.get("recognizer") or ""), p.get("score")))
    return out


def match_segments(preds: Sequence[PredictedSegment], truths: Sequence[ActivityLabel],
                   min_iou: float = MIN_IOU) -> list[tuple[int, int]]:
    """One-to-one ``(prediction index, label index)`` pairs: same zone and activity, IoU ≥ ``min_iou``, best first."""
    candidates = []
    for i, p in enumerate(preds):
        for j, t in enumerate(truths):
            if p.zone == t.zone and p.activity == t.activity:
                overlap = temporal_iou(p.start, p.end, t.start, t.end)
                if overlap >= min_iou:
                    candidates.append((-overlap, i, j))
    candidates.sort()
    used_p: set[int] = set()
    used_t: set[int] = set()
    pairs = []
    for _, i, j in candidates:
        if i in used_p or j in used_t:
            continue
        used_p.add(i)
        used_t.add(j)
        pairs.append((i, j))
    return pairs


def _scores(labelled: int, predicted: int, matched: int) -> dict[str, Any]:
    precision = ratio(matched, predicted)
    recall = ratio(matched, labelled)
    f1 = None
    if precision is not None and recall is not None:
        f1 = 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)
    return {"labelled": labelled, "predicted": predicted, "truePositives": matched, "precision": precision,
            "recall": recall, "f1": f1}


def score(clips: Sequence[tuple[Clip, Sequence[Observation]]], min_iou: float = MIN_IOU) -> dict[str, Any]:
    """Precision, recall and F1 of the activity segments, per activity and overall, on the clips labelled for it."""
    counts: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0])  # labelled, predicted, matched
    recognizers: set[str] = set()
    scored_clips = unscored = 0
    for clip, observations in clips:
        preds = predicted_segments(observations)
        recognizers.update(p.recognizer for p in preds if p.recognizer)
        if clip.activities is None:
            unscored += len(preds)
            continue
        scored_clips += 1
        truths = list(clip.activities)
        for t in truths:
            counts[t.activity][0] += 1
        for p in preds:
            counts[p.activity][1] += 1
        for i, _ in match_segments(preds, truths, min_iou):
            counts[preds[i].activity][2] += 1
    result: dict[str, Any] = {"research": True, "note": NOTE, "minIou": min_iou, "clips": scored_clips,
                              "recognizers": sorted(recognizers), "segmentsOnUnlabelledClips": unscored}
    if not scored_clips:
        return {**result, "measured": False, "reason": "no clip has its activities labelled", "perActivity": {},
                "overall": _scores(0, 0, 0)}
    totals = [sum(c[k] for c in counts.values()) for k in range(3)]
    return {**result, "measured": True,
            "perActivity": {a: _scores(*c) for a, c in sorted(counts.items())},
            "overall": _scores(*totals)}

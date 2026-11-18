"""Business accuracy: does the pipeline lead to the right findings, not only the right boxes and events?

Detection accuracy is scored at four levels, reported side by side so that a strong lower level cannot hide a weak
higher one:

1. **objects**: are people, drawers, plates boxed (box precision and recall per class);
2. **events**: did the drawer open, did a plate cross (event precision and recall);
3. **transaction correlation**: with the till's records of the clip (``pos`` labels), does the pipeline reach the same
   finding as the backend's rule would on perfectly labelled events? This is rule V-C1 (cash drawer opened with no
   sale, refund or cash movement), mirrored from ``LpCashierVisionEngine`` with its default windows;
4. **confirmed findings**: against findings a person confirmed as real (``findings`` labels), how many of the
   pipeline's findings are right and how many real ones it finds.

Levels 3 and 4 are scored only on clips that carry the labels they need.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from lp_edge.evaluation.dataset import PosRecord
from lp_edge.evaluation.metrics import Event, match_events, ratio

#: V-C1 as in the backend's rule catalogue (LpRuleCatalog): the records that explain a drawer opening and the window
#: around it, in seconds.
V_C1 = "V-C1"
DRAWER_EXPLAINED_BY = frozenset({"sale.completed", "refund.issued", "cash.movement", "cash_drawer.opened",
                                 "cash_session.opened", "cash_session.closed"})
V_C1_WINDOW_BEFORE = 60.0
V_C1_WINDOW_AFTER = 120.0


def explained(at: float, pos: Sequence[PosRecord], before: float = V_C1_WINDOW_BEFORE,
              after: float = V_C1_WINDOW_AFTER) -> bool:
    """Whether a till record that explains a drawer opening lies within ``[at - before, at + after]``."""
    return any(r.type in DRAWER_EXPLAINED_BY and at - before <= r.t <= at + after for r in pos)


def unexplained_openings(events: Sequence[Event], pos: Sequence[PosRecord], before: float = V_C1_WINDOW_BEFORE,
                         after: float = V_C1_WINDOW_AFTER) -> list[Event]:
    """The V-C1 findings the backend would raise for these drawer openings: one per unexplained opening."""
    return [Event(e.zone, V_C1, e.t) for e in events if e.type == "drawer_open"
            and not explained(e.t, pos, before, after)]


def _level(tp: int, predicted: int, labelled: int, clips: int) -> dict[str, Any]:
    return {"clips": clips, "labelled": labelled, "predicted": predicted, "truePositives": tp,
            "precision": ratio(tp, predicted) if clips else None, "recall": ratio(tp, labelled) if clips else None}


def drawer_findings(runs: Sequence[tuple[Any, list[Event]]], tolerance: float) -> dict[str, Any]:
    """Levels 3 and 4 for cash-drawer clips: ``runs`` is (clip, predicted events) per clip."""
    corr = [0, 0, 0, 0]  # tp, predicted, labelled, clips
    confirmed = [0, 0, 0, 0]
    agree = disagree = 0
    for clip, events in runs:
        if clip.pos is None:
            continue
        predicted = unexplained_openings(events, clip.pos)
        truth = unexplained_openings(clip.events, clip.pos)
        match = match_events(predicted, truth, tolerance)
        corr[0] += match.true_positives
        corr[1] += match.predicted
        corr[2] += match.labelled
        corr[3] += 1
        # Of the labelled openings the pipeline also saw, how often it reached the rule's verdict on them.
        seen = match_events([e for e in events if e.type == "drawer_open"],
                            [e for e in clip.events if e.type == "drawer_open"], tolerance)
        for p, t in seen.pairs:
            if explained(p.t, clip.pos) == explained(t.t, clip.pos):
                agree += 1
            else:
                disagree += 1
        if clip.findings is not None:
            real = match_events(predicted, list(clip.findings), tolerance)
            confirmed[0] += real.true_positives
            confirmed[1] += real.predicted
            confirmed[2] += real.labelled
            confirmed[3] += 1
    return {
        "rule": V_C1,
        "windowBeforeSeconds": V_C1_WINDOW_BEFORE,
        "windowAfterSeconds": V_C1_WINDOW_AFTER,
        "transactionCorrelation": {**_level(*corr), "verdictAgreement": ratio(agree, agree + disagree)},
        "confirmedFindings": _level(*confirmed),
    }

"""Retail checkout count (LP5): the items the agent counted over a checkout's scanner, against the true count of each
transaction batch.

A batch (``batches`` in a clip's labels) is one transaction's items passing a ``scan_area`` zone, from ``start`` to
``end`` in the clip's time, with the true number of items passed over the scanner (``items``) and, when the labeller
counted them, of the items carried past it (``bypassed``). The agent's count of a batch is what the backend's rule V-R1
reads: items crossing to the bagging side (``line_crossed`` ``out``) less those taken back (``in``), of any class but
``person``, between ``start`` and ``end``. The count error of a batch is |counted − true| ÷ true; the target is at most
5 % per batch (``maxCountError``). Items carried past the scanner (rule V-R2, ``handling`` ``bypassed``) are reported
beside it and not checked: the design sets no target for them yet.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from lp_edge.evaluation.dataset import Clip, ScanBatch
from lp_edge.evaluation.metrics import Event, count_error


def _in_batch(e: Event, batch: ScanBatch) -> bool:
    return e.zone == batch.zone and batch.start <= e.t <= batch.end and e.attrs.get("class") != "person"


def counted(events: Sequence[Event], batch: ScanBatch) -> tuple[int, int]:
    """``(items passed over the scanner, items carried past it)`` the agent reported during ``batch``."""
    out = back = bypassed = 0
    for e in events:
        if not _in_batch(e, batch):
            continue
        if e.type == "line_crossed":
            direction = e.attrs.get("direction")
            out += direction == "out"
            back += direction == "in"
        elif e.type == "handling" and e.attrs.get("action") == "bypassed":
            bypassed += 1
    return max(0, out - back), bypassed


def batch_counts(clips: Sequence[tuple[Clip, Sequence[Event]]]) -> dict[str, Any]:
    """Every labelled batch of ``clips`` (each with the agent's events): true and counted items, and the error."""
    rows = []
    for clip, events in clips:
        for batch in clip.batches:
            items, bypassed = counted(events, batch)
            rows.append({"clip": clip.id, "batch": batch.id, "zone": batch.zone, "start": batch.start,
                         "end": batch.end, "true": batch.items, "predicted": items,
                         "error": count_error(items, batch.items), "bypassedTrue": batch.bypassed,
                         "bypassedPredicted": bypassed})
    errors = [r["error"] for r in rows]
    defined = rows and None not in errors
    labelled_bypass = [r for r in rows if r["bypassedTrue"] is not None]
    return {
        "batches": len(rows),
        "perBatch": rows,
        "maxError": max(errors) if defined else None,
        "meanError": sum(errors) / len(errors) if defined else None,
        "undefined": sum(1 for e in errors if e is None),
        "bypassed": {"labelledBatches": len(labelled_bypass),
                     "true": sum(r["bypassedTrue"] for r in labelled_bypass),
                     "predicted": sum(r["bypassedPredicted"] for r in labelled_bypass)},
    }

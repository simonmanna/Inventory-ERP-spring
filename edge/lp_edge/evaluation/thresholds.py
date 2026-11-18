"""Proposed acceptance thresholds per profile, from the loss-prevention design document ("Accuracy targets").

They are proposals "to be confirmed in each phase brief before its pilot starts". A report always says which values it
used; ``--thresholds`` replaces them with a YAML file of the same shape.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

from lp_edge.evaluation.bench import HARDWARE_PROPOSED

PROPOSED: dict[str, dict[str, float]] = {
    # Event precision and recall of drawer openings; then the same of the V-C1 findings those openings lead to with
    # the till's records (transaction correlation). The finding limits are the harness's proposal, set equal to the
    # event limits so that good drawer detection cannot pass with poor correlation; the design document sets none.
    "cash_drawer": {"minPrecision": 0.90, "minRecall": 0.85, "minFindingPrecision": 0.90, "minFindingRecall": 0.85},
    # |detected − true| ÷ true, per shift.
    "bar_counter": {"maxCountError": 0.10},
    "kitchen_pass": {"maxCountError": 0.05},
    "cafe_counter": {"maxCountError": 0.05},
    # Per transaction batch (``batches`` labels: items a scan_area zone counted over the scanner, LP5); ships behind a
    # flag.
    "retail_checkout": {"maxCountError": 0.05},
}
#: Every profile: incorrect events per shift per zone.
MAX_FALSE_ALERTS_PER_SHIFT_PER_ZONE = 2


def load_thresholds(path: str | Path | None) -> dict[str, Any]:
    """The thresholds in force: the proposed ones, or a file's (same keys: ``profiles``, ``hardware``,
    ``maxFalseAlertsPerShiftPerZone``)."""
    values: dict[str, Any] = {"profiles": {k: dict(v) for k, v in PROPOSED.items()},
                              "maxFalseAlertsPerShiftPerZone": MAX_FALSE_ALERTS_PER_SHIFT_PER_ZONE,
                              "hardware": dict(HARDWARE_PROPOSED),
                              "source": "proposed (design document; not yet confirmed in a phase brief)"}
    if path is None:
        return values
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    for profile, limits in (data.get("profiles") or {}).items():
        values["profiles"].setdefault(profile, {}).update({k: float(v) for k, v in limits.items()})
    values["hardware"].update({k: float(v) for k, v in (data.get("hardware") or {}).items()})
    if data.get("maxFalseAlertsPerShiftPerZone") is not None:
        values["maxFalseAlertsPerShiftPerZone"] = float(data["maxFalseAlertsPerShiftPerZone"])
    values["source"] = str(path)
    return values

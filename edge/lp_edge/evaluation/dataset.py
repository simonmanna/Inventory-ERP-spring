"""Labelled footage for a detector evaluation: a ``dataset.yaml`` manifest and one labels file per clip.

The format is described in ``edge/evaluation/README.md``. Everything is validated on load, so a labelling mistake is
reported with the clip and field instead of skewing the scores.
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from lp_edge.evaluation.metrics import Event, TruthBox
from lp_edge.events import ACTIVITY_NAME
from lp_edge.remote_config import Zone

#: What a clip is evaluated as; each has its own acceptance threshold (thresholds.py).
PROFILES = ("cash_drawer", "bar_counter", "kitchen_pass", "cafe_counter", "retail_checkout", "other")
#: Event types a label may state, and what the agent's observations are turned into.
EVENT_TYPES = ("drawer_open", "line_crossed", "handling", "presence")
#: Conditions the design asks every profile's footage to cover.
PERIODS = ("peak", "quiet")
LIGHTING = ("day", "night")
MIN_ANGLES = 2
#: Till records a labeller may give for a clip (``pos``), with the names the backend ledger uses.
POS_TYPES = ("sale.completed", "refund.issued", "sale.line_voided", "order.cancelled", "cash.movement",
             "cash_drawer.opened", "cash_session.opened", "cash_session.closed")
#: Business findings a labeller may confirm for a clip (``findings``): the backend's camera rules.
FINDING_RULES = ("V-C1",)


class DatasetError(ValueError):
    pass


@dataclass(frozen=True)
class LabelledFrame:
    t: float
    boxes: tuple[TruthBox, ...]


@dataclass(frozen=True)
class CountLabel:
    """The true number of ``type`` events in a zone over the whole clip, optionally of one class and direction."""

    zone: str
    type: str
    count: int
    attrs: Mapping[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class PosRecord:
    """A till record in the clip's time (seconds from the clip start)."""

    t: float
    type: str


@dataclass(frozen=True)
class ScanBatch:
    """One transaction's items passing a ``scan_area`` zone (LP5), from ``start`` to ``end`` in the clip's time: the
    true number of items passed over the scanner (``items``) and, when the labeller counted them, of the items carried
    past it (``bypassed``)."""

    id: str
    zone: str
    start: float
    end: float
    items: int
    bypassed: int | None = None


@dataclass(frozen=True)
class ActivityLabel:
    """An activity seen in a zone from ``start`` to ``end`` in the clip's time (LP7, research)."""

    zone: str
    activity: str
    start: float
    end: float


@dataclass(frozen=True)
class Clip:
    id: str
    video: Path
    profile: str
    shift: str
    period: str | None
    lighting: str | None
    angle: str | None
    zones: tuple[Zone, ...]
    frames: tuple[LabelledFrame, ...]
    events: tuple[Event, ...]
    counts: tuple[CountLabel, ...]
    #: the labeller states that every alert-raising event of the clip is labelled (``eventsComplete: true``), so an
    #: unmatched prediction is a false alert; without it false alerts are not counted for the clip
    events_complete: bool
    batch: str | None = None
    labels: Path | None = None
    #: the till's records during the clip (``pos``), or None when the labeller gave none: transaction correlation is
    #: then not scored for the clip
    pos: tuple[PosRecord, ...] | None = None
    #: findings a person confirmed as real (``findings``: an unexplained drawer opening that was a loss or a breach of
    #: procedure), or None when not labelled
    findings: tuple[Event, ...] | None = None
    #: transaction batches at a checkout's scanner with their true item counts (``batches``, LP5)
    batches: tuple[ScanBatch, ...] = ()
    #: activities seen in the clip (``activities``, LP7 research), or None when not labelled: activity segments are
    #: then not scored for the clip
    activities: tuple[ActivityLabel, ...] | None = None


@dataclass(frozen=True)
class Dataset:
    name: str
    root: Path
    clips: tuple[Clip, ...]
    description: str = ""

    def classes(self) -> set[str]:
        return {b.cls for c in self.clips for f in c.frames for b in f.boxes}


def _number(value: Any, where: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise DatasetError(f"{where}: a number is expected")
    return float(value)


def _box(value: Any, where: str) -> tuple[float, float, float, float]:
    if not isinstance(value, list) or len(value) != 4:
        raise DatasetError(f"{where}: box must be [x1, y1, x2, y2]")
    x1, y1, x2, y2 = (_number(v, where) for v in value)
    if not (0 <= x1 < x2 <= 1 and 0 <= y1 < y2 <= 1):
        raise DatasetError(f"{where}: box must be normalised (0–1) with x1 < x2 and y1 < y2")
    return (x1, y1, x2, y2)


def _whole(value: Any, where: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise DatasetError(f"{where}: a whole number ≥ 0 is expected")
    return value


def _attrs(item: Mapping[str, Any]) -> dict[str, str]:
    return {k: str(item[k]) for k in ("class", "direction", "action") if item.get(k) is not None}


@dataclass(frozen=True)
class _Labels:
    frames: list[LabelledFrame]
    events: list[Event]
    counts: list[CountLabel]
    complete: bool
    pos: tuple[PosRecord, ...] | None
    findings: tuple[Event, ...] | None
    batches: tuple[ScanBatch, ...] = ()
    activities: tuple[ActivityLabel, ...] | None = None


def _batches(items: Any, clip_id: str, scan_zones: set[str]) -> tuple[ScanBatch, ...]:
    if not isinstance(items, list):
        raise DatasetError(f"clip {clip_id} batches: a list is expected")
    out = []
    for i, b in enumerate(items):
        where = f"clip {clip_id} batches[{i}]"
        if not isinstance(b, dict):
            raise DatasetError(f"{where}: an object is expected")
        if b.get("zone") not in scan_zones:
            raise DatasetError(f"{where}: zone {b.get('zone')!r} is not a scan_area zone of the clip")
        start, end = _number(b.get("start"), f"{where}.start"), _number(b.get("end"), f"{where}.end")
        if end <= start:
            raise DatasetError(f"{where}: end must be after start")
        bypassed = None if b.get("bypassed") is None else _whole(b["bypassed"], f"{where}.bypassed")
        out.append(ScanBatch(str(b.get("id") or f"{clip_id}#{i + 1}"), str(b["zone"]), start, end,
                             _whole(b.get("items"), f"{where}.items"), bypassed))
    if len({b.id for b in out}) != len(out):
        raise DatasetError(f"clip {clip_id} batches: ids must be unique")
    return tuple(sorted(out, key=lambda b: b.start))


def _activities(items: Any, clip_id: str, zone_ids: set[str]) -> tuple[ActivityLabel, ...]:
    if not isinstance(items, list):
        raise DatasetError(f"clip {clip_id} activities: a list is expected")
    out = []
    for i, a in enumerate(items):
        where = f"clip {clip_id} activities[{i}]"
        if not isinstance(a, dict):
            raise DatasetError(f"{where}: an object is expected")
        if a.get("zone") not in zone_ids:
            raise DatasetError(f"{where}: zone {a.get('zone')!r} is not a zone of the clip")
        if not isinstance(a.get("activity"), str) or not ACTIVITY_NAME.fullmatch(a["activity"]):
            raise DatasetError(f"{where}: activity must be 1 to 64 lowercase letters, digits or '_', starting with a"
                               " letter")
        start, end = _number(a.get("start"), f"{where}.start"), _number(a.get("end"), f"{where}.end")
        if end <= start:
            raise DatasetError(f"{where}: end must be after start")
        out.append(ActivityLabel(str(a["zone"]), a["activity"], start, end))
    return tuple(sorted(out, key=lambda a: a.start))


def _labels(path: Path, clip_id: str, zone_ids: set[str], scan_zones: set[str] | None = None) -> _Labels:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as err:
        raise DatasetError(f"clip {clip_id}: cannot read labels {path.name}: {err}") from err
    if not isinstance(data, dict):
        raise DatasetError(f"clip {clip_id}: labels must be a JSON object")
    frames = []
    for i, f in enumerate(data.get("frames") or []):
        where = f"clip {clip_id} frames[{i}]"
        boxes = []
        for j, b in enumerate(f.get("boxes") or []):
            if not isinstance(b.get("cls"), str) or not b["cls"]:
                raise DatasetError(f"{where}.boxes[{j}]: cls is required")
            track = b.get("track")
            boxes.append(TruthBox(b["cls"], _box(b.get("box"), f"{where}.boxes[{j}]"),
                                  None if track is None else str(track)))
        frames.append(LabelledFrame(_number(f.get("t"), f"{where}.t"), tuple(boxes)))
    events = []
    for i, e in enumerate(data.get("events") or []):
        where = f"clip {clip_id} events[{i}]"
        if e.get("type") not in EVENT_TYPES:
            raise DatasetError(f"{where}: type must be one of {', '.join(EVENT_TYPES)}")
        if e.get("zone") not in zone_ids:
            raise DatasetError(f"{where}: zone {e.get('zone')!r} is not a zone of the clip")
        end = None if e.get("end") is None else _number(e["end"], f"{where}.end")
        events.append(Event(str(e["zone"]), e["type"], _number(e.get("t"), f"{where}.t"), end, _attrs(e)))
    counts = []
    for i, c in enumerate(data.get("counts") or []):
        where = f"clip {clip_id} counts[{i}]"
        if c.get("type") not in ("line_crossed", "handling"):
            raise DatasetError(f"{where}: type must be line_crossed or handling")
        if c.get("zone") not in zone_ids:
            raise DatasetError(f"{where}: zone {c.get('zone')!r} is not a zone of the clip")
        count = c.get("count")
        if isinstance(count, bool) or not isinstance(count, int) or count < 0:
            raise DatasetError(f"{where}: count must be a whole number ≥ 0")
        counts.append(CountLabel(str(c["zone"]), c["type"], count, _attrs(c)))
    frames.sort(key=lambda f: f.t)
    pos = None
    if data.get("pos") is not None:
        records = []
        for i, r in enumerate(data["pos"]):
            where = f"clip {clip_id} pos[{i}]"
            if r.get("type") not in POS_TYPES:
                raise DatasetError(f"{where}: type must be one of {', '.join(POS_TYPES)}")
            records.append(PosRecord(_number(r.get("t"), f"{where}.t"), r["type"]))
        pos = tuple(sorted(records, key=lambda r: r.t))
    findings = None
    if data.get("findings") is not None:
        found = []
        for i, f in enumerate(data["findings"]):
            where = f"clip {clip_id} findings[{i}]"
            if f.get("rule") not in FINDING_RULES:
                raise DatasetError(f"{where}: rule must be one of {', '.join(FINDING_RULES)}")
            if f.get("zone") not in zone_ids:
                raise DatasetError(f"{where}: zone {f.get('zone')!r} is not a zone of the clip")
            found.append(Event(str(f["zone"]), str(f["rule"]), _number(f.get("t"), f"{where}.t")))
        findings = tuple(found)
    batches = () if data.get("batches") is None else _batches(data["batches"], clip_id, scan_zones or set())
    activities = None if data.get("activities") is None else _activities(data["activities"], clip_id, zone_ids)
    return _Labels(frames, events, counts, data.get("eventsComplete") is True, pos, findings, batches=batches,
                   activities=activities)


def load_dataset(path: str | Path) -> Dataset:
    """Loads ``dataset.yaml`` (or the directory that holds it) and every clip's labels."""
    manifest = Path(path)
    if manifest.is_dir():
        manifest = manifest / "dataset.yaml"
    try:
        data = yaml.safe_load(manifest.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as err:
        raise DatasetError(f"cannot read {manifest}: {err}") from err
    if not isinstance(data, dict) or not isinstance(data.get("clips"), list) or not data["clips"]:
        raise DatasetError(f"{manifest}: a name and a non-empty list of clips are required")
    root = manifest.parent
    clips = []
    seen: set[str] = set()
    for i, c in enumerate(data["clips"]):
        clip_id = str(c.get("id") or "")
        if not clip_id or clip_id in seen:
            raise DatasetError(f"clips[{i}]: id is required and must be unique")
        seen.add(clip_id)
        profile = c.get("profile")
        if profile not in PROFILES:
            raise DatasetError(f"clip {clip_id}: profile must be one of {', '.join(PROFILES)}")
        try:
            zones = tuple(Zone.parse(z) for z in c.get("zones") or [])
        except (KeyError, ValueError) as err:
            raise DatasetError(f"clip {clip_id}: invalid zone: {err}") from err
        if not zones:
            raise DatasetError(f"clip {clip_id}: at least one zone is required")
        conditions = c.get("conditions") or {}
        if conditions.get("period") not in (None, *PERIODS) or conditions.get("lighting") not in (None, *LIGHTING):
            raise DatasetError(f"clip {clip_id}: conditions.period is peak or quiet, conditions.lighting day or night")
        if not c.get("video") or not c.get("labels"):
            raise DatasetError(f"clip {clip_id}: video and labels are required")
        labels = _labels(root / c["labels"], clip_id, {z.id for z in zones},
                         {z.id for z in zones if z.zone_type == "scan_area"})
        clips.append(Clip(
            id=clip_id,
            video=root / c["video"],
            profile=profile,
            shift=str(c.get("shift") or clip_id),
            period=conditions.get("period"),
            lighting=conditions.get("lighting"),
            angle=None if conditions.get("angle") is None else str(conditions["angle"]),
            zones=zones,
            frames=tuple(labels.frames),
            events=tuple(labels.events),
            counts=tuple(labels.counts),
            events_complete=labels.complete,
            batch=None if c.get("batch") is None else str(c["batch"]),
            labels=root / c["labels"],
            pos=labels.pos,
            findings=labels.findings,
            batches=labels.batches,
            activities=labels.activities,
        ))
    name = str(data.get("name") or manifest.parent.name)
    return Dataset(name, root, tuple(clips), str(data.get("description") or ""))


def coverage(dataset: Dataset) -> dict[str, dict[str, Any]]:
    """Per profile: the conditions its clips cover and what the design asks for that is missing."""
    out: dict[str, dict[str, Any]] = {}
    for profile in sorted({c.profile for c in dataset.clips}):
        clips = [c for c in dataset.clips if c.profile == profile]
        periods = sorted({c.period for c in clips if c.period})
        lighting = sorted({c.lighting for c in clips if c.lighting})
        angles = sorted({c.angle for c in clips if c.angle})
        missing = [f"no {p} footage" for p in PERIODS if p not in periods]
        missing += [f"no {light} footage" for light in LIGHTING if light not in lighting]
        if len(angles) < MIN_ANGLES:
            missing.append(f"{len(angles)} camera angle(s), at least {MIN_ANGLES} needed")
        with_pos = [c for c in clips if c.pos is not None]
        if profile == "cash_drawer" and not with_pos:
            missing.append("no clip has its till records (pos) labelled, so transaction correlation is not scored")
        batches = sum(len(c.batches) for c in clips)
        if profile == "retail_checkout" and not batches:
            missing.append("no scan batch is labelled (batches), so the item count is not scored")
        out[profile] = {
            "clips": len(clips),
            "shifts": len({c.shift for c in clips}),
            "labelledEvents": sum(len(c.events) for c in clips),
            "labelledFrames": sum(len(c.frames) for c in clips),
            "periods": periods,
            "lighting": lighting,
            "angles": angles,
            "clipsWithTillRecords": len(with_pos),
            "clipsWithConfirmedFindings": sum(1 for c in clips if c.findings is not None),
            "missing": missing,
        }
        if profile == "retail_checkout" or batches:
            out[profile]["labelledBatches"] = batches
    return out

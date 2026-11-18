"""Runs a candidate detector over labelled footage the way the agent would, and scores what came out.

Each clip is read at the agent's analysis rate and width. Every analysed frame goes through the candidate, the agent's
tracker and the agent's own zone profiles (``build_profile``), so the scores are those of the whole pipeline the agent
would run, not of the detector alone. Box-level scores use the analysed frame nearest to each labelled time.

A candidate is either a model the agent can load (a detector kind, built in or installed through the
``lp_edge.detectors`` entry point) or a predictions file made elsewhere: then accuracy is measured but speed only if
the file states it.
"""

from __future__ import annotations

import json
import logging
import platform
import time
from collections import defaultdict
from collections.abc import Callable, Iterator, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

import yaml

from lp_edge.detectors.base import Detection
from lp_edge.evaluation import activities, provenance
from lp_edge.evaluation.bench import BenchSettings, run_bench
from lp_edge.evaluation.business import drawer_findings
from lp_edge.evaluation.dataset import Clip, CountLabel, Dataset
from lp_edge.evaluation.metrics import (
    Event,
    PredBox,
    TruthBox,
    count_error,
    match_events,
    percentile,
    ratio,
    score_frames,
    tracking,
)
from lp_edge.evaluation.retail import batch_counts
from lp_edge.events import DWELL, GAP, HANDLING, LINE_CROSSED, STATE_CHANGED, Observation
from lp_edge.profiles import ProfileEnv, UnavailableProfile, build_profile
from lp_edge.profiles.base import FrameContext
from lp_edge.tracker import ByteTracker

log = logging.getLogger(__name__)

#: The event type each profile's false alerts are counted on (None: no alert-raising event).
ALERT_EVENT = {"cash_drawer": "drawer_open", "bar_counter": "handling", "kitchen_pass": "line_crossed",
               "cafe_counter": "line_crossed", "retail_checkout": None, "other": None}
CAMERA_ID = "evaluation"


class CandidateError(ValueError):
    pass


@dataclass(frozen=True)
class Candidate:
    name: str
    licences: tuple[str, ...]
    detector: str | None = None
    model: Path | None = None
    version: str = ""
    classes: tuple[str, ...] = ()
    input_size: tuple[int, int] = (640, 640)
    providers: tuple[str, ...] = ()
    predictions: Path | None = None
    notes: str = ""
    #: the SHA-256 the weight (or predictions) file must have, and where the weights were downloaded from
    sha256: str = ""
    source_url: str = ""


def load_candidates(path: str | Path) -> list[Candidate]:
    file = Path(path)
    try:
        data = yaml.safe_load(file.read_text(encoding="utf-8")) or {}
    except (OSError, yaml.YAMLError) as err:
        raise CandidateError(f"cannot read {file}: {err}") from err
    out = []
    for i, c in enumerate(data.get("candidates") or []):
        name = str(c.get("name") or "")
        if not name:
            raise CandidateError(f"candidates[{i}]: name is required")
        has_model = bool(c.get("detector") and c.get("model"))
        if has_model == bool(c.get("predictions")):
            raise CandidateError(f"candidate {name}: give either detector and model, or predictions")
        sha256 = str(c.get("sha256") or "").strip().lower()
        if sha256 and (len(sha256) != 64 or any(ch not in "0123456789abcdef" for ch in sha256)):
            raise CandidateError(f"candidate {name}: sha256 must be 64 hexadecimal characters")
        size = c.get("inputSize") or 640
        input_size = (int(size[0]), int(size[1])) if isinstance(size, list) else (int(size), int(size))
        out.append(Candidate(
            name=name,
            licences=tuple(str(k) for k in c.get("licences") or []),
            detector=c.get("detector"),
            model=(file.parent / c["model"]) if c.get("model") else None,
            version=str(c.get("version") or name),
            classes=tuple(str(x) for x in c.get("classes") or []),
            input_size=input_size,
            providers=tuple(str(x) for x in c.get("providers") or []),
            predictions=(file.parent / c["predictions"]) if c.get("predictions") else None,
            notes=str(c.get("notes") or "").strip(),
            sha256=sha256,
            source_url=str(c.get("sourceUrl") or ""),
        ))
    if not out:
        raise CandidateError(f"{file}: no candidates")
    return out


# --------------------------------------------------------------------------------------------- frames and detections


class FrameSource(Protocol):
    def frames(self, clip: Clip, fps: float) -> Iterator[tuple[float, Any]]:
        """``(seconds from the clip start, BGR picture)`` at about ``fps``."""
        ...


class VideoSource:
    """Reads a clip's video file with OpenCV, keeps about ``fps`` frames a second, resized to the agent's width."""

    def __init__(self, max_width: int = 960) -> None:
        self.max_width = max_width

    def frames(self, clip: Clip, fps: float) -> Iterator[tuple[float, Any]]:
        import cv2  # noqa: PLC0415

        from lp_edge.video import resize_to_width  # noqa: PLC0415

        if not clip.video.is_file():
            raise FileNotFoundError(f"clip {clip.id}: video {clip.video} not found")
        capture = cv2.VideoCapture(str(clip.video))
        try:
            native = capture.get(cv2.CAP_PROP_FPS) or 25.0
            index = 0
            next_t = 0.0
            while True:
                ok, frame = capture.read()
                if not ok:
                    break
                t = index / native
                index += 1
                if t + 1e-9 < next_t:
                    continue
                next_t = t + 1.0 / fps
                yield t, resize_to_width(frame, self.max_width)
        finally:
            capture.release()


#: ``(seconds, picture)`` → detections and how long the detector took (ms), when known.
DetectFn = Callable[[float, Any], tuple[list[Detection], float | None]]


class ModelRunner:
    """A candidate model loaded the way the agent loads it."""

    def __init__(self, candidate: Candidate) -> None:
        from lp_edge.detectors import load_detector  # noqa: PLC0415
        from lp_edge.remote_config import ModelSpec  # noqa: PLC0415

        assert candidate.model is not None and candidate.detector is not None
        if not candidate.model.is_file():
            raise CandidateError(f"candidate {candidate.name}: model {candidate.model} not found")
        if candidate.sha256:
            from lp_edge.evaluation.provenance import sha256_file  # noqa: PLC0415

            actual = sha256_file(candidate.model)
            if actual != candidate.sha256:
                raise CandidateError(f"candidate {candidate.name}: {candidate.model.name} has SHA-256 {actual}, the"
                                     f" candidates file expects {candidate.sha256}")
        spec = ModelSpec(version=candidate.version, detector=candidate.detector, url="", sha256="", signature="",
                         input_size=candidate.input_size, classes=candidate.classes)
        started = time.perf_counter()
        self.detector = load_detector(candidate.model, spec, candidate.providers)
        self.load_seconds = time.perf_counter() - started
        self.classes = frozenset(self.detector.classes)

    def for_clip(self, clip: Clip) -> DetectFn:
        def detect(t: float, frame: Any) -> tuple[list[Detection], float | None]:
            started = time.perf_counter()
            detections = self.detector.detect(frame)
            return detections, (time.perf_counter() - started) * 1000.0

        return detect


def _detection(d: dict[str, Any]) -> Detection:
    x1, y1, x2, y2 = (float(v) for v in d["box"])
    return Detection(str(d["cls"]), float(d["score"]), (x1, y1, x2, y2))


class PredictionsRunner:
    """Detections made elsewhere, one JSON object per line:
    ``{"clip": id, "t": seconds, "detections": [{"cls", "score", "box": [x1, y1, x2, y2]}], "latencyMs"?: n}``."""

    def __init__(self, candidate: Candidate, fps: float) -> None:
        assert candidate.predictions is not None
        self.window = 0.5 / fps
        self.load_seconds = None
        self.by_clip: dict[str, list[tuple[float, list[Detection], float | None]]] = defaultdict(list)
        classes: set[str] = set(candidate.classes)
        try:
            lines = candidate.predictions.read_text(encoding="utf-8").splitlines()
        except OSError as err:
            raise CandidateError(f"candidate {candidate.name}: cannot read {candidate.predictions}: {err}") from err
        for n, line in enumerate(lines, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                detections = [_detection(d) for d in row.get("detections") or []]
                latency = None if row.get("latencyMs") is None else float(row["latencyMs"])
                self.by_clip[str(row["clip"])].append((float(row["t"]), detections, latency))
            except (KeyError, TypeError, ValueError) as err:
                raise CandidateError(f"{candidate.predictions.name} line {n}: {err}") from err
            classes.update(d.cls for d in detections)
        for rows in self.by_clip.values():
            rows.sort(key=lambda r: r[0])
        self.classes = frozenset(classes)

    def for_clip(self, clip: Clip) -> DetectFn:
        rows = self.by_clip.get(clip.id, [])

        def detect(t: float, frame: Any) -> tuple[list[Detection], float | None]:
            best = min(rows, key=lambda r: abs(r[0] - t), default=None)
            if best is None or abs(best[0] - t) > self.window:
                return [], None
            return best[1], best[2]

        return detect


# --------------------------------------------------------------------------------------------- one clip


@dataclass
class ClipRun:
    clip: Clip
    frames: int = 0
    latencies_ms: list[float] = field(default_factory=list)
    observations: list[Observation] = field(default_factory=list)
    #: per analysed frame: (t, detections, confirmed tracks)
    snapshots: list[tuple[float, list[PredBox], list[PredBox]]] = field(default_factory=list)
    unavailable_zones: dict[str, str] = field(default_factory=dict)
    error: str | None = None


def run_clip(clip: Clip, detect: DetectFn, classes: frozenset[str], source: FrameSource, fps: float) -> ClipRun:
    run = ClipRun(clip)
    tracker = ByteTracker()
    env = ProfileEnv(new_clip_id=lambda: "evaluation")
    profiles = []
    for zone in clip.zones:
        profile = build_profile(zone, CAMERA_ID, env, classes)
        if isinstance(profile, UnavailableProfile):
            run.unavailable_zones[zone.id] = profile.reason
        profiles.append(profile)
    tracked = set().union(*(p.wanted_classes() for p in profiles)) if profiles else set()
    motion: Any = None
    last: float | None = None
    try:
        for t, frame in source.frames(clip, fps):
            if last is None:
                for p in profiles:
                    p.start(t)
            detections, latency = detect(t, frame)
            if latency is not None:
                run.latencies_ms.append(latency)
            tracks = tracker.update([d for d in detections if d.cls in tracked], t)
            if any(p.needs_motion() for p in profiles):
                if motion is None:
                    from lp_edge.detectors.motion import MotionModel  # noqa: PLC0415

                    motion = MotionModel()
                motion.update(frame)
            height, width = (frame.shape[0], frame.shape[1]) if hasattr(frame, "shape") else (9, 16)
            context = FrameContext(ts=t, frame=frame, tracks=tracks, motion=motion, width=width, height=height)
            for p in profiles:
                run.observations.extend(p.process(context))
            run.snapshots.append((t, [PredBox(d.cls, d.score, d.box) for d in detections],
                                  [PredBox(tr.cls, tr.mean_score, tr.box, tr.id) for tr in tracks]))
            run.frames += 1
            last = t
    except Exception as err:  # noqa: BLE001  (one unreadable clip must not end the evaluation)
        log.exception("clip %s failed", clip.id)
        run.error = f"{type(err).__name__}: {err}"
    if last is not None:
        for p in profiles:
            run.observations.extend(p.stop(last))
    return run


def predicted_events(observations: Sequence[Observation]) -> list[Event]:
    """The agent's observations as comparable events."""
    out = []
    for o in observations:
        p = o.payload
        if o.type == STATE_CHANGED and p.get("to") == "open":
            out.append(Event(o.zone_id, "drawer_open", o.occurred_at))
        elif o.type == LINE_CROSSED:
            out.append(Event(o.zone_id, "line_crossed", o.occurred_at,
                             attrs={"class": str(p.get("class")), "direction": str(p.get("direction"))}))
        elif o.type == HANDLING:
            out.append(Event(o.zone_id, "handling", o.occurred_at,
                             attrs={"class": str(p.get("object")), "action": str(p.get("action"))}))
        elif o.type == DWELL:
            duration = float(p.get("durationSeconds") or 0.0)
            out.append(Event(o.zone_id, "presence", o.occurred_at - duration, o.occurred_at))
    return out


def gap_count(observations: Sequence[Observation]) -> int:
    return sum(1 for o in observations if o.type == GAP)


def aligned_frames(run: ClipRun, fps: float) -> tuple[list[tuple[list[PredBox], list[TruthBox]]],
                                                       list[tuple[list[PredBox], list[TruthBox]]], int]:
    """Labelled frames with the analysed frame nearest to them: (detections, truth), (tracks, truth), unaligned."""
    window = 0.5 / fps + 1e-6
    det, trk = [], []
    unaligned = 0
    for labelled in run.clip.frames:
        nearest = min(run.snapshots, key=lambda s: abs(s[0] - labelled.t), default=None)
        if nearest is None or abs(nearest[0] - labelled.t) > window:
            unaligned += 1
            continue
        det.append((nearest[1], list(labelled.boxes)))
        trk.append((nearest[2], list(labelled.boxes)))
    return det, trk, unaligned


def _count_key(label: CountLabel) -> tuple[str, str, tuple[tuple[str, str], ...]]:
    return (label.zone, label.type, tuple(sorted(label.attrs.items())))


def predicted_count(events: Sequence[Event], label: CountLabel) -> int:
    return sum(1 for e in events if e.zone == label.zone and e.type == label.type
               and all(e.attrs.get(k) == v for k, v in label.attrs.items()))


# --------------------------------------------------------------------------------------------- a whole candidate


def _profile_result(profile: str, runs: list[ClipRun], fps: float, tolerance: float, thresholds: dict[str, Any],
                    iou_threshold: float, labelled_classes: set[str]) -> dict[str, Any]:
    tp = predicted = labelled = 0
    missed_examples: list[dict[str, Any]] = []
    false_alerts: dict[tuple[str, str], int] = defaultdict(int)
    shift_counts: dict[tuple[str, tuple], list[int]] = defaultdict(lambda: [0, 0])  # [true, predicted]
    det_frames, trk_frames = [], []
    unaligned = gaps = 0
    alert_type = ALERT_EVENT.get(profile)
    complete_shifts = set()
    predicted_by_clip = []
    for run in runs:
        clip = run.clip
        events = predicted_events(run.observations)
        predicted_by_clip.append((clip, events))
        gaps += gap_count(run.observations)
        labelled_types = {e.type for e in clip.events}
        scored = [e for e in events if e.type in labelled_types]
        match = match_events(scored, list(clip.events), tolerance)
        tp += match.true_positives
        predicted += match.predicted
        labelled += match.labelled
        missed_examples.extend({"clip": clip.id, "zone": e.zone, "type": e.type, "t": round(e.t, 2)}
                               for e in match.missed[:5])
        if clip.events_complete and alert_type is not None:
            complete_shifts.add(clip.shift)
            for zone in clip.zones:
                false_alerts[(clip.shift, zone.id)] += 0
            unmatched = [e for e in match.false_positives if e.type == alert_type]
            if alert_type not in labelled_types:
                unmatched = [e for e in events if e.type == alert_type]
            for e in unmatched:
                false_alerts[(clip.shift, e.zone)] += 1
        unit = clip.batch if (profile == "retail_checkout" and clip.batch) else clip.shift
        for label in clip.counts:
            totals = shift_counts[(unit, _count_key(label))]
            totals[0] += label.count
            totals[1] += predicted_count(events, label)
        d, k, u = aligned_frames(run, fps)
        det_frames.extend(d)
        trk_frames.extend(k)
        unaligned += u
    per_unit = []
    for (unit, (zone, kind, attrs)), (true, pred) in sorted(shift_counts.items()):
        per_unit.append({"shift": unit, "zone": zone, "type": kind, "attrs": dict(attrs), "true": true,
                         "predicted": pred, "error": count_error(pred, true)})
    errors = [u["error"] for u in per_unit]
    alerts = [{"shift": s, "zone": z, "falseAlerts": n} for (s, z), n in sorted(false_alerts.items())]
    detection = {cls: s.summary() for cls, s in sorted(score_frames(det_frames, iou_threshold,
                                                                      labelled_classes).items())}
    result: dict[str, Any] = {
        "profile": profile,
        "clips": len(runs),
        "framesAnalysed": sum(r.frames for r in runs),
        "clipErrors": {r.clip.id: r.error for r in runs if r.error},
        "zonesNotObservable": {f"{r.clip.id}/{z}": why for r in runs for z, why in r.unavailable_zones.items()},
        "gapObservations": gaps,
        "events": {"labelled": labelled, "predicted": predicted, "truePositives": tp,
                   "precision": ratio(tp, predicted), "recall": ratio(tp, labelled), "missedExamples":
                   missed_examples[:20]},
        "counts": {"perShift": per_unit,
                   "maxError": None if not errors or None in errors else max(errors),
                   "meanError": None if not errors or None in errors else sum(errors) / len(errors),
                   "undefined": sum(1 for e in errors if e is None)},
        "falseAlerts": {"perShiftAndZone": alerts, "max": max((a["falseAlerts"] for a in alerts), default=None),
                        "shiftsFullyLabelled": len(complete_shifts)},
        "detection": detection,
        "tracking": tracking(trk_frames, iou_threshold),
        "labelledFramesWithoutAnalysedFrame": unaligned,
    }
    if profile == "cash_drawer":
        result["business"] = drawer_findings(predicted_by_clip, tolerance)
    if profile == "retail_checkout" or any(clip.batches for clip, _ in predicted_by_clip):
        result["batches"] = batch_counts(predicted_by_clip)
    result["verdict"] = verdict(profile, result, thresholds)
    return result


def verdict(profile: str, result: dict[str, Any], thresholds: dict[str, Any]) -> dict[str, Any]:
    """``pass`` when every applicable check passes, ``fail`` when one fails, ``not measured`` when none applies."""
    limits = thresholds["profiles"].get(profile, {})
    checks = []

    def check(name: str, value: float | None, limit: float, at_least: bool, why_missing: str) -> None:
        if value is None:
            checks.append({"check": name, "status": "not measured", "detail": why_missing})
            return
        ok = value >= limit if at_least else value <= limit
        checks.append({"check": name, "status": "pass" if ok else "fail", "value": round(value, 4), "limit": limit})

    events = result["events"]
    if "minPrecision" in limits:
        check("event precision", events["precision"], limits["minPrecision"], True, "no predicted events to score")
    if "minRecall" in limits:
        check("event recall", events["recall"], limits["minRecall"], True, "no labelled events")
    correlation = (result.get("business") or {}).get("transactionCorrelation") or {}
    no_pos = "no clip has its till records (pos) labelled"
    if "minFindingPrecision" in limits:
        why = no_pos if not correlation.get("clips") else "the pipeline raised no finding"
        check("finding precision (transaction correlation)", correlation.get("precision"),
              limits["minFindingPrecision"], True, why)
    if "minFindingRecall" in limits:
        why = no_pos if not correlation.get("clips") else "the labelled events raise no finding"
        check("finding recall (transaction correlation)", correlation.get("recall"), limits["minFindingRecall"],
              True, why)
    if "maxCountError" in limits:
        if profile == "retail_checkout":
            batches = result.get("batches") or {}
            if not batches.get("batches"):
                checks.append({"check": "count error per batch", "status": "not measured",
                               "detail": "no scan batch is labelled with its true item count (LP5)"})
            else:
                check("count error per batch", batches["maxError"], limits["maxCountError"], False,
                      "a batch has a true count of 0 and a predicted count above 0")
        else:
            counts = result["counts"]
            why = ("a shift has a true count of 0 and a predicted count above 0" if counts["undefined"]
                   else "no count labels")
            check("count error per shift (max)", counts["maxError"], limits["maxCountError"], False, why)
    if ALERT_EVENT.get(profile) is not None:
        check("false alerts per shift per zone (max)", result["falseAlerts"]["max"],
              thresholds["maxFalseAlertsPerShiftPerZone"], False, "no clip has all its events labelled")
    statuses = {c["status"] for c in checks}
    if result["clipErrors"]:
        status = "fail"
    elif "fail" in statuses:
        status = "fail"
    elif statuses == {"pass"}:
        status = "pass"
    else:
        status = "not measured"
    return {"status": status, "checks": checks}


def speed(latencies: list[float], fps: float, load_seconds: float | None) -> dict[str, Any]:
    p50 = percentile(latencies, 50)
    p95 = percentile(latencies, 95)
    return {
        "frames": len(latencies),
        "modelLoadSeconds": None if load_seconds is None else round(load_seconds, 3),
        "latencyMsP50": None if p50 is None else round(p50, 2),
        "latencyMsP95": None if p95 is None else round(p95, 2),
        "latencyMsMax": None if not latencies else round(max(latencies), 2),
        # How many cameras one detector process can keep up with at the analysis rate, at p95 latency.
        "camerasAtAnalysisRate": None if not p95 else round(1000.0 / p95 / fps, 2),
        "measured": bool(latencies),
    }


def peak_memory_mb() -> float | None:
    """Peak resident memory of this process (Linux and macOS); the whole evaluation, not the detector alone."""
    try:
        import resource  # noqa: PLC0415
    except ImportError:
        return None
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return round(peak / (1024 * 1024) if platform.system() == "Darwin" else peak / 1024, 1)


def environment() -> dict[str, Any]:
    info: dict[str, Any] = {"platform": platform.platform(), "machine": platform.machine(),
                            "processor": platform.processor(), "python": platform.python_version()}
    try:
        import os  # noqa: PLC0415

        info["cpus"] = os.cpu_count()
        import onnxruntime  # noqa: PLC0415

        info["onnxruntime"] = onnxruntime.__version__
        info["onnxProviders"] = onnxruntime.get_available_providers()
    except ImportError:
        info["onnxruntime"] = None
    return info


def evaluate(candidate: Candidate, dataset: Dataset, source: FrameSource, *, fps: float, tolerance: float,
             thresholds: dict[str, Any], iou_threshold: float = 0.5,
             runner: ModelRunner | PredictionsRunner | None = None,
             bench: BenchSettings | None = None) -> dict[str, Any]:
    """Every clip of the dataset through one candidate; results per profile, speed and memory; then, with ``bench``,
    the sustained multi-camera run on this machine."""
    if runner is None:
        runner = ModelRunner(candidate) if candidate.predictions is None else PredictionsRunner(candidate, fps)
    runs: dict[str, list[ClipRun]] = defaultdict(list)
    latencies: list[float] = []
    for clip in dataset.clips:
        run = run_clip(clip, runner.for_clip(clip), runner.classes, source, fps)
        runs[clip.profile].append(run)
        latencies.extend(run.latencies_ms)
        log.info("candidate %s clip %s: %d frames, %d observations", candidate.name, clip.id, run.frames,
                 len(run.observations))
    labelled = dataset.classes()
    missing_classes = sorted(labelled - set(runner.classes))
    peak = peak_memory_mb() if candidate.predictions is None else None
    if candidate.predictions is not None:
        bench_result: dict[str, Any] = {"measured": False, "reason": "a predictions file cannot be benchmarked: run"
                                        " the model on the edge box"}
    elif bench is None or bench.cameras <= 0:
        bench_result = {"measured": False, "reason": "no sustained multi-camera run (run with --cameras)"}
    else:
        log.info("candidate %s: %d cameras for %.0f s", candidate.name, bench.cameras, bench.seconds)
        bench_result = run_bench(runner, dataset.clips, lambda clip: source.frames(clip, fps), bench)
    return {
        "candidate": candidate.name,
        "version": candidate.version,
        "source": "predictions file" if candidate.predictions else f"{candidate.detector} model",
        "classes": sorted(runner.classes),
        "labelledClassesTheModelLacks": missing_classes,
        "profiles": {p: _profile_result(p, rs, fps, tolerance, thresholds, iou_threshold, labelled)
                     for p, rs in sorted(runs.items())},
        "speed": speed(latencies, fps, runner.load_seconds),
        "peakMemoryMb": peak,
        "bench": bench_result,
        "provenance": provenance.candidate(candidate),
        # LP7 research: reported, never part of a verdict or of eligibility
        "activityResearch": activities.score([(run.clip, run.observations) for rs in runs.values() for run in rs]),
    }

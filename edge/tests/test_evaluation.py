"""Detector evaluation harness: the measures, the dataset format, the licence register, and a whole run with a fake
detector through the agent's own tracker and counting profile."""

import json
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml

from lp_edge.detectors.base import Detection
from lp_edge.evaluation import activities as activity_scores
from lp_edge.evaluation import report as reports
from lp_edge.evaluation.cli import DEFAULT_REGISTER, main
from lp_edge.evaluation.dataset import ActivityLabel, DatasetError, coverage, load_dataset
from lp_edge.evaluation.licences import RegisterError, eligibility, load_register
from lp_edge.evaluation.metrics import (
    Event,
    PredBox,
    TruthBox,
    average_precision,
    count_error,
    iou,
    match_events,
    percentile,
    score_frames,
    tracking,
)
from lp_edge.evaluation.runner import Candidate, evaluate, verdict
from lp_edge.evaluation.thresholds import PROPOSED, load_thresholds
from lp_edge.events import ACTIVITY, Observation
from lp_edge.timeutil import iso

# ------------------------------------------------------------------------------------------------ metrics


def test_iou_of_identical_disjoint_and_half_overlapping_boxes():
    assert iou((0, 0, 1, 1), (0, 0, 1, 1)) == pytest.approx(1.0)
    assert iou((0, 0, 0.2, 0.2), (0.5, 0.5, 0.7, 0.7)) == 0.0
    assert iou((0, 0, 0.2, 0.2), (0.1, 0, 0.3, 0.2)) == pytest.approx(1 / 3)


def test_box_scoring_counts_each_labelled_box_once_and_ignores_unlabelled_classes():
    truth = [TruthBox("plate", (0.1, 0.1, 0.3, 0.3))]
    preds = [PredBox("plate", 0.9, (0.1, 0.1, 0.3, 0.3)), PredBox("plate", 0.8, (0.11, 0.1, 0.31, 0.3)),
             PredBox("car", 0.99, (0.5, 0.5, 0.6, 0.6))]
    scores = score_frames([(preds, truth)], 0.5, classes={"plate"})
    assert set(scores) == {"plate"}
    plate = scores["plate"].summary()
    assert (plate["truePositives"], plate["falsePositives"], plate["falseNegatives"]) == (1, 1, 0)
    assert plate["precision"] == pytest.approx(0.5)
    assert plate["recall"] == pytest.approx(1.0)


def test_average_precision():
    assert average_precision([(0.9, True), (0.8, True)], 2) == pytest.approx(1.0)
    # The false detection ranks first: precision 0.5 at recall 1.
    assert average_precision([(0.9, False), (0.8, True)], 1) == pytest.approx(0.5)
    assert average_precision([(0.9, True)], 2) == pytest.approx(0.5)
    assert average_precision([], 0) is None


def test_tracking_counts_an_identity_switch_and_mota():
    box = (0.1, 0.1, 0.3, 0.3)
    frames = [([PredBox("person", 0.9, box, 1)], [TruthBox("person", box, "a")]),
              ([PredBox("person", 0.9, box, 2)], [TruthBox("person", box, "a")]),
              ([], [TruthBox("person", box, "a")])]
    result = tracking(frames)
    assert result["identitySwitches"] == 1
    assert result["misses"] == 1
    assert result["mota"] == pytest.approx(1 - 2 / 3)


def test_events_match_closest_first_within_tolerance_with_the_labelled_attributes():
    truths = [Event("z", "line_crossed", 10.0, attrs={"direction": "in"}),
              Event("z", "line_crossed", 20.0, attrs={"direction": "in"})]
    preds = [Event("z", "line_crossed", 10.5, attrs={"direction": "in", "class": "plate"}),
             Event("z", "line_crossed", 11.0, attrs={"direction": "in"}),
             Event("z", "line_crossed", 20.1, attrs={"direction": "out"}),
             Event("other", "line_crossed", 20.0, attrs={"direction": "in"})]
    match = match_events(preds, truths, tolerance=2.0)
    assert match.true_positives == 1
    assert [p.t for p in match.false_positives] == [11.0, 20.1, 20.0]
    assert [t.t for t in match.missed] == [20.0]


def test_spans_match_on_temporal_overlap():
    truth = [Event("z", "presence", 0.0, 10.0)]
    assert match_events([Event("z", "presence", 2.0, 12.0)], truth, 0.5).true_positives == 1
    assert match_events([Event("z", "presence", 8.0, 30.0)], truth, 5.0).true_positives == 0


def test_count_error_and_percentile():
    assert count_error(95, 100) == pytest.approx(0.05)
    assert count_error(0, 0) == 0.0
    assert count_error(3, 0) is None
    assert percentile([1, 2, 3, 4], 50) == pytest.approx(2.5)
    assert percentile([], 95) is None


# ------------------------------------------------------------------------------------------------ dataset

LINE = [[0.5, 0.0], [0.5, 1.0]]
CROSSINGS = (1.0, 6.0, 11.0)  # a plate starts at x 0.3 at each time and crosses the line 1.25 s later


def write_dataset(root: Path, *, labels: dict | None = None, clip: dict | None = None) -> Path:
    labels = labels if labels is not None else {
        "eventsComplete": True,
        "frames": [{"t": 2.0, "boxes": [{"cls": "plate", "box": [0.43, 0.45, 0.53, 0.55], "track": "p1"}]}],
        # Left to right across a top-to-bottom line is "out" in the agent's image coordinates (inSide right).
        "events": [{"zone": "pass", "type": "line_crossed", "t": s + 1.25, "class": "plate", "direction": "out"}
                   for s in CROSSINGS],
        "counts": [{"zone": "pass", "type": "line_crossed", "class": "plate", "direction": "out", "count": 3}],
    }
    (root / "c1.json").write_text(json.dumps(labels), encoding="utf-8")
    entry = {"id": "c1", "video": "c1.mp4", "labels": "c1.json", "profile": "kitchen_pass", "shift": "day-1",
             "conditions": {"period": "peak", "lighting": "day", "angle": "pass-front"},
             "zones": [{"id": "pass", "zoneType": "kitchen_pass", "polygon": LINE}]}
    entry.update(clip or {})
    (root / "dataset.yaml").write_text(yaml.safe_dump({"name": "test", "clips": [entry]}), encoding="utf-8")
    return root


def test_dataset_loads_and_reports_missing_conditions(tmp_path):
    dataset = load_dataset(write_dataset(tmp_path))
    clip = dataset.clips[0]
    assert (clip.profile, clip.shift, clip.events_complete) == ("kitchen_pass", "day-1", True)
    assert len(clip.events) == 3 and clip.counts[0].count == 3
    assert dataset.classes() == {"plate"}
    missing = coverage(dataset)["kitchen_pass"]["missing"]
    assert missing == ["no quiet footage", "no night footage", "1 camera angle(s), at least 2 needed"]


def test_dataset_errors_name_the_clip_and_field(tmp_path):
    bad_box = {"frames": [{"t": 1, "boxes": [{"cls": "plate", "box": [0.5, 0.5, 0.4, 0.6]}]}]}
    with pytest.raises(DatasetError, match="clip c1 frames"):
        load_dataset(write_dataset(tmp_path, labels=bad_box))
    unknown_zone = {"events": [{"zone": "nowhere", "type": "line_crossed", "t": 1}]}
    with pytest.raises(DatasetError, match="not a zone of the clip"):
        load_dataset(write_dataset(tmp_path, labels=unknown_zone))
    with pytest.raises(DatasetError, match="profile must be one of"):
        load_dataset(write_dataset(tmp_path, clip={"profile": "parking"}))


def test_false_alerts_are_counted_only_where_the_labeller_says_every_event_is_labelled(tmp_path):
    assert load_dataset(write_dataset(tmp_path, labels={"counts": []})).clips[0].events_complete is False


# ------------------------------------------------------------------------------------------------ licences


def test_the_repository_register_loads_and_nothing_is_cleared_yet():
    register = load_register(DEFAULT_REGISTER)
    assert {"yolox-code", "rtdetr-code", "bytetrack-code", "ultralytics-code", "coco-2017-images",
            "coco-2017-annotations"} <= set(register)
    assert not [e for e in register.values() if e.status == "cleared"]
    assert register["ultralytics-code"].status == "excluded"


def test_eligibility(tmp_path):
    register = load_register(DEFAULT_REGISTER)
    yolox = eligibility(["yolox-code", "yolox-coco-weights", "onnxruntime"], register)
    assert yolox["status"] == "needs review"
    assert "yolox-code: review" in yolox["reasons"]
    assert eligibility(["ultralytics-code", "yolox-coco-weights"], register)["status"] == "excluded"
    assert "not in the register: mystery" in eligibility(["mystery"], register)["reasons"]
    cleared = tmp_path / "register.yaml"
    entry = {"status": "cleared", "checkedBy": "Legal", "checkedOn": "2026-12-01", "licence": "MIT"}
    cleared.write_text(yaml.safe_dump({"entries": [{"key": "c", "component": "code", **entry},
                                                   {"key": "w", "component": "weights", **entry, **CLEARED_WEIGHTS}]}))
    assert eligibility(["c", "w"], load_register(cleared), "a" * 64)["status"] == "cleared"
    assert eligibility(["c"], load_register(cleared))["reasons"] == ["no register entry for its weights"]


CLEARED_WEIGHTS = {
    "checkpoints": [{"file": "w.onnx", "version": "1", "downloadUrl": "https://example.org/w.onnx", "sha256": "a" * 64,
                     "trainedOn": ["own-footage"]}],
    "assessment": {"codeLicence": "Apache-2.0", "weightDistribution": "Apache-2.0", "trainingImages": "own, consented",
                   "annotations": "own", "closedRedistribution": "allowed"},
}


def test_weights_are_cleared_per_file_and_only_with_every_assessment_answer(tmp_path):
    path = tmp_path / "register.yaml"
    base = {"key": "w", "component": "weights", "status": "cleared", "checkedBy": "Legal", "checkedOn": "2026-12-01"}
    entries = [{**base, **CLEARED_WEIGHTS}, {**base, "key": "c", "component": "code"}]
    path.write_text(yaml.safe_dump({"entries": entries}))
    register = load_register(path)
    assert eligibility(["c", "w"], register, "a" * 64)["status"] == "cleared"
    other = eligibility(["c", "w"], register, "b" * 64)
    assert other["status"] == "needs review" and "is not a listed checkpoint" in other["reasons"][0]
    assert "gives no sha256" in eligibility(["c", "w"], register)["reasons"][0]
    path.write_text(yaml.safe_dump({"entries": [{**base, "checkpoints": CLEARED_WEIGHTS["checkpoints"],
                                                 "assessment": {"codeLicence": "Apache-2.0"}}]}))
    with pytest.raises(RegisterError, match="missing weightDistribution, trainingImages"):
        load_register(path)
    path.write_text(yaml.safe_dump({"entries": [{**base, "assessment": CLEARED_WEIGHTS["assessment"]}]}))
    with pytest.raises(RegisterError, match="list the checkpoints"):
        load_register(path)


def test_a_cleared_entry_needs_who_and_when(tmp_path):
    path = tmp_path / "register.yaml"
    path.write_text(yaml.safe_dump({"entries": [{"key": "x", "component": "code", "status": "cleared"}]}))
    with pytest.raises(RegisterError, match="checkedBy"):
        load_register(path)


# ------------------------------------------------------------------------------------------------ thresholds


def test_thresholds_default_to_the_proposed_values_and_a_file_overrides_them(tmp_path):
    proposed = load_thresholds(None)
    assert proposed["profiles"]["cash_drawer"] == {"minPrecision": 0.90, "minRecall": 0.85,
                                                   "minFindingPrecision": 0.90, "minFindingRecall": 0.85}
    assert proposed["hardware"]["minCameras"] == 4
    assert proposed["maxFalseAlertsPerShiftPerZone"] == 2
    path = tmp_path / "t.yaml"
    path.write_text(yaml.safe_dump({"profiles": {"bar_counter": {"maxCountError": 0.2}}}))
    assert load_thresholds(path)["profiles"]["bar_counter"]["maxCountError"] == 0.2
    assert PROPOSED["bar_counter"]["maxCountError"] == 0.10


def test_verdict_of_a_drawer_profile():
    thresholds = load_thresholds(None)
    correlated = {"transactionCorrelation": {"clips": 2, "precision": 0.95, "recall": 0.9}}
    base = {"clipErrors": {}, "counts": {}, "falseAlerts": {"max": 1}, "business": correlated}
    good = verdict("cash_drawer", {**base, "events": {"precision": 0.95, "recall": 0.9}}, thresholds)
    assert good["status"] == "pass"
    # Good drawer detection with poor transaction correlation does not pass.
    weak = {"transactionCorrelation": {"clips": 2, "precision": 0.5, "recall": 0.9}}
    assert verdict("cash_drawer", {**base, "business": weak, "events": {"precision": 0.99, "recall": 0.99}},
                   thresholds)["status"] == "fail"
    # Without the till's records, correlation is not measured, so the profile cannot pass.
    unlabelled = verdict("cash_drawer", {**base, "business": {"transactionCorrelation": {"clips": 0}},
                                         "events": {"precision": 0.99, "recall": 0.99}}, thresholds)
    assert unlabelled["status"] == "not measured"
    assert any("pos" in c.get("detail", "") for c in unlabelled["checks"])
    low = verdict("cash_drawer", {**base, "events": {"precision": 0.95, "recall": 0.8}}, thresholds)
    assert low["status"] == "fail"
    none = verdict("cash_drawer", {**base, "events": {"precision": None, "recall": None},
                                   "falseAlerts": {"max": None}}, thresholds)
    assert none["status"] == "not measured"
    retail = verdict("retail_checkout", {**base, "events": {}}, thresholds)
    assert retail["status"] == "not measured"
    assert "LP5" in retail["checks"][0]["detail"]


# ------------------------------------------------------------------------------------------------ a whole run


class FakeSource:
    def frames(self, clip, fps):
        for i in range(int(15 * fps)):
            yield i / fps, None


class FakeRunner:
    """A plate moving left to right across the pass line at each start time; one false plate at 13 s if asked."""

    classes = frozenset({"plate", "person"})
    load_seconds = 0.5

    def __init__(self, ghost: bool = False) -> None:
        self.ghost = ghost

    def for_clip(self, clip):
        def detect(t, frame):
            detections = []
            for start in CROSSINGS + ((13.0,) if self.ghost else ()):
                if start <= t <= start + 2.5:
                    x = 0.3 + 0.16 * (t - start)
                    detections.append(Detection("plate", 0.9, (x - 0.05, 0.45, x + 0.05, 0.55)))
            return detections, 12.0

        return detect


def run(tmp_path, ghost=False):
    dataset = load_dataset(write_dataset(tmp_path))
    candidate = Candidate(name="fake", licences=("yolox-code", "yolox-coco-weights"))
    return dataset, evaluate(candidate, dataset, FakeSource(), fps=5.0, tolerance=2.0,
                             thresholds=load_thresholds(None), runner=FakeRunner(ghost))


def test_a_run_scores_the_agents_own_counting_profile(tmp_path):
    _, result = run(tmp_path)
    kitchen = result["profiles"]["kitchen_pass"]
    assert kitchen["clipErrors"] == {}
    assert kitchen["events"]["truePositives"] == 3
    assert kitchen["events"]["precision"] == pytest.approx(1.0)
    assert kitchen["counts"]["perShift"][0]["predicted"] == 3
    assert kitchen["counts"]["maxError"] == 0.0
    assert kitchen["falseAlerts"]["max"] == 0
    assert kitchen["detection"]["plate"]["recall"] == pytest.approx(1.0)
    assert kitchen["verdict"]["status"] == "pass"
    assert result["speed"]["latencyMsP95"] == pytest.approx(12.0)
    assert result["speed"]["camerasAtAnalysisRate"] == pytest.approx(16.67, abs=0.01)


def test_an_extra_plate_is_a_false_alert_and_a_count_error(tmp_path):
    _, result = run(tmp_path, ghost=True)
    kitchen = result["profiles"]["kitchen_pass"]
    assert kitchen["counts"]["maxError"] == pytest.approx(1 / 3)
    assert kitchen["falseAlerts"]["max"] == 1
    assert kitchen["verdict"]["status"] == "fail"


def test_the_report_is_never_eligible_on_partial_footage_or_an_unchecked_licence(tmp_path):
    dataset, result = run(tmp_path)
    register = load_register(DEFAULT_REGISTER)
    licence = {"fake": eligibility(["yolox-code", "yolox-coco-weights"], register)}
    report = reports.build(dataset, coverage(dataset), load_thresholds(None),
                           {"fps": 5.0, "maxWidth": 960, "toleranceSeconds": 2.0, "iou": 0.5}, [result], licence,
                           {"platform": "test"})
    overall = report["candidates"][0]["overall"]
    assert overall["status"] == "not eligible"
    assert any("not representative" in b for b in overall["blockers"])
    assert any(b.startswith("licence needs review") for b in overall["blockers"])
    json_path, md_path = reports.write(report, tmp_path / "out")
    assert json.loads(json_path.read_text())["candidates"][0]["candidate"] == "fake"
    text = md_path.read_text()
    assert "**not eligible**" in text and "kitchen_pass: pass" in text


def test_cli_check_and_licences(tmp_path, capsys):
    write_dataset(tmp_path)
    assert main(["check", "--dataset", str(tmp_path)]) == 0
    assert json.loads(capsys.readouterr().out)["coverage"]["kitchen_pass"]["clips"] == 1
    candidates = tmp_path / "candidates.yaml"
    candidates.write_text(yaml.safe_dump({"candidates": [
        {"name": "r", "predictions": "p.jsonl", "licences": ["rtdetr-code", "rtdetr-coco-weights"]}]}))
    assert main(["licences", "--candidates", str(candidates)]) == 0
    assert json.loads(capsys.readouterr().out)["r"]["status"] == "needs review"
    assert main(["check", "--dataset", str(tmp_path / "missing")]) == 2


def test_the_example_candidates_file_is_valid():
    from lp_edge.evaluation.runner import load_candidates  # noqa: PLC0415

    example = Path(__file__).resolve().parents[1] / "evaluation" / "candidates.example.yaml"
    names = [c.name for c in load_candidates(example)]
    assert names == ["yolox-s", "yolox-nano", "rt-detr-r18"]
    assert len(load_candidates(example)[0].classes) == 80


# ------------------------------------------------------------------------------------------------ business accuracy

from lp_edge.evaluation.bench import BenchSettings, bench_verdict, run_bench  # noqa: E402
from lp_edge.evaluation.business import drawer_findings, explained, unexplained_openings  # noqa: E402
from lp_edge.evaluation.dataset import PosRecord  # noqa: E402
from lp_edge.evaluation.metrics import Event  # noqa: E402

DRAWER_ZONE = [[0.4, 0.6], [0.6, 0.6], [0.6, 0.8], [0.4, 0.8]]


def drawer_labels(**extra):
    return {"eventsComplete": True,
            "events": [{"zone": "drawer", "type": "drawer_open", "t": t} for t in (10.0, 200.0)],
            "pos": [{"t": 30.0, "type": "sale.completed"}, {"t": 500.0, "type": "sale.line_voided"}], **extra}


def drawer_dataset(root, **extra):
    return load_dataset(write_dataset(root, labels=drawer_labels(**extra), clip={
        "profile": "cash_drawer", "zones": [{"id": "drawer", "zoneType": "cash_drawer", "polygon": DRAWER_ZONE}]}))


def test_v_c1_window_and_explaining_records_match_the_backend_rule():
    pos = [PosRecord(100.0, "sale.completed"), PosRecord(300.0, "sale.line_voided")]
    assert explained(40.0, pos)            # sale 60 s after: inside the 120 s after-window
    assert explained(160.0, pos)           # sale 60 s before: inside the 60 s before-window
    assert not explained(161.0, pos)       # 61 s after the sale
    assert not explained(250.0, pos)       # a void does not explain a drawer opening
    opens = [Event("d", "drawer_open", 40.0), Event("d", "drawer_open", 250.0)]
    assert [e.t for e in unexplained_openings(opens, pos)] == [250.0]


def test_dataset_reads_till_records_and_confirmed_findings_and_coverage_asks_for_them(tmp_path):
    clip = drawer_dataset(tmp_path, findings=[{"rule": "V-C1", "zone": "drawer", "t": 200.0}]).clips[0]
    assert [r.type for r in clip.pos] == ["sale.completed", "sale.line_voided"]
    assert clip.findings[0].type == "V-C1"
    (tmp_path / "b").mkdir()
    bare = load_dataset(write_dataset(tmp_path / "b", labels={"events": []}, clip={
        "profile": "cash_drawer", "zones": [{"id": "drawer", "zoneType": "cash_drawer", "polygon": DRAWER_ZONE}]}))
    assert any("till records" in m for m in coverage(bare)["cash_drawer"]["missing"])
    with pytest.raises(DatasetError, match="pos\\[0\\]: type must be one of"):
        drawer_dataset(tmp_path, pos=[{"t": 1, "type": "sale.done"}])
    with pytest.raises(DatasetError, match="findings\\[0\\]: rule must be one of"):
        drawer_dataset(tmp_path, findings=[{"rule": "V-X", "zone": "drawer", "t": 1}])


def test_findings_are_scored_apart_from_drawer_events(tmp_path):
    clip = drawer_dataset(tmp_path, findings=[{"rule": "V-C1", "zone": "drawer", "t": 200.0}]).clips[0]
    # Every labelled opening is seen, plus one ghost opening with no sale near it: events look fine, findings do not.
    predicted = [Event("drawer", "drawer_open", t) for t in (10.5, 200.5, 400.0)]
    levels = drawer_findings([(clip, predicted)], tolerance=2.0)
    corr = levels["transactionCorrelation"]
    assert (corr["clips"], corr["labelled"], corr["predicted"], corr["truePositives"]) == (1, 1, 2, 1)
    assert corr["precision"] == pytest.approx(0.5) and corr["recall"] == pytest.approx(1.0)
    assert corr["verdictAgreement"] == pytest.approx(1.0)
    assert levels["confirmedFindings"]["truePositives"] == 1
    assert levels["confirmedFindings"]["precision"] == pytest.approx(0.5)


# ------------------------------------------------------------------------------------------------ reproducibility


def test_candidate_hashes_are_checked_and_recorded(tmp_path):
    from lp_edge.evaluation import provenance  # noqa: PLC0415
    from lp_edge.evaluation.runner import CandidateError, ModelRunner, load_candidates  # noqa: PLC0415

    model = tmp_path / "m.onnx"
    model.write_bytes(b"weights")
    digest = provenance.sha256_file(model)
    assert len(digest) == 64
    file = tmp_path / "c.yaml"
    file.write_text(yaml.safe_dump({"candidates": [{"name": "m", "detector": "yolox", "model": "m.onnx",
                                                    "sha256": "0" * 64, "sourceUrl": "https://example.org/m"}]}))
    candidate = load_candidates(file)[0]
    with pytest.raises(CandidateError, match="expects 0000"):
        ModelRunner(candidate)
    record = provenance.candidate(candidate)
    assert record["sha256"] == digest and record["sha256Matches"] is False
    assert record["tracker"]["tracker"] == "ByteTracker" and record["tracker"]["min_hits"] == 2
    file.write_text(yaml.safe_dump({"candidates": [{"name": "m", "detector": "yolox", "model": "m.onnx",
                                                    "sha256": "abc"}]}))
    with pytest.raises(CandidateError, match="64 hexadecimal"):
        load_candidates(file)


def test_the_fingerprint_changes_with_the_labels(tmp_path):
    from lp_edge.evaluation import provenance  # noqa: PLC0415

    agent = provenance.agent()
    assert len(agent["profilesSha256"]) == 64
    thresholds = load_thresholds(None)
    cand = {"sha256": "a" * 64, "tracker": provenance.tracker_settings()}
    first = provenance.fingerprint(cand, agent, provenance.footage(load_dataset(write_dataset(tmp_path))), {},
                                   thresholds)
    changed = provenance.fingerprint(cand, agent, provenance.footage(load_dataset(write_dataset(
        tmp_path, labels={"counts": []}))), {}, thresholds)
    assert first != changed


# ------------------------------------------------------------------------------------------------ hardware run


class SlowRunner(FakeRunner):
    def __init__(self, seconds: float) -> None:
        super().__init__()
        self.seconds = seconds

    def for_clip(self, clip):
        inner = super().for_clip(clip)

        def detect(t, frame):
            import time  # noqa: PLC0415

            time.sleep(self.seconds)
            return inner(t, frame)

        return detect


def test_the_hardware_run_keeps_every_camera_at_rate_and_times_events(tmp_path):
    dataset = load_dataset(write_dataset(tmp_path))
    bench = run_bench(FakeRunner(), dataset.clips, lambda clip: FakeSource().frames(clip, 10.0),
                      BenchSettings(cameras=2, seconds=3.0, fps=10.0, window_seconds=1.0, sample_seconds=0.2))
    assert bench["measured"] and len(bench["perCamera"]) == 2
    for cam in bench["perCamera"]:
        assert cam["error"] is None and cam["frames"] >= 25 and cam["dropped"] == 0
    assert bench["minWindowFps"] >= 9.0
    assert bench["events"] >= 2 and bench["eventLatencySecondsP95"] < 1.0
    assert bench["resources"]["samples"] >= 5
    limits = {**load_thresholds(None)["hardware"], "minCameras": 2.0, "minSustainedSeconds": 2.0}
    assert bench_verdict(bench, limits)["status"] == "pass"
    # The proposed limits want 4 cameras for 30 minutes.
    failed = bench_verdict(bench, load_thresholds(None)["hardware"])
    assert failed["status"] == "fail"
    assert {c["check"] for c in failed["checks"] if c["status"] == "fail"} == {"cameras run at once", "run length (s)"}


def test_a_detector_too_slow_for_the_rate_drops_frames_and_fails(tmp_path):
    dataset = load_dataset(write_dataset(tmp_path))
    bench = run_bench(SlowRunner(0.25), dataset.clips, lambda clip: FakeSource().frames(clip, 10.0),
                      BenchSettings(cameras=1, seconds=2.0, fps=10.0, window_seconds=1.0, sample_seconds=0.5))
    cam = bench["perCamera"][0]
    assert cam["dropped"] > 0 and cam["achievedFps"] < 6
    limits = {**load_thresholds(None)["hardware"], "minCameras": 1.0, "minSustainedSeconds": 1.0}
    verdict_ = bench_verdict(bench, limits)
    assert verdict_["status"] == "fail"
    assert any(c["check"].startswith("slowest camera") and c["status"] == "fail" for c in verdict_["checks"])
    assert bench_verdict({"measured": False, "reason": "x"}, limits)["status"] == "not measured"


def test_the_report_blocks_on_hardware_pinning_and_correlation(tmp_path):
    dataset, result = run(tmp_path)
    result["provenance"] = {"kind": "model", "expectedSha256": None, "sourceUrl": None, "file": "m.onnx",
                            "bytes": 1, "sha256": "b" * 64, "tracker": {"tracker": "ByteTracker"}}
    register = load_register(DEFAULT_REGISTER)
    report = reports.build(dataset, coverage(dataset), load_thresholds(None),
                           {"fps": 5.0, "maxWidth": 960, "toleranceSeconds": 2.0, "iou": 0.5}, [result],
                           {"fake": eligibility(["yolox-code", "yolox-coco-weights"], register)}, {"platform": "t"},
                           {"agent": {"version": "0", "commit": None, "profilesSha256": "c" * 64},
                            "clips": [], "command": ["lp-edge-eval", "run"]})
    candidate = report["candidates"][0]
    blockers = candidate["overall"]["blockers"]
    assert any(b.startswith("hardware not measured") for b in blockers)
    assert "weights not pinned: give sha256 and sourceUrl in the candidates file" in blockers
    assert len(candidate["fingerprint"]) == 64
    _, md_path = reports.write(report, tmp_path / "out")
    text = md_path.read_text()
    assert "### Hardware run: not measured" in text and "### Provenance" in text


# ------------------------------------------------------------------------------------------------ retail checkout (LP5)

SCAN_ZONE = {"id": "scan", "zoneType": "scan_area", "polygon": [[0.45, 0.4], [0.55, 0.4], [0.55, 0.6], [0.45, 0.6]]}
#: (start, y): items move left to right from x 0.2 at 0.16 a second; at y 0.5 over the scanner, at y 0.3 past it
PASSES = ((0.5, 0.5), (4.5, 0.5), (8.5, 0.5), (10.5, 0.3))
RETAIL = {"profile": "retail_checkout", "zones": [SCAN_ZONE]}


class ItemRunner:
    classes = frozenset({"item", "person"})
    load_seconds = None

    def for_clip(self, clip):
        def detect(t, frame):
            detections = []
            for start, y in PASSES:
                if start <= t <= start + 3.75:
                    x = 0.2 + 0.16 * (t - start)
                    detections.append(Detection("item", 0.9, (x - 0.05, y - 0.05, x + 0.05, y + 0.05)))
            return detections, 10.0

        return detect


def retail_run(tmp_path, batches):
    dataset = load_dataset(write_dataset(tmp_path, labels={"batches": batches}, clip=RETAIL))
    candidate = Candidate(name="fake", licences=("yolox-code", "yolox-coco-weights"))
    return dataset, evaluate(candidate, dataset, FakeSource(), fps=5.0, tolerance=2.0,
                             thresholds=load_thresholds(None), runner=ItemRunner())


def test_scan_batches_and_activities_are_validated(tmp_path):
    batch = {"zone": "scan", "start": 0, "end": 8, "items": 2}
    with pytest.raises(DatasetError, match="not a scan_area zone of the clip"):
        load_dataset(write_dataset(tmp_path, labels={"batches": [{**batch, "zone": "pass"}]}))
    for bad, message in (({"end": 0}, "end must be after start"), ({"items": -1}, "items: a whole number"),
                         ({"bypassed": 1.5}, "bypassed: a whole number")):
        with pytest.raises(DatasetError, match=message):
            load_dataset(write_dataset(tmp_path, labels={"batches": [{**batch, **bad}]}, clip=RETAIL))
    with pytest.raises(DatasetError, match="ids must be unique"):
        load_dataset(write_dataset(tmp_path, labels={"batches": [{**batch, "id": "r"}, {**batch, "id": "r"}]},
                                   clip=RETAIL))
    activity = {"zone": "pass", "activity": "object_carried_out", "start": 1, "end": 2}
    with pytest.raises(DatasetError, match=r"clip c1 activities\[0\]: activity must be"):
        load_dataset(write_dataset(tmp_path, labels={"activities": [{**activity, "activity": "Carried out"}]}))
    with pytest.raises(DatasetError, match="not a zone of the clip"):
        load_dataset(write_dataset(tmp_path, labels={"activities": [{**activity, "zone": "nowhere"}]}))
    clip = load_dataset(write_dataset(tmp_path, labels={"activities": [activity], "batches": []})).clips[0]
    assert clip.activities == (ActivityLabel("pass", "object_carried_out", 1.0, 2.0),) and clip.batches == ()
    assert load_dataset(write_dataset(tmp_path)).clips[0].activities is None


def test_coverage_asks_retail_footage_for_scan_batches(tmp_path):
    unlabelled = coverage(load_dataset(write_dataset(tmp_path, labels={}, clip=RETAIL)))["retail_checkout"]
    assert unlabelled["labelledBatches"] == 0
    assert "no scan batch is labelled (batches), so the item count is not scored" in unlabelled["missing"]
    labelled = coverage(load_dataset(write_dataset(tmp_path, labels={"batches": [
        {"zone": "scan", "start": 0, "end": 8, "items": 2}]}, clip=RETAIL)))["retail_checkout"]
    assert labelled["labelledBatches"] == 1 and not any("scan batch" in m for m in labelled["missing"])
    assert "labelledBatches" not in coverage(load_dataset(write_dataset(tmp_path)))["kitchen_pass"]


def test_a_run_counts_each_batch_with_the_agents_scan_profile(tmp_path):
    dataset, result = retail_run(tmp_path, [
        {"id": "r1", "zone": "scan", "start": 0, "end": 8, "items": 2},
        {"id": "r2", "zone": "scan", "start": 8, "end": 15, "items": 1, "bypassed": 1}])
    retail = result["profiles"]["retail_checkout"]
    assert retail["clipErrors"] == {} and retail["zonesNotObservable"] == {}
    rows = [(b["batch"], b["true"], b["predicted"], b["error"], b["bypassedTrue"], b["bypassedPredicted"])
            for b in retail["batches"]["perBatch"]]
    assert rows == [("r1", 2, 2, 0.0, None, 0), ("r2", 1, 1, 0.0, 1, 1)]
    assert retail["batches"]["bypassed"] == {"labelledBatches": 1, "true": 1, "predicted": 1}
    assert retail["verdict"] == {"status": "pass", "checks": [
        {"check": "count error per batch", "status": "pass", "value": 0.0, "limit": 0.05}]}
    report = reports.build(dataset, coverage(dataset), load_thresholds(None),
                           {"fps": 5.0, "maxWidth": 960, "toleranceSeconds": 2.0, "iou": 0.5}, [result],
                           {"fake": eligibility(["yolox-code", "yolox-coco-weights"], load_register(DEFAULT_REGISTER))},
                           {"platform": "test"})
    text = reports.markdown(report)
    assert "| r2 | c1 | scan | 1 | 1 | 0.0 % | 1 / 1 |" in text
    assert "Activity research" not in text


def test_a_miscounted_batch_fails_the_retail_profile(tmp_path):
    _, result = retail_run(tmp_path, [{"zone": "scan", "start": 0, "end": 15, "items": 4}])
    retail = result["profiles"]["retail_checkout"]
    assert retail["batches"]["perBatch"][0]["batch"] == "c1#1"
    assert retail["batches"]["maxError"] == pytest.approx(0.25)  # 3 counted over the scanner, 4 true
    assert retail["verdict"]["status"] == "fail"


def test_verdict_of_the_retail_count():
    thresholds = load_thresholds(None)
    base = {"clipErrors": {}, "counts": {}, "falseAlerts": {"max": None}, "events": {}}

    def status(batches):
        return verdict("retail_checkout", {**base, "batches": batches}, thresholds)

    assert status({"batches": 3, "maxError": 0.04, "undefined": 0})["status"] == "pass"
    assert status({"batches": 3, "maxError": 0.06, "undefined": 0})["status"] == "fail"
    undefined = status({"batches": 1, "maxError": None, "undefined": 1})
    assert undefined["status"] == "not measured" and "true count of 0" in undefined["checks"][0]["detail"]
    none = status({"batches": 0, "maxError": None, "undefined": 0})
    assert none["status"] == "not measured" and "LP5" in none["checks"][0]["detail"]


# ------------------------------------------------------------------------------------------------ activities (LP7)


def activity(name, start, end, zone="z"):
    return Observation(ACTIVITY, zone, "evaluation", end, {"activity": name, "startedAt": iso(start),
                                                           "endedAt": iso(end), "recognizer": f"{name}@1",
                                                           "score": 0.6})


def test_activity_segments_are_matched_on_temporal_overlap():
    assert activity_scores.temporal_iou(0, 10, 5, 15) == pytest.approx(1 / 3)
    assert activity_scores.temporal_iou(0, 1, 2, 3) == 0.0
    labelled = SimpleNamespace(id="c1", activities=(
        ActivityLabel("z", "object_carried_out", 10.0, 14.0), ActivityLabel("z", "object_carried_out", 30.0, 32.0),
        ActivityLabel("z", "object_left_behind", 50.0, 120.0)))
    preds = [activity("object_carried_out", 11.0, 14.0),  # IoU 0.75
             activity("object_carried_out", 31.5, 40.0),  # IoU 0.05
             activity("object_left_behind", 60.0, 120.0),  # IoU 0.86
             activity("object_left_behind", 60.0, 120.0, zone="other")]
    unlabelled = SimpleNamespace(id="c2", activities=None)
    result = activity_scores.score([(labelled, preds), (unlabelled, [activity("object_carried_out", 1.0, 2.0)])])
    assert (result["research"], result["measured"], result["clips"], result["segmentsOnUnlabelledClips"]) == (
        True, True, 1, 1)
    carried = result["perActivity"]["object_carried_out"]
    assert (carried["labelled"], carried["predicted"], carried["truePositives"]) == (2, 2, 1)
    assert carried["precision"] == carried["recall"] == carried["f1"] == pytest.approx(0.5)
    left = result["perActivity"]["object_left_behind"]
    assert (left["precision"], left["recall"], left["f1"]) == (pytest.approx(0.5), 1.0, pytest.approx(2 / 3))
    assert result["overall"]["truePositives"] == 2
    assert result["recognizers"] == ["object_carried_out@1", "object_left_behind@1"]
    none = activity_scores.score([(unlabelled, [])])
    assert none["measured"] is False and none["reason"] == "no clip has its activities labelled"


def test_activity_research_is_reported_but_never_blocks_or_clears_a_candidate(tmp_path):
    dataset, result = run(tmp_path)
    assert result["activityResearch"]["measured"] is False
    args = (dataset, coverage(dataset), load_thresholds(None),
            {"fps": 5.0, "maxWidth": 960, "toleranceSeconds": 2.0, "iou": 0.5})
    licence = {"fake": eligibility(["yolox-code", "yolox-coco-weights"], load_register(DEFAULT_REGISTER))}
    plain = reports.build(*args, [result], licence, {"platform": "test"})
    poor = activity_scores.score([(SimpleNamespace(id="c1", activities=(ActivityLabel("pass", "item_swap", 1, 2),)),
                                   [activity("item_swap", 5.0, 6.0, zone="pass")])])
    assert poor["overall"]["f1"] == 0.0
    researched = reports.build(*args, [{**result, "activityResearch": poor}], licence, {"platform": "test"})
    assert researched["candidates"][0]["overall"] == plain["candidates"][0]["overall"]
    text = reports.markdown(researched)
    assert "### Activity research (LP7; research only" in text and "| item_swap | 1 | 1 | 0.0 % | 0.0 % |" in text
    assert "Activity research" not in reports.markdown(plain)

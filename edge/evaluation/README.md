# Detector evaluation

`lp-edge-eval` measures candidate detectors before any of them is offered to agents (ADR-S077, "Detector and tracker
selection"). It answers three questions per candidate, on the footage you give it:

1. **Accuracy**: does the whole pipeline the agent runs (detector → the agent's tracker → the agent's zone profiles)
   meet the acceptance thresholds of each profile, at every level from boxes to business findings?
2. **Hardware**: on the box it runs on, do all the intended cameras keep the analysis rate at once, for a sustained
   run, with low event latency; and what CPU, memory, GPU and temperature does that cost?
3. **Licence**: has every piece the candidate depends on (code, the exact pretrained weight file, training images and
   annotations, runtime) been cleared in the [licence register](licence-register.yaml)?

A candidate is reported **eligible** only when all three hold, the weight file is pinned (SHA-256 and source URL), *and*
the footage covers what the design asks for: peak and quiet periods, day and night lighting, at least two camera angles
per profile, for cash drawers the till's records, and for retail checkouts labelled scan batches. Activity research
(LP7) is reported beside it and never counts for or against a candidate. Otherwise the report lists every blocker. On 2026-11-18 nothing
has been measured: there is no labelled footage yet, and no register entry has been checked by a person.

## Run it

```bash
cd edge
pip install -e .                       # numpy, opencv-python-headless, onnxruntime, PyYAML
lp-edge-eval check --dataset /data/pilot-a                       # validate labels, show coverage
lp-edge-eval licences --candidates /models/candidates.yaml        # licence status only
lp-edge-eval run --dataset /data/pilot-a --candidates /models/candidates.yaml --out /reports/2026-12-01 \
  --cameras 4 --sustain-seconds 1800                              # then 4 cameras at once for 30 minutes
```

Run it on the edge box itself (or identical hardware): speed and memory are measured where it runs. Options:
`--fps` and `--max-width` (the agent's `LP_EDGE_ANALYSIS_FPS`, default 5, and `LP_EDGE_ANALYSIS_MAX_WIDTH`, default
960), `--tolerance` (seconds between a predicted and a labelled event, default 2), `--iou` (box overlap, default 0.5),
`--thresholds` (a YAML file replacing the proposed thresholds), `--only <candidate>`, `--register <file>`,
`--cameras` and `--sustain-seconds` (the hardware run; without `--cameras` there is none and the candidate is not
eligible), `--window-seconds` (frame-rate window of the hardware run, default 60), `--no-video-hash`.

The report is `report.json` (every figure) and `report.md`.

## Reproducibility

Every report records what it ran on: the weight (or predictions) file's SHA-256 and size, the expected SHA-256 and
source URL from the candidates file, the tracker's settings, the agent version and commit, a SHA-256 of the
zone-profile and tracker code, every clip's video and label SHA-256 and zone definitions, the settings, thresholds,
machine and runtime versions, and the command line. Each candidate gets a **fingerprint** over everything that decides
its scores: two reports with the same fingerprint measured the same weights on the same footage with the same pipeline
and settings. A model file whose SHA-256 differs from the candidates file's `sha256` is refused.

## Hardware run

With `--cameras N`, after the accuracy run, each model candidate runs N cameras at once the way the agent does (one
processing thread per camera, one shared detector), each paced in real time at the analysis rate and looping over the
dataset's clips; a camera that falls behind drops frames, as a live stream would. Recorded: frames per second per
camera per window and frames dropped; frame latency; end-to-end event latency (the moment the event happens in the
video to the zone profile emitting it, including the profile's confirmation time, excluding the upload); process and
system CPU, resident memory, CPU temperature and, on NVIDIA GPUs, utilisation, memory and temperature (`nvidia-smi`),
every second, with first and last values to show throttling. What the machine cannot report is listed (Jetson
`tegrastats` is not read). Run it on the edge box itself, with the number of cameras the site will have.

Proposed limits (the design document sets none; `hardware:` in `--thresholds` replaces them): at least 4 cameras, every
camera at ≥ 95 % of the analysis rate in every window, event latency p95 ≤ 2 s, a run of at least 30 minutes. A
predictions file cannot be benchmarked.

## Candidates

See [`candidates.example.yaml`](candidates.example.yaml). A candidate is either

- a model the agent can load: `detector` (`yolox`, `yolox-decoded`, or a kind installed through the
  `lp_edge.detectors` entry point), `model` (ONNX file), `inputSize`, `classes` in output order, `providers`, and
  `sha256` and `sourceUrl` of the exact file downloaded; or
- `predictions`: detections made elsewhere, one JSON object per line:

  ```json
  {"clip": "c1", "t": 12.4, "detections": [{"cls": "person", "score": 0.91, "box": [0.1, 0.2, 0.3, 0.6]}], "latencyMs": 38.5}
  ```

  `t` is seconds from the clip start at the analysis rate; boxes are normalised `[x1, y1, x2, y2]`. Accuracy is
  measured the same way; speed only if `latencyMs` is given (and then for the hardware that made the file).

`licences` lists the register keys of everything the shipped candidate depends on.

## Licence register

Each entry is `review`, `excluded` or `cleared`; a cleared entry names who checked it and when. Weights are cleared
per file: a weights entry lists its `checkpoints` (file, version, downloadUrl, sha256, trainedOn) and a legal
`assessment` answering separately `codeLicence`, `weightDistribution`, `trainingImages`, `annotations` and
`closedRedistribution`; the loader refuses a cleared weights entry without both, and a candidate's licence is cleared
only if its weight file's SHA-256 is a listed checkpoint. COCO images (Flickr owners' terms) and COCO annotations
(CC BY 4.0) are separate entries: the annotation licence says nothing about the images, and the repository licence of a
model says nothing about weights trained on them. Every COCO-dependent entry stays `review` until legal has answered.

## Dataset format

```
pilot-a/
  dataset.yaml
  clips/till1-am.mp4
  labels/till1-am.json
```

`dataset.yaml`:

```yaml
name: pilot-a
description: Branch 3, December 2026, two tills and the kitchen pass
clips:
  - id: till1-am
    video: clips/till1-am.mp4
    labels: labels/till1-am.json
    profile: cash_drawer        # cash_drawer | bar_counter | kitchen_pass | cafe_counter | retail_checkout | other
    shift: 2026-12-01-am        # count errors and false alerts are per shift
    batch: null                 # retail_checkout: transaction batch for `counts`, used instead of the shift
    conditions: {period: peak, lighting: day, angle: till1-overhead}
    zones:                      # exactly as the backend sends them to the agent
      - {id: drawer, zoneType: cash_drawer, polygon: [[0.40, 0.60], [0.60, 0.60], [0.60, 0.80], [0.40, 0.80]]}
```

Labels (`labels/*.json`), times in seconds from the start of the clip, boxes normalised:

```json
{
  "eventsComplete": true,
  "frames": [
    {"t": 12.0, "boxes": [{"cls": "person", "box": [0.1, 0.2, 0.3, 0.9], "track": "p1"}]}
  ],
  "events": [
    {"zone": "drawer", "type": "drawer_open", "t": 15.2},
    {"zone": "pass", "type": "line_crossed", "t": 40.1, "class": "plate", "direction": "out"},
    {"zone": "bar", "type": "handling", "t": 61.0, "class": "bottle", "action": "pour"},
    {"zone": "till", "type": "presence", "t": 80.0, "end": 140.0}
  ],
  "counts": [
    {"zone": "pass", "type": "line_crossed", "class": "plate", "direction": "out", "count": 37}
  ],
  "pos": [
    {"t": 14.0, "type": "sale.completed"}
  ],
  "findings": [
    {"zone": "drawer", "rule": "V-C1", "t": 300.5}
  ],
  "batches": [
    {"id": "receipt-0412", "zone": "scan", "start": 402.0, "end": 461.5, "items": 14, "bypassed": 1}
  ],
  "activities": [
    {"zone": "store", "activity": "object_carried_out", "start": 512.0, "end": 518.5}
  ]
}
```

- `frames`: sampled frames with every object of the labelled classes boxed; `track` (optional) gives identity for
  tracking scores. Classes the labeller did not box are not scored.
- `events`: a predicted event matches a labelled one of the same zone and type, with the same `class`, `direction`
  or `action` where the label states one, within the tolerance (`presence` spans: temporal overlap of at least 30 %).
- `counts`: the true number of events over the whole clip; summed per shift for the count error.
- `pos` (cash drawers): the till's records during the clip, in clip time, with the ledger's type names
  (`sale.completed`, `refund.issued`, `sale.line_voided`, `order.cancelled`, `cash.movement`, `cash_drawer.opened`,
  `cash_session.opened`, `cash_session.closed`). Needed to score transaction correlation; take them from the POS
  export for the clip's till and period.
- `findings` (optional): findings a person confirmed as real, for example a drawer opening that was a loss or a breach
  of procedure.
- `eventsComplete: true` states that every alert-raising event of the clip is labelled. Only such clips count false
  alerts (an unmatched prediction is a false alert); without it a missing label would look like a false alert.
- `batches` (retail checkouts, LP5): one entry per transaction at a `scan_area` zone of the clip, from `start` (before
  its first item reaches the scanner) to `end` (after its last item is bagged), with the true number of items passed
  over the scanner (`items`) and, optionally, of the items carried past it (`bypassed`); `id` defaults to
  `<clip>#<n>`. The agent's count of a batch is what rule V-R1 reads: `line_crossed` `out` less `in` of any class but
  `person` in the zone between `start` and `end`; the count error is |counted − true| ÷ true. Items carried past the
  scanner (`handling` `bypassed`, rule V-R2) are reported beside it and not checked yet.
- `activities` (optional, LP7 research): activities seen in a zone, `activity` named as the recognizers name them
  (lowercase letters, digits and `_`), from `start` to `end`. A clip that lists `activities`, even an empty list, is
  scored for activity recognition: every segment the agent reports there and does not match is a false one. For the
  agent to report segments, the clip's zone needs `params.activityResearch: true` (and `activityRecognizers` if not the
  built-in ones).

## Accuracy by level

Detecting a person or a drawer is not detecting a loss. For cash drawers the report scores four levels side by side,
and each must pass on its own:

| Level | Question | Scored on |
|---|---|---|
| 1. objects | was the person, the drawer boxed? | labelled frames: precision, recall, AP per class |
| 2. events | did the drawer open? | labelled events: precision, recall |
| 3. transaction correlation | did the pipeline reach the finding the backend would reach on the true events? | clips with `pos`: rule V-C1 (drawer opened with no sale, refund, cash movement or session record from 60 s before to 120 s after), as in the backend's rule catalogue |
| 4. confirmed findings | are its findings the real ones? | clips with `findings` (reported; no threshold yet) |

Level 3 has its own proposed limits (precision ≥ 90 %, recall ≥ 85 %), so strong detection cannot hide weak
correlation; without `pos` labels the drawer profile is "not measured" and the candidate not eligible.

## What is scored, per profile

| Profile | Proposed threshold (design document) | Measured as |
|---|---|---|
| `cash_drawer` | precision ≥ 90 %, recall ≥ 85 % | `drawer_open` events, and V-C1 findings (level 3, harness proposal) |
| `bar_counter` | count error ≤ 10 % per shift | `handling` counts |
| `kitchen_pass`, `cafe_counter` | count error ≤ 5 % per shift | `line_crossed` counts |
| `retail_checkout` | count error ≤ 5 % per transaction batch | items counted by the agent's `scan_area` profile per labelled `batches` entry; "not measured" without batches |
| all | ≤ 2 false alerts per shift per zone | unmatched alert-raising events in `eventsComplete` clips |

Also reported: box precision, recall and average precision per labelled class; identity switches and MOTA; p50 and
p95 detector latency, model load time, peak memory of the process; zones the agent could not observe with the
candidate (for example a model without a `plate` class on a kitchen pass); the labelled classes the model lacks.

The thresholds are proposals "to be confirmed in each phase brief before its pilot starts"; the report names the
thresholds it used.

## Activity research (LP7)

Research only. On clips whose labels list `activities`, the `vision.activity` segments the agent reported are matched
one to one with the labelled ones of the same zone and activity when their temporal IoU (overlap ÷ union of the two
spans) is at least 0.5, largest overlap first. The report gives, per activity and over all of them, the labelled and
predicted segments, precision, recall and F1, and lists the recognizers (`name@version`) that reported. Segments on
clips without activity labels are counted, not scored. The section has no threshold and is never part of a profile's
verdict or of a candidate's eligibility: no ERP rule reads an activity. It appears in `report.md` only when activities
were labelled or reported.

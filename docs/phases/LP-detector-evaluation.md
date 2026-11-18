# Detector evaluation — harness and licence register

Status: coded 2026-11-18 on `phase/LP0-exception-intelligence`, not committed; design approved in review on
2026-11-18, with three added requirements (reproducibility, business accuracy, measurable hardware) now built (§7). **Nothing has
been measured**: there is no labelled footage, no candidate model has been downloaded, and no licence has been checked
by a person.

Design: the "Detector and tracker selection", "Accuracy targets" and "Model lifecycle" sections of the
loss-prevention design document; ADR-S077 §9 (step 3 of the delivery plan: "candidates measured on accuracy, speed and
licence", gate: sample footage). User guide: [`edge/evaluation/README.md`](../../edge/evaluation/README.md).

## 1. What it is

A command-line tool in the edge package, `lp-edge-eval` (`edge/lp_edge/evaluation/`), plus a licence register
(`edge/evaluation/licence-register.yaml`). It does not run inside the agent and changes nothing in the backend.

| Command | Does |
|---|---|
| `check --dataset` | Validates a labelled dataset; shows per profile the clips, shifts, labelled events and frames, and what the design's coverage rule says is missing |
| `licences --candidates` | Licence status of each candidate from the register |
| `run --dataset --candidates --out [--cameras N --sustain-seconds S]` | Runs every candidate over every clip, then the sustained multi-camera run; writes `report.json` and `report.md` |

## 2. How a candidate is measured

- Each clip is read at the agent's analysis rate and width (defaults 5 frames/s, 960 px). Every frame goes through
  the candidate, the agent's tracker (`ByteTracker`) and **the agent's own zone profiles** (`build_profile`), so the
  scores are of the pipeline the agent would run.
- Observations become comparable events: drawer `open` → `drawer_open`; `vision.line_crossed` → `line_crossed`
  (class, direction); `vision.handling` → `handling` (class, action); `vision.dwell` → `presence` (a span).
- Acceptance per profile, from the design's proposed thresholds: cash drawer precision ≥ 90 % and recall ≥ 85 %; bar
  count error ≤ 10 % per shift; kitchen pass and cafe counter ≤ 5 % per shift; at most 2 false alerts per shift per
  zone. Retail checkout (≤ 5 % per transaction batch, LP5) is scored on clips whose labels list transaction
  `batches` of a `scan_area` zone (start, end, items): the agent's count is its `out` − `in` crossings of anything but
  people in the batch. Bypassed items are reported, not checked. Without labelled batches the profile is "not
  measured" and the candidate cannot be eligible. Activities (LP7) are scored apart on clips whose labels list
  `activities` (temporal IoU ≥ 0.5 per zone and activity) and never change the verdict.
- Also: box precision, recall and AP per labelled class; identity switches and MOTA; detector latency p50/p95, model
  load time, peak process memory, and how many cameras one process keeps up with at the analysis rate.
- A candidate is either a model the agent can load (`yolox`, `yolox-decoded` or an entry-point detector) or a
  predictions file made elsewhere. RT-DETR has no detector kind in the agent, so it is evaluated from predictions.

## 3. The verdict

"Eligible" needs all of: every profile in the dataset passes (cash drawers: including transaction correlation); the
footage covers peak and quiet, day and night and at least two camera angles per profile, and cash-drawer clips carry
the till's records; the model has every labelled class; the weight file is pinned by SHA-256 and source URL; the
sustained multi-camera run passed; and every licence register entry the candidate names is `cleared`, weights for that
exact file. A cleared entry must name who checked it and when; the loader
refuses one that does not. Anything less is "not eligible" with the list of blockers.

## 4. The register as drafted

| Entry | Licence (as drafted, to confirm at source) | Status |
|---|---|---|
| YOLOX code | Apache-2.0 | review |
| RT-DETR code | Apache-2.0 | review |
| ByteTrack code | MIT (the agent's tracker is an independent implementation) | review |
| Ultralytics YOLO | AGPL-3.0 | **excluded** (design decision) |
| YOLOX and RT-DETR pretrained weights | repository licence; trained on COCO (and Objects365 for some RT-DETR checkpoints) | review |
| COCO 2017 images and annotations (two entries) | images: Flickr owners' terms; annotations: CC BY 4.0 | review |
| Objects365 | to confirm at source | review |
| Pilot footage, own weights | customer consent and data processing agreement | review |
| ONNX Runtime, OpenCV (bundles LGPL FFmpeg), NumPy | MIT, Apache-2.0, BSD-3-Clause | review |

The open legal question is the pretrained weights: whether weights trained on COCO images may ship in a closed
product. The annotation licence (CC BY 4.0) does not cover the images, and a model repository's code licence does not
establish redistribution rights for its checkpoints. A weights entry is cleared per file: it lists each checkpoint
(file, version, download URL, SHA-256, training data) and a legal assessment answering separately the code licence,
weight distribution, training-image rights, annotation rights and closed redistribution; the loader refuses a cleared
weights entry without both. Until legal has answered, no COCO-pretrained candidate can be eligible; weights trained only
on footage with documented commercial rights (`own-weights`) are the alternative.

## 5. Tests

`edge/tests/test_evaluation.py`, 29 tests: IoU, box matching, AP, identity switches, event matching (time tolerance,
attributes, spans), count error; dataset loading, validation errors and coverage; the register (repository file loads,
nothing cleared, Ultralytics excluded, cleared needs who and when); thresholds; verdicts; a whole run with a fake
detector through the agent's tracker and counting profile (pass, then an extra object → count error and false alert →
fail); the report never eligible on partial footage or an unchecked licence; the CLI; V-C1 windows; till records and
confirmed findings; findings scored apart from events; weight hashes checked and recorded; the fingerprint; the
hardware run at rate and with a detector too slow for it; per-file weight clearance and the assessment questions; the
report's hardware, pinning and correlation blockers. Run with a pytest stand-in
(this machine has no pytest, NumPy or OpenCV): all 128 edge tests pass, the OpenCV tests skipped. The real video path
(`VideoSource`, OpenCV) and model loading (`ModelRunner`, ONNX Runtime) are not exercised by any test here; CI's
`edge` job installs both.

## 6a. Review requirements added 2026-11-18

| Requirement | Built |
|---|---|
| Reproducibility | Per candidate: weight/predictions SHA-256 and size, expected SHA-256 and source URL (a model file with another hash is refused), tracker settings. Per run: agent version and commit, SHA-256 of the zone-profile and tracker code, every clip's video and label SHA-256 and zones, settings, thresholds, machine and runtime versions, command line. One fingerprint per candidate over all that decides its scores |
| Detection vs business accuracy | Four levels side by side for cash drawers: objects, events, transaction correlation (rule V-C1 mirrored from `LpCashierVisionEngine`, on clips whose labels carry the till's records), confirmed findings. Level 3 has its own limits (harness proposal: precision ≥ 90 %, recall ≥ 85 %); without till records the profile is not measured |
| Measurable hardware | `--cameras N --sustain-seconds S`: N cameras at once as the agent runs them, real-time paced, frames dropped when behind; fps per camera per window, frame and end-to-end event latency, process/system CPU, memory, CPU temperature, NVIDIA GPU use, memory and temperature, first vs last window. Proposed limits: ≥ 4 cameras, ≥ 95 % of the rate in every window, event latency p95 ≤ 2 s, ≥ 30 minutes |

Not covered: transaction correlation for V-C2/V-C3 and the counting profiles (kitchen pass against tickets, bar
against pours); Jetson `tegrastats`; upload latency. The hardware and level-3 limits are proposals for the owner to
confirm, like the design's.

## 6. What the owner needs to provide

1. Labelled footage from a pilot site, in the format of the user guide: per profile, peak and quiet, day and night,
   two angles, with `eventsComplete` clips for false alerts, and for cash drawers the till's records (POS export) per
   clip.
2. A legal view on the register, starting with the pretrained weights and COCO.
3. The edge box hardware, to run the evaluation on, and the number of cameras one box must carry.
4. Confirmation of the thresholds in each phase brief before its pilot (they are proposals).

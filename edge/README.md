# lp-edge — loss-prevention edge agent

`lp-edge` runs on a small Linux box on the customer's site, next to the cameras. It reads the camera streams, keeps
an encrypted ring buffer of recent video on its own disk, and turns what it sees in each zone into ledger observations
(`vision.*` events) for Inventory-ERP's loss-prevention ledger (ADR-S077, `docs/spec/loss-prevention.md`, LP1 and
LP2). Rules such as V-C1 (drawer open with no sale) run in the ERP; the agent only reports what happened.

## Privacy and security

- **No identification of people.** Detectors find classes such as `person`, `plate`, `cup`, `bottle` and
  `cash_drawer`. There is no face recognition, no biometric template and no re-identification across cameras. Track
  ids are per camera and are dropped within seconds.
- **Video stays on site.** The ring buffer (JPEG frames in one-minute segments, AES-256-GCM) never leaves the box. A
  clip is uploaded only when the ERP asks for it (`clip_upload` command for an evidence record); live view sends at
  most 4 low-resolution pictures a second while an authorised user watches.
- **Camera addresses and passwords never leave the box.** They live in `streams.yaml` (mode 0600) on the box. The ERP
  only knows a stream *alias* (e.g. `till-1`). The agent never logs, prints or sends an address; `lp-edge streams list`
  shows only the scheme.
- **Outbound only.** The agent calls the ERP over HTTPS (plain HTTP is refused unless `LP_EDGE_ALLOW_HTTP=true` for a
  lab). Its health endpoint listens on 127.0.0.1 only.
- **Signed software.** Detector models and agent releases are used only when their Ed25519 signature verifies against
  `LP_EDGE_TRUST_KEY` (`version|sha256|url` for a model, `version|image|digest` for a release).

## Install (Docker)

Requirements: a 64-bit Linux box with Docker Engine and the compose plugin, the cameras reachable over RTSP or
HTTP(S), outbound HTTPS to the ERP, and a disk for the buffer (see *Sizing*).

```bash
install -d -m 0700 /opt/lp-edge
cp edge/deploy/compose.yml /opt/lp-edge/
cp edge/deploy/.env.example /opt/lp-edge/.env && chmod 600 /opt/lp-edge/.env   # fill in LP_EDGE_IMAGE, LP_EDGE_SERVER, LP_EDGE_TRUST_KEY
install -d -m 0700 -o 10001 -g 10001 /var/lib/lp-edge                         # or the LP_EDGE_DATA you chose
docker compose -f /opt/lp-edge/compose.yml --env-file /opt/lp-edge/.env up -d
```

The container waits until the box is enrolled (and shows as unhealthy until then).

### Enrol

In the ERP, register the agent for its branch and copy the one-time code (`XXXX-XXXX-XXXX`; dashes and case do not
matter). On the box:

```bash
docker compose -f /opt/lp-edge/compose.yml exec lp-edge lp-edge enrol --code XXXX-XXXX-XXXX
```

`--server` defaults to `LP_EDGE_SERVER`. The agent's client credentials are saved to
`/var/lib/lp-edge/credentials.json` (0600); the waiting agent starts within ten seconds. Enrolling the box again as
another agent sets the previous agent's unsent observations aside instead of sending them under the new identity.

### Stream addresses

Each camera in the ERP has a stream alias. Give the box the address of each alias (typed at a hidden prompt):

```bash
docker compose -f /opt/lp-edge/compose.yml exec lp-edge lp-edge streams set till-1
docker compose -f /opt/lp-edge/compose.yml exec lp-edge lp-edge streams list
docker compose -f /opt/lp-edge/compose.yml exec lp-edge lp-edge streams test till-1
```

Addresses are `rtsp://`, `rtsps://`, `http://`, `https://` (MJPEG) or `file:` (trials). `set ALIAS -` reads the
address from standard input. The agent picks changes up within seconds.

### Status

```bash
docker compose -f /opt/lp-edge/compose.yml exec lp-edge lp-edge status   # health JSON; exit 0 when healthy
docker compose -f /opt/lp-edge/compose.yml exec lp-edge lp-edge clock --ntp pool.ntp.org
```

The ERP shows the same state from the agent's heartbeat (agent and camera health, queue length, clock drift, disk).

## What the agent reports

- One `vision.*` event per observation: `deviceId` is the zone, `payload.cameraId` the camera, `evidenceLevel` `zone`,
  `evidenceSource` `camera`, `source` `edge-agent`, `sourceEventId` `<agentId>:<eventId>` (stable on resend). Events
  wait in a local SQLite outbox and are sent in batches of up to 200; each one is accepted, a duplicate, or rejected,
  and a rejected one is kept aside, never resent blindly.
- Zone types: `cash_drawer` (`vision.state_changed`, reference-picture difference with hysteresis; calibrate it with the
  ERP's *calibrate* button while the drawer is closed), `checkout` (`vision.occupancy` every `occupancyEverySeconds`,
  `vision.dwell`), `bar_counter` (`vision.handling`: taken, returned), and `entrance`, `kitchen_pass`, `cafe_counter`,
  `dining`, `storage` (`vision.line_crossed`, `vision.occupancy`).
- `scan_area` (LP5, a polygon over a retail checkout's scanner; needs a detector that knows `item`): `flowDirection`
  (`left_to_right`, `right_to_left`, `top_to_bottom`, `bottom_to_top`) is the way items travel on the picture from the
  unscanned side to the bagging side. An item seen before the scanner and then beyond it, on the checkout's lane (the
  scanner's extent across the flow widened by `laneMargin`, 0.15), is reported when it reaches the bagging side:
  `vision.line_crossed` direction `out`, `count` 1, if it went through the polygon (passed over the scanner); otherwise
  `vision.handling` action `bypassed` (carried past the scanner) with a clip of the passage, `preRollSeconds` before
  to `postRollSeconds` after. An item taken back over the scanner is `vision.line_crossed` `in`. Items must be clear
  of the scanner by 0.01 of the picture before their side counts, so jitter at its edge is not counted. Whether the
  till read the item is the ERP's business (rules V-R1 and V-R2 compare these counts with the sale).
- `queue` (LP6b, a polygon over a waiting line; `person` by the feet): one `vision.dwell` per person who waited at
  least `minWaitSeconds` (5), at the end of the wait (`maxCount` 1, `method` `track`); a wait ends when the person is
  seen outside the polygon or not seen for 10 s. `vision.occupancy` (the most people in the line) every
  `occupancyEverySeconds` (60). People are counted, never identified, and no clip is kept.
- **Activity research (LP7, shadow mode).** A zone whose `params.activityResearch` is true also runs activity
  recognizers beside its profile; the profile's own observations do not change. Each recognized segment is a
  `vision.activity` (`activity`, `startedAt`, `endedAt`, `recognizer` as `name@version`, `score` 0–1 when given,
  `trackIds`), sent like any other observation. `params.activityRecognizers` names the recognizers (default: the
  built-in ones). Built in are two simple rules on object tracks: `object_carried_out` (an object that stood in the
  zone for 1 s or more leaves it) and `object_left_behind` (an object still in the zone for `leftBehindSeconds`, 60).
  Others are installed as Python packages declaring an entry point in the group `lp_edge.activity_recognizers` (a
  callable `factory(zone)` returning an object with `name`, `version`, `labels`, `observe(frame, tracks, zone)` and
  `flush(ts)`); no model ships with the agent, and an unknown or failing recognizer is logged and skipped.
  Recognizers see only the tracks of the classes the zone's profile reads, above its `minConfidence`. They concern
  what happens to objects: no pose estimation of people, no faces, no identity, no profile of anyone's behaviour. No
  ERP rule reads an activity; they are measured against reviews and labelled footage (`lp-edge-eval`).
- **Watermarks.** Each heartbeat carries, per camera, `observedThrough`: the instant up to which everything the agent
  saw has been reported and accepted. It never passes the last analysed frame, an occupancy period still open, a
  presence or drawer change awaiting confirmation, or the oldest unsent event of that camera. It stops while a stream
  is lost; on recovery the agent first reports the hole as `vision.gap` (`stream_lost`, `agent_down`, `clock_drift` or
  `disabled`) and only then moves on. The ERP's rules therefore never read "nothing happened" into a period the agent
  did not watch.
- **Clock.** Observation times come from the box's clock. The agent checks it against NTP (`LP_EDGE_NTP_SERVER`) or
  against the ERP's `serverTime` in each heartbeat answer. When it is off by more than the configured limit
  (`clock.maxDriftMs`), the agent stops observing, withdraws what it recorded since the last good check, and reports
  the period as a `clock_drift` gap once the clock is right again.
- **Clips.** An observation that deserves video (a drawer opening, a long dwell) names a clip; the clip is cut from the
  buffer shortly after, sealed in 1 MiB chunks, and its SHA-256 (taken at capture) is registered with the ERP. The
  video itself is uploaded only on request, resumably, in chunks of at most 1 MiB. A clip is at most 600 s and 64 MiB
  (encoded again smaller if needed); formats are WebM (VP8), MP4 or Matroska (MJPEG), whichever the OpenCV build has.

## Configuration

What the ERP decides (cameras, zones, heartbeat interval, buffer days, model, release) comes from `GET /agents/config`.
The box itself is configured with environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `LP_EDGE_DATA_DIR` | `/var/lib/lp-edge` | credentials, database, buffer, clips, models |
| `LP_EDGE_STREAMS_FILE` | `<data>/streams.yaml` | stream addresses |
| `LP_EDGE_TMP_DIR` | `/run/lp-edge` | where clips are encoded before sealing (tmpfs) |
| `LP_EDGE_TRUST_KEY` | — | vendor Ed25519 public key(s): PEM, base64 (raw or DER), or a file path; comma-separated |
| `LP_EDGE_CA_BUNDLE` | system | CA bundle for the ERP |
| `LP_EDGE_ALLOW_HTTP` | `false` | allow `http://` to the ERP (labs only) |
| `LP_EDGE_HEALTH_PORT` | `8765` | local health endpoint (127.0.0.1) |
| `LP_EDGE_NTP_SERVER` | — | NTP server for the clock check (else the ERP's `serverTime`) |
| `LP_EDGE_BUFFER_KEY` | generated | base64 of the 32-byte buffer key, from a secret store (else `<data>/keys/buffer.key`) |
| `LP_EDGE_BUFFER_FPS` | `2` | pictures a second kept in the buffer (0.2–15) |
| `LP_EDGE_BUFFER_MAX_WIDTH` | `640` | width of buffered pictures (160–3840) |
| `LP_EDGE_BUFFER_JPEG_QUALITY` | `60` | JPEG quality of buffered pictures (20–95) |
| `LP_EDGE_ANALYSIS_FPS` | `5` | pictures a second analysed (1–15) |
| `LP_EDGE_ANALYSIS_MAX_WIDTH` | `960` | width of analysed pictures (320–3840) |
| `LP_EDGE_RESERVE_PERCENT` / `LP_EDGE_RESERVE_BYTES` | `12` / 6 GiB | free space kept; the oldest video goes first |
| `LP_EDGE_STALL_SECONDS` | `5` | no picture for this long = stream lost |
| `LP_EDGE_ORT_PROVIDERS` | all available | ONNX Runtime providers, comma-separated |
| `LP_EDGE_LOG_LEVEL` / `LP_EDGE_LOG_FORMAT` | `INFO` / `text` | `json` for log shippers; secrets are masked |
| `LP_EDGE_SERVER` / `LP_EDGE_ENROL_CODE` | — | defaults for `lp-edge enrol` |
| `LP_MEASUREMENT_ADAPTERS` | — | JSON file of pour-meter adapters (see *Pour meters*) |

A value out of range stops the agent with a message (exit 78) rather than being clamped.

### Sizing

The buffer holds `bufferDays` (from the ERP) of every enabled camera, minus what the disk reserve requires. At the
defaults (2 pictures a second, 640 px, quality 60) expect roughly 4–6 GB a camera a day, depending on the scene;
1 picture a second at 480 px and quality 50 brings that to roughly 1.5 GB. Detector load grows with cameras ×
`LP_EDGE_ANALYSIS_FPS` × model size: start with a small YOLOX model (nano or tiny) and check each camera's `fps` in
`lp-edge status`.

## Pour meters (LP3)

The agent can also read pour meters (spouts, flow meters, dispensers) from the vendor's software on the site network
and report each pour as a `pour.measured` event (`evidenceLevel` `item`, `evidenceSource` `sensor`, `deviceId` the
measurement device registered in the ERP). The ERP lists this agent's devices in `GET /agents/config`
(`measurementDevices`); a reading of a vendor device id (`externalId`) that is not listed there, or whose device is
disabled, is counted and dropped, never sent. Nothing is read before the first configuration has been applied.

Adapters are configured on the box, because the meter software's address is site data: set
`LP_MEASUREMENT_ADAPTERS` to a JSON file (in Docker, e.g. `/var/lib/lp-edge/meters.json`; a directory an adapter
reads must be mounted into the container writable, since read files are moved) such as

```json
[
  {"name": "main-bar", "type": "jsonl_dir", "path": "/data/meters/main-bar", "glob": "*.jsonl",
   "unit": "cl", "timezone": "Europe/London", "pollSeconds": 5,
   "fields": {"externalId": "spout", "volume": "amount", "id": "pourId", "startedAt": "start", "endedAt": "end",
              "productId": "sku"}},
  {"name": "taps", "type": "http_poll", "url": "http://192.168.1.20:8080/api/pours", "itemsKey": "items",
   "cursorParam": "after", "unit": "oz_us", "gapAfterSeconds": 120, "devices": "*",
   "fields": {"externalId": "tap.serial", "volume": "volume", "id": "id", "at": "timestamp"}}
]
```

- `type`: `jsonl_dir` (one JSON object per line) or `csv_dir` (header row; `delimiter` defaults to `,`) read the files
  in `path` matching `glob`, oldest first; a file modified in the last 2 s is left for the next poll, and a read file is
  moved to `path/done/`. `http_poll` GETs `url` (5 s timeout) for a JSON array, or an object holding it under
  `itemsKey`; with `cursorParam` each request carries the last vendor id seen (or, without `fields.id`, the latest
  pour time, ISO-8601 UTC). Cursors and processed-file markers are kept in the agent's database.
- `fields` maps the vendor's names (dotted paths reach into nested objects): `externalId` and `volume` are required;
  `id`, `startedAt`, `endedAt`, `at` (one time for the pour) and `productId` are optional. Times are ISO-8601 or epoch
  seconds/milliseconds; a time without an offset is in `timezone` (default UTC).
- `unit`: `ml` (default), `cl`, `l`, `oz_us` or `oz_uk`. Volumes are sent in ml, rounded to 0.001; a volume that is not
  above 0 and at most 5000 ml, like any record that cannot be read, is counted and skipped.
- `sourceEventId` is `<agentId>:meter:<name>:<vendor id>` (or a hash of the record when the vendor has no id), so a
  record read twice is one event in the ledger. Give the vendor id when the vendor has one: without it, two records with
  identical content are one pour.
- An adapter that fails without a break for `gapAfterSeconds` (default 60) reports `measurement.gap` `adapter_down`
  for its devices once it reads again, every 15 minutes while it is still down, and when the agent stops. Its devices
  are the configured devices it has produced a reading for at least once, or every device of the agent with
  `"devices": "*"`. While it is down, the agent's `observedThrough` stays at the start of the unreported outage.
- Meters are not read while the box's clock is off. `lp-edge status` and the health endpoint show `measurements`:
  readings queued, unknown and disabled devices, bad records, adapter failures and gaps. A wrong entry in the file is
  logged and skipped; the other adapters still run. `name` is 1 to 40 of `a-z`, `0-9`, `_`, `-` and unique.

## Models

The ERP's configuration names the model: `{version, detector, url, sha256, signature, inputSize, classes}`. The agent
checks the signature, downloads the model once from the signed URL (its token goes only to the ERP's own origin),
checks the SHA-256, and keeps it under `<data>/models`. Built-in detector kinds: `yolox` (raw head of a model
exported with YOLOX's `tools/export_onnx.py`) and `yolox-decoded` (`--decode_in_inference`). Another kind can be
plugged in by a package with an entry point in the group `lp_edge.detectors` (`factory(model_path, spec, providers)`).
Without a model, `checkout` zones fall back to motion and zones that need a detector report themselves as `disabled`
gaps.

No model ships yet. A candidate is chosen with the evaluation harness, `lp-edge-eval`, which measures accuracy on
labelled footage against each profile's acceptance threshold, speed and memory on the edge box, and the candidate's
status in the licence register: see [`evaluation/README.md`](evaluation/README.md).

## Updates

The configuration may name a signed release `{version, image, digest, signature}`. The agent verifies it and offers it
on `GET http://127.0.0.1:8765/release`. The host's updater (`lp-edge-updater`, run every five minutes by
`deploy/lp-edge-updater.timer`) verifies the signature again, pulls `image@digest`, writes `LP_EDGE_IMAGE` to the env
file, recreates the service, and puts the previous image back if the agent does not report the new version as
healthy within three minutes. See `deploy/lp-edge-updater.service` for installing it in a small venv on the host.

## Development

```bash
python3.12 -m venv .venv && . .venv/bin/activate
pip install -e "edge[test]"
pytest edge/tests
```

Tests use fakes for the ERP, the cameras and the clock; nothing touches the network. The tests in
`test_vision_opencv.py` need NumPy and OpenCV (installed with the package).

Without Docker: `pip install edge` and run `lp-edge run` under systemd with `LP_EDGE_DATA_DIR` on the buffer disk and
`LP_EDGE_TMP_DIR` on a tmpfs.

## Licences of what the image contains

| Component | Licence |
|---|---|
| OpenCV (`opencv-python-headless`) | Apache-2.0; the wheel bundles FFmpeg as LGPL-2.1 shared libraries |
| ONNX Runtime | MIT |
| NumPy | BSD-3-Clause |
| cryptography | Apache-2.0 or BSD-3-Clause |
| requests, urllib3 | Apache-2.0, MIT |
| PyYAML | MIT |
| YOLOX models (supplied separately) | Apache-2.0 |

Ultralytics YOLO (AGPL-3.0) is deliberately not used. No GPL FFmpeg build or system `ffmpeg` is installed.

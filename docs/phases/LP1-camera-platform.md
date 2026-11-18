# LP1 — Camera platform: implementation brief

- Status: **coded on the branch at the owner's request (2026-11-17)**, before a pilot site was named, which overrides
  ADR-S077 §9. The owner builds and tests it later. The loss-prevention test classes pass on `fastTest`; the full
  build has not been run.
- **Detection accuracy has not been measured.** No real footage was available, no detector model ships, and every
  test feeds the backend the observations an agent would send. Section 10 lists what a pilot must measure before a
  vision finding is trusted.
- ADR: [`ADR-S077`](../adr/ADR-S077-loss-prevention.md) §1, §7, §8. Contract and every route, message and setting:
  [`docs/spec/loss-prevention.md`](../spec/loss-prevention.md), section "Vision security (LP1)". This brief does not
  repeat them.
- Branch: `phase/LP0-exception-intelligence` (LP0, LP1 and LP2 together). Extension: no NestJS counterpart, so there
  is no parity scenario and no Jest spec to port.

## 1. Goal

A customer with the `lpVision` licence feature can:

- add an edge agent for a branch and enrol the box with a one-time code; the box then works with its own
  short-lived tokens and never needs a user's password;
- name the branch's cameras and draw zones on a still taken from each camera;
- receive what the cameras see (`vision.*` observations) in the same ledger as the POS events, so the same
  reconciliation runs, cases and reports apply;
- see each agent's health, and be alerted when one goes offline or degraded;
- request the clip behind a case, view and export it with its access trail, and look at a camera live or as a still.

The backend never connects to a camera. Camera addresses and passwords stay on the edge box, and video stays on the
site unless a clip is requested.

## 2. Out of scope

- Choosing a detector (ADR-S077 §8: a separate evaluation, including the licences of the weights). No model ships;
  `LP_VISION_MODELS` is empty until one is cleared. Without a model an agent runs only the methods that need none:
  the drawer reference picture and motion.
- Face recognition or biometric identification, in any phase. Staff are attributed through their POS login.
- The rules of LP3 to LP6. Their zone types (`bar_counter`, `kitchen_pass`, `cafe_counter`, `dining`, `storage`,
  `entrance`) can be drawn so a site is set up once, but no engine reads them yet.
- Any change to POS, inventory, accounting, beverage or KDS code.

## 3. Data model (`V572__loss_prevention_camera_platform.sql`)

Every table is organization-scoped with the RLS policy that has the "no tenant" fallback.

| Table | Holds |
|---|---|
| `LpAgent` | One edge box at one branch: status (`pending`, `active`, `revoked`), health, watermark, versions, the hash of its one-time code, the API client that is its credentials |
| `LpAgentHeartbeat` | Heartbeats, kept `heartbeatRetentionDays` |
| `LpAgentEvent` | The agent's timeline: created, codes issued, enrolled, health changes, revoked. Append-only |
| `LpCamera` | Display name and stream alias. No column can hold an address or a password |
| `LpZone` | Polygon or counting line on a camera picture, its type, link (till, location, …), parameters; retired, never deleted |
| `LpAgentCommand` | Work for an agent: configuration change, snapshot, calibration, clip upload, live start and stop |
| `LpEvidence` | A clip registered at capture with its SHA-256; uploaded only when requested; encrypted at rest |
| `LpEvidenceChunk` | Chunks of an upload in progress, deleted once the clip is assembled and checked |
| `LpEvidenceCase` | Which cases a clip belongs to |
| `LpEvidenceAccess` | Every view, download, export, request, snapshot, live view, upload, integrity failure and deletion. Append-only |

`V572` also adds the case timeline kind `evidence_linked`, `LpReconRun.observationGap`, the `lp:evidence`
permission (granted to `Administrator`), and indexes to find observations by zone and clip.

## 4. Routes

38 routes, listed in the spec. Three groups:

- **Administration** (`/agents`, `/models`, `/cameras`, `/zones`, `/live`): `lp:configure`; stills also need
  `lp:evidence`, live view needs `lp:evidence`. Creating an agent or a new code needs `lp:ingest` as well, because
  the credentials it issues carry that scope.
- **The box** (`/agents/enrol`, `/agents/config`, `/agents/heartbeat`, `/agents/commands`, `/agents/clips`,
  `/agents/snapshots/{commandId}`, `/agents/live/{sessionId}/frames`, `/evidence/{id}/upload`): `lp:ingest` and an
  enrolled agent's own token. Enrolment is public; the one-time code is the authorization.
- **Evidence** (`/evidence`): `lp:evidence`.

Every route also needs the `loss-prevention` module and the `lpVision` licence feature (when the licence is
enforced). As for every feature, switching `lpVision` off in an organization's Developer Settings hides the screens;
the backend gate is the licence.

## 5. Agent protocol

Outbound HTTPS only, started by the agent: enrol once, take a token, read the configuration, send a heartbeat every
minute, long-poll for commands, post observations through the ordinary `POST /events`, register clips as it keeps
them, and upload one in resumable chunks only when asked. Details, payloads and error messages are in the spec.

Watermarks: the agent's `observedThrough` (and each camera's) says every observation up to that instant has been
sent. A vision rule never finds anything after it, nor inside a `vision.gap`. A window whose watermark advances is
reconciled again.

## 6. Evidence

- Registered at capture with a SHA-256; the clip stays on the box (`evidenceBufferDays`).
- Uploaded when an investigator asks, or when a case opens: the clips of the case's observations, and for a finding
  about a POS event at a till with cameras (C01, C02, C03, V-C2), a clip of the till from 20 seconds before to 30
  seconds after the event.
- Checked against its SHA-256 at upload and again at every view, download and export; a mismatch answers 409 and is
  recorded.
- Encrypted with AES-256-GCM before it reaches the file storage. The key is derived from `LP_EVIDENCE_KEY` (else
  `ENCRYPTION_KEY`, else the JWT access secret); the evidence id is bound in as associated data, so a stored file
  moved to another record does not decrypt.
- Retention per organization with a branch override: `evidenceCaseDays` while a linked case is open,
  `evidenceClosedCaseDays` after its cases close (or after upload when it has none). The nightly job deletes the
  file and keeps the row and its trail.
- Stills and live frames are never stored. A still is kept in memory for 10 minutes; live view relays the latest
  frame as `multipart/x-mixed-replace` through a link signed for that session.

## 7. Health and alerts

Heartbeats set `online` or `degraded` (`stream_lost`, `disk_low`, `clock_drift`, `queue_backlog`). No heartbeat for
`agentSilentMinutes` sets `offline`. Each change goes to the agent timeline and is published as
`lp.agent.health_changed`. The alert type `lp_agent_health` (hourly) raises one alert per agent offline (critical)
or degraded (warning) for at least its threshold.

## 8. The edge agent (`edge/`)

A Python 3.12 package (`lp_edge`, about 7,200 lines) that runs as a container or a service on a small box at the
branch. `edge/README.md` covers installing, enrolling, streams, settings, models, updates and sizing.

- `enrol`, `credentials`, `api`: the protocol above; credentials stored on the box only.
- `streams`, `camera`, `video`, `ringbuffer`, `clips`: camera addresses live in `streams.yaml` on the box (readable
  by the agent's user only, set with `lp-edge streams set <alias>`), keyed by the alias the ERP knows. The agent keeps
  a rolling buffer and the clips it flags, and cuts a clip from the buffer when the ERP asks for a period.
- `profiles/`: one per zone type (`cash_drawer`, `checkout`, counting lines, handling, occupancy) turning frames into
  `vision.*` observations.
- `detectors/`: the pluggable detector interface; `motion` needs no model, `yolox` loads a signed ONNX model when one
  is configured (CPU only).
- `outbox`, `uploader`, `live`, `commands`, `health`, `clock`: the queue that survives an internet outage (batches of
  200), chunked uploads sent with the clip's own content type, live frames, commands, heartbeats and the clock check.
- `signing`, `updater`, `models`: checks the vendor signature on a model or a release before using it; the updater
  rolls back a release that does not become healthy.
- Packaging: `edge/Dockerfile` (python:3.12-slim, non-root), `edge/deploy/compose.yml` and a systemd timer for the
  updater. CI has an `edge` job: `pytest edge/tests`, then `docker build`.

Its own tests are under `edge/tests` (section 11).

## 9. Web

On `/loss-prevention`, when `lpVision` is licensed and on: a **Cameras** tab (`lp:configure`: agents with health,
the one-time code, cameras, the zone editor drawn over a still, live view) and an **Evidence** tab (`lp:evidence`:
clips with filters, detail with the access trail, request, view, export). Runs show `observationGap`.

## 10. Accuracy, and what the pilot must measure

Nothing about detection quality is known yet. Before vision findings are used for decisions, a pilot site must
measure, per camera angle and lighting:

- drawer openings: detected and missed, against the POS record and a reviewed sample of video (V-C1);
- customer presence at the till: detected and missed (V-C2, V-C3);
- how often agents drift, lose a stream or go offline, and how often windows have gaps;
- the false-case rate per rule and per week, which sets the default confidence thresholds.

Until then every vision case states the observation and its confidence, and the investigator confirms on the clip.

## 11. Tests

- `LpVisionIT` (Testcontainers): enrolment with a one-time code, token, configuration, heartbeats and health,
  commands, observations refused for another agent's zone or camera, clip registration, upload in chunks with a
  wrong offset, content and export with the trail, a V-C1 case with its clip linked, stills and live view, a failed
  integrity check, tenant isolation, revocation, and the 403 of all 38 routes.
- `LpVisionOpsIT` (Testcontainers): agent changes and a second enrolment that replaces the secret, camera and zone
  changes, calibration, clips cut from the buffer, uploads that fail or lapse, a live view the box cannot start, and
  the scheduled work run by hand (silent agents, expired commands, the health alert, evidence retention, heartbeat
  pruning).
- `LpVisionEngineTest` (V-C1 to V-C3 and C11), the vision cases of `LpEventValidatorTest`, `LpEvidenceCipherTest`.
- `edge/tests`: 99 pass, run with a stand-in for pytest because pytest is not installed on the machine that wrote
  them. The OpenCV module (YOLOX decoding, JPEG, clip encoding, motion, drawer calibration) needs NumPy and OpenCV
  and first runs in CI.
- `LpReconIT` now expects 17 rules and 9 projection sources.
- Not run: the full build, the web type check (it needs `packages/shared` rebuilt for the new permission), web
  tests, `docker build` of the edge image. The screens have no translations yet.

## 12. Open items

- Live views and stills are in the memory of the API instance that started them; behind several instances the
  live and snapshot routes need sticky routing.
- No route deletes a camera (switch it off instead) or shows a command's status.
- Enrolment is public, so the organization's module switch is not checked there; the other routes refuse the
  credentials while the module is off.
- The ledger retention job (LP0) is still not built; evidence retention is.
- The agent cannot tell when a zone was drawn, so between drawing a zone and the agent loading it (seconds while it
  is online) the camera's watermark already covers the zone. Sending each zone's `createdAt` in the configuration
  would let the agent report that period as a gap.
- At the default settings the agent's buffer takes roughly 4 to 6 GB per camera per day, against 1 GB in the design.
  `LP_EDGE_BUFFER_FPS` and the buffer width bring it down; to be sized at the pilot.
- No detector model ships. Without one, drawer zones use the reference picture, checkout zones fall back to motion,
  and the other zone types report a `disabled` gap.
- The edge container reports unhealthy until it is enrolled. The updater is installed on the host by hand.

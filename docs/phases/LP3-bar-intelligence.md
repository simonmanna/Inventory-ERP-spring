# LP3 — Bar intelligence: implementation brief

- Status: **coded on the branch at the owner's request (2026-11-18)**, after LP0–LP2 and LP6a. The owner builds and
  tests it later. The affected test classes pass on `fastTest`; the full build has not been run.
- **Nothing here has been measured on a real bar.** Meter readings and camera observations are trusted as sent;
  every threshold is a design value. Section 7 lists what a pilot must measure.
- Needs LP0 (ledger, engines, cases). The camera rules need LP1 (agents, cameras, `bar_counter` zones).
- Contract: [`docs/spec/loss-prevention.md`](../spec/loss-prevention.md), section "Bar intelligence (LP3)".
- Branch: `phase/LP0-exception-intelligence`. Extension: no NestJS counterpart. Migration `V575` (shared with LP4).

## 1. Goal

LP0 compares bottle counts with the stock ledger (B01–B03): one figure per product per count, days apart. LP3 adds
what happens between counts:

| Code | Finding | Needs |
|---|---|---|
| `B04` | More volume poured of a product than its sales drew | Pour meters |
| `B05` | Pours well above the standard measure at one device | Pour meters with a standard measure |
| `V-B1` | A bottle poured at the bar counter with no drink sale near it | A `bar_counter` zone |
| `V-B2` | A pour the camera saw with no meter reading at that station | A `bar_counter` zone and a meter at its station |

## 2. Out of scope

- Converting grams to millilitres. Scales stay in the beverage module's count sessions (ADR-S077: loss prevention
  never converts weight); a meter reports volume. Volume units (cl, US fl oz) are converted on the edge box.
- Any vendor cloud integration. An adapter reads what the meter's own software leaves on the site network.
- Changing POS, beverage, inventory or KDS code.
- Choosing a detector. `V-B1` and `V-B2` use the `vision.handling` events the LP1 `bar_counter` profile sends
  (`tilted`), which need a detector that knows `bottle`; none ships.

## 3. Measurement devices (`LpMeasurementDevice`, V575)

A pour spout, a flow meter on a line, or a dispenser. One row per device the vendor's system names.

| Field | Meaning |
|---|---|
| `branchId` | Fixed at creation |
| `agentId` | The edge agent whose adapter reads it; empty when an outside system posts its readings itself |
| `kind` | `pour_spout`, `flow_meter` or `dispenser`; fixed |
| `externalId` | The vendor's id of the device (1–200 characters); unique per branch among devices not retired; fixed |
| `vendor` | Free text |
| `productId` | The product it pours when it is fixed to one; a reading may name its own |
| `station` | The bar station, as written on a `bar_counter` zone's link (compared without case) |
| `standardPourMl` | The measure it should pour (B05) |
| `enabled`, `retiredAt` | Retired, never deleted |

Routes (all `lp:configure`, module `loss-prevention`, no extra licence key — meters are not cameras; this choice
awaits the owner's approval):

| Verb and path | Purpose |
|---|---|
| `GET /measurement-devices?branchId&includeRetired` | Plain array, with `lastReadingAt` and `readings7d` |
| `POST /measurement-devices` | Create; 201 |
| `GET /measurement-devices/{id}` | One device |
| `PUT /measurement-devices/{id}` | `name`, `vendor`, `agentId`, `productId`, `station`, `standardPourMl`, `enabled` |
| `POST /measurement-devices/{id}/retire` | Retire |

A change to a device read by an agent raises that agent's `configVersion` (old and new agent); `GET /agents/config`
lists the agent's devices under `measurementDevices`.

## 4. Event types

| Type | Level, source, status | `deviceId` | Payload |
|---|---|---|---|
| `pour.measured` | `item`, `sensor`, `observed` | The device id | `volumeMl` (above 0, at most 5000); optional `productId`, `startedAt`, `endedAt`, `vendorEventId` |
| `measurement.gap` | `item`, `sensor`, `unavailable` | The device id | `from`, `to`, `reason`: `device_offline`, `adapter_down`, `clock_drift`, `disabled` |

At ingest, besides the payload checks: the device must exist at the event's branch (`Measurement device not found`);
an edge agent may send only for its own devices (`Measurement device not found for this agent`); a reading after the
device was retired is refused (`The measurement device was retired before this event`).

## 5. Engine `bar_pour`

A calendar day of the branch in the organization's zone, like the vision engines (15 minutes margin each side). It
runs only where the branch has a `bar_counter` zone or a measurement device. Settles with `visionSettleMinutes`.

The product of a reading is `payload.productId`, else the device's. A sale's volume of a product is the sum over its
lines of `ingredients[].quantity` for that product: the recipe snapshot taken at the sale (ADR-S077), now projected
on every `sale.completed` line with its `totalValue`.

| Code | How it decides | Parameters (default) | Runs |
|---|---|---|---|
| `B04` | Per product: poured − sold ≥ `minExcessMl` and poured > sold × (1 + `tolerancePercent`/100). Not run on a day with a sale whose lines carry no `ingredients` (projected before LP3, or a sender that has none) | `tolerancePercent` 5, `minExcessMl` 100 | Final day |
| `B05` | Per device with a standard measure: pours above it × (1 + `tolerancePercent`/100); a finding when there are at least `minCount` and they are at least `minSharePercent` of its pours | `tolerancePercent` 15, `minCount` 5, `minSharePercent` 20 | Final day |
| `V-B1` | A `vision.handling` `tilted` of at least `minTiltSeconds`, observed with `minConfidence`, with no drink sale from `windowBeforeSeconds` before to `windowAfterSeconds` after. A drink sale has a line that drew an ingredient; a sale without ingredient data counts as one | `windowBeforeSeconds` 120, `windowAfterSeconds` 600, `minConfidence` 0.6, `minTiltSeconds` 1 | Every run |
| `V-B2` | A tilt as in V-B1 on a zone linked to a station that has a live meter, with no `pour.measured` from that station's meters within `windowSeconds`. Skipped, never found, inside a `measurement.gap` of one of those meters or after the agent's watermark of a meter an agent reads | `windowSeconds` 30, `minConfidence` 0.6, `minTiltSeconds` 1 | Every run |

Exposure: B04 at the cost of the sold volume (Σ `totalValue` ÷ Σ quantity) when sales give it; the others none.
Subjects: B04 the product, B05 the device, V-B1/V-B2 the zone. Staff are not identified on video.

## 6. Edge adapters (`edge/lp_edge/measurements`)

The agent reads meters the same way it reads cameras: from the site network, outbound only. Adapters are configured
on the box (`LP_MEASUREMENT_ADAPTERS`, a JSON file), because the meter software's address is site data, like a
camera's. Types: `jsonl_dir` and `csv_dir` (files the vendor software writes; a file is read once and moved to
`done/`), `http_poll` (a local JSON endpoint, with a cursor). Each maps the vendor's field names, and the volume unit
(`ml`, `cl`, `l`, `oz_us`, `oz_uk`).

- A reading of an `externalId` the backend does not list for this agent is counted and dropped, never sent.
- `sourceEventId` is the agent id, the adapter name and the vendor's reading id (else a hash of the reading), so a
  file read twice is one event.
- An adapter that fails for `gapAfterSeconds` (60) reports `measurement.gap` `adapter_down` for each of its devices
  for the failed period.

## 7. What a pilot must measure

- Meter accuracy against measured pours (B04 tolerance, B05 tolerance).
- How often a pour is tilted on camera without a meter, by design (a bottle without a spout): V-B2's false rate.
- V-B1 in a busy bar: a sale within ten minutes nearly always exists, so V-B1 finds only quiet-period pours.

## 8. Tests

- `LpBarPourEngineTest`: B04 found, within tolerance, skipped without ingredient data; B05; V-B1 found and explained;
  V-B2 found, explained, in a gap, pending watermark; determinism.
- `LpBarRestaurantIT`: device routes and messages, the permission of every route, tenant isolation, a reading through
  `/events` checked against the device, an agent refused another agent's device, `measurementDevices` in the agent
  configuration, and a B04 case through a replay.
- `LpEventValidatorTest`: the two new types.
- Edge: `tests/test_measurements.py` (22 tests; 150 edge tests pass with the pytest stand-in).
- Not run: the full build.

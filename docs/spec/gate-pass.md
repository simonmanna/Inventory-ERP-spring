# Gate pass (module `gatepass`, ADR-S085)

A gate pass is the gate-out document for the driver, vehicle, person or courier who takes goods out through the gate.
It has no NestJS counterpart: it is a post-parity owner request (2026-11-23), and every route here is new.

## Sources

| `sourceType` | Source (table) | Branch from | Goods issued when | Payment from | Gate-out also |
|---|---|---|---|---|---|
| `delivery` | `FleetLoad`: sales order delivery or fleet trip | delivery order, else load location | `stockIssuedAt` set | the order or invoice behind the delivery order | trip `depart` (only from `dispatched`) |
| `sales_order` | `SalesOrder` with its `SalesHandover` | order | `deliveryStatus = full` | order invoices and deposits | handover → `dispatched` |
| `invoice` | `Document` (sales_invoice) with its handover | invoice | posted / paid | `paymentStatus` | handover → `dispatched`; for a cash-sale invoice, the cash sale delivery |
| `cash_sale` | `SalesSubmission` | sale | `confirmed` | `paidTotal` vs `grandTotal` | delivery → `dispatched` |
| `stock_transfer` | `StockTransfer` | from-location | dispatched / in transit / completed | — | recorded only |
| `pick_order` | `WarehousePickOrder` | location | packed / shipped | — | recorded only |
| `rental` | `RentalAgreement` | agreement | checked out or later | agreement invoice | recorded only |
| `repair` | `RepairOrder` (its devices) | order | ready for pickup or later | repair invoice | recorded only |
| `supplier_return` | `DebitNote` (inbound, returned_goods) | `customFields.locationId` | posted | — | recorded only |

A cancelled, voided, rejected or reversed source cannot get a pass. A pass that was already issued cannot be used
once its source is cancelled.

## Routes (`/api/v1`)

| Verb | Path | Permission | Notes |
|---|---|---|---|
| GET | `/gate-passes` | `gate_pass:read` or `gate_pass:check` | list envelope `{data, meta}`; filters below |
| GET | `/gate-passes/source/{sourceType}/{sourceId}` | read or issue | data for the issue dialog |
| GET | `/gate-passes/verify?code=` | check | number or QR text |
| GET | `/gate-passes/{id}` | read or check | pass detail |
| POST | `/gate-passes` | issue | 201 |
| GET | `/gate-passes/{id}/pdf` | read or issue | A5 PDF; every print is counted and logged |
| POST | `/gate-passes/{id}/gate-out` | check | 200; the result carries `sourceUpdate` |
| POST | `/gate-passes/{id}/refuse` | check | 200; logged, the pass stays usable |
| POST | `/gate-passes/{id}/void` | void | 200 |

- **`GET /gate-passes` filters:** `status` (issued / used / void / expired), `sourceType`, `sourceId`, `branchId`, `q`
  (number, bearer, plate, document, partner), `from` and `to` (dates, business zone), `page`, `pageSize`.
- **Issue dialog data (`/source/...`):** `source`, `warnings`, `prefill` (bearer, phone, licence, plate, vehicle type,
  transporter, destination), `lines` with `{sourceQuantity, onPasses, remaining}`, and `passes`.
- **Verify (`/verify?code=`):** `code` is either a number (`GP-2026-00001`) or the scanned QR text
  (`GP-2026-00001|ABC123`). It returns the detail plus `codeVerified`.
- **Detail (`/{id}`):** the pass with `lines` and `events`, plus:
  - `source`: the live source summary;
  - `problems`: void, already_used, expired, source_cancelled or source_missing;
  - `canLetOut` and `liveWarnings`.
- **Issue (`POST /gate-passes`):** body `{sourceType, sourceId, bearerType, bearerName, bearerIdNumber?, bearerPhone?,
  vehiclePlate?, vehicleType?, transportCompany?, destination?, purpose?, remarks?, validHours? (1–168, default 24),
  lines? [{sourceLineId, quantity}]}`. When `lines` is left out, every line goes on the pass with what is left.
- **Gate-out:** body `{verifyCode?, plate?, note?}`.
- **Refuse and void:** body `{reason}`.

`verifyCode` is never part of a JSON response. It is printed on the pass and inside the QR code.

## Rules and messages

- `bearerType` must be driver, person or courier. A driver needs `vehiclePlate`: 400 `vehiclePlate should not be empty
  for a driver`. Plates are stored in upper case.
- Cancelled source: 400 `Cannot issue a gate pass for a cancelled <label>`.
- Shared cap: an invoice raised from a cash sale or sales order, and a delivery of an order (or of an invoice), belong
  to that root document (`rootType` / `rootId`). Per product, the passes of all related documents together never exceed
  the root's quantity, so the same goods cannot go out once on the cash sale and again on its invoice. The issue dialog's
  `remaining` already applies this cap. Quantities are compared as numbers; mixed units of the same product across the
  documents are not converted.
- Lines:
  - An unknown line gets 400 `Line <id> is not on this <label>`.
  - A line listed twice gets 400 `Line <id> is listed twice`.
  - Too much gets 400 `Quantity for <item> exceeds what is left to pass out (<n>)`.
  - Nothing left gets 400 `Every item of this <label> is already on a gate pass`.
- Warnings are stored and printed as red banners: `goods_not_issued`, `unpaid`, `partially_paid`. They never block.
- Number: `GP-{YYYY}-00001`. The sequence key is `gate_pass:YYYY`, configurable under document numbering (Inventory).
- Gate-out refusals:
  - void: 400 `Gate pass is void`;
  - used: 409 `Gate pass was already used at …`;
  - expired: 400 `Gate pass expired at …`;
  - source cancelled: 400 `The source document was cancelled; the goods may not leave`;
  - wrong code: 400 `Verification code does not match this gate pass`;
  - wrong plate: 400 `Vehicle plate X does not match the gate pass (Y)`.
- Void: 400 `A gate pass that was used at the gate cannot be voided`, or 400 `Gate pass is already void`.
- Events (`GatePassEvent`): issued, printed (reprints note `COPY n`), gate_out (data `sourceUpdate`), refused, voided.
  The audit log records issue, gate-out (update), refuse (reject) and void (cancel).

## Web

- Every delivery screen shows a **Gate pass** card that lists the passes, with print, void and "Issue gate pass".
- **Inventory → Gate passes → Gate check** (`/gate`): the security screen. The guard scans or types the pass, sees
  green "Valid" or red "Do not let out", the warnings, bearer, vehicle and items, then chooses Let out or Refuse.
- **Inventory → Gate passes** (`/gate-passes`): the register, with filters and the history of each pass.

## Tests

`GatePassIT`:
- every source query against the schema;
- cash sale caps, warnings, void, and blocking when the source is cancelled;
- shared cap between a cash sale and its invoice;
- concurrency: four simultaneous issues for the same goods give exactly one pass;
- gate verify by QR, wrong code, gate-out marks the cash sale dispatched, used only once;
- plate mismatch and expiry;
- sales order handover marked dispatched;
- invoice of a cash sale;
- stock transfer recorded only;
- PDF and print count, and tenant isolation;
- authorization matrix (9 routes).

## Deviations

Post-parity extension (owner request 2026-11-23); see ADR-S085. The delivery modules' existing routes and responses
are unchanged. At gate-out, the cash sale delivery method is set only when it was empty: pickup for a person or
courier, delivery for a driver.

# Integrations

Every external system the ERP talks to, what exists for it today and how to add a vendor. Real hardware and vendor
accounts are not part of this repository: each integration is an interface, a simulator or mock so the flow can be
exercised, and config keys that stay blank until a real system exists. Nothing here has been run against a real device
or vendor.

| Integration | Interface | Built in | Selected by | Status |
|---|---|---|---|---|
| Weighbridge indicators | `fleet.application.WeighbridgeAdapter` | `simulator`, `ascii-tcp`, `ascii-serial` | Adapter type on each device | Generic ASCII works with most indicators; no vendor-specific adapter |
| Telematics | `fleet.application.TelematicsProvider` | `mock`, `rest` | `FLEET_TELEMATICS_PROVIDER`, then the type on each device | `rest` fixes a small JSON contract; no vendor API mapped |
| Machine data (PLC, counter, belt scale) | `manufacturing.spi.MachineDataAdapter` | `simulator`, `rest`, push route, CSV route | Adapter code on the poll request | Events become drafts an operator confirms |
| Laboratory | `quality.application.LabResultAdapter` | `simulator`, `rest`, CSV route | Adapter code on the fetch request | Results map to specification parameters by code |
| Mobile money | `invoicing.application.MobileMoneyProvider` | `mock` only | `MOBILE_MONEY_PROVIDER` | **No real provider exists yet** |
| Bank statements | `accounting.application.BankStatementParser` (not an SPI) | CSV, MT940, camt.053 | `format`, or detected from the file | Inbound statements only |
| Labels | `documents.application.LabelRenderer` (not an SPI) | PDF, ZPL; QR, Code 128 | `format` and `symbology` on the request | No direct printing: the browser or driver prints the file |
| Messaging | `communication.application.providers.MessagingProvider` | `internal`, `telegram`, `whatsapp` | Channel configured per organization | See [`spec/communication.md`](spec/communication.md) |
| BI | `reporting` views and `/bi/datasets` | 8 datasets | Permission `bi:export` | Read-only; see below |

A failed call to an external system never posts anything to the ledger, and no key or token is ever logged.

## Weighbridge

`WeighbridgeAdapter` reads a stable weight from one indicator and never blocks past the timeout it is given. Devices are
configured on the weighbridge settings screen (`/fleet/weighbridge/settings`); each stores its adapter type and a JSON
configuration, validated by `validate(config)` before it is saved.

| Key | Meaning |
|---|---|
| `FLEET_WEIGHBRIDGE_MAX_READ_TIMEOUT_MS` (`app.fleet.weighbridge.max-read-timeout-ms`) | Upper bound of any read; a device's own `readTimeoutMs` cannot exceed it. Default 15000 |

Device configuration, read by `AsciiFrameParser` and the TCP adapter:

| Field | Meaning |
|---|---|
| `host`, `port` | `ascii-tcp`: the indicator, or a serial-to-Ethernet converter |
| `frameRegex` | Regex with the named group `weight` and the optional groups `status`, `sign`, `unit` |
| `stableValues`, `motionValues`, `overloadValues` | Status tokens. Defaults `ST`; `US, MO, M`; `OL, OV` |
| `unit` | Unit when the frame has none: `kg`, `t`, `g`, `lb`. Default `kg` |
| `decimals` | Implied decimal places when the weight has no decimal point. Default 0 |
| `stableFrames` | Equal stable frames in a row that make a reading stable, 1 to 20. Default 3 |

**Add a vendor.** Try the ASCII adapters first: most indicators only need a `frameRegex`. For a protocol they cannot
read, add a Spring bean implementing `WeighbridgeAdapter` with a new `type()`. No schema change is needed. Return the raw
frame in `StableWeight`, because it is kept on the ticket for audit. Throw `WeighbridgeReadException` on a timeout.

## Telematics

`TelematicsProvider` gives positions and meters per unit and events for all units after a cursor. The sync job
(`FLEET_TELEMATICS_SYNC_CRON`) pulls them; re-pulling a window is safe because rows carry the provider's `externalId`.
Devices are managed under `/fleet/telematics/devices`, and `POST /fleet/telematics/sync` runs a pull.

| Key | Meaning |
|---|---|
| `FLEET_TELEMATICS_PROVIDER` | Provider of new devices: `mock` (default) or `rest` |
| `FLEET_TELEMATICS_URL`, `FLEET_TELEMATICS_API_KEY` | Base URL and bearer key of `rest` |
| `FLEET_TELEMATICS_TIMEOUT_MS` | Per call. Default 10000 |
| `FLEET_TELEMATICS_SYNC_CRON` | Pull schedule. Default every 5 minutes |
| `FLEET_TELEMATICS_RETENTION_DAYS` | Position history kept. Default 365 |

The `rest` contract: `GET /units/{unit}/positions?from=&to=`, `GET /units/{unit}/meters` (404 means the unit reports
none) and `GET /events?since=`.

**Add a vendor.** Either put a thin gateway answering that contract in front of the vendor, which needs no code here, or
add a bean implementing `TelematicsProvider` with a new `type()`. A failed call is recorded on the device and the rest
carry on.

## Machine data

A PLC, counter, belt scale or gateway delivers output counts and downtime. Events are never posted: the shop floor turns
them into draft reports that an operator confirms on the terminal.

| Route (`shop_floor:machine_data`) | Use |
|---|---|
| `POST /manufacturing/shop-floor/machine-data/poll` | Pull adapters |
| `POST /manufacturing/shop-floor/machine-data/push` | A gateway or MQTT bridge posts events; needs no bean |
| `POST /manufacturing/shop-floor/machine-data/csv` | Upload a file |

| Key | Meaning |
|---|---|
| `MFG_MACHINE_DATA_REST_URL`, `MFG_MACHINE_DATA_REST_TOKEN` | `rest` adapter: `GET <url>/events?machine=&since=&until=` with a bearer token. A blank URL means not configured |
| `MFG_MACHINE_DATA_REST_TIMEOUT_MS` | Default 10000 |

**Add a vendor.** Prefer the push route. For a pull source, add a bean implementing `MachineDataAdapter` with a new
`code()`. `externalId` must be stable across re-deliveries, because ids are unique per adapter. Event types are `count`,
`downtime`, `downtime_start` and `downtime_stop`.

## Laboratory

An external laboratory delivers results for inspection lots, matched to the lot's specification parameters by
`parameterCode`. A sieve result is one row per sieve.

| Route | Permission | Use |
|---|---|---|
| `POST /quality/lab-imports/fetch` | `quality:inspect` | Ask an adapter for results |
| `POST /quality/lab-imports/csv` | `quality:inspect` | Upload a results file |
| `GET /quality/lab-imports`, `GET /quality/lab-imports/{id}` | `quality:read` | What was imported |

| Key | Meaning |
|---|---|
| `QUALITY_LAB_REST_URL`, `QUALITY_LAB_REST_TOKEN` | `rest` adapter. A blank URL means not configured |
| `QUALITY_LAB_REST_TIMEOUT_MS` | Default 10000 |

**Add a vendor.** Add a bean implementing `LabResultAdapter` with a new `code()`. `configured()` must be false until its
credentials are set. Return nothing for a lot the laboratory has not finished.

## Mobile money

`mock` is the only provider. `MOBILE_MONEY_URL` and `MOBILE_MONEY_API_KEY` are reserved for a real one and are not read
by any code yet.

| Route | Use |
|---|---|
| `GET`, `POST /mobile-money/collections`, `GET /mobile-money/collections/{id}` | List, start, read |
| `POST /mobile-money/collections/{id}/refresh` | Ask the provider now |
| `POST /mobile-money/callbacks/{provider}/{organizationId}` | Provider callback, signed |

| Key | Meaning |
|---|---|
| `MOBILE_MONEY_PROVIDER` | Provider of new collections. Default `mock` |
| `MOBILE_MONEY_URL`, `MOBILE_MONEY_API_KEY` | For a real adapter. Never logged |
| `MOBILE_MONEY_CALLBACK_SECRET` | Callbacks carry a hex HMAC-SHA256 of the body with this secret |
| `MOBILE_MONEY_POLL_CRON` | Status poll. Default every 2 minutes |

**Add a vendor.** Add a bean implementing `MobileMoneyProvider` with a new `code()` and set `MOBILE_MONEY_PROVIDER`. It
does no database work and throws on a failed call, which leaves the collection pending. `reference` is unique, so a
retry is safe at a provider that honours it. `parseCallback` runs after the signature check, so it only reads the body.
The phone number is for that call only and is not stored.

## Bank statements

`POST /bank-reconciliation/import-file` (multipart `file`, `bankAccountId`, optional `format`, `dryRun=true` to preview;
`Idempotency-Key` required; `bank_account:update`). Formats are `csv`, `mt940` and `camt053`, or `auto` to detect. CSV
needs a header row in English; comma, semicolon and tab separators work, and an ambiguous date is read day first. Amounts
are signed, money in positive.

**Add a format.** `BankStatementParser` is pure and has no SPI: add a case to `parse`, teach `detect` to recognise the
file, and add test files. Outbound bank payment files are not built.

## Labels

`GET /labels/sources/{type}` lists what can be printed and `POST /labels/render` returns the file (`label:print`). Types
are `product`, `stockpile` and `ticket`; up to 200 ids, 1 to 50 copies. Stock is 100 × 60 mm. `format` is `pdf` (any
printer, barcode drawn from a ZXing matrix) or `zpl` (a Zebra printer draws its own barcode); `symbology` is `qr` or
`code128`.

**Add a label type.** Add its query to `LabelService` and its value to `LabelRequest.type`. **Add a printer language.**
Add a method to `LabelRenderer` next to `zpl` and its value to `LabelRequest.format`.

## BI export

Two ways in, both read-only:

**CSV.** `GET /bi/datasets` lists the datasets and their columns; `GET /bi/datasets/{key}/export.csv?from=&to=` downloads
one (`bi:export`, screen `/reports/bi-exports`). Dates default to this month so far and a file holds at most 200 000 rows.
Files are UTF-8 with a BOM, and text cells a spreadsheet would run as a formula are neutralized.

**Views.** Migration V111 creates the `reporting` schema. A BI tool connects to these views and not to the tables:

| View | Dataset key | Date column |
|---|---|---|
| `reporting.sales_lines` | `sales-lines` | `issueDate` |
| `reporting.purchase_lines` | `purchase-lines` | `orderDate` |
| `reporting.stock_on_hand` | `stock-on-hand` | none, a snapshot |
| `reporting.crushing_output` | `crushing-output` | `runDate` |
| `reporting.block_production` | `block-production` | `startedAt` |
| `reporting.weighbridge_tickets` | `weighbridge-tickets` | `createdAt` |
| `reporting.delivery_lines` | `delivery-lines` | `requestedDate` |
| `reporting.journal_lines` | `journal-lines` | `postingDate` |

Each view shows the organization in the session setting `app.org_id` and nothing when it is unset. To give a BI tool
access:

```sql
CREATE ROLE bi_reader LOGIN PASSWORD '<a strong secret>';
GRANT USAGE ON SCHEMA reporting TO bi_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA reporting TO bi_reader;
ALTER ROLE bi_reader SET app.org_id = '<organization id>';
```

**Limit:** a Postgres session can change its own `app.org_id`, so this login is only safe when every organization in the
database belongs to the same owner. On a shared database, use the CSV route, which is filtered by the signed-in
organization. The views are a contract: a later migration that drops or retypes a column they use must recreate them.

**Add a dataset.** Add the view in a new migration, with the `app.org_id` filter and an `organizationId` column, then add
a row to `DATASETS` in `BiExportService`. Columns are read from the database, so nothing else changes.

## Alerts as an integration point

Alerts raise `alert.raised` once per type and scan. It is rule-eventable, so a communication rule can route it. See
[`spec/alerts.md`](spec/alerts.md).

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
| Embedded dashboards | Apache Superset 6.1.0, `bi` views, `/bi/dashboards` | 7 module dashboards | `SUPERSET_*`, compose profile `bi` | Isolation through guest-token row-level security; see below |

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

## Embedded dashboards (Apache Superset)

ADR-S078. Sales, Procurement, Inventory and Finance have a built-in analytics screen (`/analytics/<module>`); it gets an
**Explore** view showing the module's Superset dashboard inside the ERP. POS, HR and Manufacturing have no built-in
screen; their dashboard opens at `/analytics/bi/<module>`, linked from the analytics tabs. A dashboard appears only when
it is configured and the user holds `analytics:view` plus the module's permission:

| Module | Permission | Views |
|---|---|---|
| `sales` | `analytics:sales` | `sales_lines`, `sales_invoices` |
| `procurement` | `analytics:procurement` | `purchase_lines`, `vendor_bills` |
| `inventory` | `analytics:inventory` | `stock_on_hand`, `stock_movements` |
| `finance` | `analytics:finance` | `journal_lines`, `payments` |
| `pos` | `pos:reports` | `pos_sales`, `pos_sale_lines`, `pos_cash_sessions`, `pos_voided_items` |
| `hr` | `hr:report` | `hr_employees`, `hr_attendance`, `hr_leave`, `hr_payroll_costs` |
| `manufacturing` | `production_order:read` | `production_orders`, `production_materials`, `production_variances` |

 The browser never logs into Superset. The API mints a guest token valid for 5 minutes
for one dashboard, and the token carries the clause `"organizationId" = '<the user's organization>'`. Superset adds
that clause to every query the token runs. The organization comes from the signed-in session, so this is safe on a
shared database.

| Route | Permission | Returns |
|---|---|---|
| `GET /bi/dashboards` | `analytics:view` | `[{module, label}]` the caller may open; `[]` when Superset is off |
| `GET /bi/dashboards/{module}/guest-token` | `analytics:view` + the module's permission (table above) | `{module, dashboardId, supersetDomain, token, expiresAt}`; 404 unknown or unconfigured module or Superset off, 503 Superset configured but unreachable |

**Data.** Migration V570 creates the `bi` schema: `sales_lines`, `sales_invoices` (with aging), `purchase_lines`,
`vendor_bills` (with aging), `stock_on_hand`, `stock_movements`, `journal_lines` and `payments`. Unlike `reporting`,
these views do not filter on `app.org_id`. Only `superset_reader` may read them; the API role may not. Every view has
an `organizationId` column, because the guest-token clause filters on it. V571 adds the POS, HR and Manufacturing
views. POS sales follow the POS reports (POS invoices posted, paid or refunded). The HR views carry no personal data a
dashboard does not need: no pay per employee, bank, tax, pension or contact details; payroll is summed per run and
department (`BiDashboardIT` fails if such a column appears in any `bi` view).

**Set up (once per installation).**

1. Fill the `SUPERSET_*` block of `.env` (see `.env.example`). Use long random values for `SUPERSET_SECRET_KEY` and
   `SUPERSET_GUEST_TOKEN_SECRET`. Set `SUPERSET_FRAME_ANCESTORS` to the ERP web origin.
2. `docker compose --profile bi up -d --build`. `superset-db-init` creates the `superset` metadata database (owned by
   `superset_meta`) and gives `superset_reader` its password. `superset` starts on port 8088 and creates the admin,
   the `EmbedViewer` guest role and the `EmbedIssuer` account the API uses.
3. Provision the dashboards and prove isolation with two real organization ids:

   ```bash
   SUPERSET_URL=http://localhost:8088 SUPERSET_ADMIN_PASSWORD=... \
   BI_DB_URI='postgresql+psycopg2://superset_reader:<SUPERSET_READER_PASSWORD>@db:5432/erp' \
   SUPERSET_EMBED_ALLOWED_DOMAINS='https://erp.example.com' BI_CHECK_ORGS='<org-a> <org-b>' \
   node tools/superset-provision/provision.mjs --check-isolation
   ```

   Copy the printed `SUPERSET_DASHBOARD_<MODULE>=` lines into `.env`. Set `SUPERSET_URL=http://superset:8088` and
   `SUPERSET_PUBLIC_URL` to the address browsers use, then restart the API.

**Change a dashboard.** Edit `tools/superset-provision/dashboards/<module>.json` and run the script again; it updates
in place. To add a module:

1. Add its views to a new migration, each with `organizationId`.
2. Add a `BiModule` to `SupersetEmbedService.MODULES` and a `dashboards.<module>` key to `application.yml`.
3. Add a spec file. A chart may carry `"where"`, a SQL filter for that chart only.
4. Add the view names to `BI_VIEWS` in the script.
5. If the module has no built-in analytics screen, it shows at `/analytics/bi/<module>` with no web change; add its
   icon to `ICONS` in `BiModuleDashboardPage.tsx`.

**Never** expose a dashboard whose isolation check failed. Run the check again before changing the Superset image tag.

## Alerts as an integration point

Alerts raise `alert.raised` once per type and scan. It is rule-eventable, so a communication rule can route it. See
[`spec/alerts.md`](spec/alerts.md).

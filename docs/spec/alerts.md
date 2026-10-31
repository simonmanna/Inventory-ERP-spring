# Alerts — `alerts`

| | |
|---|---|
| Phase | Track C Phase 5 |
| Nest source | none, new module |
| Design | [ADR-S021](../adr/ADR-S021-scheduled-alerts.md) |
| Routes | 7, under `/alerts` |
| Feature flag | `ENABLE_ALERTS` → `app.modules.alerts`; `VITE_ENABLE_ALERTS` shows the menu entry |
| Java package | `com.inventoryerp.alerts` (`spi` is a named interface) |
| Migration | `V110__alerts.sql` |
| Permissions | `alerts:read`, `alerts:manage` |
| Screen | `/alerts`: alerts, exception report, settings |

## Alert types

| Type | Scan | Threshold (default) | Repeat | Channels | Sources |
|---|---|---|---|---|---|
| `stock_below_reorder` | hourly | none | daily | e-mail | `StockAlertSource`: open replenishment alerts, and products with no rule below `minQuantity` |
| `weighbridge_exception` | hourly | hours waiting (0) | daily | in-app, e-mail | `FleetAlertSources.WeighbridgeExceptions` |
| `curing_release_due` | hourly | hours ahead (24) | daily | in-app | `ProductionAlertSources.CuringReleaseDue` |
| `yield_below_threshold` | daily | minimum % over 7 days (90) | weekly | in-app, e-mail | `ProductionAlertSources.YieldBelowThreshold`: per crushing plant and block product |
| `expiring_document` | daily | lead days (30) | weekly | in-app, e-mail | `FleetAlertSources.Documents` (own lead days), `QuarryLicenceAlertSource`, `HrExpiryAlertSource` |
| `overdue_maintenance` | daily | days late (0) | weekly | in-app, e-mail | `FleetAlertSources.Services`, `AssetMaintenanceAlertSource` |
| `credit_limit_breach` | daily | % of limit (100) | weekly | in-app, e-mail | `CreditLimitAlertSource` |
| `budget_overrun` | daily | % of budget (100) | weekly | in-app, e-mail | `BudgetAlertSource`, `ProcurementBudgetAlertSource` |
| `capa_overdue` | daily | none | weekly | in-app, e-mail | `CapaOverdueAlertSource` |

Stock starts on e-mail alone because the replenishment scan already rings the bell when an alert opens.

Recipients: users holding `recipientPermission` (default `alerts:read`, at most 200) plus `recipientUserIds`. Inactive
and deleted users are skipped. In-app and e-mail honour each user's notification preferences, category `alerts`.

## Routes

| Route | Permission | Purpose |
|---|---|---|
| `GET /alerts/rules` | `alerts:read` | Every type: definition, what is in force, `customized` |
| `PUT /alerts/rules/{type}` | `alerts:manage` | Replace a type's settings; audited |
| `GET /alerts/events` | `alerts:read` | `alertType`, `severity`, `status`, `from`, `to`, `page`, `pageSize` |
| `GET /alerts/events/export.csv` | `alerts:read` | The same filters as CSV, at most 50 000 rows |
| `POST /alerts/events/{id}/acknowledge` | `alerts:read` | Someone saw it; audited. Does not silence the subject |
| `GET /alerts/exception-report` | `alerts:read` | Per type: total, critical, open, last raised; `from`, `to` |
| `POST /alerts/run` | `alerts:manage` | Scan now, optionally `{alertType}`. Sends nothing twice |

## Jobs

| Lock | Cron | Default |
|---|---|---|
| `alerts-hourly-scan` | `ALERTS_HOURLY_CRON` | `0 10 * * * *` |
| `alerts-daily-scan` | `ALERTS_DAILY_CRON` | `0 30 6 * * *` |

Metrics: `alerts.raised`, `alerts.failed` (tag `type`).

## Events

`alert.raised`, once per type and scan, in the claim's transaction, only when something new was claimed. It is
rule-eventable. `AlertWhatsAppSubscriber` in `communication` sends it when the rule lists `whatsapp`, once per alert and
user.

## Add an alert

Add a type to `AlertTypes.ALL`, then a bean implementing `AlertSource` in the module that owns the data. Keep the subject
key stable, add the state to it when a change of state must alert again, and keep `collect` read-only and
`@Transactional(readOnly = true)`.

## Status

Built, not compiled and not run. Thresholds and channels are per organization and per type; recipients are per type.
Labels and threshold help come from the server in English.

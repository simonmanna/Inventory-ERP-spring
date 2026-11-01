# Customization platform (`com.inventoryerp.customization`, `kernel.apiclients`)

Extension (no NestJS counterpart), roadmap 2.5, design: `docs/adr/ADR-S029-customization-platform.md`, migration
`V221__customization_platform.sql`.

## Routes (`/api/v1`)

| Verb | Path | Permission |
|---|---|---|
| GET | `/custom-fields/entities` | `customization:read` |
| GET / POST | `/custom-fields/definitions?entity&includeInactive` | read / `customization:manage` |
| PATCH / DELETE | `/custom-fields/definitions/:id` (delete = deactivate) | `customization:manage` |
| GET / PUT | `/custom-fields/values/:entity/:recordId` (`{values}`; null clears a key) | `custom_record:read` / `custom_record:write` |
| GET / POST | `/custom-objects` | `custom_record:read` / `customization:manage` |
| GET / PATCH | `/custom-objects/:key` | `custom_record:read` / `customization:manage` |
| GET / POST | `/custom-objects/:key/records?search&f.<field>=value&page&pageSize` | `custom_record:read` / `custom_record:write` |
| GET / PATCH / DELETE | `/custom-objects/:key/records/:id` | `custom_record:read` / `custom_record:write` |
| GET / POST | `/print-templates?documentKind` | `print_template:read` / `print_template:manage` |
| GET | `/print-templates/fields/:documentKind` | `print_template:read` |
| POST | `/print-templates/preview` | `print_template:read` |
| GET | `/print-templates/render/:documentKind/:recordId?templateId&format=html\|pdf` | `print_template:read` |
| GET / PATCH / DELETE | `/print-templates/:id` | read / manage |
| GET | `/print-templates/:id/versions` | `print_template:read` |
| POST | `/print-templates/:id/versions/:version/restore` | `print_template:manage` |
| GET | `/automation-rules/catalog` | `automation:read` |
| GET / POST | `/automation-rules` | `automation:read` / `automation:manage` |
| GET / PATCH / DELETE | `/automation-rules/:id` | read / manage |
| GET | `/automation-rules/:id/runs` | `automation:read` |
| POST | `/automation-rules/:id/test` (`{recordId}`, dry run) | `automation:manage` |
| GET | `/api-clients`, `/api-clients/scopes`, `/api-clients/:id` | `api_client:read` |
| POST / PATCH / DELETE | `/api-clients`, `/api-clients/:id` (delete = revoke) | `api_client:manage` |
| POST | `/api-clients/:id/rotate-secret` | `api_client:manage` |
| POST | `/oauth/token` (form or JSON, HTTP Basic accepted; RFC 6749 errors) | public, throttled |

Entities for custom fields: `partner`, `product`, `sales_invoice`, `credit_note`, `vendor_bill`, `purchase_order`,
`sales_order`, `sales_quotation`, `deal`, `asset`, `task`, and `x_<key>` per custom object.
Automation triggers: `invoice.posted`, `creditnote.issued`, `bill.posted`, `partner.created`, `sales.order.confirmed`,
`sales.order.cancelled`, `purchase_order.approved`. Actions: `notify`, `email`, `webhook`, `set_custom_field`,
`create_task`.

## Client tokens

`type: client` JWTs carry the granted scopes; the guard grants their intersection with the client's live scopes and
refuses routes without `@RequirePermissions` ("API clients can only call permission-protected routes").

## Tests

`CustomizationIT` (6): custom fields, custom objects, print templates (HTML, PDF, preview, versions, default),
automation (run, idempotent redelivery, dry run), API clients (token, scopes, user routes closed, rotate, revoke),
authorization matrix (41 routes). `TemplateEngineTest` (5).

## Deviations

None (extension). Web: `/settings/custom-fields`, `/custom-objects/:key`, `/settings/print-templates`,
`/settings/automation`, `/settings/api-clients`; custom-fields panel on the partner overview.

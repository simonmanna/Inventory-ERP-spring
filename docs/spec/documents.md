# Documents (DMS) — `documents`

| | |
|---|---|
| Phase | P3c |
| Nest source | `reference/nest-api/src/modules/documents` |
| Routes | 28 — full table: [generated/documents.md](generated/documents.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.documents` |

## Purpose

Generic document registry: documents with lines, types and lifecycles (state machines), templates, relations and relation chains, sources, attachments, versions/snapshots, timeline, actions, print logs, PDF/HTML preview and printing.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| documents | list, get, patch, preview, print, timeline, actions/:action |
| templates | list, create, activate |
| relations | list, chain, create, delete |
| attachments/sources | per document |
| types/lifecycles | CRUD |

## Business rules to reproduce exactly

1. Lifecycle transitions validated against `DocumentLifecycle`/`DocumentTransition`; each transition writes `DMSWorkflowLedger` + `LifecycleEvent`.
2. Template rendering: Nest uses a headless browser pool; Spring uses Thymeleaf → HTML and OpenPDF (or Playwright-Java if pixel parity is required — ADR needed).
3. DMS registry seeded by `seedDmsRegistry` (port into SeedRunner).

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

document lifecycle events.

## Scheduled jobs

—

## Notes and pitfalls

- 36 spec files — port them; they define lifecycle semantics.

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/documents-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [x] `docs/PORT_STATUS.md` updated

## Deviations

Pending owner approval:

1. **A4 PDF rendering** (`GET /:id/preview?format=a4`, `POST /:id/print`): Nest drives the system Chrome
   (`--print-to-pdf`); Java renders the substituted HTML's text with OpenPDF. `content`
   (`PDF <n> bytes (seal-reproducible)`) and `base64` therefore differ in bytes; html / thermal / plain
   output is identical (same `{{key}}` substitution, ported verbatim).

Same as Nest, kept on purpose:

- **Route shadowing**: Express matches in registration order, so `GET /documents/templates` is served by
  `GET /documents/:id` (404 `Document not found`) and `GET /documents/types/categories` by
  `GET /documents/types/:code` (404 `Unknown document type code: categories`). The Java handlers delegate the
  same way; the intended list endpoints exist as service methods only.
- Reversal / reissue counterparts carry no lines (the engine maps `doc.lines`, which it never loads).
- `DocumentRelation` has no unique index, so duplicate relations are accepted (the 409 branch is unreachable).
- Lifecycle actions and prints resolve `document:<type>:<action>` → `document:<category>:<action>` →
  `document:<action>`; Nest reads the grants from the JWT, Java from the role tables (`PermissionService`) —
  same set.

Registry seeding: `DmsRegistrySeeder` upserts `classpath:dms/registry.json` on every boot and appends the DMS
keys to system roles (also as a demo-seed contributor). The JSON is exported from the Nest
`dms.seed-data.ts` by `node tools/dms-registry/export.mjs` — re-run it when the Nest seed changes.

## Status

P3c: 28/28 routes. Tests: `DmsRulesTest` (ports of `dms.lifecycle`, `dms.relations`, `dms.snapshot`
(seal + render byte-checked against the Nest code), `dms.seed-validation`, `document-permissions` specs),
`DocumentsIT` (registry + type admin, route shadowing, engine idempotency / guards / version lock / snapshot /
outbox fact, reversal counterpart + CANCELS, patch, detail, list, preview, print / replay / reprint reason,
templates, relations + chain, sources, attachments validation, tenant isolation, authorization matrix = 28
routes). `dms-type-resolver.service.spec.ts` is covered by the existing resolver usage in invoicing. The
remaining Jest files listed in the generated spec are cross-module integration suites (POS / procurement /
HR / sync) ported with their phases. Parity scenario: `contract-tests/scenarios/documents-dms.json`.

## INT-3 label printing (Phase 4, built, unverified: nothing compiled or run)

`GET /labels/sources/{type}` (`product, stockpile, ticket`; `search`) and `POST /labels/render` (`type, ids, copies, format` =
pdf or zpl, `symbology` = qr or code128), both `label:print` (`V82__label_printing.sql`). `LabelRenderer` draws 100 × 60 mm
labels: a PDF with vector bars from a ZXing matrix for any printer, or a ZPL II file for a 203 dpi Zebra printer, with `^` and
`~` removed from values. Screen: `/labels`. Precast pallet labels stay as they are. No direct print to a network printer.


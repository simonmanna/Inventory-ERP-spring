<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
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

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/documents-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.

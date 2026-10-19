<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
# Communication — `communication`

| | |
|---|---|
| Phase | P7 |
| Nest source | `reference/nest-api/src/modules/communication` |
| Routes | 22 — full table: [generated/communication.md](generated/communication.md) |
| Feature flag | `ENABLE_COMMUNICATION` |
| Java package | `com.inventoryerp.communication` |

## Purpose

Conversations, messages, attachments, templates, rules, channels (internal, Telegram bot webhook, WhatsApp Cloud API), SSE stream, dispatch worker with rate limits.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| ~22 routes | see generated table |

## Business rules to reproduce exactly

1. Dispatch worker polls `CommunicationDispatch` (COMM_DISPATCH_POLL_MS), respects `WA_MIN_GAP_MS`/`WA_DAILY_CAP`.
2. Channel credentials encrypted with `COMM_ENCRYPTION_KEY`.
3. Telegram webhook is public, verified by channel secret.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

—

## Scheduled jobs

communication dispatch poller.

## Notes and pitfalls

- **Deviation (approved in plan):** Baileys (unofficial WhatsApp Web) has no Java port — support WhatsApp Cloud API only; `WHATSAPP_TRANSPORT=baileys` is rejected at startup with a clear message.

## Porting checklist

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/communication-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.

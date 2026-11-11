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

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/communication-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [x] `docs/PORT_STATUS.md` updated

## Deviations

1. `WHATSAPP_TRANSPORT=baileys` is rejected at startup (no Java port of Baileys). Only the WhatsApp Cloud API transport
   is supported. Approved in the plan.
2. Channel credentials (`botToken`, `webhookSecret`, `accessToken` in `CommunicationChannel.config`) are sealed at rest
   as `enc:v1:<iv:tag:ciphertext>` (AES-256-GCM, `COMM_ENCRYPTION_KEY`, base64 of 32 bytes), to meet
   `docs/SECURITY.md`. Nest stores them as plain JSON. Plain values that Nest wrote still read, so a cut-over needs no
   data migration. Other config keys stay plain.
3. `POST /communication/channels` returns those credentials masked as `********`. Nest echoes them as stored.
4. `GET /communication/conversations/:id/messages?limit=<not a number>` clamps to the default of 50. Nest passes `NaN`
   to Prisma and answers 500.
5. The dispatch poller (`MessageDispatchScheduler`) does not run when `app.jobs.enabled=false`, like every other
   Spring cron. `MessageDispatchWorker.tick()` / `drain()` still work when called directly.

## Status

22/22 routes. The controllers are `CommunicationController` and `TelegramWebhookController` (public; authenticated by
the per-channel `X-Telegram-Bot-Api-Secret-Token`). Outbound HTTPS goes through the `ProviderHttp` interface, so tests
make no network calls. Tests:
- `CommunicationRulesTest`: ports of all 5 Jest specs (access rule, dispatch idempotency keys, condition, template
  render, tenancy scoping of every communication entity).
- `ProvidersTest`: credential sealing (round trip, tamper, bad key), Telegram error classification, address
  normalisation, masking.
- `CommunicationIT`: conversations with Idempotency-Key replay, messages and read state, chatter context, template
  versions, rules (validation, toggle, delete, condition, dedupe per event instance), channels (sealed and masked
  credentials, connect/disconnect/remove), Telegram webhook (bad secret, unknown channel, replay dedupe), the dispatch
  worker against a fake Bot API (sent and terminal `blocked`), WhatsApp Cloud API via a fake (inbound partner link, a
  rule messaging the partner, 429 retry-after, ambiguous network retry clamped to 3, 5xx failure + sender
  notification, read status never downgraded, provider switched off), recipient resolvers, the SSE inbox stream with `?access_token=`, tenant
  isolation, and the authorization matrix (22).

Parity scenario `contract-tests/scenarios/communication-inbox.json` is written but has not yet been run against
Nest. Web screens have not yet been smoke-tested against Spring.

## Discuss — staff and department communication (ADR-S064, extension: no Nest counterpart)

[ADR-S064](../adr/ADR-S064-discuss-staff-communication.md) adds an Odoo-style staff messenger on top of the ported
module. Every new route is under `/communication/discuss`; the 22 Nest routes keep their contract. The one change
they see is that a send to an archived conversation is refused with "This conversation is archived.", and archived
state can only be set through Discuss.

- Channels: public or private, `#general` (auto-join), one channel per HR department (members synced hourly after
  the first `POST /discuss/departments/sync`). Plus 1:1 direct messages (one per pair) and group chats.
- Members and roles (owner, admin, member). Moderators are the owner, admins, and holders of
  `communication:channel:manage`. Join, leave, invite, remove, rename, visibility, archive.
- Messages: @mentions (inbox item plus in-app notification), replies, emoji reactions, pins, personal stars, edit,
  soft delete, attachments (draft upload, then attach; signed URLs), paging by `seq`, search.
- Per-member notify level (all, mentions, none), mute, and pin. Unread and badge counts. A personal inbox
  (mentions, followed records). DM notifications for offline members.
- Record chatter for `SalesOrder`, `SalesQuotation`, `PurchaseOrder`, `Invoice`, `Expense`: followers, messages vs
  log notes, gated by the record's read permission.
- Realtime on `/communication/stream`: `message.updated`, `message.deleted`, `conversation.updated`, `typing`,
  `read`, `presence`, `inbox.new`. `message.created` also carries `discuss`.
- V443 grants `communication:conversation:read|write` and `communication:message:send` to every existing role.

Code: `DiscussController`, `DiscussService`, `DiscussMessageService`, `DiscussRecordService`, `DiscussViews`,
`DiscussEvents`, `DepartmentChannelSync`, `DiscussDepartmentSyncJob`. Tests: `DiscussIT`.

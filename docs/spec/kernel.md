# Kernel (platform) — `kernel`

| | |
|---|---|
| Phase | P1 |
| Nest source | `reference/nest-api/src/kernel`, `reference/nest-api/src/health`, `reference/nest-api/src/observability` |
| Routes | 82 — full table: [generated/kernel.md](generated/kernel.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.kernel` |

## Purpose

Identity, staff, tenancy, authorization, idempotency, settings, audit, events/outbox, approvals, files, notifications, feature flags & module loader, recurring documents, webhooks, global search, reports access, three-way-match service, sequences, workflow/lifecycle registries, health and metrics.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| auth | `/auth/*`: login, mfa-login, refresh, forgot/reset/change password, mfa enroll/verify/disable, me, sessions (list/revoke), permissions catalog |
| staff | `/roles` CRUD; `/users` CRUD + reset-password, PIN set/clear, unlock |
| settings | `/settings*`: scoped key/value (org/branch/user) with registry + resolver (`setting-registry.ts`, `setting-resolver.service.ts`), company, features, developer, inventory-defaults, effective view |
| approvals | `/approvals`, `/approval-policies`, `/approval-workflows`: multi-step approval engine used by expenses, procurement, cash variance |
| audit | `/audit-logs` read; `AuditService.log(entity, entityId, action, old, new)` called by every write service |
| files | `/files`: upload (multipart), list, signed URL, download, delete |
| notifications | `/notifications`, `/push`: in_app/email/sms/push, preferences, VAPID subscribe |
| feature flags / modules | `/feature-flags`, `/modules/*`: per-org module enablement (`OrganizationModule`), `@RequiresModule` guard |
| recurring | `/recurring`: recurring documents with pause/resume/end; tick job every 10 min |
| webhooks | `/webhooks/endpoints`, deliveries; HMAC-signed delivery, retry job every minute, secret rotation |
| search | `GET /search` global search across partners/products/documents |
| reports access | `/reports/access/*`: which report keys (`packages/shared/src/reports.ts`) each role may see |
| three-way match | `/procurement/three-way-match/*` (service lives in kernel in Nest; port into procurement module, keep paths) |
| health | `/health`, `/health/live|ready|startup` (done in P0) |

## Business rules to reproduce exactly

1. Login requires `organizationCode`; user must belong to that org, be active, not locked. 10 failed attempts → `lockedUntil = now + 15 min`; successful login resets the counter. Record every attempt in `LoginAttempt` (ip, user agent, success, reason).
2. MFA: if `mfaSecret` is set, login returns `{ mfaToken, requiresMfa: true }`; the mfaToken (24 random bytes, base64url) is stored hashed as an `IdempotencyRecord` row `mfa:<sha256>` (status `pending`, 5-minute window); `mfa-login` validates TOTP then issues tokens.
3. Refresh token rotation: each refresh revokes the old row and sets its `replacedById`; presenting an already-rotated (revoked) token returns 401 `Invalid or expired refresh token`. (The Nest code does **not** revoke the rest of the chain on reuse; kept identical.)
4. POS session check (`pos-session-check.ts`): valid `X-Pos-User` overrides actor; revoked/deactivated POS user → 401 + `X-Pos-Session: revoked` except on PIN routes.
5. POS eligibility (`pos-eligibility.ts`): which roles/users may PIN-login on a terminal (port spec `pos-eligibility.spec.ts`).
6. Role policy (`role-policy.spec.ts`): system roles cannot be deleted/renamed; a user cannot grant permissions they do not hold; last administrator cannot be removed.
7. Permissions guard requires ALL listed permissions; reads permissions from DB unless `PERMISSIONS_DB_LOOKUP=false`.
8. Idempotency: see CONTRACT.md §6; port `idempotency.service.ts`, `business-outcome.ts` and both specs exactly.
9. Tenancy: port `tenancy.extension.ts` semantics — which models are org-scoped, soft-delete filter, and the explicit escape hatches (`raw` client) — into the Hibernate filter setup.
10. Sequences: per-org per-document-type Postgres sequences with optional yearly key `:YYYY`; formatted `prefix + padStart(padding, "0")` (`sequence.service.ts`).
11. Read-only middleware: `READ_ONLY_MODE=true` rejects non-GET.
12. Rate limiting: `StaffAwareThrottlerGuard` 100/60 s, 600/10 min keyed by staff id when authenticated.
13. Encryption service: AES-GCM with IV + tag columns for MFA secrets — must decrypt existing data.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

Emits `user.registered`, `user.logged_in`, approval events; outbox worker ships `EventOutbox` rows to webhooks.

## Scheduled jobs

outbox-ship 30 s, webhooks-retry 1 min, recurring-tick 10 min, cleanup-notifications hourly, cleanup-tokens daily.

## Notes and pitfalls

- Access tokens must NOT carry permissions (header size); login body still returns `permissions[]`.
- Seed (`SeedRunner`) belongs to this phase so the web login works on a fresh Spring DB.
- Organization bootstrap (`POST /organizations/bootstrap`) lives in core but creates kernel rows (roles, admin user, settings) — coordinate.

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/kernel-*.json` — zero unexplained diffs
- [x] Web screens for this module work against Spring (no console errors)
- [x] `docs/PORT_STATUS.md` updated

## Implementation notes (Nest behaviour reproduced on purpose)

- `@Idempotent` routes (e.g. `POST /auth/login`) answer **200**, not 201: the Nest interceptor overwrites the
  route status with the handler result status (200) or the cached status on replay.
- Guards run after routing, in Nest order: unknown route -> 404 `Cannot GET /api/v1/...`; missing token ->
  401 `Authentication required`; missing permission -> 403 `Missing required permission(s): a, b`;
  `@RequiresModule` -> 403. The throttler (100/60 s + 600/10 min, x5 for signed-in staff, per route) runs last.
- HttpExceptions constructed with a plain string (`new HttpException('...', 403)`, `ThrottlerException`)
  are rendered by the Nest global filter as a bare JSON string body; reproduced.
- `PermissionsGuard` DB mode is the only mode (access tokens no longer carry permissions, CONTRACT section 7).
  Permissions are cached per user for at most 60 s and evicted on any role/user write.
- Validation errors are Nest-shaped (`message: string[]`, class-validator wording). An unknown property is
  reported alone (`property x should not exist`), not together with other violations.
- `POST /auth/reset-password` with an invalid token is an unhandled error (500) in Nest (`throw new Error`); kept.
- Webhook retries: like Nest, the tick only picks `pending` deliveries (a `failed` delivery is not retried).
- `POST /push/subscribe` requires `notifications:write`, which is not in the permission catalog (Nest); kept.
- Global search: the pg_trgm pass fails (the extension is not in the schema) and falls back to the substring
  hits, exactly like Nest.
- `/procurement/three-way-match/*` is ported in P4 together with the procurement tables.

## Deviations

Bug fixes where the Nest code could never work (pending owner approval):

1. **MFA login / enrollment lookups** - Nest looked the pending `IdempotencyRecord` up with
   `organizationId: ''`, so `mfa-login` always answered `MFA token invalid or expired` and `mfa/verify`
   always `No MFA enrollment in progress`. Spring looks the row up by its unique key (`mfa:<sha256>`; for
   enrollment the caller's organization + key). Messages and flow are otherwise identical.
2. **MFA secret decryption** - Nest's `EncryptionService.decrypt` compared a 3-byte prefix with the 2-byte
   `v1` tag (always failing) and `readMfaSecret` passed an empty IV/tag. Spring writes the identical format
   (scrypt key, AES-256-GCM, `base64("v1"+ct)`, IV/tag columns) and decrypts it correctly; a unit test
   decrypts a ciphertext produced by `node:crypto` with the Nest parameters.
3. **Seed (P1 subset)** - `SeedRunner` seeds the kernel/login-critical part of `prisma/seed.ts` 1:1
   (currencies, permissions, DEMO org, Administrator, admin user, taxes, categories, partners, fiscal year,
   branch, table zones, staff roles + users + PINs, kitchen stations, POS tables). UoM/products/stock, chart
   of accounts, menu, DMS registry and approval workflows are added by the phases that own those modules.
4. **Entity listener** - assigns `organizationId` on persist for org-scoped entities (Nest tenancy
   extension) and refuses cross-organization updates; it does not auto-fill `createdBy`/`updatedBy` (Nest
   services set those explicitly; auto-filling would change the rows written).

## Status

P1 done: 78/82 routes (+17 routes the generator missed). Open: three-way-match (P4), `/metrics` token gate (P8).

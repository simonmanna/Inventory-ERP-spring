<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
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
2. MFA: if `mfaEnabled`, login returns `{ mfaToken, requiresMfa: true }`; the mfaToken (24 random bytes, base64url) is stored hashed as a `Setting` row `mfa:<sha256>` with short expiry; `mfa-login` validates TOTP then issues tokens.
3. Refresh token rotation: each refresh marks the old row `replacedById`; presenting an already-replaced token revokes the whole chain (reuse detection) and returns 401.
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

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/kernel-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.

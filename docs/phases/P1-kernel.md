# Phase P1 — Kernel

Copy-paste brief for the agent doing this phase.

---

You are implementing **phase P1 (kernel)** of Inventory-ERP-spring, a Spring Boot 4 / Java 25
rebuild of the NestJS POS-CAFE ERP. Read `AGENTS.md`, `docs/CONTRACT.md`, `docs/ARCHITECTURE.md`,
`docs/SECURITY.md`, `docs/DATABASE.md`, `docs/spec/kernel.md` and `docs/spec/generated/kernel.md`
before writing code. The NestJS source in `reference/nest-api/src/kernel` is the specification.

## Deliverables (in this order, one PR each where sensible)

1. **Tenant context + security chain**
   - `TenantContext` (orgId, userId, posUserId, branchId, deviceId) as a request-scoped holder.
   - `JwtTokenService` (access/pos sign+verify, HS256, 3 secrets) and `RefreshTokenService` (opaque,
     sha256, rotation, reuse detection) — port `jwt-token.service.ts`, `refresh-token.util.ts` + spec.
   - Security filter chain: bearer → access token; `X-Pos-User` → POS token (same org, revocation →
     401 + `X-Pos-Session: revoked` except PIN routes; port `pos-session-check.ts`); `X-Device-Token`
     only on `/sync/**`; `access_token`/`pos_token` query params only on the 3 SSE paths.
   - `@Public` equivalent = permitAll matchers generated from the route table "Public" column.
   - `@RequirePermissions(String... all)` annotation + interceptor/AuthorizationManager reading
     permissions from DB (cached Caffeine 60 s, evicted on role/user change). Port `permissions.guard.ts`
     (incl. its 403 messages) and `PERMISSIONS_DB_LOOKUP`.
   - `@RequiresModule` + `ModuleEnabledGuard` behaviour.
   - Startup secret validation in `prod` profile.
2. **Hibernate tenancy**: `@FilterDef tenant` + `softDelete`, enabled per request for org-scoped
   entities (port the model list from `prisma/tenancy.extension.ts`), entity listener assigning
   `organizationId`/`createdBy`/`updatedBy`, `SET LOCAL app.org_id` per transaction.
3. **Idempotency**: servlet filter + `IdempotencyService` exactly per CONTRACT.md §6 (incl.
   `OUTCOME_ROUTES`, abandoned-after-2-min, `business-outcome.ts`), separate transaction for the
   pending row; port `idempotency.required.spec.ts`, `business-outcome.spec.ts`.
4. **Auth endpoints** `/auth/*` (login with lockout + LoginAttempt, MFA with TOTP + AES-GCM secret
   decrypt-compatible with Nest `encryption/`, refresh, forgot/reset with OneTimeToken + mail,
   change password, me, sessions list/revoke, permissions catalog).
5. **Staff**: `/roles`, `/users` (+ PIN, unlock, reset-password) — port `role-policy.spec.ts`,
   `pos-eligibility.spec.ts`.
6. **Cross-cutting**: rate limiting (Bucket4j, staff-aware), read-only mode filter, audit service +
   `/audit-logs`, settings registry/resolver + `/settings*`, sequence service, outbox writer
   (`EventOutbox`, `DomainEventLog`) + outbox-ship job + Modulith publication, ShedLock config.
7. **Files** (`/files`, local + real S3 driver), **notifications** (`/notifications`, `/push`, mail via
   Spring Mail + Thymeleaf, SMS via Twilio, web push via VAPID), **approvals** (`/approvals`,
   policies, workflows), **feature flags/modules**, **recurring**, **webhooks** (HMAC signature as Nest,
   retry job), **search**, **reports access**.
8. **SeedRunner** (profile `seed`) porting `reference/nest-api/prisma/seed.ts` 1:1 (can be completed
   module by module, but org/roles/users/permissions/currencies/branch are needed now).
9. **Contract-test harness** first working version (`contract-tests/README.md`): kernel scenarios.

## Exit criteria
- `./gradlew build` green; all kernel Jest specs ported.
- With `docker compose up -d db`, `./gradlew bootRun --args='--spring.profiles.active=dev,seed'`
  then `pnpm web:dev`: log in as `admin@demo.test` (org `DEMO`), PIN-login a cashier, refresh token
  rotation works, logout works; no console errors from kernel endpoints.
- Authz matrix + tenant isolation tests for kernel routes.
- Kernel parity scenarios: zero unexplained diffs.
- `docs/PORT_STATUS.md` kernel rows ticked.

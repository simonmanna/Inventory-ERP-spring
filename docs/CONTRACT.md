# Wire Contract (non-negotiable)

The React client (`web/`) and the Android POS were written against the NestJS API. The Spring API
must be indistinguishable on the wire. Every rule here is derived from the reference source; the
file named in each section is authoritative if this text is ever ambiguous.

Clients that depend on this contract: `web/src/lib/api.ts`, `web/src/lib/api-error.ts`,
`web/src/lib/idempotent-request.ts`, `web/src/features/pos/offline-queue.ts`,
`web/src/features/tables/sse.ts`, `web/src/features/communication/sse.ts`,
`docs/legacy/sync-protocol.md` (Android).

---

## 1. Base path and routing

- Global prefix **`/api/v1`** for every application route (`ApiPathConfig`). Swagger UI at
  `/api/docs`; Prometheus at `/api/v1/metrics`.
- Same path, same HTTP verb, same path-variable names as Nest. Full list: `docs/spec/routes.json`.
- Nest resolves static segments before params when declared first (`/usage` before `/:id`). Spring
  MVC prefers literal segments automatically — still verify each such pair in tests.
- Known duplicate: `/fiscal-periods` CRUD is declared by both `core/fiscal-period.controller.ts` and
  `accounting/posting/fiscal-period-crud.controller.ts`. Nest registers the core module first, so
  **core's handlers win**; implement CRUD once (in accounting) with core's behaviour, plus
  `close/lock/reopen`.
- Trailing slashes: not used by the client; do not rely on them.

## 2. Request conventions

- JSON bodies, UTF-8. Max body 2 MB (`BODY_LIMIT`); uploads up to `UPLOAD_MAX_BYTES` (multipart).
- Validation = Nest `ValidationPipe({ whitelist, forbidNonWhitelisted, transform, enableImplicitConversion })`:
  - **Unknown body properties → 400** (`FAIL_ON_UNKNOWN_PROPERTIES` is on globally).
  - Query strings are converted to the declared type (numbers, booleans `"true"/"false"`).
  - Rules per field are listed in `docs/spec/generated/<m>.md` → "DTOs".
- Headers the client sends:
  | Header | When | Meaning |
  |---|---|---|
  | `Authorization: Bearer <access>` | always after login | back-office identity + organization |
  | `X-Pos-User: <posToken>` | POS terminal after PIN login | acting cashier (overrides actor, same org) |
  | `Idempotency-Key: <uuid>` | every POST/PUT/PATCH/DELETE | see §6 |
  | `X-Device-Token`, `X-Device-Label` | Android/offline device | `/sync/*` only |
  | `X-Request-Id` | optional | propagated, echoed back |

## 3. Responses and errors

- **No envelope.** Return the resource / array / object exactly as Nest does.
- Field names camelCase exactly as Prisma/Nest (entity field names = column names).
- Status codes: POST → **201** unless the handler has `@HttpCode` (route table "Status" column) or is
  `@Idempotent` behind the IdempotencyInterceptor (those answer **200**, or the cached status on replay);
  GET/PUT/PATCH/DELETE → 200; SSE → 200 `text/event-stream`.
- **Error body** (port of `kernel/filters/global-exception.filter.ts`):
  ```json
  { "statusCode": 400, "message": "Human readable message", "error": "Bad Request" }
  ```
  - Bean-validation failures: `message` is a **string array** (one entry per violation,
    `"<field> <reason>"`). The client joins arrays with `"; "`.
  - Business errors: `message` copied **verbatim** from the Nest `throw new XxxException('...')`
    (catalogue per module in `docs/spec/generated/<m>.md` → "Error messages").
  - Nest exceptions thrown with an object (`new ConflictException({ code, message, ... })`) render
    that object as the body; reproduce with `ApiException(status, message, extra)`.
  - Unique violation that escapes a service → **409**
    `{ "message": "This operation conflicts with one that was just completed. Refresh and try again.", "statusCode": 409, "requestId": "..." }`.
  - Unhandled → **500** `{ "message": "Internal Server Error", "statusCode": 500, "requestId": "..." }`
    (+ `error`, `name` only when `EXPOSE_ERROR_DETAIL=true`).
  - 401 (no/invalid token on a protected route) → `{ "message": "Authentication required", "error": "Unauthorized", "statusCode": 401 }`
    (Nest `JwtAuthGuard`; guards run after routing, so an unknown route is a 404 even without a token);
    403 (permission) → `{ "message": "Missing required permission(s): a:b, c:d", "error": "Forbidden", "statusCode": 403 }`
    (`permissions.guard.ts`).
  - Unknown route → 404 `{ "statusCode": 404, "message": "Cannot GET /api/v1/x", "error": "Not Found" }`.
- Client behaviour you must not break: 401 triggers one refresh + retry; 403 and 5xx show a toast;
  a response with **no status** (network failure) flips the POS to offline mode.

## 4. Lists and pagination

- Standard query (`packages/shared/src/pagination.ts`, `kernel/common/pagination.dto.ts`):
  `page` (≥1, default 1), `pageSize` (1..1000, default 25), `search`, `sortBy`, `sortOrder` (`asc|desc`).
- Standard result: `{ "data": [...], "meta": { "page", "pageSize", "total", "totalPages" } }` and
  header **`X-Total-Count`** (exposed via CORS). Java: `PageQuery` / `PageResult`.
- Not every list uses it: some return a plain array, some `{ items }`, some `{ data, hasMore }`.
  **Copy the shape per endpoint** from the Nest service return value.
- Module-specific filters (status, dates, partnerId, ...) keep their Nest query names.

## 5. JSON value formats

| Type | Wire format | Java |
|---|---|---|
| Money / quantity (`Decimal`) | **string**, plain, no trailing zeros: `"12.5"`, `"0"`, `"1500"` | `BigDecimal` (serialized by `JsonConfig`) |
| Timestamp (`DateTime`) | ISO-8601 UTC with ms: `"2026-10-19T08:15:30.123Z"` | `Instant` |
| Date (`@db.Date`) | `"2026-10-19"` | `LocalDate` |
| Enum | DB value string: `"not_paid"`, `"dine_in"`, `"AVCO"` | generated `DbEnum` (`@JsonValue value()`) |
| Json column | embedded JSON (object/array), not a string | entity `String` → map to `JsonNode`/record in DTO |
| null | included as `null` (Prisma returns nulls) | default Jackson inclusion |
| Integer/Float | number | `Integer`/`Long`/`Double` |
| IDs | UUID strings | `String` |

Requests: money may arrive as number or numeric string — accept both (`BigDecimal` binding).
Timestamps should be emitted with millisecond precision to match Prisma (`Instant` truncated to ms).

## 6. Idempotency (port of `kernel/idempotency/*`)

- Every write may carry `Idempotency-Key`; the client always sends one.
- **Required** (400 when missing) only where Nest has `@Idempotent({ required: true })`. The regex below
  (`OUTCOME_ROUTES`, `idempotency.service.ts`) does not make a key required; it marks routes whose outcome is
  saved in the business transaction (abandoned-retry and cached-4xx rules):
  ```
  /(pos/(checkout|tabs/[^/]+/settle|orders/[^/]+/settle|split-bills/[^/]+/settle|invoices/[^/]+/(payments|refund)|sales/[^/]+/void|shift/handover)|cash-sessions/(open|close|movement|tender-settlements|[^/]+/(banking|force-close|reconcile))|accounts/cash-flow/(deposit|withdraw)|treasury/transfer|expenses(/[^/]+/(pay|void))?|payments(/[^/]+/void)?|supplier-payments)$
  ```
  plus every handler marked `@Idempotent()` (route table "Idem" column).
- Record: table `IdempotencyRecord`, unique `(organizationId, key)`, `requestHash = sha256(method + path + rawBody)`.
- Algorithm:
  1. Existing **completed** + same hash → replay stored `statusCode` + body; handler not run.
  2. Existing completed + **different hash** → 409.
  3. Existing **pending** → 409 `{ "code": "OPERATION_PENDING", "message": "This operation needs recovery; do not submit it with a new key. Retry with the same key.", "operationKey": key, "recovery": <stored responseJson> }`.
     Money route pending > 2 min with empty outcome = abandoned → delete and re-run.
  4. None → insert pending row (unique constraint = lock), run handler, store response, mark completed.
     Handler failure → the pending row is **kept** for recovery (same key again → 409 `OPERATION_PENDING`),
     except a definitive 4xx on an OUTCOME route with no saved outcome, cached as completed with `safeToRetry: true`.
- The "business outcome" for money routes is written in the **same transaction** as the money
  (`business-outcome.ts`) so a crash never leaves money without an outcome. Port this exactly.
- The sync push path uses `executeWithKey(opId)` per op (§10).

## 7. Authentication (port of `kernel/auth/*`, `kernel/tenancy/*`)

- `POST /auth/login` body `{ organizationCode, email, password }` (all required; `email` must be an email).
  - Success: `{ accessToken, refreshToken, user: { id, email, firstName, lastName, roles[] }, permissions[], organization?: { id, code, name, currencyCode, timezone } }`.
  - MFA enrolled: `{ mfaToken, requiresMfa: true }` → client calls `POST /auth/mfa-login { mfaToken, code }`.
  - 10 consecutive failures lock the account for 15 minutes (`MAX_FAILED_ATTEMPTS`, `lockedUntil`);
    every attempt recorded in `LoginAttempt`. Message texts verbatim from `auth.service.ts`.
- Tokens (HS256):
  | Token | Secret | TTL | Claims / storage |
  |---|---|---|---|
  | access | `JWT_ACCESS_SECRET` | 15m | `{ sub, organizationId, email, type: "access" }` — **do not embed permissions** (Nest did; the guard reads them from DB anyway; this keeps headers small) |
  | refresh | opaque 48 random bytes | 7d | sha256 in `RefreshToken`, rotation via `replacedById`; reuse of a rotated token revokes the chain |
  | pos | `JWT_POS_SECRET` | 12h | `{ sub, organizationId, type: "pos" }`, sent as `X-Pos-User` |
  | device | opaque | until revoked | sha256 in `PosDevice`, header `X-Device-Token`, `/sync/*` only |
- `POST /auth/refresh { refreshToken }` → `{ accessToken, refreshToken, user, permissions, organization }`
  (rotated; a rotated/revoked token → 401).
- POS identity: when `X-Pos-User` is valid and in the same organization as the bearer, the acting
  user = POS user. If the POS user was revoked/deactivated → **401 with header `X-Pos-Session: revoked`**
  (exposed via CORS) except on the PIN screens (`pos-session-check.ts`). The client then returns to
  the PIN pad without refreshing the bearer.
- SSE endpoints (`/pos/tables/stream`, `/pos/kds/stream`, `/communication/stream`) accept
  `?access_token=` and `?pos_token=` query parameters because `EventSource` cannot send headers.
  **Only** on these three paths.
- Public routes (no auth): `/health*`, `/auth/login|mfa-login|refresh|forgot-password|reset-password`,
  `/menu/public/**`, `/communication/webhooks/telegram/:channelId`, and others marked `@Public()`
  (route table "Public" column).

## 8. Authorization

- `@RequirePermissions('a:b', 'c:d')` = caller must hold **all** listed permissions. Strings exactly as
  in `Permissions.java` (generated). Permissions come from the user's roles in the DB (cached ≤ 60 s,
  evicted on role/user change), not from the token.
- `@RequiresModule('x')` → 404/403 behaviour exactly as `module-enabled.guard.ts` when the module is
  disabled for the organization (`OrganizationModule`) or by `ENABLE_*`.
- Rate limit (per route): 100 / 60 s and 600 / 10 min per IP when anonymous; per user and x5
  (`THROTTLE_STAFF_MULTIPLIER`) when signed in; login/mfa-login 10 / 5 min, forgot-password 5 / 15 min.
  429 body is the bare JSON string `"ThrottlerException: Too Many Requests"` (the Nest global filter renders
  string HttpException responses as-is) with `Retry-After`.

## 9. Real-time (SSE)

- `GET /pos/tables/stream` → `data: {"type":"snapshot","tables":[...],"stats":{...}}` on connect and on
  every change; heartbeat comment every 25 s.
- `GET /pos/kds/stream` and `GET /communication/stream`: same pattern; payloads per Nest gateway code.
- Implement with `SseEmitter` (timeout 0, heartbeat via scheduler), publish from after-commit events.
  nginx must keep `proxy_buffering off` (already in `infra/docker/nginx.conf`).

## 10. Offline sync (`/sync/*`) — Android + offline web

Full protocol: `docs/legacy/sync-protocol.md` and `reference/nest-api/src/modules/sync`. Summary:
- `POST /sync/devices/register { name, platform: android|web, branchId? }` (perm `organization:update`)
  → device token returned once.
- `GET /sync/pull?cursor=&scopes=` → per-scope watermark cursor (base64 JSON, server clock, 5 s
  overlap); tombstones via `deletedAt`.
- `POST /sync/push` (`X-Device-Token`, `Idempotency-Key: <batchId>`) body
  `{ deviceId, ops: [{ opId, deviceSeq, type, actorUserId, occurredAt, payload }] }` →
  per-op `{ opId, status: applied|replayed|failed, httpStatus, error, mapping, finalNumbers }`.
  ~40 op types (`SYNC_OP_TYPES`), client-id mapping, 424 on failed dependency, provisional → final
  numbers, dead letters block cash-session close. "A sale is never blocked."

## 11. Offline web POS queue

`web/src/features/pos/offline-queue.ts` replays queued operations FIFO with the **original body
and original `Idempotency-Key`** to `/pos/checkout`, `/pos/tabs/{id}/settle`, `/pos/tabs/{id}/items`
(append-only). 2xx = done; 4xx (except 401/403/408/409/429) = failed; others retried. Manager
overrides first call `POST /pos/operation-approval`. Reachability probe: unauthenticated
`GET /api/v1/health` with 3 s timeout — must stay cheap and DB-free.

## 12. CORS

Allow-list from `CORS_ORIGINS` (comma separated); with `CORS_ALLOW_LAN=true` (or in dev) also any
private-LAN origin (10/8, 172.16/12, 192.168/16, localhost). Allowed headers: `Authorization,
Content-Type, Idempotency-Key, X-Pos-User, X-Device-Token, X-Device-Label, X-Request-Id`. Exposed:
`X-Request-Id, X-Total-Count, X-Pos-Session`. No credentials (cookies) are used.

## 13. Read-only mode

`READ_ONLY_MODE=true` (cloud read replica) rejects every non-GET with the body/status of
`kernel/common/read-only.middleware.ts`.

## 14. Health

- `GET /health` and `/health/live` → `{ "status": "ok", "timestamp": "..." }` (no DB).
- `GET /health/ready`, `/health/startup` → `{ status, criticalOk, timestamp, durationMs, checks, critical, advisory }`,
  503 when a critical check fails; backup health is advisory (P6).

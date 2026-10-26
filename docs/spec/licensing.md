# Licensing — `licensing` + `license-server`

| | |
|---|---|
| Phase | P-L1 … P-L4 (new; not part of the Nest port) |
| Nest source | — (no equivalent; **out of parity scope**) |
| Routes | 4 client + 6 public + 24 admin — tables below |
| Feature flag | always on (the licence gates everything else) |
| Java package | `com.inventoryerp.kernel.licensing` (client) · `com.inventoryerp.license` (server) |
| Decision record | [ADR-S002](../adr/ADR-S002-licensing.md) |

## Purpose

Sell the ERP as daily / monthly / yearly / trial / perpetual subscriptions, with per-feature and
per-module activation and caps on devices, users and organizations. One central licence server
issues and revokes; every install verifies its licence **offline** and keeps trading through
network outages, degrading to read-only only after the grace window has passed.

---

## 1. Model

### Licence lifecycle

```
pending ──issue──> active ──expiresAt──> grace ──graceUntil──> read_only
                     │ ▲                   │                      │
            suspend  │ │ resume            └───── renew ──────────┘
                     ▼ │                            │
                 suspended                          ▼
                     │                            active
                  revoke
                     ▼
                  revoked  (terminal, read-only immediately, no grace)
```

Client-side `LicenseState.mode`: `UNLICENSED | TRIAL | ACTIVE | GRACE | READ_ONLY | REVOKED`.
`UNLICENSED` (never activated) behaves as `TRIAL` for `app.license.trial-days` (default 14) counted
from the first boot recorded in `LicenseInstallation.createdAt`, then `READ_ONLY`.

### Periods

| `Plan.period` | `periodCount` | `expiresAt` | Default grace | Heartbeat |
|---|---|---|---|---|
| `DAY` | 1…n | `startsAt + n days` | 1 day | 1 h |
| `MONTH` | 1…n | `startsAt + n months` | 7 days | 6 h |
| `YEAR` | 1…n | `startsAt + n years` | 14 days | 24 h |
| `TRIAL` | n days | `startsAt + n days` | 0 | 6 h |
| `PERPETUAL` | — | `null` (never expires) | — | 24 h (telemetry only) |

Renewal extends from `max(now, expiresAt)` so a customer never loses paid days by renewing early.
Month arithmetic clamps to the end of a short month (31 Jan + 1 month = 28/29 Feb).

### Entitlements

Feature keys reuse the existing ones in `web/src/lib/features.ts` and the `@RequiresModule`
manifest names, so no new vocabulary: `crm`, `rental`, `manufacturing`, `repair`, `communication`,
`assets` / `fixed-asset`, `taskBoard` / `task`, `accounting`, `expense`, `hr`, `beverage`.
Add-ons that are not sidebar sections get their own keys: `multiOrg`, `androidPos`, `apiAccess`,
`webhooks`, `advancedReports`, `backupOffsite`.

**Effective = licensed ∩ enabled.** A plan grants the ceiling; Developer Settings
(`/settings/developer`) may switch a licensed feature off but can never switch an unlicensed one on.

| Licensed | Developer setting | Result |
|---|---|---|
| yes | absent / true | on |
| yes | false | off |
| no | anything | off, and the toggle renders disabled with "not included in your plan" |

### Limits

| Limit | Counted as |
|---|---|
| `devices` | `PosDevice` rows with `isActive = true` across all orgs |
| `users` | `User` rows active and not soft-deleted |
| `organizations` | `Organization` rows with status `active` |

Exceeding a limit **never** flips the install to read-only. The *creating* route fails with 402 and
a verbatim message (§5), everything already created keeps working. Counts are reported on every
heartbeat so the server can show over-limit customers for upsell.

---

## 2. Token format

Compact JWS, `alg: EdDSA` (Ed25519), header `{alg, typ: "JWT", kid}`.

```json
{
  "iss": "inventoryerp-license",
  "sub": "ERP-7K4Q-2M8X-9ZTP-4C1W",
  "aud": "f3c9…(instance fingerprint, sha256 hex)",
  "jti": "018f…(activation id)",
  "iat": 1761350400,
  "nbf": 1761350400,
  "exp": 1764633600,
  "lic": {
    "customer": "Acme Cafe Ltd",
    "customerId": "018f…",
    "plan": "pro-monthly",
    "period": "MONTH",
    "periodCount": 1,
    "status": "active",
    "startsAt": "2026-10-25T00:00:00Z",
    "expiresAt": "2026-11-25T00:00:00Z",
    "graceUntil": "2026-12-02T00:00:00Z",
    "refreshAfter": "2026-10-26T06:00:00Z",
    "limits": { "devices": 5, "users": 25, "organizations": 1 },
    "features": ["crm", "accounting", "expense", "androidPos", "webhooks"],
    "modules": ["crm"],
    "offline": false,
    "serverTime": "2026-10-25T09:13:22Z"
  }
}
```

Rules:

1. `exp` equals `lic.graceUntil` (or 100 years out for `PERPETUAL`) so verification keeps succeeding
   throughout grace. **Mode is derived from `lic.expiresAt` / `lic.graceUntil`, never from `exp`.**
2. `aud` must equal the local fingerprint. A mismatch is not a hard failure: the install enters
   `GRACE` with reason `fingerprint_mismatch` and prompts for re-activation (covers DB restores).
3. `lic.serverTime` feeds the clock high-water mark used for tamper detection.
4. An offline-issued token sets `offline: true`, gets no `refreshAfter`, and has
   `exp = expiresAt + graceDays` exactly like an online one.
5. Tokens are opaque to the web client — it reads `/license/status`, never the JWS.

### Key management

Ed25519 keypairs live in `SigningKey`; the private half is encrypted at rest with
`LICENSE_SIGNING_PASSPHRASE` (AES-256-GCM, PBKDF2-SHA256, same parameters as `kernel/auth/EncryptionService`).
Rotation issues a new `kid`, signs new tokens with it, and keeps retired public keys served in the
JWKS for the length of the longest outstanding token. Clients ship the current public keys compiled
in (`license-keys.json` on the classpath) and refresh the JWKS opportunistically on heartbeat,
pinning by `kid`; an unknown `kid` with no reachable JWKS keeps the previous valid state and warns.

---

## 3. Central server — `license-server/`

Own Gradle build, own database (`license`, `localhost:5435` in compose), Flyway `V1__license.sql`.
Prefix `/api/v1`. Virtual threads, springdoc, Micrometer, Bucket4j, ShedLock — same stack list as
the ERP (ADR-S001 §1), no additions.

### Schema

| Table | Columns (essentials) |
|---|---|
| `Customer` | id, name, email, phone, country, taxId, notes, createdAt, updatedAt |
| `Product` | code (`erp`), name, description |
| `Plan` | id, code, name, productCode, period, periodCount, priceMinor, currency, maxDevices, maxUsers, maxOrganizations, features jsonb, graceDays, isTrial, isActive, sortOrder |
| `License` | id, key (unique), customerId, planId, status, issuedAt, startsAt, expiresAt, graceDays, autoRenew, maxDevices/maxUsers/maxOrganizations (nullable overrides), featureOverrides jsonb, notes, createdBy |
| `LicenseActivation` | id, licenseId, fingerprint, instanceName, hostname, appVersion, status (`active`/`deactivated`), firstSeenAt, lastSeenAt, deactivatedAt — unique (licenseId, fingerprint) |
| `LicenseHeartbeat` | id, activationId, at, deviceCount, userCount, orgCount, appVersion, ip, mode — retention 90 days |
| `LicenseEvent` | id, licenseId, activationId, type, at, actor, detail jsonb — append-only audit |
| `SigningKey` | id, kid, algorithm, publicKey, privateKeyEncrypted, activeFrom, retiredAt |
| `AdminUser` | id, email, passwordHash (bcrypt cost 12), totpSecretEncrypted, role (`owner`/`support`), isActive, lastLoginAt |

`LicenseEvent.type`: `issued, activated, reactivated, deactivated, heartbeat_gap, renewed, suspended,
resumed, revoked, grace_entered, read_only_entered, limit_exceeded, tamper_suspected, offline_token_issued`.

Licence key format `ERP-XXXX-XXXX-XXXX-XXXX`, Crockford base32 (no I/L/O/U), last group is a
CRC-8 check so a mistyped key fails locally before any request.

### Client-facing routes (authenticated by the licence key itself; Bucket4j 10 req/min/IP)

| Verb | Path | Body → Result |
|---|---|---|
| POST | `/activate` | `{licenseKey, fingerprint, instanceName, hostname, appVersion}` → `{token, kid, refreshAfter}` · 404 unknown key · 403 revoked/suspended · 409 activation limit reached |
| POST | `/heartbeat` | `{token, deviceCount, userCount, orgCount, appVersion, mode}` → `{token, refreshAfter}` (sliding renewal) · 403 revoked |
| POST | `/deactivate` | `{licenseKey, fingerprint}` → 204, frees the activation slot |
| GET | `/.well-known/license-keys.json` | JWKS of active + retired-but-unexpired public keys (public, cacheable 1 h) |

An install may hold **one** activation per fingerprint; the number of distinct active fingerprints
per licence is capped at `instanceLimit` (default 1, raised for multi-site customers).

### Admin routes (`/admin/**`, admin JWT + optional TOTP, all audited)

| Area | Routes |
|---|---|
| auth | `POST /admin/auth/login`, `POST /admin/auth/refresh`, `POST /admin/auth/logout` |
| customers | `GET|POST /admin/customers`, `GET|PUT|DELETE /admin/customers/{id}` |
| plans | `GET|POST /admin/plans`, `GET|PUT /admin/plans/{id}` |
| licences | `GET|POST /admin/licenses`, `GET|PUT /admin/licenses/{id}` |
| actions | `POST /admin/licenses/{id}/{renew|suspend|resume|revoke}` |
| offline | `POST /admin/licenses/{id}/offline-token` `{fingerprint, instanceName}` → `.lic` attachment |
| forensics | `GET /admin/licenses/{id}/{activations|heartbeats|events}`, `DELETE /admin/activations/{id}` |
| dashboard | `GET /admin/dashboard` — counts by status, expiring in 7/30 days, over-limit installs, recurring revenue estimate, installs silent > 3× their heartbeat interval |
| keys | `GET /admin/keys`, `POST /admin/keys/rotate` |

Responses are plain records; list routes use the same `{items,total,page,pageSize}` envelope as
`PageResult` in the ERP so the shared TS types can be reused.

---

## 4. Client — `kernel/licensing`

### Schema (ERP database, Flyway `V{next}__licensing.sql`)

| Table | Notes |
|---|---|
| `LicenseInstallation` | **single row**, install-scoped (no `organizationId` — the documented exception to AGENTS.md §8): id, fingerprint, licenseKey, token, tokenKid, claims jsonb, mode, status, startsAt, expiresAt, graceUntil, refreshAfter, lastCheckAt, lastCheckOk, lastCheckError, serverTimeHighWater, createdAt, updatedAt |
| `LicenseAuditLocal` | append-only local transitions: at, fromMode, toMode, reason, detail jsonb |

### Fingerprint

`sha256( installId + ":" + pg_control_system().system_identifier )`, hex. `installId` is a UUID
created on first boot and kept in `Setting` under `license.installId`. Stable across container
rebuilds and IP changes; changes when the database is restored into a different cluster.

### Classes

| Class | Responsibility |
|---|---|
| `LicenseFingerprint` | derive and cache the fingerprint |
| `LicenseKeySet` | classpath public keys + opportunistic JWKS refresh, pinned by `kid` |
| `LicenseVerifier` | JWS verify (nimbus-jose-jwt), claim shape validation, `aud` check |
| `LicenseStore` | load / upsert the singleton row, write `LicenseAuditLocal` |
| `LicenseStateMachine` | claims + now + high-water → `LicenseState` (pure, heavily unit-tested) |
| `LicenseService` | `current()`, `activate(key)`, `applyOfflineToken(jws)`, `refresh()`, `deactivate()` |
| `LicenseSnapshot` | 60 s Caffeine cache of `LicenseState`, invalidated on any write |
| `LicenseEntitlements` | reconcile `OrganizationModule` + `FeatureFlag` rows from `lic.features` for every org |
| `LicenseHeartbeatJob` | `@Scheduled` at the plan's interval, ±10 % jitter, ShedLock `license:heartbeat` |
| `LicenseEnforcementInterceptor` | the 402 gate (§5) |
| `RequiresFeature` | `@RequiresFeature("crm")` — 402 when the feature is not licensed |
| `LicenseController` | `/license/**` (§6) |
| `LicenseLimits` | `assertCanAddDevice()` / `assertCanAddUser()` / `assertCanAddOrganization()`, called by the POS device, staff and org create services |

`LicenseState` record: `mode, status, plan, customer, features (Set), modules (Set), limits, usage,
startsAt, expiresAt, graceUntil, daysRemaining, offline, lastCheckAt, lastCheckError, fingerprint`.

### Clock-tamper rule

`serverTimeHighWater` keeps the newest `lic.serverTime` ever seen. If local `now` is more than 1 h
behind it, the install logs `tamper_suspected`, treats `now` as the high-water mark for mode
calculation, and forces an online check before leaving `GRACE`.

---

## 5. Enforcement

Registered in `WebConfig` **after** `AccessGuardInterceptor`, so 401/403 still win over 402 and an
unknown route is still 404.

| Mode | Reads | Writes |
|---|---|---|
| `ACTIVE`, `TRIAL` | allowed | allowed |
| `GRACE` | allowed | allowed, `X-License-Warning: grace` header + banner |
| `READ_ONLY`, `REVOKED`, expired `UNLICENSED` | allowed | **402** except the allowlist below |

Always allowed, in every mode:

- `GET`, `HEAD`, `OPTIONS` (any path)
- `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/mfa/**`
- `/license/**`
- `/health/**`, `/actuator/**`, `/metrics`
- `/sync/push` and `/sync/pull` — a terminal must be able to drain queued offline sales; losing a
  shop's takings to a billing state is never acceptable
- POS "finish what is open": `POST /pos/shifts/{id}/close`, `POST /pos/sessions/{id}/reconcile`,
  `POST /pos/orders/{id}/pay`, `POST /pos/orders/{id}/void`. Creating a *new* order is blocked.
- **Draining the web POS offline queue.** A replay to `/pos/checkout`, `/pos/operation-approval`,
  `/pos/cash-operations` or `/pos/sessions` carrying `X-Offline-Recorded-At: <ISO instant>` earlier
  than the moment writes stopped (`graceUntil`, or the trial's end) is allowed and logged. The
  Android till gets this for free because `/sync/**` is allowed outright; the web terminal replays
  to the ordinary POS routes, so it has to say what it is doing.

  The timestamp comes from the client, so a patched client could backdate one — accepted
  deliberately. Anyone able to patch the app can already push sales through `/sync/push`, so it opens
  no new hole, and what it buys is that an ordinary shop never loses a day's trade to an expiry
  nobody noticed.

402 body (so the web client can render one dialog for every case):

```json
{
  "statusCode": 402,
  "message": "Your licence has expired. The system is read-only until it is renewed.",
  "license": { "mode": "READ_ONLY", "status": "expired", "expiresAt": "…", "graceUntil": "…", "plan": "pro-monthly" }
}
```

Messages (the authoritative list; copied verbatim into `LicenseMessages`):

| Situation | Message |
|---|---|
| grace expired | `Your licence has expired. The system is read-only until it is renewed.` |
| revoked | `This licence has been revoked. Contact your supplier.` |
| suspended | `This licence is suspended. Contact your supplier.` |
| never activated, trial over | `The trial period has ended. Activate a licence to continue.` |
| feature not licensed | `'<feature>' is not included in your licence.` |
| device limit | `Your licence allows <n> POS device(s). Deactivate a device or upgrade your plan.` |
| user limit | `Your licence allows <n> user(s). Deactivate a user or upgrade your plan.` |
| organization limit | `Your licence allows <n> organization(s). Upgrade your plan to add more.` |
| activation: unknown key | `Licence key not recognised.` |
| activation: wrong instance | `This licence is already activated on another installation.` |
| activation: offline token mismatch | `This licence file was issued for a different installation.` |

---

## 6. ERP routes (`/license`, additive — ADR-S001 §7)

| Verb | Path | Permission | Purpose |
|---|---|---|---|
| GET | `/license/status` | authenticated | full `LicenseState` + usage, for the banner and the settings page |
| GET | `/license/fingerprint` | `settings.manage` | the string a customer sends us for an offline licence |
| POST | `/license/activate` | `settings.manage` | `{licenseKey}` → online activation against the central server |
| POST | `/license/offline` | `settings.manage` | `{token}` (pasted or uploaded `.lic`) → verify + store |
| POST | `/license/refresh` | `settings.manage` | force a heartbeat now |
| POST | `/license/deactivate` | `settings.manage` | release the activation (migrating to new hardware) |

`GET /license/status` is also folded into the `/sync/pull` response (`license` key) so the Android
POS learns its mode without a second round trip.

---

## 7. Clients

### Web

- `web/src/features/license/api.ts` + `useLicense()` (TanStack Query, 60 s stale).
- `web/src/pages/settings/LicensePage.tsx` — state chip, plan, customer, expiry countdown, usage
  bars against each limit, activated devices, activate-by-key form, offline paste/upload,
  fingerprint with copy button, "Check now".
- `LicenseBanner` in `components/layout/app-shell.tsx`: amber when `daysRemaining <= 14` or `GRACE`,
  red and persistent in `READ_ONLY` / `REVOKED`.
- `lib/api.ts`: a 402 branch that opens the blocking licence dialog instead of a toast.
- `lib/features.ts`: `sectionEnabled(features, licensedFeatures, title)` — unlicensed sections are
  hidden, and unlicensed toggles on `/settings/developer` render disabled with the plan hint.

### Android

- `/sync/pull` carries `license`; `DeviceConfig` caches `mode` and `expiresAt`.
- `HomeScreen` / `TerminalScreen` show a read-only banner; starting a new sale is blocked in
  `READ_ONLY` while closing a shift and syncing stay available.

---

## 8. Tests

| Level | Coverage |
|---|---|
| unit | `LicenseStateMachineTest` — every transition, boundary seconds around `expiresAt`/`graceUntil`, backwards clock, fingerprint mismatch, perpetual, trial; `LicensePeriodTest` — day/month/year arithmetic incl. month-end clamping and early renewal; `LicenseKeyTest` — format + CRC; `LicenseVerifierTest` — good token, wrong signature, unknown `kid`, tampered claims, wrong `aud` |
| integration (Testcontainers) | `LicenseActivationIT` — activate → heartbeat → renew → suspend → resume → revoke, each asserting the resulting mode; `LicenseEnforcementIT` — one case per row of the §5 matrix, including the POS and sync allowlist; `LicenseEntitlementsIT` — reconciler writes the expected `OrganizationModule`/`FeatureFlag` rows and `@RequiresModule` follows; `LicenseOfflineIT` — issue offline token on the server, upload to the ERP, verify |
| authz | 402/403 matrix for all six `/license` routes |
| server | `LicenseAdminIT` (CRUD + actions + audit rows), `SigningKeyRotationIT`, `ActivationLimitIT`, rate-limit test |
| arch | ArchUnit: nothing outside `kernel.licensing` touches `LicenseInstallation`; no private key type on the ERP classpath |

Coverage gate: ≥ 80 % lines on `kernel.licensing` and `com.inventoryerp.license.application`
(AGENTS.md §7).

## 9. Security rules

1. The private key never leaves the licence server; the ERP ships public keys only.
2. Licence keys are logged masked (`ERP-…-4C1W`); tokens are never logged.
3. `/activate`, `/heartbeat` and `/admin/auth/login` are rate-limited (Bucket4j).
4. Admin passwords are bcrypt cost 12; TOTP available for the `owner` role.
5. Heartbeat telemetry carries counts and versions only — no customer, product or sales data.
6. The licence server is reachable over HTTPS only; the ERP pins the JWKS by `kid`, not by host.

## 10. Phases

| Phase | Deliverable |
|---|---|
| P-L1 | `license-server/` — schema, plans, licences, activate/heartbeat/deactivate, JWKS, key rotation, admin CRUD + actions, offline tokens, tests |
| P-L2 | `kernel/licensing` — fingerprint, verifier, state machine, store, entitlement reconciler, heartbeat job, enforcement interceptor, `/license` routes, limits, tests |
| P-L3 | Web — licence page, banner, 402 handling, feature-ceiling in `features.ts` |
| P-L4 | Android read-only mode; admin dashboard (expiring, silent installs, over-limit); renewal reminder emails |

## Deviations

Not applicable — no Nest behaviour is being reproduced. Parity harness scenarios are not required
for this module; `docs/PORT_STATUS.md` is unaffected.

## Status

All four phases implemented, 2026-10-26.

| Phase | Where | State |
|---|---|---|
| P-L1 authority | `license-server/` | **done** — 10 tables, Ed25519 signing with rotation, client + 30 admin routes, offline tokens, renewal reminders, dashboard. 78 tests green (`LicenseServerIT` walks sell → issue → activate → check in → renew → suspend → resume → revoke over real HTTP on Testcontainers Postgres). |
| P-L2 install | `backend/.../kernel/licensing` | **done** — fingerprint, offline verifier, state machine, entitlement reconciler, heartbeat job, 402 gate, six `/license` routes, seat caps. 60 unit + 25 integration tests green. |
| P-L3 web | `web/src/features/license`, `pages/settings/LicensePage.tsx` | **done** — licence page, banner, global 402 dialog, licence ceiling on the sidebar and on Developer Settings. Typecheck and lint clean. |
| P-L4 Android + ops | `android/.../LicenseBanner.kt`, `docker-compose.yml`, `.github/workflows/ci.yml` | **done** — licence carried on `/sync/pull`, persisted on the device, banner on the till, new sales blocked while the queue still drains. Compose profile, Dockerfile and a CI job for the authority. |

### Fixed after the first live deployment (2026-10-26)

1. **Online activation and check-in never worked.** `LicenseServerClient` read the authority's
   answer with `EntityJson.parseJson`, which returns a `JsonNode`, then tested it for `Map` — so every
   2xx answer was "unreadable" and reported as unreachable, while the authority had in fact
   activated the install. Every other test stubbed the authority above the client.
   `LicenseServerClientTest` now runs it against a real socket.
2. **Suspend and revoke did not reach an install.** The authority answered a suspended or revoked
   licence's heartbeat with a bare 403, and the install keeps the token it holds on any refusal, so
   it traded on until that token's grace ran out (a year on a yearly plan). The heartbeat now hands
   back a signed token with `lic.status` = `suspended` / `revoked`, which the state machine already
   maps to `READ_ONLY` / `REVOKED`. A revoked licence gets it even though revoking released its slots.
   `/activate` still refuses both with 403.
3. **The licence was not a ceiling for vertical modules.** The reconciler only switches existing
   `OrganizationModule` rows off, and the module guard (Nest parity) still allows a tenant with no
   rows at all, or any module whose `ENABLE_*` flag is set. `LicenseEnforcementInterceptor` now
   refuses a `@RequiresModule` route whose feature the held licence lacks (402,
   `'<feature>' is not included in your licence.`), reads included, as `@RequiresFeature` does.
4. `license-server/gradlew` was committed without its executable bit, so the authority's Docker
   image never built; `Dockerfile.license` now `chmod`s it as well.

### Deliberate deviation from the frozen contract

`GET /sync/pull` gained a `license` member (ADR-S001 freezes the ported routes; this is additive and
existing members keep their order and spelling). The Android client already parses with
`ignoreUnknownKeys`, and the field is nullable there so a terminal still works against a server that
does not send it. Parity scenarios for `sync` should expect the extra member.

### Known gaps, deliberately left

1. **Three add-on feature keys have no enforcement point yet**: `apiAccess`, `backupOffsite` and
   `multiOrg`. They are sellable and appear in plans, but the features they would gate are either
   not implemented (offsite backup is a package note only) or have no route to gate (there is no
   organization-create route — organizations are seeded). `LicenseLimits.assertCanAddOrganization()`
   exists and is tested, waiting for that route. Gating them for show would have been worse than
   saying so: a key that looks enforced and is not is how a licence quietly stops meaning anything.
2. **68 new UI strings are registered but untranslated** in `ti`, `lg` and `sw`
   (`web/src/lib/i18n/phrases/*.json`, empty values → English fallback). `pnpm web:i18n` therefore
   fails until a translator fills them. Machine-guessing a lockout message in three languages is not
   a risk worth taking: a cashier mis-told that the till is dead is a support call at best.
3. ~~`license-keys.json` ships empty.~~ Filled 2026-10-26 with the production authority's first
   key (`kid 9a76fdc6…`). Refill it from `GET /admin/keys/pinned` after every key rotation.
4. **Clocks are defended in one direction only.** Winding a clock back cannot buy term (the
   high-water mark); winding it forward only expires the licence sooner, which costs the attacker,
   so it is not defended against.
5. **`DashboardService.overLimitInstalls()` scans every activation.** Fine at a few thousand
   licences, and it is one admin screen; it wants a query rather than a loop before it is tens of
   thousands.

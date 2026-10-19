# Security

Target: OWASP ASVS 4.0 Level 2 for the API, plus the POS-specific controls below. Port every
control that exists in Nest; the items marked **(new)** are improvements Spring adds.

## 1. Authentication
- Passwords: bcrypt cost 10 (compatible with existing `passwordHash` values from bcryptjs `$2a$/$2b$`).
  `DelegatingPasswordEncoder` not needed — use `BCryptPasswordEncoder(10)`.
- PINs: bcrypt in `User.pinHash` (`pinHashRounds` column honoured); PIN login rate-limited per
  terminal; PIN change requires current PIN (port `pos-auth` service rules).
- Lockout: 10 failures → 15 min (`failedLoginCount`, `lockedUntil`), `LoginAttempt` audit rows.
- MFA: TOTP (`dev.samstevens.totp`, 30 s, 6 digits, ±1 window as otplib default), secret encrypted
  AES-256-GCM with `APP_ENCRYPTION_KEY` — must decrypt existing Nest ciphertexts (port
  `kernel/encryption` IV/tag layout exactly).
- Tokens: HS256 with three distinct secrets ≥ 32 bytes. **(new)** startup fails in `prod` profile if a
  secret is missing, shorter than 32 bytes, or equals a dev default.
- Refresh tokens: opaque, sha256-at-rest, rotation, reuse detection revokes the whole chain,
  session list/revoke (`/auth/sessions`). Cleanup job daily.
- Password reset: one-time tokens (`OneTimeToken`), sha256-at-rest, single use, short expiry; the
  response never reveals whether the email exists.

## 2. Authorization
- Every route carries the same `@RequirePermissions` as Nest (generated table is the checklist).
- Routes without explicit permissions still require authentication unless `@Public()` in Nest.
- Permission lookup from DB (roles → permissions), cached per user ≤ 60 s, evicted on role change.
- Manager overrides (`/pos/override`, `PosApprovalGrant`): grant bound to one `operationKey`,
  re-checked on replay, expires; approver must hold the required permission and cannot be the
  requester where Nest forbids it (variance approval, handover).
- **Authorization matrix test** per module: each route × (no token → 401, wrong permission → 403,
  right permission → 2xx).

## 3. Tenant isolation
- `organizationId` always from the verified token, never from the request body/query.
- Hibernate tenant filter + entity listener + native-SQL review rule (ARCHITECTURE.md §6).
- RLS backstop with non-superuser DB role in production (DATABASE.md §6).
- **Cross-tenant test suite**: two orgs seeded; every list/get/update/delete route must not leak or
  touch the other org (404, never 403-with-data).

## 4. Input and output
- Bean Validation on every DTO; unknown fields rejected; string length caps where Nest has them;
  `@Valid` on nested collections; max page size 1000.
- Uploads: size limit, content sniffing (Tika-lite magic bytes) vs declared type, random storage
  names, no path traversal (`Path.normalize` + prefix check), download via signed URL / auth.
- SQL: parameters only; no string concatenation of user input into native SQL (`sortBy` must be
  whitelisted per entity — Nest whitelists too).
- Errors: no stack traces/SQL in prod responses (`EXPOSE_ERROR_DETAIL=false`).

## 5. Transport and headers
- TLS terminated at Caddy/nginx; HSTS, `X-Content-Type-Options`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer` (nginx config); API adds the same via Spring Security headers.
- CORS allow-list only (CONTRACT.md §12).

## 6. Secrets and data protection
- Secrets only from env / Docker secrets; `.env` never committed.
- Log masking: `Authorization`, `X-Pos-User`, `X-Device-Token`, `password`, `pin`, `managerPin`,
  `refreshToken`, `mfaToken`, `code`, card/phone numbers.
- Channel credentials (Telegram/WhatsApp) encrypted at rest with `COMM_ENCRYPTION_KEY`.
- Backups encrypted (AES-256-CBC + PBKDF2 600k, openssl-compatible) and checksummed.

## 7. Abuse controls
- Rate limits: 100/60 s and 600/10 min per staff user/IP (Bucket4j); stricter on `/auth/login`,
  `/auth/forgot-password`, `/pos/auth/pin-login`, `/menu/public/orders` **(new: per-IP 10/min)**.
- Public digital menu: session-bound order submission (`MenuQrSession`), no PII returned.

## 8. Supply chain
- Dependabot (Gradle, npm, GitHub Actions); OWASP dependency-check or Trivy in CI (P8);
  CycloneDX SBOM per release; pinned versions in `libs.versions.toml`.

## 9. Security review checklist (P8)
- [ ] Authz matrix green for all 1,118 routes
- [ ] Cross-tenant suite green
- [ ] Idempotency on all money routes (missing key → 400; replay identical)
- [ ] No secrets in logs (log capture test)
- [ ] RLS enforced on staging with non-superuser role
- [ ] Dependency scan clean / waivers documented
- [ ] Pen-test style checks: JWT alg confusion (`none`/RS→HS) rejected, expired token rejected,
      refresh reuse revokes chain, IDOR on every `:id` route, upload of HTML/SVG served as attachment

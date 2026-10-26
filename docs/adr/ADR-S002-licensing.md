# ADR-S002: Central license server with offline-verifiable signed licenses

- Status: accepted (2026-10-26)
- Related: ADR-S001 (frozen wire contract), `docs/spec/licensing.md`

## Context

The product is sold as a deployed ERP: some customers run it on their own hardware in the shop
(often with poor or no internet), others on a web server we host. The owner needs to

1. sell **daily, monthly, yearly** (and trial / perpetual) subscriptions,
2. keep one **central authority** that can issue, renew, suspend and revoke a licence,
3. sell **features and modules individually** (CRM, Rentals, Production, Repair, HR, …),
4. cap **POS devices, named users and organizations** per licence,
5. keep a shop trading when the internet drops.

Nothing in `reference/nest-api/` covers licensing, so there is no parity obligation — this is new
surface, built Spring-first.

The codebase already has per-tenant gating that the licence must drive rather than duplicate:
`FeatureFlag` + `OrganizationModule` rows, `FeatureFlagsService` (30 s Caffeine cache),
`@RequiresModule` enforced by `AccessGuardInterceptor`, and `web/src/lib/features.ts`.

## Decision

1. **A separate service, `license-server/`**, in this repo: its own Spring Boot 4.1 / Java 25 app,
   its own Postgres database, package root `com.inventoryerp.license`. It is the only place the
   signing private key exists. ERP installs are clients; a customer install can never mint a licence.
2. **A licence is a compact JWS signed with Ed25519 (EdDSA), carrying a `kid`.** Entitlements,
   limits, dates and grace live in the claims. Clients verify **offline** against pinned public keys
   published at `/.well-known/license-keys.json`; no network call is needed to use the product.
3. **Binding is instance + seats.** A licence key activates one install, identified by a
   fingerprint derived from a stored install UUID and the Postgres cluster `system_identifier`.
   Inside that install the licence caps active POS devices, active users and organizations.
4. **Enforcement is grace, then read-only.** `ACTIVE` → `GRACE` (after `expiresAt`, warnings only)
   → `READ_ONLY` (after `graceUntil`): reads succeed, mutations return **402 Payment Required**.
   Login, `/license/**`, health, and a small POS allowlist (close shift, settle an open order,
   `/sync/push`) stay writable so no shop loses an in-progress service or queued offline sales.
5. **The licence is the ceiling, Developer Settings is the switch.** Effective features are
   `licensed ∩ enabled`. On every token change a reconciler rewrites `OrganizationModule` and
   `FeatureFlag` rows, so `@RequiresModule` and the web sidebar keep working untouched.
6. **Offline issuance is first class.** For air-gapped installs, an admin pastes the instance
   fingerprint into the server and gets a `.lic` token file the customer uploads. Same token
   format, same verification path; only the transport differs.
7. **New HTTP surface is additive.** `/license/**` on the ERP and everything on the licence server.
   No ported Nest route changes path, verb, status or JSON, so ADR-S001's freeze holds.

## Alternatives rejected

- *Licence module inside the ERP backend* — the signing key would ship to every customer.
- *Offline `.lic` files only, no server* — no remote revoke, no renewal, no usage telemetry.
- *Online check on every request* — a dropped link would stop a cafe mid-service.
- *Hard block at expiry* — same outage risk, for little extra commercial leverage over read-only.

## Consequences

- One more deployable and one more database to run and back up; `docker compose` gains a
  `license-db` and a `license-api` service, both optional for ERP-only development.
- `nimbus-jose-jwt` (already on the classpath via Spring Security 7) is used for JWS on both sides;
  no framework outside the pinned list is added.
- Restoring a database into a different Postgres cluster changes the fingerprint and drops the
  install into `GRACE` until it is re-activated. Deliberate; the server allows re-binding the same
  key and logs the move.
- A clock rolled backwards past the stored high-water mark is treated as tampering: the install
  forces an online check and logs `tamper_suspected`.
- Licence state is install-scoped, not org-scoped — the one documented exception to
  "every table is filtered by `organizationId`" (AGENTS.md §8).
- The web client must handle 402 globally, and the Android POS must render a read-only banner.

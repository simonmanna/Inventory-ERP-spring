# ADR-S050: Customer portal (`portal`) and how a customer signs in

- Status: Accepted (autonomous, 2026-10-30). Built, unverified: nothing was compiled or run.
- Brief: `AUTONOMOUS_BUILD_CONSTRUCTION.md` Phase 4; requirements lines 452 and 470; gap item PORTAL-1
- Spec: [`docs/spec/portal.md`](../spec/portal.md). Migration: `V80__customer_portal.sql`

## Context

Customers want to follow their orders, deliveries, invoices and statement without phoning the office. The system has no
customer identity: every login is staff, and many routes are open to any signed-in user.

## Decision

1. **A portal login is an ordinary `User`** on the system role "Customer Portal", whose only permission is
   `portal:customer`. Passwords, lockout, refresh rotation, MFA and password reset are the existing auth code, and no
   second token type exists.
2. **`PortalAccount` ties the login to one `Partner`.** `PortalAccountService.current()` reads it on every portal
   request, so the partner never comes from the request and a disabled account stops at once. Several logins may serve one
   customer.
3. **Lock-down in `AccessGuardInterceptor`.** Permissioned routes already refuse such a login. Routes that need no
   permission refuse it too unless marked `@CustomerPortalReachable` (only `AuthController`). The test is "holds exactly
   `portal:customer`", because an Administrator may also hold it through the seeded full permission list.
4. **Read-only over existing data.** New module `com.inventoryerp.portal`; it calls `invoicing.application`
   (`StatementService`, `InvoiceService`, `InvoiceDeliveryService`) and otherwise reads with explicit column lists, so
   costs, freight, margins and internal notes cannot leak by accident. A document of another customer answers 404. Drafts
   stay hidden.
5. **Feature flag `app.modules.portal`** (`ENABLE_PORTAL`, default off), `@RequiresModule("portal")`, manifest
   `PortalModule`. It is not a licence feature yet.

## Consequences

- Each active portal login counts as a licence seat (`LicenseLimits.assertCanAddUser`).
- Portal users appear in the staff user list; their role is a system role.
- Every route open to any signed-in user pays one cached permission lookup; behaviour for staff and POS is unchanged.
- A new route that a portal login should reach must carry `portal:customer` or `@CustomerPortalReachable`.

## Alternatives rejected

- A separate customer token type and filter: a second auth path to secure and test.
- Customer id as a request parameter guarded by a check: one missed check leaks another customer's documents.

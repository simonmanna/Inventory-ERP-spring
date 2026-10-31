# ADR-S017: User data scope by branch and location

- Status: proposed (2026-10-30). Built, **not compiled or run**.
- Related: BR-1, `V62__location_branch_user_scope.sql`, `docs/spec/core.md`

## Context

`BranchScopeService.branchFilter()` was ported from Nest with no callers. It treats `User.defaultBranchId` as a limit.
That column is the branch a user picks for themselves (`/organizations/me/branch`) and HR fills it in, so it is set for
users who see the whole organization today. Wiring it in as written would silently cut their data, which the brief
forbids.

## Decision

1. `InventoryLocation.branchId` (nullable) links a location to a branch.
2. `UserDataScope` holds explicit grants of a branch or a location. No rows means unrestricted, and every caller then
   takes its old path with the same SQL.
3. A branch grant covers its locations; a location grant covers that location and its branch's documents.
4. Fail closed: a row without a branch or location is outside a restricted user's scope.
5. Reads answer 404 or filter; a filter or write outside scope answers 403.
6. Nobody changes their own scope and a restricted user changes nobody's.
7. POS, `/sync/*` and stock posting never read the scope.

## Consequences

Location JSON gains `branchId`. Coverage is partial: see `docs/DECISIONS_FOR_OWNER.md`. The ADR number may need
renumbering if another lane takes S017.

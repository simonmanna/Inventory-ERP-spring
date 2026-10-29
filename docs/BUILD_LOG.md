# Build log
Current: Tier 0 complete except parity runs / Nest retirement; parallel sessions on Tier 1 (A) and Tier 2/4 (B)
Next action: session B — merge the Tier 4 fleet and Tier 2 (2.6, 2.8, 2.9, 2.10) worktree branches as they finish.

## Parallel sessions — read before picking an item

Two autonomous sessions push to `main`. To avoid duplicate work, each claims items here **before** starting them
(commit + push the claim). Do not start an item claimed by the other session; rebase often (`git pull --rebase`).

| Session | Who / where | Claimed items |
|---|---|---|
| A | owner's session (pushed 0.1 B/C/D, 1.8, ADR-S007/S008) | roadmap order: Tier 1 (1.1 → 1.7), then 2.4, 2.1, 2.2, 2.3, 2.5 |
| B | MacBook session (pushed 0.2 repair, 0.3 rental hardening) | **4 fleet, 2.6 BI, 2.8 subscriptions, 2.9 projects, 2.10 portals & payments**; then 2.7, 3.x from the end |

Migrations: session B renumbers its migrations to the next free `V{n}` at merge time, so numbers may jump.

## Environment notes (session B dev box)

- 4 GB MacBook, macOS 12, no running Docker daemon. Builds are serialised (one Gradle daemon at a time).
- Integration tests run **without Docker** against a local Postgres 16.15 (port 5436, `max_locks_per_transaction=1024`):
  `IT_EXTERNAL_DB=true JDBC_DATABASE_URL=jdbc:postgresql://localhost:5436/<db>?stringtype=unspecified DB_USER=postgres
  DB_PASSWORD=postgres ./gradlew test` — `TestcontainersConfiguration` skips the container when `IT_EXTERNAL_DB=true`.
  CI (`.github/workflows/ci.yml`) still runs the Testcontainers suite on every push.

## Items
| Item | Status | Commits | Notes |
|---|---|---|---|
| 0.1 manufacturing | done | 6494df0 4dd311f 3a5d904 d34f3f0 | session A: ADR-S006 packages B, C, D + web screens |
| 0.3 rental | done | 7b3e3af 8ea9a0e | 47/47; `RentalIT` + `RentalLifecycleIT` + `RentalMathTest`; money routes idempotent (deviation) |
| 0.2 repair | done | c5e0355 | 64/64; `RepairIT` (21) incl. idempotency, GL balance, row locks, tenant guards |
| 0.4 communication | done | 40d2b11 | re-verified green in the full suite (669 tests) |
| 0.x parity harness run | blocked | | needs a live Nest + Postgres (Docker); owner machine |
| 0.x Nest retirement | todo | | after the parity run |
| 1.8 landed costing & cost centres | done | ac30fd5 7f4ccc6 5a60daa | session A, ADR-S007 / ADR-S008 |
| 1.1 – 1.7 | session A | | not started by session B |
| 2.4, 2.1, 2.2, 2.3, 2.5 | session A | | not started by session B |
| 4 fleet | in-progress (B) | | new module `fleet` |
| 2.6 BI & dashboards | in-progress (B) | | |
| 2.8 subscriptions & deferrals | in-progress (B) | | |
| 2.9 projects & service billing | in-progress (B) | | |
| 2.10 portals & payment gateways | in-progress (B) | | Stripe (test mode) + SPI |
| 2.7 intercompany & multi-company | todo | | |
| 2.x UI i18n | todo | | |
| 2.x e-commerce connector | todo | | |
| 3.3 scale & data lifecycle | todo | | |
| 3.1 advanced costing | todo | | partly covered by ADR-S008 (standard + process costing) |
| 3.2 IFRS 15 / IFRS 16 | todo | | |
| 3.4 governance, risk & compliance | todo | | |
| 3.5 treasury | todo | | |
| 3.6 AI features | todo | | |
| 3.7 ecosystem | todo | | |
| hardening pass | todo | | known: POS sales-summary by trading date returns 0 between 00:00 and 06:00 UTC (`PosSaleIT.reports`, `PosLakeviewSimIT.tradingDaysTieOut` fail in that window) |
| final report | todo | | |

## Blocked
- 0.x parity harness: needs Nest + Postgres running side by side (Docker); not possible on the session B box.

## Session history
- 2026-10-28 session B1: state audit (`compileJava` + `compileTestJava` green on ef77292); Docker-free IT switch;
  baseline 617 tests / 2 failed. Rental + repair verified and hardened by agents; rebased onto session A's 12 commits
  (kept both rental suites); full suite 669 tests, failures only the 00:00–06:00 UTC POS window + 2 rental tests fixed.

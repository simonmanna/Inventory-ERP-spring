# Migration and ADR number registry

- Status: active (IPR Phase 0, task T01, requirement R8). Owner: the shared-engine owner (see
  `docs/ARCHITECTURE.md` §11). Changes to this file need owner review (`.github/CODEOWNERS`).
- Scope: Flyway migrations of the main API, `backend/src/main/resources/db/migration/`, and ADR numbers in
  `docs/adr/`. The licence server (`license-server/src/main/resources/db/migration/`, V1–V4 today) has its own
  database and its own number space. It is not covered here.

## Why a registry

Flyway applies versions in order and refuses a version below the highest applied one unless out-of-order is on.
Several streams work in parallel worktrees. Two branches that pick the same `V{n}` both pass CI alone and collide at
merge, or one is silently skipped on a database that already has the other. This has happened before: PLM was
built as V640–V644 and renumbered to V670–V674 before merge, and LP0 had to renumber to V520.

## Rules

1. **Reserve before you write.** Add a row to "Reservations" in the same PR that adds the first migration of the
   range, or earlier in a docs PR. A number that is not in this file is not yours.
2. **Rescan all refs before using a number.** A reservation is a plan, not proof. Immediately before you create a
   `V{n}__*.sql` file, run the scan below and confirm no ref already holds `V{n}`. Do it again before you rebase or
   merge.
3. **Never reuse a burned number.** A number that exists on any ref, including archive tags, is burned, even if
   main never had it. A test or demo database may already record it in `flyway_schema_history`.
4. **Never edit a merged migration.** `V1__baseline.sql` and `V2__reference_data.sql` are never edited (AGENTS.md
   §8). A correction is a new migration.
5. **Additive by default.** A migration in a reserved IPR range adds nullable columns, indexes or new tables. It
   does not rewrite data. It never runs `UPDATE` or `DELETE` on `"InventoryLedger"` (the append-only trigger
   `guard_inventory_ledger` refuses it anyway, `V1__baseline.sql:2204`, `:17176`).
6. **ADR numbers** follow the same rule: next free `ADR-S0nn` after a scan of all refs, recorded in the table below.

### Scan command

Run from any worktree of the repository:

```bash
git fetch --all --prune --tags
for r in $(git for-each-ref --format='%(refname)' refs/heads refs/remotes refs/tags); do
  git ls-tree -r --name-only "$r" -- backend/src/main/resources/db/migration docs/adr
done | sort -u > /tmp/refscan.txt
# highest migration number on any ref
grep -oE 'V[0-9]+__' /tmp/refscan.txt | tr -dc '0-9\n' | sort -n | tail -1
# is my number free? (prints nothing when free)
grep -E '/V700__' /tmp/refscan.txt
# highest ADR number on any ref
grep -oE 'ADR-S[0-9]+' /tmp/refscan.txt | sort -u | tail -1
```

Uncommitted files in other worktrees are not refs. Also check `git worktree list` and
`git status --porcelain -- backend/src/main/resources/db/migration` in each worktree before you pick a number.

## Last scan

- Date: 2026-11-27. Base: `origin/main` 0e670f12.
- Refs scanned: 42 (17 local heads, 15 remote refs, 10 tags including `archive/*`).
- Uncommitted migration or ADR files in the 12 worktrees: none.

| Item | Highest | Where |
|---|---|---|
| Migration on any ref | **V696** `V696__purchase_line_value_checks.sql` | `main`, `origin/main`, `origin/fix/purchase-readiness`, `feat/procurement-analytics-dashboard`, the four `fix/ipr-p0-*` branches |
| Migration on `main` | V696 (282 files) | – |
| ADR on any ref | **ADR-S087** `ADR-S087-quality-control.md` | `main` and all branches |
| Non-S ADR | `ADR-PROCUREMENT-001.md` | separate series |

Migration numbers used from V640 upward, on any ref:

| Numbers | Use | Ref |
|---|---|---|
| V640–V644 | PLM (first numbering) | archive tags only (`archive/feat/plm-app`, `archive/wip/plm-owner-adjustments`). **Burned.** |
| V650–V656 | finance hardening, ledger insertion order (V654 `seq`) | main |
| V660–V661 | quality control (ADR-S087 reserves V661–V669) | main |
| V670–V674 | PLM (renumbered) | main |
| V675–V683 | sales quotations, delivery, dunning, gapless numbers, sales policy | main |
| V690, V692, V696 | purchase readiness | main |

Free gaps below V700 (V662–V669, V684–V689, V691, V693–V695, V697–V699) are **not** for new work. V662–V669 stay
with ADR-S087. The others are left as they are, so that out-of-order branches never land below an applied number.

ADR numbers: S067–S069 were never used (the series jumps from S066 to S070). S011 is used twice
(`ADR-S011-advanced-multistage-manufacturing.md`, `ADR-S011-sales-o2c.md`). Do not reuse either gap. The next ADR
is **ADR-S088**.

## Reservations

| Range | Owner / stream | Planned use | Status |
|---|---|---|---|
| ADR-S088 | IPR integrator | Stock movement effective time (`docs/adr/ADR-S088-stock-movement-effective-time.md`) | Proposed (Phase 0) |
| V700 | IPR engine owner, Phase 1b (T10) | `InventoryLedger."effectiveAt"` `timestamp(3)` **nullable, no default, no backfill** (ADR-S088); `InventoryCountSession."freezeLocation"` freeze flag; `InventoryCountLine."measuredSeq"` (count spec §1) | **Written** (`V700__stock_effective_time.sql`, branch `fix/ipr-1b`) |
| V701 | IPR engine owner, Phase 1b (T12) | Partial unique index: one draft count session per location (S4); partial unique index on late count corrections `("organizationId", "sourceLineId", "referenceId") WHERE "referenceType" = 'inventory_count_late_correction'` (count spec §4.4); count type default `cycle` (S1) | **Written** (`V701__count_session_guards.sql`, branch `fix/ipr-1b`) |
| V702 | IPR Phase 2 stream C (X-9 partial output) | `V702__production_partial_output.sql`: new table `ProductionPartialOutput` (one row per partial receipt of a simple order's main output: quantity, WIP credit, overhead absorbed, ledger code), index on (org, order), RLS `tenant_isolation`. New table only; no existing row changed; ledger trigger untouched | Written on `fix/ipr-2` |
| V703–V705 | IPR Phase 2 stream C, then stream H (Phase 1a / 2 residuals, `fix/ipr-12-residuals`) | spare | Reserved, not written: **not needed by stream H** (every residual fix is code or seed only) |
| V706–V707 | IPR Phase 1a stream B (delivery and returns) | as needed | Reserved; **not needed by stream B** (`fix/ipr-1a`) |
| V708–V709 | IPR Phase 4 stream F (go-live, promoted P2s) | as needed | Reserved |
| V710–V714 | IPR Phase 3 (security and controls: SoD seeding, transit-loss approval, reservation source types) | as needed | Reserved; **not needed by stream D** (`fix/ipr-3`): SoD seeding runs in org bootstrap, the threshold is a setting, no schema change |
| V715–V719 | IPR Phase 4 (opening-stock import: import run, batch with unique `(organizationId, importHash)`) | as needed (R5) | Reserved, not written |

Phase 0 of IPR adds **no** migration (Phase 0 rules). The first V7xx is written in Phase 1b, after the owner
accepts Phase 0. Each IPR migration must leave the trigger `inventory_ledger_append_only` enabled; a test checks
`pg_trigger` (R1).

| V720–V721 | IPR Phase 1c/1d stream E2 (serial/lot, restock links), allocated after V700–V719 filled | as needed | Reserved |

A stream outside IPR that needs a migration takes the next free number **above V721** and records it here.

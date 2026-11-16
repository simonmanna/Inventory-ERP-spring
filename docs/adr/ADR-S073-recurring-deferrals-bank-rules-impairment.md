# ADR-S073: Recurring entries, deferrals, bank rules and asset impairment

- Status: accepted (built 2026-11-10, branch `phase/acc-gl`)
- Builds on: ACC-3 (posting integrity, `postingKey`), ACC-5 (fixed-asset GL lifecycle) and ACC-6 (bank
  reconciliation, locked statements, D-B5 direction-aware matching).
- Migrations: `V489__recurring_entries_deferrals.sql`, `V490__bank_match_rules.sql`, `V491__asset_impairment.sql`
- Extension: no NestJS counterpart. Existing routes and their responses are unchanged, except that the bank-line list
  gains two fields (`matchedRuleId` and `journalEntryId`) and lines can now have the new status `partial`.
- Spec: [accounting.md § ADR-S073](../spec/accounting.md#adr-s073-gl-automation),
  [fixedasset.md § ADR-S073](../spec/fixedasset.md).

## Context

The accounting spec listed these general-ledger gaps as "not built":

- recurring journal entries;
- deferred revenue and prepaid expense schedules;
- bank matching rules, bank-charge journals, and partial or grouped matches (ACC-6);
- fixed-asset impairment (ACC-5).

Users had to work around each one by hand, with manual journals, spreadsheets, or excluding lines with a note.
Nothing guarded against posting twice, and the deferral balance could not be reconciled to the ledger.

## Decision

Every GL write goes through `PostingService` with a deterministic `postingKey`. Every scheduled body is safe to run
again.

### 1. Recurring journal entries (`/recurring-entries`)

- **Template.** `RecurringJournalTemplate` stores:
  - the journal, the description and the balanced lines (JSON);
  - the frequency (`daily`, `weekly`, `monthly`, `quarterly`, `yearly`) and `intervalCount`;
  - `startDate`, optional `endDate` and `nextRunDate`;
  - `mode`: `post` or `draft`;
  - `status`: `active`, `paused` or `ended`.
- **Calendar.** Occurrences are counted from the start date (`GlSchedules.occurrence`). A template that starts on the
  31st falls on the last day of shorter months.
- **Occurrence.** Each occurrence posts on its date with the key `recurring:<templateId>:<date>`.
  `RecurringJournalRun` records it, and is unique per template and date. A second job run, or a manual run of an
  occurrence that already exists, replays it (`replayed: true`).
- **Maker-checker.** In `draft` mode the entry is staged with `PostingService.stageDraft`, and its maker
  (`createdBy`) is the template author. The existing `POST /journal-entries/:id/post` therefore refuses the author
  and needs a second user.
- **Job.** `GlAutomationJob.recurringEntries` runs daily at 01:30. It uses ShedLock `accounting-recurring-entries` and
  the cron `app.accounting.recurring-cron`.
  - It catches up at most 60 missed occurrences per template.
  - Each occurrence commits on its own.
  - A refused occurrence (closed or locked period, books lock) is recorded as a `failed` run with the error. The
    schedule does not move, so the job tries again the next day.
- **Manual "run now".** It answers 400 in the same cases.
- **Pause and resume.** Resuming skips the occurrences missed while the template was paused.

### 2. Deferral schedules (`/deferrals`)

- **Schedule.** `DeferralSchedule` releases a balance from a balance-sheet account to a P&L account.
  - The balance-sheet account (`sourceAccountId`) holds deferred revenue or a prepaid expense. When none is given,
    it defaults to the mapping `unearned_revenue` or `prepaid_expense`.
  - The P&L account is `targetAccountId`.
- **Planned slices.** `DeferralRelease` rows are planned when the schedule is created (`GlSchedules.deferralPlan`).
  Each slice is rounded to 2 dp, half-up, and the last slice takes the remainder.
  - `monthly`: equal slices of one month each.
  - `daily`: calendar-month slices weighted by the number of days in each.
- **Release.** A slice posts on its period end with the key `deferral:<id>:<periodIndex>`.
  - Revenue: Dr deferred revenue / Cr revenue.
  - Expense: Dr expense / Cr prepaid.
  - Slices are released by the job (`accounting-deferral-releases`, 01:40), by `POST /deferrals/run` (all schedules)
    and by `POST /deferrals/:id/release` (one schedule, all-or-nothing).
- **Defer a posted line.** `POST /deferrals/from-journal-line` defers (part of) a posted line. A credit line is revenue
  and a debit line is an expense.
  - On a P&L account, the deferred amount is first moved to the deferral account (key `deferral:<id>:initial`). It is
    dated on the line's date while that period is open, otherwise today.
  - A line can be deferred once while its schedule is not cancelled.
- **Cancel.** Cancelling cancels the planned slices and posts nothing. The rest of the balance stays on the deferral
  account.
- **Report.** `GET /deferrals/report?asOf` compares, for each deferral account, the remaining balance of its
  schedules with the account's GL balance in its natural sign. The `difference` should be 0.

### 3. Bank matching rules and partial or grouped matches

- **Rule.** `BankMatchRule` is set per bank (GL) account:
  - it matches on the description, the reference or either, using contains or a regex (case-insensitive);
  - it can limit the absolute amount to a range and the direction to inbound, outbound or any;
  - it has a priority.
- **Actions.**
  - `match_payment` takes the closest posted payment of the same size and direction (D-B5) within the tolerance,
    optionally for one partner. The payment must not be matched or allocated anywhere.
  - `post_entry` books the line against `counterAccountId` with key `bank_rule:<lineId>`. A charge books Dr account,
    Dr input tax, Cr bank. Income books Dr bank, Cr account, Cr output tax. The tax is included in the line amount.
    The line becomes `matched` with `journalEntryId` and `matchedRuleId`.
- **When rules run.** Rules run after every import, once the import has committed, with one transaction per line, so
  a refused posting never fails the import. They also run on demand (`POST /bank-reconciliation/rules/apply`).
- **Allocations.** `BankStatementMatch` supports:
  - one line to several payments (`POST /bank-reconciliation/lines/:id/allocate`);
  - several lines to one payment (`POST /bank-reconciliation/payments/:id/allocate`).

  Either side can be partial, and every allocation must match the direction. A line stays `partial` until its
  allocations cover it.
- **Changes to existing behaviour.**
  - Auto-match and candidates skip payments that already have an allocation.
  - Outstanding book items (`summary`, `statement`) count only the unallocated remainder of a payment.
  - Unrecorded statement items count the unallocated remainder of a `partial` line.
  - `unmatch` deletes a line's allocations and reverses the entry a rule posted.
- **Locked statements (ACC-6).** Rules never touch a line on or before a locked statement, and allocating such a line
  is refused with the existing "locked through" message.
- **Permissions.** These routes use `bank_account:read` and `bank_account:update`, like the rest of bank
  reconciliation.

### 4. Fixed-asset impairment (IAS 36, cost model)

- **Impairment.** `POST /fixed-assets/:id/impairments {recoverableAmount}` writes the carrying amount (the register's
  `currentValue`) down to the recoverable amount.
  - It posts Dr `asset_impairment_loss` / Cr `accumulated_impairment` with key `asset_impairment:<id>`, sourceType
    `asset_impairment` and sourceId the asset.
  - It is refused unless the recoverable amount is below the carrying amount.
- **Depreciation afterwards.**
  - Straight line spreads the carrying amount after the latest impairment event, less the residual value, over the
    remaining useful life at that date (`usefulLife` − runs so far).
  - The declining methods apply their rate to the lower carrying amount.
  - Assets that were never impaired keep the existing calculation exactly.
- **Reversal.** `POST /fixed-assets/:id/impairments/reversal` posts the opposite entry. The amount is capped at the
  lowest of:
  - the impairment still on the books;
  - the carrying amount the asset would have had without impairment (the original schedule replayed for the runs so
    far), minus the current carrying amount;
  - the recoverable amount minus the current carrying amount.
- **Disposal.** Disposal also debits the accumulated impairment still on the books. The gain or loss is then proceeds
  − (cost − posted depreciation − impairment).
- **New mappings.** `asset_impairment_loss` and `accumulated_impairment` are optional mappings in
  `packages/shared/src/accounting/account-mappings.ts`. On first use they are created as `IMPAIRMENT-LOSS`
  (`other_expense`) and `ACCUM-IMPAIRMENT` (`contra_asset`). `prepaid_expense` is added the same way
  (`PREPAID-EXPENSE`). The CoA template (Nest reference) is not changed.
- **Out of scope.** A revalued asset is still impaired through P&L. The surplus is not released first (IAS 36.61).

### Permissions

| Permission | Covers |
|---|---|
| `recurring_entry:read` | reading templates and run history |
| `recurring_entry:manage` | creating, changing, pausing, resuming, running and deleting templates |
| `deferral:read` | reading schedules and the report |
| `deferral:manage` | creating, releasing and cancelling schedules |
| `fixed_asset:impair` | recording impairments and reversals |

V503 grants all five to the `Administrator` role of existing organizations. New organizations get them through
`Permissions.ALL`.

## Consequences

- Each scheduled body is safe to re-run: the posting keys plus the unique run and release rows prevent double
  booking under ShedLock failover and manual reruns.
- Closed periods behave as before. A failed job occurrence or slice is visible in history and is retried, never
  skipped silently.
- Grouped matching changes the outstanding and unrecorded figures only when allocations exist. Existing statements
  reconcile exactly as before.
- Tests: `GlAutomationIT` covers GL postings, idempotency of the jobs, maker-checker drafts, closed periods, bank
  rules, grouped and partial matches, the locked-statement refusal, the impairment lifecycle, authorization and
  tenant isolation. `GlSchedulesTest` covers the calendar and money arithmetic.

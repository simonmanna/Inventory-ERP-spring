# ADR-S070 — The accounting day is the organisation-local calendar day

- Status: proposed (built 2026-11-10, branch `phase/acc-tz`), deviation from Nest pending owner approval
- Code: `accounting/application/AccountingDay.java` (the one helper), `FiscalPeriodControl`, `PostingService`
- Spec: [docs/spec/accounting.md § Deviations](../spec/accounting.md)

## Context

`Organization.timezone` holds an IANA zone (default `UTC`; the market is East Africa, e.g. `Africa/Kampala`,
UTC+3). Money screens (`OrgDates`) already cut days in that zone, but the general ledger cut them by UTC day, like
Nest: a sale at 01:00 on 1 February in Kampala is 22:00 UTC on 31 January, so it landed in January in period
control, entry numbering and every ledger report, while the cash screens showed it on 1 February.

## Decision

The accounting day of an instant is its calendar date in the organisation's zone. Concretely:

1. **Two kinds of value.** `JournalEntry.postingDate` (and other true timestamps) are real instants, stored as
   UTC. Date-like values are **org-local wall clock**: a bare `YYYY-MM-DD` in a request, and the stored bounds of a
   fiscal period, fiscal year, VAT settlement and the books lock (`2026-01-31 23:59:59.999` on a period is the end of
   31 January in the organisation's zone). `AccountingDay.fromWallClock` / `toWallClock` convert between the two.
2. **Posting.** `PostingService` stores real timestamps unchanged. A date-only posting (exactly UTC midnight, the
   convention `LocalDate.atStartOfDay(ZoneOffset.UTC)`) becomes local noon of that date, so it lands on that date in
   any zone (UTC midnight is the previous evening in New York). The entry-number year is the accounting-day year.
3. **Period control.** `FiscalPeriodControl` (and the books lock, the stock / landed-cost / fuel-tank period
   checks) compare the posting instant read as org-local wall clock against the stored bounds. The covering period
   row is still held `FOR SHARE`.
4. **Ranges.** Every ledger range (reports, period close, year close, close checklists, VAT settlement, costing and
   project reads) turns dates into instant bounds in the org zone in Java, so SQL keeps index-friendly
   `"postingDate" >= ? AND "postingDate" < ?`; when SQL joins `postingDate` to a stored period, the zone conversion is
   applied to the period column (`AT TIME ZONE`), never to `postingDate`.
5. **UTC organisations are unchanged.** Every conversion is the identity for a UTC zone, so results, stored values
   and SQL text are the same as before for every UTC organisation.

## Consequences

- A real posting that is exactly 00:00:00.000 UTC in a non-UTC organisation is taken as date-only and moved to local
  noon of that UTC date. Today callers build such instants only for date-only postings.
- Values that follow the host-wide `APP_TIMEZONE` (`BusinessZone`, Nest's host-local behaviour) are unchanged.
  So are the costing lock's `postingDate::date` advisory join in the period checklist and the UTC-formatted day in
  partner statements.
- Periods stored with a bare midnight end date still leave out the rest of their last day, as before, for UTC
  parity. Fiscal years always create whole-day periods.

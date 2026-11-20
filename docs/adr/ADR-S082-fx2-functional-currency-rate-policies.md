# ADR-S082 — FX-2: functional currency, rate types and policies, monetary scope, deposits, expenses, receipts

- Status: **in progress** on branch `phase/fx2`. Owner decisions 1–6 of 2026-11-20 are the basis. Spec:
  [FX-2-SPEC](../accounting/FX-2-SPEC.md). Workstreams land as one commit each; this ADR records each one when built.
- Builds on: [ADR-S081](ADR-S081-fx-gain-loss-correctness.md) (FX-1), ADR-S054, ADR-S076, ADR-S080.
- Extension: no NestJS counterpart. New routes and request fields are additive; deviations are listed in
  `docs/spec/accounting.md`.
- Constraints kept: `PostingService` stays the only GL writer; `CurrencyService`, `DocumentFxRates`, `ForeignCash` and
  `PaymentService.fxSettlement` are extended, not replaced. No historical posting is modified.

## A. Functional currency (decision 1) — built

- `PostingService.baseCurrency()` and `ForeignCash.functional()` refuse "Set the company's functional currency before
  posting" instead of falling back to USD.
- `FunctionalCurrencyLock` refuses clearing the currency: "The functional currency is required". The existing lock
  after the first posting is unchanged.
- V584: refuses to migrate while any organisation has no currency (listing them), keeps `NOT NULL`, and adds
  `CHECK (btrim("currencyCode") <> '')`.
- Not done in A: the other `"USD"` / `"UGX"` fallbacks in other modules are left in place. They can no longer fire
  because the column cannot be null or blank. The web onboarding still pre-selects a currency.

## B. Rate types, approval and policies (decision 4) — built

- `CurrencyRate` gains `rateType` (`official|bank|cash|market`), `status` (`pending|approved|rejected`), `approvedBy`
  and `approvedAt`. Existing rows become `official` + `approved`. The unique key now includes the rate type. A new
  `CHECK (rate > 0)` is added `NOT VALID`, so existing rows are not re-checked.
- `FxRatePolicy` (organisation, context) has RLS. Contexts: sales, purchase, goods_receipt, expense, payment_in,
  payment_out, treasury, deposit, revaluation, opening_balance.
- **Defaults when a context has no row:** official rate, approval not required, stated rates allowed, no tolerance.
  This reproduces pre-FX-2 results exactly (open question 1–2 defaults: approval off, no tolerance). The spec's
  "approved rates required by default" is equivalent today, because every existing rate is approved.
- A new rate is `pending` when any policy of the company requires approved rates of that type, otherwise `approved`.
  Approve and reject follow maker-checker: the person who entered a rate gets 403.
- Resolution: `CurrencyService.resolve(…, context)` / `resolveStated(…, context)` / `policyRate(…)`.
  - A stated rate is refused when the policy disallows stated rates.
  - A stated rate beyond the tolerance needs permission `currency:rate_override` plus a reason.
  - Otherwise the policy's rate type is used: approved rates only when the policy requires approval; rejected rates
    are never used.
- **Override reason transport (deviation from the spec):** the HTTP header `X-Fx-Rate-Override-Reason`, read by
  `FxRateOverrideFilter`, instead of an `fxRateOverrideReason` field on every money DTO. This keeps all request bodies
  unchanged (forbidNonWhitelisted).
- Context wiring:
  - `PostingService` maps the posting source to a context: vendor bill, debit note and landed cost → purchase; invoice,
    credit note, POS and cash sale → sales; goods receipt → goods_receipt; expense; customer deposit → deposit; cash
    flow and treasury → treasury; fx_revaluation → revaluation; opening_balance.
  - Payments resolve as `payment_in` or `payment_out`, and now pass their rate as a leg snapshot, so a foreign payment
    records its rate.
  - Treasury, bank matching, deposits, revaluation, PO, debit note, landed cost and `DocumentFxRates` pass their
    context.
- `JournalEntryFxRate` gains `rateType`, `context`, `policyRate`, `overrideReason` and `approvedBy` (the overriding
  user).
- Routes:
  - `POST /currencies/rates` gains `rateType`.
  - `GET /currencies/rates` gains the `rateType` and `status` filters, and each row gains id, type, status, createdBy,
    approvedBy and shared.
  - `POST /currencies/rates/{id}/approve|reject` (`currency:approve`).
  - `GET /fx-rate-policies` (`currency:read`).
  - `PUT /fx-rate-policies/{context}` (`currency:policy`).
- Permissions `currency:approve`, `currency:rate_override` and `currency:policy` are granted to Administrator in V585.
  `sales:deposit:classify` is added to the contract now and granted in V587 (D).
- Web, Currency page: a rate-type selector for entry and list, a pending-approval card with Approve and Reject, and a
  policies table. Money dialogs do not yet show the resolved rate type or ask for a reason; that is left for later.

## Tests

- `FunctionalCurrencyIT`: clearing refused; changeable before and locked after the first posting; blank refused by
  the database.
- `FxRatePolicyIT`: defaults and tenant isolation; a bank-rate purchase policy with its snapshot; pending ignored,
  enterer refused, second person approves; tolerance refusal, then a header override with the reason snapshotted;
  stated rates forbidden; validation.
- Unchanged and green: `CurrencyServiceResolveTest`, `FxGainLossFx1IT`, `SharedFxIT`, `FxPaymentsIT`, `FxDepositsIT`,
  `FxTreasuryIT`, `Acc0FxIT`, `ForeignCashTest`, `LandedCostAuditIT`, `ProcurementIntegrityIT`.

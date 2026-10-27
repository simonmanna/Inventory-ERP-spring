# ADR-S004: Beverage count controls — variance cause codes and unweighed-bottle exceptions

- Status: accepted (2026-10-26, approved by the owner). Deliberate deviation from the NestJS reference.
- Related: ADR-S001 (frozen wire contract), `V6__beverage_controls.sql`, `V7__beverage_double_pour.sql`,
  `docs/spec/beverage.md` "Deviations"

## Context

The ported (Nest-parity) beverage module already covers weight-based bar control:
- calibrated per-product bottle config (volume, tare, full weight, ml-per-gram factor);
- opening and closing weigh counts, and an immutable `BottleMeasurement` log;
- variance against perpetual system stock, which POS sales and recipes have already reduced;
- per-product and org tolerances;
- a reason on flagged lines, and segregation-of-duties manager approval;
- the variance posted as a `cycle_count` stock adjustment, never silently.

We reviewed it against a bar loss-control brief and found two gaps:

1. **Variance causes were free text only.** "spilled", "Spillage" and "spill" could not be grouped. Nobody
   could see how much loss was comps, staff drinks or unrecorded sales.
2. **Unweighed bottles were silently skipped.** A line nobody weighed has `countedMl = NULL`, so submit ignores
   it. A count could be finalised with half the bar unweighed, and no one would be told.

## Decision

1. **`BottleCountLine.reasonCode` (V6)** is optional and is one of `spillage`, `breakage`, `complimentary`,
   `staff_drink`, `unrecorded_sale`, `bottle_transfer`, `measurement_error` or `unexplained`. The DB has a
   CHECK constraint on it and the DTO uses `@OneOf`.
   - The free-text `reason` stays. It is still the field required on flagged lines, so Nest-era clients behave
     exactly as before.
   - The draft route accepts `reasonCode`. Line responses and the variance report return it.
   - The web count screen offers a cause picker that pre-fills the reason. The dashboard shows a Cause column.
2. **Unweighed-bottle exception (opt-in).** It applies only when the organization setting
   `beverage.requireAllBottlesWeighed` is `true`. Submit then refuses a count if a line holds stock
   (`systemMl > 0`) but was not weighed. To continue, the submit must include both:
   - `unweighedReason`;
   - manager approval through the existing approval gate (`beverage:approve`, not the counter, PIN when given).

   The exception is stored on the session (`unweighedReason`, `unweighedCount`) and in the audit row.
   Unweighed lines are never adjusted, so stock is left as the system has it.
3. **Single and double shot sizes per product (V7).** `standardPourMl` is the single shot; the new optional
   `Product.doublePourMl` is the double (NULL = 2 × single; must be > 0). Both are set on the product setup
   screen. The yield report adds `doublePourMl` and `doublesPerBottle`. Sales still draw ml only through
   menu-item recipes, so this never moves stock and cannot drift from what was sold: a double sold is a menu
   item whose recipe draws the double size. No separate preset table.

## Consequences

- With the setting off, which is the default, every route behaves as in Nest. The only wire difference is
  additive fields: `reasonCode`, `unweighedReason`, `unweighedCount` (counts and variance report),
  `doublePourMl`, `doublesPerBottle` (yield report) and `doublePourMl` on products.
- The parity harness ignores exactly those fields for `beverage-control.json` (`contract-tests/allowed-diffs.json`,
  `*` path segments).
- New error messages, which do not exist in Nest:
  - `"<n> bottle line(s) with stock were not weighed. Weigh them, or submit an unweighed exception with a reason and manager approval."`
  - `"Submitting with unweighed bottles requires manager approval."`

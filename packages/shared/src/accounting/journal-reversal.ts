/**
 * Journal sources whose posted entries the generic `POST /journal-entries/{id}/reverse` accepts
 * (finance readiness audit A2, owner decision 2026-11-23).
 *
 * - A manual journal has no source type (`null`) and is always accepted, control-account adjustments included
 *   (those also need `journal_entry:control_adjust`).
 * - A `reversal` entry is judged by the entry it reverses.
 * - Every other source is refused, new and unreviewed ones included: the document or process that posted the entry
 *   reverses it together with the records it belongs to (invoice cancel, period reopen, depreciation-run reversal, …).
 *   The API names that action in its 400 and in the journal-entry detail (`sourceDocument`).
 *
 * Source of truth for the API (`tools/contracts2java/accounting.mjs` → `contracts/journal-reversal.json`) and the web.
 */
export const GENERIC_REVERSAL_SOURCE_TYPES = ['recurring_entry'] as const;
export type GenericReversalSourceType = (typeof GENERIC_REVERSAL_SOURCE_TYPES)[number];

/** Source type of the entry a reversal posts; judged by the original entry. */
export const REVERSAL_SOURCE_TYPE = 'reversal';

/**
 * Whether the generic reversal may accept an entry of this source type. `true` for a `reversal` entry means only
 * "decided by its original": the journal-entry detail's `sourceDocument` block is the API's final word.
 */
export function isGenericReversalSource(sourceType: string | null | undefined): boolean {
  return (
    sourceType == null ||
    sourceType === REVERSAL_SOURCE_TYPE ||
    (GENERIC_REVERSAL_SOURCE_TYPES as readonly string[]).includes(sourceType)
  );
}

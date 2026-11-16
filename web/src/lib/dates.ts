/**
 * Local calendar-date helpers. `Date#toISOString()` is UTC, so slicing it gives
 * the wrong day east of UTC around midnight (and `new Date(y, m, 1)` becomes the
 * last day of the previous month). Report filters and form defaults use these.
 */

/** Local calendar date as YYYY-MM-DD (no UTC shift). */
export function localDay(d: Date = new Date()): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** First day of the month containing `d`, as a local YYYY-MM-DD. */
export function firstOfMonth(d: Date = new Date()): string {
  return localDay(new Date(d.getFullYear(), d.getMonth(), 1));
}

/** Last day of the month containing `d`, as a local YYYY-MM-DD. */
export function lastOfMonth(d: Date = new Date()): string {
  return localDay(new Date(d.getFullYear(), d.getMonth() + 1, 0));
}

/** First day of the year containing `d`, as a local YYYY-MM-DD. */
export function firstOfYear(d: Date = new Date()): string {
  return localDay(new Date(d.getFullYear(), 0, 1));
}

/** An ISO timestamp (or date) rendered as the local calendar day YYYY-MM-DD. */
export function localDayOf(value: string | Date | null | undefined): string {
  if (!value) return '';
  const d = typeof value === 'string' ? new Date(value) : value;
  return Number.isNaN(d.getTime()) ? String(value).slice(0, 10) : localDay(d);
}

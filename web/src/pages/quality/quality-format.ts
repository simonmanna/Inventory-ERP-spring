/** Formatting helpers of the quality screens (ADR-S013). */

export { toOptions, num } from '../fleet/fleet-format';

/** Readable form of a code without a label map ("goods_receipt" → "goods receipt"). */
export const label = (code?: string | null) => (code ? code.replace(/_/g, ' ') : '—');

/** Number or undefined from a text input ("" → undefined). */
export const optNum = (v: string): number | undefined => (v.trim() === '' ? undefined : Number(v));

/** Today as yyyy-mm-dd (local). */
export const today = () => {
  const d = new Date();
  return new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 10);
};

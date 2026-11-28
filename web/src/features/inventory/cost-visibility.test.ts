import { describe, expect, it } from 'vitest';
import { canConsumeReservation, costText } from './cost-visibility';

const fmt = (v: number | string) => `UGX ${Number(v).toFixed(2)}`;

describe('costText (IPR S-2)', () => {
  it('formats a visible cost', () => {
    expect(costText(12.5, true, fmt)).toBe('UGX 12.50');
    expect(costText('0', true, fmt)).toBe('UGX 0.00');
  });

  it('shows a dash for a hidden (null) cost or a user without the permission', () => {
    expect(costText(null, true, fmt)).toBe('—');
    expect(costText(undefined, true, fmt)).toBe('—');
    expect(costText(12.5, false, fmt)).toBe('—');
  });
});

describe('canConsumeReservation (IPR C-5)', () => {
  it('offers consume only on active manual reservations', () => {
    expect(canConsumeReservation({ sourceType: 'manual', status: 'active' })).toBe(true);
    expect(canConsumeReservation({ sourceType: 'order', status: 'active' })).toBe(false);
    expect(canConsumeReservation({ sourceType: 'manual', status: 'released' })).toBe(false);
  });
});

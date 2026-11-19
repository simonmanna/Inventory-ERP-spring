import { describe, expect, it } from 'vitest';
import { dur, hourText, num, options, pct } from './lp-format';

describe('num', () => {
  it('shows — when there is no value', () => {
    expect(num(null)).toBe('—');
    expect(num(undefined)).toBe('—');
    expect(num('')).toBe('—');
  });

  it('formats numbers and numeric strings in the locale, with at most 2 decimals by default', () => {
    expect(num(3)).toBe((3).toLocaleString());
    expect(num('12.3456')).toBe((12.35).toLocaleString(undefined, { maximumFractionDigits: 2 }));
    expect(num(0)).toBe((0).toLocaleString());
  });

  it('honours the number of decimals', () => {
    expect(num(1.23456, 0)).toBe((1).toLocaleString());
    expect(num(1.23456, 3)).toBe((1.235).toLocaleString(undefined, { maximumFractionDigits: 3 }));
  });
});

describe('pct', () => {
  it('shows — when there is no value', () => {
    expect(pct(null)).toBe('—');
    expect(pct(undefined)).toBe('—');
    expect(pct('')).toBe('—');
  });

  it('turns a fraction into a percentage with at most one decimal', () => {
    expect(pct(0.5)).toBe(`${(50).toLocaleString()}%`);
    expect(pct('0.1234')).toBe(`${(12.3).toLocaleString(undefined, { maximumFractionDigits: 1 })}%`);
    expect(pct(0)).toBe(`${(0).toLocaleString()}%`);
  });
});

describe('dur', () => {
  it('shows — when there is no value', () => {
    expect(dur(null)).toBe('—');
    expect(dur(undefined)).toBe('—');
    expect(dur('')).toBe('—');
  });

  it('shows m:ss below an hour', () => {
    expect(dur(0)).toBe('0:00');
    expect(dur(5)).toBe('0:05');
    expect(dur(65)).toBe('1:05');
    expect(dur(3599)).toBe('59:59');
  });

  it('shows h:mm:ss from an hour', () => {
    expect(dur(3600)).toBe('1:00:00');
    expect(dur(3725)).toBe('1:02:05');
  });

  it('rounds fractional seconds and reads numeric strings', () => {
    expect(dur(59.6)).toBe('1:00');
    expect(dur('90.4')).toBe('1:30');
  });
});

describe('hourText', () => {
  it('pads the hour', () => {
    expect(hourText(0)).toBe('00:00');
    expect(hourText(9)).toBe('09:00');
    expect(hourText(23)).toBe('23:00');
  });
});

describe('options', () => {
  it('keeps distinct non-empty values, sorted', () => {
    expect(options(['b', null, 'a', undefined, '', 'b'], '')).toEqual([
      { value: 'a', label: 'a' }, { value: 'b', label: 'b' },
    ]);
  });

  it('keeps the chosen value even when the page no longer shows it', () => {
    expect(options(['a'], 'z').map((o) => o.value)).toEqual(['a', 'z']);
  });

  it('labels each value', () => {
    expect(options(['x_y'], '', (v) => v.toUpperCase())).toEqual([{ value: 'x_y', label: 'X_Y' }]);
  });
});

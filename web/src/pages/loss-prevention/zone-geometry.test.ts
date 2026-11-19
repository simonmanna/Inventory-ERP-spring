import { describe, expect, it } from 'vitest';
import { ZONE_DEFAULT_PARAMS, ZONE_LINKS, ZONE_TYPES, type Point } from '@/features/loss-prevention/vision-api';
import { defaultLink, inArrow, joinParams, paramProblem, splitParams, svgPoints } from './zone-geometry';

const close = (a: number, b: number) => Math.abs(a - b) < 1e-9;

describe('svgPoints', () => {
  it('scales fractions to the 1000 × 1000 view box', () => {
    expect(svgPoints([[0, 0], [0.5, 0.25], [1, 1]])).toBe('0,0 500,250 1000,1000');
  });

  it('is empty without points', () => {
    expect(svgPoints([])).toBe('');
  });
});

describe('inArrow', () => {
  const a: Point = [0.2, 0.5];
  const b: Point = [0.8, 0.5];

  it('has the 7 corners of an arrow', () => {
    expect(inArrow(a, b, 'left', 1)).toHaveLength(7);
  });

  it('points up (left of A → B) for a horizontal line walked to the right', () => {
    const tip = inArrow(a, b, 'left', 1)[3];
    expect(close(tip[0], 0.5)).toBe(true);
    expect(close(tip[1], 0.5 - 0.08)).toBe(true);
  });

  it('points down for the right side', () => {
    const tip = inArrow(a, b, 'right', 1)[3];
    expect(close(tip[0], 0.5)).toBe(true);
    expect(close(tip[1], 0.5 + 0.08)).toBe(true);
  });

  it('mirrors the two sides around the line', () => {
    const left = inArrow(a, b, 'left', 1);
    const right = inArrow(a, b, 'right', 1);
    left.forEach((p, i) => {
      expect(close(p[1] - 0.5, -(right[i][1] - 0.5))).toBe(true);
    });
  });

  it('keeps the arrow square on screen for a wide picture', () => {
    // A vertical line A (top) → B (bottom): "left" is towards +x as seen walking down the picture.
    const tip = inArrow([0.5, 0.2], [0.5, 0.8], 'left', 2)[3];
    // 0.08 on screen is 0.04 of the width on a picture twice as wide as high.
    expect(close(tip[0], 0.5 + 0.04)).toBe(true);
    expect(close(tip[1], 0.5)).toBe(true);
  });

  it('does not divide by zero when both ends are the same point', () => {
    const arrow = inArrow([0.3, 0.3], [0.3, 0.3], 'left', 1);
    for (const [x, y] of arrow) {
      expect(Number.isFinite(x)).toBe(true);
      expect(Number.isFinite(y)).toBe(true);
    }
  });
});

describe('defaultLink', () => {
  it('is the first allowed link when a link is required', () => {
    expect(defaultLink('cash_drawer')).toBe('till');
    expect(defaultLink('checkout')).toBe('till');
    expect(defaultLink('scan_area')).toBe('till');
  });

  it('is no link when a link is optional', () => {
    expect(defaultLink('bar_counter')).toBe('');
    expect(defaultLink('entrance')).toBe('');
    expect(defaultLink('queue')).toBe('');
  });

  it('agrees with ZONE_LINKS for every zone type', () => {
    for (const t of ZONE_TYPES) {
      expect(defaultLink(t)).toBe(ZONE_LINKS[t].required ? ZONE_LINKS[t].types[0] : '');
    }
  });
});

describe('splitParams', () => {
  it('turns known parameters into text and keeps unknown ones aside', () => {
    const { text, extra } = splitParams('kitchen_pass', {
      classes: ['plate', 'bowl'], preRollSeconds: 20, minConfidence: null, activityResearch: true, custom: { a: 1 },
    });
    expect(text).toEqual({ classes: 'plate, bowl', preRollSeconds: '20', minConfidence: '' });
    expect(extra).toEqual({ activityResearch: true, custom: { a: 1 } });
  });

  it('handles a zone without stored parameters', () => {
    expect(splitParams('entrance', null)).toEqual({ text: {}, extra: {} });
    expect(splitParams('entrance', undefined)).toEqual({ text: {}, extra: {} });
  });
});

describe('paramProblem', () => {
  it('accepts empty fields (the default applies)', () => {
    expect(paramProblem('cash_drawer', {})).toBe(false);
    expect(paramProblem('cash_drawer', { openThreshold: '  ' })).toBe(false);
  });

  it('accepts valid numbers, fractions and lists', () => {
    expect(paramProblem('cash_drawer', { openThreshold: '0.3', minOpenSeconds: '0', method: 'reference_diff' })).toBe(false);
    expect(paramProblem('dining', { classes: 'plate, person', occupancyEverySeconds: '120' })).toBe(false);
  });

  it('rejects negative or non-numeric numbers', () => {
    expect(paramProblem('cash_drawer', { minOpenSeconds: '-1' })).toBe(true);
    expect(paramProblem('cash_drawer', { minOpenSeconds: 'abc' })).toBe(true);
    expect(paramProblem('cash_drawer', { minOpenSeconds: 'Infinity' })).toBe(true);
  });

  it('rejects a fraction above 1 but allows other numbers above 1', () => {
    expect(paramProblem('cash_drawer', { openThreshold: '1.5' })).toBe(true);
    expect(paramProblem('scan_area', { laneMargin: '2' })).toBe(true);
    expect(paramProblem('entrance', { minConfidence: '1' })).toBe(false);
    expect(paramProblem('cash_drawer', { preRollSeconds: '120' })).toBe(false);
  });

  it('rejects a list with no item', () => {
    expect(paramProblem('kitchen_pass', { classes: ' , ,' })).toBe(true);
  });

  it('ignores keys the zone type does not define', () => {
    expect(paramProblem('entrance', { minOpenSeconds: '-5' })).toBe(false);
  });
});

describe('joinParams', () => {
  it('converts the text fields back and leaves empty ones out', () => {
    const out = joinParams('scan_area', {
      classes: 'item, bag ,', laneMargin: '0.2', flowDirection: 'right_to_left', preRollSeconds: '',
    }, { activityResearch: true });
    expect(out).toEqual({
      activityResearch: true, classes: ['item', 'bag'], laneMargin: 0.2, flowDirection: 'right_to_left',
    });
  });

  it('drops text for keys the zone type does not define', () => {
    expect(joinParams('entrance', { minOpenSeconds: '3', classes: 'person' }, {})).toEqual({ classes: ['person'] });
  });

  it('round-trips the defaults of every zone type through split and join', () => {
    for (const t of ZONE_TYPES) {
      const { text, extra } = splitParams(t, ZONE_DEFAULT_PARAMS[t]);
      expect(paramProblem(t, text)).toBe(false);
      expect(joinParams(t, text, extra)).toEqual(ZONE_DEFAULT_PARAMS[t]);
    }
  });
});

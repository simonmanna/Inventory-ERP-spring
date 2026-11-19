import { describe, expect, it } from 'vitest';
import { clickAnchor, keywordResolver, resumeStep, routeMatches, routeReached, tourState, toursForPage, visibleAnchor, visibleTours } from './tour-logic';
import type { TourDef, TourProgress } from './types';

const tour = (over: Partial<TourDef> = {}): TourDef => ({
  key: 't.one', version: 2, module: 'sales', title: 'Create a quotation', summary: 'Make an offer for a customer',
  keywords: ['quote'], permissions: ['sales:quotation:write'], startPath: '/sales/quotations',
  steps: [
    { route: '/sales/quotations', anchor: 'a', title: '', body: '', advanceOn: { click: 'a' } },
    { route: '/sales/quotations/new', anchor: 'b', title: '', body: '', advanceOn: { route: '/sales/quotations/:id' } },
    { route: '/sales/quotations/:id', title: '', body: '', advanceOn: { visible: 'dlg' } },
  ],
  ...over,
});

const progress = (over: Partial<TourProgress> = {}): TourProgress => ({
  tourKey: 't.one', tourVersion: 2, status: 'in_progress', currentStep: 1, startedAt: '', completedAt: null, updatedAt: '',
  ...over,
});

describe('routes', () => {
  it('matches patterns exactly; no route matches everywhere', () => {
    expect(routeMatches('/sales/quotations/:id', '/sales/quotations/abc')).toBe(true);
    expect(routeMatches('/sales/quotations', '/sales/quotations/abc')).toBe(false);
    expect(routeMatches(undefined, '/anything')).toBe(true);
  });

  it('a route step is reached only after leaving its own page', () => {
    const step = tour().steps[1];
    expect(routeReached(step, '/sales/quotations/new')).toBe(false);
    expect(routeReached(step, '/sales/quotations/q-1')).toBe(true);
    expect(routeReached(tour().steps[0], '/sales/quotations/q-1')).toBe(false);
  });

  it('reads the click and visible anchors', () => {
    const [a, b, c] = tour().steps;
    expect(clickAnchor(a)).toBe('a');
    expect(clickAnchor(b)).toBeNull();
    expect(visibleAnchor(c)).toBe('dlg');
  });

  it('lists the tours that start on a page', () => {
    expect(toursForPage([tour()], '/sales/quotations')).toHaveLength(1);
    expect(toursForPage([tour()], '/sales')).toHaveLength(0);
  });
});

describe('permissions', () => {
  it('needs any one of the tour permissions; none means everyone', () => {
    const tours = [tour(), tour({ key: 'open', permissions: [] }), tour({ key: 'two', permissions: ['x', 'y'] })];
    expect(visibleTours(tours, (p) => p === 'y').map((t) => t.key)).toEqual(['open', 'two']);
    expect(visibleTours(tours, () => false).map((t) => t.key)).toEqual(['open']);
  });
});

describe('progress', () => {
  it('derives the state from the server copy', () => {
    expect(tourState(tour(), undefined)).toBe('new');
    expect(tourState(tour(), progress())).toBe('in_progress');
    expect(tourState(tour(), progress({ tourVersion: 1 }))).toBe('new');
    expect(tourState(tour(), progress({ status: 'dismissed' }))).toBe('new');
    expect(tourState(tour(), progress({ status: 'in_progress', completedAt: '2026-11-19T10:00:00Z' }))).toBe('completed');
  });

  it('resumes at the saved step of the same version, clamped', () => {
    expect(resumeStep(tour(), progress())).toBe(1);
    expect(resumeStep(tour(), progress({ currentStep: 99 }))).toBe(2);
    expect(resumeStep(tour(), progress({ tourVersion: 1 }))).toBe(0);
    expect(resumeStep(tour(), undefined)).toBe(0);
  });
});

describe('keyword resolver', () => {
  it('suggests tours whose words match the question, best first', () => {
    const other = tour({ key: 'two', title: 'Sales at a glance', summary: 'How a sale moves', keywords: ['dashboard'] });
    expect(keywordResolver.resolve('how do I make a quote for a customer?', [other, tour()]).map((t) => t.key)).toEqual(['t.one']);
    expect(keywordResolver.resolve('dashboard', [other, tour()]).map((t) => t.key)).toEqual(['two']);
    expect(keywordResolver.resolve('  ', [tour()])).toEqual([]);
  });
});

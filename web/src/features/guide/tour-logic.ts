import { matchPath } from 'react-router-dom';
import type { TourDef, TourProgress, TourStep } from './types';

/** Does `pathname` match the route pattern? A step without a route matches everywhere. */
export function routeMatches(pattern: string | undefined, pathname: string): boolean {
  if (!pattern) return true;
  const m = matchPath({ path: pattern, end: true }, pathname);
  // A record page (`/:id`) is not the "new" form: the app declares `/x/new` before `/x/:id`, so a tour must too.
  return m !== null && !Object.values(m.params).includes('new');
}

/** The chapter the step belongs to: the last chapter heading at or before it. */
export function chapterAt(steps: TourStep[], index: number): string | undefined {
  for (let i = Math.min(index, steps.length - 1); i >= 0; i--) if (steps[i].chapter) return steps[i].chapter;
  return undefined;
}

/** Tours this user may take: any one of the tour's permissions (same rule as RequirePermission). */
export function visibleTours(tours: TourDef[], has: (permission: string) => boolean): TourDef[] {
  return tours.filter((t) => t.permissions.length === 0 || t.permissions.some(has));
}

/** Tours that begin on this page. */
export function toursForPage(tours: TourDef[], pathname: string): TourDef[] {
  return tours.filter((t) => routeMatches(t.steps[0]?.route ?? t.startPath, pathname));
}

/** The anchor whose click finishes the step, if the step advances on a click. */
export function clickAnchor(step: TourStep): string | null {
  return typeof step.advanceOn === 'object' && 'click' in step.advanceOn ? step.advanceOn.click : null;
}

/**
 * Is the step finished by reaching this route? The user must have left the step's own page: `/sales/quotations/:id`
 * also matches `/sales/quotations/new`, and saving a new quotation is what moves the browser from one to the other.
 */
export function routeReached(step: TourStep, pathname: string): boolean {
  return typeof step.advanceOn === 'object' && 'route' in step.advanceOn
    && routeMatches(step.advanceOn.route, pathname) && !(step.route && routeMatches(step.route, pathname));
}

/** The anchor whose appearance finishes the step, if any. */
export function visibleAnchor(step: TourStep): string | null {
  return typeof step.advanceOn === 'object' && 'visible' in step.advanceOn ? step.advanceOn.visible : null;
}

/** Done, in progress or new, from the server copy. A newer tour version counts as new unless it was finished. */
export function tourState(tour: TourDef, progress: TourProgress | undefined): 'new' | 'in_progress' | 'completed' {
  if (!progress) return 'new';
  if (progress.completedAt) return 'completed';
  if (progress.tourVersion !== tour.version || progress.status !== 'in_progress') return 'new';
  return 'in_progress';
}

/** Step to resume at, clamped to the tour. */
export function resumeStep(tour: TourDef, progress: TourProgress | undefined): number {
  if (!progress || progress.tourVersion !== tour.version || progress.status !== 'in_progress') return 0;
  return Math.min(Math.max(0, progress.currentStep), tour.steps.length - 1);
}

/** Question words that say nothing about the task. */
const STOP = new Set(['how', 'what', 'where', 'when', 'why', 'which', 'the', 'and', 'for', 'can', 'does', 'show', 'want',
  'learn', 'this', 'that', 'with', 'from', 'into', 'you', 'your', 'are', 'need', 'please', 'help', 'about']);

const words = (s: string) => s.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter((w) => w.length > 2 && !STOP.has(w));

/**
 * The assistant seam (ADR-S079): turns a question into tours to suggest. Phase 1 matches words against each tour's
 * title, summary and keywords; a later phase can answer through a model and return the same shape.
 */
export interface AssistantResolver {
  resolve(question: string, tours: TourDef[]): TourDef[];
}

export const keywordResolver: AssistantResolver = {
  resolve(question, tours) {
    const q = words(question);
    if (q.length === 0) return [];
    return tours
      .map((t) => {
        const hay = new Set(words([t.title, t.summary, ...t.keywords].join(' ')));
        const score = q.reduce((s, w) => s + ([...hay].some((h) => h.startsWith(w) || w.startsWith(h)) ? 1 : 0), 0);
        return { t, score };
      })
      .filter((x) => x.score > 0)
      .sort((a, b) => b.score - a.score)
      .map((x) => x.t);
  },
};

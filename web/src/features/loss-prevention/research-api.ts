import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { Filters, Paged } from './api';

/**
 * Activity recognition research (LP7, shadow mode): what the edge agent's recognizers report in zones marked for
 * research (`params.activityResearch`), and people's reviews of it. Every route needs the `lpVision` licence feature
 * and `lp:investigate`. No rule reads an activity, so it never makes a finding or a case; an activity is about what
 * happens to objects in a zone, never about who was there.
 */
type Num = number | string | null;

export type Verdict = 'correct' | 'incorrect' | 'unsure';
export const VERDICTS: Verdict[] = ['correct', 'incorrect', 'unsure'];
/** The list filter also takes `unreviewed`: activities nobody has reviewed yet. */
export const VERDICT_FILTERS = ['unreviewed', ...VERDICTS] as const;

/** An activity name, and the label a reviewer gives instead: as the agent sends `payload.activity`. */
export const ACTIVITY_LABEL_RE = /^[a-z][a-z0-9_]{0,63}$/;
export const MAX_REVIEW_NOTE = 1000;

export interface ActivityReview {
  id: string;
  verdict: Verdict;
  label: string | null;
  note: string | null;
  reviewedBy: string | null;
  reviewerName: string | null;
  createdAt: string;
}

export interface Activity {
  eventId: string;
  seq: number | string;
  branchId: string | null;
  zoneId: string;
  zoneName: string | null;
  zoneType: string | null;
  cameraId: string | null;
  activity: string;
  /** `name@version` of the recognizer that reported it. */
  recognizer: string;
  score: Num;
  startedAt: string;
  endedAt: string;
  occurredAt: string;
  /** A clip the agent kept on the edge box, when it kept one. */
  clipId: string | null;
  /** How many reviews it has; the latest is `review`. */
  reviews: number;
  review: ActivityReview | null;
}

export interface ActivitySummaryRow {
  recognizer: string;
  activity: string;
  events: number;
  reviewed: number;
  correct: number;
  incorrect: number;
  unsure: number;
  /** correct ÷ (correct + incorrect); null while neither was given. */
  precision: Num;
}

export interface ActivitySummary {
  from: string;
  to: string;
  rows: ActivitySummaryRow[];
  /** What reviewers said an activity really was, when they marked it incorrect. */
  relabels: { recognizer: string; activity: string; label: string; count: number }[];
  note: string;
}

export interface ReviewInput {
  verdict: Verdict;
  label?: string;
  note?: string;
}

export interface SavedReview {
  id: string;
  eventId: string;
  verdict: Verdict;
  label: string | null;
  note: string | null;
  reviewedBy: string | null;
  createdAt: string;
}

const KEY = ['loss-prevention', 'research'] as const;
const BASE = '/loss-prevention/research';

/** Newest first; `verdict` filters on the latest review (`unreviewed`: none yet). */
export const useLpActivities = (filters: Filters, enabled = true) => useQuery({
  queryKey: [...KEY, 'activities', filters],
  enabled,
  queryFn: async () => (await api.get<Paged<Activity>>(`${BASE}/activities`, { params: filters })).data,
});

/** Per recognizer and activity: how many were reported, reviewed, right and wrong. Default: the last 30 days. */
export const useLpActivitySummary = (filters: Filters, enabled = true) => useQuery({
  queryKey: [...KEY, 'summary', filters],
  enabled,
  queryFn: async () => (await api.get<ActivitySummary>(`${BASE}/activities/summary`, { params: filters })).data,
});

/** Adds a review; earlier reviews are kept and the latest one counts. */
export const useReviewLpActivity = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (v: { eventId: string; body: ReviewInput }) =>
      (await api.post<SavedReview>(`${BASE}/activities/${encodeURIComponent(v.eventId)}/review`, v.body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
};

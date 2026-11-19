/**
 * In-app guided tours (ADR-S079). A tour walks the user through a task on the real screens: each step points at an
 * element marked `data-tour="<anchor>"`, explains it, and moves on when the user has done what it asks.
 */

/** When a step is done. */
export type Advance =
  /** The user presses Next. */
  | 'next'
  /** The user clicks the step's anchor (or another anchor). */
  | { click: string }
  /** The browser reaches a route matching this pattern (`/sales/quotations/:id`). */
  | { route: string }
  /** An element with this anchor appears (e.g. a dialog opened). */
  | { visible: string };

export interface TourStep {
  /** Route pattern the step lives on (react-router syntax). Absent: any page. */
  route?: string;
  /** `data-tour` value of the element to highlight. Absent: a centred card. */
  anchor?: string;
  title: string;
  body: string;
  placement?: 'top' | 'bottom' | 'left' | 'right';
  advanceOn: Advance;
  /** Shown as a chapter heading in long tours. */
  chapter?: string;
}

export interface TourDef {
  key: string;
  /** Bump when the steps change materially, so saved progress restarts. */
  version: number;
  module: 'sales';
  title: string;
  summary: string;
  /** Search words besides the title and summary. */
  keywords: string[];
  /** The user needs any one of these (same rule as RequirePermission). Empty: everyone. */
  permissions: string[];
  /** Static path the "Start" button opens before step 1. */
  startPath: string;
  steps: TourStep[];
}

export type ProgressStatus = 'in_progress' | 'completed' | 'dismissed';

/** Server copy of a user's progress (GET /guide/progress). */
export interface TourProgress {
  tourKey: string;
  tourVersion: number;
  status: ProgressStatus;
  currentStep: number;
  startedAt: string;
  completedAt: string | null;
  updatedAt: string;
}

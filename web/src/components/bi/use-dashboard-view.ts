import { useCallback, useState } from 'react';

export type DashboardViewMode = 'summary' | 'explore';

const KEY = 'erp.analytics.view';

function read(): DashboardViewMode {
  try {
    return localStorage.getItem(KEY) === 'summary' ? 'summary' : 'explore';
  } catch {
    return 'explore';
  }
}

/**
 * Which view an analytics screen opens in: the interactive Superset dashboard by default (ADR-S078), or the built-in
 * summary when the user last picked it. Remembered per browser only; a screen without a dashboard shows the summary.
 */
export function useDashboardView(): [DashboardViewMode, (view: DashboardViewMode) => void] {
  const [view, setView] = useState<DashboardViewMode>(read);
  const change = useCallback((next: DashboardViewMode) => {
    setView(next);
    try {
      localStorage.setItem(KEY, next);
    } catch {
      // Storage blocked: the choice lasts for this page only.
    }
  }, []);
  return [view, change];
}

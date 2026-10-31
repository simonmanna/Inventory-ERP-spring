import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { AnalyticsQuery } from './api';

const pad = (n: number) => String(n).padStart(2, '0');
const local = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

const FIELDS = ['branchId', 'siteId', 'deliverySiteId', 'productId', 'partnerId'] as const;

/** The filters live in the URL, so a dashboard can be bookmarked and survives moving between dashboards. */
export function useAnalyticsFilters() {
  const [params, setParams] = useSearchParams();
  const query = useMemo<AnalyticsQuery>(() => {
    const now = new Date();
    const q: AnalyticsQuery = {
      from: params.get('from') || local(new Date(now.getFullYear(), now.getMonth(), 1)),
      to: params.get('to') || local(now),
    };
    for (const f of FIELDS) {
      const v = params.get(f);
      if (v) q[f] = v;
    }
    return q;
  }, [params]);
  const update = useCallback((patch: Partial<AnalyticsQuery>) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      for (const [k, v] of Object.entries(patch)) {
        if (v) next.set(k, v);
        else next.delete(k);
      }
      return next;
    }, { replace: true });
  }, [setParams]);
  return { query, update, search: params.toString() };
}

import { keepPreviousData, useQueries, useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useAuthStore } from '@/stores/auth.store';
import type { Bucket } from './period';

type Params = Record<string, string | number | undefined>;

/**
 * A read for a dashboard panel. It only runs when the user holds the route's permission: a 403 would otherwise raise
 * an error toast for every panel the user may not see. `allowed` lets the panel hide itself.
 */
export function useOps<T>(path: string, params: Params | undefined, permission: string | true, refetchMs?: number) {
  const allowed = useAuthStore((s) => permission === true || s.hasPermission(permission));
  const query = useQuery({
    queryKey: ['ops-dashboard', path, params ?? {}],
    queryFn: async () => (await api.get<T>(path, { params })).data,
    enabled: allowed,
    retry: false,
    staleTime: 60_000,
    placeholderData: keepPreviousData,
    refetchInterval: refetchMs,
  });
  return { ...query, allowed };
}

/** The same read once per bucket, for reports with no time grouping of their own. Results keep bucket order. */
export function useOpsBuckets<T>(
  path: string,
  buckets: Bucket[],
  toParams: (b: Bucket) => Params,
  permission: string | true,
) {
  const allowed = useAuthStore((s) => permission === true || s.hasPermission(permission));
  const results = useQueries({
    queries: buckets.map((b) => {
      const params = toParams(b);
      return {
        queryKey: ['ops-dashboard', path, params],
        queryFn: async () => (await api.get<T>(path, { params })).data,
        enabled: allowed,
        retry: false,
        staleTime: 60_000,
      };
    }),
  });
  return {
    allowed,
    isLoading: results.some((r) => r.isLoading),
    rows: buckets.map((bucket, i) => ({ bucket, data: results[i]?.data as T | undefined })),
  };
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

/**
 * HR & payroll HP-1…HP-10 endpoints. One small read hook and one write hook keep the screens thin; every write
 * invalidates the whole `['hr', 'pro']` cache so lists refresh after approvals, runs and settlements.
 */
export const HR_PRO_KEY = ['hr', 'pro'] as const;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type Row = Record<string, any>;
export interface Page<T = Row> {
  rows: T[];
  total: number;
}

export function useHrGet<T = Page>(path: string | null, params?: Record<string, unknown>) {
  return useQuery({
    queryKey: [...HR_PRO_KEY, path, params],
    queryFn: async () => (await api.get<T>(path as string, { params })).data,
    enabled: !!path,
  });
}

export function useHrSend<B = unknown, R = Row>(method: 'post' | 'patch' | 'put' | 'delete') {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ path, body }: { path: string; body?: B }) =>
      method === 'delete' ? (await api.delete<R>(path)).data : (await api[method]<R>(path, body ?? {})).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: HR_PRO_KEY });
      qc.invalidateQueries({ queryKey: ['hr', 'payroll'] });
    },
  });
}

/** Download a file route (PDF, CSV, SIF) with the session's auth and save it. */
export async function downloadHrFile(path: string, fallbackName: string) {
  const res = await api.get(path, { responseType: 'blob' });
  const header = String(res.headers['content-disposition'] ?? '');
  const match = /filename="?([^";]+)"?/.exec(header);
  const url = URL.createObjectURL(res.data as Blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = match?.[1] ?? fallbackName;
  a.click();
  URL.revokeObjectURL(url);
}

export const money = (v: unknown, digits = 0) =>
  v === null || v === undefined || v === '' ? '—' : Number(v).toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits });

export const day = (v: unknown) => (v ? String(v).slice(0, 10) : '—');

export const fullName = (r: Row) => [r.firstName, r.lastName].filter(Boolean).join(' ');

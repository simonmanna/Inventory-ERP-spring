import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

/** Scheduled alerts and exception reports (ADR-S021), and the BI datasets. */
export interface AlertRule {
  alertType: string;
  label: string;
  cadence: 'hourly' | 'daily';
  thresholdUnit: 'percent' | 'days' | 'hours' | null;
  thresholdHelp: string | null;
  defaultThreshold: number | string | null;
  enabled: boolean;
  threshold: number | string | null;
  repeatEvery: 'daily' | 'weekly' | 'monthly';
  channels: string[];
  recipientPermission: string;
  recipientUserIds: string[];
  customized: boolean;
}

export interface AlertRuleInput {
  enabled: boolean;
  threshold: number | null;
  repeatEvery: string;
  channels: string[];
  recipientPermission: string | null;
  recipientUserIds: string[];
}

export interface AlertEvent {
  id: string;
  alertType: string;
  severity: 'info' | 'warning' | 'critical';
  title: string;
  body: string | null;
  link: string | null;
  status: 'open' | 'acknowledged';
  periodKey: string;
  recipients: number;
  raisedAt: string;
}

export interface ReportRow {
  alertType: string;
  total: number;
  critical: number;
  open: number;
  lastRaisedAt: string;
}

export interface Paged<T> {
  data: T[];
  meta: { page: number; pageSize: number; total: number; totalPages: number };
}

export interface BiDataset {
  key: string;
  label: string;
  view: string;
  dateColumn: string | null;
  columns: string[];
}

export type EventFilters = Record<string, string | number | undefined>;

const KEY = ['alerts'] as const;

export const useAlertRules = () => useQuery({
  queryKey: [...KEY, 'rules'],
  queryFn: async () => (await api.get<AlertRule[]>('/alerts/rules')).data,
});

export const useAlertEvents = (filters: EventFilters) => useQuery({
  queryKey: [...KEY, 'events', filters],
  queryFn: async () => (await api.get<Paged<AlertEvent>>('/alerts/events', { params: filters })).data,
});

export const useExceptionReport = (filters: EventFilters) => useQuery({
  queryKey: [...KEY, 'report', filters],
  queryFn: async () => (await api.get<{ rows: ReportRow[] }>('/alerts/exception-report', { params: filters })).data,
});

function useChange<V>(fn: (v: V) => Promise<unknown>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => { qc.invalidateQueries({ queryKey: KEY }); } });
}

export const useSaveAlertRule = () => useChange((v: { type: string; body: AlertRuleInput }) =>
  api.put(`/alerts/rules/${v.type}`, v.body).then((r) => r.data));
export const useAcknowledgeAlert = () => useChange((id: string) =>
  api.post(`/alerts/events/${id}/acknowledge`).then((r) => r.data));
export const useRunAlerts = () => useChange((_: void) => api.post('/alerts/run', {}).then((r) => r.data));

export const useBiDatasets = () => useQuery({
  queryKey: ['bi-datasets'],
  queryFn: async () => (await api.get<BiDataset[]>('/bi/datasets')).data,
});

async function saveCsv(path: string, params: EventFilters, fallback: string) {
  const res = await api.get<Blob>(path, { params, responseType: 'blob' });
  const match = /filename="([^"]+)"/.exec(String(res.headers?.['content-disposition'] ?? ''));
  const url = URL.createObjectURL(res.data);
  const a = document.createElement('a');
  a.href = url;
  a.download = match?.[1] ?? fallback;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export const downloadAlerts = (filters: EventFilters) => saveCsv('/alerts/events/export.csv', filters, 'alerts.csv');
export const downloadDataset = (key: string, filters: EventFilters) =>
  saveCsv(`/bi/datasets/${key}/export.csv`, filters, `${key}.csv`);

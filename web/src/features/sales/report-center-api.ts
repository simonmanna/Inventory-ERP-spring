import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';

/** Sales report center (ADR-S057) wire types. */
export type ColumnType = 'text' | 'date' | 'money' | 'number' | 'qty' | 'percent' | 'days' | 'status' | 'count' | 'auto';
export interface ReportColumn { key: string; label: string; type: ColumnType }
export interface ReportKpi { key: string; label: string; value: number | string | null; type: ColumnType; hint: string | null }
export interface ReportSeries { key: string; label: string; kind: 'bar' | 'line' | 'area' }
export interface ReportChartSpec { kind: 'bar' | 'hbar' | 'line' | 'area' | 'pie' | 'combo' | 'none'; x: string | null; series: ReportSeries[]; stacked: boolean }
export type ReportRow = Record<string, unknown> & { _route?: string; _drill?: { report: string; params: Record<string, string> } };
export interface ReportResult {
  key: string; category: string; title: string; description: string; from: string; to: string;
  columns: ReportColumn[]; rows: ReportRow[]; totals: Record<string, unknown>; kpis: ReportKpi[];
  chart: ReportChartSpec; chartData: ReportRow[] | null; notes: string[]; filters: string[]; generatedAt: string;
}
export interface ReportDef { key: string; category: string; title: string; description: string; permission: string; filters: string[] }
export interface ReportCategory { key: string; title: string; description: string; reports: ReportDef[] }
export interface ReportCatalog { categories: ReportCategory[]; count: number }
export interface Lookup { id: string; name: string }
export interface ReportLookups {
  branches: Lookup[]; salespeople: Lookup[]; customers: Lookup[]; products: Lookup[];
  categories: Lookup[]; customerCategories: Lookup[]; channels: Lookup[];
}
export interface DashboardKpi { key: string; label: string; value: number | null; type: ColumnType; sub: number | string | null; report: string }
export type DashboardTop = ReportRow & { id: string | null; label: string; revenue: number; margin: number; quantity: number };
export interface ReportDashboard {
  from: string; to: string; kpis: DashboardKpi[];
  trend: Array<{ label: string; revenue: number; margin: number; discount: number }>;
  topCustomers: DashboardTop[]; topProducts: DashboardTop[]; topSalespeople: DashboardTop[];
  byBranch: DashboardTop[]; byCategory: DashboardTop[]; byChannel: DashboardTop[];
}

export type ReportParams = Record<string, string>;

const BASE = '/sales/report-center';

export function useReportCatalog() {
  return useQuery({ queryKey: ['sales', 'report-center', 'catalog'], staleTime: 5 * 60_000, queryFn: async () => (await api.get<ReportCatalog>(`${BASE}/catalog`)).data });
}

export function useReportLookups() {
  return useQuery({ queryKey: ['sales', 'report-center', 'lookups'], staleTime: 5 * 60_000, queryFn: async () => (await api.get<ReportLookups>(`${BASE}/lookups`)).data });
}

export function useReportRun(key: string | undefined, params: ReportParams) {
  return useQuery({
    queryKey: ['sales', 'report-center', 'run', key, params],
    enabled: !!key,
    placeholderData: keepPreviousData,
    queryFn: async () => (await api.get<ReportResult>(`${BASE}/run/${key}`, { params })).data,
  });
}

export function useReportDashboard(params: ReportParams) {
  return useQuery({
    queryKey: ['sales', 'report-center', 'dashboard', params],
    placeholderData: keepPreviousData,
    queryFn: async () => (await api.get<ReportDashboard>(`${BASE}/dashboard`, { params })).data,
  });
}

/** Downloads the server-rendered Excel / CSV export. */
export async function downloadReport(key: string, params: ReportParams, format: 'xlsx' | 'csv') {
  const res = await api.get<Blob>(`${BASE}/export/${key}`, { params: { ...params, format }, responseType: 'blob' });
  const disposition = String(res.headers['content-disposition'] ?? '');
  const name = /filename="([^"]+)"/.exec(disposition)?.[1] ?? `${key}.${format}`;
  const url = URL.createObjectURL(res.data);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

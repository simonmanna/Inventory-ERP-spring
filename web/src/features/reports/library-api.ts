import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { PaginatedResult } from '@erp/shared';

/** Financial report library (FR-0, ADR-S043). */

export type FilterKey =
  | 'PERIOD' | 'AS_OF' | 'BRANCH' | 'COST_CENTER' | 'PARTNER' | 'ACCOUNT' | 'JOURNAL' | 'SOURCE'
  | 'DIMENSION' | 'POSTING_STATUS' | 'COMPARE' | 'LAYOUT';

export interface OptionSpec {
  key: string;
  label: string;
  type: 'text' | 'number' | 'select' | 'date' | 'budget';
  choices: string[];
  defaultValue: string | null;
}

export interface ReportDefinition {
  id: string;
  category: string;
  title: string;
  description: string | null;
  level: 'FINANCIAL' | 'OPERATIONAL' | 'MANAGEMENT';
  permission: string;
  filters: FilterKey[];
  engine: boolean;
  webRoute: string | null;
  apiRoute: string | null;
  options: OptionSpec[];
}

export interface LibraryCategory {
  code: string;
  key: string;
  title: string;
  reports: ReportDefinition[];
}

export interface ReportRequest {
  period?: string;
  from?: string;
  to?: string;
  asOf?: string;
  branchIds?: string[];
  costCenterIds?: string[];
  partnerIds?: string[];
  accountIds?: string[];
  journalIds?: string[];
  sourceTypes?: string[];
  dimensions?: Record<string, string[]>;
  postingStatus?: 'POSTED' | 'ALL';
  compare?: string[];
  layoutCode?: string;
  page?: number;
  limit?: number;
  options?: Record<string, string>;
}

export interface ReportColumn { key: string; label: string; type: 'text' | 'date' | 'money' | 'number' | 'percent' }
export interface Drill { reportId: string | null; params: ReportRequest | null; route: string | null }
export interface ReportRow {
  id: string;
  kind: 'header' | 'line' | 'account' | 'subtotal' | 'total' | 'kpi';
  depth: number;
  bold: boolean;
  values: Record<string, string | number | null>;
  drill: Record<string, Drill>;
}
export interface ReportCheck { code: string; label: string; ok: boolean; detail: string | null }
export interface ReportHeader {
  company: string | null;
  reportId: string;
  title: string;
  category: string;
  level: string;
  period: string;
  from: string | null;
  to: string | null;
  asOf: string | null;
  currency: string | null;
  basis: string;
  branches: string[];
  postingStatus: string;
  generatedAt: string;
  generatedBy: string | null;
  runId: string;
  resultHash: string;
}
export interface ReportResult {
  header: ReportHeader;
  columns: ReportColumn[];
  rows: ReportRow[];
  totals: Record<string, unknown>;
  checks: ReportCheck[];
  meta: Record<string, unknown>;
}
export interface DefinitionInfo {
  definition: ReportDefinition;
  periodPresets: string[];
  compareOptions: string[];
  layouts: { code: string; name: string; kind: string; isSystem: boolean }[];
  exportFormats: string[];
  canSchedule: boolean;
}
export interface SavedView { id: string; reportId: string; name: string; params: ReportRequest; ownerId: string; shared: boolean }
export interface ReportSchedule {
  id: string; reportId: string; name: string; format: string; frequency: string; dayOfWeek: number | null;
  dayOfMonth: number | null; hourOfDay: number; recipients: string[]; isActive: boolean; nextRunAt: string;
  lastRunAt: string | null; lastStatus: string | null; lastError: string | null;
}
export interface Option { id: string; name: string; code?: string }

const KEY = ['report-library'];

/** Drop empty values so the engine sees only the filters that are set. */
export function cleanRequest(r: ReportRequest): ReportRequest {
  const out: Record<string, unknown> = {};
  Object.entries(r).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return;
    if (Array.isArray(v) && v.length === 0) return;
    if (typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === 0) return;
    out[k] = v;
  });
  return out as ReportRequest;
}

export function useReportLibrary() {
  return useQuery({
    queryKey: [...KEY, 'library'],
    queryFn: async () => (await api.get<LibraryCategory[]>('/reports/library')).data,
  });
}

export function useReportDefinition(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'definition', id],
    queryFn: async () => (await api.get<DefinitionInfo>(`/reports/library/${id}`)).data,
    enabled: !!id,
  });
}

export function useRunReport(id: string | undefined, request: ReportRequest | null) {
  return useQuery({
    queryKey: [...KEY, 'run', id, request],
    queryFn: async () => (await api.post<ReportResult>(`/reports/run/${id}`, cleanRequest(request ?? {}))).data,
    enabled: !!id && request !== null,
    staleTime: 30_000,
  });
}

export async function downloadLibraryReport(id: string, format: string, request: ReportRequest) {
  const res = await api.post<Blob>(`/reports/run/${id}/export`, cleanRequest(request), {
    params: { format },
    responseType: 'blob',
  });
  const disposition = String(res.headers?.['content-disposition'] ?? '');
  const match = /filename="([^"]+)"/.exec(disposition);
  const url = URL.createObjectURL(res.data);
  const a = document.createElement('a');
  a.href = url;
  a.download = match?.[1] ?? `${id}.${format}`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function useVerifyRun() {
  return useMutation({
    mutationFn: async (runId: string) =>
      (await api.post<{ reproducible: boolean; originalHash: string; currentHash: string }>(`/reports/runs/${runId}/verify`)).data,
  });
}

export function useSavedViews(reportId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'views', reportId],
    queryFn: async () => (await api.get<SavedView[]>('/reports/views', { params: { reportId } })).data,
    enabled: !!reportId,
  });
}

export function useCreateView() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { reportId: string; name: string; params: ReportRequest; shared?: boolean }) =>
      (await api.post<SavedView>('/reports/views', { ...body, params: cleanRequest(body.params) })).data,
    onSuccess: (_d, v) => qc.invalidateQueries({ queryKey: [...KEY, 'views', v.reportId] }),
  });
}

export function useDeleteView(reportId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => (await api.delete(`/reports/views/${id}`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: [...KEY, 'views', reportId] }),
  });
}

export function useSchedules(enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'schedules'],
    queryFn: async () => (await api.get<ReportSchedule[]>('/reports/schedules')).data,
    enabled,
  });
}

export function useCreateSchedule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: {
      reportId: string; name: string; params: ReportRequest; format: string; frequency: string;
      dayOfWeek?: number; dayOfMonth?: number; hourOfDay?: number; recipients: string[];
    }) => (await api.post<ReportSchedule>('/reports/schedules', { ...body, params: cleanRequest(body.params) })).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: [...KEY, 'schedules'] }),
  });
}

export function useDeleteSchedule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => (await api.delete(`/reports/schedules/${id}`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: [...KEY, 'schedules'] }),
  });
}

export function useBranchOptions(enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'branches'],
    queryFn: async () => (await api.get<PaginatedResult<Option>>('/branches', { params: { pageSize: 200 } })).data.data,
    enabled,
  });
}

export function useCostCenterOptions(enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'cost-centers'],
    queryFn: async () => (await api.get<PaginatedResult<Option>>('/cost-centers', { params: { pageSize: 200 } })).data.data,
    enabled,
  });
}

export function useAccountOptions(enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'accounts'],
    queryFn: async () => (await api.get<PaginatedResult<Option>>('/accounts', { params: { pageSize: 200 } })).data.data,
    enabled,
  });
}

export function usePartnerOptions(enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'partners'],
    queryFn: async () => (await api.get<PaginatedResult<Option>>('/partners', { params: { pageSize: 200 } })).data.data,
    enabled,
  });
}

export function useJournalOptions(enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'journals'],
    queryFn: async () => (await api.get<PaginatedResult<Option>>('/journals', { params: { pageSize: 100 } })).data.data,
    enabled,
  });
}

export function useBudgetOptions(enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'budgets'],
    queryFn: async () => (await api.get<PaginatedResult<Option>>('/budgets', { params: { pageSize: 100 } })).data.data,
    enabled,
  });
}

/** Encode a request for the viewer URL (drill-down, bookmarks). */
export function encodeRequest(r: ReportRequest): string {
  return encodeURIComponent(JSON.stringify(cleanRequest(r)));
}

export function decodeRequest(q: string | null): ReportRequest | null {
  if (!q) return null;
  try {
    return JSON.parse(decodeURIComponent(q)) as ReportRequest;
  } catch {
    return null;
  }
}

import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';

/** Wire types of the analytics routes (ADR-S020). Values stay as sent: money and tonnes may be strings. */
export type Format = 'tonnes' | 'qty' | 'money' | 'rate' | 'percent' | 'count' | 'hours' | 'date' | 'text';

export interface Tile { key: string; value: string | number | null; format: Format; drill: string | null }
export interface Series { key: string; label: string | null }
export type Row = Record<string, unknown>;
export interface Chart { key: string; type: 'bar' | 'line'; xKey: string; format: Format; series: Series[]; rows: Row[] }
export interface Column { key: string; format: Format }
export interface DashboardTable {
  key: string;
  columns: Column[];
  rows: Row[];
  drill: string | null;
  drillParam: string | null;
  rowKey: string | null;
}
export interface Dashboard {
  domain: string;
  from: string;
  to: string;
  supportedFilters: string[];
  ignoredFilters: string[];
  tiles: Tile[];
  charts: Chart[];
  tables: DashboardTable[];
  notes: string[];
}

export interface DocumentRow {
  type: string;
  id: string;
  number: string | null;
  date: string | null;
  party: string | null;
  status: string | null;
  quantity: string | number | null;
  amount: string | number | null;
}

export interface AnalyticsQuery {
  from: string;
  to: string;
  branchId?: string;
  siteId?: string;
  deliverySiteId?: string;
  productId?: string;
  partnerId?: string;
}

export interface Option { id: string; code: string | null; name: string }
export interface FilterOptions {
  branches: Option[];
  sites: Option[];
  products: Option[];
  customers: Option[];
  suppliers: Option[];
  deliverySites: Option[];
}

export function useDashboard(path: string, query: AnalyticsQuery) {
  return useQuery({
    queryKey: ['analytics', path, query],
    queryFn: async () => (await api.get<Dashboard>(`/analytics/${path}`, { params: query })).data,
    placeholderData: keepPreviousData,
    staleTime: 60_000,
    retry: false,
  });
}

export function useFilterOptions(partnerId?: string) {
  return useQuery({
    queryKey: ['analytics', 'filter-options', partnerId ?? ''],
    queryFn: async () => (await api.get<FilterOptions>('/analytics/filter-options', { params: { partnerId } })).data,
    staleTime: 300_000,
  });
}

export function useDocuments(path: string, kpi: string, query: AnalyticsQuery, page: number, pageSize: number) {
  return useQuery({
    queryKey: ['analytics', path, 'documents', kpi, query, page, pageSize],
    queryFn: async () => (await api.get<PaginatedResult<DocumentRow>>(`/analytics/${path}/documents`, {
      params: { ...query, kpi, page, pageSize },
    })).data,
    placeholderData: keepPreviousData,
  });
}

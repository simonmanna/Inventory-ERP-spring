import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';

export interface SupplierPerformanceRow {
  partnerId: string;
  partnerName: string | null;
  orders: number;
  spend: string;
  receipts: number;
  onTimeRate: number | null;
  avgDaysLate: number | null;
  fillRate: number | null;
  inspectionLots: number;
  qualityRate: number | null;
  rejectedQty: string;
  priceVariance: string;
  priceVarianceRate: number | null;
  score: number | null;
}

export interface SupplierPerformance {
  from: string;
  to: string;
  rows: SupplierPerformanceRow[];
  totals: { suppliers: number; spend: string };
}

export function useSupplierPerformance(params: { from?: string; to?: string; partnerId?: string }) {
  return useQuery({
    queryKey: ['supplier-performance', params],
    queryFn: async () =>
      (await api.get<SupplierPerformance>('/procurement/reports/supplier-performance', { params })).data,
  });
}

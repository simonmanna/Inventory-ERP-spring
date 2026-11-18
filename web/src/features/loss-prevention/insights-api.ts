import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { Filters } from './api';

/**
 * Loss prevention operations insights (LP6a): kitchen times, sales, void/refund/discount rates, table turnover, cash
 * accuracy and pour yield, computed from the event ledger. Every route needs the `lpInsights` licence feature. A rate
 * whose denominator is zero comes back as null ("no data").
 *
 * LP6b adds figures counted by cameras (`lpVision` zones): footfall and conversion, queues, table occupancy. They count
 * people and tables and never identify anyone; how accurately a camera counts is not measured (`cameraNote`).
 */
type Num = number | string | null;

export interface KitchenFigures {
  tickets: number;
  prepared: number;
  averagePrepSeconds: Num;
  worstDayP90PrepSeconds: Num;
  dispatched: number;
  averageServeSeconds: Num;
  worstDayP90ServeSeconds: Num;
  recalled: number;
  recallRate: Num;
}

interface RateFigures { count: number; amount: Num; per100Sales: Num; amountRate: Num }

export interface SalesFigures {
  count: number;
  total: Num;
  averageTicket: Num;
  items: Num;
  itemsPerSale: Num;
  voids: RateFigures;
  refunds: RateFigures;
  discounts: { sales: number; amount: Num; shareOfSales: Num; rate: Num };
}

export interface InsightMetrics {
  kitchen: KitchenFigures;
  sales: SalesFigures;
  tables: { orders: number; total: Num; tableDays: number; turnsPerTablePerDay: Num; averagePerOrder: Num };
  cash: {
    sessions: number; balanced: number; short: number; over: number; shortAmount: Num; overAmount: Num;
    tolerance: Num; withinTolerance: number | null; withinToleranceRate: Num; balancedRate: Num;
  };
  pour: {
    lines: number; measuredLines: number; soldMl: Num; consumedMl: Num; yield: Num; consumedCost: Num;
    linesWithCost: number;
  };
  /** LP6b; absent from a server without camera metrics. */
  camera?: CameraMetrics;
}

/** People in and out at entrances, and sales on the days with footfall (conversion = sales ÷ people in). */
export interface FootfallFigures { in: number; out: number; sales: number; conversionRate: Num }

export interface CameraMetrics {
  footfall: FootfallFigures;
  queues: { waits: number; averageWaitSeconds: Num; worstDayP90WaitSeconds: Num; averageLength: Num };
  tables: {
    occupancyRate: Num; seatings: number; ordered: number; averageWaitToOrderSeconds: Num;
    worstDayP90WaitToOrderSeconds: Num;
  };
}

/** Days with data per area; `historical: false` = only events projected after LP6a carry what the area needs. */
interface AreaCoverage { periodDays: number; daysWithData: number; historical: boolean }

export interface InsightsCoverage {
  kitchen: AreaCoverage;
  sales: AreaCoverage;
  cash: AreaCoverage;
  pour: AreaCoverage;
  tables: AreaCoverage & {
    sales: number; salesRecordingTable: number; recordedShare: Num; firstRecordedDay: string | null; partial: boolean;
  };
  /** LP6b: days with camera counts, time without a usable picture; `measured` is false until a pilot reviews them. */
  camera?: { periodDays: number; daysWithData: number; gapSeconds: Num; measured: boolean };
}

export interface InsightsSummary {
  from: string;
  to: string;
  status: { pendingDays: number; failedDays: number; lastComputedAt: string | null };
  metrics: InsightMetrics;
  coverage: InsightsCoverage;
  previous: { from: string; to: string; metrics: InsightMetrics };
  trend: {
    day: string; sales: number; total: Num; averageTicket: Num; voidRate: Num; refundRate: Num;
    kitchenTickets: number; averagePrepSeconds: Num; p90PrepSeconds: Num;
  }[];
  definitions: Record<string, string>;
  note: string;
  /** LP6b: what camera figures are and are not. */
  cameraNote?: string;
}

/** The parts every camera report (LP6b) shares. */
interface CameraReport {
  from: string;
  to: string;
  definitions: Record<string, string>;
  note: string;
  cameraNote: string;
}

export interface FootfallReport extends CameraReport {
  branchId: string | null;
  totals: FootfallFigures;
  days: (FootfallFigures & { day: string })[];
  hours: (FootfallFigures & { hour: number })[];
  zones: FootfallZoneRow[];
}
/** An entrance zone's counts; `cameraName` is the camera the zone is drawn on. */
export interface FootfallZoneRow {
  zoneId: string; name: string | null; cameraName: string | null; in: number; out: number; gapSeconds: Num;
}

export interface QueueRow {
  zoneId: string; name: string | null; tillId: string | null; waits: number; averageWaitSeconds: Num;
  worstDayP90WaitSeconds: Num; observedSeconds: Num; averageLength: Num; worstDayP90Length: Num; gapSeconds: Num;
}
export interface QueueHourRow { hour: number; waits: number; averageWaitSeconds: Num; averageLength: Num }
export interface QueuesReport extends CameraReport { rows: QueueRow[]; hours: QueueHourRow[] }

export interface OccupancyRow {
  zoneId: string; name: string | null; linkedType: string | null; linkedId: string | null; tableName: string | null;
  tableNumber: number | null; observedSeconds: Num; occupiedSeconds: Num; occupancyRate: Num; seatings: number;
  ordered: number; averageWaitToOrderSeconds: Num; worstDayP90WaitToOrderSeconds: Num; gapSeconds: Num;
}
export interface OccupancyHourRow { hour: number; occupancyRate: Num; seatings: number; averageWaitToOrderSeconds: Num }
export interface OccupancyReport extends CameraReport { rows: OccupancyRow[]; hours: OccupancyHourRow[] }

export interface HourRow {
  hour: number; sales: number; total: Num; averageTicket: Num; voids: number; refunds: number;
  kitchenTickets: number; averagePrepSeconds: Num; averageServeSeconds: Num;
}
export type StationRow = KitchenFigures & { station: string | null };
export type StaffRow = SalesFigures & { role?: string | null; people?: number; actorId?: string | null; name?: string | null };
export interface TableRow {
  tableId: string; name: string | null; number: number | null; orders: number; total: Num; averagePerOrder: Num;
  daysUsed: number; turnsPerDayUsed: Num;
}
export type PourRow = InsightMetrics['pour'] & { productId: string | null; productName: string | null };

const KEY = ['loss-prevention', 'insights'] as const;
const BASE = '/loss-prevention/insights';

const get = async <T,>(path: string, params: Filters) => (await api.get<T>(`${BASE}${path}`, { params })).data;

export const useInsightsSummary = (f: Filters) =>
  useQuery({ queryKey: [...KEY, 'summary', f], queryFn: () => get<InsightsSummary>('/summary', f) });
export const useInsightsHourly = (f: Filters) =>
  useQuery({ queryKey: [...KEY, 'hourly', f], queryFn: () => get<{ rows: HourRow[] }>('/hourly', f) });
export const useInsightsKitchen = (f: Filters) =>
  useQuery({ queryKey: [...KEY, 'kitchen', f], queryFn: () => get<{ rows: StationRow[] }>('/kitchen', f) });
export const useInsightsStaff = (f: Filters) =>
  useQuery({ queryKey: [...KEY, 'staff', f], queryFn: () => get<{ groupBy: string; rows: StaffRow[] }>('/staff', f) });
export const useInsightsTables = (f: Filters) =>
  useQuery({ queryKey: [...KEY, 'tables', f], queryFn: () => get<{ rows: TableRow[] }>('/tables', f) });
export const useInsightsPour = (f: Filters) =>
  useQuery({ queryKey: [...KEY, 'pour', f], queryFn: () => get<{ rows: PourRow[] }>('/pour', f) });

/** Camera reports (LP6b): read only where the licence includes cameras, since without them they are empty. */
export const useInsightsFootfall = (f: Filters, enabled = true) =>
  useQuery({ queryKey: [...KEY, 'footfall', f], enabled, queryFn: () => get<FootfallReport>('/footfall', f) });
export const useInsightsQueues = (f: Filters, enabled = true) =>
  useQuery({ queryKey: [...KEY, 'queues', f], enabled, queryFn: () => get<QueuesReport>('/queues', f) });
export const useInsightsOccupancy = (f: Filters, enabled = true) =>
  useQuery({ queryKey: [...KEY, 'occupancy', f], enabled, queryFn: () => get<OccupancyReport>('/occupancy', f) });

/** Marks the days of a period for computing again (`lp:configure`); the job picks them up within minutes. */
export const useInsightsRecompute = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { from: string; to: string; branchId?: string }) =>
      (await api.post<{ days: number }>(`${BASE}/recompute`, body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
};

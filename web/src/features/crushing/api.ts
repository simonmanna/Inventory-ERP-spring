import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { tx } from '@/lib/i18n/tx';
import type { JournalEntry } from '@/features/quarry/api';

// ---- Types (crushing, screening & aggregate processing 2.3 — ADR-S016) ----

type Dec = string | null;
export type RunStatus = 'draft' | 'posted' | 'reversed';
export type SurveyStatus = 'draft' | 'submitted' | 'posted' | 'rejected' | 'reversed';
export type ProfileStatus = 'draft' | 'active' | 'archived';
export type FractionRole = 'main' | 'co_product' | 'by_product' | 'waste';
export type Shift = 'day' | 'night' | 'morning' | 'afternoon';

export const SHIFT_LABEL: Record<Shift, string> = {
  day: tx('Day shift'), night: tx('Night shift'), morning: tx('Morning shift'), afternoon: tx('Afternoon shift'),
};
export const RUN_STATUS_LABEL: Record<RunStatus, string> = { draft: tx('Draft'), posted: tx('Posted'), reversed: tx('Reversed') };
export const SURVEY_STATUS_LABEL: Record<SurveyStatus, string> = {
  draft: tx('Draft'), submitted: tx('Submitted'), posted: tx('Posted'), rejected: tx('Rejected'), reversed: tx('Reversed'),
};
export const PROFILE_STATUS_LABEL: Record<ProfileStatus, string> = { draft: tx('Draft'), active: tx('Active'), archived: tx('Archived') };
export const ROLE_LABEL: Record<FractionRole, string> = {
  main: tx('Main product'), co_product: tx('Co-product'), by_product: tx('By-product'), waste: tx('Waste'),
};
export const STAGE_KIND_LABEL: Record<string, string> = {
  primary: tx('Primary crushing'), secondary: tx('Secondary crushing'), tertiary: tx('Tertiary crushing'),
  quaternary: tx('Quaternary crushing'), screening: tx('Screening'), washing: tx('Washing'), other: tx('Other'),
};
export const UNIT_KIND_LABEL: Record<string, string> = {
  feeder: tx('Feeder'), jaw_crusher: tx('Jaw crusher'), cone_crusher: tx('Cone crusher'), vsi_crusher: tx('VSI crusher'),
  impact_crusher: tx('Impact crusher'), screen: tx('Screen'), conveyor: tx('Conveyor'), washer: tx('Washer'),
  generator: tx('Generator'), other: tx('Other'),
};
export const METER_LABEL: Record<string, string> = {
  none: tx('No meter'), engine_hours: tx('Engine hours'), kwh: tx('kWh meter'), both: tx('Engine hours and kWh'),
};
export const FEED_METHOD_LABEL: Record<string, string> = {
  belt_scale: tx('Belt scale'), weighbridge: tx('Weighbridge'), loads: tx('Loads × truck capacity'), survey: tx('Survey / estimate'),
};
export const OUTPUT_METHOD_LABEL: Record<string, string> = {
  belt_scale: tx('Belt scale (tonnes)'), weighbridge: tx('Weighbridge (tonnes)'), survey_pct: tx('Survey split (% of feed)'),
};
export const SURVEY_METHOD_LABEL: Record<string, string> = {
  drone: tx('Drone'), total_station: tx('Total station'), gps: tx('GPS'), tape: tx('Tape'), visual: tx('Visual estimate'), other: tx('Other'),
};
export const COST_KIND_LABEL: Record<string, string> = {
  diesel: tx('Diesel'), electricity: tx('Electricity'), labour: tx('Labour'), wear_parts: tx('Wear parts'), maintenance: tx('Maintenance'), other: tx('Other cost'),
};
export const VALUATION_LABEL: Record<string, string> = {
  allocated: tx('Shares the joint cost'), nrv: tx('Net realisable value'), zero: tx('Zero value'),
};
export const ALLOCATION_LABEL: Record<string, string> = {
  quantity: tx('By tonnes'), weight: tx('By weight'), market_value: tx('By market value'), fixed_pct: tx('Fixed %'),
};

export interface Unit {
  id: string; code: string; name: string; kind: string; stageId: string | null; workCenterId: string | null; workCenterCode?: string | null;
  capacityTph: Dec; powerKw: Dec; meterKind: string; isActive: boolean; notes: string | null;
}
export interface Stage { id: string; sequence: number; name: string; kind: string; capacityTph: Dec; routingOperationId: string | null; units?: Unit[] }
export interface ProfileSummary { id: string; code: string; name: string; version: number; status: ProfileStatus; expectedRecoveryPct: Dec; feedProductId: string; feedProductName: string }
export interface Plant {
  id: string; code: string; name: string; siteId: string | null; siteCode: string | null; feedStockpileId: string | null;
  feedStockpileCode: string | null; designCapacityTph: Dec; dieselCostPerLitre: string; electricityCostPerKwh: string;
  labourRatePerHour: string; routingId: string | null; circuitVersion: number; isActive: boolean; notes: string | null;
  stageCount: number; unitCount: number; activeProfileCount: number;
}
export interface PlantDetail extends Plant { stages: Array<Stage & { units: Unit[] }>; inactiveUnits: Unit[]; profiles: ProfileSummary[] }
export interface GradingLine {
  id: string; productId: string; productCode: string; productName: string; sizeLabel: string | null; role: FractionRole;
  stageSequence: number | null; expectedPct: string; stocked: boolean; stockpileId: string | null; stockpileCode: string | null;
  valuation: string; nrvUnitValue: Dec; marketValue: Dec; fixedPct: Dec;
}
export interface Profile {
  id: string; plantId: string; plantCode: string; plantName: string; code: string; name: string; feedProductId: string;
  feedProductName: string; version: number; status: ProfileStatus; allocationMethod: string; expectedRecoveryPct: string;
  bomId: string | null; bomCode: string | null; notes: string | null; lineCount: number; activatedAt: string | null;
}
export interface ProfileDetail extends Profile { lines: GradingLine[]; stages: Stage[] }
export interface Run {
  id: string; code: string; plantId: string; plantCode: string; plantName: string; profileId: string; profileCode: string;
  profileVersion: number; runDate: string; shift: Shift; operatorName: string | null; feedStockpileId: string;
  feedStockpileCode: string; feedProductName: string; feedTonnes: string; feedMethod: string; outputMethod: string;
  recirculatedTonnes: string; operatingHours: Dec; downtimeHours: string; downtimeReason: string | null; labourHours: string;
  status: RunStatus; productionOrderId: string | null; orderCode: string | null; outputTonnes: Dec; lossTonnes: Dec;
  actualYieldPct: Dec; expectedYieldPct: Dec; feedCost: Dec; conversionCost: Dec; abnormalLossValue: Dec; totalCost: Dec;
  costPerTonne: Dec; notes: string | null; reversalReason: string | null; postedAt: string | null;
}
export interface RunOutput {
  id: string; gradingLineId: string; productId: string; productName: string; sizeLabel: string | null; role: FractionRole;
  stageSequence: number | null; stockpileCode: string | null; stocked: boolean; expectedPct: string; expectedTonnes: string;
  actualPct: Dec; actualTonnes: string; value: Dec; unitCost: Dec;
}
export interface RunMeter { id: string; unitId: string; unitCode: string; unitName: string; hoursStart: Dec; hoursEnd: Dec; runHours: string; kwhStart: Dec; kwhEnd: Dec; kwh: string; dieselLitres: string }
export interface RunCost { id: string; kind: string; unitId: string | null; unitCode: string | null; stageSequence: number | null; quantity: Dec; unitRate: Dec; amount: string; description: string | null; auto: boolean }
export interface RunPreview {
  outputTonnes: string; lossTonnes: string; actualYieldPct: Dec; expectedYieldPct: Dec; yieldVariancePct: Dec;
  circulatingLoadPct: Dec; balanceError: string | null; throughputTph: Dec; availabilityPct: Dec; utilisationPct: Dec;
  feedOnHand?: string; feedUnitCost?: string; feedCost: Dec; conversionCost: Dec; totalCost: Dec; costPerTonne: Dec;
  abnormalLossValue?: Dec; estimated: boolean;
}
export type { JournalEntry };
export interface RunDetail extends Run { outputs: RunOutput[]; meters: RunMeter[]; costs: RunCost[]; preview: RunPreview; journals: JournalEntry[] }
export interface Survey {
  id: string; code: string; stockpileId: string; stockpileCode: string; stockpileName: string; productId: string; productName: string;
  surveyDate: string; method: string; surveyor: string | null; volumeM3: string; bulkDensity: string; surveyedTonnes: string;
  bookTonnes: Dec; varianceTonnes: Dec; variancePct: Dec; varianceValue: Dec; status: SurveyStatus; notes: string | null;
  rejectionReason: string | null; reversalReason: string | null; submittedBy: string | null;
}
export interface SurveyDetail extends Survey { currentBookTonnes: string; previewVarianceTonnes?: string; previewVariancePct?: Dec; journals: JournalEntry[] }
export interface StockpileRow {
  id: string; code: string; name: string; kind: string; siteCode: string; capacityT: Dec; onHandTonnes: string; onHandValue: string;
  stock: Array<{ productId: string; productCode: string; productName: string; onHandTonnes: string; value: string; lastSurvey: string | null }>;
}
export interface Lookups {
  plants: Array<{ id: string; code: string; name: string; feedStockpileId: string | null; siteId: string | null; profiles: Array<{ id: string; code: string; name: string; version: number; feedProductName: string }> }>;
  stockpiles: Array<{ id: string; code: string; name: string; kind: string; siteId: string; siteCode: string }>;
  sites: Array<{ id: string; code: string; name: string }>;
}
export type Report = Record<string, unknown> & { from: string; to: string };

const KEY = ['crushing'] as const;
const BASE = '/manufacturing/crushing';
const get = async <T>(url: string, params?: Record<string, unknown>) => (await api.get<T>(`${BASE}${url}`, { params })).data;
const clean = (p: Record<string, unknown>) => Object.fromEntries(Object.entries(p).filter(([, v]) => v !== '' && v !== undefined && v !== null));

// ---- Queries ----

export const useCrushingLookups = () => useQuery({ queryKey: [...KEY, 'lookups'], queryFn: () => get<Lookups>('/lookups') });
export const useCrushingProductSearch = (search: string) => useQuery({
  queryKey: [...KEY, 'products', search], enabled: search.trim().length > 0,
  queryFn: () => get<Array<{ id: string; code: string; name: string; costingMethod: string }>>('/lookups/products', { search }),
});
export const usePlants = (includeInactive = false) => useQuery({ queryKey: [...KEY, 'plants', includeInactive], queryFn: () => get<Plant[]>('/plants', { includeInactive }) });
export const usePlant = (id?: string) => useQuery({ queryKey: [...KEY, 'plant', id], enabled: !!id, queryFn: () => get<PlantDetail>(`/plants/${id}`) });
export const useProfile = (id?: string) => useQuery({ queryKey: [...KEY, 'profile', id], enabled: !!id, queryFn: () => get<ProfileDetail>(`/grading-profiles/${id}`) });
export const useRuns = (p: Record<string, unknown>) => useQuery({
  queryKey: [...KEY, 'runs', p], placeholderData: (prev) => prev, queryFn: () => get<PaginatedResult<Run>>('/runs', clean(p)),
});
export const useRun = (id?: string) => useQuery({ queryKey: [...KEY, 'run', id], enabled: !!id, queryFn: () => get<RunDetail>(`/runs/${id}`) });
export const useCrushingStockpiles = (kind?: string) => useQuery({ queryKey: [...KEY, 'stockpiles', kind], queryFn: () => get<StockpileRow[]>('/stockpiles', clean({ kind })) });
export const useSurveys = (p: Record<string, unknown>) => useQuery({
  queryKey: [...KEY, 'surveys', p], placeholderData: (prev) => prev, queryFn: () => get<PaginatedResult<Survey>>('/stockpile-surveys', clean(p)),
});
export const useSurvey = (id?: string) => useQuery({ queryKey: [...KEY, 'survey', id], enabled: !!id, queryFn: () => get<SurveyDetail>(`/stockpile-surveys/${id}`) });
export const useCrushingReport = (report: 'yield' | 'cost-per-tonne' | 'plant-hours', p: Record<string, unknown>) =>
  useQuery({ queryKey: [...KEY, 'report', report, p], queryFn: () => get<Report>(`/reports/${report}`, clean(p)) });

// ---- Mutations ----

function useCrushingMutation<TVars, TResult = unknown>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: KEY }) });
}
type Body = Record<string, unknown>;
const post = async <T>(url: string, body?: unknown) => (await api.post<T>(`${BASE}${url}`, body)).data;
const put = async <T>(url: string, body: unknown) => (await api.put<T>(`${BASE}${url}`, body)).data;

export const useSavePlant = () => useCrushingMutation(async ({ id, body }: { id?: string; body: Body }) =>
  (id ? put<PlantDetail>(`/plants/${id}`, body) : post<PlantDetail>('/plants', body)));
export const useDeletePlant = () => useCrushingMutation(async (id: string) => api.delete(`${BASE}/plants/${id}`));
export const useSaveProfile = () => useCrushingMutation(async ({ id, body }: { id?: string; body: Body }) =>
  (id ? put<ProfileDetail>(`/grading-profiles/${id}`, body) : post<ProfileDetail>('/grading-profiles', body)));
export const useProfileAction = () => useCrushingMutation(async ({ id, action }: { id: string; action: 'activate' | 'new-version' | 'archive' }) =>
  post<ProfileDetail>(`/grading-profiles/${id}/${action}`));
export const useDeleteProfile = () => useCrushingMutation(async (id: string) => api.delete(`${BASE}/grading-profiles/${id}`));

/** A phone that retries the draft never doubles it: creation carries an Idempotency-Key too. */
export const useSaveRun = () => useCrushingMutation(async ({ id, body }: { id?: string; body: Body }) =>
  (id ? put<RunDetail>(`/runs/${id}`, body) : idempotentPost<RunDetail>(`${BASE}/runs`, body)));
export const useDeleteRun = () => useCrushingMutation(async (id: string) => api.delete(`${BASE}/runs/${id}`));
export const usePostRun = () => useCrushingMutation(async (id: string) => idempotentPost<RunDetail>(`${BASE}/runs/${id}/post`));
export const useReverseRun = () => useCrushingMutation(async ({ id, reason }: { id: string; reason: string }) =>
  idempotentPost<RunDetail>(`${BASE}/runs/${id}/reverse`, { reason }));

export const useSaveSurvey = () => useCrushingMutation(async ({ id, body }: { id?: string; body: Body }) =>
  (id ? put<SurveyDetail>(`/stockpile-surveys/${id}`, body) : idempotentPost<SurveyDetail>(`${BASE}/stockpile-surveys`, body)));
export const useDeleteSurvey = () => useCrushingMutation(async (id: string) => api.delete(`${BASE}/stockpile-surveys/${id}`));
export const useSurveyAction = () => useCrushingMutation(async ({ id, action, reason }: { id: string; action: 'submit' | 'approve' | 'reject' | 'reverse'; reason?: string }) =>
  (action === 'approve' || action === 'reverse'
    ? idempotentPost<SurveyDetail>(`${BASE}/stockpile-surveys/${id}/${action}`, action === 'reverse' ? { reason } : undefined)
    : post<SurveyDetail>(`/stockpile-surveys/${id}/${action}`, action === 'reject' ? { reason } : undefined)));

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { tx } from '@/lib/i18n/tx';

// ---- Types (quarry 2.2 — sites, extraction, blasting, ROM stockpiles; ADR-S012) ----

export type Shift = 'day' | 'night' | 'morning' | 'afternoon';
export type LogStatus = 'draft' | 'posted' | 'reversed';
export type BlastStatus = 'planned' | 'fired' | 'closed' | 'cancelled';
export type IssueStatus = 'pending_approval' | 'approved' | 'rejected' | 'issued' | 'reconciled' | 'cancelled';
export type LoadMethod = 'loads' | 'weighbridge' | 'haulage' | 'survey';
export type LossKind = 'overburden' | 'unusable_rock' | 'waste' | 'spillage' | 'other';
export type ZoneKind = 'zone' | 'bench' | 'face';
type Dec = string | null;

export const SHIFT_LABEL: Record<Shift, string> = {
  day: tx('Day shift'), night: tx('Night shift'), morning: tx('Morning shift'), afternoon: tx('Afternoon shift'),
};
export const LOG_STATUS_LABEL: Record<LogStatus, string> = { draft: tx('Draft'), posted: tx('Posted'), reversed: tx('Reversed') };
export const BLAST_STATUS_LABEL: Record<BlastStatus, string> = {
  planned: tx('Planned'), fired: tx('Fired'), closed: tx('Closed'), cancelled: tx('Cancelled'),
};
export const ISSUE_STATUS_LABEL: Record<IssueStatus, string> = {
  pending_approval: tx('Pending approval'), approved: tx('Approved'), rejected: tx('Rejected'), issued: tx('Issued'),
  reconciled: tx('Reconciled'), cancelled: tx('Cancelled'),
};
export const METHOD_LABEL: Record<LoadMethod, string> = {
  loads: tx('Loads × truck capacity'), weighbridge: tx('Weighbridge ticket'), haulage: tx('Haulage record'), survey: tx('Survey / estimate'),
};
export const LOSS_LABEL: Record<LossKind, string> = {
  overburden: tx('Overburden'), unusable_rock: tx('Unusable rock'), waste: tx('Waste'), spillage: tx('Spillage'), other: tx('Other'),
};
export const ZONE_KIND_LABEL: Record<ZoneKind, string> = { zone: tx('Zone'), bench: tx('Bench'), face: tx('Face') };
export const SITE_STATUS_LABEL: Record<string, string> = { active: tx('Active'), suspended: tx('Suspended'), closed: tx('Closed') };
export const DESTINATION_LABEL: Record<string, string> = {
  rom_stockpile: tx('ROM stockpile'), crusher: tx('Crusher'), waste_dump: tx('Waste dump'), other: tx('Other'),
};
export const EXPLOSIVE_KIND_LABEL: Record<string, string> = {
  high_explosive: tx('High explosive'), blasting_agent: tx('Blasting agent'), detonator: tx('Detonator'), booster: tx('Booster'),
  cord: tx('Detonating cord'), other: tx('Other'),
};
export const COMPONENT_LABEL: Record<string, string> = {
  fuel: tx('Fuel'), labour: tx('Labour'), equipment: tx('Equipment hours'), blasting: tx('Blasting'), royalty: tx('Royalties & levies'), other: tx('Other cost'),
};

export interface Ref { id: string; code: string; name: string }

export interface Site {
  id: string; code: string; name: string; status: string; branchId: string | null; costCenterId: string | null;
  address: string | null; latitude: Dec; longitude: Dec; areaHectares: Dec; licenceNumber: string | null;
  licenceAuthority: string | null; licenceExpiresOn: string | null; notes: string | null;
  zoneCount?: number; stockpileCount?: number; monthTonnes?: Dec;
}
export interface Zone {
  id: string; siteId: string; parentId: string | null; kind: ZoneKind; code: string; name: string; materialId: string | null;
  geology: string | null; elevationM: Dec; benchHeightM: Dec; status: string; notes: string | null;
  parentCode?: string | null; materialName?: string | null;
}
export interface Stockpile {
  id: string; siteId: string; code: string; name: string; kind: 'rom' | 'product'; locationId: string; capacityT: Dec;
  isDefault: boolean; isActive: boolean; notes: string | null; siteCode?: string; locationCode?: string;
  onHandTonnes: Dec; onHandValue: Dec;
}
export interface SiteDetail extends Site {
  branch: Ref | null; costCenter: Ref | null; zones: Zone[]; stockpiles: Stockpile[];
  magazines: Array<{ id: string; code: string; name: string; licenceNumber: string | null; licenceExpiresOn: string | null; isActive: boolean }>;
  plans: Array<{ id: string; code: string; name: string; periodStart: string; periodEnd: string; targetTonnes: string; status: string }>;
}
export interface Material {
  id: string; code: string; name: string; rockType: string | null; grade: string | null; densityTPerM3: Dec; productId: string;
  isActive: boolean; notes: string | null; productCode?: string; productName?: string; costingMethod?: string;
}
export interface Variance {
  target: string; actual: string; variance: string; achievedPct: string; expectedToDate: string; paceVariance: string;
  elapsedDays: number; totalDays: number; status: 'not_started' | 'ahead' | 'on_track' | 'behind' | 'achieved' | 'missed';
}
export interface Plan {
  id: string; code: string; name: string; siteId: string; zoneId: string | null; materialId: string | null;
  periodStart: string; periodEnd: string; targetTonnes: string; budgetCostPerTonne: Dec; status: string; notes: string | null;
  siteCode: string; zoneCode: string | null; materialName: string | null; actualTonnes: string; actualCost: string;
  actualCostPerTonne: Dec; logCount: number; variance: Variance;
}
export interface ExtractionLog {
  id: string; code: string; siteId: string; zoneId: string | null; materialId: string; stockpileId: string; blastId: string | null;
  extractedOn: string; shift: Shift; hrShiftId: string | null; supervisorEmployeeId: string | null; status: LogStatus;
  grossTonnes: string; lossTonnes: string; netTonnes: string; otherCost: string; otherCostNote: string | null;
  allocationBasis: string | null; fuelCost: string; labourCost: string; equipmentCost: string; blastingCost: string;
  royaltyCost: string; totalCost: string; capitalisedCost: string; lossCost: string; unitCost: Dec;
  journalEntryId: string | null; reversalEntryId: string | null; postedAt: string | null; reversedAt: string | null;
  reversalReason: string | null; notes: string | null;
  siteCode: string; siteName: string; zoneCode: string | null; zoneName: string | null; materialCode: string; materialName: string;
  stockpileCode: string; blastCode: string | null;
}
export interface LoadLine {
  id: string; lineNumber: number; method: LoadMethod; truckVehicleId: string | null; loadCount: number | null; capacityT: Dec;
  weighbridgeTicketId: string | null; haulageId: string | null; fleetTripId: string | null; tonnes: string; notes: string | null;
  truckCode?: string | null; ticketNumber?: string | null; haulageCode?: string | null;
}
export interface ResourceLine {
  id: string; lineNumber: number; kind: 'equipment' | 'labour'; vehicleId: string | null; employeeId: string | null; role: string | null;
  hours: string; meterStart: Dec; meterEnd: Dec; fuelLitres: Dec; ratePerHour: Dec; fuelCostPerLitre: Dec; appliedRate: Dec;
  hoursCost: Dec; fuelCost: Dec; notes: string | null; vehicleCode?: string | null; vehicleClass?: string | null; employeeName?: string | null;
}
export interface JournalEntry {
  id: string; entryNumber: string; status: string; postingDate: string; description: string; reversalOfId: string | null;
  lines: Array<{ id: string; debit: string; credit: string; description: string; accountCode: string; accountName: string }>;
}
export interface CostLine { id: string; component: string; description: string; quantity: Dec; rate: Dec; amount: string }
export interface ExtractionLogDetail extends ExtractionLog {
  supervisor: { id: string; employeeCode: string; name: string } | null;
  loads: LoadLine[]; resources: ResourceLine[];
  losses: Array<{ id: string; kind: LossKind; tonnes: string; reason: string | null }>;
  costs: CostLine[]; journal: JournalEntry[];
}
export interface CostPreview {
  grossTonnes: string; lossTonnes: string; netTonnes: string; fuelCost: string; labourCost: string; equipmentCost: string;
  blastingCost: string; royaltyCost: string; otherCost: string; totalCost: string; capitalisedCost: string; lossCost: string;
  unitCost: string; lines: Array<{ component: string; description: string; quantity: Dec; rate: Dec; amount: string }>;
}
export interface Haulage {
  id: string; code: string; siteId: string; fromZoneId: string | null; destination: string; stockpileId: string | null;
  hauledOn: string; shift: Shift; truckVehicleId: string | null; driverEmployeeId: string | null; method: 'loads' | 'weighbridge';
  loadCount: number | null; capacityT: Dec; weighbridgeTicketId: string | null; fleetTripId: string | null; tonnes: string;
  notes: string | null; siteCode: string; zoneCode: string | null; stockpileCode: string | null; truckCode: string | null;
  truckPlate: string | null; ticketNumber: string | null; extractionLogId: string | null; extractionLogCode: string | null;
  driverFirstName: string | null; driverLastName: string | null; materialId: string | null; destinationNote: string | null;
}
export interface Blast {
  id: string; code: string; siteId: string; zoneId: string | null; materialId: string | null; status: BlastStatus;
  plannedAt: string | null; firedAt: string | null; closedAt: string | null; patternType: string | null; holeCount: number | null;
  holeDiameterMm: Dec; averageDepthM: Dec; burdenM: Dec; spacingM: Dec; subDrillM: Dec; stemmingM: Dec; drilledMetres: Dec;
  drillVehicleId: string | null; drillHours: Dec; estimatedVolumeM3: Dec; estimatedTonnes: Dec; explosivesKg: string;
  explosivesCost: string; drillingCost: string; otherCost: string; absorbedCost: string; totalCost: string;
  shotfirerEmployeeId: string | null; shotfirerLicenceNo: string | null; blastPermitNo: string | null; permitExpiresOn: string | null;
  exclusionZoneM: Dec; warningSignalAt: string | null; allClearAt: string | null; misfireCount: number; misfireNotes: string | null;
  vibrationPpvMmS: Dec; airblastDb: Dec; weather: string | null; notes: string | null;
  siteCode: string; zoneCode: string | null; materialName: string | null;
}
export interface BlastDetail extends Blast {
  costPerTonne: Dec; powderFactorKgPerT: Dec; remainingCost: string;
  shotfirer: { id: string; employeeCode: string; name: string } | null;
  drillVehicle: { id: string; code: string; registrationPlate: string | null } | null;
  issues: Array<{ id: string; code: string; status: IssueStatus; issuedValue: string; returnedValue: string; magazineCode: string; requestedAt: string | null; issuedAt: string | null }>;
  extractionLogs: Array<{ id: string; code: string; extractedOn: string; status: LogStatus; netTonnes: string; blastingCost: string }>;
}
export interface Magazine {
  id: string; siteId: string | null; code: string; name: string; locationId: string; licenceNumber: string | null;
  licenceAuthority: string | null; licenceExpiresOn: string | null; capacityKg: Dec; keeperEmployeeId: string | null;
  isActive: boolean; notes: string | null; siteCode?: string | null; locationCode?: string; keeperName?: string | null; netExplosiveKg: Dec;
}
export interface MagazineDetail extends Magazine {
  stock: Array<{ explosiveId: string; kind: string; hazardClass: string | null; unNumber: string | null; productCode: string; productName: string; quantity: string; value: string }>;
  recentIssues: Array<{ id: string; code: string; status: IssueStatus; issuedAt: string | null; issuedValue: string; blastCode: string }>;
}
export interface Explosive {
  id: string; productId: string; kind: string; hazardClass: string | null; unNumber: string | null; netExplosiveKg: string;
  controlled: boolean; isActive: boolean; notes: string | null; productCode: string; productName: string; magazineStock: string;
}
export interface ExplosiveIssue {
  id: string; code: string; blastId: string; magazineId: string; status: IssueStatus; purpose: string | null;
  shotfirerEmployeeId: string | null; shotfirerLicenceNo: string | null; requestedBy: string | null; requestedAt: string | null;
  approvalRequestId: string | null; decidedAt: string | null; issuedAt: string | null; issuedValue: string; reconciledAt: string | null;
  returnedValue: string; cancelReason: string | null; notes: string | null; blastCode: string; blastStatus: BlastStatus;
  siteCode: string; magazineCode: string; magazineName: string; shotfirerName: string | null; approvalStatus: string | null;
  issueJournalEntryId: string | null; returnJournalEntryId: string | null;
}
export interface ExplosiveIssueDetail extends ExplosiveIssue {
  lines: Array<{ id: string; lineNumber: number; explosiveId: string; productName: string; productCode: string; kind: string; requestedQty: string; issuedQty: string; returnedQty: string; unitCost: Dec; issuedValue: string; returnedValue: string; magazineStock: string }>;
  journal: JournalEntry[];
  audit: Array<{ entity: string; action: string; createdAt: string; actorName: string | null; actorEmail: string | null }>;
}
export interface QuarryLookups {
  sites: Array<Ref & { status: string }>;
  zones: Array<Ref & { siteId: string; parentId: string | null; kind: ZoneKind; status: string; materialId: string | null }>;
  materials: Array<Ref & { productId: string; isActive: boolean; densityTPerM3: Dec }>;
  stockpiles: Array<Ref & { siteId: string; kind: string; isDefault: boolean; isActive: boolean }>;
  magazines: Array<Ref & { siteId: string | null; isActive: boolean }>;
  explosives: Array<Ref & { kind: string; netExplosiveKg: string }>;
  plant: Array<{ id: string; code: string; name: string | null; registrationPlate: string | null; className: string; currentHours: Dec }>;
  trucks: Array<{ id: string; code: string; name: string | null; registrationPlate: string | null; payloadCapacityT: string }>;
  vehicleClasses: Array<Ref & { kind: string }>;
  hrShifts: Array<Ref & { startTime: string; endTime: string }>;
  locations: Array<Ref & { type: string }>;
  branches: Ref[];
  costCenters: Ref[];
  shifts: Shift[];
}
export interface Overview {
  today: string; todayTonnes: string; activeTargetTonnes: string;
  month: { netTonnes: string; totalCost: string; lossTonnes: string; grossTonnes: string; logs: number; costPerTonne: Dec };
  counts: { drafts: number; pendingExplosiveApprovals: number; approvedToIssue: number; openBlasts: number; romTonnes: string; haulsToday: number };
}
export interface RoyaltyRate {
  id: string; name: string; kind: string; siteId: string | null; materialId: string | null; basis: string; ratePerTonne: string;
  authorityPartnerId: string | null; effectiveFrom: string; effectiveTo: string | null; isActive: boolean; notes: string | null;
  siteCode?: string | null; materialName?: string | null; authorityName?: string | null;
}
export interface EquipmentRate {
  id: string; categoryId: string | null; vehicleId: string | null; ratePerHour: string; notes: string | null;
  categoryName?: string | null; vehicleCode?: string | null;
}
export interface QuarrySettings {
  id: string; allocationBasis: 'net_tonnes' | 'gross_tonnes'; fuelCostPerLitre: string; labourRatePerHour: string;
  equipmentRatePerHour: string; distinctExplosiveIssuer: boolean;
}
export interface StockpileView extends Stockpile {
  stock: Array<{ productId: string; productCode: string; productName: string; onHandTonnes: string; averageCost: string; value: string }>;
  movements: PaginatedResult<{ id: string; ledgerCode: string; type: string; quantityChange: string; balanceAfter: string; unitCost: string; totalValue: string; notes: string | null; createdAt: string; productName: string; extractionLogCode: string | null; referenceId: string | null }>;
}
export interface Loss {
  id: string; siteId: string; zoneId: string | null; logId: string | null; lossOn: string; kind: LossKind; tonnes: string;
  reason: string | null; siteCode: string; zoneCode: string | null; logCode: string | null; materialName: string | null;
}
type WithSummary<T, S> = PaginatedResult<T> & { summary: S };
type Report = Record<string, unknown> & { from: string; to: string };

const KEY = ['quarry'] as const;
const get = async <T>(url: string, params?: Record<string, unknown>) => (await api.get<T>(url, { params })).data;
const clean = (p: Record<string, unknown>) => Object.fromEntries(Object.entries(p).filter(([, v]) => v !== '' && v !== undefined && v !== null));

// ---- Queries ----

export const useQuarryOverview = () => useQuery({ queryKey: [...KEY, 'overview'], queryFn: () => get<Overview>('/quarry/overview') });
export const useQuarryLookups = () => useQuery({ queryKey: [...KEY, 'lookups'], queryFn: () => get<QuarryLookups>('/quarry/lookups') });
export const useEmployeeSearch = (search: string) => useQuery({
  queryKey: [...KEY, 'employees', search], enabled: search.trim().length > 0,
  queryFn: () => get<Array<{ id: string; employeeCode: string; name: string }>>('/quarry/lookups/employees', { search }),
});
export const useProductSearch = (search: string) => useQuery({
  queryKey: [...KEY, 'products', search], enabled: search.trim().length > 0,
  queryFn: () => get<Array<{ id: string; code: string; name: string; costingMethod: string }>>('/quarry/lookups/products', { search }),
});
export const usePartnerSearch = (search: string) => useQuery({
  queryKey: [...KEY, 'partners', search], enabled: search.trim().length > 0,
  queryFn: () => get<Ref[]>('/quarry/lookups/partners', { search }),
});
export const useTicketSearch = (search: string, enabled: boolean) => useQuery({
  queryKey: [...KEY, 'tickets', search], enabled,
  queryFn: () => get<Array<{ id: string; ticketNumber: string; netKg: string; registrationPlate: string; completedAt: string }>>('/quarry/lookups/weighbridge-tickets', { search }),
});
export const useSites = (p: Record<string, unknown>) => useQuery({
  queryKey: [...KEY, 'sites', p], placeholderData: (prev) => prev, queryFn: () => get<PaginatedResult<Site>>('/quarry/sites', clean(p)),
});
export const useSite = (id?: string) => useQuery({ queryKey: [...KEY, 'site', id], enabled: !!id, queryFn: () => get<SiteDetail>(`/quarry/sites/${id}`) });
export const useMaterials = (includeInactive = false) => useQuery({
  queryKey: [...KEY, 'materials', includeInactive], queryFn: () => get<Material[]>('/quarry/materials', { includeInactive }),
});
export const useStockpiles = (siteId?: string) => useQuery({
  queryKey: [...KEY, 'stockpiles', siteId], queryFn: () => get<Stockpile[]>('/quarry/stockpiles', clean({ siteId })),
});
export const useStockpile = (id: string | undefined, page: number) => useQuery({
  queryKey: [...KEY, 'stockpile', id, page], enabled: !!id, placeholderData: (prev) => prev,
  queryFn: () => get<StockpileView>(`/quarry/stockpiles/${id}`, { page, pageSize: 20 }),
});
export const usePlans = (p: Record<string, unknown>) => useQuery({
  queryKey: [...KEY, 'plans', p], placeholderData: (prev) => prev, queryFn: () => get<PaginatedResult<Plan>>('/quarry/extraction-plans', clean(p)),
});
export const useExtractionLogs = (p: Record<string, unknown>) => useQuery({
  queryKey: [...KEY, 'logs', p], placeholderData: (prev) => prev,
  queryFn: () => get<WithSummary<ExtractionLog, { netTonnes: string; totalCost: string; costPerTonne: Dec }>>('/quarry/extraction-logs', clean(p)),
});
export const useExtractionLog = (id?: string) => useQuery({
  queryKey: [...KEY, 'log', id], enabled: !!id, queryFn: () => get<ExtractionLogDetail>(`/quarry/extraction-logs/${id}`),
});
export const useCostPreview = (id: string | undefined, enabled: boolean) => useQuery({
  queryKey: [...KEY, 'log', id, 'preview'], enabled: !!id && enabled, retry: false,
  queryFn: () => get<CostPreview>(`/quarry/extraction-logs/${id}/cost-preview`),
});
export const useHaulage = (p: Record<string, unknown>) => useQuery({
  queryKey: [...KEY, 'haulage', p], placeholderData: (prev) => prev,
  queryFn: () => get<WithSummary<Haulage, { tonnes: string; loads: string }>>('/quarry/haulage-trips', clean(p)),
});
export const useLosses = (p: Record<string, unknown>) => useQuery({
  queryKey: [...KEY, 'losses', p], placeholderData: (prev) => prev, queryFn: () => get<PaginatedResult<Loss>>('/quarry/losses', clean(p)),
});
export const useBlasts = (p: Record<string, unknown>) => useQuery({
  queryKey: [...KEY, 'blasts', p], placeholderData: (prev) => prev, queryFn: () => get<PaginatedResult<Blast>>('/quarry/blasts', clean(p)),
});
export const useBlast = (id?: string) => useQuery({ queryKey: [...KEY, 'blast', id], enabled: !!id, queryFn: () => get<BlastDetail>(`/quarry/blasts/${id}`) });
export const useMagazines = () => useQuery({ queryKey: [...KEY, 'magazines'], queryFn: () => get<Magazine[]>('/quarry/magazines') });
export const useMagazine = (id?: string) => useQuery({ queryKey: [...KEY, 'magazine', id], enabled: !!id, queryFn: () => get<MagazineDetail>(`/quarry/magazines/${id}`) });
export const useExplosives = () => useQuery({ queryKey: [...KEY, 'explosives'], queryFn: () => get<Explosive[]>('/quarry/explosives', { includeInactive: true }) });
export const useIssues = (p: Record<string, unknown>) => useQuery({
  queryKey: [...KEY, 'issues', p], placeholderData: (prev) => prev, queryFn: () => get<PaginatedResult<ExplosiveIssue>>('/quarry/explosive-issues', clean(p)),
});
export const useIssue = (id?: string) => useQuery({ queryKey: [...KEY, 'issue', id], enabled: !!id, queryFn: () => get<ExplosiveIssueDetail>(`/quarry/explosive-issues/${id}`) });
export const useQuarrySettings = () => useQuery({ queryKey: [...KEY, 'settings'], queryFn: () => get<QuarrySettings>('/quarry/settings') });
export const useRoyaltyRates = () => useQuery({ queryKey: [...KEY, 'royalty-rates'], queryFn: () => get<RoyaltyRate[]>('/quarry/royalty-rates') });
export const useEquipmentRates = () => useQuery({ queryKey: [...KEY, 'equipment-rates'], queryFn: () => get<EquipmentRate[]>('/quarry/equipment-rates') });
export const useQuarryReport = (report: 'extraction-vs-target' | 'cost-per-tonne' | 'losses' | 'royalties', p: Record<string, unknown>) =>
  useQuery({ queryKey: [...KEY, 'report', report, p], queryFn: () => get<Report>(`/quarry/reports/${report}`, clean(p)) });

// ---- Mutations ----

function useQuarryMutation<TVars, TResult = unknown>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: KEY }) });
}
type Body = Record<string, unknown>;
const post = async <T>(url: string, body?: unknown) => (await api.post<T>(url, body)).data;
const patch = async <T>(url: string, body: unknown) => (await api.patch<T>(url, body)).data;

/** Create (POST) or replace (PATCH {id}) a master-data row of `base`. */
export const useSaveRow = <T = unknown>(base: string) =>
  useQuarryMutation(async ({ id, body }: { id?: string; body: Body }) => (id ? patch<T>(`${base}/${id}`, body) : post<T>(base, body)));
export const useDeleteRow = (base: string) => useQuarryMutation(async (id: string) => api.delete(`${base}/${id}`));

export const useSaveSettings = () => useQuarryMutation(async (body: Body) => (await api.put<QuarrySettings>('/quarry/settings', body)).data);

/** Extraction entry: `post: true` posts at once — stock and GL, so the Idempotency-Key survives retries. */
export const useCreateLog = () => useQuarryMutation(async (body: Body) => idempotentPost<ExtractionLogDetail>('/quarry/extraction-logs', body));
export const useUpdateLog = () => useQuarryMutation(async ({ id, body }: { id: string; body: Body }) => patch<ExtractionLogDetail>(`/quarry/extraction-logs/${id}`, body));
export const usePostLog = () => useQuarryMutation(async (id: string) => idempotentPost<ExtractionLogDetail>(`/quarry/extraction-logs/${id}/post`));
export const useReverseLog = () => useQuarryMutation(async ({ id, reason }: { id: string; reason: string }) =>
  idempotentPost<ExtractionLogDetail>(`/quarry/extraction-logs/${id}/reverse`, { reason }));

export const useBlastAction = () => useQuarryMutation(async ({ id, action, body }: { id: string; action: 'fire' | 'close' | 'cancel'; body?: Body }) =>
  post<BlastDetail>(`/quarry/blasts/${id}/${action}`, body));
export const useRequestExplosives = () => useQuarryMutation(async (body: Body) => post<ExplosiveIssueDetail>('/quarry/explosive-issues', body));
export const useIssueExplosives = () => useQuarryMutation(async (id: string) => idempotentPost<ExplosiveIssueDetail>(`/quarry/explosive-issues/${id}/issue`));
export const useReconcileExplosives = () => useQuarryMutation(async ({ id, body }: { id: string; body: Body }) =>
  idempotentPost<ExplosiveIssueDetail>(`/quarry/explosive-issues/${id}/reconcile`, body));
export const useIssueAction = () => useQuarryMutation(async ({ id, action, body }: { id: string; action: 'sync' | 'cancel'; body?: Body }) =>
  post<ExplosiveIssueDetail>(`/quarry/explosive-issues/${id}/${action}`, body));

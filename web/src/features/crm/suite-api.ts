import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { PERMISSIONS } from '@erp/shared';
import { useAuthStore } from '@/stores/auth.store';
import type { UnitAwareProduct } from '@/features/products/units';
import { tx } from '@/lib/i18n/tx';

// ---- Types (CRM sales suite, ADR-S010) ----

export type DealKind = 'lead' | 'opportunity';
export type DealStatus = 'open' | 'won' | 'lost';
export type ActivityState = 'overdue' | 'today' | 'planned' | 'none';
export type PlannedType = 'call' | 'email' | 'meeting' | 'task' | 'note';
export type QuoteStatus =
  | 'draft' | 'pending_approval' | 'approved' | 'sent' | 'accepted' | 'rejected' | 'expired' | 'cancelled' | 'converted';
export type Color =
  | 'slate' | 'sky' | 'blue' | 'indigo' | 'violet' | 'pink' | 'rose' | 'orange' | 'amber' | 'lime' | 'emerald' | 'teal';

export const COLORS: Color[] = ['slate', 'sky', 'blue', 'indigo', 'violet', 'pink', 'rose', 'orange', 'amber', 'lime', 'emerald', 'teal'];

export interface Stage {
  id: string;
  name: string;
  sequence: number;
  probability: number;
  isWon: boolean;
  fold: boolean;
  color: Color;
  requirements: string | null;
  legacyStage: string;
  teamId: string | null;
  rottingDays: number | null;
  isActive: boolean;
  openCount?: number;
}

export interface Team {
  id: string;
  name: string;
  leaderId: string | null;
  memberIds: string[];
  monthlyTarget: number;
  color: Color;
  isActive: boolean;
}

export interface Tag { id: string; name: string; color: Color }
export interface Lookup { id: string; name: string; isActive: boolean }
export interface CrmUserRef { id: string; email: string; name: string }

export interface CrmSettings {
  quoteMaxDiscountPercent: number;
  quoteApprovalAmount: number;
  creditCheck: boolean;
  quoteValidityDays: number;
  defaultQuoteTerms: string | null;
  autoWinOnConversion: boolean;
  assignmentMode: 'manual' | 'round_robin' | 'least_loaded';
  assignmentPool: string[];
  rottingDays: number;
  useLeads: boolean;
}

export interface CrmLookups {
  stages: Stage[];
  teams: Team[];
  tags: Tag[];
  sources: Lookup[];
  lostReasons: Lookup[];
  users: CrmUserRef[];
  settings: CrmSettings;
}

export interface NextActivity { id: string; title: string; type: PlannedType; dueAt: string }

export interface Opportunity {
  id: string;
  kind: DealKind;
  name: string;
  partnerId: string | null;
  partnerName: string | null;
  contactId: string | null;
  contactName: string | null;
  companyName: string | null;
  email: string | null;
  phone: string | null;
  city: string | null;
  country: string | null;
  ownerId: string | null;
  ownerName: string | null;
  teamId: string | null;
  stageId: string | null;
  stageName: string | null;
  legacyStage: string;
  status: DealStatus;
  amount: number;
  currencyCode: string;
  probability: number;
  probabilityOverride: number | null;
  priority: number;
  tagIds: string[];
  sourceId: string | null;
  lostReasonId: string | null;
  lostNote: string | null;
  expectedClose: string | null;
  createdAt: string;
  updatedAt: string;
  stageChangedAt: string;
  wonAt: string | null;
  lostAt: string | null;
  convertedAt: string | null;
  notes: string | null;
  quotationCount: number;
  activityState: ActivityState;
  daysInStage: number;
  rotting: boolean;
  weightedAmount: number;
  nextActivity: NextActivity | null;
}

export interface PartnerLite {
  id: string;
  code: string;
  name: string;
  email: string | null;
  phone: string | null;
  creditLimit: number;
  creditHold: boolean;
  isCustomer: boolean;
  contacts: Array<{ id: string; firstName: string; lastName: string | null; position: string | null; email: string | null; phone: string | null; isPrimary: boolean }>;
}

export interface PlannedActivity {
  id: string;
  type: PlannedType;
  title: string;
  body: string | null;
  status: string;
  outcome: string | null;
  dueAt: string | null;
  remindAt: string | null;
  duration: number | null;
  completedAt: string | null;
  dealId: string | null;
  partnerId: string | null;
  assignedToId: string | null;
  assignedToName: string | null;
  dealName?: string | null;
  partnerName?: string | null;
  state?: 'overdue' | 'today' | 'planned' | 'done';
}

export interface OpportunityDetail extends Opportunity {
  partner: PartnerLite | null;
  quotations: Array<{ id: string; number: string; status: QuoteStatus; total: number; currencyCode: string; issueDate: string; validUntil: string | null }>;
  plannedActivities: PlannedActivity[];
  stageDurations: Array<{ stageId: string; seconds: number }>;
}

export interface TimelineItem {
  id: string;
  source: 'activity' | 'stage' | 'audit';
  at: string;
  userName: string | null;
  type?: string;
  title?: string;
  body?: string | null;
  status?: string;
  outcome?: string | null;
  fromStage?: string | null;
  toStage?: string | null;
  fromStatus?: string | null;
  toStatus?: string;
  secondsInStage?: number | null;
  action?: string;
  newValues?: Record<string, unknown> | null;
}

export interface Paged<T, X = Record<string, number>> {
  data: T[];
  meta: { page: number; pageSize: number; total: number; totalPages: number };
  totals?: X;
}

export interface KanbanColumn {
  stage: Stage;
  count: number;
  amount: number;
  activity: Record<ActivityState, number>;
  items: Opportunity[];
}

export interface GroupRow { key: string | null; label: string | null; count: number; amount: number; weighted: number }

/** Sales users work through /sales/quotations, which needs no CRM switch; CRM-only roles keep the facade. */
export const quoteBase = () => (useAuthStore.getState().hasPermission(PERMISSIONS.sales.quotationRead) ? '/sales/quotations' : '/crm/quotations');

export interface QuoteLine {
  id?: string;
  /** Section heading or free note row between priced lines (no amounts); null for a priced line. */
  displayType?: 'line_section' | 'line_note' | null;
  leadTimeDays?: number | null;
  productId: string | null;
  variantId?: string | null;
  variantName?: string | null;
  uomId?: string | null;
  productName?: string | null;
  productCode?: string | null;
  description: string;
  quantity: number;
  listPrice?: number;
  unitPrice: number;
  discountPercent: number;
  priceSource?: 'list' | 'rule' | 'customer' | 'manual';
  taxId: string | null;
  taxName?: string | null;
  taxInclusive?: boolean;
  lineSubtotal?: number;
  lineTax?: number;
  lineTotal?: number;
  stock?: { onHand: number; reserved: number; available: number; sufficient: boolean } | null;
}

export interface CreditInfo {
  creditLimit: number;
  creditHold: boolean;
  openReceivables: number;
  overdueReceivables: number;
  headroom: number | null;
  exceeded: boolean;
}

export interface Quotation {
  id: string;
  internalNotes?: string | null;
  branchId?: string | null;
  paymentTermId?: string | null;
  deliveryAddress?: string | null;
  /** Promised delivery date; becomes the sales order's commitment date. */
  commitmentDate?: string | null;
  lostReasonId?: string | null;
  number: string;
  revision: number;
  dealId: string | null;
  dealName: string | null;
  partnerId: string;
  partnerName: string | null;
  contactId: string | null;
  ownerId: string | null;
  ownerName: string | null;
  status: QuoteStatus;
  issueDate: string;
  validUntil: string | null;
  currencyCode: string;
  subtotal: number;
  discountTotal: number;
  taxAmount: number;
  total: number;
  maxDiscountPercent: number;
  notes: string | null;
  terms: string | null;
  approvalReasons: string[];
  rejectionReason: string | null;
  approvedAt: string | null;
  sentAt: string | null;
  acceptedAt: string | null;
  convertedType: 'sales_order' | 'order' | 'proforma' | null;
  convertedId: string | null;
  convertedNumber: string | null;
  createdById: string | null;
  createdAt: string;
  lines?: QuoteLine[];
  credit?: CreditInfo | null;
  canApprove?: boolean;
}

export interface PriceRule {
  id: string;
  name: string;
  partnerId: string | null;
  partnerName?: string | null;
  partnerCategoryId: string | null;
  partnerCategoryName?: string | null;
  productId: string | null;
  productName?: string | null;
  productCategoryId: string | null;
  productCategoryName?: string | null;
  minQuantity: number;
  kind: 'fixed_price' | 'discount_percent';
  value: number;
  validFrom: string | null;
  validTo: string | null;
  priority: number;
  isActive: boolean;
  notes: string | null;
}

export interface ResolvedPrice {
  productId: string;
  quantity: number;
  listPrice: number;
  unitPrice: number;
  discountPercent: number;
  netPrice: number;
  source: 'list' | 'rule' | 'customer';
  ruleName: string | null;
  taxId: string | null;
  taxInclusive: boolean;
  description: string;
}

export interface SavedFilter { id: string; userId: string; scope: string; name: string; filters: Record<string, string>; shared: boolean; isDefault: boolean }

// ---- Labels ----

export const QUOTE_STATUS_LABEL: Record<QuoteStatus, string> = {
  draft: tx('Draft'),
  pending_approval: tx('Awaiting approval'),
  approved: tx('Approved'),
  sent: tx('Sent'),
  accepted: tx('Accepted'),
  rejected: tx('Rejected'),
  expired: tx('Expired'),
  cancelled: tx('Cancelled'),
  converted: tx('Converted'),
};

export const ACTIVITY_TYPE_LABEL: Record<PlannedType, string> = {
  call: tx('Call'),
  email: tx('Email'),
  meeting: tx('Meeting'),
  task: tx('To-do'),
  note: tx('Note'),
};

export const STATUS_LABEL: Record<DealStatus, string> = { open: tx('Open'), won: tx('Won'), lost: tx('Lost') };

export const GROUP_BY_LABEL: Record<string, string> = {
  stage: tx('Stage'),
  owner: tx('Salesperson'),
  team: tx('Sales team'),
  source: tx('Source'),
  tag: tx('Tag'),
  lostReason: tx('Lost reason'),
  partner: tx('Customer'),
  city: tx('City'),
  country: tx('Country'),
  status: tx('Status'),
  priority: tx('Priority'),
  createdMonth: tx('Created (month)'),
  closeMonth: tx('Expected closing (month)'),
  wonMonth: tx('Won (month)'),
};

// ---- Hooks ----

const clean = (p: Record<string, unknown>) =>
  Object.fromEntries(Object.entries(p).filter(([, v]) => v !== undefined && v !== null && v !== ''));

export function useCrmLookups() {
  return useQuery({
    queryKey: ['crm-suite', 'lookups'],
    queryFn: async () => (await api.get<CrmLookups>('/crm/config/lookups')).data,
    staleTime: 60_000,
  });
}

export function useKanban(params: Record<string, string | undefined>) {
  return useQuery({
    queryKey: ['crm-suite', 'kanban', params],
    queryFn: async () => (await api.get<{ columns: KanbanColumn[]; unstaged: Opportunity[] }>('/crm/opportunities/kanban', { params: clean(params) })).data,
    placeholderData: keepPreviousData,
  });
}

export function useOpportunities(params: Record<string, string | number | undefined>, path = '/crm/opportunities', enabled = true) {
  return useQuery({
    queryKey: ['crm-suite', 'list', path, params],
    queryFn: async () => (await api.get<Paged<Opportunity, { count: number; amount: number; weighted: number }>>(path, { params: clean(params) })).data,
    placeholderData: keepPreviousData,
    enabled,
  });
}

export function useGrouped(groupBy: string, params: Record<string, string | undefined>, enabled = true) {
  return useQuery({
    queryKey: ['crm-suite', 'grouped', groupBy, params],
    queryFn: async () => (await api.get<GroupRow[]>('/crm/opportunities/grouped', { params: clean({ ...params, groupBy }) })).data,
    enabled,
  });
}

export function useOpportunity(id: string | undefined) {
  return useQuery({
    queryKey: ['crm-suite', 'opportunity', id],
    queryFn: async () => (await api.get<OpportunityDetail>(`/crm/opportunities/${id}`)).data,
    enabled: !!id,
  });
}

export function useTimeline(id: string | undefined) {
  return useQuery({
    queryKey: ['crm-suite', 'timeline', id],
    queryFn: async () => (await api.get<TimelineItem[]>(`/crm/opportunities/${id}/timeline`)).data,
    enabled: !!id,
  });
}

/** Generic CRM mutation that refreshes every CRM query on success. */
export function useCrmMutation<TVars, TOut = unknown>(fn: (v: TVars) => Promise<TOut>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['crm-suite'] }),
  });
}

export const crmApi = {
  createOpportunity: async (body: Record<string, unknown>) => (await api.post<OpportunityDetail>('/crm/opportunities', body)).data,
  createLead: async (body: Record<string, unknown>) => (await api.post<OpportunityDetail>('/crm/leads', body)).data,
  update: async ({ id, ...body }: { id: string } & Record<string, unknown>) => (await api.patch<OpportunityDetail>(`/crm/opportunities/${id}`, body)).data,
  move: async ({ id, stageId, index }: { id: string; stageId: string; index?: number }) =>
    (await api.post<OpportunityDetail>(`/crm/opportunities/${id}/move`, { stageId, index })).data,
  won: async (id: string) => (await api.post<OpportunityDetail>(`/crm/opportunities/${id}/won`)).data,
  lost: async ({ id, ...body }: { id: string; lostReasonId?: string; lostNote?: string }) =>
    (await api.post<OpportunityDetail>(`/crm/opportunities/${id}/lost`, body)).data,
  reopen: async (id: string) => (await api.post<OpportunityDetail>(`/crm/opportunities/${id}/reopen`)).data,
  convert: async ({ id, ...body }: { id: string } & Record<string, unknown>) =>
    (await api.post<OpportunityDetail>(`/crm/leads/${id}/convert`, body)).data,
  assign: async ({ id, ownerId }: { id: string; ownerId?: string | null }) =>
    (await api.post<OpportunityDetail>(`/crm/opportunities/${id}/assign`, { ownerId })).data,
  archive: async (id: string) => (await api.delete(`/crm/opportunities/${id}`)).data,

  schedule: async (body: Record<string, unknown>) => (await api.post<PlannedActivity>('/crm/planner', body)).data,
  updateActivity: async ({ id, ...body }: { id: string } & Record<string, unknown>) => (await api.patch<PlannedActivity>(`/crm/planner/${id}`, body)).data,
  doneActivity: async ({ id, ...body }: { id: string; outcome?: string; followUp?: Record<string, unknown> }) =>
    (await api.post<PlannedActivity>(`/crm/planner/${id}/done`, body)).data,
  cancelActivity: async (id: string) => (await api.post<PlannedActivity>(`/crm/planner/${id}/cancel`)).data,

  createStage: async (body: Partial<Stage>) => (await api.post<Stage>('/crm/config/stages', body)).data,
  updateStage: async ({ id, ...body }: Partial<Stage> & { id: string }) => (await api.patch<Stage>(`/crm/config/stages/${id}`, body)).data,
  deleteStage: async (id: string) => (await api.delete(`/crm/config/stages/${id}`)).data,
  reorderStages: async (ids: string[]) => (await api.post<Stage[]>('/crm/config/stages/reorder', { ids })).data,
  saveTeam: async ({ id, ...body }: Partial<Team> & { id?: string }) =>
    (id ? await api.patch<Team>(`/crm/config/teams/${id}`, body) : await api.post<Team>('/crm/config/teams', body)).data,
  deleteTeam: async (id: string) => (await api.delete(`/crm/config/teams/${id}`)).data,
  saveLookup: async ({ kind, id, ...body }: { kind: 'tags' | 'sources' | 'lost-reasons'; id?: string; name?: string; color?: string; isActive?: boolean }) =>
    (id ? await api.patch(`/crm/config/${kind}/${id}`, body) : await api.post(`/crm/config/${kind}`, body)).data,
  deleteLookup: async ({ kind, id }: { kind: 'tags' | 'sources' | 'lost-reasons'; id: string }) => (await api.delete(`/crm/config/${kind}/${id}`)).data,
  updateSettings: async (body: Partial<CrmSettings>) => (await api.put<CrmSettings>('/crm/config/settings', body)).data,

  saveFilter: async (body: { scope: string; name: string; filters: Record<string, string>; shared?: boolean }) => (await api.post<SavedFilter>('/crm/saved-filters', body)).data,
  deleteFilter: async (id: string) => (await api.delete(`/crm/saved-filters/${id}`)).data,

  saveRule: async ({ id, ...body }: Partial<PriceRule> & { id?: string }) =>
    (id ? await api.patch<PriceRule>(`/crm/pricing/rules/${id}`, body) : await api.post<PriceRule>('/crm/pricing/rules', body)).data,
  deleteRule: async (id: string) => (await api.delete(`/crm/pricing/rules/${id}`)).data,
  resolvePrices: async (body: { partnerId?: string; lines: Array<{ productId: string; quantity: number }> }) =>
    (await api.post<{ lines: ResolvedPrice[] }>('/crm/pricing/resolve', body)).data,

  createQuote: async (body: Record<string, unknown>) => (await api.post<Quotation>(quoteBase(), body)).data,
  updateQuote: async ({ id, ...body }: { id: string } & Record<string, unknown>) => (await api.put<Quotation>(`${quoteBase()}/${id}`, body)).data,
  quoteAction: async ({ id, action, body }: { id: string; action: 'submit' | 'approve' | 'reject' | 'send' | 'accept' | 'decline' | 'cancel' | 'revise'; body?: Record<string, unknown> }) =>
    (await api.post<Quotation>(`${quoteBase()}/${id}/${action}`, body ?? {})).data,
  convertQuote: async ({ id, target, allowBackorder }: { id: string; target: 'sales_order' | 'order' | 'proforma'; allowBackorder?: boolean }) =>
    (await api.post<Quotation & { converted: { type: string; id: string; number: string } }>(`${quoteBase()}/${id}/convert`, { target, allowBackorder },
      { headers: { 'Idempotency-Key': crypto.randomUUID() } })).data,
  deleteQuote: async (id: string) => (await api.delete(`${quoteBase()}/${id}`)).data,
};

export function useSavedFilters(scope: string) {
  return useQuery({
    queryKey: ['crm-suite', 'saved', scope],
    queryFn: async () => (await api.get<SavedFilter[]>('/crm/saved-filters', { params: { scope } })).data,
  });
}

export function usePlanner(params: Record<string, string | undefined>) {
  return useQuery({
    queryKey: ['crm-suite', 'planner', params],
    queryFn: async () => (await api.get<{ data: PlannedActivity[]; counts: { overdue: number; today: number; upcoming: number } }>('/crm/planner', { params: clean(params) })).data,
    placeholderData: keepPreviousData,
  });
}

export function usePriceRules(params: Record<string, string | undefined> = {}) {
  return useQuery({
    queryKey: ['crm-suite', 'rules', params],
    queryFn: async () => (await api.get<PriceRule[]>('/crm/pricing/rules', { params: clean(params) })).data,
  });
}

export function useQuotations(params: Record<string, string | number | undefined>) {
  return useQuery({
    queryKey: ['crm-suite', 'quotes', params],
    queryFn: async () => (await api.get<Paged<Quotation, { count: number; total: number }>>(quoteBase(), { params: clean(params) })).data,
    placeholderData: keepPreviousData,
  });
}

export function useQuotation(id: string | undefined) {
  return useQuery({
    queryKey: ['crm-suite', 'quote', id],
    queryFn: async () => (await api.get<Quotation>(`${quoteBase()}/${id}`)).data,
    enabled: !!id,
  });
}

export interface CustomerRow {
  id: string; code: string; name: string; email: string | null; phone: string | null; isCompany: boolean; isCustomer: boolean;
  creditLimit: number; creditHold: boolean; categoryName: string | null; openOpportunities: number; openValue: number;
  wonValue: number; won: number; openReceivables: number; overdueReceivables: number; lastActivityAt: string | null;
}

export function useCustomers(params: Record<string, string | number | undefined>) {
  return useQuery({
    queryKey: ['crm-suite', 'customers', params],
    queryFn: async () => (await api.get<Paged<CustomerRow>>('/crm/customers', { params: clean(params) })).data,
    placeholderData: keepPreviousData,
  });
}

export interface Customer360 {
  partner: PartnerLite & Record<string, unknown>;
  opportunities: Opportunity[];
  quotations: Quotation[];
  activities: Array<{ id: string; type: string; title: string; status: string; dueAt: string | null; occurredAt: string; dealId: string | null; outcome: string | null }>;
  invoices: Array<{ id: string; documentNumber: string; documentType: string; status: string; issueDate: string; dueDate: string | null; totalAmount: number; amountResidual: number; paymentStatus: string }>;
  orders: Array<{ id: string; orderNumber: string; status: string; total: number; createdAt: string; invoiceId: string | null }>;
  credit: CreditInfo | null;
  priceRules: PriceRule[];
}

export function useCustomer(id: string | undefined) {
  return useQuery({
    queryKey: ['crm-suite', 'customer', id],
    queryFn: async () => (await api.get<Customer360>(`/crm/customers/${id}`)).data,
    enabled: !!id,
  });
}

export interface Breakdown { key: string | null; label: string; total: number; leads: number; open: number; won: number; lost: number; wonValue: number; openValue: number; winRate: number; conversion: number }

export interface CrmReport {
  range: { from: string; to: string };
  currency: string;
  kpis: {
    leads: number; opportunities: number; converted: number; leadConversion: number; open: number; openValue: number;
    weightedValue: number; won: number; lost: number; wonValue: number; winRate: number; avgDealSize: number;
    avgCycleDays: number; quotations: number; quotationValue: number; quoteAcceptance: number;
  };
  funnel: Array<{ key: string; label: string; color: Color; isWon: boolean; count: number }>;
  byStage: Array<{ key: string; label: string; color: Color; count: number; amount: number; weighted: number }>;
  byOwner: Breakdown[];
  byTeam: Breakdown[];
  bySource: Breakdown[];
  byTag: Breakdown[];
  byCountry: Breakdown[];
  lostReasons: Array<{ key: string | null; label: string; count: number; amount: number }>;
  trend: Array<{ month: string; created: number; won: number; wonValue: number }>;
  forecast: Array<{ month: string; count: number; amount: number; weighted: number }>;
  timeInStage: Array<{ key: string; label: string; avgDays: number; samples: number }>;
  quotations: { count: number; value: number; acceptanceRate: number; byStatus: Array<{ key: QuoteStatus; count: number; value: number }> };
  activities: { byType: Array<{ key: string; done: number }>; overdue: number };
}

export function useCrmReport(params: Record<string, string | undefined>) {
  return useQuery({
    queryKey: ['crm-suite', 'report', params],
    queryFn: async () => (await api.get<CrmReport>('/crm/reports', { params: clean(params) })).data,
    placeholderData: keepPreviousData,
  });
}

export interface CrmDashboard {
  scope: 'mine' | 'all';
  currency: string;
  totals: { openCount: number; openValue: number; weighted: number; leads: number };
  month: { won: number; wonValue: number; target: number; progress: number | null };
  activities: { overdue: number; today: number };
  pendingApproval: { count: number; value: number } | null;
  byStage: Array<{ key: string; label: string; color: Color; count: number; amount: number }>;
  closingSoon: Opportunity[];
  rotting: Opportunity[];
}

export function useCrmDashboard(scope: string) {
  return useQuery({
    queryKey: ['crm-suite', 'dashboard', scope],
    queryFn: async () => (await api.get<CrmDashboard>('/crm/dashboard', { params: { scope } })).data,
  });
}

/** A product as `/products` lists it; also carries its units (ADR-S003) for line unit pickers. */
export interface ProductOption extends UnitAwareProduct { id: string; name: string; code: string | null; salesPrice: number | string | null; taxId: string | null }

export function useProductOptions() {
  return useQuery({
    queryKey: ['crm-suite', 'products'],
    queryFn: async () => (await api.get<{ data: ProductOption[] }>('/products', { params: { pageSize: 500 } })).data.data,
    staleTime: 5 * 60_000,
  });
}

export function usePartnerOptions(search: string, enabled = true) {
  return useQuery({
    enabled,
    queryKey: ['crm-suite', 'partners', search],
    queryFn: async () => (await api.get<{ data: Array<{ id: string; name: string; code: string | null; isCustomer: boolean; email: string | null }> }>('/partners', {
      params: { page: 1, pageSize: 20, search: search || undefined },
    })).data.data,
    placeholderData: keepPreviousData,
  });
}

export function useTaxOptions() {
  return useQuery({
    queryKey: ['crm-suite', 'taxes'],
    queryFn: async () => {
      const res = await api.get<Array<{ id: string; name: string; rate: number | string }> | { data: Array<{ id: string; name: string; rate: number | string }> }>('/taxes', { params: { pageSize: 200 } });
      return Array.isArray(res.data) ? res.data : res.data.data;
    },
    staleTime: 5 * 60_000,
  });
}

export function useCategoryOptions(kind: 'partner' | 'product') {
  return useQuery({
    queryKey: ['crm-suite', 'categories', kind],
    queryFn: async () => {
      const res = await api.get<Array<{ id: string; name: string }> | { data: Array<{ id: string; name: string }> }>(
        kind === 'partner' ? '/partner-categories' : '/product-categories', { params: { pageSize: 200 } });
      return Array.isArray(res.data) ? res.data : res.data.data;
    },
    staleTime: 5 * 60_000,
  });
}

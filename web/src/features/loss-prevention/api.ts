import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

/** Loss prevention (ADR-S077): cases raised by reconciliation, the rules behind them, runs and reports. */
export type Severity = 'info' | 'warning' | 'critical';
export type CaseState = 'open' | 'reviewing' | 'escalated' | 'closed';
export type Outcome = 'explained' | 'confirmed_variance' | 'false_alarm' | 'inconclusive';
type Num = number | string | null;

export const OUTCOMES: Outcome[] = ['explained', 'confirmed_variance', 'false_alarm', 'inconclusive'];
export const CAUSE_CODES = [
  'spillage', 'breakage', 'complimentary', 'staff_drink', 'unrecorded_sale', 'bottle_transfer', 'measurement_error',
  'unexplained', 'remake', 'waste', 'late_sync', 'training',
] as const;

export interface Paged<T> {
  data: T[];
  meta: { page: number; pageSize: number; total: number; totalPages: number };
}

export interface LpCase {
  id: string;
  caseNumber: string;
  engine: string;
  ruleCode: string;
  branchId: string;
  businessDate: string;
  title: string;
  severity: Severity;
  state: CaseState;
  outcome: Outcome | null;
  causeCode: string | null;
  subjectType: string | null;
  subjectId: string | null;
  subjectName: string | null;
  assigneeId: string | null;
  assigneeName: string | null;
  exposureAmount: Num;
  exposureMethod: string | null;
  openedAt: string;
  closedAt: string | null;
  closedBy: string | null;
}

export interface Finding {
  id: string;
  runId: string;
  ruleCode: string;
  fingerprint: string;
  severity: Severity;
  title: string;
  subjectId: string | null;
  expected: Num;
  observed: Num;
  delta: Num;
  unit: string | null;
  exposureAmount: Num;
  exposureMethod: string | null;
  detail: Record<string, unknown>;
  status: 'active' | 'superseded';
  createdAt: string;
  caseId?: string | null;
  caseNumber?: string | null;
  caseState?: CaseState | null;
}

export interface LedgerEvent {
  id: string;
  type: string;
  source: string;
  occurredAt: string;
  evidenceLevel: string;
  evidenceSource: string;
  observationStatus: string;
  payload: Record<string, unknown>;
}

export interface TimelineEntry {
  id: string;
  kind: string;
  actorId: string | null;
  actorName: string | null;
  note: string | null;
  data: Record<string, unknown>;
  createdAt: string;
}

export interface CaseDetail extends LpCase {
  findings: Finding[];
  events: LedgerEvent[];
  timeline: TimelineEntry[];
}

export interface Rule {
  code: string;
  engine: string;
  title: string;
  enabled: boolean;
  severity: Severity;
  params: Record<string, Num>;
  defaults: { enabled: boolean; severity: Severity; params: Record<string, Num> };
  finalOnly: boolean;
  version: number;
  customized: boolean;
  configured: boolean;
}

export interface RuleInput {
  enabled: boolean;
  severity: Severity;
  params: Record<string, number | null>;
}

export interface Run {
  id: string;
  windowId: string;
  engine: string;
  engineVersion: string;
  branchId: string;
  businessDate: string;
  trigger: string;
  isFinal: boolean;
  inputCount: number;
  findingCount: number;
  supersedesRunId: string | null;
  createdAt: string;
  /** LP2: a camera window was not fully observed (a vision gap, or past the agent's watermark). */
  observationGap?: boolean;
}

export interface RunComparison {
  from: Run;
  to: Run;
  added: Finding[];
  removed: Finding[];
  changed: { fingerprint: string; before: Finding; after: Finding }[];
  unchanged: number;
  configChanged: boolean;
  inputChanged: boolean;
}

export interface Dashboard {
  totals: {
    open: number; notStarted: number; reviewing: number; escalated: number; critical: number; exposure: Num;
    oldestOpenedAt: string | null;
  };
  byEngine: { engine: string; cases: number }[];
  byRule: { ruleCode: string; title: string; cases: number }[];
  byBranch: { branchId: string; branchName: string | null; cases: number }[];
  trend: { day: string; cases: number; confirmed: number }[];
  disclaimer: string;
}

export interface Summary {
  generated: number; reviewed: number; closed: number; closedAutomatically: number; explained: number;
  confirmedVariance: number; falseAlarm: number; inconclusive: number; escalated: number;
  medianHoursToReview: Num; medianHoursToClose: Num;
}

export interface Performance {
  from: string;
  to: string;
  summary: Summary;
  previous: { from: string; to: string; summary: Summary };
  byEngine: { engine: string; cases: number; confirmed: number; falseAlarm: number }[];
  byRule: { ruleCode: string; title: string; cases: number; confirmed: number; falseAlarm: number; exposure: Num; exposureMethod: string | null }[];
  byBranch: { branchId: string; branchName: string | null; cases: number; confirmed: number }[];
  byCause: { causeCode: string; cases: number }[];
  disclaimer: string;
}

export interface CashierRow {
  subjectId: string; subjectName: string | null; cases: number; open: number; confirmed: number; explained: number;
  falseAlarm: number; rules: string[]; exposure: Num;
}

export interface BarRow {
  productId: string; productName: string | null; lines: number; varianceMl: Num; shortageMl: Num; soldMl: Num;
  withoutCause: number;
}

export type Filters = Record<string, string | number | undefined>;

const KEY = ['loss-prevention'] as const;
const BASE = '/loss-prevention';
const idem = () => ({ headers: { 'Idempotency-Key': crypto.randomUUID() } });

/** `refetchInterval` (ms) keeps a live screen moving; off by default. */
export const useLpDashboard = (refetchInterval?: number) => useQuery({
  queryKey: [...KEY, 'dashboard'],
  refetchInterval,
  queryFn: async () => (await api.get<Dashboard>(`${BASE}/dashboard`)).data,
});

export const useLpCases = (filters: Filters, refetchInterval?: number) => useQuery({
  queryKey: [...KEY, 'cases', filters],
  refetchInterval,
  queryFn: async () => (await api.get<Paged<LpCase>>(`${BASE}/cases`, { params: filters })).data,
});

export const useLpCase = (id: string | null) => useQuery({
  queryKey: [...KEY, 'case', id],
  enabled: !!id,
  queryFn: async () => (await api.get<CaseDetail>(`${BASE}/cases/${id}`)).data,
});

/** A review step on a case: `action` is the last path segment (assign, notes, start-review, escalate, close, reopen). */
export const useLpCaseAction = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (v: { id: string; action: string; body?: Record<string, unknown> }) =>
      (await api.post<LpCase>(`${BASE}/cases/${v.id}/${v.action}`, v.body ?? {})).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
};

export const useLpRules = () => useQuery({
  queryKey: [...KEY, 'rules'],
  queryFn: async () => (await api.get<Rule[]>(`${BASE}/rules`)).data,
});

export const useSaveLpRule = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (v: { code: string; body: RuleInput }) =>
      (await api.put<Rule>(`${BASE}/rules/${v.code}`, v.body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: [...KEY, 'rules'] }),
  });
};

export const useLpRuns = (filters: Filters) => useQuery({
  queryKey: [...KEY, 'runs', filters],
  queryFn: async () => (await api.get<Paged<Run>>(`${BASE}/runs`, { params: filters })).data,
});

export const useLpRunComparison = (from: string | null, to: string | null) => useQuery({
  queryKey: [...KEY, 'compare', from, to],
  enabled: !!from && !!to,
  queryFn: async () => (await api.get<RunComparison>(`${BASE}/runs/${from}/compare/${to}`)).data,
});

export const useLpReplay = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { engine: string; from: string; to: string }) =>
      (await api.post<{ windows: number; findings: number }>(`${BASE}/runs/replay`, body, idem())).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
};

/** Sweep this ERP's records into the ledger and run what is due, now instead of on the schedule. */
export const useLpSync = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => (await api.post<{ windowsReconciled: number }>(`${BASE}/projection/run`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
};

export const useLpPerformance = (filters: Filters) => useQuery({
  queryKey: [...KEY, 'performance', filters],
  queryFn: async () => (await api.get<Performance>(`${BASE}/reports/performance`, { params: filters })).data,
});

export const useLpByCashier = (filters: Filters) => useQuery({
  queryKey: [...KEY, 'by-cashier', filters],
  queryFn: async () => (await api.get<{ rows: CashierRow[] }>(`${BASE}/reports/by-cashier`, { params: filters })).data,
});

export const useLpBarVariance = (filters: Filters) => useQuery({
  queryKey: [...KEY, 'bar-variance', filters],
  queryFn: async () => (await api.get<{ rows: BarRow[] }>(`${BASE}/reports/bar-variance`, { params: filters })).data,
});

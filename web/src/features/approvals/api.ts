import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

/** Approval hub + rules builder client (ADR-S061). */

export type Priority = 'low' | 'medium' | 'high' | 'critical';
export type RequestStatus = 'pending' | 'approved' | 'rejected' | 'cancelled';
export type InboxScope = 'mine' | 'requested' | 'all';
export type ApproverScope = 'permission' | 'department_manager' | 'user' | 'role';

export interface PersonRef {
  id: string;
  name: string;
  email: string | null;
}

export interface InboxCard {
  id: string;
  entityType: string;
  entityId: string;
  reference: string | null;
  title: string | null;
  amount: number | null;
  currency: string | null;
  priority: Priority;
  status: RequestStatus;
  createdAt: string;
  decidedAt: string | null;
  requester: PersonRef | null;
  workflowName: string | null;
  stage: { stepOrder: number; name: string | null; index: number; total: number };
  decisionCount: number;
  canDecide: boolean;
}

export type StageState =
  | 'approved'
  | 'current'
  | 'rejected'
  | 'cancelled'
  | 'waiting'
  | 'not_reached'
  | 'skipped';

export interface StageAssignee {
  kind: ApproverScope;
  label: string;
  permissions: string[];
  users: PersonRef[];
}

export interface TimelineStage {
  stepOrder: number;
  name: string;
  state: StageState;
  requiredCount: number;
  approvals: number;
  decisions: {
    approver: PersonRef;
    status: 'approved' | 'rejected';
    comment: string | null;
    decidedAt: string;
  }[];
  assignee: StageAssignee;
}

export interface Timeline {
  request: InboxCard;
  workflow: { id: string; name: string; description: string | null; conditions: ConditionGroup | null } | null;
  stages: TimelineStage[];
  details: { key: string; label: string; value: unknown }[];
  snapshot: Record<string, unknown>;
  canDecide: boolean;
}

export interface Summary {
  awaitingMe: number;
  awaitingMeUrgent: number;
  requestedPending: number;
  decidedToday: number;
}

// ---- conditions ----

export type Operator =
  | 'eq'
  | 'neq'
  | 'gt'
  | 'gte'
  | 'lt'
  | 'lte'
  | 'between'
  | 'in'
  | 'not_in'
  | 'contains'
  | 'not_contains'
  | 'starts_with'
  | 'is_empty'
  | 'is_not_empty';

export interface ConditionLeaf {
  field: string;
  operator: Operator;
  value?: unknown;
}

export interface ConditionGroup {
  logic: 'and' | 'or';
  rules: (ConditionLeaf | ConditionGroup)[];
}

export const isGroup = (r: ConditionLeaf | ConditionGroup): r is ConditionGroup =>
  (r as ConditionGroup).rules !== undefined;

// ---- workflows ----

export interface WorkflowStep {
  id?: string;
  stepOrder: number;
  name: string;
  approverPermissions: string[] | null;
  requiredCount: number;
  minAmount: number | null;
  maxAmount: number | null;
  branchId: string | null;
  departmentId: string | null;
  approverScope: ApproverScope;
  conditions: ConditionGroup | null;
  approverUserIds: string[] | null;
  approverRoleIds: string[] | null;
}

export interface Workflow {
  id: string;
  name: string;
  entityType: string;
  description: string | null;
  minAmount: number | null;
  branchId: string | null;
  departmentId: string | null;
  enforceDistinctApprovers: boolean;
  isActive: boolean;
  conditions: ConditionGroup | null;
  precedence: number;
  priority: Priority;
  steps: WorkflowStep[];
  createdAt: string;
}

export interface FieldMeta {
  key: string;
  label: string;
  type: 'number' | 'text' | 'date' | 'boolean' | 'list' | 'id' | 'branch' | 'department' | 'user' | 'priority';
  sample: unknown;
}

export interface BuilderMeta {
  fields: FieldMeta[];
  operators: Operator[];
  approverScopes: ApproverScope[];
  priorities: Priority[];
  users: PersonRef[];
  roles: { id: string; name: string }[];
}

export interface Simulation {
  needsApproval: boolean;
  priority: Priority;
  workflow: { id: string; name: string } | null;
  stages: { stepOrder: number; name: string; applies: boolean; requiredCount: number; assignee: StageAssignee }[];
}

// ---- hooks ----

export function useInbox(params: {
  scope: InboxScope;
  status?: RequestStatus;
  priority?: Priority | '';
  entityType?: string;
  q?: string;
}) {
  return useQuery<InboxCard[]>({
    queryKey: ['approvals', 'inbox', params],
    queryFn: async () =>
      (
        await api.get<InboxCard[]>('/approvals/inbox', {
          params: {
            scope: params.scope,
            status: params.scope === 'mine' ? undefined : params.status,
            priority: params.priority || undefined,
            entityType: params.entityType || undefined,
            q: params.q || undefined,
          },
        })
      ).data,
  });
}

export function useApprovalSummary() {
  return useQuery<Summary>({
    queryKey: ['approvals', 'summary'],
    queryFn: async () => (await api.get<Summary>('/approvals/summary')).data,
    refetchInterval: 60_000,
  });
}

export function useTimeline(id: string | null) {
  return useQuery<Timeline>({
    queryKey: ['approvals', 'timeline', id],
    enabled: !!id,
    queryFn: async () => (await api.get<Timeline>(`/approvals/${id}/timeline`)).data,
  });
}

export function useDecide() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { id: string; status: 'approved' | 'rejected'; comment?: string }) =>
      (await api.post(`/approvals/${vars.id}/decide`, { status: vars.status, comment: vars.comment || undefined }))
        .data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

export function useWorkflows() {
  return useQuery<Workflow[]>({
    queryKey: ['approval-workflows'],
    queryFn: async () => (await api.get<Workflow[]>('/approval-workflows')).data,
  });
}

export function useBuilderMeta(entityType: string | null) {
  return useQuery<BuilderMeta>({
    queryKey: ['approval-workflows', 'meta', entityType],
    enabled: !!entityType,
    queryFn: async () =>
      (await api.get<BuilderMeta>('/approval-workflows/meta', { params: { entityType } })).data,
  });
}

export async function simulate(entityType: string, snapshot: Record<string, unknown>) {
  return (await api.post<Simulation>('/approval-workflows/simulate', { entityType, snapshot })).data;
}

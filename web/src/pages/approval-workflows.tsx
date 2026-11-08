import { useMemo, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft,
  ArrowRight,
  ChevronDown,
  ChevronRight,
  Copy,
  FlaskConical,
  FolderOpen,
  ListPlus,
  Minus,
  MoreHorizontal,
  Pencil,
  Plus,
  Save,
  ToggleLeft,
  ToggleRight,
  Trash2,
  User,
  Users,
  X,
} from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import { api } from '@/lib/api';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { entityTypeLabel } from '@/lib/approval-entity-types';
import { isHeldForApproval, useBranchChoices, useDepartmentChoices } from '@/features/approval-controls/api';
import { usePermissionCatalog } from '@/features/staff/api';
import {
  type ApproverScope,
  type BuilderMeta,
  type ConditionGroup,
  type ConditionLeaf,
  type FieldMeta,
  type Operator,
  type Priority,
  type Simulation,
  type Workflow,
  isGroup,
  simulate,
  useBuilderMeta,
  useWorkflows,
} from '@/features/approvals/api';
import { PriorityBadge } from '@/pages/approvals';

// ---- catalogue ---------------------------------------------------------------------------------------------------

const MODULES: { name: string; types: string[] }[] = [
  {
    name: tx('Procurement'),
    types: ['purchase_request', 'purchase_order', 'rfq_award', 'goods_receipt', 'supplier_bill_approval', 'vendor_bill', 'debit_note'],
  },
  { name: tx('Expenses'), types: ['expense', 'expense_claim', 'expense_payment', 'supplier_payment', 'credit_note', 'invoice_cancel'] },
  { name: tx('Sales'), types: ['sales_order', 'credit_override', 'rental_agreement'] },
  {
    name: tx('Inventory'),
    types: ['stock_adjustment', 'stock_out', 'waste', 'inventory_count_submit', 'production_loss_writeoff', 'bom_version'],
  },
  { name: tx('Assets'), types: ['asset_acquisition', 'asset_disposal', 'asset_transfer', 'asset_revaluation', 'depreciation_run'] },
  { name: tx('Operations'), types: ['fleet_meter_correction', 'quarry_explosive_issue'] },
  { name: tx('POS'), types: ['pos_discount', 'pos_refund'] },
  { name: tx('Controls'), types: ['config_change'] },
];

const OPERATOR_LABEL: Record<Operator, string> = {
  eq: tx('Equals'),
  neq: tx('Not Equal To'),
  gt: tx('Greater Than'),
  gte: tx('Greater Than or Equal'),
  lt: tx('Less Than'),
  lte: tx('Less Than or Equal'),
  between: tx('Between'),
  in: tx('Is Any Of'),
  not_in: tx('Is None Of'),
  contains: tx('Contains'),
  not_contains: tx('Does Not Contain'),
  starts_with: tx('Starts With'),
  is_empty: tx('Is Empty'),
  is_not_empty: tx('Is Not Empty'),
};

const OPS_BY_TYPE: Record<string, Operator[]> = {
  number: ['gt', 'gte', 'lt', 'lte', 'eq', 'neq', 'between', 'is_empty', 'is_not_empty'],
  date: ['gt', 'gte', 'lt', 'lte', 'eq', 'between', 'is_empty', 'is_not_empty'],
  option: ['eq', 'neq', 'in', 'not_in', 'is_empty', 'is_not_empty'],
  list: ['contains', 'not_contains', 'is_empty', 'is_not_empty'],
  text: ['eq', 'neq', 'in', 'not_in', 'contains', 'not_contains', 'starts_with', 'is_empty', 'is_not_empty'],
};

const OPTION_TYPES = new Set(['branch', 'department', 'user', 'priority', 'boolean']);
const opsFor = (type: string | undefined) =>
  OPS_BY_TYPE[type && OPTION_TYPES.has(type) ? 'option' : type === 'number' || type === 'date' || type === 'list' ? type : 'text'];

const SCOPE_LABEL: Record<ApproverScope, string> = {
  department_manager: tx('Dynamic Department Head'),
  user: tx('Specific users'),
  role: tx('Role'),
  permission: tx('Permission holders'),
};

const PRIORITIES: Priority[] = ['low', 'medium', 'high', 'critical'];

// ---- draft model -------------------------------------------------------------------------------------------------

interface StageDraft {
  key: string;
  name: string;
  approverScope: ApproverScope;
  approverUserIds: string[];
  approverRoleIds: string[];
  approverPermissions: string[];
  requiredCount: number;
  minAmount: string;
  maxAmount: string;
  departmentId: string;
  conditions: ConditionGroup;
}

interface RuleDraft {
  id: string | null;
  name: string;
  entityType: string;
  description: string;
  priority: Priority;
  precedence: number;
  isActive: boolean;
  enforceDistinctApprovers: boolean;
  minAmount: string;
  branchId: string;
  departmentId: string;
  conditions: ConditionGroup;
  stages: StageDraft[];
}

const emptyGroup = (): ConditionGroup => ({ logic: 'and', rules: [] });
let seq = 0;
const key = () => `k${++seq}`;

const newStage = (name = ''): StageDraft => ({
  key: key(),
  name,
  approverScope: 'department_manager',
  approverUserIds: [],
  approverRoleIds: [],
  approverPermissions: [],
  requiredCount: 1,
  minAmount: '',
  maxAmount: '',
  departmentId: '',
  conditions: emptyGroup(),
});

const newRule = (entityType: string): RuleDraft => ({
  id: null,
  name: '',
  entityType,
  description: '',
  priority: 'medium',
  precedence: 0,
  isActive: true,
  enforceDistinctApprovers: false,
  minAmount: '',
  branchId: '',
  departmentId: '',
  conditions: { logic: 'and', rules: [{ field: 'amount', operator: 'gt', value: 1000 }] },
  stages: [newStage(tx('Department Head Approval')), { ...newStage(tx('Finance Director')), approverScope: 'role' }],
});

const fromWorkflow = (w: Workflow): RuleDraft => ({
  id: w.id,
  name: w.name,
  entityType: w.entityType,
  description: w.description ?? '',
  priority: w.priority ?? 'medium',
  precedence: w.precedence ?? 0,
  isActive: w.isActive,
  enforceDistinctApprovers: w.enforceDistinctApprovers,
  minAmount: w.minAmount == null ? '' : String(w.minAmount),
  branchId: w.branchId ?? '',
  departmentId: w.departmentId ?? '',
  conditions: w.conditions ?? emptyGroup(),
  stages: [...w.steps]
    .sort((a, b) => a.stepOrder - b.stepOrder)
    .map((s) => ({
      key: key(),
      name: s.name,
      approverScope: s.approverScope ?? 'permission',
      approverUserIds: s.approverUserIds ?? [],
      approverRoleIds: s.approverRoleIds ?? [],
      approverPermissions: s.approverPermissions ?? [],
      requiredCount: s.requiredCount,
      minAmount: s.minAmount == null ? '' : String(s.minAmount),
      maxAmount: s.maxAmount == null ? '' : String(s.maxAmount),
      departmentId: s.departmentId ?? '',
      conditions: s.conditions ?? emptyGroup(),
    })),
});

const orNull = (g: ConditionGroup) => (g.rules.length === 0 ? null : g);
const num = (v: string) => (v.trim() === '' ? null : Number(v));

function toPayload(d: RuleDraft) {
  return {
    name: d.name.trim() || entityTypeLabel(d.entityType),
    entityType: d.entityType,
    description: d.description.trim() || null,
    priority: d.priority,
    precedence: Number(d.precedence) || 0,
    isActive: d.isActive,
    enforceDistinctApprovers: d.enforceDistinctApprovers,
    minAmount: num(d.minAmount),
    branchId: d.branchId || null,
    departmentId: d.departmentId || null,
    conditions: orNull(d.conditions),
    steps: d.stages.map((s, i) => ({
      stepOrder: i + 1,
      name: s.name.trim() || `${tx('Stage')} ${i + 1}`,
      approverScope: s.approverScope,
      approverUserIds: s.approverScope === 'user' ? s.approverUserIds : null,
      approverRoleIds: s.approverScope === 'role' ? s.approverRoleIds : null,
      approverPermissions: s.approverPermissions,
      requiredCount: Number(s.requiredCount) || 1,
      minAmount: num(s.minAmount),
      maxAmount: num(s.maxAmount),
      departmentId: s.departmentId || null,
      conditions: orNull(s.conditions),
    })),
  };
}

// ---- sentences ---------------------------------------------------------------------------------------------------

interface Lookups {
  fields: Map<string, FieldMeta>;
  options: (field: FieldMeta | undefined) => { id: string; name: string }[];
}

function valueText(v: unknown, field: FieldMeta | undefined, lk: Lookups): string {
  const opts = lk.options(field);
  const one = (x: unknown) => {
    const hit = opts.find((o) => o.id === String(x));
    if (hit) return hit.name;
    if (typeof x === 'number') return new Intl.NumberFormat().format(x);
    return String(x);
  };
  if (Array.isArray(v)) return v.map(one).join(', ');
  return one(v);
}

function describe(g: ConditionGroup, lk: Lookups, nested = false): string {
  const parts = g.rules.map((r) => {
    if (isGroup(r)) return describe(r, lk, true);
    const f = lk.fields.get(r.field);
    const label = f?.label ?? r.field;
    const op = OPERATOR_LABEL[r.operator]?.toLowerCase() ?? r.operator;
    if (r.operator === 'is_empty' || r.operator === 'is_not_empty') return `${label} ${op}`;
    if (r.operator === 'between' && Array.isArray(r.value))
      return `${label} ${tx('is between')} ${valueText(r.value[0], f, lk)} ${tx('and')} ${valueText(r.value[1], f, lk)}`;
    return `${label} ${tx('is')} ${op} ${valueText(r.value, f, lk)}`;
  });
  const s = parts.join(g.logic === 'or' ? ` ${tx('OR')} ` : ` ${tx('AND')} `);
  return nested && parts.length > 1 ? `(${s})` : s;
}

// ---- page --------------------------------------------------------------------------------------------------------

export function ApprovalWorkflowsPage() {
  const list = useWorkflows();
  const [entityType, setEntityType] = useState<string>('purchase_order');
  const [draft, setDraft] = useState<RuleDraft | null>(null);
  const [custom, setCustom] = useState('');
  const meta = useBuilderMeta(entityType);

  const counts = useMemo(() => {
    const m = new Map<string, number>();
    list.data?.forEach((w) => m.set(w.entityType, (m.get(w.entityType) ?? 0) + 1));
    return m;
  }, [list.data]);
  const known = new Set(MODULES.flatMap((m) => m.types));
  const others = [...counts.keys()].filter((t) => !known.has(t));
  const modules = others.length ? [...MODULES, { name: tx('Other'), types: others }] : MODULES;
  const moduleOf = modules.find((m) => m.types.includes(entityType))?.name ?? tx('Other');
  const rules = (list.data ?? [])
    .filter((w) => w.entityType === entityType)
    .sort((a, b) => (b.precedence ?? 0) - (a.precedence ?? 0) || a.createdAt.localeCompare(b.createdAt));

  const pick = (t: string) => {
    setEntityType(t);
    setDraft(null);
  };

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Workflow Configuration')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Settings')} → {moduleOf} → {entityTypeLabel(entityType)}
        </p>
      </div>

      <div className="grid gap-4 lg:grid-cols-[240px_1fr]">
        {/* module navigator */}
        <Card className="h-fit">
          <CardContent className="space-y-3 p-3">
            {modules.map((m) => (
              <div key={m.name} className="space-y-0.5">
                <div className="px-2 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{m.name}</div>
                {m.types.map((t) => (
                  <button
                    key={t}
                    type="button"
                    onClick={() => pick(t)}
                    className={cn(
                      'flex w-full items-center justify-between gap-2 rounded-md px-2 py-1.5 text-left text-sm',
                      t === entityType ? 'bg-primary/10 font-medium text-primary' : 'hover:bg-accent',
                    )}
                  >
                    <span className="inline-flex min-w-0 items-center gap-2">
                      <FolderOpen className={cn('h-4 w-4 shrink-0', t === entityType ? 'text-primary' : 'text-muted-foreground')} />
                      <span className="truncate">{entityTypeLabel(t)}</span>
                    </span>
                    {counts.get(t) ? (
                      <span className="rounded-full bg-muted px-1.5 text-[11px] text-muted-foreground">{counts.get(t)}</span>
                    ) : null}
                  </button>
                ))}
              </div>
            ))}
            <div className="flex gap-1 border-t pt-3">
              <Input
                className="h-8 text-xs"
                placeholder={tx('other document type…')}
                value={custom}
                onChange={(e) => setCustom(e.target.value.replace(/[^a-z0-9_]/gi, '_').toLowerCase())}
              />
              <Button size="sm" variant="outline" disabled={!custom} onClick={() => pick(custom)}>
                {tx('Open')}
              </Button>
            </div>
          </CardContent>
        </Card>

        {/* rules */}
        <div className="min-w-0 space-y-4">
          {draft ? (
            <RuleEditor
              key={draft.id ?? 'new'}
              initial={draft}
              meta={meta.data}
              onClose={() => setDraft(null)}
            />
          ) : (
            <>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <h2 className="text-lg font-semibold">{tx('Approval Rules')}</h2>
                  <p className="text-sm text-muted-foreground">
                    {tx('Rules are tried from the highest precedence down; the first whose conditions match routes the document. No matching rule = auto-approved.')}
                  </p>
                </div>
                <Button onClick={() => setDraft(newRule(entityType))}>
                  <Plus className="mr-1 h-4 w-4" /> {tx('New Rule')}
                </Button>
              </div>
              {list.isLoading && <Skeleton className="h-40 w-full" />}
              {list.data && rules.length === 0 && (
                <Card>
                  <CardContent className="flex flex-col items-center gap-3 p-12 text-center">
                    <p className="text-sm text-muted-foreground">
                      {tx('{{type}} are auto-approved: no rule is configured.', { type: entityTypeLabel(entityType) })}
                    </p>
                    <Button variant="outline" size="sm" onClick={() => setDraft(newRule(entityType))}>
                      <Plus className="mr-1 h-4 w-4" /> {tx('Add your first rule')}
                    </Button>
                  </CardContent>
                </Card>
              )}
              {rules.map((w) => (
                <RuleCard
                  key={w.id}
                  rule={w}
                  meta={meta.data}
                  onEdit={() => setDraft(fromWorkflow(w))}
                  onDuplicate={() => setDraft({ ...fromWorkflow(w), id: null, name: `${w.name} (${tx('copy')})` })}
                />
              ))}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

// ---- rule card ---------------------------------------------------------------------------------------------------

function useLookups(meta: BuilderMeta | undefined): Lookups {
  const branches = useBranchChoices();
  const departments = useDepartmentChoices();
  return useMemo(() => {
    const fields = new Map<string, FieldMeta>();
    meta?.fields.forEach((f) => fields.set(f.key, f));
    const options = (f: FieldMeta | undefined) => {
      switch (f?.type) {
        case 'branch':
          return branches.data ?? [];
        case 'department':
          return departments.data ?? [];
        case 'user':
          return meta?.users.map((u) => ({ id: u.id, name: u.name || u.email || u.id })) ?? [];
        case 'priority':
          return PRIORITIES.map((p) => ({ id: p, name: p }));
        case 'boolean':
          return [
            { id: 'true', name: tx('Yes') },
            { id: 'false', name: tx('No') },
          ];
        default:
          return [];
      }
    };
    return { fields, options };
  }, [meta, branches.data, departments.data]);
}

function assigneeText(
  s: { approverScope: ApproverScope; approverUserIds: string[] | null; approverRoleIds: string[] | null; approverPermissions: string[] | null },
  meta: BuilderMeta | undefined,
) {
  switch (s.approverScope) {
    case 'department_manager':
      return tx('Dynamic Department Head');
    case 'user': {
      const names = (s.approverUserIds ?? []).map((id) => meta?.users.find((u) => u.id === id)?.name ?? id);
      return names.join(', ') || tx('No users');
    }
    case 'role': {
      const names = (s.approverRoleIds ?? []).map((id) => meta?.roles.find((r) => r.id === id)?.name ?? id);
      return `${tx('Role:')} ${names.join(', ') || '—'}`;
    }
    default:
      return s.approverPermissions?.length ? s.approverPermissions.join(', ') : tx('Anyone with approval access');
  }
}

function RuleCard({
  rule,
  meta,
  onEdit,
  onDuplicate,
}: {
  rule: Workflow;
  meta: BuilderMeta | undefined;
  onEdit: () => void;
  onDuplicate: () => void;
}) {
  const qc = useQueryClient();
  const lk = useLookups(meta);
  const toggle = useMutation({
    mutationFn: async () => (await api.patch(`/approval-workflows/${rule.id}`, { isActive: !rule.isActive })).data,
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ['approval-workflows'] });
      notify.success(isHeldForApproval(data) ? tx('Held for approval') : tx('Rule updated'));
    },
    onError: (err: any) => notify.error(err?.response?.data?.message ?? tx('Failed')),
  });
  const remove = useMutation({
    mutationFn: async () => (await api.delete(`/approval-workflows/${rule.id}`)).data,
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ['approval-workflows'] });
      notify.success(isHeldForApproval(data) ? tx('Held for approval') : tx('Rule deleted'));
    },
    onError: (err: any) => notify.error(err?.response?.data?.message ?? tx('Failed')),
  });
  const steps = [...rule.steps].sort((a, b) => a.stepOrder - b.stepOrder);
  const when = rule.conditions?.rules.length ? describe(rule.conditions, lk) : null;

  return (
    <Card className={cn(!rule.isActive && 'opacity-60')}>
      <CardContent className="space-y-3 p-4">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0 space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-semibold">{rule.name}</span>
              <PriorityBadge priority={rule.priority ?? 'medium'} />
              {rule.precedence ? <Badge variant="outline">{tx('Precedence')} {rule.precedence}</Badge> : null}
              {rule.enforceDistinctApprovers && <Badge variant="outline">{tx('SoD')}</Badge>}
              {!rule.isActive && <Badge variant="secondary">{tx('Inactive')}</Badge>}
            </div>
            <p className="text-sm">
              <span className="font-semibold">{tx('If')}</span>{' '}
              {when ?? <span className="text-muted-foreground">{tx('any document (no conditions)')}</span>}
              {rule.minAmount != null && (
                <span className="text-muted-foreground">
                  {' '}
                  · {tx('amount')} ≥ {rule.minAmount}
                </span>
              )}
            </p>
            {rule.description && <p className="text-xs text-muted-foreground">{rule.description}</p>}
          </div>
          <div className="flex shrink-0 items-center">
            <Button
              variant="ghost"
              size="icon"
              disabled={toggle.isPending}
              onClick={() => toggle.mutate()}
              title={rule.isActive ? tx('Active — click to disable') : tx('Inactive — click to enable')}
            >
              {rule.isActive ? (
                <ToggleRight className="h-5 w-5 text-emerald-500" />
              ) : (
                <ToggleLeft className="h-5 w-5 text-muted-foreground" />
              )}
            </Button>
            <Button variant="ghost" size="icon" onClick={onEdit} title={tx('Edit')}>
              <Pencil className="h-4 w-4" />
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon">
                  <MoreHorizontal className="h-4 w-4" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem onClick={onDuplicate}>
                  <Copy className="mr-2 h-4 w-4" /> {tx('Duplicate')}
                </DropdownMenuItem>
                <DropdownMenuItem
                  className="text-red-600"
                  onClick={() => {
                    if (confirm(tx('Delete this rule?'))) remove.mutate();
                  }}
                >
                  <Trash2 className="mr-2 h-4 w-4" /> {tx('Delete')}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {steps.map((s, i) => (
            <div key={s.id ?? i} className="flex items-center gap-2">
              <div className="flex items-center gap-2 rounded-md border bg-muted/40 px-2.5 py-1.5 text-sm">
                <span className="flex h-5 w-5 items-center justify-center rounded-full bg-primary text-[11px] font-semibold text-primary-foreground">
                  {i + 1}
                </span>
                <div className="min-w-0">
                  <div className="font-medium leading-tight">{s.name}</div>
                  <div className="max-w-[16rem] truncate text-xs text-muted-foreground">
                    {assigneeText(s, meta)}
                    {s.requiredCount > 1 && ` · ${s.requiredCount} ${tx('approvals')}`}
                    {s.conditions?.rules.length ? ` · ${tx('only if')} ${describe(s.conditions, lk)}` : ''}
                  </div>
                </div>
              </div>
              {i < steps.length - 1 && <ArrowRight className="h-4 w-4 text-muted-foreground" />}
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

// ---- rule editor -------------------------------------------------------------------------------------------------

function RuleEditor({ initial, meta, onClose }: { initial: RuleDraft; meta: BuilderMeta | undefined; onClose: () => void }) {
  const qc = useQueryClient();
  const [d, setD] = useState<RuleDraft>(initial);
  const [advanced, setAdvanced] = useState(
    !!(initial.branchId || initial.departmentId || initial.minAmount || initial.precedence || initial.enforceDistinctApprovers),
  );
  const branches = useBranchChoices();
  const departments = useDepartmentChoices();
  const lk = useLookups(meta);
  const set = (patch: Partial<RuleDraft>) => setD((x) => ({ ...x, ...patch }));
  const setStage = (i: number, patch: Partial<StageDraft>) =>
    setD((x) => ({ ...x, stages: x.stages.map((s, j) => (j === i ? { ...s, ...patch } : s)) }));
  const moveStage = (i: number, by: number) =>
    setD((x) => {
      const stages = [...x.stages];
      const [s] = stages.splice(i, 1);
      stages.splice(i + by, 0, s);
      return { ...x, stages };
    });

  const save = useMutation({
    mutationFn: async () => {
      if (d.stages.length === 0) throw new Error(tx('Add at least one approval stage'));
      const body = toPayload(d);
      return d.id
        ? (await api.patch(`/approval-workflows/${d.id}`, body)).data
        : (await api.post('/approval-workflows', body)).data;
    },
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ['approval-workflows'] });
      notify.success(isHeldForApproval(data) ? tx('Held for approval') : tx('Rule saved'));
      onClose();
    },
    onError: (err: any) => {
      const m = err?.response?.data?.message ?? err?.message ?? tx('Failed');
      notify.error(Array.isArray(m) ? m.join('; ') : m);
    },
  });

  return (
    <Card>
      <CardContent className="space-y-5 p-5">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="icon" onClick={onClose} title={tx('Back')}>
              <ArrowLeft className="h-4 w-4" />
            </Button>
            <h2 className="text-lg font-semibold">{tx('Approval Rules Builder')}</h2>
          </div>
          <span className="text-sm text-muted-foreground">{entityTypeLabel(d.entityType)}</span>
        </div>

        <div className="space-y-4 rounded-lg border bg-muted/30 p-4">
          <div className="grid gap-3 md:grid-cols-[1fr_180px]">
            <Input
              className="bg-background text-base"
              placeholder={tx('Approval Rule: e.g. Large Expense Report')}
              value={d.name}
              onChange={(e) => set({ name: e.target.value })}
            />
            <select
              className="h-9 rounded-md border border-input bg-background px-3 text-sm"
              value={d.priority}
              onChange={(e) => set({ priority: e.target.value as Priority })}
              title={tx('Priority given to requests routed by this rule')}
            >
              {PRIORITIES.map((p) => (
                <option key={p} value={p}>
                  {tx('Priority:')} {p}
                </option>
              ))}
            </select>
          </div>
          <Textarea
            rows={1}
            className="bg-background"
            placeholder={tx('Description (optional)')}
            value={d.description}
            onChange={(e) => set({ description: e.target.value })}
          />

          {/* conditions */}
          <div className="rounded-lg border bg-background">
            <div className="flex items-center justify-between gap-2 border-b px-4 py-3">
              <p className="min-w-0 text-base">
                <span className="font-semibold">{tx('If')}</span>{' '}
                {d.conditions.rules.length ? describe(d.conditions, lk) : <span className="text-muted-foreground">{tx('any document')}</span>}
              </p>
              <Button
                variant="ghost"
                size="icon"
                title={tx('Clear conditions')}
                onClick={() => set({ conditions: emptyGroup() })}
              >
                <Trash2 className="h-4 w-4" />
              </Button>
            </div>
            <div className="p-4">
              <GroupEditor group={d.conditions} meta={meta} lk={lk} onChange={(g) => set({ conditions: g })} depth={0} />
            </div>
          </div>

          {/* stages */}
          <div className="space-y-2">
            <Label className="text-sm font-semibold">{tx('Approval Stages')}</Label>
            <div className="overflow-x-auto rounded-lg border bg-muted/40 p-4">
              <div className="flex min-w-max items-stretch">
                {d.stages.map((s, i) => (
                  <div key={s.key} className="flex items-stretch">
                    <StageEditor
                      index={i}
                      stage={s}
                      count={d.stages.length}
                      meta={meta}
                      lk={lk}
                      departments={departments.data ?? []}
                      onChange={(p) => setStage(i, p)}
                      onMove={(by) => moveStage(i, by)}
                      onRemove={() => setD((x) => ({ ...x, stages: x.stages.filter((_, j) => j !== i) }))}
                    />
                    {i < d.stages.length - 1 && (
                      <div className="flex w-16 items-center">
                        <span className="h-3 w-3 shrink-0 rounded-full bg-primary" />
                        <span className="flex-1 border-t-2 border-dashed border-primary" />
                        <ChevronRight className="-ml-1 h-4 w-4 text-primary" />
                      </div>
                    )}
                  </div>
                ))}
                <div className="flex w-16 items-center">
                  <span className="h-3 w-3 shrink-0 rounded-full bg-primary/40" />
                  <span className="flex-1 border-t-2 border-dashed border-muted-foreground/30" />
                </div>
              </div>
            </div>
          </div>

          {/* advanced */}
          <div>
            <button
              type="button"
              className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
              onClick={() => setAdvanced((a) => !a)}
            >
              {advanced ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
              {tx('Scope & options')}
            </button>
            {advanced && (
              <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                <Field label={tx('Branch')}>
                  <select
                    className="h-9 w-full rounded-md border border-input bg-background px-3 text-sm"
                    value={d.branchId}
                    onChange={(e) => set({ branchId: e.target.value })}
                  >
                    <option value="">{tx('All branches')}</option>
                    {branches.data?.map((b) => (
                      <option key={b.id} value={b.id}>
                        {b.name}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label={tx('Department')}>
                  <select
                    className="h-9 w-full rounded-md border border-input bg-background px-3 text-sm"
                    value={d.departmentId}
                    onChange={(e) => set({ departmentId: e.target.value })}
                  >
                    <option value="">{tx('All departments')}</option>
                    {departments.data?.map((x) => (
                      <option key={x.id} value={x.id}>
                        {x.name}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label={tx('Min amount (legacy band)')}>
                  <Input
                    type="number"
                    className="bg-background"
                    value={d.minAmount}
                    onChange={(e) => set({ minAmount: e.target.value })}
                  />
                </Field>
                <Field label={tx('Precedence (higher wins)')}>
                  <Input
                    type="number"
                    className="bg-background"
                    value={d.precedence}
                    onChange={(e) => set({ precedence: Number(e.target.value) })}
                  />
                </Field>
                <label className="flex items-center gap-2 text-sm sm:col-span-2">
                  <input
                    type="checkbox"
                    className="h-4 w-4"
                    checked={d.enforceDistinctApprovers}
                    onChange={(e) => set({ enforceDistinctApprovers: e.target.checked })}
                  />
                  {tx('Segregation of duties (one person clears at most one stage)')}
                </label>
                <label className="flex items-center gap-2 text-sm sm:col-span-2">
                  <input
                    type="checkbox"
                    className="h-4 w-4"
                    checked={d.isActive}
                    onChange={(e) => set({ isActive: e.target.checked })}
                  />
                  {tx('Active')}
                </label>
              </div>
            )}
          </div>

          <div className="flex flex-wrap justify-end gap-2">
            <TestRule draft={d} meta={meta} />
            <Button variant="outline" onClick={() => setD((x) => ({ ...x, stages: [...x.stages, newStage()] }))}>
              <Plus className="mr-1 h-4 w-4" /> {tx('Add Approval Stage')}
            </Button>
            <Button disabled={save.isPending} onClick={() => save.mutate()}>
              <Save className="mr-1 h-4 w-4" /> {tx('Save Rule')}
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <Label className="text-xs text-muted-foreground">{label}</Label>
      {children}
    </div>
  );
}

// ---- conditions --------------------------------------------------------------------------------------------------

function GroupEditor({
  group,
  meta,
  lk,
  onChange,
  depth,
  onRemove,
}: {
  group: ConditionGroup;
  meta: BuilderMeta | undefined;
  lk: Lookups;
  onChange: (g: ConditionGroup) => void;
  depth: number;
  onRemove?: () => void;
}) {
  const setRule = (i: number, r: ConditionLeaf | ConditionGroup) =>
    onChange({ ...group, rules: group.rules.map((x, j) => (j === i ? r : x)) });
  const removeRule = (i: number) => onChange({ ...group, rules: group.rules.filter((_, j) => j !== i) });
  const firstField = meta?.fields[0]?.key ?? 'amount';

  return (
    <div className={cn('space-y-2', depth > 0 && 'rounded-lg border border-dashed p-3')}>
      {group.rules.map((r, i) => (
        <div key={i}>
          {i > 0 && (
            <div className="my-1.5 flex items-center gap-2">
              <span className="h-px flex-1 bg-border" />
              <span className="text-[11px] font-semibold uppercase text-muted-foreground">{group.logic}</span>
              <span className="h-px flex-1 bg-border" />
            </div>
          )}
          {isGroup(r) ? (
            <GroupEditor
              group={r}
              meta={meta}
              lk={lk}
              depth={depth + 1}
              onChange={(g) => setRule(i, g)}
              onRemove={() => removeRule(i)}
            />
          ) : (
            <ConditionRow
              rule={r}
              meta={meta}
              lk={lk}
              logic={group.logic}
              onLogic={(logic) => onChange({ ...group, logic })}
              onChange={(x) => setRule(i, x)}
              onRemove={() => removeRule(i)}
            />
          )}
        </div>
      ))}
      <div className="flex flex-wrap items-center gap-2 pt-1">
        <Button
          size="sm"
          onClick={() => onChange({ ...group, rules: [...group.rules, { field: firstField, operator: 'gt', value: '' }] })}
        >
          {tx('Add Condition')}
        </Button>
        {depth < 2 && (
          <Button
            size="sm"
            variant="outline"
            onClick={() =>
              onChange({
                ...group,
                rules: [
                  ...group.rules,
                  { logic: group.logic === 'and' ? 'or' : 'and', rules: [{ field: firstField, operator: 'gt', value: '' }] },
                ],
              })
            }
          >
            <ListPlus className="mr-1 h-4 w-4" /> {tx('Add Group')}
          </Button>
        )}
        {group.rules.length > 1 && <LogicToggle logic={group.logic} onChange={(logic) => onChange({ ...group, logic })} />}
        {onRemove && (
          <Button size="sm" variant="ghost" className="ml-auto text-red-600" onClick={onRemove}>
            <Trash2 className="mr-1 h-4 w-4" /> {tx('Remove group')}
          </Button>
        )}
      </div>
    </div>
  );
}

function LogicToggle({ logic, onChange }: { logic: 'and' | 'or'; onChange: (l: 'and' | 'or') => void }) {
  return (
    <div className="inline-flex overflow-hidden rounded-md border">
      {(['and', 'or'] as const).map((l) => (
        <button
          key={l}
          type="button"
          onClick={() => onChange(l)}
          className={cn(
            'px-3 py-1 text-xs font-semibold uppercase',
            logic === l ? 'bg-primary text-primary-foreground' : 'bg-background hover:bg-accent',
          )}
        >
          {l}
        </button>
      ))}
    </div>
  );
}

function ConditionRow({
  rule,
  meta,
  lk,
  logic,
  onLogic,
  onChange,
  onRemove,
}: {
  rule: ConditionLeaf;
  meta: BuilderMeta | undefined;
  lk: Lookups;
  logic: 'and' | 'or';
  onLogic: (l: 'and' | 'or') => void;
  onChange: (r: ConditionLeaf) => void;
  onRemove: () => void;
}) {
  const field = lk.fields.get(rule.field);
  const ops = opsFor(field?.type);
  const fields = meta?.fields ?? [];
  const knownField = fields.some((f) => f.key === rule.field);

  return (
    <div className="flex flex-wrap items-center gap-2 rounded-md border bg-muted/40 p-2">
      <select
        className="h-9 min-w-[10rem] flex-1 rounded-md border border-input bg-background px-2 text-sm"
        value={knownField ? rule.field : '__custom'}
        onChange={(e) => {
          const next = e.target.value === '__custom' ? '' : e.target.value;
          const nf = lk.fields.get(next);
          const nextOps = opsFor(nf?.type);
          onChange({ field: next, operator: nextOps.includes(rule.operator) ? rule.operator : nextOps[0], value: '' });
        }}
      >
        {fields.map((f) => (
          <option key={f.key} value={f.key}>
            {f.label}
          </option>
        ))}
        <option value="__custom">{tx('Custom field…')}</option>
      </select>
      {!knownField && (
        <Input
          className="h-9 w-40 bg-background"
          placeholder={tx('snapshot.path')}
          value={rule.field}
          onChange={(e) => onChange({ ...rule, field: e.target.value })}
        />
      )}
      <select
        className="h-9 w-48 rounded-md border border-input bg-background px-2 text-sm"
        value={rule.operator}
        onChange={(e) => {
          const op = e.target.value as Operator;
          const value =
            op === 'between' ? ['', ''] : op === 'in' || op === 'not_in' ? [] : Array.isArray(rule.value) ? '' : rule.value;
          onChange({ ...rule, operator: op, value });
        }}
      >
        {ops.map((o) => (
          <option key={o} value={o}>
            {OPERATOR_LABEL[o]}
          </option>
        ))}
      </select>
      <div className="min-w-[10rem] flex-[2]">
        <ValueInput rule={rule} field={field} lk={lk} onChange={(value) => onChange({ ...rule, value })} />
      </div>
      <LogicToggle logic={logic} onChange={onLogic} />
      <Button variant="ghost" size="icon" onClick={onRemove} title={tx('Remove condition')}>
        <Minus className="h-4 w-4" />
      </Button>
    </div>
  );
}

function coerce(raw: string, type: string | undefined): unknown {
  if (type === 'number' && raw.trim() !== '' && !Number.isNaN(Number(raw))) return Number(raw);
  return raw;
}

function ValueInput({
  rule,
  field,
  lk,
  onChange,
}: {
  rule: ConditionLeaf;
  field: FieldMeta | undefined;
  lk: Lookups;
  onChange: (v: unknown) => void;
}) {
  const type = field?.type;
  const options = lk.options(field);
  const inputType = type === 'number' ? 'number' : type === 'date' ? 'date' : 'text';

  if (rule.operator === 'is_empty' || rule.operator === 'is_not_empty') {
    return <div className="h-9" />;
  }
  if (rule.operator === 'between') {
    const [a, b] = Array.isArray(rule.value) ? rule.value : ['', ''];
    return (
      <div className="flex items-center gap-2">
        <Input
          type={inputType}
          className="h-9 bg-background"
          value={String(a ?? '')}
          onChange={(e) => onChange([coerce(e.target.value, type), b])}
        />
        <span className="text-xs text-muted-foreground">{tx('and')}</span>
        <Input
          type={inputType}
          className="h-9 bg-background"
          value={String(b ?? '')}
          onChange={(e) => onChange([a, coerce(e.target.value, type)])}
        />
      </div>
    );
  }
  if (rule.operator === 'in' || rule.operator === 'not_in') {
    const values = Array.isArray(rule.value) ? rule.value.map(String) : [];
    if (options.length > 0) {
      return <MultiPick options={options} values={values} onChange={onChange} />;
    }
    return (
      <Input
        className="h-9 bg-background"
        placeholder={tx('comma, separated, values')}
        value={values.join(', ')}
        onChange={(e) =>
          onChange(
            e.target.value
              .split(',')
              .map((v) => v.trim())
              .filter(Boolean)
              .map((v) => coerce(v, type)),
          )
        }
      />
    );
  }
  if (options.length > 0) {
    return (
      <select
        className="h-9 w-full rounded-md border border-input bg-background px-2 text-sm"
        value={String(rule.value ?? '')}
        onChange={(e) => onChange(e.target.value)}
      >
        <option value="">{tx('Select…')}</option>
        {options.map((o) => (
          <option key={o.id} value={o.id}>
            {o.name}
          </option>
        ))}
      </select>
    );
  }
  return (
    <Input
      type={inputType}
      className="h-9 bg-background"
      placeholder={field?.sample != null ? `${tx('e.g.')} ${String(field.sample)}` : tx('Value')}
      value={String(rule.value ?? '')}
      onChange={(e) => onChange(coerce(e.target.value, type))}
    />
  );
}

function MultiPick({
  options,
  values,
  onChange,
  placeholder,
}: {
  options: { id: string; name: string }[];
  values: string[];
  onChange: (v: string[]) => void;
  placeholder?: string;
}) {
  const [search, setSearch] = useState('');
  const shown = options.filter((o) => o.name.toLowerCase().includes(search.toLowerCase()));
  const names = values.map((v) => options.find((o) => o.id === v)?.name ?? v);
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="outline" className="h-9 w-full justify-between bg-background font-normal">
          <span className="truncate">{names.length ? names.join(', ') : placeholder ?? tx('Select…')}</span>
          <ChevronDown className="ml-2 h-4 w-4 shrink-0" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-72 p-2" align="start">
        <Input
          className="mb-2 h-8"
          placeholder={tx('Search…')}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <div className="max-h-60 space-y-0.5 overflow-y-auto">
          {shown.map((o) => {
            const on = values.includes(o.id);
            return (
              <label
                key={o.id}
                className={cn('flex cursor-pointer items-center gap-2 rounded px-2 py-1 text-sm', on ? 'bg-primary/10' : 'hover:bg-accent')}
              >
                <input
                  type="checkbox"
                  className="h-4 w-4"
                  checked={on}
                  onChange={() => onChange(on ? values.filter((v) => v !== o.id) : [...values, o.id])}
                />
                <span className="truncate">{o.name}</span>
              </label>
            );
          })}
          {shown.length === 0 && <p className="px-2 py-1 text-xs text-muted-foreground">{tx('No matches')}</p>}
        </div>
      </PopoverContent>
    </Popover>
  );
}

// ---- stage -------------------------------------------------------------------------------------------------------

function StageEditor({
  index,
  stage,
  count,
  meta,
  lk,
  departments,
  onChange,
  onMove,
  onRemove,
}: {
  index: number;
  stage: StageDraft;
  count: number;
  meta: BuilderMeta | undefined;
  lk: Lookups;
  departments: { id: string; name: string }[];
  onChange: (p: Partial<StageDraft>) => void;
  onMove: (by: number) => void;
  onRemove: () => void;
}) {
  const [more, setMore] = useState(
    stage.conditions.rules.length > 0 || !!stage.minAmount || !!stage.maxAmount || !!stage.departmentId,
  );
  const users = (meta?.users ?? []).map((u) => ({ id: u.id, name: u.name || u.email || u.id }));
  const roles = meta?.roles ?? [];
  const firstUser = meta?.users.find((u) => u.id === stage.approverUserIds[0]);

  return (
    <div className="relative w-80 shrink-0 rounded-lg border bg-background p-4 pl-6 shadow-sm">
      <span className="absolute -left-3 top-6 flex h-7 w-7 items-center justify-center rounded-full bg-primary text-sm font-semibold text-primary-foreground ring-4 ring-muted/40">
        {index + 1}
      </span>
      <div className="flex items-start gap-1">
        <Input
          className="h-8 border-none px-1 text-base font-semibold shadow-none focus-visible:ring-1"
          placeholder={tx('Stage name')}
          value={stage.name}
          onChange={(e) => onChange({ name: e.target.value })}
        />
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" className="h-8 w-8 shrink-0">
              <MoreHorizontal className="h-4 w-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuItem disabled={index === 0} onClick={() => onMove(-1)}>
              <ArrowLeft className="mr-2 h-4 w-4" /> {tx('Move earlier')}
            </DropdownMenuItem>
            <DropdownMenuItem disabled={index === count - 1} onClick={() => onMove(1)}>
              <ArrowRight className="mr-2 h-4 w-4" /> {tx('Move later')}
            </DropdownMenuItem>
            <DropdownMenuItem className="text-red-600" disabled={count === 1} onClick={onRemove}>
              <X className="mr-2 h-4 w-4" /> {tx('Remove stage')}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      <div className="mt-2 space-y-2">
        <div className="text-xs text-muted-foreground">{tx('Assigned to:')}</div>
        <select
          className="h-9 w-full rounded-md border border-input bg-background px-2 text-sm"
          value={stage.approverScope}
          onChange={(e) => onChange({ approverScope: e.target.value as ApproverScope })}
        >
          {(['department_manager', 'user', 'role', 'permission'] as ApproverScope[]).map((s) => (
            <option key={s} value={s}>
              {SCOPE_LABEL[s]}
            </option>
          ))}
        </select>

        <div className="flex items-center gap-3 rounded-md bg-muted/50 p-2">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-muted">
            {stage.approverScope === 'role' || stage.approverScope === 'permission' ? (
              <Users className="h-4 w-4 text-muted-foreground" />
            ) : (
              <User className="h-4 w-4 text-muted-foreground" />
            )}
          </span>
          <div className="min-w-0 text-sm leading-tight">
            {stage.approverScope === 'department_manager' && (
              <>
                <div>{tx('Department')}</div>
                <div className="text-muted-foreground">{tx('Dynamic Department Head')}</div>
              </>
            )}
            {stage.approverScope === 'user' && (
              <>
                <div className="truncate font-medium">
                  {firstUser ? firstUser.name : tx('Pick users')}
                  {stage.approverUserIds.length > 1 && ` +${stage.approverUserIds.length - 1}`}
                </div>
                <div className="text-muted-foreground">{tx('Named approver')}</div>
              </>
            )}
            {stage.approverScope === 'role' && (
              <>
                <div className="truncate font-medium">
                  {stage.approverRoleIds.map((id) => roles.find((r) => r.id === id)?.name ?? id).join(', ') ||
                    tx('Pick roles')}
                </div>
                <div className="text-muted-foreground">{tx('Role Title')}</div>
              </>
            )}
            {stage.approverScope === 'permission' && (
              <>
                <div className="truncate font-medium">
                  {stage.approverPermissions.length
                    ? tx('{{n}} permission(s)', { n: stage.approverPermissions.length })
                    : tx('Anyone with approval access')}
                </div>
                <div className="text-muted-foreground">{tx('Permission holders')}</div>
              </>
            )}
          </div>
        </div>

        {stage.approverScope === 'user' && (
          <MultiPick
            options={users}
            values={stage.approverUserIds}
            placeholder={tx('Select users…')}
            onChange={(v) => onChange({ approverUserIds: v })}
          />
        )}
        {stage.approverScope === 'role' && (
          <MultiPick
            options={roles}
            values={stage.approverRoleIds}
            placeholder={tx('Select roles…')}
            onChange={(v) => onChange({ approverRoleIds: v })}
          />
        )}
        {(stage.approverScope === 'permission' || stage.approverScope === 'department_manager') && (
          <PermissionPicker
            permissions={stage.approverPermissions}
            onChange={(p) => onChange({ approverPermissions: p })}
            fallback={stage.approverScope === 'department_manager'}
          />
        )}

        <div className="flex items-center justify-between gap-2">
          <Label className="text-xs text-muted-foreground">{tx('Approvals needed')}</Label>
          <Input
            type="number"
            min={1}
            className="h-8 w-20"
            value={stage.requiredCount}
            onChange={(e) => onChange({ requiredCount: Math.max(1, Number(e.target.value) || 1) })}
          />
        </div>

        <button
          type="button"
          className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
          onClick={() => setMore((m) => !m)}
        >
          {more ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
          {stage.conditions.rules.length
            ? tx('Only when: {{s}}', { s: describe(stage.conditions, lk) })
            : tx('Stage conditions')}
        </button>
        {more && (
          <div className="space-y-3 rounded-md border p-2">
            <p className="text-xs text-muted-foreground">{tx('The stage is skipped unless these hold.')}</p>
            <GroupEditor
              group={stage.conditions}
              meta={meta}
              lk={lk}
              depth={1}
              onChange={(g) => onChange({ conditions: g })}
            />
            <div className="grid grid-cols-2 gap-2">
              <Field label={tx('Band min')}>
                <Input
                  type="number"
                  className="h-8"
                  value={stage.minAmount}
                  onChange={(e) => onChange({ minAmount: e.target.value })}
                />
              </Field>
              <Field label={tx('Band max')}>
                <Input
                  type="number"
                  className="h-8"
                  value={stage.maxAmount}
                  onChange={(e) => onChange({ maxAmount: e.target.value })}
                />
              </Field>
            </div>
            <Field label={tx('Only for department')}>
              <select
                className="h-8 w-full rounded-md border border-input bg-background px-2 text-sm"
                value={stage.departmentId}
                onChange={(e) => onChange({ departmentId: e.target.value })}
              >
                <option value="">{tx('All departments')}</option>
                {departments.map((x) => (
                  <option key={x.id} value={x.id}>
                    {x.name}
                  </option>
                ))}
              </select>
            </Field>
          </div>
        )}
      </div>
    </div>
  );
}

function PermissionPicker({
  permissions,
  onChange,
  fallback,
}: {
  permissions: string[];
  onChange: (p: string[]) => void;
  fallback: boolean;
}) {
  const catalog = usePermissionCatalog();
  const options = useMemo(
    () =>
      (catalog.data?.groups ?? []).flatMap((g) =>
        g.permissions.map((p) => ({ id: p.key, name: `${g.resource} · ${p.action}` })),
      ),
    [catalog.data],
  );
  return (
    <div className="space-y-1">
      {fallback && (
        <p className="text-[11px] text-muted-foreground">
          {tx('Fallback when no department head resolves:')}
        </p>
      )}
      <MultiPick
        options={options}
        values={permissions}
        placeholder={tx('Approver permissions…')}
        onChange={onChange}
      />
    </div>
  );
}

// ---- test rule ---------------------------------------------------------------------------------------------------

function collectFields(g: ConditionGroup, out: Set<string>) {
  g.rules.forEach((r) => (isGroup(r) ? collectFields(r, out) : out.add(r.field)));
}

function TestRule({ draft, meta }: { draft: RuleDraft; meta: BuilderMeta | undefined }) {
  const lk = useLookups(meta);
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState<Record<string, string>>({ amount: '' });
  const [result, setResult] = useState<Simulation | null>(null);
  const [busy, setBusy] = useState(false);

  const used = useMemo(() => {
    const s = new Set<string>(['amount']);
    collectFields(draft.conditions, s);
    draft.stages.forEach((st) => collectFields(st.conditions, s));
    s.delete('requesterId');
    return [...s].filter(Boolean);
  }, [draft]);

  const run = async () => {
    const snapshot: Record<string, unknown> = {};
    for (const k of used) {
      const v = values[k];
      if (v == null || v === '') continue;
      const f = lk.fields.get(k);
      const val = coerce(v, f?.type);
      // dot paths become nested objects, the way documents send them
      const parts = k.split('.');
      let cur = snapshot;
      parts.slice(0, -1).forEach((p) => {
        cur[p] = (cur[p] as Record<string, unknown>) ?? {};
        cur = cur[p] as Record<string, unknown>;
      });
      cur[parts[parts.length - 1]] = val;
    }
    setBusy(true);
    try {
      setResult(await simulate(draft.entityType, snapshot));
    } catch (err: any) {
      notify.error(err?.response?.data?.message ?? tx('Failed'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline">
          <FlaskConical className="mr-1 h-4 w-4" /> {tx('Test')}
        </Button>
      </DialogTrigger>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Test the saved rules')}</DialogTitle>
        </DialogHeader>
        <p className="text-sm text-muted-foreground">
          {tx('Enter a sample document. The result uses the rules as saved, so save your changes first.')}
        </p>
        <div className="space-y-2">
          {used.map((k) => {
            const f = lk.fields.get(k);
            const options = lk.options(f);
            return (
              <div key={k} className="grid grid-cols-[140px_1fr] items-center gap-2">
                <Label className="truncate text-sm">{f?.label ?? k}</Label>
                {options.length ? (
                  <select
                    className="h-9 rounded-md border border-input bg-background px-2 text-sm"
                    value={values[k] ?? ''}
                    onChange={(e) => setValues((v) => ({ ...v, [k]: e.target.value }))}
                  >
                    <option value="">—</option>
                    {options.map((o) => (
                      <option key={o.id} value={o.id}>
                        {o.name}
                      </option>
                    ))}
                  </select>
                ) : (
                  <Input
                    type={f?.type === 'number' ? 'number' : f?.type === 'date' ? 'date' : 'text'}
                    value={values[k] ?? ''}
                    onChange={(e) => setValues((v) => ({ ...v, [k]: e.target.value }))}
                  />
                )}
              </div>
            );
          })}
        </div>
        <Button onClick={run} disabled={busy}>
          <FlaskConical className="mr-1 h-4 w-4" /> {tx('Run test')}
        </Button>
        {result && (
          <div className="space-y-2 rounded-md border p-3 text-sm">
            {result.workflow ? (
              <>
                <div className="flex items-center justify-between gap-2">
                  <span>
                    {tx('Routed by')} <span className="font-semibold">{result.workflow.name}</span>
                  </span>
                  <PriorityBadge priority={result.priority} />
                </div>
                <ol className="space-y-1">
                  {result.stages.map((s, i) => (
                    <li key={s.stepOrder} className={cn('flex items-center gap-2', !s.applies && 'text-muted-foreground line-through')}>
                      <span className="flex h-5 w-5 items-center justify-center rounded-full bg-muted text-[11px]">{i + 1}</span>
                      <span className="font-medium">{s.name}</span>
                      <span className="truncate text-xs text-muted-foreground">— {s.assignee.label}</span>
                    </li>
                  ))}
                </ol>
                {!result.needsApproval && <p className="text-emerald-600">{tx('No stage applies: auto-approved.')}</p>}
              </>
            ) : (
              <p className="text-emerald-600">{tx('No rule matches: this document is auto-approved.')}</p>
            )}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

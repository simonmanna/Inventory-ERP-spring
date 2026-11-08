import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  BadgeDollarSign,
  Building2,
  CalendarDays,
  Check,
  CheckCircle2,
  Clock,
  FileText,
  Inbox,
  Loader2,
  Package,
  Receipt,
  Search,
  Settings2,
  ShoppingCart,
  SkipForward,
  Truck,
  X,
  XCircle,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import { notify } from '@/lib/notify';
import { entityTypeLabel } from '@/lib/approval-entity-types';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import {
  type InboxCard,
  type InboxScope,
  type PersonRef,
  type Priority,
  type RequestStatus,
  type Timeline,
  type TimelineStage,
  useApprovalSummary,
  useDecide,
  useInbox,
  useTimeline,
} from '@/features/approvals/api';

// ---- presentation helpers ----------------------------------------------------------------------------------------

const PRIORITY_STYLE: Record<Priority, string> = {
  critical: 'bg-red-600 text-white',
  high: 'bg-red-100 text-red-700 dark:bg-red-950 dark:text-red-300',
  medium: 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300',
  low: 'bg-sky-100 text-sky-800 dark:bg-sky-950 dark:text-sky-300',
};

const PRIORITY_LABEL: Record<Priority, string> = {
  critical: tx('Critical'),
  high: tx('High Priority'),
  medium: tx('Medium Priority'),
  low: tx('Low Priority'),
};

const STATUS_STYLE: Record<RequestStatus, string> = {
  pending: 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300',
  approved: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300',
  rejected: 'bg-red-100 text-red-700 dark:bg-red-950 dark:text-red-300',
  cancelled: 'bg-muted text-muted-foreground',
};

const STATUS_LABEL: Record<RequestStatus, string> = {
  pending: tx('Pending'),
  approved: tx('Approved'),
  rejected: tx('Rejected'),
  cancelled: tx('Cancelled'),
};

export function PriorityBadge({ priority, className }: { priority: Priority; className?: string }) {
  return (
    <span className={cn('inline-flex rounded-full px-2 py-0.5 text-[11px] font-medium', PRIORITY_STYLE[priority], className)}>
      {PRIORITY_LABEL[priority]}
    </span>
  );
}

function StatusBadge({ status }: { status: RequestStatus }) {
  return (
    <span className={cn('inline-flex rounded-full px-2 py-0.5 text-[11px] font-medium', STATUS_STYLE[status])}>
      {STATUS_LABEL[status]}
    </span>
  );
}

function entityIcon(entityType: string) {
  if (/purchase|rfq|supplier|vendor|goods_receipt|debit/.test(entityType)) return ShoppingCart;
  if (/expense|claim|bill|payment|credit_note|invoice/.test(entityType)) return Receipt;
  if (/leave|time|attendance|payroll|hr_/.test(entityType)) return CalendarDays;
  if (/asset|depreciation/.test(entityType)) return Building2;
  if (/sales|credit_override|pos_|rental/.test(entityType)) return BadgeDollarSign;
  if (/stock|inventory|waste|production|bom/.test(entityType)) return Package;
  if (/fleet|weighbridge|quarry/.test(entityType)) return Truck;
  return FileText;
}

const AVATAR_TONES = [
  'bg-sky-100 text-sky-800',
  'bg-violet-100 text-violet-800',
  'bg-emerald-100 text-emerald-800',
  'bg-amber-100 text-amber-800',
  'bg-rose-100 text-rose-800',
  'bg-teal-100 text-teal-800',
];

function initials(name: string | undefined | null) {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase() || '?';
}

export function Avatar({ person, size = 'md' }: { person: Pick<PersonRef, 'id' | 'name'> | null; size?: 'sm' | 'md' }) {
  const tone = AVATAR_TONES[(person?.id ?? '').split('').reduce((a, c) => a + c.charCodeAt(0), 0) % AVATAR_TONES.length];
  return (
    <span
      title={person?.name}
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-full font-semibold ring-2 ring-background',
        size === 'sm' ? 'h-6 w-6 text-[10px]' : 'h-8 w-8 text-xs',
        tone,
      )}
    >
      {initials(person?.name)}
    </span>
  );
}

function money(amount: number | null, currency: string | null) {
  if (amount == null) return null;
  try {
    if (currency) {
      return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount);
    }
  } catch {
    // Unknown currency code: fall through to a plain number.
  }
  return new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount);
}

const shortDate = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : '';

function display(value: unknown) {
  if (value == null) return '—';
  if (typeof value === 'number') return new Intl.NumberFormat().format(value);
  if (typeof value === 'boolean') return value ? tx('Yes') : tx('No');
  return String(value);
}

// ---- page --------------------------------------------------------------------------------------------------------

const SCOPES: { key: InboxScope; label: string }[] = [
  { key: 'mine', label: tx('Awaiting me') },
  { key: 'requested', label: tx('My requests') },
  { key: 'all', label: tx('All') },
];

export function ApprovalsPage() {
  const [params, setParams] = useSearchParams();
  const [scope, setScope] = useState<InboxScope>((params.get('scope') as InboxScope) || 'mine');
  const [status, setStatus] = useState<RequestStatus>('pending');
  const [priority, setPriority] = useState<Priority | ''>('');
  const [search, setSearch] = useState('');
  const [q, setQ] = useState('');
  const selected = params.get('request');

  useEffect(() => {
    const t = setTimeout(() => setQ(search), 250);
    return () => clearTimeout(t);
  }, [search]);

  const inbox = useInbox({ scope, status, priority, q });
  const summary = useApprovalSummary();

  const select = (id: string | null) => {
    const next = new URLSearchParams(params);
    if (id) next.set('request', id);
    else next.delete('request');
    setParams(next, { replace: true });
  };

  // Open the first card when nothing is selected (or the selection left the list after a decision).
  useEffect(() => {
    const items = inbox.data;
    if (!items || items.length === 0) return;
    if (!selected) select(items[0].id);
  }, [inbox.data]); // eslint-disable-line react-hooks/exhaustive-deps

  const cleared = summary.data
    ? Math.round(
        (summary.data.decidedToday / Math.max(1, summary.data.decidedToday + summary.data.awaitingMe)) * 100,
      )
    : 0;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Approval Hub')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Everything waiting on a decision, with every stage of its approval route.')}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {summary.data && (
            <>
              <SummaryChip label={tx('Awaiting me')} value={summary.data.awaitingMe} />
              <SummaryChip label={tx('Urgent')} value={summary.data.awaitingMeUrgent} tone="danger" />
              <SummaryChip label={tx('Decided today')} value={summary.data.decidedToday} tone="success" />
            </>
          )}
          <Button variant="outline" size="sm" asChild>
            <Link to="/approval-workflows">
              <Settings2 className="mr-1 h-4 w-4" />
              {tx('Configure workflows')}
            </Link>
          </Button>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(300px,380px)_1fr]">
        {/* ---- list ---- */}
        <Card className="flex min-h-0 flex-col lg:max-h-[calc(100vh-11rem)]">
          <div className="space-y-3 border-b p-3">
            <div className="flex items-center justify-between">
              <span className="font-semibold">
                {scope === 'mine' ? tx('Pending Approvals') : scope === 'requested' ? tx('My Requests') : tx('All Requests')}
              </span>
              {scope === 'mine' && summary.data && (
                <span className="text-xs text-muted-foreground">{tx('{{pct}}% cleared today', { pct: cleared })}</span>
              )}
            </div>
            {scope === 'mine' && (
              <div className="h-1.5 overflow-hidden rounded-full bg-muted">
                <div className="h-full rounded-full bg-primary transition-all" style={{ width: `${cleared}%` }} />
              </div>
            )}
            <div className="grid grid-cols-3 gap-1 rounded-md bg-muted p-1">
              {SCOPES.map((s) => (
                <button
                  key={s.key}
                  type="button"
                  onClick={() => {
                    setScope(s.key);
                    select(null);
                  }}
                  className={cn(
                    'rounded px-2 py-1 text-xs font-medium transition-colors',
                    scope === s.key ? 'bg-background shadow-sm' : 'text-muted-foreground hover:text-foreground',
                  )}
                >
                  {s.label}
                </button>
              ))}
            </div>
            <div className="relative">
              <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
              <Input
                className="pl-8"
                placeholder={tx('Search reference, requester, vendor…')}
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </div>
            <div className="flex gap-2">
              <select
                className="h-8 flex-1 rounded-md border border-input bg-transparent px-2 text-xs"
                value={priority}
                onChange={(e) => setPriority(e.target.value as Priority | '')}
              >
                <option value="">{tx('Any priority')}</option>
                <option value="critical">{tx('Critical')}</option>
                <option value="high">{tx('High')}</option>
                <option value="medium">{tx('Medium')}</option>
                <option value="low">{tx('Low')}</option>
              </select>
              {scope !== 'mine' && (
                <select
                  className="h-8 flex-1 rounded-md border border-input bg-transparent px-2 text-xs"
                  value={status}
                  onChange={(e) => setStatus(e.target.value as RequestStatus)}
                >
                  {(['pending', 'approved', 'rejected', 'cancelled'] as const).map((s) => (
                    <option key={s} value={s}>
                      {STATUS_LABEL[s]}
                    </option>
                  ))}
                </select>
              )}
            </div>
          </div>
          <div className="min-h-0 flex-1 space-y-2 overflow-y-auto p-3">
            {inbox.isLoading && [0, 1, 2].map((i) => <Skeleton key={i} className="h-24 w-full" />)}
            {inbox.isError && (
              <p className="p-4 text-center text-sm text-muted-foreground">
                {tx('You cannot view this list.')}
              </p>
            )}
            {inbox.data?.length === 0 && (
              <div className="flex flex-col items-center gap-2 p-8 text-center text-sm text-muted-foreground">
                <Inbox className="h-8 w-8" />
                {scope === 'mine' ? tx('Nothing is waiting on you.') : tx('No requests match.')}
              </div>
            )}
            {inbox.data?.map((c) => (
              <InboxItem key={c.id} card={c} active={c.id === selected} onClick={() => select(c.id)} />
            ))}
          </div>
        </Card>

        {/* ---- detail ---- */}
        {selected ? (
          <RequestDetail id={selected} onDecided={() => select(null)} />
        ) : (
          <Card className="flex items-center justify-center p-12 text-sm text-muted-foreground">
            {tx('Select a request to review it.')}
          </Card>
        )}
      </div>
    </div>
  );
}

function SummaryChip({ label, value, tone }: { label: string; value: number; tone?: 'danger' | 'success' }) {
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border bg-card px-3 py-1 text-xs">
      <span
        className={cn(
          'font-semibold',
          tone === 'danger' && value > 0 && 'text-red-600',
          tone === 'success' && 'text-emerald-600',
        )}
      >
        {value}
      </span>
      <span className="text-muted-foreground">{label}</span>
    </span>
  );
}

function InboxItem({ card, active, onClick }: { card: InboxCard; active: boolean; onClick: () => void }) {
  const Icon = entityIcon(card.entityType);
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'w-full rounded-lg border p-3 text-left transition-colors',
        active ? 'border-primary bg-primary text-primary-foreground shadow' : 'bg-card hover:bg-accent',
      )}
    >
      <div className="flex items-start gap-3">
        <span
          className={cn(
            'flex h-9 w-9 shrink-0 items-center justify-center rounded-full',
            active ? 'bg-primary-foreground/20' : 'bg-muted',
          )}
        >
          <Icon className="h-4 w-4" />
        </span>
        <div className="min-w-0 flex-1">
          <div className="truncate font-semibold">
            {card.reference}
            {card.title && <span className="font-normal"> — {card.title}</span>}
          </div>
          <div className={cn('text-xs', active ? 'text-primary-foreground/80' : 'text-muted-foreground')}>
            {entityTypeLabel(card.entityType)}
          </div>
        </div>
        <PriorityBadge priority={card.priority} className="shrink-0" />
      </div>
      <div
        className={cn(
          'mt-2 flex items-center justify-between gap-2 text-xs',
          active ? 'text-primary-foreground/90' : 'text-muted-foreground',
        )}
      >
        <span className="inline-flex min-w-0 items-center gap-1 truncate">
          <Clock className="h-3 w-3 shrink-0" />
          {card.status === 'pending'
            ? tx('Stage {{index}}/{{total}}', { index: card.stage.index || 1, total: card.stage.total || 1 }) +
              (card.stage.name ? ` · ${card.stage.name}` : '')
            : STATUS_LABEL[card.status]}
        </span>
        <span className="shrink-0">{shortDate(card.createdAt)}</span>
      </div>
      <div className="mt-1 flex items-center justify-between gap-2 text-xs">
        <span className={cn('truncate', active ? 'text-primary-foreground/90' : 'text-muted-foreground')}>
          {tx('Requester:')} <span className="font-medium">{card.requester?.name ?? '—'}</span>
        </span>
        {card.amount != null && <span className="shrink-0 font-semibold">{money(card.amount, card.currency)}</span>}
      </div>
    </button>
  );
}

// ---- detail ------------------------------------------------------------------------------------------------------

function RequestDetail({ id, onDecided }: { id: string; onDecided: () => void }) {
  const timeline = useTimeline(id);
  const decide = useDecide();
  const [comment, setComment] = useState('');

  useEffect(() => setComment(''), [id]);

  if (timeline.isLoading) {
    return (
      <Card className="space-y-4 p-6">
        <Skeleton className="h-8 w-2/3" />
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-40 w-full" />
      </Card>
    );
  }
  if (timeline.isError || !timeline.data) {
    return (
      <Card className="flex items-center justify-center p-12 text-sm text-muted-foreground">
        {tx('This request could not be loaded.')}
      </Card>
    );
  }
  const t = timeline.data;
  const r = t.request;
  const route = t.stages.filter((s) => s.state !== 'skipped');
  const done = route.filter((s) => s.state === 'approved').length;
  const progress = Math.round((done / Math.max(1, route.length)) * 100);

  const submit = (status: 'approved' | 'rejected') => {
    if (status === 'rejected' && !comment.trim()) {
      notify.error(tx('Add a comment explaining the rejection'));
      return;
    }
    decide.mutate(
      { id, status, comment },
      {
        onSuccess: () => {
          notify.success(status === 'approved' ? tx('Approved') : tx('Rejected'));
          setComment('');
          onDecided();
        },
        onError: (err: any) => notify.error(err?.response?.data?.message ?? tx('Failed')),
      },
    );
  };

  return (
    <Card className="flex min-w-0 flex-col">
      {/* header */}
      <div className="space-y-4 border-b p-5">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="text-lg font-semibold">
              {entityTypeLabel(r.entityType)} {tx('Approval:')} {r.reference}
            </h2>
            <PriorityBadge priority={r.priority} />
            <StatusBadge status={r.status} />
          </div>
          <span className="text-sm font-medium text-muted-foreground">{progress}%</span>
        </div>
        <div className="grid grid-cols-2 gap-4 text-sm md:grid-cols-4">
          <Meta label={tx('Requested By')}>
            <span className="inline-flex items-center gap-2">
              <Avatar person={r.requester} size="sm" />
              {r.requester?.name ?? '—'}
            </span>
          </Meta>
          <Meta label={tx('Amount')}>
            <span className="font-semibold">{money(r.amount, r.currency) ?? '—'}</span>
          </Meta>
          <Meta label={r.title ? tx('Party / Subject') : tx('Rule')}>{r.title ?? t.workflow?.name ?? '—'}</Meta>
          <Meta label={tx('Submitted')}>{new Date(r.createdAt).toLocaleDateString()}</Meta>
        </div>
      </div>

      {/* stepper */}
      <div className="border-b p-5">
        <Stepper timeline={t} />
        {t.stages.some((s) => s.state === 'skipped') && (
          <p className="mt-3 inline-flex items-center gap-1 text-xs text-muted-foreground">
            <SkipForward className="h-3 w-3" />
            {tx('Skipped by rule conditions:')}{' '}
            {t.stages
              .filter((s) => s.state === 'skipped')
              .map((s) => s.name)
              .join(', ')}
          </p>
        )}
      </div>

      {/* details */}
      <div className="space-y-5 p-5">
        <section className="space-y-2">
          <h3 className="font-semibold">{tx('Approval Details')}</h3>
          {t.details.length === 0 ? (
            <p className="text-sm text-muted-foreground">{tx('No details were captured for this request.')}</p>
          ) : (
            <div className="grid overflow-hidden rounded-lg border sm:grid-cols-2 xl:grid-cols-4">
              {t.details.map((d) => (
                <div key={d.key} className="border-b border-r last:border-r-0">
                  <div className="bg-muted/60 px-3 py-2 text-xs font-medium text-muted-foreground">{d.label}</div>
                  <div className="break-words px-3 py-2 text-sm">{display(d.value)}</div>
                </div>
              ))}
            </div>
          )}
        </section>

        {t.stages.some((s) => s.decisions.length > 0) && (
          <section className="space-y-2">
            <h3 className="font-semibold">{tx('Activity')}</h3>
            <ol className="space-y-2">
              {t.stages.flatMap((s) =>
                s.decisions.map((d, i) => (
                  <li key={`${s.stepOrder}-${i}`} className="flex items-start gap-3 text-sm">
                    <Avatar person={d.approver} size="sm" />
                    <div className="min-w-0">
                      <div>
                        <span className="font-medium">{d.approver.name}</span>{' '}
                        <span className={d.status === 'approved' ? 'text-emerald-600' : 'text-red-600'}>
                          {d.status === 'approved' ? tx('approved') : tx('rejected')}
                        </span>{' '}
                        <span className="text-muted-foreground">
                          {s.name} · {new Date(d.decidedAt).toLocaleString()}
                        </span>
                      </div>
                      {d.comment && <p className="text-muted-foreground">“{d.comment}”</p>}
                    </div>
                  </li>
                )),
              )}
            </ol>
          </section>
        )}
      </div>

      {/* actions */}
      <div className="mt-auto border-t p-5">
        {t.canDecide ? (
          <div className="space-y-3">
            <Textarea
              rows={2}
              placeholder={tx('Comment (required to reject)')}
              value={comment}
              onChange={(e) => setComment(e.target.value)}
            />
            <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
              <Button
                size="lg"
                className="bg-emerald-600 text-white hover:bg-emerald-700 sm:w-44"
                disabled={decide.isPending}
                onClick={() => submit('approved')}
              >
                {decide.isPending ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Check className="mr-2 h-5 w-5" />}
                {tx('Approve')}
              </Button>
              <Button
                size="lg"
                variant="destructive"
                className="sm:w-44"
                disabled={decide.isPending}
                onClick={() => submit('rejected')}
              >
                <X className="mr-2 h-5 w-5" />
                {tx('Reject')}
              </Button>
            </div>
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">
            {r.status === 'pending'
              ? tx('Waiting on {{who}}.', {
                  who: t.stages.find((s) => s.state === 'current')?.assignee.label ?? tx('the next approver'),
                })
              : tx('This request is {{status}}.', { status: STATUS_LABEL[r.status].toLowerCase() })}
          </p>
        )}
      </div>
    </Card>
  );
}

function Meta({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="truncate">{children}</div>
    </div>
  );
}

function Stepper({ timeline }: { timeline: Timeline }) {
  const r = timeline.request;
  const stages = useMemo(() => timeline.stages.filter((s) => s.state !== 'skipped'), [timeline.stages]);
  return (
    <div className="overflow-x-auto pb-1">
      <ol className="flex min-w-max gap-3">
        <StepCard
          index={1}
          title={tx('Initiated')}
          state="approved"
          people={r.requester ? [r.requester] : []}
          caption={shortDate(r.createdAt)}
        />
        {stages.map((s, i) => (
          <StepCard
            key={s.stepOrder}
            index={i + 2}
            title={s.name}
            state={s.state}
            people={peopleFor(s)}
            caption={captionFor(s)}
            hint={s.assignee.kind === 'permission' || s.assignee.kind === 'role' ? s.assignee.label : undefined}
          />
        ))}
      </ol>
    </div>
  );
}

function peopleFor(s: TimelineStage): PersonRef[] {
  if (s.decisions.length > 0) return s.decisions.map((d) => d.approver);
  return s.assignee.users.slice(0, 3);
}

function captionFor(s: TimelineStage): string {
  const last = s.decisions[s.decisions.length - 1];
  switch (s.state) {
    case 'approved':
      return last ? `${tx('Approved')} ${shortDate(last.decidedAt)}` : tx('Approved');
    case 'rejected':
      return last ? `${tx('Rejected')} ${shortDate(last.decidedAt)}` : tx('Rejected');
    case 'current':
      return s.requiredCount > 1
        ? tx('Pending · {{n}} of {{m}}', { n: s.approvals, m: s.requiredCount })
        : tx('Pending');
    case 'cancelled':
      return tx('Cancelled');
    case 'not_reached':
      return tx('Not reached');
    default:
      return tx('Waiting');
  }
}

function StepCard({
  index,
  title,
  state,
  people,
  caption,
  hint,
}: {
  index: number;
  title: string;
  state: TimelineStage['state'];
  people: PersonRef[];
  caption: string;
  hint?: string;
}) {
  const current = state === 'current';
  const bar =
    state === 'approved'
      ? 'bg-primary'
      : state === 'rejected'
        ? 'bg-red-500'
        : current
          ? 'bg-primary/40'
          : 'bg-muted';
  return (
    <li className="w-48 shrink-0">
      <div
        className={cn(
          'flex items-center gap-2 rounded-md px-2 py-1.5',
          current && 'bg-primary text-primary-foreground shadow',
        )}
      >
        <span
          className={cn(
            'flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-semibold',
            state === 'approved' && 'bg-primary text-primary-foreground',
            state === 'rejected' && 'bg-red-500 text-white',
            current && 'bg-primary-foreground/20',
            !['approved', 'rejected', 'current'].includes(state) && 'bg-muted text-muted-foreground',
          )}
        >
          {state === 'approved' ? (
            <CheckCircle2 className="h-4 w-4" />
          ) : state === 'rejected' ? (
            <XCircle className="h-4 w-4" />
          ) : (
            index
          )}
        </span>
        <div className="min-w-0 flex-1">
          {current && <div className="text-[10px] uppercase tracking-wide opacity-80">{tx('Current stage')}</div>}
          <div className="truncate text-sm font-medium">{title}</div>
        </div>
        {current && <Loader2 className="h-4 w-4 shrink-0 animate-spin opacity-80" />}
      </div>
      <div className={cn('mt-2 h-1 rounded-full', bar)} />
      <div className="mt-2 flex items-center gap-2">
        {people.length > 0 ? (
          <span className="flex -space-x-2">
            {people.map((p) => (
              <Avatar key={p.id} person={p} />
            ))}
          </span>
        ) : null}
        <div className="min-w-0 text-sm">
          <div className="truncate">
            {people.length === 1 ? people[0].name : people.length > 1 ? `${people.length} ${tx('people')}` : hint ?? '—'}
          </div>
          <div
            className={cn(
              'text-xs',
              current ? 'text-amber-600' : state === 'rejected' ? 'text-red-600' : 'text-muted-foreground',
            )}
          >
            {caption}
          </div>
        </div>
      </div>
    </li>
  );
}

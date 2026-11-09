import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import {
  Activity, AlertTriangle, ArrowLeft, ArrowRight, Ban, CalendarClock, Check, CheckCircle2, Clock, Coins, ExternalLink,
  FileText, GitCompare, Inbox, MessageSquare, Minus, Paperclip, Pencil, Plus, Rocket, Save, ShieldCheck, Trash2, XCircle,
} from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { StatusPill } from '@/components/list/list-page';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { useWorkCenters } from '@/features/manufacturing/api';
import { useProductOptions } from '@/features/manufacturing/options';
import {
  num, useApplyChange, type ChangeRow, type DispositionPlan, type DispositionResult, useChangeImpact, useCommentChange, useDecideChange, useLinkChangeDocuments, useMoveChangeStage,
  usePlmChange, usePlmDocuments, useSaveRoutingOperations, type ChangeDetail, type ChangeImpact, type PlmDocument,
  type RoutingOperation,
} from '@/features/manufacturing/api-plm';
import { money, useOrgCurrency } from '@/lib/format';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import {
  Avatar, ChangeStatusPill, ChangeTypeBadge, CheckList, DocStatusPill, EmptyState, FileLink, LifecyclePill,
  LifecycleStepper, Panel, PriorityPill, ProductLabel, RequestStatusPill, RevisionBadge, TabBar,
} from './plm-ui';
import { CancelChangeDialog, EditChangeDialog } from './change-dialogs';
import { DocumentDialog } from './document-dialogs';
import {
  DOC_TYPES, REASONS, STOCK_DISPOSITION, WIP_DISPOSITION, ago, day, dayTime, human, qty, show,
} from './vocab';

type Tab = 'changes' | 'impact' | 'documents' | 'activity';
type ProductMap = Map<string, { code: string; name: string }>;

/** One engineering change order: stages, approvals, what changes, impact, documents and activity. */
export function PlmChangeDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) || 'changes';
  const setTab = (t: Tab) => {
    const next = new URLSearchParams(params);
    if (t === 'changes') next.delete('tab'); else next.set('tab', t);
    setParams(next, { replace: true });
  };
  const has = useAuthStore((s) => s.hasPermission);
  const canChange = has(PERMISSIONS.plm.change);
  const canApply = has(PERMISSIONS.plm.apply);
  const { data: eco, isLoading, error } = usePlmChange(id);
  const { data: products = [] } = useProductOptions();
  const productMap: ProductMap = useMemo(() => new Map(products.map((p) => [p.id, { code: p.code, name: p.name }])), [products]);
  const move = useMoveChangeStage();
  const apply = useApplyChange();
  const [editing, setEditing] = useState(false);
  const [cancelling, setCancelling] = useState(false);

  if (error) {
    return (
      <div className="p-10 text-center text-muted-foreground">
        {tx('This change order does not exist.')}{' '}
        <Link to="/manufacturing/plm/changes" className="text-primary hover:underline">{tx('Back to change orders')}</Link>
      </div>
    );
  }
  if (isLoading || !eco) return <div className="space-y-4"><Skeleton className="h-28 w-full" /><Skeleton className="h-96 w-full" /></div>;

  const open = eco.status === 'draft' || eco.status === 'approved';
  const draft = eco.status === 'draft';
  const at = eco.stages.findIndex((s) => s.id === eco.stage?.id);
  const moveTo = (stageId: string, name: string) => {
    if (!confirm(tx('Move {{n}} to {{s}}?', { n: eco.number, s: name }))) return;
    move.mutate({ id: eco.id, stageId }, { onSuccess: () => notify.success(tx('Moved to {{s}}', { s: name })) });
  };
  const doApply = () => {
    if (!confirm(tx('Apply {{n}} now? This activates the new version and bumps the product revision.', { n: eco.number }))) return;
    apply.mutate(eco.id, { onSuccess: (row) => applied(row) });
  };
  const forceApply = () => {
    if (!confirm(tx('Switch {{n}} over now? Remaining stock of the replaced components is left as it is.', { n: eco.number }))) return;
    apply.mutate({ id: eco.id, force: true }, { onSuccess: (row) => applied(row) });
  };
  const applied = (row: ChangeRow) => {
    if (row.status === 'applied') notify.success(tx('{{n}} applied', { n: eco.number }));
    else notify.info(tx('{{n}} queued', { n: eco.number }), row.pending ?? undefined);
  };
  // force overrides a use-up wait only, never the effective date (the backend reports the date first)
  const waitsForStock = eco.status === 'approved' && eco.type === 'bom' && !!eco.pending?.includes('used up');

  const tabs = [
    { id: 'changes' as const, label: tx('Changes'), icon: GitCompare },
    { id: 'impact' as const, label: tx('Impact'), icon: AlertTriangle },
    { id: 'documents' as const, label: tx('Documents'), icon: FileText, count: eco.documents.length },
    { id: 'activity' as const, label: tx('Activity'), icon: Activity, count: eco.activity.length },
  ];

  return (
    <div className="space-y-4">
      {/* header */}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          <Link to="/manufacturing/plm/changes" className="mt-1.5 rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground" aria-label={tx('Back to change orders')}>
            <ArrowLeft className="h-4 w-4" />
          </Link>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <span className="font-mono">{eco.number}</span>
              <ChangeTypeBadge type={eco.type} />
              <ChangeStatusPill status={eco.status} />
              <PriorityPill priority={eco.priority} />
            </div>
            <h1 className="text-2xl font-bold tracking-tight">{eco.title}</h1>
            <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
              {eco.product && (
                eco.plmItem
                  ? <Link to={`/manufacturing/plm/products/${eco.plmItem.id}`} className="text-primary hover:underline"><ProductLabel product={eco.product} /></Link>
                  : <ProductLabel product={eco.product} />
              )}
              {eco.plmItem && <span className="flex items-center gap-1"><LifecyclePill state={eco.plmItem.lifecycleState} /><RevisionBadge revision={eco.plmItem.revision} /></span>}
              {eco.ownerName && <span className="flex items-center gap-1.5"><Avatar name={eco.ownerName} className="h-5 w-5" />{eco.ownerName}</span>}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {canChange && draft && <Button variant="outline" size="sm" onClick={() => setEditing(true)}><Pencil className="mr-1.5 h-4 w-4" />{tx('Edit')}</Button>}
          {canApply && open && <Button variant="outline" size="sm" className="text-rose-700 dark:text-rose-300" onClick={() => setCancelling(true)}><Ban className="mr-1.5 h-4 w-4" />{tx('Cancel change')}</Button>}
          {canApply && waitsForStock && <Button variant="outline" size="sm" onClick={forceApply} disabled={apply.isPending}><Rocket className="mr-1.5 h-4 w-4" />{tx('Switch over now')}</Button>}
          {canApply && eco.status === 'approved' && !eco.applyQueued && <Button size="sm" onClick={doApply} disabled={apply.isPending}><Rocket className="mr-1.5 h-4 w-4" />{tx('Apply')}</Button>}
        </div>
      </div>

      {eco.status === 'applied' && (
        <div className="flex items-center gap-2 rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-2 text-sm text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-300">
          <CheckCircle2 className="h-4 w-4" />{tx('Applied on {{d}}', { d: dayTime(eco.appliedAt) })}{eco.newRevision && <> · {tx('revision')} <b>{eco.newRevision}</b></>}
        </div>
      )}
      {eco.status === 'approved' && (eco.applyQueued || eco.pending) && (
        <div className="flex items-start gap-2 rounded-lg border border-amber-300 bg-amber-50 px-4 py-2 text-sm text-amber-900 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200">
          <Clock className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            {eco.applyQueued ? <b>{tx('Queued for switch-over')}</b> : <b>{tx('Not ready to switch over')}</b>}
            {eco.pending && <>: {eco.pending}</>}
            {eco.applyQueued && <> {tx('It is applied automatically (checked every hour) once this clears.')}</>}
          </span>
        </div>
      )}
      {eco.status === 'applied' && eco.dispositionResult && <DispositionResultPanel result={eco.dispositionResult} />}
      {eco.status === 'rejected' && (
        <div className="flex items-center gap-2 rounded-lg border border-rose-200 bg-rose-50 px-4 py-2 text-sm text-rose-800 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-300">
          <XCircle className="h-4 w-4" />{tx('Cancelled')}{eco.rejectedReason && <>: {eco.rejectedReason}</>}
        </div>
      )}

      {/* stage bar */}
      <div className="flex overflow-x-auto rounded-xl border bg-card p-1 shadow-sm">
        {eco.stages.map((s, i) => {
          const current = i === at;
          const done = i < at || eco.status === 'applied';
          const clickable = canChange && open && !current;
          return (
            <button
              key={s.id}
              type="button"
              disabled={!clickable || move.isPending}
              onClick={() => moveTo(s.id, s.name)}
              title={s.description ?? undefined}
              className={cn('relative flex min-w-[130px] flex-1 items-center justify-center gap-1.5 px-4 py-2 text-sm transition',
                i === 0 ? 'rounded-l-lg' : '', i === eco.stages.length - 1 ? 'rounded-r-lg' : '',
                current && open ? 'bg-primary font-semibold text-primary-foreground' : done ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-300' : 'text-muted-foreground',
                clickable && 'hover:bg-muted hover:text-foreground')}
            >
              {done && !current ? <Check className="h-3.5 w-3.5" /> : s.requiresApproval ? <ShieldCheck className="h-3.5 w-3.5" /> : null}
              {s.name}
              {i < eco.stages.length - 1 && <ArrowRight className="absolute -right-2 z-10 h-4 w-4 text-muted-foreground/50" />}
            </button>
          );
        })}
      </div>

      <div className="grid gap-4 xl:grid-cols-[1fr_360px]">
        <div className="min-w-0 space-y-4">
          <TabBar tabs={tabs} value={tab} onChange={setTab} />
          {tab === 'changes' && <ChangesTab eco={eco} productMap={productMap} canEdit={canChange && draft} />}
          {tab === 'impact' && <ImpactTab eco={eco} />}
          {tab === 'documents' && <DocumentsTab eco={eco} canEdit={canChange && draft} />}
          {tab === 'activity' && <ActivityTab eco={eco} />}
        </div>
        <div className="space-y-4">
          <ApprovalsPanel eco={eco} />
          <Panel title={tx('Details')} icon={CalendarClock}>
            <dl className="grid grid-cols-[120px_1fr] gap-x-3 gap-y-2 text-sm">
              <dt className="text-muted-foreground">{tx('Effective')}</dt>
              <dd>{eco.effectivity === 'date' ? day(eco.effectiveDate ? String(eco.effectiveDate) : null) : tx('As soon as applied')}</dd>
              <dt className="text-muted-foreground">{tx('Stock')}</dt>
              <dd>{STOCK_DISPOSITION[eco.stockDisposition] ?? eco.stockDisposition}</dd>
              <dt className="text-muted-foreground">{tx('Work in progress')}</dt>
              <dd>{WIP_DISPOSITION[eco.wipDisposition] ?? eco.wipDisposition}</dd>
              <dt className="text-muted-foreground">{tx('New revision')}</dt>
              <dd>{eco.newRevision ? <RevisionBadge revision={eco.newRevision} /> : <span className="text-muted-foreground">{tx('Next (automatic)')}</span>}</dd>
              <dt className="text-muted-foreground">{tx('Created')}</dt>
              <dd>{dayTime(eco.createdAt)}{eco.createdByName && <span className="text-muted-foreground"> · {eco.createdByName}</span>}</dd>
              {eco.approvedAt && <><dt className="text-muted-foreground">{tx('Approved')}</dt><dd>{dayTime(eco.approvedAt)}{eco.approvedByName && <span className="text-muted-foreground"> · {eco.approvedByName}</span>}</dd></>}
              {eco.reason && <><dt className="text-muted-foreground">{tx('Reason')}</dt><dd className="whitespace-pre-wrap">{eco.reason}</dd></>}
            </dl>
            {eco.description && <p className="mt-3 whitespace-pre-wrap border-t pt-3 text-sm">{eco.description}</p>}
          </Panel>
          {eco.request && (
            <Panel title={tx('From change request')} icon={Inbox}>
              <div className="space-y-1 text-sm">
                <div className="flex items-center gap-2">
                  <Link to={`/manufacturing/plm/requests?q=${encodeURIComponent(eco.request.number)}`} className="font-mono text-xs text-primary hover:underline">{eco.request.number}</Link>
                  <RequestStatusPill status={eco.request.status} />
                </div>
                <p className="font-medium">{eco.request.title}</p>
                <p className="text-xs text-muted-foreground">
                  {REASONS[eco.request.reasonCode] ?? eco.request.reasonCode}
                  {eco.request.dueDate && <> · {tx('needed by')} {day(String(eco.request.dueDate))}</>}
                </p>
                {eco.request.description && <p className="line-clamp-4 whitespace-pre-wrap text-muted-foreground">{eco.request.description}</p>}
              </div>
            </Panel>
          )}
        </div>
      </div>

      <EditChangeDialog eco={eco} open={editing} onClose={() => setEditing(false)} />
      <CancelChangeDialog eco={eco} open={cancelling} onClose={() => setCancelling(false)} />
    </div>
  );
}

// ---- approvals -------------------------------------------------------------------------------------------------------

function ApprovalsPanel({ eco }: { eco: ChangeDetail }) {
  const canApprove = useAuthStore((s) => s.hasPermission)(PERMISSIONS.plm.approve);
  const decide = useDecideChange();
  const [comment, setComment] = useState('');
  const a = eco.approval;
  const submit = (decision: 'approved' | 'rejected') => {
    if (decision === 'rejected' && !comment.trim() && !confirm(tx('Reject without a comment?'))) return;
    decide.mutate({ id: eco.id, decision, comment: comment.trim() || undefined }, {
      onSuccess: (d) => {
        setComment('');
        notify.success(decision === 'approved'
          ? (d.stage?.id !== eco.stage?.id ? tx('Approved; moved to {{s}}', { s: d.stage?.name ?? '' }) : tx('Approval recorded'))
          : tx('Rejection recorded'));
      },
    });
  };

  return (
    <Panel title={tx('Approval · {{s}}', { s: eco.stage?.name ?? '' })} icon={ShieldCheck}>
      {!a?.requiresApproval ? (
        <p className="text-sm text-muted-foreground">
          {eco.status === 'draft' ? tx('This stage needs no approval. Move the change forward when it is ready.')
            : eco.status === 'approved' ? tx('Approved and ready to apply.') : tx('No approval pending.')}
        </p>
      ) : (
        <div className="space-y-3">
          {a.approvers.length === 0 ? (
            <p className="text-sm text-muted-foreground">{tx('No named approvers: anyone with the PLM approve permission can sign off.')}</p>
          ) : (
            <ul className="space-y-2">
              {a.approvers.map((p) => (
                <li key={p.userId} className="flex items-start gap-2 text-sm">
                  <Avatar name={p.userName} />
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="font-medium">{p.userName ?? '?'}</span>
                      {p.kind && <span className={cn('rounded px-1 text-[10px] uppercase', p.kind === 'required' ? 'bg-amber-100 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300' : 'bg-muted text-muted-foreground')}>{p.kind === 'required' ? tx('required') : tx('optional')}</span>}
                    </div>
                    {p.comment && <p className="text-xs text-muted-foreground">“{p.comment}”</p>}
                  </div>
                  {p.decision === 'approved' ? <CheckCircle2 className="h-4 w-4 text-emerald-600" aria-label={tx('Approved')} />
                    : p.decision === 'rejected' ? <XCircle className="h-4 w-4 text-rose-600" aria-label={tx('Rejected')} />
                      : <Clock className="h-4 w-4 text-muted-foreground" aria-label={tx('Pending')} />}
                </li>
              ))}
            </ul>
          )}
          {a.blocker ? (
            <p className="flex items-start gap-2 rounded-md bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:bg-amber-500/15 dark:text-amber-300">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />{a.blocker}
            </p>
          ) : (
            <p className="flex items-center gap-2 rounded-md bg-emerald-50 px-3 py-2 text-xs text-emerald-800 dark:bg-emerald-500/15 dark:text-emerald-300">
              <CheckCircle2 className="h-3.5 w-3.5" />{tx('Approvals complete for this stage.')}
            </p>
          )}
          {a.myDecision && <p className="text-xs text-muted-foreground">{tx('Your decision')}: <b>{a.myDecision === 'approved' ? tx('approved') : tx('rejected')}</b></p>}
          {a.canDecide && canApprove && (
            <div className="space-y-2 border-t pt-3">
              <Textarea rows={2} maxLength={2000} value={comment} onChange={(e) => setComment(e.target.value)} placeholder={tx('Comment (optional)')} />
              <div className="flex gap-2">
                <Button size="sm" className="flex-1 bg-emerald-600 hover:bg-emerald-700" disabled={decide.isPending} onClick={() => submit('approved')}>
                  <CheckCircle2 className="mr-1.5 h-4 w-4" />{tx('Approve')}
                </Button>
                <Button size="sm" variant="outline" className="flex-1 border-rose-300 text-rose-700 hover:bg-rose-50 dark:text-rose-300" disabled={decide.isPending} onClick={() => submit('rejected')}>
                  <XCircle className="mr-1.5 h-4 w-4" />{tx('Reject')}
                </Button>
              </div>
            </div>
          )}
        </div>
      )}
      {eco.approvals.length > 0 && (
        <details className="mt-3 border-t pt-3 text-xs">
          <summary className="cursor-pointer text-muted-foreground">{tx('All decisions ({{n}})', { n: eco.approvals.length })}</summary>
          <ul className="mt-2 space-y-1.5">
            {eco.approvals.map((p) => (
              <li key={p.id} className={cn('flex flex-wrap items-center gap-1', p.voided && 'text-muted-foreground line-through')}>
                {p.decision === 'approved' ? <CheckCircle2 className="h-3 w-3 text-emerald-600" /> : <XCircle className="h-3 w-3 text-rose-600" />}
                <b>{p.userName ?? '?'}</b> · {p.stageName ?? '—'} · {ago(p.createdAt)}
                {p.comment && <span className="w-full pl-4 text-muted-foreground">“{p.comment}”</span>}
              </li>
            ))}
          </ul>
        </details>
      )}
    </Panel>
  );
}

// ---- changes ---------------------------------------------------------------------------------------------------------

function productName(map: ProductMap, id: string): ReactNode {
  const p = map.get(id);
  return p ? <><span className="font-mono text-xs text-muted-foreground">{p.code}</span> {p.name}</> : <span className="font-mono text-xs">{id}</span>;
}

function ChangesTab({ eco, productMap, canEdit }: { eco: ChangeDetail; productMap: ProductMap; canEdit: boolean }) {
  if (eco.type === 'bom') return <BomChanges eco={eco} productMap={productMap} canEdit={canEdit} />;
  if (eco.type === 'routing') return <RoutingChanges eco={eco} canEdit={canEdit} />;
  if (eco.type === 'product') {
    return (
      <Panel title={tx('Lifecycle change')} icon={ArrowRight}>
        {eco.plmItem ? (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center justify-center gap-3 rounded-lg bg-muted/40 py-4">
              <LifecyclePill state={eco.plmItem.lifecycleState} />
              <ArrowRight className="h-5 w-5 text-muted-foreground" />
              <LifecyclePill state={eco.targetLifecycleState} />
            </div>
            <LifecycleStepper current={eco.plmItem.lifecycleState} target={eco.status === 'applied' ? null : eco.targetLifecycleState} />
            <p className="text-xs text-muted-foreground">{tx('Applying the change moves the product to the target state (with the same checks as a direct transition).')}</p>
          </div>
        ) : <p className="text-sm text-muted-foreground">{tx('The product is no longer under lifecycle management.')}</p>}
      </Panel>
    );
  }
  return <DocumentList docs={eco.documents} />;
}

function BomChanges({ eco, productMap, canEdit }: { eco: ChangeDetail; productMap: ProductMap; canEdit: boolean }) {
  const c = eco.comparison;
  if (!c) return <EmptyState compact icon={GitCompare} title={tx('No BOM comparison')} hint={tx('The new BOM version is missing.')} />;
  const nothing = !c.header.length && !c.added.length && !c.removed.length && !c.changed.length;
  return (
    <Panel
      title={<span className="flex items-center gap-2">
        <Link to={`/manufacturing/boms/${c.from.id}`} className="font-mono text-primary hover:underline">{c.from.code} v{c.from.version}</Link>
        <ArrowRight className="h-4 w-4 text-muted-foreground" />
        <Link to={`/manufacturing/boms/${c.to.id}`} className="font-mono text-primary hover:underline">{c.to.code} v{c.to.version}</Link>
      </span>}
      icon={GitCompare}
      actions={eco.newBomId && (
        <Button size="sm" variant={canEdit ? 'default' : 'outline'} asChild>
          <Link to={`/manufacturing/boms/${eco.newBomId}`}>{canEdit ? <Pencil className="mr-1.5 h-4 w-4" /> : <ExternalLink className="mr-1.5 h-4 w-4" />}{canEdit ? tx('Edit new version') : tx('Open new version')}</Link>
        </Button>
      )}
    >
      {nothing ? (
        <p className="py-6 text-center text-sm text-muted-foreground">{tx('No differences yet: edit the new draft BOM version.')}</p>
      ) : (
        <div className="space-y-4">
          {c.header.length > 0 && (
            <div>
              <h3 className="mb-1.5 text-xs font-semibold uppercase text-muted-foreground">{tx('Header')}</h3>
              <div className="overflow-hidden rounded-md border">
                <Table>
                  <TableBody>
                    {c.header.map((h) => (
                      <TableRow key={h.field}>
                        <TableCell className="w-1/3 text-sm text-muted-foreground">{human(h.field)}</TableCell>
                        <TableCell className="text-sm"><s className="text-muted-foreground">{show(h.from)}</s> <ArrowRight className="mx-1 inline h-3 w-3" /> <b>{show(h.to)}</b></TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            </div>
          )}
          <div>
            <h3 className="mb-1.5 text-xs font-semibold uppercase text-muted-foreground">{tx('Components')}</h3>
            <div className="overflow-hidden rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow className="bg-muted/40">
                    <TableHead className="w-10" />
                    <TableHead>{tx('Component')}</TableHead>
                    <TableHead>{tx('Change')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {c.added.map((l) => (
                    <TableRow key={`a-${l.id}`} className="bg-emerald-50/60 dark:bg-emerald-500/5">
                      <TableCell><Plus className="h-4 w-4 text-emerald-600" /></TableCell>
                      <TableCell className="text-sm">{productName(productMap, l.componentProductId)}</TableCell>
                      <TableCell className="text-sm text-emerald-700 dark:text-emerald-300">{tx('Added')} × {qty(l.quantity)}{l.isOptional ? ` (${tx('optional')})` : ''}</TableCell>
                    </TableRow>
                  ))}
                  {c.removed.map((l) => (
                    <TableRow key={`r-${l.id}`} className="bg-rose-50/60 dark:bg-rose-500/5">
                      <TableCell><Minus className="h-4 w-4 text-rose-600" /></TableCell>
                      <TableCell className="text-sm">{productName(productMap, l.componentProductId)}</TableCell>
                      <TableCell className="text-sm text-rose-700 dark:text-rose-300">{tx('Removed')} ({tx('was')} × {qty(l.quantity)})</TableCell>
                    </TableRow>
                  ))}
                  {c.changed.map((l) => (
                    <TableRow key={`c-${l.componentProductId}`} className="bg-amber-50/60 dark:bg-amber-500/5">
                      <TableCell><Pencil className="h-4 w-4 text-amber-600" /></TableCell>
                      <TableCell className="text-sm">{productName(productMap, l.componentProductId.split(':')[0])}</TableCell>
                      <TableCell className="text-sm">
                        {Object.entries(l.changes).map(([k, v]) => (
                          <span key={k} className="mr-3 inline-block">{human(k)}: <s className="text-muted-foreground">{show(v.from)}</s> → <b>{show(v.to)}</b></span>
                        ))}
                      </TableCell>
                    </TableRow>
                  ))}
                  {!c.added.length && !c.removed.length && !c.changed.length && (
                    <TableRow><TableCell colSpan={3} className="py-4 text-center text-sm text-muted-foreground">{tx('Components unchanged')}</TableCell></TableRow>
                  )}
                </TableBody>
              </Table>
            </div>
          </div>
        </div>
      )}
    </Panel>
  );
}

interface OpDraft {
  sequence: string;
  name: string;
  workCenterId: string;
  durationMins: string;
  setupMins: string;
  instructions: string;
  isSubcontracted: boolean;
  subcontractorId: string;
  subcontractCostPerUnit: string;
}
const toDraft = (o: RoutingOperation): OpDraft => ({
  sequence: String(o.sequence ?? ''), name: o.name ?? '', workCenterId: o.workCenterId ?? '',
  durationMins: o.durationMins != null ? String(o.durationMins) : '', setupMins: o.setupMins != null ? String(o.setupMins) : '',
  instructions: o.instructions ?? '', isSubcontracted: !!o.isSubcontracted, subcontractorId: o.subcontractorId ?? '',
  subcontractCostPerUnit: o.subcontractCostPerUnit != null ? String(Number(o.subcontractCostPerUnit)) : '',
});

function RoutingChanges({ eco, canEdit }: { eco: ChangeDetail; canEdit: boolean }) {
  const { data: workCenters = [] } = useWorkCenters();
  const wcName = useMemo(() => new Map((workCenters as Array<{ id: string; code?: string; name: string }>).map((w) => [w.id, w.name])), [workCenters]);
  const save = useSaveRoutingOperations();
  const ops = eco.newRouting?.operations ?? [];
  const [rows, setRows] = useState<OpDraft[]>(() => ops.map(toDraft));
  const [dirty, setDirty] = useState(false);
  useEffect(() => {
    if (!dirty) setRows((eco.newRouting?.operations ?? []).map(toDraft));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [eco.newRouting]);
  const update = (i: number, patch: Partial<OpDraft>) => { setRows((r) => r.map((x, j) => (j === i ? { ...x, ...patch } : x))); setDirty(true); };
  const add = () => {
    const next = rows.reduce((m, r) => Math.max(m, Number(r.sequence) || 0), 0) + 10;
    setRows([...rows, { sequence: String(next), name: '', workCenterId: '', durationMins: '', setupMins: '', instructions: '', isSubcontracted: false, subcontractorId: '', subcontractCostPerUnit: '' }]);
    setDirty(true);
  };
  const submit = () => {
    if (!rows.length) return notify.error(tx('A routing needs at least one operation.'));
    if (rows.some((r) => !r.name.trim() || r.sequence === '')) return notify.error(tx('Every operation needs a sequence and a name.'));
    save.mutate({
      id: eco.id,
      operations: rows.map((r) => ({
        sequence: Number(r.sequence),
        name: r.name.trim(),
        workCenterId: r.workCenterId || undefined,
        durationMins: r.durationMins === '' ? undefined : Number(r.durationMins),
        setupMins: r.setupMins === '' ? undefined : Number(r.setupMins),
        instructions: r.instructions || undefined,
        isSubcontracted: r.isSubcontracted,
        subcontractorId: r.subcontractorId || undefined,
        subcontractCostPerUnit: r.subcontractCostPerUnit === '' ? undefined : Number(r.subcontractCostPerUnit),
      })),
    }, { onSuccess: () => { setDirty(false); notify.success(tx('Operations saved')); } });
  };
  const rc = eco.routingComparison;

  return (
    <div className="space-y-4">
      <Panel title={tx('Routing differences')} icon={GitCompare}>
        {!rc || (!rc.added.length && !rc.removed.length && !rc.changed.length) ? (
          <p className="py-4 text-center text-sm text-muted-foreground">{tx('No differences yet: edit the operations of the new routing below.')}</p>
        ) : (
          <ul className="space-y-1.5 text-sm">
            {rc.added.map((o) => <li key={`a${o.sequence}`} className="flex gap-2 text-emerald-700 dark:text-emerald-300"><Plus className="h-4 w-4" />{o.sequence} · {o.name}</li>)}
            {rc.removed.map((o) => <li key={`r${o.sequence}`} className="flex gap-2 text-rose-700 dark:text-rose-300"><Minus className="h-4 w-4" />{o.sequence} · {o.name}</li>)}
            {rc.changed.map((o) => (
              <li key={`c${o.sequence}`} className="flex gap-2">
                <Pencil className="h-4 w-4 shrink-0 text-amber-600" />
                <span><b>{o.sequence} · {o.name}</b>{' '}
                  {Object.entries(o.changes).map(([k, v]) => (
                    <span key={k} className="mr-3 inline-block">{human(k)}: <s className="text-muted-foreground">{k === 'workCenterId' ? wcName.get(String(v.from)) ?? show(v.from) : show(v.from)}</s> → <b>{k === 'workCenterId' ? wcName.get(String(v.to)) ?? show(v.to) : show(v.to)}</b></span>
                  ))}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Panel>

      <Panel
        title={eco.newRouting ? tx('New routing: {{n}}', { n: eco.newRouting.name }) : tx('New routing')}
        icon={Pencil}
        actions={canEdit && (
          <>
            <Button size="sm" variant="outline" onClick={add}><Plus className="mr-1 h-4 w-4" />{tx('Operation')}</Button>
            <Button size="sm" onClick={submit} disabled={!dirty || save.isPending}><Save className="mr-1 h-4 w-4" />{tx('Save operations')}</Button>
          </>
        )}
        bodyClassName="p-0"
      >
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/40">
                <TableHead className="w-20">{tx('Seq')}</TableHead>
                <TableHead>{tx('Operation')}</TableHead>
                <TableHead>{tx('Work centre')}</TableHead>
                <TableHead className="w-24">{tx('Run (min)')}</TableHead>
                <TableHead className="w-24">{tx('Setup (min)')}</TableHead>
                <TableHead className="w-24">{tx('Subcontract')}</TableHead>
                {canEdit && <TableHead className="w-10" />}
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r, i) => (
                <TableRow key={i}>
                  {canEdit ? (
                    <>
                      <TableCell><Input className="h-8" type="number" min={0} value={r.sequence} onChange={(e) => update(i, { sequence: e.target.value })} /></TableCell>
                      <TableCell className="min-w-[180px] space-y-1">
                        <Input className="h-8" value={r.name} onChange={(e) => update(i, { name: e.target.value })} placeholder={tx('Name')} />
                        <Input className="h-8 text-xs" value={r.instructions} onChange={(e) => update(i, { instructions: e.target.value })} placeholder={tx('Instructions')} />
                      </TableCell>
                      <TableCell className="min-w-[150px]">
                        <select className="h-8 w-full rounded-md border border-input bg-background px-2 text-sm" value={r.workCenterId} onChange={(e) => update(i, { workCenterId: e.target.value })}>
                          <option value="">—</option>
                          {(workCenters as Array<{ id: string; name: string }>).map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
                        </select>
                      </TableCell>
                      <TableCell><Input className="h-8" type="number" min={0} value={r.durationMins} onChange={(e) => update(i, { durationMins: e.target.value })} /></TableCell>
                      <TableCell><Input className="h-8" type="number" min={0} value={r.setupMins} onChange={(e) => update(i, { setupMins: e.target.value })} /></TableCell>
                      <TableCell>
                        <label className="flex items-center gap-1 text-xs">
                          <input type="checkbox" className="h-4 w-4 accent-primary" checked={r.isSubcontracted} onChange={(e) => update(i, { isSubcontracted: e.target.checked })} />
                          {r.isSubcontracted && <Input className="h-8 w-20" type="number" min={0} step="0.01" value={r.subcontractCostPerUnit} placeholder={tx('Cost')} onChange={(e) => update(i, { subcontractCostPerUnit: e.target.value })} />}
                        </label>
                      </TableCell>
                      <TableCell>
                        <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Remove')} onClick={() => { setRows(rows.filter((_, j) => j !== i)); setDirty(true); }}>
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </TableCell>
                    </>
                  ) : (
                    <>
                      <TableCell className="tabular-nums">{r.sequence}</TableCell>
                      <TableCell><div className="font-medium">{r.name}</div>{r.instructions && <div className="text-xs text-muted-foreground">{r.instructions}</div>}</TableCell>
                      <TableCell className="text-sm">{wcName.get(r.workCenterId) ?? '—'}</TableCell>
                      <TableCell className="tabular-nums">{r.durationMins || '0'}</TableCell>
                      <TableCell className="tabular-nums">{r.setupMins || '0'}</TableCell>
                      <TableCell className="text-sm">{r.isSubcontracted ? (r.subcontractCostPerUnit ? `${tx('yes')} · ${r.subcontractCostPerUnit}` : tx('yes')) : '—'}</TableCell>
                    </>
                  )}
                </TableRow>
              ))}
              {!rows.length && <TableRow><TableCell colSpan={7} className="py-6 text-center text-sm text-muted-foreground">{tx('No operations')}</TableCell></TableRow>}
            </TableBody>
          </Table>
        </div>
      </Panel>
    </div>
  );
}

// ---- impact ----------------------------------------------------------------------------------------------------------

function ImpactTab({ eco }: { eco: ChangeDetail }) {
  const { data: impact, isLoading } = useChangeImpact(eco.id);
  const currency = useOrgCurrency();
  if (isLoading || !impact) return <Skeleton className="h-72 w-full rounded-xl" />;
  const cost = impact.cost;
  const costOk = cost && !('error' in cost) ? cost : null;
  const delta = costOk ? num(costOk.delta) : 0;

  return (
    <div className="space-y-4">
      {impact.warnings.length > 0 ? (
        <div className="space-y-1.5 rounded-xl border border-amber-300 bg-amber-50 p-4 dark:border-amber-500/30 dark:bg-amber-500/10">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-amber-900 dark:text-amber-200"><AlertTriangle className="h-4 w-4" />{tx('Check before applying')}</h3>
          <ul className="list-disc space-y-1 pl-6 text-sm text-amber-900 dark:text-amber-200">
            {impact.warnings.map((w, i) => <li key={i}>{w}</li>)}
          </ul>
        </div>
      ) : (
        <div className="flex items-center gap-2 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-300">
          <CheckCircle2 className="h-4 w-4" />{tx('No warnings: nothing obvious is affected.')}
        </div>
      )}

      <div className="grid gap-3 sm:grid-cols-3">
        {cost && 'error' in cost ? (
          <div className="rounded-xl border bg-card p-4 text-sm text-muted-foreground shadow-sm sm:col-span-3">
            <Coins className="mb-1 h-4 w-4" />{tx('Cost roll-up unavailable')}: {cost.error}
          </div>
        ) : costOk ? (
          <>
            <Kpi label={tx('Current unit cost')} value={money(costOk.currentUnitCost, currency)} />
            <Kpi label={tx('New unit cost')} value={money(costOk.newUnitCost, currency)} />
            <Kpi
              label={tx('Cost change')}
              value={<span className={cn(delta > 0 ? 'text-rose-600' : delta < 0 ? 'text-emerald-600' : '')}>{delta > 0 ? '+' : ''}{money(costOk.delta, currency)}</span>}
              hint={costOk.deltaPct != null ? `${num(costOk.deltaPct) > 0 ? '+' : ''}${num(costOk.deltaPct)} %` : undefined}
            />
          </>
        ) : null}
        {impact.onHand != null && <Kpi label={tx('Product on hand')} value={qty(impact.onHand)} />}
        {impact.lifecycleState !== undefined && <Kpi label={tx('Product lifecycle')} value={impact.lifecycleState ? <LifecyclePill state={impact.lifecycleState} /> : '—'} />}
        <Kpi label={tx('Open production orders')} value={impact.openOrders.length} hint={WIP_DISPOSITION[impact.wipDisposition]} />
      </div>

      {(impact.removedComponents.length > 0 || impact.addedComponents.length > 0 || impact.changedComponents.length > 0) && (
        <Panel title={tx('Components')} icon={GitCompare} bodyClassName="p-0">
          <ComponentImpact impact={impact} />
        </Panel>
      )}

      {impact.openOrders.length > 0 && (
        <Panel title={tx('Open production orders')} icon={Clock} bodyClassName="p-0">
          <Table>
            <TableHeader><TableRow className="bg-muted/40"><TableHead>{tx('Order')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead className="text-right">{tx('Planned qty')}</TableHead><TableHead>{tx('BOM version')}</TableHead><TableHead>{tx('Scheduled')}</TableHead></TableRow></TableHeader>
            <TableBody>
              {impact.openOrders.map((o) => (
                <TableRow key={o.id}>
                  <TableCell><Link to={`/manufacturing/orders/${o.id}`} className="font-mono text-xs text-primary hover:underline">{o.orderCode}</Link></TableCell>
                  <TableCell><StatusPill tone="info">{human(o.status)}</StatusPill></TableCell>
                  <TableCell className="text-right tabular-nums">{qty(o.plannedQty)}</TableCell>
                  <TableCell>{o.bomVersion != null ? `v${o.bomVersion}` : '—'}</TableCell>
                  <TableCell className="text-sm text-muted-foreground">{o.scheduledFor ? day(String(o.scheduledFor)) : '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Panel>
      )}

      {impact.routing && (
        <Panel title={tx('BOMs re-pointed to the new routing')} icon={ArrowRight}>
          {impact.routing.bomsRepointed.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No BOM uses the current routing.')}</p> : (
            <div className="flex flex-wrap gap-2">
              {impact.routing.bomsRepointed.map((b) => (
                <Link key={b.id} to={`/manufacturing/boms/${b.id}`} className="rounded-md border px-2 py-1 font-mono text-xs hover:border-primary">{b.code} v{b.version} <span className="text-muted-foreground">({b.status})</span></Link>
              ))}
            </div>
          )}
        </Panel>
      )}

      {eco.type === 'bom' && eco.status !== 'applied' && impact.dispositionPlan && <DispositionPlanPanel plan={impact.dispositionPlan} />}

      {impact.whereUsed && impact.whereUsed.length > 0 && (
        <Panel title={tx('Where used')} icon={ExternalLink} bodyClassName="p-0">
          <Table>
            <TableHeader><TableRow className="bg-muted/40"><TableHead>{tx('Level')}</TableHead><TableHead>{tx('BOM')}</TableHead><TableHead>{tx('Parent product')}</TableHead><TableHead className="text-right">{tx('Quantity')}</TableHead></TableRow></TableHeader>
            <TableBody>
              {impact.whereUsed.map((w, i) => (
                <TableRow key={`${w.bomId}-${i}`}>
                  <TableCell className="text-xs text-muted-foreground">{tx('Level {{n}}', { n: w.level })}</TableCell>
                  <TableCell><Link to={`/manufacturing/boms/${w.bomId}`} className="font-mono text-xs text-primary hover:underline">{w.code}</Link> <span className="text-xs text-muted-foreground">v{w.version}</span></TableCell>
                  <TableCell className="text-sm">{w.outputName ?? w.name}</TableCell>
                  <TableCell className="text-right tabular-nums">{qty(w.quantity)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Panel>
      )}
    </div>
  );
}

const DOC_LABEL: Record<string, { label: string; to: string }> = {
  stock_adjustment: { label: tx('Stock adjustment'), to: '/inventory/adjustments' },
  stock_transfer: { label: tx('Stock transfer'), to: '/inventory/transfers' },
  debit_note: { label: tx('Debit note'), to: '/procurement/debit-notes' },
};

function productText(p: { code?: string; name?: string } | null | undefined, fallback: string) {
  return p ? `${p.code ? `${p.code} · ` : ''}${p.name ?? ''}` : fallback;
}

/** What apply will do with the stock of removed components and with open production orders. */
function DispositionPlanPanel({ plan }: { plan: DispositionPlan }) {
  const nothing = plan.stock.length === 0 && plan.orders.length === 0 && plan.keptComponents.length === 0;
  return (
    <Panel title={tx('Stock and open orders on apply')} icon={Inbox} bodyClassName="space-y-3 p-0">
      <p className="px-4 pt-3 text-sm text-muted-foreground">
        {STOCK_DISPOSITION[plan.stockDisposition] ?? plan.stockDisposition} · {WIP_DISPOSITION[plan.wipDisposition] ?? plan.wipDisposition}
        {nothing && <> · {tx('nothing to move: no stock of removed components and no open orders.')}</>}
      </p>
      {plan.useUpPending.length > 0 && (
        <p className="mx-4 rounded-md bg-amber-50 px-3 py-2 text-xs text-amber-900 dark:bg-amber-500/10 dark:text-amber-200">
          {tx('Waiting for stock to be used up:')} {plan.useUpPending.map((u) => `${u.label} (${qty(u.onHand)})`).join(', ')}
        </p>
      )}
      {plan.stock.length > 0 && (
        <Table>
          <TableHeader><TableRow className="bg-muted/40"><TableHead>{tx('Component')}</TableHead><TableHead>{tx('Location')}</TableHead><TableHead className="text-right">{tx('On hand')}</TableHead><TableHead>{tx('Action')}</TableHead></TableRow></TableHeader>
          <TableBody>
            {plan.stock.map((r, i) => (
              <TableRow key={`${r.productId}-${r.locationId}-${i}`}>
                <TableCell className="text-sm">{productText(r.product, r.productId)}</TableCell>
                <TableCell className="text-sm">{r.locationName}</TableCell>
                <TableCell className="text-right tabular-nums">{qty(r.quantity)}</TableCell>
                <TableCell className="text-sm">{r.action}{r.supplier && <span className="text-muted-foreground"> · {r.supplier.name}</span>}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {plan.keptComponents.length > 0 && (
        <ul className="px-4 text-xs text-muted-foreground">
          {plan.keptComponents.map((k) => <li key={k.productId}>{productText(k.product, k.productId)}: {k.reason}</li>)}
        </ul>
      )}
      {plan.orders.length > 0 && (
        <Table>
          <TableHeader><TableRow className="bg-muted/40"><TableHead>{tx('Order')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead className="text-right">{tx('Quantity')}</TableHead><TableHead>{tx('Action')}</TableHead></TableRow></TableHeader>
          <TableBody>
            {plan.orders.map((o) => (
              <TableRow key={o.id}>
                <TableCell><Link to={`/manufacturing/orders/${o.id}`} className="font-mono text-xs text-primary hover:underline">{o.orderCode}</Link></TableCell>
                <TableCell><StatusPill tone="info">{human(o.status)}</StatusPill></TableCell>
                <TableCell className="text-right tabular-nums">{qty(o.plannedQty)}</TableCell>
                <TableCell className="text-sm">{o.action}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      <div className="pb-1" />
    </Panel>
  );
}

/** What apply did: draft stock documents, replaced / flagged / kept orders, and what was skipped. */
function DispositionResultPanel({ result }: { result: DispositionResult }) {
  const empty = result.documents.length + result.replacedOrders.length + result.flaggedOrders.length
    + result.keptOrders.length + result.skipped.length === 0;
  if (empty) return null;
  return (
    <Panel title={tx('Stock and open orders')} icon={Inbox} bodyClassName="space-y-2 text-sm">
      {result.documents.length > 0 && (
        <div>
          <p className="font-medium">{tx('Draft documents to post')}</p>
          <ul className="mt-1 space-y-0.5">
            {result.documents.map((d) => (
              <li key={d.id}>
                <Link to={DOC_LABEL[d.kind]?.to ?? '#'} className="text-primary hover:underline">{DOC_LABEL[d.kind]?.label ?? d.kind} <span className="font-mono">{d.code}</span></Link>
                <span className="text-muted-foreground"> · {tx('{{n}} line(s)', { n: num(d.lines) })}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {result.replacedOrders.length > 0 && (
        <div>
          <p className="font-medium">{tx('Orders moved to the new BOM')}</p>
          <ul className="mt-1 space-y-0.5">
            {result.replacedOrders.map((o) => (
              <li key={o.fromId} className="flex flex-wrap items-center gap-1.5">
                <Link to={`/manufacturing/orders/${o.fromId}`} className="font-mono text-xs text-muted-foreground line-through hover:underline">{o.fromCode}</Link>
                <ArrowRight className="h-3 w-3 text-muted-foreground" />
                <Link to={`/manufacturing/orders/${o.toId}`} className="font-mono text-xs text-primary hover:underline">{o.toCode}</Link>
                <StatusPill tone="info">{human(o.status)}</StatusPill>
              </li>
            ))}
          </ul>
        </div>
      )}
      {result.flaggedOrders.length > 0 && (
        <p>{tx('Flagged for quality check:')} {result.flaggedOrders.map((o, i) => (
          <span key={o.id}>{i > 0 && ', '}<Link to={`/manufacturing/orders/${o.id}`} className="font-mono text-xs text-primary hover:underline">{o.orderCode}</Link></span>
        ))}</p>
      )}
      {result.keptOrders.length > 0 && (
        <ul className="text-xs text-muted-foreground">
          {result.keptOrders.map((o) => <li key={o.id}><Link to={`/manufacturing/orders/${o.id}`} className="font-mono hover:underline">{o.orderCode}</Link> {tx('kept')}: {o.reason}</li>)}
        </ul>
      )}
      {result.skipped.length > 0 && (
        <ul className="text-xs text-muted-foreground">
          {result.skipped.map((k, i) => <li key={`${k.productId}-${i}`}>{productText(k.product, k.productId)}: {k.reason}</li>)}
        </ul>
      )}
    </Panel>
  );
}

function Kpi({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="rounded-xl border bg-card p-4 shadow-sm">
      <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
      <div className="mt-1 text-xl font-semibold tabular-nums">{value}</div>
      {hint && <p className="mt-0.5 text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

function ComponentImpact({ impact }: { impact: ChangeImpact }) {
  return (
    <Table>
      <TableHeader>
        <TableRow className="bg-muted/40">
          <TableHead className="w-10" />
          <TableHead>{tx('Component')}</TableHead>
          <TableHead>{tx('Change')}</TableHead>
          <TableHead className="text-right">{tx('On hand')}</TableHead>
          <TableHead>{tx('Notes')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {impact.removedComponents.map((c) => (
          <TableRow key={`r-${c.productId}`}>
            <TableCell><Minus className="h-4 w-4 text-rose-600" /></TableCell>
            <TableCell className="text-sm"><ProductLabel product={c.product} fallback={c.productId} /></TableCell>
            <TableCell className="text-sm">{tx('Removed')} ({tx('was')} × {qty(c.quantity)})</TableCell>
            <TableCell className="text-right tabular-nums">{qty(c.onHand)}</TableCell>
            <TableCell className="text-sm">
              {c.obsolescenceRisk
                ? <StatusPill tone="danger">{tx('Obsolescence risk')}</StatusPill>
                : <span className="text-xs text-muted-foreground">{tx('Used in {{n}} other active BOM(s)', { n: num(c.usedElsewhere) })}</span>}
            </TableCell>
          </TableRow>
        ))}
        {impact.addedComponents.map((c) => (
          <TableRow key={`a-${c.productId}`}>
            <TableCell><Plus className="h-4 w-4 text-emerald-600" /></TableCell>
            <TableCell className="text-sm"><ProductLabel product={c.product} fallback={c.productId} /></TableCell>
            <TableCell className="text-sm">{tx('Added')} × {qty(c.quantity)}</TableCell>
            <TableCell className="text-right tabular-nums">{qty(c.onHand)}</TableCell>
            <TableCell>{c.lifecycleState ? <LifecyclePill state={c.lifecycleState} /> : <span className="text-xs text-muted-foreground">{tx('Not under PLM')}</span>}</TableCell>
          </TableRow>
        ))}
        {impact.changedComponents.map((c) => (
          <TableRow key={`c-${c.productId}`}>
            <TableCell><Pencil className="h-4 w-4 text-amber-600" /></TableCell>
            <TableCell className="text-sm"><ProductLabel product={c.product} fallback={c.productId} /></TableCell>
            <TableCell className="text-sm">
              {Object.entries(c.changes ?? {}).map(([k, v]) => <span key={k} className="mr-3 inline-block">{human(k)}: {show(v.from)} → <b>{show(v.to)}</b></span>)}
            </TableCell>
            <TableCell className="text-right tabular-nums">{qty(c.onHand)}</TableCell>
            <TableCell />
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

// ---- documents -------------------------------------------------------------------------------------------------------

function DocumentList({ docs }: { docs: PlmDocument[] }) {
  if (!docs.length) return <EmptyState compact icon={FileText} title={tx('No documents attached')} />;
  return (
    <div className="overflow-hidden rounded-xl border bg-card shadow-sm">
      <Table>
        <TableHeader>
          <TableRow className="bg-muted/40">
            <TableHead>{tx('Document')}</TableHead>
            <TableHead>{tx('Title')}</TableHead>
            <TableHead>{tx('Type')}</TableHead>
            <TableHead>{tx('Status')}</TableHead>
            <TableHead>{tx('File')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {docs.map((d) => (
            <TableRow key={d.id}>
              <TableCell className="whitespace-nowrap"><span className="font-mono text-xs">{d.number}</span> <span className="text-xs text-muted-foreground">v{d.version} · {tx('Rev')} {d.revision}</span></TableCell>
              <TableCell>{d.title}</TableCell>
              <TableCell className="text-sm text-muted-foreground">{DOC_TYPES[d.docType] ?? d.docType}</TableCell>
              <TableCell><DocStatusPill status={d.status} /></TableCell>
              <TableCell><FileLink fileId={d.fileId} url={d.url} fileName={d.fileName} /></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function DocumentsTab({ eco, canEdit }: { eco: ChangeDetail; canEdit: boolean }) {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.plm.manage);
  const { data: drafts = [] } = usePlmDocuments({ status: 'draft' }, canEdit);
  const link = useLinkChangeDocuments();
  const [picked, setPicked] = useState<string[]>([]);
  const [creating, setCreating] = useState(false);
  const attached = new Set(eco.documents.map((d) => d.id));
  const options = drafts
    .filter((d) => !attached.has(d.id))
    .sort((a, b) => Number(b.productId === eco.productId) - Number(a.productId === eco.productId))
    .map((d) => ({
      id: d.id,
      label: <><span className="font-mono text-xs text-muted-foreground">{d.number} v{d.version}</span> {d.title}</>,
      hint: [DOC_TYPES[d.docType], d.product?.code, d.changeNumber ? tx('on {{n}}', { n: d.changeNumber }) : null].filter(Boolean).join(' · '),
    }));
  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">{tx('Draft documents attached to the change are released when it is applied.')}</p>
      <DocumentList docs={eco.documents} />
      {canEdit && (
        <Panel
          title={tx('Attach draft documents')}
          icon={Paperclip}
          actions={canManage && <Button size="sm" variant="outline" onClick={() => setCreating(true)}><Plus className="mr-1 h-4 w-4" />{tx('New document')}</Button>}
        >
          <CheckList options={options} value={picked} onChange={setPicked} empty={tx('No other draft documents.')} />
          <div className="mt-3 flex justify-end">
            <Button size="sm" disabled={!picked.length || link.isPending}
              onClick={() => link.mutate({ id: eco.id, documentIds: picked }, { onSuccess: () => { setPicked([]); notify.success(tx('Documents attached')); } })}>
              <Paperclip className="mr-1.5 h-4 w-4" />{tx('Attach {{n}}', { n: picked.length })}
            </Button>
          </div>
        </Panel>
      )}
      <DocumentDialog open={creating} onClose={() => setCreating(false)} productId={eco.productId} changeId={eco.id} />
    </div>
  );
}

// ---- activity --------------------------------------------------------------------------------------------------------

const ACTIVITY_TONE: Record<string, string> = {
  created: 'bg-sky-500', edited: 'bg-slate-400', stage: 'bg-violet-500', approval: 'bg-amber-500', applied: 'bg-emerald-500',
  status: 'bg-rose-500', comment: 'bg-primary',
};

function ActivityTab({ eco }: { eco: ChangeDetail }) {
  const comment = useCommentChange();
  const [message, setMessage] = useState('');
  const send = () => {
    if (!message.trim()) return;
    comment.mutate({ id: eco.id, message: message.trim() }, { onSuccess: () => setMessage('') });
  };
  return (
    <div className="space-y-4">
      <div className="rounded-xl border bg-card p-3 shadow-sm">
        <Textarea rows={2} maxLength={4000} value={message} onChange={(e) => setMessage(e.target.value)} placeholder={tx('Write a comment…')}
          onKeyDown={(e) => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) send(); }} />
        <div className="mt-2 flex justify-end">
          <Button size="sm" onClick={send} disabled={!message.trim() || comment.isPending}><MessageSquare className="mr-1.5 h-4 w-4" />{tx('Comment')}</Button>
        </div>
      </div>
      {eco.activity.length === 0 ? <EmptyState compact icon={Activity} title={tx('No activity yet')} /> : (
        <div className="rounded-xl border bg-card p-4 shadow-sm">
          <ol className="relative space-y-4 border-l pl-6">
            {eco.activity.map((a) => (
              <li key={a.id} className="relative">
                <span className={cn('absolute -left-[29px] top-1 h-3.5 w-3.5 rounded-full border-2 border-background', ACTIVITY_TONE[a.kind] ?? 'bg-slate-400')} />
                {a.kind === 'comment' ? (
                  <div className="rounded-lg bg-muted/50 px-3 py-2 text-sm">
                    <p className="mb-0.5 flex items-center gap-1.5 text-xs text-muted-foreground"><Avatar name={a.userName} className="h-4 w-4 text-[8px]" /><b className="text-foreground">{a.userName ?? '?'}</b> · {dayTime(a.createdAt)}</p>
                    <p className="whitespace-pre-wrap">{a.message}</p>
                  </div>
                ) : (
                  <>
                    <p className="text-sm">{a.message}</p>
                    <p className="text-xs text-muted-foreground">{a.userName ? `${a.userName} · ` : ''}{dayTime(a.createdAt)}</p>
                  </>
                )}
              </li>
            ))}
          </ol>
        </div>
      )}
    </div>
  );
}

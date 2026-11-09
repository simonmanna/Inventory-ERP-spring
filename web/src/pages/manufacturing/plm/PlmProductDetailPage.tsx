import { useState, type ReactNode } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import {
  ArrowLeft, ArrowRight, ClipboardList, Factory, FileText, GitPullRequest, History, Inbox, ListTree, Network, Pencil, Plus,
  Route as RouteIcon, Star, Tag,
} from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { StatusPill } from '@/components/list/list-page';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { num, usePlmItem, type LifecycleState, type PlmItemDetail } from '@/features/manufacturing/api-plm';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import {
  Avatar, ChangeStatusPill, ChangeTypeBadge, DocStatusPill, EmptyState, FileLink, LifecyclePill, LifecycleStepper,
  PriorityPill, RequestStatusPill, RevisionBadge, TabBar,
} from './plm-ui';
import { EditItemDialog, TransitionDialog } from './item-dialogs';
import { ChangeDialog } from './change-dialogs';
import { RequestDialog } from './request-dialogs';
import { DocumentDialog } from './document-dialogs';
import { BOM_STATUS_TONE, DOC_TYPES, LIFECYCLE, LIFECYCLE_ORDER, REASONS, day, dayTime, human, qty } from './vocab';

type Tab = 'boms' | 'routings' | 'changes' | 'requests' | 'documents' | 'where-used' | 'orders' | 'history';

/** Product cockpit: lifecycle, revision and everything engineering knows about one product. */
export function PlmProductDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) || 'boms';
  const setTab = (t: Tab) => {
    const next = new URLSearchParams(params);
    if (t === 'boms') next.delete('tab'); else next.set('tab', t);
    setParams(next, { replace: true });
  };
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.plm.manage);
  const canChange = has(PERMISSIONS.plm.change);
  const canRequest = has(PERMISSIONS.plm.request);
  const { data: item, isLoading, error } = usePlmItem(id);
  const [editing, setEditing] = useState(false);
  const [toState, setToState] = useState<LifecycleState | null>(null);
  const [newChange, setNewChange] = useState(false);
  const [newRequest, setNewRequest] = useState(false);
  const [newDoc, setNewDoc] = useState(false);

  if (error) {
    return (
      <div className="p-10 text-center text-muted-foreground">
        {tx('This product is not under lifecycle management.')}{' '}
        <Link to="/manufacturing/plm/products" className="text-primary hover:underline">{tx('Back to products')}</Link>
      </div>
    );
  }
  if (isLoading || !item) return <div className="space-y-4"><Skeleton className="h-28 w-full" /><Skeleton className="h-96 w-full" /></div>;

  const forward = (s: LifecycleState) => LIFECYCLE_ORDER.indexOf(s) > LIFECYCLE_ORDER.indexOf(item.lifecycleState);
  const tabs = [
    { id: 'boms' as const, label: tx('BOM versions'), icon: ListTree, count: item.boms.length },
    { id: 'routings' as const, label: tx('Routings'), icon: RouteIcon, count: item.routings.length },
    { id: 'changes' as const, label: tx('Changes'), icon: GitPullRequest, count: item.changes.filter((c) => c.status === 'draft' || c.status === 'approved').length },
    { id: 'requests' as const, label: tx('Requests'), icon: Inbox, count: item.requests.filter((r) => r.status === 'new' || r.status === 'accepted').length },
    { id: 'documents' as const, label: tx('Documents'), icon: FileText, count: item.documents.length },
    { id: 'where-used' as const, label: tx('Where used'), icon: Network, count: item.whereUsed.length },
    { id: 'orders' as const, label: tx('Open orders'), icon: Factory, count: item.openOrders.length },
    { id: 'history' as const, label: tx('History'), icon: History },
  ];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          <Link to="/manufacturing/plm/products" className="mt-1.5 rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground" aria-label={tx('Back to products')}>
            <ArrowLeft className="h-4 w-4" />
          </Link>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <span className="font-mono">{item.product.code}</span>
              <RevisionBadge revision={item.revision} />
              <LifecyclePill state={item.lifecycleState} />
              {item.product.manufacturingRole && <span className="rounded bg-muted px-1.5 py-0.5">{human(item.product.manufacturingRole)}</span>}
            </div>
            <h1 className="truncate text-2xl font-bold tracking-tight">{item.product.name}</h1>
            <p className="flex flex-wrap items-center gap-x-2 text-sm text-muted-foreground">
              {item.ownerName ? <span className="flex items-center gap-1.5"><Avatar name={item.ownerName} className="h-5 w-5" />{item.ownerName}</span> : tx('No owner')}
              {item.releasedAt && <span>· {tx('Released')} {day(item.releasedAt)}</span>}
              {item.obsoleteAt && <span>· {tx('Obsolete since')} {day(item.obsoleteAt)}</span>}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {canRequest && <Button variant="outline" size="sm" onClick={() => setNewRequest(true)}><Inbox className="mr-1.5 h-4 w-4" />{tx('Request change')}</Button>}
          {canChange && <Button size="sm" onClick={() => setNewChange(true)}><Plus className="mr-1.5 h-4 w-4" />{tx('New change')}</Button>}
          {canManage && <Button variant="outline" size="sm" onClick={() => setEditing(true)}><Pencil className="mr-1.5 h-4 w-4" />{tx('Edit')}</Button>}
        </div>
      </div>

      <div className="rounded-xl border bg-card p-4 shadow-sm">
        <LifecycleStepper current={item.lifecycleState} />
        {canManage && item.allowedTransitions.length > 0 && (
          <div className="mt-4 flex flex-wrap items-center gap-2 border-t pt-3">
            <span className="text-xs text-muted-foreground">{tx('Move to')}:</span>
            {item.allowedTransitions.map((s) => (
              <Button key={s} size="sm" variant={forward(s) && s !== 'obsolete' ? 'default' : 'outline'}
                className={cn(s === 'obsolete' && 'border-rose-300 text-rose-700 hover:bg-rose-50 dark:text-rose-300')}
                onClick={() => setToState(s)}>
                {forward(s) && <ArrowRight className="mr-1 h-3.5 w-3.5" />}{LIFECYCLE[s].label}
              </Button>
            ))}
            <span className="ml-auto text-xs text-muted-foreground">{tx('Or use a lifecycle change order for an approved move.')}</span>
          </div>
        )}
        {(item.description || item.notes || item.replacement) && (
          <div className="mt-4 grid gap-3 border-t pt-3 text-sm md:grid-cols-3">
            {item.description && <div><p className="text-xs font-medium uppercase text-muted-foreground">{tx('Description')}</p><p className="whitespace-pre-wrap">{item.description}</p></div>}
            {item.notes && <div><p className="text-xs font-medium uppercase text-muted-foreground">{tx('Notes')}</p><p className="whitespace-pre-wrap">{item.notes}</p></div>}
            {item.replacement && (
              <div>
                <p className="text-xs font-medium uppercase text-muted-foreground">{tx('Replaced by')}</p>
                <p><span className="font-mono text-xs text-muted-foreground">{item.replacement.code}</span> {item.replacement.name}</p>
              </div>
            )}
          </div>
        )}
      </div>

      <TabBar tabs={tabs} value={tab} onChange={setTab} />

      {tab === 'boms' && <BomsTab item={item} />}
      {tab === 'routings' && <RoutingsTab item={item} />}
      {tab === 'changes' && (
        <ChangesTab item={item} action={canChange && <Button size="sm" onClick={() => setNewChange(true)}><Plus className="mr-1 h-4 w-4" />{tx('New change')}</Button>} />
      )}
      {tab === 'requests' && (
        <RequestsTab item={item} action={canRequest && <Button size="sm" variant="outline" onClick={() => setNewRequest(true)}><Plus className="mr-1 h-4 w-4" />{tx('New request')}</Button>} />
      )}
      {tab === 'documents' && (
        <DocumentsTab item={item} action={canManage && <Button size="sm" variant="outline" onClick={() => setNewDoc(true)}><Plus className="mr-1 h-4 w-4" />{tx('New document')}</Button>} />
      )}
      {tab === 'where-used' && <WhereUsedTab item={item} />}
      {tab === 'orders' && <OrdersTab item={item} />}
      {tab === 'history' && <HistoryTab item={item} />}

      <EditItemDialog item={item} open={editing} onClose={() => setEditing(false)} />
      <TransitionDialog item={item} toState={toState} onClose={() => setToState(null)} />
      <ChangeDialog open={newChange} onClose={() => setNewChange(false)} productId={item.productId} />
      <RequestDialog open={newRequest} onClose={() => setNewRequest(false)} productId={item.productId} />
      <DocumentDialog open={newDoc} onClose={() => setNewDoc(false)} productId={item.productId} />
    </div>
  );
}

function TabCard({ children, action, empty }: { children: ReactNode; action?: ReactNode; empty?: boolean }) {
  return (
    <div className="space-y-2">
      {action && <div className="flex justify-end">{action}</div>}
      {empty ? children : <div className="overflow-hidden rounded-xl border bg-card shadow-sm [&_thead_tr]:bg-muted/40">{children}</div>}
    </div>
  );
}

function BomsTab({ item }: { item: PlmItemDetail }) {
  if (!item.boms.length) return <EmptyState compact icon={ListTree} title={tx('No BOM yet')} hint={tx('Create a BOM for this product under Manufacturing → Recipes (BOM).')} />;
  return (
    <TabCard>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{tx('BOM')}</TableHead>
            <TableHead>{tx('Version')}</TableHead>
            <TableHead>{tx('Status')}</TableHead>
            <TableHead>{tx('Effective')}</TableHead>
            <TableHead>{tx('Updated')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {item.boms.map((b) => (
            <TableRow key={b.id}>
              <TableCell>
                <Link to={`/manufacturing/boms/${b.id}`} className="font-mono text-xs text-primary hover:underline">{b.code}</Link>
                <div className="flex items-center gap-1 text-sm">{b.name}{b.isDefault && <Star className="h-3 w-3 fill-amber-400 text-amber-400" aria-label={tx('Default')} />}</div>
              </TableCell>
              <TableCell>v{b.version}</TableCell>
              <TableCell><StatusPill tone={BOM_STATUS_TONE[b.status] ?? 'neutral'}>{human(b.status)}</StatusPill></TableCell>
              <TableCell className="text-sm text-muted-foreground">{b.effectiveFrom || b.effectiveTo ? `${day(b.effectiveFrom)} → ${day(b.effectiveTo)}` : tx('Always')}</TableCell>
              <TableCell className="text-sm text-muted-foreground">{day(b.updatedAt)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TabCard>
  );
}

function RoutingsTab({ item }: { item: PlmItemDetail }) {
  if (!item.routings.length) return <EmptyState compact icon={RouteIcon} title={tx('No routing')} hint={tx('Routings of the product or used by its BOMs appear here.')} />;
  return (
    <TabCard>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{tx('Routing')}</TableHead>
            <TableHead>{tx('Status')}</TableHead>
            <TableHead className="text-right">{tx('Operations')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {item.routings.map((r) => (
            <TableRow key={r.id}>
              <TableCell className="font-medium">{r.name}</TableCell>
              <TableCell><StatusPill tone={r.isActive ? 'success' : 'neutral'}>{r.isActive ? tx('Active') : tx('Inactive')}</StatusPill></TableCell>
              <TableCell className="text-right tabular-nums">{num(r.operations)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TabCard>
  );
}

function ChangesTab({ item, action }: { item: PlmItemDetail; action?: ReactNode }) {
  if (!item.changes.length) return <TabCard empty action={action}><EmptyState compact icon={GitPullRequest} title={tx('No change orders yet')} /></TabCard>;
  return (
    <TabCard action={action}>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{tx('ECO')}</TableHead>
            <TableHead>{tx('Title')}</TableHead>
            <TableHead>{tx('Type')}</TableHead>
            <TableHead>{tx('Stage')}</TableHead>
            <TableHead>{tx('Status')}</TableHead>
            <TableHead>{tx('Revision')}</TableHead>
            <TableHead>{tx('Date')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {item.changes.map((c) => (
            <TableRow key={c.id}>
              <TableCell><Link to={`/manufacturing/plm/changes/${c.id}`} className="font-mono text-xs text-primary hover:underline">{c.number}</Link></TableCell>
              <TableCell><span className="flex items-center gap-2">{c.title}<PriorityPill priority={c.priority} hideNormal /></span></TableCell>
              <TableCell><ChangeTypeBadge type={c.type} /></TableCell>
              <TableCell className="text-sm">{c.stageName ?? '—'}</TableCell>
              <TableCell><ChangeStatusPill status={c.status} /></TableCell>
              <TableCell><RevisionBadge revision={c.newRevision} /></TableCell>
              <TableCell className="text-sm text-muted-foreground">{day(c.appliedAt ?? c.createdAt)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TabCard>
  );
}

function RequestsTab({ item, action }: { item: PlmItemDetail; action?: ReactNode }) {
  if (!item.requests.length) return <TabCard empty action={action}><EmptyState compact icon={Inbox} title={tx('No change requests')} /></TabCard>;
  return (
    <TabCard action={action}>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{tx('ECR')}</TableHead>
            <TableHead>{tx('Title')}</TableHead>
            <TableHead>{tx('Reason')}</TableHead>
            <TableHead>{tx('Priority')}</TableHead>
            <TableHead>{tx('Status')}</TableHead>
            <TableHead>{tx('Change order')}</TableHead>
            <TableHead>{tx('Created')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {item.requests.map((r) => (
            <TableRow key={r.id}>
              <TableCell><Link to={`/manufacturing/plm/requests?q=${encodeURIComponent(r.number)}`} className="font-mono text-xs text-primary hover:underline">{r.number}</Link></TableCell>
              <TableCell>{r.title}</TableCell>
              <TableCell className="text-sm text-muted-foreground">{REASONS[r.reasonCode] ?? r.reasonCode}</TableCell>
              <TableCell><PriorityPill priority={r.priority} /></TableCell>
              <TableCell><RequestStatusPill status={r.status} /></TableCell>
              <TableCell>{r.changeId ? <Link to={`/manufacturing/plm/changes/${r.changeId}`} className="text-sm text-primary hover:underline">{tx('Open')}</Link> : '—'}</TableCell>
              <TableCell className="text-sm text-muted-foreground">{day(r.createdAt)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TabCard>
  );
}

function DocumentsTab({ item, action }: { item: PlmItemDetail; action?: ReactNode }) {
  if (!item.documents.length) return <TabCard empty action={action}><EmptyState compact icon={FileText} title={tx('No current documents')} hint={tx('Drawings, specifications and instructions of this product.')} /></TabCard>;
  return (
    <TabCard action={action}>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{tx('Document')}</TableHead>
            <TableHead>{tx('Title')}</TableHead>
            <TableHead>{tx('Type')}</TableHead>
            <TableHead>{tx('Status')}</TableHead>
            <TableHead>{tx('File')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {item.documents.map((d) => (
            <TableRow key={d.id}>
              <TableCell>
                <Link to={`/manufacturing/plm/documents?q=${encodeURIComponent(d.number)}`} className="font-mono text-xs text-primary hover:underline">{d.number}</Link>
                <span className="ml-1 text-xs text-muted-foreground">v{d.version} · {tx('Rev')} {d.revision}</span>
              </TableCell>
              <TableCell>{d.title}</TableCell>
              <TableCell className="text-sm text-muted-foreground">{DOC_TYPES[d.docType] ?? d.docType}</TableCell>
              <TableCell><DocStatusPill status={d.status} /></TableCell>
              <TableCell><FileLink fileId={d.fileId} url={d.url} fileName={d.fileName} /></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TabCard>
  );
}

function WhereUsedTab({ item }: { item: PlmItemDetail }) {
  if (!item.whereUsed.length) return <EmptyState compact icon={Network} title={tx('Not used in any active BOM')} hint={tx('Parent products that use this one (directly or through sub-assemblies) appear here.')} />;
  return (
    <TabCard>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{tx('Level')}</TableHead>
            <TableHead>{tx('BOM')}</TableHead>
            <TableHead>{tx('Parent product')}</TableHead>
            <TableHead className="text-right">{tx('Quantity')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {item.whereUsed.map((w, i) => (
            <TableRow key={`${w.bomId}-${i}`}>
              <TableCell><span style={{ paddingLeft: (w.level - 1) * 14 }} className="text-xs text-muted-foreground">{'↳ '.repeat(Math.min(w.level, 1))}{tx('Level {{n}}', { n: w.level })}</span></TableCell>
              <TableCell><Link to={`/manufacturing/boms/${w.bomId}`} className="font-mono text-xs text-primary hover:underline">{w.code}</Link> <span className="text-xs text-muted-foreground">v{w.version}</span></TableCell>
              <TableCell>{w.outputName ?? w.name}</TableCell>
              <TableCell className="text-right tabular-nums">{qty(w.quantity)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TabCard>
  );
}

function OrdersTab({ item }: { item: PlmItemDetail }) {
  if (!item.openOrders.length) return <EmptyState compact icon={ClipboardList} title={tx('No open production orders')} />;
  return (
    <TabCard>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{tx('Order')}</TableHead>
            <TableHead>{tx('Status')}</TableHead>
            <TableHead className="text-right">{tx('Planned qty')}</TableHead>
            <TableHead>{tx('BOM version')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {item.openOrders.map((o) => (
            <TableRow key={o.id}>
              <TableCell><Link to={`/manufacturing/orders/${o.id}`} className="font-mono text-xs text-primary hover:underline">{o.orderCode}</Link></TableCell>
              <TableCell><StatusPill tone={o.status === 'in_progress' ? 'info' : o.status === 'qc_hold' ? 'warning' : 'neutral'}>{human(o.status)}</StatusPill></TableCell>
              <TableCell className="text-right tabular-nums">{qty(o.plannedQty)}</TableCell>
              <TableCell>{o.bomVersion != null ? `v${o.bomVersion}` : '—'}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TabCard>
  );
}

function HistoryTab({ item }: { item: PlmItemDetail }) {
  if (!item.history.length) return <EmptyState compact icon={History} title={tx('No history')} />;
  return (
    <div className="rounded-xl border bg-card p-4 shadow-sm">
      <ol className="relative space-y-4 border-l pl-6">
        {item.history.map((h) => (
          <li key={h.id} className="relative">
            <span className={cn('absolute -left-[31px] top-0.5 flex h-5 w-5 items-center justify-center rounded-full border-2 border-background',
              h.kind === 'revision' ? 'bg-violet-500' : h.kind === 'registered' ? 'bg-slate-400' : (h.toState ? LIFECYCLE[h.toState]?.bar : 'bg-primary'))}>
              {h.kind === 'revision' ? <Tag className="h-2.5 w-2.5 text-white" /> : <ArrowRight className="h-2.5 w-2.5 text-white" />}
            </span>
            <div className="flex flex-wrap items-center gap-2 text-sm">
              {h.kind === 'registered' && <><span className="font-medium">{tx('Registered')}</span><LifecyclePill state={h.toState} />{h.toRevision && <RevisionBadge revision={h.toRevision} />}</>}
              {h.kind === 'transition' && <><LifecyclePill state={h.fromState} /><ArrowRight className="h-3.5 w-3.5 text-muted-foreground" /><LifecyclePill state={h.toState} /></>}
              {h.kind === 'revision' && <><span className="font-medium">{tx('Revision')}</span><RevisionBadge revision={h.fromRevision} /><ArrowRight className="h-3.5 w-3.5 text-muted-foreground" /><RevisionBadge revision={h.toRevision} /></>}
              {h.changeId && <Link to={`/manufacturing/plm/changes/${h.changeId}`} className="text-xs text-primary hover:underline">{tx('View change')}</Link>}
            </div>
            {h.note && <p className="mt-0.5 text-sm text-muted-foreground">{h.note}</p>}
            <p className="mt-0.5 text-xs text-muted-foreground">{h.userName ? `${h.userName} · ` : ''}{dayTime(h.createdAt)}</p>
          </li>
        ))}
      </ol>
    </div>
  );
}

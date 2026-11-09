import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  Activity, ArrowRight, CheckCircle2, ClipboardPen, Clock, FileText, GitBranch, GitPullRequest, Hourglass, Inbox,
  MessageSquare, Package, Plus, ShieldCheck, Stamp,
} from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list/list-page';
import { useAuthStore } from '@/stores/auth.store';
import { num, usePlmOverview, type LifecycleState } from '@/features/manufacturing/api-plm';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { Avatar, EmptyState, Panel, PlmSubNav, PriorityPill, StatCard } from './plm-ui';
import { ChangeDialog } from './change-dialogs';
import { RequestDialog } from './request-dialogs';
import { DOC_STATUS, LIFECYCLE, LIFECYCLE_ORDER, REQUEST_STATUS, ago } from './vocab';

const ACTIVITY_ICON: Record<string, typeof Activity> = {
  created: Plus,
  edited: ClipboardPen,
  stage: ArrowRight,
  approval: ShieldCheck,
  applied: CheckCircle2,
  status: Clock,
  comment: MessageSquare,
};

/** PLM dashboard: lifecycle distribution, the ECO pipeline, my approvals and the latest activity. */
export function PlmOverviewPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data, isLoading } = usePlmOverview();
  const [newChange, setNewChange] = useState(false);
  const [newRequest, setNewRequest] = useState(false);

  const openChanges = data ? num(data.changes.byStatus.draft) + num(data.changes.byStatus.approved) : 0;
  const openRequests = data ? num(data.requests.byStatus.new) + num(data.requests.byStatus.accepted) : 0;
  const overdue = data ? num(data.requests.overdue) : 0;
  const maxState = data ? Math.max(1, ...LIFECYCLE_ORDER.map((s) => num(data.lifecycle[s]))) : 1;
  const maxStage = data ? Math.max(1, ...data.changes.byStage.map((s) => num(s.count))) : 1;

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('Product lifecycle')}
        description={tx('Products from concept to obsolete, with controlled engineering changes and released documents.')}
        icon={GitBranch}
        actions={(
          <>
            {has(PERMISSIONS.plm.request) && (
              <Button variant="outline" onClick={() => setNewRequest(true)}><Inbox className="mr-1 h-4 w-4" />{tx('New request')}</Button>
            )}
            {has(PERMISSIONS.plm.change) && (
              <Button onClick={() => setNewChange(true)}><Plus className="mr-1 h-4 w-4" />{tx('New change')}</Button>
            )}
          </>
        )}
      />
      <PlmSubNav />

      {isLoading || !data ? (
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">{Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-24 rounded-xl" />)}</div>
          <Skeleton className="h-64 rounded-xl" />
        </div>
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
            <StatCard label={tx('Products under PLM')} value={num(data.items)} icon={Package}
              hint={tx('{{n}} released', { n: num(data.lifecycle.released) })} onClick={() => navigate('/manufacturing/plm/products')} />
            <StatCard label={tx('Open change orders')} value={openChanges} icon={GitPullRequest}
              hint={tx('{{n}} approved, waiting to apply', { n: num(data.changes.byStatus.approved) })} onClick={() => navigate('/manufacturing/plm/changes')} />
            <StatCard label={tx('Applied, last 30 days')} value={num(data.changes.appliedLast30Days)} icon={CheckCircle2} tone="success" />
            <StatCard label={tx('Average cycle time')} icon={Hourglass}
              value={data.changes.avgCycleDays == null ? '—' : tx('{{n}} d', { n: num(data.changes.avgCycleDays) })}
              hint={tx('Created → applied, last 180 days')} />
            <StatCard label={tx('Open change requests')} value={openRequests} icon={Inbox}
              tone={overdue > 0 ? 'danger' : undefined}
              hint={overdue > 0 ? tx('{{n}} overdue', { n: overdue }) : tx('None overdue')}
              onClick={() => navigate('/manufacturing/plm/requests')} />
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Panel title={tx('Lifecycle')} icon={Package}>
              {num(data.items) === 0 ? (
                <EmptyState compact icon={Package} title={tx('No products under PLM yet')}
                  hint={tx('Register products, or register every product with an active BOM in one go.')}
                  action={<Button size="sm" variant="outline" asChild><Link to="/manufacturing/plm/products">{tx('Go to products')}</Link></Button>} />
              ) : (
                <div className="space-y-2.5">
                  {LIFECYCLE_ORDER.map((s: LifecycleState) => {
                    const n = num(data.lifecycle[s]);
                    return (
                      <button key={s} type="button" onClick={() => navigate(`/manufacturing/plm/products?state=${s}`)}
                        className="group grid w-full grid-cols-[96px_1fr_40px] items-center gap-3 text-left text-sm">
                        <span className="text-muted-foreground group-hover:text-foreground">{LIFECYCLE[s].label}</span>
                        <span className="h-2.5 overflow-hidden rounded-full bg-muted">
                          <span className={cn('block h-full rounded-full transition-all group-hover:opacity-80', LIFECYCLE[s].bar)}
                            style={{ width: `${(n / maxState) * 100}%` }} />
                        </span>
                        <span className="text-right font-medium tabular-nums">{n}</span>
                      </button>
                    );
                  })}
                </div>
              )}
            </Panel>

            <Panel title={tx('Change pipeline')} icon={GitPullRequest}
              actions={<Link to="/manufacturing/plm/changes" className="text-xs text-primary hover:underline">{tx('Open board')}</Link>}>
              <div className="flex items-stretch gap-1 overflow-x-auto pb-1">
                {data.changes.byStage.map((s, i) => (
                  <div key={s.id} className="flex min-w-[110px] flex-1 items-center gap-1">
                    <Link to="/manufacturing/plm/changes" className="flex-1 rounded-lg border bg-muted/30 p-3 text-center hover:border-primary/40">
                      <p className="truncate text-xs text-muted-foreground">{s.name}</p>
                      <p className="text-2xl font-semibold tabular-nums">{num(s.count)}</p>
                      <span className="mt-1 block h-1 overflow-hidden rounded-full bg-muted">
                        <span className="block h-full rounded-full bg-primary" style={{ width: `${(num(s.count) / maxStage) * 100}%` }} />
                      </span>
                    </Link>
                    {i < data.changes.byStage.length - 1 && <ArrowRight className="h-4 w-4 shrink-0 text-muted-foreground" />}
                  </div>
                ))}
              </div>
              <div className="mt-4 grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
                {(['new', 'accepted', 'rejected', 'converted'] as const).map((k) => (
                  <div key={k} className="rounded-md bg-muted/30 px-3 py-2">
                    <p className="text-xs text-muted-foreground">{tx('Requests')} · {REQUEST_STATUS[k].label}</p>
                    <p className="font-semibold tabular-nums">{num(data.requests.byStatus[k])}</p>
                  </div>
                ))}
              </div>
              <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
                <span className="flex items-center gap-1"><FileText className="h-3.5 w-3.5" />{tx('Documents')}:</span>
                {(['draft', 'released', 'superseded', 'obsolete'] as const).map((k) => (
                  <span key={k}>{DOC_STATUS[k].label} <b className="text-foreground">{num(data.documents[k])}</b></span>
                ))}
              </div>
            </Panel>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Panel title={tx('Awaiting my approval')} icon={Stamp} bodyClassName="p-0">
              {data.awaitingMyApproval.length === 0 ? (
                <p className="px-4 py-8 text-center text-sm text-muted-foreground">{tx('Nothing is waiting for your approval.')}</p>
              ) : (
                <ul className="divide-y">
                  {data.awaitingMyApproval.map((c) => (
                    <li key={c.id}>
                      <Link to={`/manufacturing/plm/changes/${c.id}`} className="flex items-center gap-3 px-4 py-2.5 hover:bg-muted/40">
                        <span className="font-mono text-xs text-muted-foreground">{c.number}</span>
                        <span className="min-w-0 flex-1 truncate text-sm font-medium">{c.title}</span>
                        <PriorityPill priority={c.priority} hideNormal />
                        <span className="hidden rounded bg-muted px-1.5 py-0.5 text-xs sm:inline">{c.stageName}</span>
                        <span className="text-xs text-muted-foreground">{ago(c.createdAt)}</span>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>

            <Panel title={tx('Recent activity')} icon={Activity} bodyClassName="p-0">
              {data.recentActivity.length === 0 ? (
                <p className="px-4 py-8 text-center text-sm text-muted-foreground">{tx('No change activity yet.')}</p>
              ) : (
                <ul className="divide-y">
                  {data.recentActivity.map((a) => {
                    const Icon = ACTIVITY_ICON[a.kind] ?? Activity;
                    return (
                      <li key={a.id} className="flex gap-3 px-4 py-2.5">
                        <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-muted"><Icon className="h-3.5 w-3.5 text-muted-foreground" /></span>
                        <div className="min-w-0 flex-1 text-sm">
                          <p className="leading-snug">
                            <Link to={`/manufacturing/plm/changes/${a.changeId}`} className="font-mono text-xs text-primary hover:underline">{a.number}</Link>{' '}
                            <span className="text-muted-foreground">{a.title}</span>
                          </p>
                          <p className="line-clamp-2">{a.message}</p>
                          <p className="mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground">
                            {a.userName && <><Avatar name={a.userName} className="h-4 w-4 text-[8px]" />{a.userName} ·</>} {ago(a.createdAt)}
                          </p>
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}
            </Panel>
          </div>
        </>
      )}

      <ChangeDialog open={newChange} onClose={() => setNewChange(false)} />
      <RequestDialog open={newRequest} onClose={() => setNewRequest(false)} />
    </div>
  );
}

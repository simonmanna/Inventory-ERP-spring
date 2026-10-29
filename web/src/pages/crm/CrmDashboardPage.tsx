import { useState } from 'react';
import { Link } from 'react-router-dom';
import { AlarmClock, CalendarCheck, FileClock, Flame, Gauge, KanbanSquare, Target } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useCrmDashboard, type Opportunity } from '@/features/crm/suite-api';
import { Avatar, KpiCard, Meter, colorOf, compact } from './crm-ui';

/** Sales dashboard: my (or everyone's) pipeline, monthly target, activities, approvals, closing soon and rotting deals. */
export function CrmDashboardPage() {
  const viewAll = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.dealViewAll));
  const [scope, setScope] = useState(viewAll ? 'all' : 'mine');
  const { data, isLoading } = useCrmDashboard(scope);
  const cur = data?.currency;
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Gauge} title={tx('Sales dashboard')} description={scope === 'mine' ? tx('Your pipeline at a glance') : tx('The whole team at a glance')}
        actions={viewAll && (
          <div className="flex rounded-md border bg-card p-0.5 text-sm">
            {(['mine', 'all'] as const).map((s) => <button key={s} onClick={() => setScope(s)} className={cn('rounded px-3 py-1', scope === s ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>{s === 'mine' ? tx('Mine') : tx('Team')}</button>)}
          </div>
        )} />
      {isLoading && <Skeleton className="h-80 w-full" />}
      {data && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
            <Link to="/crm/pipeline"><KpiCard label={tx('Open opportunities')} value={data.totals.openCount} sub={money(data.totals.openValue, cur)} /></Link>
            <KpiCard label={tx('Weighted pipeline')} value={compact(data.totals.weighted)} sub={tx('amount × probability')} />
            <Link to="/crm/leads"><KpiCard label={tx('Open leads')} value={data.totals.leads} /></Link>
            <Link to="/crm/activities"><KpiCard label={tx('Overdue activities')} value={data.activities.overdue} sub={`${data.activities.today} ${tx('due today')}`} tone={data.activities.overdue > 0 ? 'danger' : undefined} /></Link>
            {data.pendingApproval
              ? <Link to="/crm/quotations?status=pending_approval"><KpiCard label={tx('Quotes to approve')} value={data.pendingApproval.count} sub={money(data.pendingApproval.value, cur)} tone={data.pendingApproval.count > 0 ? 'danger' : undefined} /></Link>
              : <KpiCard label={tx('Won this month')} value={data.month.won} sub={money(data.month.wonValue, cur)} tone="success" />}
          </div>

          <div className="grid gap-4 lg:grid-cols-3">
            <div className="rounded-xl border bg-card p-4 shadow-sm">
              <h2 className="flex items-center gap-2 font-semibold"><Target className="h-4 w-4 text-primary" />{tx('This month')}</h2>
              <p className="mt-3 text-3xl font-bold tabular-nums">{money(data.month.wonValue, cur)}</p>
              <p className="text-sm text-muted-foreground">{data.month.won} {tx('deals won')}{Number(data.month.target) > 0 ? ` · ${tx('target')} ${money(data.month.target, cur)}` : ''}</p>
              {data.month.progress != null && (
                <div className="mt-3">
                  <div className="h-3 overflow-hidden rounded-full bg-muted"><div className={cn('h-full rounded-full', Number(data.month.progress) >= 100 ? 'bg-emerald-500' : 'bg-primary')} style={{ width: `${Math.min(100, Number(data.month.progress))}%` }} /></div>
                  <p className="mt-1 text-xs text-muted-foreground">{data.month.progress}% {tx('of target')}</p>
                </div>
              )}
              {Number(data.month.target) === 0 && <p className="mt-3 text-xs text-muted-foreground">{tx('Set a monthly target on the sales team to track progress.')}</p>}
            </div>
            <div className="rounded-xl border bg-card p-4 shadow-sm lg:col-span-2">
              <h2 className="mb-3 flex items-center gap-2 font-semibold"><KanbanSquare className="h-4 w-4 text-primary" />{tx('Pipeline by stage')}</h2>
              {data.byStage.map((s) => (
                <Link key={s.key} to={`/crm/pipeline?f=1&stageId=${s.key}${scope === 'mine' ? '&mine=true' : ''}&view=list`} className="grid grid-cols-[140px_1fr_40px_100px] items-center gap-2 rounded py-1.5 text-sm hover:bg-muted/40">
                  <span className="flex items-center gap-1.5 truncate"><span className={cn('h-2 w-2 rounded-full', colorOf(s.color).dot)} />{s.label}</span>
                  <Meter value={Number(s.amount)} max={Math.max(...data.byStage.map((x) => Number(x.amount)), 1)} />
                  <span className="text-right tabular-nums">{s.count}</span>
                  <span className="text-right tabular-nums">{money(s.amount, cur)}</span>
                </Link>
              ))}
              {data.byStage.length === 0 && <p className="text-sm text-muted-foreground">{tx('No open opportunities.')}</p>}
            </div>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <DealList title={tx('Closing in the next 30 days')} icon={AlarmClock} rows={data.closingSoon} empty={tx('Nothing closing soon.')} right={(o) => o.expectedClose ? date(o.expectedClose) : ''} />
            <DealList title={tx('Going cold')} icon={Flame} rows={data.rotting} empty={tx('No stale deals. 👏')} right={(o) => `${o.daysInStage} ${tx('days in')} ${o.stageName ?? ''}`} />
          </div>
          <div className="flex flex-wrap gap-2 text-sm">
            <Link className="rounded-lg border bg-card px-3 py-2 hover:bg-muted" to="/crm/activities"><CalendarCheck className="mr-1.5 inline h-4 w-4" />{tx('My activities')}</Link>
            <Link className="rounded-lg border bg-card px-3 py-2 hover:bg-muted" to="/crm/quotations"><FileClock className="mr-1.5 inline h-4 w-4" />{tx('Quotations')}</Link>
            <Link className="rounded-lg border bg-card px-3 py-2 hover:bg-muted" to="/crm/reports"><Gauge className="mr-1.5 inline h-4 w-4" />{tx('Reports')}</Link>
          </div>
        </>
      )}
    </div>
  );
}

function DealList({ title, icon: Icon, rows, empty, right }: { title: string; icon: typeof Flame; rows: Opportunity[]; empty: string; right: (o: Opportunity) => string }) {
  return (
    <div className="rounded-xl border bg-card p-4 shadow-sm">
      <h2 className="mb-2 flex items-center gap-2 font-semibold"><Icon className="h-4 w-4 text-primary" />{title}</h2>
      <div className="divide-y">
        {rows.map((o) => (
          <Link key={o.id} to={`/crm/opportunities/${o.id}`} className="flex items-center gap-3 py-2 text-sm hover:bg-muted/30">
            <Avatar name={o.ownerName} />
            <div className="min-w-0 flex-1"><p className="truncate font-medium">{o.name}</p><p className="truncate text-xs text-muted-foreground">{o.partnerName ?? o.companyName ?? ''}</p></div>
            <div className="text-right"><p className="tabular-nums">{money(o.amount, o.currencyCode)}</p><p className="text-xs text-muted-foreground">{right(o)}</p></div>
          </Link>
        ))}
        {rows.length === 0 && <p className="py-3 text-sm text-muted-foreground">{empty}</p>}
      </div>
    </div>
  );
}

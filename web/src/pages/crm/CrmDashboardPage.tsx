import { useState, type CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import {
  AlarmClock, ArrowRight, CalendarCheck, FileCheck2, FileClock, Flame, Gauge, Inbox, KanbanSquare, Loader2, Sparkles, Trophy,
  type LucideIcon,
} from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Skeleton } from '@/components/ui/skeleton';
import { useAuthStore } from '@/stores/auth.store';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { canReadQuotes, quoteUi, useCrmDashboard, type Opportunity } from '@/features/crm/suite-api';
import { Avatar, EmptyState, ErrorState, KpiCard, Panel, ProgressRing, Reveal, colorOf, compact } from './crm-ui';

function greeting(): string {
  const h = new Date().getHours();
  return h < 12 ? tx('Good morning') : h < 18 ? tx('Good afternoon') : tx('Good evening');
}

const hairline = 'color-mix(in srgb, var(--sb-text) 16%, transparent)';
const heroStyle: CSSProperties = {
  background: 'radial-gradient(90% 130% at 100% 0%, color-mix(in srgb, var(--sb-active-bar) 18%, transparent) 0%, transparent 60%), var(--sb-sidebar)',
  color: 'var(--sb-active)',
};

/** Sales dashboard: my (or everyone's) pipeline, monthly target, activities, approvals, closing soon and rotting deals. */
export function CrmDashboardPage() {
  const viewAll = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.dealViewAll));
  const canQuotes = useAuthStore((s) => canReadQuotes(s.hasPermission));
  const canReports = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.reportRead));
  const user = useAuthStore((s) => s.user);
  const [scope, setScope] = useState(viewAll ? 'all' : 'mine');
  const { data, isLoading, isError, isFetching, refetch } = useCrmDashboard(scope);
  const cur = data?.currency;
  const progress = data?.month.progress != null ? Number(data.month.progress) : null;
  const hasTarget = Number(data?.month.target ?? 0) > 0;
  const stageMax = Math.max(1, ...(data?.byStage ?? []).map((x) => Number(x.amount)));

  return (
    <div className="space-y-5">
      <Reveal>
        <section className="overflow-hidden rounded-2xl p-5 shadow-lg md:p-7" style={heroStyle}>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h1 className="flex items-center gap-2 text-xl font-semibold">
                <Gauge className="h-5 w-5" style={{ color: 'var(--sb-active-bar)' }} />{tx('Sales dashboard')}
                {isFetching && !isLoading && <Loader2 className="h-4 w-4 animate-spin" style={{ color: 'var(--sb-text)' }} />}
              </h1>
              <p className="text-sm" style={{ color: 'var(--sb-text)' }}>
                {greeting()}{user?.firstName ? `, ${user.firstName}` : ''}. {scope === 'mine' ? tx('Your pipeline at a glance') : tx('The whole team at a glance')}
              </p>
            </div>
            {viewAll && (
              <div className="flex rounded-lg border p-0.5" style={{ borderColor: hairline }} role="tablist">
                {(['mine', 'all'] as const).map((s) => {
                  const on = scope === s;
                  return (
                    <button key={s} role="tab" aria-selected={on} onClick={() => setScope(s)}
                      className={cn('press rounded-md px-3 py-1 text-xs font-medium', !on && 'hover:bg-[var(--sb-hover)]')}
                      style={on ? { background: 'var(--sb-active-bar)', color: '#1f2023' } : { color: 'var(--sb-text)' }}>
                      {s === 'mine' ? tx('Mine') : tx('Team')}
                    </button>
                  );
                })}
              </div>
            )}
          </div>

          {isLoading && <Skeleton className="mt-6 h-36 w-full opacity-20" />}
          {data && (
            <div className="mt-6 grid gap-6 lg:grid-cols-[auto_1fr] lg:items-center">
              <div className="flex items-center gap-5">
                <ProgressRing percent={progress ?? 0} size={124} stroke={11} trackClass="[stroke:color-mix(in_srgb,var(--sb-text)_18%,transparent)]" barClass="[stroke:var(--sb-active-bar)]">
                  {hasTarget
                    ? <><span className="text-2xl font-semibold tabular-nums">{Math.round(progress ?? 0)}%</span><span className="text-[11px]" style={{ color: 'var(--sb-text)' }}>{tx('of target')}</span></>
                    : <Trophy className="h-7 w-7" style={{ color: 'var(--sb-active-bar)' }} />}
                </ProgressRing>
                <div className="min-w-0">
                  <p className="text-sm" style={{ color: 'var(--sb-text)' }}>{tx('Won this month')}</p>
                  <p className="text-3xl font-semibold tracking-tight tabular-nums md:text-4xl">{money(data.month.wonValue, cur)}</p>
                  <p className="mt-0.5 text-sm" style={{ color: 'var(--sb-text)' }}>
                    {data.month.won} {tx('deals won')}{hasTarget ? ` · ${tx('target')} ${money(data.month.target, cur)}` : ''}
                  </p>
                  {!hasTarget && <p className="mt-1 text-xs" style={{ color: 'var(--sb-text)' }}>{tx('Set a monthly target on the sales team to track progress.')}</p>}
                </div>
              </div>
              <div className="grid grid-cols-2 gap-2 xl:grid-cols-4">
                <HeroStat to="/crm/leads" icon={Inbox} label={tx('Open leads')} value={String(data.totals.leads)} />
                <HeroStat to="/crm/pipeline" icon={KanbanSquare} label={tx('Open opportunities')} value={String(data.totals.openCount)} sub={money(data.totals.openValue, cur)} />
                <HeroStat to="/crm/pipeline?view=list" icon={Sparkles} label={tx('Weighted pipeline')} value={compact(data.totals.weighted)} sub={tx('amount × probability')} />
                <HeroStat to="/crm/activities" icon={CalendarCheck} label={tx('Overdue activities')} value={String(data.activities.overdue)}
                  sub={`${data.activities.today} ${tx('due today')}`} alert={data.activities.overdue > 0} />
              </div>
            </div>
          )}
        </section>
      </Reveal>

      {isError && <ErrorState message={tx('Could not load the dashboard.')} onRetry={() => refetch()} />}
      {isLoading && <div className="grid gap-4 lg:grid-cols-3"><Skeleton className="h-64 lg:col-span-2" /><Skeleton className="h-64" /></div>}

      {data && (
        <>
          <Reveal delay={80}>
            <div className="grid gap-4 lg:grid-cols-3">
              <Panel title={tx('Pipeline by stage')} icon={KanbanSquare} hint={tx('Open value per stage; click a stage to see its deals.')} href="/crm/pipeline" action={tx('Open pipeline')} className="lg:col-span-2">
                {data.byStage.length === 0
                  ? <EmptyState icon={KanbanSquare} title={tx('No open opportunities.')} hint={tx('New opportunities show up here by stage.')} />
                  : (
                    <ol className="space-y-1">
                      {data.byStage.map((s, i) => (
                        <li key={s.key}>
                          <Link to={`/crm/pipeline?f=1&stageId=${s.key}${scope === 'mine' ? '&mine=true' : ''}&view=list`}
                            className="group grid grid-cols-[minmax(90px,140px)_1fr_auto] items-center gap-3 rounded-lg px-2 py-2 text-sm transition-colors hover:bg-muted/50">
                            <span className="flex min-w-0 items-center gap-2"><span className={cn('h-2.5 w-2.5 shrink-0 rounded-full', colorOf(s.color).dot)} /><span className="truncate font-medium">{s.label}</span></span>
                            <span className="relative h-7 overflow-hidden rounded-md bg-muted/60">
                              <span className={cn('grow-x absolute inset-y-0 left-0 rounded-md opacity-80 transition-opacity group-hover:opacity-100', colorOf(s.color).bar)}
                                style={{ width: `${Math.max(3, (Number(s.amount) / stageMax) * 100)}%`, animationDelay: `${120 + i * 70}ms` }} />
                              <span className="relative flex h-full items-center px-2 text-xs font-medium tabular-nums">{s.count} {s.count === 1 ? tx('deal') : tx('deals')}</span>
                            </span>
                            <span className="w-24 text-right font-medium tabular-nums">{compact(s.amount)}</span>
                          </Link>
                        </li>
                      ))}
                    </ol>
                  )}
              </Panel>
              <div className="grid gap-4">
                {data.pendingApproval
                  ? <KpiCard to={`${quoteUi()}?status=pending_approval`} icon={FileClock} label={tx('Quotes to approve')} value={data.pendingApproval.count}
                    sub={money(data.pendingApproval.value, cur)} tone={data.pendingApproval.count > 0 ? 'danger' : undefined} />
                  : <KpiCard icon={Trophy} label={tx('Won this month')} value={data.month.won} sub={money(data.month.wonValue, cur)} tone="success" />}
                <nav className="grid gap-2 rounded-2xl border bg-card p-3 shadow-sm" aria-label={tx('Shortcuts')}>
                  <Shortcut to="/crm/activities" icon={CalendarCheck} label={tx('My activities')} />
                  {canQuotes && <Shortcut to={quoteUi()} icon={FileCheck2} label={tx('Quotations')} />}
                  {canReports && <Shortcut to="/crm/reports" icon={Gauge} label={tx('Reports')} />}
                </nav>
              </div>
            </div>
          </Reveal>

          <Reveal delay={160}>
            <div className="grid gap-4 lg:grid-cols-2">
              <DealList title={tx('Closing in the next 30 days')} icon={AlarmClock} rows={data.closingSoon} empty={tx('Nothing closing soon.')}
                right={(o) => o.expectedClose ? date(o.expectedClose) : ''} />
              <DealList title={tx('Going cold')} icon={Flame} rows={data.rotting} empty={tx('No stale deals. 👏')}
                right={(o) => `${o.daysInStage} ${tx('days in')} ${o.stageName ?? ''}`} hot />
            </div>
          </Reveal>
        </>
      )}
    </div>
  );
}

function HeroStat({ to, icon: Icon, label, value, sub, alert }: { to: string; icon: LucideIcon; label: string; value: string; sub?: string; alert?: boolean }) {
  return (
    <Link to={to} className="press group flex flex-col gap-1 rounded-xl border p-3 transition-colors hover:bg-[var(--sb-hover)]" style={{ borderColor: hairline }}>
      <span className="flex items-center justify-between gap-2 text-xs" style={{ color: 'var(--sb-text)' }}>
        <span className="truncate">{label}</span>
        <Icon className="h-3.5 w-3.5 shrink-0 transition-transform duration-300 group-hover:scale-125" style={{ color: alert ? '#fb7185' : 'var(--sb-active-bar)' }} />
      </span>
      <span className={cn('text-xl font-semibold tabular-nums', alert && 'text-rose-400')}>{value}</span>
      {sub && <span className="truncate text-xs" style={{ color: 'var(--sb-text)' }}>{sub}</span>}
    </Link>
  );
}

function Shortcut({ to, icon: Icon, label }: { to: string; icon: LucideIcon; label: string }) {
  return (
    <Link to={to} className="press group flex items-center gap-3 rounded-xl px-3 py-2 text-sm font-medium transition-colors hover:bg-muted">
      <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10 text-primary"><Icon className="h-4 w-4" /></span>
      <span className="flex-1">{label}</span>
      <ArrowRight className="h-4 w-4 text-muted-foreground transition-transform duration-200 group-hover:translate-x-0.5" />
    </Link>
  );
}

function DealList({ title, icon, rows, empty, right, hot }: {
  title: string; icon: LucideIcon; rows: Opportunity[]; empty: string; right: (o: Opportunity) => string; hot?: boolean;
}) {
  return (
    <Panel title={title} icon={icon}>
      {rows.length === 0
        ? <EmptyState icon={icon} title={empty} className="py-6" />
        : (
          <ul className="-mx-2 divide-y">
            {rows.map((o, i) => (
              <li key={o.id} className="animate-in fade-in slide-in-from-left-1 fill-mode-both duration-300 motion-reduce:animate-none" style={{ animationDelay: `${200 + i * 40}ms` }}>
                <Link to={`/crm/opportunities/${o.id}`} className="flex items-center gap-3 rounded-lg px-2 py-2.5 text-sm transition-colors hover:bg-muted/50">
                  <Avatar name={o.ownerName} className="h-8 w-8 text-[11px]" />
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-medium">{o.name}</p>
                    <p className="truncate text-xs text-muted-foreground">{o.partnerName ?? o.companyName ?? ''}</p>
                  </div>
                  <div className="text-right">
                    <p className="font-medium tabular-nums">{money(o.amount, o.currencyCode)}</p>
                    <p className={cn('text-xs', hot ? 'text-rose-600 dark:text-rose-400' : 'text-muted-foreground')}>{right(o)}</p>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        )}
    </Panel>
  );
}

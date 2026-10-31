import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import { useDashboard } from './api';
import { AnalyticsFilterBar } from './AnalyticsFilterBar';
import { DashboardView, type Drill } from './DashboardView';
import { DrillDownDialog } from './DrillDownDialog';
import { analyticsDomains } from './domains';
import { useAnalyticsFilters } from './use-analytics-filters';

/** Links to the dashboards this user may open; filters carry over. */
export function DomainTabs({ current, search }: { current?: string; search: string }) {
  const has = useAuthStore((s) => s.hasPermission);
  const qs = search ? `?${search}` : '';
  const tab = (active: boolean) => `whitespace-nowrap rounded-md px-3 py-1.5 text-sm ${active
    ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted'}`;
  return (
    <nav className="flex gap-1 overflow-x-auto" aria-label={tx('Dashboards')}>
      <Link className={tab(!current)} to={`/analytics${qs}`}>{tx('Overview')}</Link>
      {analyticsDomains().filter((d) => has(d.permission)).map((d) => (
        <Link key={d.path} className={tab(current === d.path)} to={`/analytics/${d.path}${qs}`}>{d.label}</Link>
      ))}
    </nav>
  );
}


/** One dashboard: filters, tiles, charts, tables, drill-down (ADR-S020). */
export function AnalyticsDomainPage({ path }: { path: string }) {
  const domain = analyticsDomains().find((d) => d.path === path);
  const { query, update, search } = useAnalyticsFilters();
  const { data, isLoading, isError, isFetching } = useDashboard(path, query);
  const [drill, setDrill] = useState<Drill | null>(null);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={domain?.icon} title={domain?.label ?? path}
        description={tx('Figures for the selected period and filters. Click a figure to see its documents.')} />
      <DomainTabs current={path} search={search} />
      <AnalyticsFilterBar query={query} update={update} supported={data?.supportedFilters ?? ['date']}
        suppliers={path === 'procurement'} />
      {isLoading && <div className="grid grid-cols-2 gap-3 sm:grid-cols-4"><Skeleton className="h-20" /><Skeleton className="h-20" /><Skeleton className="h-20" /><Skeleton className="h-20" /></div>}
      {isError && <p className="text-sm text-destructive">{tx('Could not load this dashboard. Check the filters, or ask whether the module is enabled.')}</p>}
      {data && (
        <div className={isFetching ? 'opacity-70' : undefined}>
          <DashboardView dashboard={data} onDrill={setDrill} />
        </div>
      )}
      {drill && <DrillDownDialog path={path} drill={drill} query={query} onClose={() => setDrill(null)} />}
    </div>
  );
}

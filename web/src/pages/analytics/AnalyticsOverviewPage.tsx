import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ListPageHeader } from '@/components/list';
import { Skeleton } from '@/components/ui/skeleton';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import { useDashboard, type AnalyticsQuery } from './api';
import { AnalyticsFilterBar } from './AnalyticsFilterBar';
import { DomainTabs } from './AnalyticsDomainPage';
import { TileGrid, type Drill } from './DashboardView';
import { DrillDownDialog } from './DrillDownDialog';
import { AnalyticsIcon, analyticsDomains, type Domain } from './domains';
import { useAnalyticsFilters } from './use-analytics-filters';

const HEADLINE_TILES = 4;

/** A dashboard's first tiles; a dashboard that cannot load (module off) stays out of the way. */
function DomainHeadlines({ domain, query, search, onDrill }: {
  domain: Domain; query: AnalyticsQuery; search: string; onDrill: (path: string, d: Drill) => void;
}) {
  const { data, isLoading, isError } = useDashboard(domain.path, query);
  if (isError) return null;
  const qs = search ? `?${search}` : '';
  return (
    <section className="space-y-2">
      <Link to={`/analytics/${domain.path}${qs}`} className="text-sm font-medium hover:underline">{domain.label}</Link>
      {isLoading && <Skeleton className="h-20" />}
      {data && <TileGrid tiles={data.tiles.slice(0, HEADLINE_TILES)} onDrill={(d) => onDrill(domain.path, d)} />}
    </section>
  );
}

/** Every dashboard this user may see, headline figures only. Each reads its own route, so permissions apply. */
export function AnalyticsOverviewPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const { query, update, search } = useAnalyticsFilters();
  const [drill, setDrill] = useState<{ path: string; drill: Drill } | null>(null);
  const shared: AnalyticsQuery = { from: query.from, to: query.to, branchId: query.branchId };
  const domains = analyticsDomains().filter((d) => has(d.permission));
  return (
    <div className="space-y-4">
      <ListPageHeader icon={AnalyticsIcon} title={tx('Management Dashboards')}
        description={tx('Headline figures across the business for the selected period.')} />
      <DomainTabs search={search} />
      <AnalyticsFilterBar query={query} update={update} supported={['date', 'branch']} />
      {domains.length === 0 && <p className="text-sm text-muted-foreground">{tx('No dashboard has been granted to your role yet.')}</p>}
      {domains.map((d) => (
        <DomainHeadlines key={d.path} domain={d} query={shared} search={search}
          onDrill={(path, x) => setDrill({ path, drill: x })} />
      ))}
      {drill && <DrillDownDialog path={drill.path} drill={drill.drill} query={shared} onClose={() => setDrill(null)} />}
    </div>
  );
}

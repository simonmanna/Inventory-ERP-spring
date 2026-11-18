import { Factory, Store, Users, type LucideIcon } from 'lucide-react';
import { useParams } from 'react-router-dom';
import { Skeleton } from '@/components/ui/skeleton';
import { SupersetDashboard } from '@/components/bi/SupersetDashboard';
import { useBiDashboards } from '@/components/bi/use-bi-dashboards';
import { ListPageHeader } from '@/components/list';
import { tx } from '@/lib/i18n/tx';
import { DomainTabs } from './AnalyticsDomainPage';
import { AnalyticsIcon } from './domains';

const ICONS: Record<string, LucideIcon> = { pos: Store, hr: Users, manufacturing: Factory };

/**
 * A module that has only an embedded Superset dashboard, no built-in one (ADR-S078 B1: POS, HR, Manufacturing). The
 * API decides who may open it: the list holds only configured modules the user has the permission for.
 */
export function BiModuleDashboardPage() {
  const { module = '' } = useParams();
  const { data, isLoading, isError } = useBiDashboards();
  const entry = data?.find((d) => d.module === module);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={ICONS[module] ?? AnalyticsIcon} title={entry ? tx(entry.label) : tx('Dashboard')}
        description={tx('Explore the figures: filter, drill into a chart, or download its data.')} />
      <DomainTabs current={module} search="" />
      {isLoading && <Skeleton className="h-96" />}
      {isError && <p className="text-sm text-destructive">{tx('Could not load the dashboards.')}</p>}
      {data && !entry && (
        <p className="text-sm text-muted-foreground">
          {tx('This dashboard is not set up, or you do not have permission to open it.')}
        </p>
      )}
      {entry && <SupersetDashboard module={module} />}
    </div>
  );
}

import { Link } from 'react-router-dom';
import { Bomb, ClipboardList, Mountain, Plus, Truck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useQuarryOverview } from '@/features/quarry/api';
import { StatTile, tonnes } from './quarry-ui';

/** Quarry overview (2.2): month-to-date tonnes and cost per tonne, today, drafts, explosive approvals, ROM stock. */
export function QuarryDashboardPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const { data: o, isLoading, isError } = useQuarryOverview();
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Mountain} title={tx('Quarry Overview')}
        description={tx('Extraction, blasting and run-of-mine stock across your quarry sites.')}
        actions={
          <>
            {has(PERMISSIONS.quarryExtraction.haul) && (
              <Button variant="outline" asChild><Link to="/quarry/haulage"><Truck className="mr-1.5 h-4 w-4" /> {tx('Log haulage')}</Link></Button>
            )}
            {has(PERMISSIONS.quarryExtraction.create) && (
              <Button asChild><Link to="/quarry/extraction/new"><Plus className="mr-1.5 h-4 w-4" /> {tx('New extraction log')}</Link></Button>
            )}
          </>
        } />
      {isLoading && <div className="grid grid-cols-2 gap-3 sm:grid-cols-4"><Skeleton className="h-20" /><Skeleton className="h-20" /></div>}
      {isError && <p className="text-sm text-destructive">{tx('Could not load the overview. Try again.')}</p>}
      {o && (
        <>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <StatTile label={tx('Extracted this month')} value={tonnes(o.month.netTonnes)} hint={tx('{{count}} posted logs', { count: o.month.logs })} />
            <StatTile label={tx('Cost per tonne (month)')} value={o.month.costPerTonne ? money(o.month.costPerTonne) : '—'} hint={money(o.month.totalCost)} />
            <StatTile label={tx('Extracted today')} value={tonnes(o.todayTonnes)} hint={tx('{{count}} haulage records today', { count: o.counts.haulsToday })} />
            <StatTile label={tx('ROM stock on hand')} value={tonnes(o.counts.romTonnes)} />
            <StatTile label={tx('Losses this month')} value={tonnes(o.month.lossTonnes)} hint={`${tx('Gross')} ${tonnes(o.month.grossTonnes)}`} />
            <StatTile label={tx('Active targets')} value={tonnes(o.activeTargetTonnes)} />
            <StatTile label={tx('Drafts to post')} value={o.counts.drafts} tone={o.counts.drafts > 0 ? 'warning' : undefined} />
            <StatTile label={tx('Explosives awaiting approval')} value={o.counts.pendingExplosiveApprovals}
              tone={o.counts.pendingExplosiveApprovals > 0 ? 'warning' : undefined}
              hint={tx('{{count}} approved, not issued', { count: o.counts.approvedToIssue })} />
          </div>
          <div className="grid gap-3 sm:grid-cols-3">
            <Link to="/quarry/extraction" className="flex items-center gap-3 rounded-xl border bg-card p-4 shadow-sm hover:border-primary/50">
              <ClipboardList className="h-5 w-5 text-primary" /> <span>{tx('Extraction logs')}</span>
            </Link>
            <Link to="/quarry/blasts" className="flex items-center gap-3 rounded-xl border bg-card p-4 shadow-sm hover:border-primary/50">
              <Bomb className="h-5 w-5 text-primary" /> <span>{tx('Blasts ({{count}} open)', { count: o.counts.openBlasts })}</span>
            </Link>
            <Link to="/quarry/stockpiles" className="flex items-center gap-3 rounded-xl border bg-card p-4 shadow-sm hover:border-primary/50">
              <Mountain className="h-5 w-5 text-primary" /> <span>{tx('ROM stockpiles')}</span>
            </Link>
          </div>
        </>
      )}
    </div>
  );
}

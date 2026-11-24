import { Link, useNavigate } from 'react-router-dom';
import { AlertTriangle, BellRing, ClipboardCheck, LayoutDashboard, ListChecks, ShieldAlert } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { CHECK_TYPE_LABEL, useQcOverview, type QcCheck } from '@/features/quality-control/api';
import { BlocksPill, CheckStatusPill } from './qc-ui';

/** Quality-control overview (ADR-S087): checks to do, blocking checks, recent failures. */
export function QcOverviewPage() {
  const { data, isLoading } = useQcOverview();
  const tiles = [
    { label: tx('Checks to do'), value: data?.byStatus.pending, icon: ListChecks, to: '/quality-control/checks?status=pending' },
    { label: tx('Blocking an operation'), value: data?.blocking, icon: ShieldAlert, to: '/quality-control/checks?status=pending' },
    { label: tx('Failed (30 days)'), value: data?.failedLast30Days, icon: AlertTriangle, to: '/quality-control/checks?status=failed' },
    { label: tx('Open alerts'), value: data?.openAlerts, icon: BellRing, to: '/quality-control/alerts' },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={LayoutDashboard} title={tx('Quality Control')}
        description={tx('Operator quality checks on receipts, production, work orders and deliveries.')} />
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {tiles.map((t) => (
          <Link key={t.label} to={t.to} className="rounded-xl border bg-card p-4 shadow-sm transition-colors hover:bg-muted/40">
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <span>{t.label}</span><t.icon className="h-4 w-4" />
            </div>
            <div className="mt-2 text-2xl font-semibold tabular-nums">{isLoading ? <Skeleton className="h-7 w-12" /> : t.value ?? 0}</div>
          </Link>
        ))}
      </div>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <CheckList title={tx('Checks to do')} icon={ClipboardCheck} rows={data?.pending} loading={isLoading} empty={tx('Nothing to check.')} />
        <CheckList title={tx('Recent failures')} icon={AlertTriangle} rows={data?.recentFailures} loading={isLoading} empty={tx('No failed checks.')} />
      </div>
    </div>
  );
}

function CheckList({ title, icon: Icon, rows, loading, empty }: {
  title: string; icon: typeof ListChecks; rows?: QcCheck[]; loading: boolean; empty: string;
}) {
  const navigate = useNavigate();
  return (
    <Card>
      <CardHeader className="pb-2"><CardTitle className="flex items-center gap-2 text-base"><Icon className="h-4 w-4" /> {title}</CardTitle></CardHeader>
      <CardContent className="space-y-1">
        {loading && <Skeleton className="h-24 w-full" />}
        {!loading && !rows?.length && <p className="py-6 text-center text-sm text-muted-foreground">{empty}</p>}
        {rows?.map((c) => (
          <button key={c.id} type="button" onClick={() => navigate(`/quality-control/checks/${c.id}`)}
            className="flex w-full items-center justify-between gap-3 rounded-md px-2 py-2 text-left text-sm hover:bg-muted/50">
            <div className="min-w-0">
              <div className="font-medium">{c.checkNumber} · <span className="text-muted-foreground">{CHECK_TYPE_LABEL[c.checkType]}</span></div>
              <div className="truncate text-xs text-muted-foreground">{c.title}{c.productName ? ` · ${c.productName}` : ''}{c.sourceRef ? ` · ${c.sourceRef}` : ''}</div>
            </div>
            <div className="flex shrink-0 items-center gap-2">
              <BlocksPill blocks={c.blocksSource} />
              <CheckStatusPill status={c.status} />
              <span className="hidden text-xs text-muted-foreground sm:inline">{dateTime(c.doneAt ?? c.createdAt)}</span>
            </div>
          </button>
        ))}
      </CardContent>
    </Card>
  );
}

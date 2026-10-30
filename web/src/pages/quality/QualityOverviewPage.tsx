import { Link } from 'react-router-dom';
import { ShieldCheck } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { ListPageHeader } from '@/components/list';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useInspectionLots, useNcrs, useOverdueCapa, useQualityReport } from '@/features/quality/api';
import { LotStatusPill, NcrStatusPill, ResultPill, SeverityPill } from './quality-ui';
import { num } from './quality-format';

/** Quality overview (ADR-S013): lots waiting for a decision, open NCRs, overdue CAPA actions, 30-day pass rate. */
export function QualityOverviewPage() {
  const open = useInspectionLots({ page: 1, pageSize: 8, status: 'open' });
  const held = useInspectionLots({ page: 1, pageSize: 8, status: 'quarantined' });
  const ncrs = useNcrs({ page: 1, pageSize: 8, status: 'open' });
  const late = useOverdueCapa();
  const report = useQualityReport({ groupBy: 'product' });
  const t = report.data?.totals;
  const tiles: Array<[string, string | number]> = [
    [tx('Open lots'), open.data?.meta.total ?? '—'],
    [tx('In quarantine'), held.data?.meta.total ?? '—'],
    [tx('Open NCRs'), ncrs.data?.meta.total ?? '—'],
    [tx('Overdue CAPA actions'), late.data?.actions.length ?? '—'],
    [tx('Pass rate (30 days)'), t?.passRate != null ? `${num(t.passRate, 1)} %` : '—'],
  ];
  const lots = [...(held.data?.data ?? []), ...(open.data?.data ?? [])].slice(0, 10);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={ShieldCheck} title={tx('Quality')} description={tx('Inspection lots, nonconformances and corrective actions.')} />
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
        {tiles.map(([k, v]) => (
          <Card key={k}><CardContent className="pt-4"><p className="text-xs text-muted-foreground">{k}</p><p className="text-2xl font-semibold tabular-nums">{v}</p></CardContent></Card>
        ))}
      </div>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between"><CardTitle className="text-base">{tx('Lots waiting for a decision')}</CardTitle><Link className="text-sm text-primary hover:underline" to="/quality/lots">{tx('View all')}</Link></CardHeader>
          <CardContent>
            {lots.length === 0 ? <p className="text-sm text-muted-foreground">{tx('Nothing waiting.')}</p> : (
              <ul className="divide-y text-sm">
                {lots.map((l) => (
                  <li key={l.id} className="flex flex-wrap items-center gap-2 py-2">
                    <Link className="font-medium text-primary hover:underline" to={`/quality/lots/${l.id}`}>{l.lotNumber}</Link>
                    <span className="min-w-0 flex-1 truncate">{l.productName} {l.batchNumber ?? ''}</span>
                    <span className="text-xs text-muted-foreground">{dateTime(l.createdAt)}</span>
                    <ResultPill result={l.result} />
                    <LotStatusPill status={l.status} />
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between"><CardTitle className="text-base">{tx('Open NCRs')}</CardTitle><Link className="text-sm text-primary hover:underline" to="/quality/ncrs">{tx('View all')}</Link></CardHeader>
          <CardContent>
            {(ncrs.data?.data ?? []).length === 0 ? <p className="text-sm text-muted-foreground">{tx('No open NCRs.')}</p> : (
              <ul className="divide-y text-sm">
                {(ncrs.data?.data ?? []).map((n) => (
                  <li key={n.id} className="flex flex-wrap items-center gap-2 py-2">
                    <Link className="font-medium text-primary hover:underline" to={`/quality/ncrs/${n.id}`}>{n.ncrNumber}</Link>
                    <span className="min-w-0 flex-1 truncate">{n.title}</span>
                    <SeverityPill severity={n.severity} />
                    <NcrStatusPill status={n.status} />
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

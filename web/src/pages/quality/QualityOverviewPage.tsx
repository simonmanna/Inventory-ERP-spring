import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { ClipboardCheck, ShieldCheck } from 'lucide-react';
import { PERMISSIONS, type PaginatedResult } from '@erp/shared';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useOps, useOpsBuckets } from '@/components/ops-dashboard/api';
import { Donut, Empty, Gauge, RankedBars, SegmentBar, STATUS, TrendChart, useSeries, type RankRow } from '@/components/ops-dashboard/charts';
import { Brief, DashboardHero, HeroAction, HeroStat, num, numOrNull, Panel, pctText, qtyText, Reveal, type BriefItem } from '@/components/ops-dashboard/kit';
import { queryBuckets, usePeriod } from '@/components/ops-dashboard/period';
import { NCR_STATUS_LABEL, SEVERITY_LABEL, type InspectionLot, type Ncr, type QualityReport, type ReportRow } from '@/features/quality/api';
import { LotStatusPill, NcrStatusPill, ResultPill, SeverityPill } from './quality-ui';

const READ = PERMISSIONS.quality.read;
const SUMMARY = '/quality/reports/summary';
const OPEN_NCR = new Set(['open', 'investigating', 'action']);

type Overdue = { actions: Array<{ id: string }>; capas: Array<{ id: string }> };
type Row = ReportRow & { quantity?: string; rejectedQty?: string; downgradedQty?: string; releasedQty?: string };

/** Pass rate by a dimension, worst first: that is where a manager looks. */
function passRows(rows: Row[] | undefined, href: (r: Row) => string | undefined): RankRow[] {
  return (rows ?? [])
    // Lots with no supplier or work centre come back as one unnamed row: nothing to compare.
    .filter((r) => r.evaluated > 0 && r.label !== '—')
    .map((r) => {
      const rate = num(r.passRate);
      return {
        key: r.key,
        label: r.label,
        value: rate,
        display: pctText(rate),
        sub: r.lots === 1 ? tx('1 lot · {{failed}} failed', { failed: r.failed }) : tx('{{lots}} lots · {{failed}} failed', { lots: r.lots, failed: r.failed }),
        note: num(r.ncrCount) > 0 ? tx('{{count}} NCR', { count: num(r.ncrCount) }) : undefined,
        href: href(r),
        flag: rate < 90 ? { tone: 'critical' as const, label: tx('Below 90%') } : rate < 98 ? { tone: 'warning' as const, label: tx('Watch') } : undefined,
      };
    })
    .sort((a, b) => a.value - b.value);
}

/**
 * Quality overview (ADR-S013): pass rate and its trend, where failures come from, the state of nonconformances, and the
 * lots and NCRs waiting for someone.
 */
export function QualityOverviewPage() {
  const { period, setPeriod } = usePeriod();
  const range = { from: period.from, to: period.to };
  const palette = useSeries();

  const byProduct = useOps<QualityReport>(SUMMARY, { groupBy: 'product', ...range }, READ);
  const bySupplier = useOps<QualityReport>(SUMMARY, { groupBy: 'supplier', ...range }, READ);
  const byCentre = useOps<QualityReport>(SUMMARY, { groupBy: 'work_center', ...range }, READ);
  const incoming = useOps<QualityReport>(SUMMARY, { groupBy: 'product', stage: 'incoming', ...range }, READ);
  const inProcess = useOps<QualityReport>(SUMMARY, { groupBy: 'product', stage: 'in_process', ...range }, READ);
  const final = useOps<QualityReport>(SUMMARY, { groupBy: 'product', stage: 'final', ...range }, READ);
  const buckets = useMemo(() => queryBuckets(period), [period]);
  const trend = useOpsBuckets<QualityReport>(SUMMARY, buckets, (b) => ({ groupBy: 'product', from: b.from, to: b.to }), READ);

  const openLots = useOps<PaginatedResult<InspectionLot>>('/quality/inspection-lots', { page: 1, pageSize: 8, status: 'open' }, READ);
  const held = useOps<PaginatedResult<InspectionLot>>('/quality/inspection-lots', { page: 1, pageSize: 8, status: 'quarantined' }, READ);
  const ncrs = useOps<PaginatedResult<Ncr>>('/quality/ncrs', { page: 1, pageSize: 200 }, READ);
  const late = useOps<Overdue>('/quality/capa-actions/overdue', undefined, READ);

  const t = byProduct.data?.totals;
  const passRate = numOrNull(t?.passRate);
  const allNcrs = ncrs.data?.data ?? [];
  const live = allNcrs.filter((x) => OPEN_NCR.has(x.status));
  const critical = live.filter((x) => x.severity === 'critical').length;
  const lots = [...(held.data?.data ?? []), ...(openLots.data?.data ?? [])].slice(0, 8);

  const trendRows = trend.rows.map(({ bucket, data }) => ({
    label: bucket.label,
    passed: data?.totals.passed ?? 0,
    failed: data?.totals.failed ?? 0,
    rate: numOrNull(data?.totals.passRate),
  }));

  const ageRows = useMemo(() => {
    const now = Date.now();
    const bands = [
      { key: 'a', label: tx('Up to 7 days'), max: 7, value: 0, color: '#86b6ef', ink: '#0d2747' },
      { key: 'b', label: tx('8–30 days'), max: 30, value: 0, color: '#3987e5' },
      { key: 'c', label: tx('31–90 days'), max: 90, value: 0, color: '#1c5cab' },
      { key: 'd', label: tx('Over 90 days'), max: Infinity, value: 0, color: '#0d366b' },
    ];
    for (const x of live) {
      const age = (now - new Date(x.createdAt).getTime()) / 86_400_000;
      const band = bands.find((b) => age <= b.max);
      if (band) band.value += 1;
    }
    return bands;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ncrs.data]);

  const disposition = useMemo(() => {
    const rows = (byProduct.data?.rows ?? []) as Row[];
    const sum = (read: (r: Row) => unknown) => rows.reduce((s, r) => s + num(read(r)), 0);
    const released = sum((r) => r.releasedQty);
    const rejected = sum((r) => r.rejectedQty);
    const downgraded = sum((r) => r.downgradedQty);
    const pending = Math.max(0, sum((r) => r.quantity) - released - rejected - downgraded);
    return [
      { key: 'released', label: tx('Released'), value: released, color: STATUS.good },
      { key: 'downgraded', label: tx('Downgraded'), value: downgraded, color: STATUS.warning, ink: '#0b0b0b' },
      { key: 'rejected', label: tx('Rejected'), value: rejected, color: STATUS.critical },
      { key: 'pending', label: tx('Awaiting decision'), value: pending, color: STATUS.neutral },
    ];
  }, [byProduct.data]);

  const worst = passRows(byProduct.data?.rows as Row[] | undefined, () => undefined)[0];
  const overdue = late.data?.actions.length ?? 0;
  const quarantined = held.data?.meta.total ?? 0;
  const oldNcrs = ageRows[3].value;
  const brief: BriefItem[] = [];
  if (critical > 0) brief.push({ key: 'crit', tone: 'bad', text: (critical === 1 ? tx('{{count}} critical nonconformance is still open.', { count: critical }) : tx('{{count}} critical nonconformances are still open.', { count: critical })), to: '/quality/ncrs' });
  if (overdue > 0) brief.push({ key: 'capa', tone: 'bad', text: (overdue === 1 ? tx('{{count}} CAPA action is past its due date.', { count: overdue }) : tx('{{count}} CAPA actions are past their due date.', { count: overdue })), to: '/quality/capas' });
  if (quarantined > 0) brief.push({ key: 'held', tone: 'warn', text: (quarantined === 1 ? tx('{{count}} lot is in quarantine and waiting for a decision.', { count: quarantined }) : tx('{{count}} lots are in quarantine and waiting for a decision.', { count: quarantined })), to: '/quality/lots?status=quarantined' });
  if (worst && worst.value < 90) brief.push({ key: 'worst', tone: 'warn', text: tx('{{name}} has the lowest pass rate, at {{rate}}.', { name: worst.label, rate: worst.display }) });
  if (oldNcrs > 0) brief.push({ key: 'age', tone: 'warn', text: (oldNcrs === 1 ? tx('{{count}} open NCR is older than 90 days.', { count: oldNcrs }) : tx('{{count}} open NCRs are older than 90 days.', { count: oldNcrs })), to: '/quality/ncrs' });
  if (passRate != null && passRate >= 95) brief.push({ key: 'rate', tone: 'good', text: tx('Pass rate is {{rate}} on {{count}} evaluated lots.', { rate: pctText(passRate), count: t?.evaluated ?? 0 }) });
  if ((ncrs.data?.meta.total ?? 0) > allNcrs.length) brief.push({ key: 'cap', tone: 'info', text: tx('NCR charts use the latest {{n}} of {{total}} NCRs.', { n: allNcrs.length, total: ncrs.data?.meta.total ?? 0 }) });

  const stages = [
    { key: 'incoming', label: tx('Incoming'), report: incoming.data },
    { key: 'in_process', label: tx('In-process'), report: inProcess.data },
    { key: 'final', label: tx('Final'), report: final.data },
  ];
  const severity = [
    { key: 'critical', label: SEVERITY_LABEL.critical, value: live.filter((x) => x.severity === 'critical').length, color: STATUS.critical },
    { key: 'major', label: SEVERITY_LABEL.major, value: live.filter((x) => x.severity === 'major').length, color: STATUS.serious },
    { key: 'minor', label: SEVERITY_LABEL.minor, value: live.filter((x) => x.severity === 'minor').length, color: STATUS.neutral },
  ];
  const statusRows = (['open', 'investigating', 'action'] as const).map((s, i) => ({
    key: s,
    label: NCR_STATUS_LABEL[s],
    value: live.filter((x) => x.status === s).length,
    color: palette[i],
    ink: s === 'investigating' ? '#ffffff' : undefined,
    href: `/quality/ncrs?status=${s}`,
  }));

  return (
    <div className="space-y-5">
      <DashboardHero
        icon={ShieldCheck}
        title={tx('Quality')}
        subtitle={tx('Inspection results, nonconformances and corrective actions.')}
        period={period.key}
        onPeriod={setPeriod}
        fetching={byProduct.isFetching || trend.isLoading}
        actions={<HeroAction to="/quality/lots" icon={ClipboardCheck} primary>{tx('Inspection lots')}</HeroAction>}
        headline={{
          label: tx('Pass rate'),
          value: passRate ?? 0,
          format: (v) => (passRate == null ? '—' : `${v.toFixed(1)}%`),
          sub: tx('{{passed}} of {{evaluated}} evaluated lots passed', { passed: t?.passed ?? 0, evaluated: t?.evaluated ?? 0 }),
        }}
        chartTitle={tx('Lots passed and failed')}
        chart={<TrendChart onHero kind="stacked" height={170} data={trendRows} format={(v) => qtyText(v)} series={[{ key: 'passed', label: tx('Passed'), color: 'var(--sb-active-bar)' }, { key: 'failed', label: tx('Failed'), color: STATUS.critical }]} />}
        stats={(
          <>
            <HeroStat label={tx('Lots inspected')} value={qtyText(t?.lots ?? 0)} sub={tx('{{count}} failed', { count: t?.failed ?? 0 })} to="/quality/lots" ring={passRate} />
            <HeroStat label={tx('Open lots')} value={qtyText(openLots.data?.meta.total ?? 0)} to="/quality/lots?status=open" />
            <HeroStat label={tx('In quarantine')} value={qtyText(held.data?.meta.total ?? 0)} to="/quality/lots?status=quarantined" />
            <HeroStat label={tx('Open NCRs')} value={qtyText(live.length)} sub={tx('{{count}} critical', { count: critical })} to="/quality/ncrs" />
            <HeroStat label={tx('Overdue CAPA actions')} value={qtyText(late.data?.actions.length ?? 0)} to="/quality/capas" />
            <HeroStat label={tx('NCRs raised')} value={qtyText(t?.ncrCount ?? 0)} sub={tx('in this period')} to="/quality/ncrs" />
          </>
        )}
      />

      <Reveal delay={80} className="grid gap-5 lg:grid-cols-2">
        <Brief items={brief} />
        <Panel title={tx('Pass rate by inspection stage')} hint={tx('On track from 98%, watch from 90%.')}>
          <div className="grid grid-cols-3 gap-2">
            {stages.map((s) => (
              <Gauge key={s.key} label={s.label} value={numOrNull(s.report?.totals.passRate)} threshold={{ good: 98, warn: 90 }}
                sub={(s.report?.totals.lots ?? 0) === 1 ? tx('1 lot') : tx('{{count}} lots', { count: s.report?.totals.lots ?? 0 })} />
            ))}
          </div>
        </Panel>
      </Reveal>

      <Reveal delay={140} className="grid gap-5 lg:grid-cols-3">
        <Panel title={tx('Pass rate by product')} hint={tx('Lowest first')} href="/quality/reports" action={tx('Report')}>
          <RankedBars rows={passRows(byProduct.data?.rows as Row[] | undefined, () => undefined)} max={100} limit={6} />
        </Panel>
        <Panel title={tx('Pass rate by supplier')} hint={tx('Lowest first')} href="/quality/reports" action={tx('Report')}>
          <RankedBars rows={passRows(bySupplier.data?.rows as Row[] | undefined, () => undefined)} max={100} limit={6} emptyText={tx('No inspected lots are linked to a supplier in this period.')} />
        </Panel>
        <Panel title={tx('Pass rate by work centre')} hint={tx('Lowest first')} href="/quality/reports" action={tx('Report')}>
          <RankedBars rows={passRows(byCentre.data?.rows as Row[] | undefined, () => undefined)} max={100} limit={6} emptyText={tx('No inspected lots are linked to a work centre in this period.')} />
        </Panel>
      </Reveal>

      <Reveal delay={200} className="grid gap-5 lg:grid-cols-3">
        <Panel title={tx('Open NCRs by severity')} hint={tx('Not yet closed')}>
          <Donut rows={severity} center={String(live.length)} centerSub={tx('open')} format={(v) => qtyText(v)} size={140} stacked />
        </Panel>
        <Panel title={tx('Open NCRs by stage')} hint={tx('Where they are stuck')} href="/quality/ncrs" action={tx('View all')}>
          <SegmentBar rows={statusRows} />
          <div className="mt-5">
            <div className="mb-2 text-xs font-medium text-muted-foreground">{tx('Age of open NCRs')}</div>
            <SegmentBar rows={ageRows} />
          </div>
        </Panel>
        <Panel title={tx('What happened to inspected quantity')} hint={tx('All lots in this period, in their stock units')}>
          <SegmentBar rows={disposition} format={(v) => qtyText(v, 1)} />
        </Panel>
      </Reveal>

      <Reveal delay={260} className="grid gap-5 lg:grid-cols-2">
        <Panel title={tx('Lots waiting for a decision')} href="/quality/lots" action={tx('View all')}>
          {lots.length === 0 ? <Empty>{tx('Nothing waiting.')}</Empty> : (
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
        </Panel>
        <Panel title={tx('Open NCRs')} href="/quality/ncrs" action={tx('View all')}>
          {live.length === 0 ? <Empty>{tx('No open NCRs.')}</Empty> : (
            <ul className="divide-y text-sm">
              {live.slice(0, 8).map((x) => (
                <li key={x.id} className="flex flex-wrap items-center gap-2 py-2">
                  <Link className="font-medium text-primary hover:underline" to={`/quality/ncrs/${x.id}`}>{x.ncrNumber}</Link>
                  <span className="min-w-0 flex-1 truncate">{x.title}</span>
                  <SeverityPill severity={x.severity} />
                  <NcrStatusPill status={x.status} />
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </Reveal>
    </div>
  );
}

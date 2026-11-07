import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Bomb, ClipboardList, Mountain, Plus, Truck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { money, useOrgCurrency } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import { compactMoney } from '@/pages/sales/dashboard/utils';
import { compactNumber } from '@/pages/sales/reports/report-format';
import { useOps } from '@/components/ops-dashboard/api';
import { Bullet, DataDetails, Donut, Empty, RankedBars, Silos, TrendChart, useSeries } from '@/components/ops-dashboard/charts';
import { Brief, DashboardHero, HeroAction, HeroStat, num, numOrNull, Panel, pctText, qtyText, Reveal, type BriefItem } from '@/components/ops-dashboard/kit';
import { bucketKey, bucketsOf, usePeriod, type Grain } from '@/components/ops-dashboard/period';
import { LOSS_LABEL, type LossKind, type Overview, type Variance } from '@/features/quarry/api';
import { tonnes } from './quarry-ui';

const P = PERMISSIONS;

interface CostRow {
  siteId: string; siteCode: string; siteName: string; period: string | null; logs: number;
  grossTonnes: string; lossTonnes: string; netTonnes: string; totalCost: string; costPerTonne: string | null; lossPct: string | null;
  fuelCost: string; labourCost: string; equipmentCost: string; blastingCost: string; royaltyCost: string; otherCost: string;
}
type CostReport = { rows: CostRow[]; totals: Omit<CostRow, 'siteId' | 'siteCode' | 'siteName' | 'period'> };
interface TargetRow { id: string; code: string; name: string; siteCode: string; siteName: string; targetTonnes: string; actualTonnes: string; budgetCostPerTonne: string | null; actualCostPerTonne: string | null; periodEnd: string; variance: Variance }
interface LossReport { rows: Array<{ siteCode: string; kind: LossKind; tonnes: string }>; sites: Array<{ siteId: string; siteCode: string; lossPct: string | null; lossTonnes: string; grossTonnes: string }> }
interface Stockpile { id: string; code: string; name: string; kind: 'rom' | 'product'; capacityT: string | null; onHandTonnes: string; onHandValue: string; isActive: boolean }

const STATUS_WORD: Record<Variance['status'], { tone: 'good' | 'warning' | 'critical'; label: () => string } | null> = {
  not_started: null,
  ahead: { tone: 'good', label: () => tx('Ahead') },
  on_track: { tone: 'good', label: () => tx('On track') },
  achieved: { tone: 'good', label: () => tx('Achieved') },
  behind: { tone: 'warning', label: () => tx('Behind') },
  missed: { tone: 'critical', label: () => tx('Missed') },
};

/** Sites with their chart slot: order follows the site code, so a site keeps its colour across periods. Past six: "Other". */
function siteSeries(rows: CostRow[], palette: string[]) {
  const codes = [...new Set(rows.map((r) => r.siteCode))].sort();
  const shown = codes.slice(0, codes.length > 6 ? 5 : 6);
  const series = shown.map((code, i) => ({ key: code, label: code, color: palette[i] }));
  if (codes.length > shown.length) series.push({ key: '__other', label: tx('Other sites'), color: '#898781' });
  return { series, slot: (code: string) => (shown.includes(code) ? code : '__other') };
}

function pivot(rows: CostRow[], grain: Grain, range: { from: string; to: string }, slot: (code: string) => string, keys: string[], read: (r: CostRow) => number) {
  const out = bucketsOf(range, grain).map((b) => {
    const row: Record<string, string | number> = { bucket: b.key, label: b.label };
    for (const k of keys) row[k] = 0;
    return row;
  });
  const index = new Map(out.map((r) => [r.bucket, r]));
  for (const r of rows) {
    const row = r.period ? index.get(bucketKey(r.period, grain)) : undefined;
    if (row) row[slot(r.siteCode)] = Number(row[slot(r.siteCode)]) + read(r);
  }
  return out;
}

/** Quarry overview (2.2): tonnes and their cost, progress against target, where stone is lost, and what is in stock. */
export function QuarryDashboardPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const currency = useOrgCurrency();
  const palette = useSeries();
  const { period, setPeriod } = usePeriod();
  const p = { dateFrom: period.from, dateTo: period.to };
  const cmoney = (v: unknown) => compactMoney(v, currency);
  const lineGrain: Grain = period.grain === 'day' ? 'week' : period.grain;

  const overview = useOps<Overview>('/quarry/overview', undefined, P.quarryExtraction.read);
  const bySite = useOps<CostReport>('/quarry/reports/cost-per-tonne', { ...p, groupBy: 'site' }, P.quarry.report);
  const trend = useOps<CostReport>('/quarry/reports/cost-per-tonne', { ...p, groupBy: period.grain }, P.quarry.report);
  const costTrend = useOps<CostReport>('/quarry/reports/cost-per-tonne', { ...p, groupBy: lineGrain }, P.quarry.report);
  const targets = useOps<{ rows: TargetRow[] }>('/quarry/reports/extraction-vs-target', p, P.quarry.report);
  const losses = useOps<LossReport>('/quarry/reports/losses', p, P.quarry.report);
  const royalties = useOps<{ totals: { amount: string } }>('/quarry/reports/royalties', p, P.quarry.report);
  const piles = useOps<Stockpile[]>('/quarry/stockpiles', undefined, P.quarrySite.read);

  const o = overview.data;
  const canReport = bySite.allowed;
  const totals = bySite.data?.totals;
  const net = canReport ? num(totals?.netTonnes) : num(o?.month.netTonnes);
  const costPerTonne = numOrNull(totals?.costPerTonne);
  const lossPct = numOrNull(totals?.lossPct);

  const { series, slot } = useMemo(() => siteSeries(trend.data?.rows ?? [], palette), [trend.data, palette]);
  const keys = series.map((s) => s.key);
  const volume = useMemo(() => pivot(trend.data?.rows ?? [], period.grain, period, slot, keys, (r) => num(r.netTonnes)), [trend.data, period, slot, keys.join()]); // eslint-disable-line react-hooks/exhaustive-deps

  const costLines = useMemo(() => {
    const rows = costTrend.data?.rows ?? [];
    const { series: s } = siteSeries(rows, palette);
    const named = s.filter((x) => x.key !== '__other');
    const data = bucketsOf(period, lineGrain).map((b) => {
      const row: Record<string, string | number | null> = { label: b.label };
      for (const x of named) row[x.key] = null;
      for (const r of rows) if (r.period && bucketKey(r.period, lineGrain) === b.key && named.some((x) => x.key === r.siteCode)) row[r.siteCode] = numOrNull(r.costPerTonne);
      return row;
    });
    return { data, series: named };
  }, [costTrend.data, period, lineGrain, palette]);

  const buildUp = [
    { key: 'equipment', label: tx('Equipment'), value: num(totals?.equipmentCost) },
    { key: 'fuel', label: tx('Fuel'), value: num(totals?.fuelCost) },
    { key: 'labour', label: tx('Labour'), value: num(totals?.labourCost) },
    { key: 'blasting', label: tx('Blasting'), value: num(totals?.blastingCost) },
    { key: 'royalty', label: tx('Royalties'), value: num(totals?.royaltyCost) },
    { key: 'other', label: tx('Other'), value: num(totals?.otherCost) },
  ];

  const plans = targets.data?.rows ?? [];
  const planTarget = plans.reduce((s, r) => s + num(r.targetTonnes), 0);
  const planActual = plans.reduce((s, r) => s + num(r.actualTonnes), 0);
  const attainment = planTarget > 0 ? (planActual / planTarget) * 100 : null;

  const lossKinds = useMemo(() => {
    const sum = new Map<LossKind, number>();
    for (const r of losses.data?.rows ?? []) sum.set(r.kind, (sum.get(r.kind) ?? 0) + num(r.tonnes));
    return [...sum.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([kind, value]) => ({ key: kind, label: LOSS_LABEL[kind] ?? kind, value }));
  }, [losses.data]);
  const lossBySite = (losses.data?.sites ?? []).map((s) => ({ key: s.siteId, label: s.siteCode, value: num(s.lossPct), display: pctText(numOrNull(s.lossPct)), sub: tx('{{loss}} of {{gross}}', { loss: tonnes(s.lossTonnes), gross: tonnes(s.grossTonnes) }), flag: num(s.lossPct) >= 20 ? { tone: 'warning' as const, label: tx('High') } : undefined })).sort((a, b) => b.value - a.value);

  const stock = (piles.data ?? []).filter((s) => s.isActive).sort((a, b) => num(b.onHandTonnes) - num(a.onHandTonnes)).map((s) => ({
    key: s.id, code: s.code, name: s.name, value: num(s.onHandTonnes), capacity: numOrNull(s.capacityT), display: tonnes(s.onHandTonnes), href: `/quarry/stockpiles/${s.id}`,
  }));
  const stockValue = (piles.data ?? []).reduce((s, x) => s + num(x.onHandValue), 0);

  const pendingExp = o?.counts.pendingExplosiveApprovals ?? 0;
  const drafts = o?.counts.drafts ?? 0;
  const toIssue = o?.counts.approvedToIssue ?? 0;
  const brief: BriefItem[] = [];
  for (const r of plans.filter((x) => x.variance.status === 'missed' || x.variance.status === 'behind').slice(0, 2)) {
    brief.push({ key: `plan-${r.id}`, tone: r.variance.status === 'missed' ? 'bad' : 'warn', text: tx('{{site}} is at {{pct}} of its {{target}} target.', { site: r.siteCode, pct: pctText(num(r.variance.achievedPct)), target: tonnes(r.targetTonnes) }), to: '/quarry/plans' });
  }
  if (pendingExp > 0) brief.push({ key: 'exp', tone: 'bad', text: (pendingExp === 1 ? tx('{{count}} explosive issue is waiting for approval.', { count: pendingExp }) : tx('{{count}} explosive issues are waiting for approval.', { count: pendingExp })), to: '/quarry/explosive-issues' });
  if (drafts > 0) brief.push({ key: 'drafts', tone: 'warn', text: (drafts === 1 ? tx('{{count}} extraction log is still a draft, so it is not in these figures.', { count: drafts }) : tx('{{count}} extraction logs are still drafts, so they are not in these figures.', { count: drafts })), to: '/quarry/extraction' });
  if (toIssue > 0) brief.push({ key: 'issue', tone: 'info', text: (toIssue === 1 ? tx('{{count}} approved explosive issue has not been issued yet.', { count: toIssue }) : tx('{{count}} approved explosive issues have not been issued yet.', { count: toIssue })), to: '/quarry/explosive-issues' });
  const highLoss = lossBySite[0];
  if (highLoss && highLoss.value >= 20) brief.push({ key: 'loss', tone: 'warn', text: tx('{{site}} loses the most stone: {{pct}} of gross tonnes.', { site: highLoss.label, pct: highLoss.display }), to: '/quarry/losses' });
  const cheapest = [...(bySite.data?.rows ?? [])].filter((r) => numOrNull(r.costPerTonne) != null).sort((a, b) => num(a.costPerTonne) - num(b.costPerTonne));
  if (cheapest.length > 1) brief.push({ key: 'cost', tone: 'info', text: tx('Cost per tonne ranges from {{low}} at {{lowSite}} to {{high}} at {{highSite}}.', { low: cmoney(cheapest[0].costPerTonne), lowSite: cheapest[0].siteCode, high: cmoney(cheapest[cheapest.length - 1].costPerTonne), highSite: cheapest[cheapest.length - 1].siteCode }), to: '/quarry/reports' });
  const unmet = plans.filter((x) => num(x.budgetCostPerTonne) > 0 && num(x.actualCostPerTonne) > num(x.budgetCostPerTonne));
  if (unmet.length > 0) brief.push({ key: 'budget', tone: 'warn', text: (unmet.length === 1 ? tx('{{count}} site is above its budget cost per tonne.', { count: unmet.length }) : tx('{{count}} sites are above their budget cost per tonne.', { count: unmet.length })), to: '/quarry/reports' });
  if (attainment != null && attainment >= 100) brief.push({ key: 'met', tone: 'good', text: tx('Extraction targets are met.') });

  const rowsBySite = bySite.data?.rows ?? [];

  return (
    <div className="space-y-5">
      <DashboardHero
        icon={Mountain}
        title={tx('Quarry Overview')}
        subtitle={tx('Extraction, blasting and run-of-mine stock across your quarry sites.')}
        period={period.key}
        onPeriod={setPeriod}
        fetching={bySite.isFetching || overview.isFetching}
        actions={(
          <>
            {has(P.quarryExtraction.haul) && <HeroAction to="/quarry/haulage" icon={Truck}>{tx('Log haulage')}</HeroAction>}
            {has(P.quarryExtraction.create) && <HeroAction to="/quarry/extraction/new" icon={Plus} primary>{tx('New extraction log')}</HeroAction>}
          </>
        )}
        headline={{
          label: canReport ? tx('Net stone extracted') : tx('Extracted this month'),
          value: net,
          format: (v) => `${qtyText(v)} t`,
          sub: canReport ? tx('{{count}} posted logs · {{gross}} gross', { count: totals?.logs ?? 0, gross: tonnes(totals?.grossTonnes) }) : undefined,
        }}
        chartTitle={tx('Net tonnes by site')}
        chart={canReport ? <TrendChart onHero kind="stacked" height={170} data={volume} series={series} format={compactNumber} /> : null}
        stats={(
          <>
            <HeroStat label={tx('Target achieved')} value={pctText(attainment)} sub={planTarget > 0 ? tx('{{actual}} of {{target}}', { actual: compactNumber(planActual), target: `${compactNumber(planTarget)} t` }) : tx('No targets set')} to="/quarry/plans" ring={attainment} />
            <HeroStat label={tx('Cost per tonne')} value={costPerTonne == null ? '—' : cmoney(costPerTonne)} title={costPerTonne == null ? undefined : money(costPerTonne, currency)} sub={tx('{{total}} in total', { total: cmoney(totals?.totalCost) })} to="/quarry/reports" />
            <HeroStat label={tx('Stone lost')} value={pctText(lossPct)} sub={tonnes(totals?.lossTonnes)} to="/quarry/losses" ring={lossPct} />
            <HeroStat label={tx('Extracted today')} value={tonnes(o?.todayTonnes)} sub={tx('{{count}} haulage records today', { count: o?.counts.haulsToday ?? 0 })} to="/quarry/extraction" />
            <HeroStat label={tx('ROM stock on hand')} value={tonnes(o?.counts.romTonnes)} to="/quarry/stockpiles" />
            <HeroStat label={tx('Open blasts')} value={String(o?.counts.openBlasts ?? 0)} sub={tx('{{count}} awaiting explosive approval', { count: o?.counts.pendingExplosiveApprovals ?? 0 })} to="/quarry/blasts" />
          </>
        )}
      />

      <Reveal delay={80} className="grid gap-5 lg:grid-cols-2">
        <Brief items={brief} />
        <Panel title={tx('Progress against extraction targets')} hint={tx('Black tick: target. Grey tick: where the site should be by today.')} href="/quarry/plans" action={tx('Plans')}>
          {plans.length === 0 ? <Empty>{tx('No extraction targets in this period.')}</Empty> : (
            <div className="space-y-1.5">
              {plans.slice(0, 6).map((r) => {
                const word = STATUS_WORD[r.variance.status];
                return (
                  <Bullet key={r.id} label={r.siteCode} actual={num(r.actualTonnes)} target={num(r.targetTonnes)} pace={num(r.variance.expectedToDate)}
                    display={`${compactNumber(num(r.actualTonnes))} / ${compactNumber(num(r.targetTonnes))} t`}
                    status={word ? { tone: word.tone, label: word.label() } : undefined}
                    sub={tx('{{pct}} · cost {{cost}} / t against {{budget}} budget', { pct: pctText(num(r.variance.achievedPct)), cost: cmoney(r.actualCostPerTonne), budget: cmoney(r.budgetCostPerTonne) })} />
                );
              })}
            </div>
          )}
        </Panel>
      </Reveal>

      <Reveal delay={140} className="grid gap-5 lg:grid-cols-5">
        <Panel className="lg:col-span-3" title={tx('Cost per tonne by site')} hint={lineGrain === 'week' ? tx('Weekly') : tx('Monthly')} href="/quarry/reports" action={tx('Report')}>
          <TrendChart kind="line" data={costLines.data} series={costLines.series} format={compactNumber} height={230} />
        </Panel>
        <Panel className="lg:col-span-2" title={tx('Where the money goes')} hint={tx('Build-up of extraction cost')}>
          <Donut rows={buildUp} center={cmoney(totals?.totalCost)} format={cmoney} size={170} stacked />
        </Panel>
      </Reveal>

      <Reveal delay={200} className="grid gap-5 lg:grid-cols-3">
        <Panel title={tx('Stone lost by site')} hint={tx('Share of gross tonnes')} href="/quarry/losses" action={tx('Losses')}>
          <RankedBars rows={lossBySite} max={Math.max(25, ...lossBySite.map((x) => x.value))} limit={6} />
        </Panel>
        <Panel title={tx('Losses by kind')} hint={tx('Tonnes')}>
          <Donut rows={lossKinds} format={(v) => qtyText(v)} centerSub={tx('tonnes')} size={140} stacked />
        </Panel>
        <Panel title={tx('Site comparison')} hint={royalties.data && num(royalties.data.totals.amount) > 0 ? tx('Royalties and levies: {{amount}}', { amount: cmoney(royalties.data.totals.amount) }) : undefined}>
          {rowsBySite.length === 0 ? <Empty /> : (
            <>
              <table className="w-full text-sm">
                <thead><tr className="text-left text-xs text-muted-foreground"><th className="pb-2 font-medium">{tx('Site')}</th><th className="pb-2 text-right font-medium">{tx('Net t')}</th><th className="pb-2 text-right font-medium">{tx('Cost / t')}</th><th className="pb-2 text-right font-medium">{tx('Lost')}</th></tr></thead>
                <tbody>
                  {rowsBySite.map((r) => (
                    <tr key={r.siteId} className="border-t">
                      <td className="py-1.5 font-medium" title={r.siteName}>{r.siteCode}</td>
                      <td className="py-1.5 text-right tabular-nums">{compactNumber(num(r.netTonnes))}</td>
                      <td className="py-1.5 text-right tabular-nums" title={money(r.costPerTonne, currency)}>{compactNumber(num(r.costPerTonne))}</td>
                      <td className="py-1.5 text-right tabular-nums">{pctText(numOrNull(r.lossPct))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <DataDetails heads={[tx('Site'), tx('Logs'), tx('Gross t'), tx('Total cost')]} rows={rowsBySite.map((r) => [r.siteCode, r.logs, qtyText(num(r.grossTonnes)), money(r.totalCost, currency)])} />
            </>
          )}
        </Panel>
      </Reveal>

      <Reveal delay={260}>
        <Panel title={tx('Stockpiles')} hint={tx('On hand against capacity · valued at {{value}}', { value: cmoney(stockValue) })} href="/quarry/stockpiles" action={tx('View all')}>
          <Silos rows={stock} />
        </Panel>
      </Reveal>

      <Reveal delay={300} className="grid gap-3 sm:grid-cols-3">
        <Link to="/quarry/extraction" className="flex items-center gap-3 rounded-xl border bg-card p-4 shadow-sm hover:border-primary/50"><ClipboardList className="h-5 w-5 text-primary" /> <span>{tx('Extraction logs')}</span></Link>
        <Link to="/quarry/blasts" className="flex items-center gap-3 rounded-xl border bg-card p-4 shadow-sm hover:border-primary/50"><Bomb className="h-5 w-5 text-primary" /> <span>{tx('Blasts ({{count}} open)', { count: o?.counts.openBlasts ?? 0 })}</span></Link>
        <Link to="/quarry/stockpiles" className="flex items-center gap-3 rounded-xl border bg-card p-4 shadow-sm hover:border-primary/50"><Mountain className="h-5 w-5 text-primary" /> <span>{tx('ROM stockpiles')}</span></Link>
      </Reveal>
    </div>
  );
}

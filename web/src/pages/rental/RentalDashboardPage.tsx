import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { CalendarClock, KeyRound, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { date, money, useOrgCurrency } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { compactMoney } from '@/pages/sales/dashboard/utils';
import { compactNumber } from '@/pages/sales/reports/report-format';
import { useOps, useOpsBuckets } from '@/components/ops-dashboard/api';
import { Donut, Empty, FlagPill, Gauge, Legend, RankedBars, SegmentBar, STATUS, TrendChart, useSeries, type RankRow } from '@/components/ops-dashboard/charts';
import { Brief, DashboardHero, HeroAction, HeroStat, num, numOrNull, Panel, pctText, qtyText, Reveal, type BriefItem } from '@/components/ops-dashboard/kit';
import { queryBuckets, rollUp, usePeriod } from '@/components/ops-dashboard/period';
import { type RentalAgreement } from '@/features/rental/api';

const READ = PERMISSIONS.rental.read;

interface Revenue { closedAgreements: number; rentalIncome: number; lateFeeIncome: number; damageRecovery: number; total: number }
interface Utilization { fleet: number; totalUnitDays: number; hiredUnitDays: number; utilization: number }
interface Unit { id: string; unitCode: string; status: string; conditionGrade: string | null; rentalCount: number; lifetimeRevenue: string; product?: { id: string; code: string; name: string } }
interface FleetReport { byStatus: Record<string, number>; items: Unit[] }
type Agreement = RentalAgreement & { partner?: { id: string; name: string; code: string } };
interface AgreementList { total: number; items: Agreement[] }

const OPEN = new Set(['confirmed', 'checked_out', 'partially_returned']);
const ON_HIRE = new Set(['checked_out', 'partially_returned']);
const contractValue = (a: Agreement) => (a.lines ?? []).reduce((s, l) => s + num(l.lineTotal), 0);
const isOverdue = (a: Agreement, now: number) => ON_HIRE.has(a.status) && new Date(a.dueAt).getTime() < now;

/** Rental dashboard: what is out and earning, what is due back or late, and how hard the units are working. */
export function RentalDashboardPage() {
  const currency = useOrgCurrency();
  const palette = useSeries();
  const { period, setPeriod } = usePeriod();
  const cmoney = (v: unknown) => compactMoney(v, currency);
  const utcRange = useMemo(() => ({ from: `${period.from}T00:00:00.000Z`, to: `${period.to}T23:59:59.999Z` }), [period]);

  const agreements = useOps<AgreementList>('/rental/agreements', { page: 1, pageSize: 200 }, READ);
  const fleet = useOps<FleetReport>('/rental/reports/fleet', undefined, READ);
  const revenue = useOps<Revenue>('/rental/reports/revenue', utcRange, READ);
  const utilisation = useOps<Utilization>('/rental/reports/utilization', utcRange, READ);
  const buckets = useMemo(() => queryBuckets(period), [period]);
  const toRange = (b: { from: string; to: string }) => ({ from: `${b.from}T00:00:00.000Z`, to: `${b.to}T23:59:59.999Z` });
  const revenueTrend = useOpsBuckets<Revenue>('/rental/reports/revenue', buckets, toRange, READ);
  const utilTrend = useOpsBuckets<Utilization>('/rental/reports/utilization', buckets, toRange, READ);

  const list = agreements.data?.items ?? [];
  const units = useMemo(() => fleet.data?.items ?? [], [fleet.data]);
  const now = Date.now();
  const onHire = list.filter((a) => ON_HIRE.has(a.status));
  const overdue = list.filter((a) => isOverdue(a, now));
  const open = list.filter((a) => OPEN.has(a.status));
  const pending = list.filter((a) => a.status === 'pending_approval');
  const openValue = open.reduce((s, a) => s + contractValue(a), 0);
  const rate = numOrNull(utilisation.data?.utilization);
  const fleetSize = units.length;
  const inPeriod = list.filter((a) => a.startAt.slice(0, 10) >= period.from && a.startAt.slice(0, 10) <= period.to && a.status !== 'cancelled');

  const booked = useMemo(() => rollUp(inPeriod, (a) => a.startAt, { value: contractValue }, period, period.grain), [agreements.data, period]); // eslint-disable-line react-hooks/exhaustive-deps

  const income = revenueTrend.rows.map(({ bucket, data }) => ({ label: bucket.label, rental: num(data?.rentalIncome), late: num(data?.lateFeeIncome), damage: num(data?.damageRecovery) }));
  const utilRows = utilTrend.rows.map(({ bucket, data }) => ({ label: bucket.label, pct: numOrNull(data?.utilization) }));

  const unitStatus: Record<string, { label: string; color: string }> = {
    available: { label: tx('Available'), color: STATUS.good },
    reserved: { label: tx('Reserved'), color: palette[6] },
    checked_out: { label: tx('On hire'), color: palette[0] },
    returned: { label: tx('Returned'), color: palette[2] },
    cleaning: { label: tx('Cleaning'), color: STATUS.warning },
    repair: { label: tx('In repair'), color: STATUS.serious },
    damaged: { label: tx('Damaged'), color: STATUS.critical },
    lost: { label: tx('Lost'), color: '#7a1f1f' },
    retired: { label: tx('Retired'), color: STATUS.neutral },
  };
  const statusOf = (s: string) => unitStatus[s] ?? { label: s, color: STATUS.neutral };
  const statusCounts = Object.entries(fleet.data?.byStatus ?? {}).filter(([, c]) => c > 0);

  const byProduct = useMemo(() => {
    const groups = new Map<string, { name: string; units: Unit[] }>();
    for (const u of units) {
      const key = u.product?.id ?? 'none';
      const g = groups.get(key) ?? { name: u.product?.name ?? tx('No product'), units: [] };
      g.units.push(u);
      groups.set(key, g);
    }
    return [...groups.entries()].map(([key, g]) => ({ key, ...g, units: [...g.units].sort((a, b) => a.unitCode.localeCompare(b.unitCode)) })).sort((a, b) => a.name.localeCompare(b.name));
  }, [units]);

  const conditions = useMemo(() => {
    const m = new Map<string, number>();
    for (const u of units) { const c = u.conditionGrade ?? 'unknown'; m.set(c, (m.get(c) ?? 0) + 1); }
    const label: Record<string, string> = { new: tx('New'), good: tx('Good'), fair: tx('Fair'), poor: tx('Poor'), damaged: tx('Damaged'), unknown: tx('Not graded') };
    return [...m.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([k, value]) => ({ key: k, label: label[k] ?? k, value }));
  }, [units]);

  const earners: RankRow[] = byProduct.map((g) => ({
    key: g.key, label: g.name,
    value: g.units.reduce((s, u) => s + num(u.lifetimeRevenue), 0),
    display: cmoney(g.units.reduce((s, u) => s + num(u.lifetimeRevenue), 0)),
    sub: g.units.length === 1 ? tx('1 unit') : tx('{{count}} units', { count: g.units.length }),
    note: tx('Hired {{count}} times', { count: g.units.reduce((s, u) => s + u.rentalCount, 0) }),
  })).filter((r) => r.value > 0).sort((a, b) => b.value - a.value);

  const customers: RankRow[] = useMemo(() => {
    const m = new Map<string, { name: string; value: number; count: number }>();
    for (const a of list) {
      if (a.status === 'cancelled' || a.status === 'draft') continue;
      const key = a.partner?.id ?? a.partnerId;
      const g = m.get(key) ?? { name: a.partner?.name ?? tx('Unknown customer'), value: 0, count: 0 };
      g.value += contractValue(a);
      g.count += 1;
      m.set(key, g);
    }
    return [...m.entries()].map(([key, g]) => ({ key, label: g.name, value: g.value, display: cmoney(g.value), sub: g.count === 1 ? tx('1 agreement') : tx('{{count}} agreements', { count: g.count }) })).sort((a, b) => b.value - a.value);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agreements.data, currency]);

  const pipeline = [
    { key: 'draft', label: tx('Draft'), value: list.filter((a) => a.status === 'draft').length, color: '#86b6ef', ink: '#0d2747', href: '/rental/agreements?status=draft' },
    { key: 'pending_approval', label: tx('Awaiting approval'), value: pending.length, color: '#5598e7', href: '/rental/agreements?status=pending_approval' },
    { key: 'confirmed', label: tx('Confirmed'), value: list.filter((a) => a.status === 'confirmed').length, color: '#2a78d6', href: '/rental/agreements?status=confirmed' },
    { key: 'on_hire', label: tx('On hire'), value: onHire.length, color: '#1c5cab', href: '/rental/agreements?status=checked_out' },
    { key: 'returned', label: tx('Returned'), value: list.filter((a) => a.status === 'returned').length, color: '#104281', href: '/rental/agreements?status=returned' },
    { key: 'closed', label: tx('Closed'), value: list.filter((a) => a.status === 'closed').length, color: '#0d366b', href: '/rental/agreements?status=closed' },
  ];

  const dueBack = [...open].sort((a, b) => a.dueAt.localeCompare(b.dueAt)).slice(0, 7);
  const damageHeld = list.reduce((s, a) => s + num(a.damageTotal), 0);
  const brokenUnits = units.filter((u) => u.status === 'damaged' || u.status === 'repair').length;
  const qtyOut = onHire.reduce((s, a) => s + (a.lines ?? []).reduce((x, l) => x + num(l.quantity), 0), 0);
  const noUnit = onHire.reduce((s, a) => s + (a.lines ?? []).filter((l) => !l.unitId).reduce((x, l) => x + num(l.quantity), 0), 0);

  const brief: BriefItem[] = [];
  if (overdue.length > 0) brief.push({ key: 'late', tone: 'bad', text: (overdue.length === 1 ? tx('{{count}} agreement is past its return date.', { count: overdue.length }) : tx('{{count}} agreements are past their return date.', { count: overdue.length })), to: '/rental/agreements?status=checked_out' });
  if (brokenUnits > 0) brief.push({ key: 'broken', tone: 'warn', text: (brokenUnits === 1 ? tx('{{count}} unit is damaged or in repair and cannot earn.', { count: brokenUnits }) : tx('{{count}} units are damaged or in repair and cannot earn.', { count: brokenUnits })), to: '/rental/units' });
  if (pending.length > 0) brief.push({ key: 'pend', tone: 'warn', text: (pending.length === 1 ? tx('{{count}} agreement is waiting for approval.', { count: pending.length }) : tx('{{count}} agreements are waiting for approval.', { count: pending.length })), to: '/rental/agreements?status=pending_approval' });
  if (rate != null && fleetSize > 0 && rate < 40) brief.push({ key: 'util', tone: 'warn', text: tx('Only {{pct}} of available unit-days were hired in this period.', { pct: pctText(rate) }), to: '/rental/reports' });
  if (rate != null && rate >= 70) brief.push({ key: 'utilg', tone: 'good', text: tx('Units are working hard: {{pct}} of unit-days hired.', { pct: pctText(rate) }) });
  if (num(revenue.data?.total) === 0 && open.length > 0) brief.push({ key: 'none', tone: 'info', text: tx('No agreement has closed in this period, so no income is settled yet. {{value}} is under open contracts.', { value: cmoney(openValue) }), to: '/rental/agreements' });
  if (noUnit > 0) brief.push({ key: 'nounit', tone: 'warn', text: tx('{{count}} hired items have no unit assigned, so the yard still shows those units as available.', { count: qtyText(noUnit) }), to: '/rental/agreements?status=checked_out' });
  if ((agreements.data?.total ?? 0) > list.length) brief.push({ key: 'cap', tone: 'info', text: tx('Agreement charts use the latest {{n}} of {{total}} agreements.', { n: list.length, total: agreements.data?.total ?? 0 }) });

  return (
    <div className="space-y-5">
      <DashboardHero
        icon={KeyRound}
        title={tx('Rental Management')}
        subtitle={tx('Agreements, fleet utilisation, deposits and returns.')}
        period={period.key}
        onPeriod={setPeriod}
        fetching={agreements.isFetching || revenueTrend.isLoading}
        actions={<HeroAction to="/rental/agreements/new" icon={Plus} primary>{tx('New agreement')}</HeroAction>}
        headline={{
          label: tx('Value under open contracts'),
          value: openValue,
          format: (v) => cmoney(v),
          title: money(openValue, currency),
          sub: open.length === 1 ? tx('1 open agreement · {{onHire}} on hire', { onHire: onHire.length }) : tx('{{count}} open agreements · {{onHire}} on hire', { count: open.length, onHire: onHire.length }),
        }}
        chartTitle={tx('Contract value booked, by start date')}
        chart={<TrendChart onHero height={170} data={booked} format={(v) => cmoney(v)} axisFormat={compactNumber} series={[{ key: 'value', label: tx('Contract value'), color: 'var(--sb-active-bar)' }]} />}
        stats={(
          <>
            <HeroStat label={tx('Utilisation')} value={pctText(rate)} sub={tx('{{h}} of {{t}} unit-days', { h: qtyText(num(utilisation.data?.hiredUnitDays)), t: qtyText(num(utilisation.data?.totalUnitDays)) })} to="/rental/reports" ring={rate} />
            <HeroStat label={tx('Settled income')} value={cmoney(revenue.data?.total)} title={money(revenue.data?.total, currency)} sub={(revenue.data?.closedAgreements ?? 0) === 1 ? tx('1 closed agreement') : tx('{{count}} closed agreements', { count: revenue.data?.closedAgreements ?? 0 })} to="/rental/reports" />
            <HeroStat label={tx('Units in the fleet')} value={qtyText(fleetSize)} sub={tx('{{count}} available', { count: fleet.data?.byStatus.available ?? 0 })} to="/rental/units" />
            <HeroStat label={tx('On hire')} value={qtyText(qtyOut)} sub={tx('items out with customers')} to="/rental/agreements?status=checked_out" />
            <HeroStat label={tx('Overdue')} value={qtyText(overdue.length)} sub={tx('past return date')} to="/rental/agreements?status=checked_out" />
            <HeroStat label={tx('Pending')} value={qtyText(pending.length)} sub={tx('awaiting approval')} to="/rental/agreements?status=pending_approval" />
          </>
        )}
      />

      <Reveal delay={80} className="grid gap-5 lg:grid-cols-2">
        <Brief items={brief} />
        <Panel title={tx('Utilisation')} hint={tx('Share of unit-days on hire')} href="/rental/reports" action={tx('Report')}>
          <div className="grid items-center gap-4 sm:grid-cols-3">
            <Gauge label={tx('This period')} value={rate} threshold={{ good: 70, warn: 40 }} />
            <div className="sm:col-span-2"><TrendChart kind="line" height={150} data={utilRows} format={(v) => `${qtyText(v, 1)}%`} series={[{ key: 'pct', label: tx('Utilisation') }]} /></div>
          </div>
        </Panel>
      </Reveal>

      <Reveal delay={140}>
        <Panel title={tx('The unit yard')} hint={tx('Every rental unit by product. Hover a unit for its code and status.')} href="/rental/units" action={tx('View all')}>
          {byProduct.length === 0 ? <Empty>{tx('No rental units yet.')}</Empty> : (
            <>
              <div className="mb-3"><Legend items={statusCounts.map(([s, c]) => ({ label: statusOf(s).label, color: statusOf(s).color, value: String(c) }))} /></div>
              <div className="grid gap-x-8 gap-y-3 md:grid-cols-2">
                {byProduct.map((g) => (
                  <div key={g.key}>
                    <div className="mb-1 flex justify-between gap-2 text-xs"><span className="truncate font-medium" title={g.name}>{g.name}</span><span className="shrink-0 tabular-nums text-muted-foreground">{g.units.length}</span></div>
                    <ul className="flex flex-wrap gap-1">
                      {g.units.map((u) => {
                        const st = statusOf(u.status);
                        return (
                          <li key={u.id}>
                            <Link to={`/rental/units?search=${encodeURIComponent(u.unitCode)}`} className="block h-6 w-6 rounded transition-transform hover:scale-110 motion-reduce:hover:scale-100" style={{ background: st.color }} title={`${u.unitCode}: ${st.label}`} aria-label={`${u.unitCode}: ${st.label}`} />
                          </li>
                        );
                      })}
                    </ul>
                  </div>
                ))}
              </div>
            </>
          )}
        </Panel>
      </Reveal>

      <Reveal delay={200} className="grid gap-5 lg:grid-cols-3">
        <Panel title={tx('Agreement pipeline')} hint={tx('All agreements by stage')} href="/rental/agreements" action={tx('View all')}>
          <SegmentBar rows={pipeline} />
          {damageHeld > 0 && <p className="mt-3 text-xs text-muted-foreground">{tx('Damage charged to date: {{value}}', { value: cmoney(damageHeld) })}</p>}
        </Panel>
        <Panel title={tx('Top customers')} hint={tx('Contract value, all time')}>
          <RankedBars rows={customers} limit={5} />
        </Panel>
        <Panel title={tx('Unit condition')} hint={tx('Latest grade on each unit')}>
          <Donut rows={conditions} center={String(fleetSize)} centerSub={tx('units')} format={(v) => qtyText(v)} size={140} />
        </Panel>
      </Reveal>

      <Reveal delay={260} className="grid gap-5 lg:grid-cols-3">
        <Panel title={tx('Settled income')} hint={tx('Closed agreements in the period')} href="/rental/reports" action={tx('Report')}>
          <TrendChart kind="stacked" height={190} data={income} format={(v) => cmoney(v)} axisFormat={compactNumber} series={[{ key: 'rental', label: tx('Rental') }, { key: 'late', label: tx('Late fees') }, { key: 'damage', label: tx('Damage recovered') }]} />
        </Panel>
        <Panel title={tx('Best-earning products')} hint={tx('Lifetime rental revenue')} href="/rental/catalog" action={tx('Catalog')}>
          <RankedBars rows={earners} limit={5} />
        </Panel>
        <Panel title={tx('Due back next')} hint={tx('Open agreements by return date')} href="/rental/agreements" action={tx('View all')}>
          {dueBack.length === 0 ? <Empty>{tx('Nothing is out right now.')}</Empty> : (
            <ul className="divide-y text-sm">
              {dueBack.map((a) => {
                const days = Math.ceil((new Date(a.dueAt).getTime() - now) / 86_400_000);
                return (
                  <li key={a.id} className="py-1.5">
                    <Link to={`/rental/agreements/${a.id}`} className="flex items-center gap-2 hover:text-primary">
                      <CalendarClock className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden />
                      <span className="min-w-0 flex-1 truncate font-medium">{a.agreementNumber}</span>
                      {isOverdue(a, now)
                        ? <FlagPill tone="critical">{tx('{{d}} d late', { d: Math.abs(days) })}</FlagPill>
                        : <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{date(a.dueAt)}</span>}
                    </Link>
                    <div className="truncate ps-[22px] text-[11px] text-muted-foreground">{a.partner?.name ?? ''} · {cmoney(contractValue(a))}</div>
                  </li>
                );
              })}
            </ul>
          )}
        </Panel>
      </Reveal>
    </div>
  );
}

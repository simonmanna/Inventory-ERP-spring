import { useState } from 'react';
import { BarChart3 } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { FilterSelect, ListPageHeader } from '@/components/list';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { LOSS_LABEL, useQuarryLookups, useQuarryReport, type LossKind, type Variance } from '@/features/quarry/api';
import { StatTile, VariancePill, qty, tonnes } from './quarry-ui';

type Row = Record<string, string | number | null | Variance>;
const s = (v: unknown) => (v === null || v === undefined ? '' : String(v));

function Table({ heads, rows }: { heads: string[]; rows: Array<Array<React.ReactNode>> }) {
  if (!rows.length) return <p className="rounded-xl border bg-card p-6 text-center text-sm text-muted-foreground">{tx('No data for this period.')}</p>;
  return (
    <div className="overflow-x-auto rounded-xl border bg-card">
      <table className="w-full text-sm">
        <thead><tr className="bg-muted/40 text-left text-xs uppercase text-muted-foreground">{heads.map((h, i) => <th key={i} className={`px-3 py-2 ${i > 0 ? 'text-right' : ''}`}>{h}</th>)}</tr></thead>
        <tbody>{rows.map((r, i) => <tr key={i} className="border-t">{r.map((c, j) => <td key={j} className={`px-3 py-2 ${j > 0 ? 'text-right tabular-nums' : ''}`}>{c}</td>)}</tr>)}</tbody>
      </table>
    </div>
  );
}

/** Quarry reports: extraction vs target, cost per tonne by site and period, losses, royalties & levies. */
export function QuarryReportsPage() {
  const first = new Date(); first.setDate(1);
  const [from, setFrom] = useState(first.toISOString().slice(0, 10));
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const [siteId, setSiteId] = useState('');
  const [groupBy, setGroupBy] = useState('month');
  const lookups = useQuarryLookups();
  const p = { dateFrom: from, dateTo: to, siteId };
  const target = useQuarryReport('extraction-vs-target', p);
  const cpt = useQuarryReport('cost-per-tonne', { ...p, groupBy });
  const losses = useQuarryReport('losses', p);
  const royalties = useQuarryReport('royalties', p);
  const rows = (r: typeof target) => ((r.data?.rows ?? []) as Row[]);
  const totals = (r: typeof target) => ((r.data?.totals ?? {}) as Record<string, string | null>);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={BarChart3} title={tx('Quarry Reports')} description={tx('Posted extraction only. Default: this month to date.')} />
      <div className="flex flex-wrap items-center gap-2">
        <Input type="date" className="w-40" value={from} onChange={(e) => setFrom(e.target.value)} />
        <Input type="date" className="w-40" value={to} onChange={(e) => setTo(e.target.value)} />
        <FilterSelect value={siteId} onChange={setSiteId} options={(lookups.data?.sites ?? []).map((x) => ({ value: x.id, label: x.code }))} allLabel={tx('All sites')} />
      </div>
      <Tabs defaultValue="target">
        <TabsList className="flex-wrap">
          <TabsTrigger value="target">{tx('Extraction vs target')}</TabsTrigger>
          <TabsTrigger value="cost">{tx('Cost per tonne')}</TabsTrigger>
          <TabsTrigger value="losses">{tx('Losses')}</TabsTrigger>
          <TabsTrigger value="royalties">{tx('Royalties & levies')}</TabsTrigger>
        </TabsList>
        <TabsContent value="target" className="space-y-3">
          {target.isLoading ? <Skeleton className="h-32" /> : (
            <>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                <StatTile label={tx('Target')} value={tonnes(totals(target).targetTonnes)} />
                <StatTile label={tx('Actual')} value={tonnes(totals(target).actualTonnes)} />
                <StatTile label={tx('Achieved')} value={`${qty(totals(target).achievedPct, 1)}%`} />
              </div>
              <Table heads={[tx('Plan'), tx('Period'), tx('Target'), tx('Actual'), tx('Variance'), tx('Cost / t'), tx('Pace')]}
                rows={rows(target).map((r) => [`${s(r.code)} · ${s(r.siteCode)}${r.zoneCode ? ` · ${s(r.zoneCode)}` : ''}`, `${date(s(r.periodStart))} – ${date(s(r.periodEnd))}`,
                  tonnes(s(r.targetTonnes)), tonnes(s(r.actualTonnes)), tonnes((r.variance as Variance).variance),
                  r.actualCostPerTonne ? money(s(r.actualCostPerTonne)) : '—', <VariancePill key="v" status={(r.variance as Variance).status} />])} />
            </>
          )}
        </TabsContent>
        <TabsContent value="cost" className="space-y-3">
          <FilterSelect value={groupBy} onChange={(v) => setGroupBy(v || 'month')} allLabel={tx('By month')}
            options={[{ value: 'day', label: tx('By day') }, { value: 'week', label: tx('By week') }, { value: 'site', label: tx('By site only') }]} />
          {cpt.isLoading ? <Skeleton className="h-32" /> : (
            <>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                <StatTile label={tx('Net tonnes')} value={tonnes(totals(cpt).netTonnes)} />
                <StatTile label={tx('Total cost')} value={money(totals(cpt).totalCost)} />
                <StatTile label={tx('Cost per tonne')} value={totals(cpt).costPerTonne ? money(totals(cpt).costPerTonne) : '—'} />
                <StatTile label={tx('Loss %')} value={`${qty(totals(cpt).lossPct, 2)}%`} />
              </div>
              <Table heads={[tx('Site / period'), tx('Net'), tx('Fuel'), tx('Labour'), tx('Equipment'), tx('Blasting'), tx('Royalties'), tx('Other'), tx('Total'), tx('Cost / t')]}
                rows={rows(cpt).map((r) => [`${s(r.siteCode)}${r.period ? ` · ${date(s(r.period))}` : ''}`, tonnes(s(r.netTonnes)), money(s(r.fuelCost)),
                  money(s(r.labourCost)), money(s(r.equipmentCost)), money(s(r.blastingCost)), money(s(r.royaltyCost)), money(s(r.otherCost)),
                  money(s(r.totalCost)), r.costPerTonne ? money(s(r.costPerTonne)) : '—'])} />
            </>
          )}
        </TabsContent>
        <TabsContent value="losses" className="space-y-3">
          {losses.isLoading ? <Skeleton className="h-32" /> : (
            <>
              <StatTile label={tx('Total losses')} value={tonnes(totals(losses).tonnes)} />
              <Table heads={[tx('Site / kind'), tx('On extraction'), tx('Standalone'), tx('Total')]}
                rows={rows(losses).map((r) => [`${s(r.siteCode)} · ${LOSS_LABEL[s(r.kind) as LossKind] ?? s(r.kind)}`, tonnes(s(r.extractionTonnes)), tonnes(s(r.standaloneTonnes)), tonnes(s(r.tonnes))])} />
              <Table heads={[tx('Site'), tx('Gross'), tx('Losses'), tx('Net'), tx('Loss %'), tx('Loss written off')]}
                rows={((losses.data?.sites ?? []) as Row[]).map((r) => [s(r.siteCode), tonnes(s(r.grossTonnes)), tonnes(s(r.lossTonnes)), tonnes(s(r.netTonnes)), `${qty(s(r.lossPct), 2)}%`, money(s(r.lossCost))])} />
            </>
          )}
        </TabsContent>
        <TabsContent value="royalties" className="space-y-3">
          {royalties.isLoading ? <Skeleton className="h-32" /> : (
            <>
              <StatTile label={tx('Royalties & levies due')} value={money(totals(royalties).amount)} />
              <Table heads={[tx('Site / rate'), tx('Authority'), tx('Tonnes'), tx('Rate / t'), tx('Amount')]}
                rows={rows(royalties).map((r) => [`${s(r.siteCode)} · ${s(r.name)}`, s(r.authorityName) || '—', tonnes(s(r.tonnes)), money(s(r.ratePerTonne)), money(s(r.amount))])} />
            </>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}

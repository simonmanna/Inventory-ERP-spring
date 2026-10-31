import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Factory, Pencil, Send, Trash2, Undo2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  COST_KIND_LABEL, FEED_METHOD_LABEL, OUTPUT_METHOD_LABEL, ROLE_LABEL, SHIFT_LABEL, useDeleteRun, usePostRun, useReverseRun, useRun,
} from '@/features/crushing/api';
import { EmptyState, JournalTable, KeyValues, ReasonDialog, RunStatusPill, Section, StatTile, pct, qty, tonnes } from './crushing-ui';

/** A crushing run: expected vs actual split, yield and loss, cost per tonne per fraction, meters, costs, GL; post / reverse. */
export function CrushingRunDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: r, isLoading, isError } = useRun(id);
  const postRun = usePostRun();
  const reverse = useReverseRun();
  const remove = useDeleteRun();
  const [reversing, setReversing] = useState(false);
  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (isError || !r) return <EmptyState>{tx('Crushing run not found.')}</EmptyState>;
  const p = r.preview;
  const draft = r.status === 'draft';
  const doPost = async () => {
    try { await postRun.mutateAsync(r.id); notify.success(tx('Posted')); } catch (err) { notify.error(apiMessage(err, tx('Could not post'))); }
  };
  const doDelete = async () => {
    try { await remove.mutateAsync(r.id); navigate('/manufacturing/crushing'); } catch (err) { notify.error(apiMessage(err, tx('Could not delete'))); }
  };
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Factory} title={`${r.code} · ${r.plantCode}`} description={`${date(r.runDate)} · ${SHIFT_LABEL[r.shift]}${r.operatorName ? ` · ${r.operatorName}` : ''}`}
        actions={
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" asChild><Link to="/manufacturing/crushing"><ArrowLeft className="mr-1.5 h-4 w-4" />{tx('Back')}</Link></Button>
            {draft && has(PERMISSIONS.crushingRun.record) && <Button variant="outline" onClick={() => navigate(`/manufacturing/crushing/runs/${r.id}/edit`)}><Pencil className="mr-1.5 h-4 w-4" />{tx('Edit')}</Button>}
            {draft && has(PERMISSIONS.crushingRun.record) && <Button variant="outline" onClick={doDelete} disabled={remove.isPending}><Trash2 className="mr-1.5 h-4 w-4" />{tx('Delete')}</Button>}
            {draft && has(PERMISSIONS.crushingRun.post) && <Button onClick={doPost} disabled={postRun.isPending || !!p.balanceError}><Send className="mr-1.5 h-4 w-4" />{tx('Post run')}</Button>}
            {r.status === 'posted' && has(PERMISSIONS.crushingRun.post) && <Button variant="destructive" onClick={() => setReversing(true)}><Undo2 className="mr-1.5 h-4 w-4" />{tx('Reverse')}</Button>}
          </div>
        } />
      <div className="flex flex-wrap items-center gap-2"><RunStatusPill status={r.status} />{r.orderCode && <span className="text-sm text-muted-foreground">{tx('Production order')} {r.orderCode}</span>}</div>
      {p.balanceError && <p className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">{p.balanceError}</p>}
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4 lg:grid-cols-6">
        <StatTile label={tx('Feed')} value={tonnes(r.feedTonnes)} hint={FEED_METHOD_LABEL[r.feedMethod]} />
        <StatTile label={tx('Output')} value={tonnes(p.outputTonnes)} hint={OUTPUT_METHOD_LABEL[r.outputMethod]} />
        <StatTile label={tx('Loss')} value={tonnes(p.lossTonnes)} tone={Number(p.lossTonnes) < 0 ? 'danger' : undefined} />
        <StatTile label={tx('Yield')} value={pct(p.actualYieldPct)} hint={`${tx('expected')} ${pct(p.expectedYieldPct)}`} tone={Number(p.yieldVariancePct) < 0 ? 'warning' : undefined} />
        <StatTile label={tx('Throughput')} value={p.throughputTph ? `${qty(p.throughputTph)} t/h` : '—'} hint={p.utilisationPct ? `${pct(p.utilisationPct)} ${tx('of capacity')}` : undefined} />
        <StatTile label={p.estimated ? tx('Estimated cost') : tx('Total cost')} value={money(p.totalCost)} hint={p.costPerTonne ? `${money(p.costPerTonne)} / t` : undefined} />
      </div>
      <Section title={tx('Output per size fraction')}>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted-foreground">
              <tr><th className="py-1.5">{tx('Product')}</th><th>{tx('Role')}</th><th className="text-right">{tx('Expected')}</th><th className="text-right">{tx('Actual')}</th><th className="text-right">{tx('Variance')}</th><th className="text-right">{tx('Value')}</th><th className="text-right">{tx('Per tonne')}</th><th>{tx('Stockpile')}</th></tr>
            </thead>
            <tbody>
              {r.outputs.map((o) => (
                <tr key={o.id} className="border-t">
                  <td className="py-1.5">{o.sizeLabel ? `${o.sizeLabel} · ` : ''}{o.productName}</td>
                  <td>{ROLE_LABEL[o.role]}</td>
                  <td className="text-right tabular-nums">{tonnes(o.expectedTonnes)} <span className="text-xs text-muted-foreground">{pct(o.expectedPct)}</span></td>
                  <td className="text-right tabular-nums">{tonnes(o.actualTonnes)} <span className="text-xs text-muted-foreground">{pct(o.actualPct)}</span></td>
                  <td className="text-right tabular-nums">{qty(Number(o.actualTonnes) - Number(o.expectedTonnes))} t</td>
                  <td className="text-right tabular-nums">{o.value ? money(o.value) : '—'}</td>
                  <td className="text-right tabular-nums">{o.unitCost ? money(o.unitCost) : '—'}</td>
                  <td>{o.stocked ? o.stockpileCode ?? '—' : tx('Not stocked')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>
      <Section title={tx('Run details')}>
        <KeyValues items={[
          [tx('Grading profile'), `${r.profileCode} v${r.profileVersion}`],
          [tx('Feed stockpile'), `${r.feedStockpileCode} · ${r.feedProductName}`],
          [tx('Recirculated oversize'), `${tonnes(r.recirculatedTonnes)} (${pct(p.circulatingLoadPct)})`],
          [tx('Operating hours'), qty(r.operatingHours)],
          [tx('Downtime hours'), `${qty(r.downtimeHours)}${r.downtimeReason ? ` · ${r.downtimeReason}` : ''}`],
          [tx('Availability'), pct(p.availabilityPct)],
          [tx('Crew labour hours'), qty(r.labourHours)],
          [tx('Feed cost'), money(p.feedCost)],
          [tx('Conversion cost'), money(p.conversionCost)],
          [tx('Abnormal loss written off'), p.abnormalLossValue ? money(p.abnormalLossValue) : '—'],
          [tx('Notes'), r.notes],
          [tx('Reversal reason'), r.reversalReason],
        ]} />
      </Section>
      <div className="grid gap-4 lg:grid-cols-2">
        <Section title={tx('Meter readings')}>
          {r.meters.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No meter readings.')}</p> : (
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-muted-foreground"><tr><th className="py-1.5">{tx('Unit')}</th><th className="text-right">{tx('Hours')}</th><th className="text-right">kWh</th><th className="text-right">{tx('Diesel litres')}</th></tr></thead>
              <tbody>{r.meters.map((m) => (
                <tr key={m.id} className="border-t"><td className="py-1.5">{m.unitCode} · {m.unitName}</td><td className="text-right tabular-nums">{qty(m.runHours)}</td><td className="text-right tabular-nums">{qty(m.kwh)}</td><td className="text-right tabular-nums">{qty(m.dieselLitres)}</td></tr>
              ))}</tbody>
            </table>
          )}
        </Section>
        <Section title={tx('Conversion cost')}>
          {r.costs.length === 0 ? <p className="text-sm text-muted-foreground">{draft ? tx('Diesel, electricity and crew labour are costed from the readings above.') : tx('No cost lines.')}</p> : (
            <table className="w-full text-sm">
              <tbody>{r.costs.map((c) => (
                <tr key={c.id} className="border-t"><td className="py-1.5">{COST_KIND_LABEL[c.kind] ?? c.kind}{c.unitCode ? ` · ${c.unitCode}` : ''}{c.description ? ` · ${c.description}` : ''}</td><td className="text-right tabular-nums">{money(c.amount)}</td></tr>
              ))}</tbody>
            </table>
          )}
        </Section>
      </div>
      {!draft && <Section title={tx('GL entries')}><JournalTable entries={r.journals} /></Section>}
      {reversing && <ReasonDialog title={tx('Reverse crushing run')} description={tx('Stock and GL of the run are reversed; its products must still be in their stockpiles.')}
        confirmLabel={tx('Reverse')} busy={reverse.isPending} onClose={() => setReversing(false)}
        onConfirm={async (reason) => { await reverse.mutateAsync({ id: r.id, reason }); notify.success(tx('Reversed')); }} />}
    </div>
  );
}

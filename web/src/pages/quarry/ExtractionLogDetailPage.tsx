import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, ClipboardList, Pencil, Send, Trash2, Undo2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  COMPONENT_LABEL, LOSS_LABEL, METHOD_LABEL, SHIFT_LABEL, useCostPreview, useDeleteRow, useExtractionLog, usePostLog, useReverseLog,
} from '@/features/quarry/api';
import { EmptyState, JournalTable, KeyValues, LogStatusPill, ReasonDialog, Section, qty, tonnes } from './quarry-ui';

/** An extraction log: quantities, equipment / labour, losses, the cost build-up (or preview), GL; post / reverse. */
export function ExtractionLogDetailPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: l, isLoading, isError } = useExtractionLog(id);
  const preview = useCostPreview(id, l?.status === 'draft');
  const post = usePostLog();
  const reverse = useReverseLog();
  const remove = useDeleteRow('/quarry/extraction-logs');
  const [reversing, setReversing] = useState(false);

  if (isLoading) return <Skeleton className="h-96" />;
  if (isError || !l) return <EmptyState>{tx('Could not load the extraction log.')}</EmptyState>;
  const draft = l.status === 'draft';
  const costs = draft ? (preview.data?.lines ?? []) : l.costs;

  return (
    <div className="space-y-4">
      <Button variant="ghost" size="sm" asChild><Link to="/quarry/extraction"><ArrowLeft className="mr-1 h-4 w-4" /> {tx('Extraction logs')}</Link></Button>
      <ListPageHeader icon={ClipboardList} title={`${l.code} · ${l.siteCode}`} description={`${date(l.extractedOn)} · ${SHIFT_LABEL[l.shift]} · ${l.materialName}`}
        actions={
          <>
            <LogStatusPill status={l.status} />
            {draft && has(PERMISSIONS.quarryExtraction.update) && (
              <>
                <Button variant="outline" onClick={() => navigate(`/quarry/extraction/${l.id}/edit`)}><Pencil className="mr-1.5 h-4 w-4" /> {tx('Edit')}</Button>
                <Button variant="outline" onClick={async () => {
                  try { await remove.mutateAsync(l.id); notify.success(tx('Deleted')); navigate('/quarry/extraction'); } catch (e) { notify.error(apiMessage(e, tx('Could not delete'))); }
                }}><Trash2 className="mr-1.5 h-4 w-4" /> {tx('Delete')}</Button>
              </>
            )}
            {draft && has(PERMISSIONS.quarryExtraction.post) && (
              <Button disabled={post.isPending} onClick={async () => {
                try { await post.mutateAsync(l.id); notify.success(tx('Extraction posted into ROM stock')); } catch (e) { notify.error(apiMessage(e, tx('Could not post'))); }
              }}><Send className="mr-1.5 h-4 w-4" /> {tx('Post to ROM stock')}</Button>
            )}
            {l.status === 'posted' && has(PERMISSIONS.quarryExtraction.post) && (
              <Button variant="outline" onClick={() => setReversing(true)}><Undo2 className="mr-1.5 h-4 w-4" /> {tx('Reverse')}</Button>
            )}
          </>
        } />
      <Section title={tx('Summary')}>
        <KeyValues items={[
          [tx('Zone / bench / face'), l.zoneCode ? `${l.zoneCode} · ${l.zoneName}` : tx('Whole site')],
          [tx('ROM stockpile'), <Link key="k" className="text-primary hover:underline" to={`/quarry/stockpiles/${l.stockpileId}`}>{l.stockpileCode}</Link>],
          [tx('Supervisor'), l.supervisor?.name],
          [tx('Blast'), l.blastId ? <Link key="b" className="text-primary hover:underline" to={`/quarry/blasts/${l.blastId}`}>{l.blastCode}</Link> : null],
          [tx('Gross'), tonnes(l.grossTonnes)],
          [tx('Losses'), tonnes(l.lossTonnes)],
          [tx('Net into stock'), tonnes(l.netTonnes)],
          [tx('Total cost'), draft ? (preview.data ? `${money(preview.data.totalCost)} (${tx('preview')})` : '—') : money(l.totalCost)],
          [tx('Cost per tonne'), draft ? (preview.data ? money(preview.data.unitCost) : '—') : l.unitCost ? money(l.unitCost) : '—'],
          [tx('Allocation'), l.allocationBasis === 'gross_tonnes' ? tx('Per gross tonne (loss share written off)') : l.allocationBasis ? tx('Per net tonne') : null],
          [tx('Posted'), l.postedAt ? dateTime(l.postedAt) : null],
          [tx('Reversed'), l.reversedAt ? `${dateTime(l.reversedAt)} · ${l.reversalReason ?? ''}` : null],
        ]} />
        {draft && preview.isError && <p className="text-sm text-amber-700">{apiMessage(preview.error, tx('The cost cannot be previewed yet'))}</p>}
      </Section>
      <div className="grid gap-4 lg:grid-cols-2">
        <Section title={tx('Quantity')}>
          <ul className="divide-y text-sm">
            {l.loads.map((x) => (
              <li key={x.id} className="flex justify-between gap-2 py-2">
                <span>{METHOD_LABEL[x.method]}{x.truckCode ? ` · ${x.truckCode}` : ''}{x.loadCount && x.method === 'loads' ? ` · ${x.loadCount} × ${qty(x.capacityT)} t` : ''}
                  {x.ticketNumber ? ` · ${x.ticketNumber}` : ''}{x.haulageCode ? ` · ${x.haulageCode}` : ''}</span>
                <span className="tabular-nums">{tonnes(x.tonnes)}</span>
              </li>
            ))}
            {l.losses.map((x) => (
              <li key={x.id} className="flex justify-between gap-2 py-2 text-rose-700">
                <span>{LOSS_LABEL[x.kind]}{x.reason ? ` · ${x.reason}` : ''}</span><span className="tabular-nums">−{tonnes(x.tonnes)}</span>
              </li>
            ))}
          </ul>
        </Section>
        <Section title={tx('Equipment & labour')}>
          {l.resources.length === 0 ? <p className="text-sm text-muted-foreground">{tx('None recorded.')}</p> : (
            <ul className="divide-y text-sm">
              {l.resources.map((r) => (
                <li key={r.id} className="flex justify-between gap-2 py-2">
                  <span>{r.kind === 'equipment' ? `${r.vehicleCode ?? ''} · ${r.vehicleClass ?? ''}` : r.role ?? tx('Labour')}{r.employeeName ? ` · ${r.employeeName}` : ''}</span>
                  <span className="tabular-nums">{qty(r.hours)} h{r.fuelLitres ? ` · ${qty(r.fuelLitres)} L` : ''}{r.hoursCost ? ` · ${money(Number(r.hoursCost) + Number(r.fuelCost ?? 0))}` : ''}</span>
                </li>
              ))}
            </ul>
          )}
        </Section>
      </div>
      <Section title={draft ? tx('Cost build-up (preview)') : tx('Cost build-up')}>
        {costs.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No cost lines (no rates set).')}</p> : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr className="text-left text-muted-foreground"><th className="py-1">{tx('Component')}</th><th>{tx('Description')}</th><th className="text-right">{tx('Quantity')}</th><th className="text-right">{tx('Rate')}</th><th className="text-right">{tx('Amount')}</th></tr></thead>
              <tbody>
                {costs.map((c, i) => (
                  <tr key={i} className="border-t">
                    <td className="py-1.5">{COMPONENT_LABEL[c.component] ?? c.component}</td><td>{c.description}</td>
                    <td className="text-right tabular-nums">{qty(c.quantity, 3)}</td><td className="text-right tabular-nums">{qty(c.rate, 4)}</td>
                    <td className="text-right tabular-nums">{money(c.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>
      {!draft && <Section title={tx('General ledger')}><JournalTable entries={l.journal} /></Section>}
      {reversing && (
        <ReasonDialog title={tx('Reverse extraction {{code}}', { code: l.code })} confirmLabel={tx('Reverse')} busy={reverse.isPending}
          description={tx('The stone leaves the ROM stockpile at the cost it came in with and the GL entry is reversed.')}
          onConfirm={async (reason) => { await reverse.mutateAsync({ id: l.id, reason }); notify.success(tx('Extraction reversed')); }}
          onClose={() => setReversing(false)} />
      )}
    </div>
  );
}

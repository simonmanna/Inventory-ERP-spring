import { useState } from 'react';
import { Layers, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTablePagination, FilterSelect, ListPageHeader, ListToolbar, useListState } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { Field, OptionalSelect } from '@/pages/fleet/fleet-ui';
import {
  SURVEY_METHOD_LABEL, SURVEY_STATUS_LABEL, useCrushingLookups, useCrushingStockpiles, useDeleteSurvey, useSaveSurvey, useSurvey,
  useSurveyAction, useSurveys, type StockpileRow,
} from '@/features/crushing/api';
import { EmptyState, JournalTable, KeyValues, ReasonDialog, Section, SurveyStatusPill, pct, qty, toOptions, today, tonnes } from './crushing-ui';

/** New / edit survey: volume × bulk density = tonnes for one product of a stockpile. */
function SurveyDialog({ piles, initial, onClose }: { piles: StockpileRow[]; initial: { id?: string; stockpileId: string; productId: string }; onClose: () => void }) {
  const save = useSaveSurvey();
  const lookups = useCrushingLookups();
  const [v, setV] = useState({ stockpileId: initial.stockpileId, productId: initial.productId, surveyDate: today(), method: 'drone', surveyor: '', volumeM3: '', bulkDensity: '' });
  const pile = piles.find((k) => k.id === v.stockpileId);
  const tonnesNow = (Number(v.volumeM3) || 0) * (Number(v.bulkDensity) || 0);
  const submit = async () => {
    if (!v.stockpileId || !v.productId || v.volumeM3 === '' || !(Number(v.bulkDensity) > 0)) { notify.error(tx('Fill in the stockpile, product, volume and bulk density.')); return; }
    try {
      await save.mutateAsync({ id: initial.id, body: { ...v, surveyor: v.surveyor.trim() || null, volumeM3: Number(v.volumeM3), bulkDensity: Number(v.bulkDensity) } });
      notify.success(tx('Saved'));
      onClose();
    } catch (err) { notify.error(apiMessage(err, tx('Could not save'))); }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader><DialogTitle>{tx('Stockpile survey')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Stockpile *')}><OptionalSelect value={v.stockpileId} onChange={(x) => setV((s) => ({ ...s, stockpileId: x, productId: '' }))} placeholder={tx('Choose…')}
            options={(lookups.data?.stockpiles ?? []).map((k) => ({ value: k.id, label: `${k.siteCode} · ${k.code} · ${k.name}` }))} /></Field>
          <Field label={tx('Product *')}><OptionalSelect value={v.productId} onChange={(x) => setV((s) => ({ ...s, productId: x }))} placeholder={tx('Choose…')}
            options={(pile?.stock ?? []).map((p) => ({ value: p.productId, label: `${p.productName} · ${qty(p.onHandTonnes)} t` }))} /></Field>
          <Field label={tx('Survey date *')}><Input type="date" max={today()} value={v.surveyDate} onChange={(e) => setV((s) => ({ ...s, surveyDate: e.target.value }))} /></Field>
          <Field label={tx('Method')}><OptionalSelect value={v.method} onChange={(x) => setV((s) => ({ ...s, method: x || 'drone' }))} placeholder={tx('Choose…')} options={toOptions(SURVEY_METHOD_LABEL)} /></Field>
          <Field label={tx('Surveyor')}><Input value={v.surveyor} onChange={(e) => setV((s) => ({ ...s, surveyor: e.target.value }))} /></Field>
          <Field label={tx('Volume (m³) *')}><Input type="number" inputMode="decimal" min={0} step="any" value={v.volumeM3} onChange={(e) => setV((s) => ({ ...s, volumeM3: e.target.value }))} /></Field>
          <Field label={tx('Bulk density (t/m³) *')}><Input type="number" inputMode="decimal" min={0} step="any" value={v.bulkDensity} onChange={(e) => setV((s) => ({ ...s, bulkDensity: e.target.value }))} /></Field>
          <Field label={tx('Surveyed tonnes')}><Input value={qty(tonnesNow, 3)} disabled /></Field>
        </div>
        <DialogFooter><Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button><Button onClick={submit} disabled={save.isPending}>{tx('Save')}</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** One survey: variance against book, submit / approve / reject / reverse, GL entries. */
function SurveyDetailDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const has = useAuthStore((st) => st.hasPermission);
  const { data: v, isLoading } = useSurvey(id);
  const act = useSurveyAction();
  const remove = useDeleteSurvey();
  const [reason, setReason] = useState<'reject' | 'reverse' | null>(null);
  const run = async (action: 'submit' | 'approve') => {
    try { await act.mutateAsync({ id, action }); notify.success(tx('Saved')); } catch (err) { notify.error(apiMessage(err, tx('The action failed'))); }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader><DialogTitle>{v ? `${v.code} · ${v.stockpileCode}` : tx('Stockpile survey')}</DialogTitle></DialogHeader>
        {isLoading || !v ? <Skeleton className="h-48 w-full" /> : (
          <div className="space-y-3">
            <SurveyStatusPill status={v.status} />
            <KeyValues items={[
              [tx('Product'), v.productName], [tx('Survey date'), date(v.surveyDate)], [tx('Method'), SURVEY_METHOD_LABEL[v.method] ?? v.method],
              [tx('Surveyor'), v.surveyor], [tx('Volume (m³)'), qty(v.volumeM3)], [tx('Bulk density (t/m³)'), qty(v.bulkDensity, 4)],
              [tx('Surveyed tonnes'), tonnes(v.surveyedTonnes)], [tx('Book tonnes'), tonnes(v.bookTonnes ?? v.currentBookTonnes)],
              [tx('Variance'), `${tonnes(v.varianceTonnes ?? v.previewVarianceTonnes)} (${pct(v.variancePct ?? v.previewVariancePct)})`],
              [tx('Variance value'), v.varianceValue ? money(v.varianceValue) : '—'], [tx('Reason'), v.rejectionReason ?? v.reversalReason],
            ]} />
            {v.journals.length > 0 && <JournalTable entries={v.journals} />}
          </div>
        )}
        <DialogFooter className="flex-wrap gap-2">
          {v?.status === 'draft' && has(PERMISSIONS.crushingSurvey.record) && <>
            <Button variant="outline" disabled={remove.isPending} onClick={async () => { try { await remove.mutateAsync(id); onClose(); } catch (err) { notify.error(apiMessage(err, tx('Could not delete'))); } }}>{tx('Delete')}</Button>
            <Button onClick={() => run('submit')} disabled={act.isPending}>{tx('Submit for approval')}</Button>
          </>}
          {v?.status === 'submitted' && has(PERMISSIONS.crushingSurvey.approve) && <>
            <Button variant="outline" onClick={() => setReason('reject')}>{tx('Reject')}</Button>
            <Button onClick={() => run('approve')} disabled={act.isPending}>{tx('Approve and adjust stock')}</Button>
          </>}
          {v?.status === 'posted' && has(PERMISSIONS.crushingSurvey.approve) && <Button variant="destructive" onClick={() => setReason('reverse')}>{tx('Reverse')}</Button>}
          <Button variant="outline" onClick={onClose}>{tx('Close')}</Button>
        </DialogFooter>
        {reason && <ReasonDialog title={reason === 'reject' ? tx('Reject survey') : tx('Reverse survey')} confirmLabel={reason === 'reject' ? tx('Reject') : tx('Reverse')}
          busy={act.isPending} onClose={() => setReason(null)} onConfirm={async (r) => { await act.mutateAsync({ id, action: reason, reason: r }); notify.success(tx('Saved')); }} />}
      </DialogContent>
    </Dialog>
  );
}

/** Finished-aggregate (and ROM) stockpiles with their stock and last survey, and the surveys with approval. */
export function CrushingStockpilesPage() {
  const has = useAuthStore((st) => st.hasPermission);
  const piles = useCrushingStockpiles();
  const list = useListState({ stockpileId: '', status: '' });
  const surveys = useSurveys({ page: list.page, pageSize: list.pageSize, stockpileId: list.filters.stockpileId, status: list.filters.status });
  const [creating, setCreating] = useState<{ stockpileId: string; productId: string } | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const rows = piles.data ?? [];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Layers} title={tx('Aggregate Stockpiles')} description={tx('Stock per stockpile and product; surveys (volume × density) adjust it after approval.')}
        actions={has(PERMISSIONS.crushingSurvey.record) && <Button onClick={() => setCreating({ stockpileId: '', productId: '' })}><Plus className="mr-1.5 h-4 w-4" /> {tx('New survey')}</Button>} />
      {piles.isLoading ? <Skeleton className="h-40 w-full" /> : rows.length === 0 ? <EmptyState>{piles.isError ? tx('Could not load the stockpiles. Try again.') : tx('No stockpile yet: create them under Quarry → Stockpiles.')}</EmptyState> : (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {rows.map((k) => (
            <div key={k.id} className="rounded-xl border bg-card p-3 shadow-sm">
              <div className="flex items-center justify-between gap-2"><span className="font-medium">{k.siteCode} · {k.code} · {k.name}</span><span className="text-xs text-muted-foreground">{k.kind}</span></div>
              <div className="text-sm tabular-nums">{tonnes(k.onHandTonnes)} · {money(k.onHandValue)}{k.capacityT ? ` · ${pct((Number(k.onHandTonnes) / Number(k.capacityT)) * 100)} ${tx('full')}` : ''}</div>
              <ul className="mt-2 space-y-1 text-sm">
                {k.stock.map((p) => {
                  const last = p.lastSurvey ? (JSON.parse(p.lastSurvey) as { code: string; surveyDate: string; surveyedTonnes: string }) : null;
                  return (
                    <li key={p.productId} className="flex items-center justify-between gap-2">
                      <span className="truncate">{p.productName}: {tonnes(p.onHandTonnes)}</span>
                      <span className="flex items-center gap-2 text-xs text-muted-foreground">
                        {last ? `${tx('Last survey')} ${date(last.surveyDate)}` : tx('Never surveyed')}
                        {has(PERMISSIONS.crushingSurvey.record) && <Button variant="ghost" size="sm" className="h-7 px-2" onClick={() => setCreating({ stockpileId: k.id, productId: p.productId })}>{tx('Survey')}</Button>}
                      </span>
                    </li>
                  );
                })}
              </ul>
            </div>
          ))}
        </div>
      )}
      <Section title={tx('Surveys')}>
        <ListToolbar>
          <FilterSelect value={list.filters.stockpileId} onChange={(v) => list.setFilter('stockpileId', v)} options={rows.map((k) => ({ value: k.id, label: k.code }))} allLabel={tx('All stockpiles')} />
          <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter('status', v)} options={toOptions(SURVEY_STATUS_LABEL)} allLabel={tx('All statuses')} />
        </ListToolbar>
        {(surveys.data?.data ?? []).length === 0 && !surveys.isLoading && <p className="text-sm text-muted-foreground">{tx('No survey recorded yet.')}</p>}
        <div className="space-y-2">
          {(surveys.data?.data ?? []).map((v) => (
            <button key={v.id} type="button" onClick={() => setOpen(v.id)} className="flex w-full flex-wrap items-center justify-between gap-2 rounded-lg border p-2 text-left text-sm hover:bg-accent">
              <span>{v.code} · {date(v.surveyDate)} · {v.stockpileCode} · {v.productName}</span>
              <span className="flex items-center gap-3 tabular-nums">{tonnes(v.surveyedTonnes)}{v.varianceTonnes ? ` (${qty(v.varianceTonnes)} t)` : ''} <SurveyStatusPill status={v.status} /></span>
            </button>
          ))}
        </div>
        {surveys.data?.meta && <DataTablePagination page={surveys.data.meta.page} pageSize={list.pageSize} total={surveys.data.meta.total} totalPages={surveys.data.meta.totalPages}
          onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="survey" />}
      </Section>
      {creating && <SurveyDialog piles={rows} initial={creating} onClose={() => setCreating(null)} />}
      {open && <SurveyDetailDialog id={open} onClose={() => setOpen(null)} />}
    </div>
  );
}

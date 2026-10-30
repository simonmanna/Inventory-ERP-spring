import { useState } from 'react';
import { toast } from 'sonner';
import { Plus, ShieldAlert, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  creditApi, useCreditDecisions, useCreditExposure, useCreditLimits, useCreditPolicy, useCreditReview, useSalesMutation,
  type CreditLimit,
} from '@/features/sales/api';
import { Field } from '@/pages/crm/crm-ui';
import { PartnerPicker } from '@/pages/crm/crm-dialogs';

type Tab = 'review' | 'limits' | 'decisions' | 'policy';

/** Credit management (ADR-S011 S3): review queue, limits, decisions, policy. */
export function CreditPage() {
  const [tab, setTab] = useState<Tab>('review');
  const TABS: Array<[Tab, string]> = [['review', tx('Review queue')], ['limits', tx('Credit limits')], ['decisions', tx('Decisions')], ['policy', tx('Policy')]];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={ShieldAlert} title={tx('Credit Control')} description={tx('Exposure, limits and holds: open orders, delivered-not-invoiced and receivables against each customer limit.')} />
      <div className="flex flex-wrap rounded-lg border bg-card p-0.5">
        {TABS.map(([k, l]) => <button key={k} onClick={() => setTab(k)} className={cn('rounded-md px-3 py-1.5 text-sm', tab === k ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>{l}</button>)}
      </div>
      {tab === 'review' && <ReviewQueue />}
      {tab === 'limits' && <Limits />}
      {tab === 'decisions' && <Decisions />}
      {tab === 'policy' && <Policy />}
    </div>
  );
}

function ReviewQueue() {
  const { data } = useCreditReview();
  return (
    <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
      <table className="w-full min-w-[820px] text-sm">
        <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
          <th className="px-3 py-2">{tx('Customer')}</th><th className="px-3 py-2 text-right">{tx('Limit')}</th><th className="px-3 py-2 text-right">{tx('Exposure')}</th>
          <th className="px-3 py-2 text-right">{tx('Headroom')}</th><th className="px-3 py-2 text-right">{tx('Overdue')}</th><th className="px-3 py-2">{tx('Status')}</th></tr></thead>
        <tbody>
          {(data ?? []).map((e) => (
            <tr key={e.partnerId} className="border-t">
              <td className="px-3 py-2 font-medium">{e.partnerName}</td>
              <td className="px-3 py-2 text-right tabular-nums">{e.limit > 0 ? money(e.limit) : '—'}</td>
              <td className="px-3 py-2 text-right tabular-nums">{money(e.exposure)}</td>
              <td className={cn('px-3 py-2 text-right tabular-nums', (e.headroom ?? 0) < 0 && 'text-rose-600')}>{e.headroom == null ? '—' : money(e.headroom)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{e.overdue.amount > 0 ? `${money(e.overdue.amount)} · ${e.overdue.maxDays}d` : '—'}</td>
              <td className="px-3 py-2">{e.creditHold ? <span className="rounded bg-rose-600 px-2 py-0.5 text-xs text-white">{tx('On hold')}</span> : e.riskCategory ?? ''}</td>
            </tr>
          ))}
          {data && data.length === 0 && <tr><td colSpan={6} className="p-6 text-center text-muted-foreground">{tx('No customer needs a credit review.')}</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

const blankLimit = (): CreditLimit => ({ partnerId: '', branchId: null, currencyCode: null, amount: 0, riskCategory: null, reviewDate: null, periodDays: null, validFrom: null, validTo: null, notes: null });

function Limits() {
  const { data } = useCreditLimits();
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.credit.manage));
  const [edit, setEdit] = useState<CreditLimit | null>(null);
  const save = useSalesMutation(creditApi.saveLimit);
  const del = useSalesMutation(creditApi.deleteLimit);
  const err = (e: unknown) => toast.error(apiErrorMessage(e, tx('Could not save')));
  return (
    <div className="space-y-2">
      {canManage && <Button onClick={() => setEdit(blankLimit())}><Plus className="mr-1 h-4 w-4" />{tx('New credit limit')}</Button>}
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[700px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className="px-3 py-2">{tx('Customer')}</th><th className="px-3 py-2 text-right">{tx('Limit')}</th><th className="px-3 py-2">{tx('Risk')}</th><th className="px-3 py-2">{tx('Review')}</th><th /></tr></thead>
          <tbody>
            {(data ?? []).map((l) => (
              <tr key={l.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => canManage && setEdit({ ...l })}>
                <td className="px-3 py-2">{l.partnerName}</td><td className="px-3 py-2 text-right tabular-nums">{money(l.amount)}</td>
                <td className="px-3 py-2">{l.riskCategory ?? '—'}</td><td className="px-3 py-2">{l.reviewDate ? date(l.reviewDate) : '—'}</td>
                <td className="px-3 py-2">{canManage && <button className="rounded p-1 text-muted-foreground hover:bg-muted" onClick={(e) => { e.stopPropagation(); del.mutate(l.id!, { onError: err }); }}><Trash2 className="h-4 w-4" /></button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {edit && (
        <Dialog open onOpenChange={(v) => !v && setEdit(null)}>
          <DialogContent className="max-w-lg">
            <DialogHeader><DialogTitle>{tx('Credit limit')}</DialogTitle></DialogHeader>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label={tx('Customer')} className="sm:col-span-2"><PartnerPicker value={edit.partnerId} label={edit.partnerName} onPick={(id, n) => setEdit({ ...edit, partnerId: id, partnerName: n })} /></Field>
              <Field label={tx('Limit')}><Input type="number" value={edit.amount} onChange={(e) => setEdit({ ...edit, amount: Number(e.target.value) })} /></Field>
              <Field label={tx('Risk category')}>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={edit.riskCategory ?? ''} onChange={(e) => setEdit({ ...edit, riskCategory: e.target.value || null })}>
                  <option value="" /><option value="low">{tx('Low')}</option><option value="medium">{tx('Medium')}</option><option value="high">{tx('High')}</option><option value="blocked">{tx('Blocked')}</option>
                </select></Field>
              <Field label={tx('Credit period (days)')}><Input type="number" value={edit.periodDays ?? ''} onChange={(e) => setEdit({ ...edit, periodDays: e.target.value ? Number(e.target.value) : null })} /></Field>
              <Field label={tx('Next review')}><Input type="date" value={edit.reviewDate ?? ''} onChange={(e) => setEdit({ ...edit, reviewDate: e.target.value || null })} /></Field>
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => setEdit(null)}>{tx('Cancel')}</Button>
              <Button disabled={!edit.partnerId} onClick={() => save.mutate(edit, { onSuccess: () => setEdit(null), onError: err })}>{tx('Save')}</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}

function Decisions() {
  const { data } = useCreditDecisions({});
  return (
    <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
      <table className="w-full min-w-[820px] text-sm">
        <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
          <th className="px-3 py-2">{tx('When')}</th><th className="px-3 py-2">{tx('Customer')}</th><th className="px-3 py-2">{tx('Check point')}</th><th className="px-3 py-2">{tx('Document')}</th>
          <th className="px-3 py-2">{tx('Result')}</th><th className="px-3 py-2">{tx('Reasons')}</th><th className="px-3 py-2">{tx('By')}</th></tr></thead>
        <tbody>
          {(data ?? []).map((d) => (
            <tr key={d.id} className="border-t">
              <td className="px-3 py-2 text-xs">{date(d.createdAt)}</td><td className="px-3 py-2">{d.partnerName}</td><td className="px-3 py-2 text-xs">{d.checkpoint}</td>
              <td className="px-3 py-2 text-xs">{d.documentNumber ?? '—'}</td>
              <td className="px-3 py-2"><span className={cn('rounded px-2 py-0.5 text-xs', d.result === 'pass' ? 'bg-emerald-100 text-emerald-800' : d.result === 'override' ? 'bg-sky-100 text-sky-800' : 'bg-rose-100 text-rose-800')}>{d.result}</span></td>
              <td className="px-3 py-2 text-xs">{d.reasons.join('; ')}</td><td className="px-3 py-2 text-xs">{d.decidedByName ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Policy() {
  const { data } = useCreditPolicy();
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.credit.manage));
  const save = useSalesMutation(creditApi.updatePolicy);
  if (!data) return null;
  const set = (body: Record<string, unknown>) => save.mutate(body, { onSuccess: () => toast.success(tx('Saved')), onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))) });
  return (
    <div className="grid max-w-2xl gap-3 rounded-xl border bg-card p-4 shadow-sm sm:grid-cols-2">
      <Field label={tx('Overdue after (days)')}><Input type="number" disabled={!canManage} defaultValue={String(data.overdueDays)} onBlur={(e) => set({ overdueDays: Number(e.target.value) })} /></Field>
      <Field label={tx('When invoices are overdue')}>
        <select disabled={!canManage} className="h-9 w-full rounded-md border bg-background px-2 text-sm" defaultValue={String(data.overdueAction)} onChange={(e) => set({ overdueAction: e.target.value })}>
          <option value="none">{tx('Ignore')}</option><option value="approval">{tx('Hold — release by credit override')}</option><option value="hard">{tx('Block')}</option>
        </select></Field>
      <label className="flex items-center gap-2 text-sm"><input type="checkbox" disabled={!canManage} defaultChecked={Boolean(data.depositsReduceExposure)} onChange={(e) => set({ depositsReduceExposure: e.target.checked })} />{tx('Deposits reduce exposure')}</label>
      <label className="flex items-center gap-2 text-sm"><input type="checkbox" disabled={!canManage} defaultChecked={Boolean(data.groupLimits)} onChange={(e) => set({ groupLimits: e.target.checked })} />{tx('Check group (parent customer) limits')}</label>
      <label className="flex items-center gap-2 text-sm"><input type="checkbox" disabled={!canManage} defaultChecked={Boolean(data.autoHold)} onChange={(e) => set({ autoHold: e.target.checked })} />{tx('Daily automatic customer hold when blocking')}</label>
    </div>
  );
}

/** Customer credit panel (exposure breakdown) for order and partner pages. */
export function CreditPanel({ partnerId }: { partnerId: string }) {
  const { data: e } = useCreditExposure(partnerId);
  if (!e) return null;
  const over = e.headroom != null && e.headroom < 0;
  const Row = ({ l, v }: { l: string; v: number }) => <div className="flex justify-between py-0.5"><span className="text-muted-foreground">{l}</span><span className="tabular-nums">{money(v)}</span></div>;
  return (
    <div className={cn('rounded-xl border bg-card p-4 text-sm shadow-sm', (e.creditHold || over) && 'border-rose-300')}>
      <h2 className="mb-2 font-semibold">{tx('Customer credit')}</h2>
      {e.creditHold && <p className="mb-2 text-rose-700">{tx('On credit hold')}</p>}
      <Row l={tx('Limit')} v={e.limit} />
      <Row l={tx('Open orders')} v={e.components.undelivered} />
      <Row l={tx('Delivered, not invoiced')} v={e.components.deliveredUninvoiced} />
      <Row l={tx('Receivables')} v={e.components.receivables} />
      {e.components.deposits > 0 && <Row l={tx('Deposits')} v={-e.components.deposits} />}
      {e.components.credits > 0 && <Row l={tx('Unapplied credits')} v={-e.components.credits} />}
      <div className="mt-1 border-t pt-1"><Row l={tx('Exposure')} v={e.exposure} /></div>
      {e.headroom != null && <div className={cn('flex justify-between font-medium', over && 'text-rose-600')}><span>{tx('Headroom')}</span><span className="tabular-nums">{money(e.headroom)}</span></div>}
      {e.overdue.amount > 0 && <p className="mt-1 text-xs text-rose-600">{tx('Overdue')}: {money(e.overdue.amount)} · {e.overdue.maxDays} {tx('days')}</p>}
    </div>
  );
}

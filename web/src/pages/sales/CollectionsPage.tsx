import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { HandCoins, Play, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { Field } from '@/pages/crm/crm-ui';
import { PartnerPicker } from '@/pages/crm/crm-dialogs';

interface WorkRow { partnerId: string; partnerName: string; creditHold: boolean; open: number; current: number; d30: number; d60: number; d90: number; over90: number; lastLevel: number | null; lastDunnedAt: string | null; promisedDate: string | null }
interface Level { id: string; sequence: number; name: string; daysOverdue: number; channel: string; subject: string; body: string; feeAmount: number; setCreditHold: boolean; isActive: boolean }
interface Run { id: string; runDate: string; trigger: string; entries: number | Entry[]; skipped: number }
interface Entry { id: string; partnerName: string; levelName: string; levelSequence: number; overdueAmount: number; maxDaysOverdue: number; delivery: string; feeInvoiceId: string | null; creditHoldSet: boolean; error: string | null }
interface Promise2Pay { id: string; partnerName: string; amount: number; promisedDate: string; status: string; notes: string | null }

type Tab = 'worklist' | 'runs' | 'levels' | 'promises';

/** Collections (ADR-S011 S8): worklist, dunning, promises to pay. */
export function CollectionsPage() {
  const [tab, setTab] = useState<Tab>('worklist');
  const TABS: Array<[Tab, string]> = [['worklist', tx('Worklist')], ['runs', tx('Dunning runs')], ['levels', tx('Dunning levels')], ['promises', tx('Promises to pay')]];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={HandCoins} title={tx('Collections')} description={tx('Overdue customers by age, reminders, late fees, credit holds and promises to pay.')} />
      <div className="flex flex-wrap rounded-lg border bg-card p-0.5">
        {TABS.map(([k, l]) => <button key={k} onClick={() => setTab(k)} className={cn('rounded-md px-3 py-1.5 text-sm', tab === k ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>{l}</button>)}
      </div>
      {tab === 'worklist' && <Worklist />}
      {tab === 'runs' && <Runs />}
      {tab === 'levels' && <Levels />}
      {tab === 'promises' && <Promises />}
    </div>
  );
}

function Worklist() {
  const [bucket, setBucket] = useState('');
  const { data } = useQuery({ queryKey: ['sales', 'worklist', bucket], queryFn: async () => (await api.get<WorkRow[]>('/sales/collections/worklist', { params: bucket ? { bucket } : {} })).data });
  return (
    <div className="space-y-2">
      <select className="h-9 rounded-md border bg-background px-2 text-sm" value={bucket} onChange={(e) => setBucket(e.target.value)}>
        <option value="">{tx('All overdue buckets')}</option><option value="d30">1–30</option><option value="d60">31–60</option><option value="d90">61–90</option><option value="over90">90+</option>
      </select>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[900px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className="px-3 py-2">{tx('Customer')}</th><th className="px-3 py-2 text-right">{tx('Open')}</th><th className="px-3 py-2 text-right">{tx('Current')}</th>
            <th className="px-3 py-2 text-right">1–30</th><th className="px-3 py-2 text-right">31–60</th><th className="px-3 py-2 text-right">61–90</th><th className="px-3 py-2 text-right">90+</th>
            <th className="px-3 py-2">{tx('Last reminder')}</th><th className="px-3 py-2">{tx('Promise')}</th></tr></thead>
          <tbody>
            {(data ?? []).map((r) => (
              <tr key={r.partnerId} className="border-t">
                <td className="px-3 py-2 font-medium">{r.partnerName}{r.creditHold && <span className="ml-2 rounded bg-rose-600 px-1.5 text-xs text-white">{tx('Hold')}</span>}</td>
                {[r.open, r.current, r.d30, r.d60, r.d90, r.over90].map((v, i) => <td key={i} className={cn('px-3 py-2 text-right tabular-nums', i === 5 && Number(v) > 0 && 'text-rose-600')}>{Number(v) ? money(v) : '—'}</td>)}
                <td className="px-3 py-2 text-xs">{r.lastLevel ? `L${r.lastLevel} · ${date(r.lastDunnedAt!)}` : '—'}</td>
                <td className="px-3 py-2 text-xs">{r.promisedDate ? date(r.promisedDate) : '—'}</td>
              </tr>
            ))}
            {data && data.length === 0 && <tr><td colSpan={9} className="p-6 text-center text-muted-foreground">{tx('No overdue receivables.')}</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Runs() {
  const qc = useQueryClient();
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.collectionsManage));
  const { data } = useQuery({ queryKey: ['sales', 'dunning-runs'], queryFn: async () => (await api.get<Run[]>('/sales/collections/runs')).data });
  const [open, setOpen] = useState<string | null>(null);
  const { data: detail } = useQuery({ queryKey: ['sales', 'dunning-run', open], queryFn: async () => (await api.get<Run>(`/sales/collections/runs/${open}`)).data, enabled: !!open });
  const run = useMutation({
    mutationFn: async () => (await api.post<Run>('/sales/collections/runs', {})).data,
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ['sales'] }); setOpen(r.id); toast.success(tx('Dunning run done')); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Dunning run failed'))),
  });
  return (
    <div className="space-y-2">
      {canManage && <Button onClick={() => run.mutate()} disabled={run.isPending}><Play className="mr-1 h-4 w-4" />{tx('Run dunning now')}</Button>}
      <ul className="space-y-1 text-sm">
        {(data ?? []).map((r) => <li key={r.id}><button className="text-primary hover:underline" onClick={() => setOpen(r.id)}>{date(r.runDate)} · {r.trigger}</button> · {String(r.entries)} {tx('reminders')} · {r.skipped} {tx('skipped')}</li>)}
      </ul>
      {detail && Array.isArray(detail.entries) && (
        <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
          <table className="w-full min-w-[760px] text-sm">
            <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className="px-3 py-2">{tx('Customer')}</th><th className="px-3 py-2">{tx('Level')}</th>
              <th className="px-3 py-2 text-right">{tx('Overdue')}</th><th className="px-3 py-2 text-right">{tx('Days')}</th><th className="px-3 py-2">{tx('Delivery')}</th><th className="px-3 py-2">{tx('Fee / hold')}</th></tr></thead>
            <tbody>
              {detail.entries.map((e) => (
                <tr key={e.id} className="border-t"><td className="px-3 py-2">{e.partnerName}</td><td className="px-3 py-2">L{e.levelSequence} {e.levelName}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{money(e.overdueAmount)}</td><td className="px-3 py-2 text-right">{e.maxDaysOverdue}</td>
                  <td className="px-3 py-2 text-xs">{e.delivery}{e.error && <span className="block text-rose-600">{e.error}</span>}</td>
                  <td className="px-3 py-2 text-xs">{e.feeInvoiceId ? tx('Fee invoice') : ''}{e.creditHoldSet ? ' · ' + tx('credit hold') : ''}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function Levels() {
  const qc = useQueryClient();
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.collectionsManage));
  const { data } = useQuery({ queryKey: ['sales', 'dunning-levels'], queryFn: async () => (await api.get<Level[]>('/sales/collections/levels')).data });
  const [edit, setEdit] = useState<Partial<Level> | null>(null);
  const save = useMutation({
    mutationFn: async (l: Partial<Level>) => (l.id ? await api.put(`/sales/collections/levels/${l.id}`, l) : await api.post('/sales/collections/levels', l)).data,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['sales'] }); setEdit(null); }, onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
  });
  return (
    <div className="space-y-2">
      {canManage && <Button onClick={() => setEdit({ sequence: (data?.length ?? 0) + 1, channel: 'email', daysOverdue: 7, feeAmount: 0, setCreditHold: false, isActive: true, subject: '', body: '' })}><Plus className="mr-1 h-4 w-4" />{tx('New level')}</Button>}
      <ul className="space-y-1 text-sm">
        {(data ?? []).map((l) => <li key={l.id} className="cursor-pointer rounded border p-2 hover:bg-muted/30" onClick={() => canManage && setEdit(l)}>L{l.sequence} · {l.name} · {tx('from')} {l.daysOverdue} {tx('days')} · {l.channel}{Number(l.feeAmount) > 0 && ` · ${tx('fee')} ${money(l.feeAmount)}`}{l.setCreditHold && ` · ${tx('credit hold')}`}{!l.isActive && ` · ${tx('inactive')}`}</li>)}
      </ul>
      {edit && (
        <Dialog open onOpenChange={(v) => !v && setEdit(null)}>
          <DialogContent className="max-w-lg">
            <DialogHeader><DialogTitle>{tx('Dunning level')}</DialogTitle></DialogHeader>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label={tx('Sequence')}><Input type="number" value={edit.sequence ?? 1} onChange={(e) => setEdit({ ...edit, sequence: Number(e.target.value) })} /></Field>
              <Field label={tx('From days overdue')}><Input type="number" value={edit.daysOverdue ?? 0} onChange={(e) => setEdit({ ...edit, daysOverdue: Number(e.target.value) })} /></Field>
              <Field label={tx('Name')} className="sm:col-span-2"><Input value={edit.name ?? ''} onChange={(e) => setEdit({ ...edit, name: e.target.value })} /></Field>
              <Field label={tx('Channel')}><select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={edit.channel} onChange={(e) => setEdit({ ...edit, channel: e.target.value })}>
                <option value="email">{tx('E-mail with statement')}</option><option value="letter">{tx('Letter (print)')}</option><option value="none">{tx('Record only')}</option></select></Field>
              <Field label={tx('Late fee')}><Input type="number" value={edit.feeAmount ?? 0} onChange={(e) => setEdit({ ...edit, feeAmount: Number(e.target.value) })} /></Field>
              <Field label={tx('Subject')} className="sm:col-span-2"><Input value={edit.subject ?? ''} onChange={(e) => setEdit({ ...edit, subject: e.target.value })} /></Field>
              <Field label={tx('Message')} className="sm:col-span-2" hint="{{customer}} {{amount}} {{days}}"><Textarea rows={4} value={edit.body ?? ''} onChange={(e) => setEdit({ ...edit, body: e.target.value })} /></Field>
              <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={!!edit.setCreditHold} onChange={(e) => setEdit({ ...edit, setCreditHold: e.target.checked })} />{tx('Put customer on credit hold')}</label>
              <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={edit.isActive !== false} onChange={(e) => setEdit({ ...edit, isActive: e.target.checked })} />{tx('Active')}</label>
            </div>
            <DialogFooter><Button variant="outline" onClick={() => setEdit(null)}>{tx('Cancel')}</Button><Button onClick={() => save.mutate(edit)}>{tx('Save')}</Button></DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}

function Promises() {
  const qc = useQueryClient();
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.collectionsManage));
  const { data } = useQuery({ queryKey: ['sales', 'promises'], queryFn: async () => (await api.get<Promise2Pay[]>('/sales/collections/promises')).data });
  const [form, setForm] = useState<{ partnerId: string; partnerName?: string; amount: number; promisedDate: string; notes: string } | null>(null);
  const save = useMutation({
    mutationFn: async () => (await api.post('/sales/collections/promises', form)).data,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['sales'] }); setForm(null); }, onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
  });
  return (
    <div className="space-y-2">
      {canManage && <Button onClick={() => setForm({ partnerId: '', amount: 0, promisedDate: new Date().toISOString().slice(0, 10), notes: '' })}><Plus className="mr-1 h-4 w-4" />{tx('Record promise')}</Button>}
      <ul className="space-y-1 text-sm">
        {(data ?? []).map((t) => <li key={t.id} className="rounded border p-2">{t.partnerName} · {money(t.amount)} {tx('by')} {date(t.promisedDate)} · <span className={t.status === 'broken' ? 'text-rose-600' : t.status === 'kept' ? 'text-emerald-600' : ''}>{t.status}</span>{t.notes ? ` · ${t.notes}` : ''}</li>)}
      </ul>
      {form && (
        <Dialog open onOpenChange={(v) => !v && setForm(null)}>
          <DialogContent className="max-w-md">
            <DialogHeader><DialogTitle>{tx('Promise to pay')}</DialogTitle></DialogHeader>
            <Field label={tx('Customer')}><PartnerPicker value={form.partnerId} label={form.partnerName} onPick={(id, n) => setForm({ ...form, partnerId: id, partnerName: n })} /></Field>
            <Field label={tx('Amount')}><Input type="number" value={form.amount} onChange={(e) => setForm({ ...form, amount: Number(e.target.value) })} /></Field>
            <Field label={tx('Promised date')}><Input type="date" value={form.promisedDate} onChange={(e) => setForm({ ...form, promisedDate: e.target.value })} /></Field>
            <Field label={tx('Notes')}><Textarea rows={2} value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} /></Field>
            <DialogFooter><Button variant="outline" onClick={() => setForm(null)}>{tx('Cancel')}</Button><Button disabled={!form.partnerId} onClick={() => save.mutate()}>{tx('Save')}</Button></DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}

import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { BadgePercent, FileSignature, Plus, Trash2 } from 'lucide-react';
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
import { ProductPicker } from '@/pages/fleet/dispatch/dispatch-ui';
import { useCrmLookups } from '@/features/crm/suite-api';

// ---- Types (ADR-S011 S9) ----

interface AgreementLine { id?: string; productId: string; productName?: string; contractQty: number; unitPrice: number; discountPercent?: number; minReleaseQty?: number; releasedQty?: number; balanceQty?: number; releasedPercent?: number }
interface Agreement {
  id: string; number: string; name: string; kind: string; status: string; partnerId: string; partnerName: string;
  startDate: string; endDate: string; allowOverRelease: boolean; notes: string | null; releases: number | Array<{ id: string; number: string; status: string; total: number }>;
  lines?: AgreementLine[]; contractValue?: number; releasedValue?: number;
}
interface Tier { from: number; rate: number; rateType: 'per_unit' | 'percent' }
interface Program { id: string; name: string; partnerId: string | null; partnerName: string | null; productId: string | null; productName: string | null; periodType: string; basis: string; startDate: string; endDate: string | null; tiers: Tier[]; status: string; accrued: number }
interface Accrual { id: string; programName: string; partnerName: string; periodStart: string; periodEnd: string; qualifyingQty: number; qualifyingValue: number; amount: number; rate: number; status: string; creditNoteNumber: string | null }
interface Rule { id: string; name: string; salespersonId: string | null; salespersonName: string | null; teamName: string | null; productCategoryName: string | null; basis: string; method: string; rate: number; priority: number; isActive: boolean }
interface Statement { id: string; number: string; salespersonName: string; periodStart: string; status: string; total: number; payrollInputId: string | null }
interface Target { id: string; month: string; salespersonName: string | null; teamName: string | null; branchName: string | null; productCategoryName: string | null; amount: number; actualAmount?: number; achievementPercent?: number | null }

const sel = 'h-9 w-full rounded-md border bg-background px-2 text-sm';
const th = 'px-3 py-2';

function Tabs<T extends string>({ tabs, value, onChange }: { tabs: Array<[T, string]>; value: T; onChange: (t: T) => void }) {
  return (
    <div className="flex flex-wrap rounded-lg border bg-card p-0.5">
      {tabs.map(([k, l]) => <button key={k} onClick={() => onChange(k)} className={cn('rounded-md px-3 py-1.5 text-sm', value === k ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>{l}</button>)}
    </div>
  );
}

function useSave<T>(fn: (v: T) => Promise<unknown>, done?: () => void) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['sales'] }); done?.(); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
  });
}

// ---------------------------------------------------------------- agreements + rebates

/** Agreements and rebates (licence salesAgreements). */
export function AgreementsPage() {
  const [tab, setTab] = useState<'agreements' | 'rebates'>('agreements');
  return (
    <div className="space-y-4">
      <ListPageHeader icon={FileSignature} title={tx('Agreements & rebates')} description={tx('Blanket orders and contracts with agreed prices and quantities, and volume rebates.')} />
      <Tabs tabs={[['agreements', tx('Agreements')], ['rebates', tx('Rebates')]]} value={tab} onChange={setTab} />
      {tab === 'agreements' ? <Agreements /> : <Rebates />}
    </div>
  );
}

function Agreements() {
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.agreementManage));
  const [status, setStatus] = useState('');
  const { data } = useQuery({ queryKey: ['sales', 'agreements', status], queryFn: async () => (await api.get<Agreement[]>('/sales/agreements', { params: status ? { status } : {} })).data });
  const [open, setOpen] = useState<string | null>(null);
  const [form, setForm] = useState<{ partnerId: string; partnerName?: string; name: string; kind: string; startDate: string; endDate: string; allowOverRelease: boolean; notes: string; lines: AgreementLine[] } | null>(null);
  const create = useSave(async () => (await api.post('/sales/agreements', form)).data, () => setForm(null));
  const today = new Date().toISOString().slice(0, 10);
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        <select className={cn(sel, 'w-48')} value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">{tx('All statuses')}</option>{['draft', 'active', 'expired', 'closed', 'cancelled'].map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        {canManage && <Button onClick={() => setForm({ partnerId: '', name: '', kind: 'blanket', startDate: today, endDate: today, allowOverRelease: false, notes: '', lines: [] })}><Plus className="mr-1 h-4 w-4" />{tx('New agreement')}</Button>}
      </div>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[760px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className={th}>{tx('Number')}</th><th className={th}>{tx('Customer')}</th><th className={th}>{tx('Name')}</th><th className={th}>{tx('Validity')}</th><th className={th}>{tx('Status')}</th><th className={cn(th, 'text-right')}>{tx('Releases')}</th></tr></thead>
          <tbody>
            {(data ?? []).map((a) => (
              <tr key={a.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => setOpen(a.id)}>
                <td className={cn(th, 'font-medium')}>{a.number}</td><td className={th}>{a.partnerName}</td><td className={th}>{a.name} <span className="text-xs text-muted-foreground">({a.kind})</span></td>
                <td className={th}>{date(a.startDate)} – {date(a.endDate)}</td><td className={th}>{a.status}</td><td className={cn(th, 'text-right')}>{String(a.releases)}</td>
              </tr>
            ))}
            {data && data.length === 0 && <tr><td colSpan={6} className="p-6 text-center text-muted-foreground">{tx('No agreements yet.')}</td></tr>}
          </tbody>
        </table>
      </div>
      {open && <AgreementDialog id={open} canManage={canManage} onClose={() => setOpen(null)} />}
      {form && (
        <Dialog open onOpenChange={(v) => !v && setForm(null)}>
          <DialogContent className="max-w-3xl">
            <DialogHeader><DialogTitle>{tx('New agreement')}</DialogTitle></DialogHeader>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label={tx('Customer')}><PartnerPicker value={form.partnerId} label={form.partnerName} onPick={(id, n) => setForm({ ...form, partnerId: id, partnerName: n })} /></Field>
              <Field label={tx('Name')}><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
              <Field label={tx('Kind')}><select className={sel} value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}><option value="blanket">{tx('Blanket order')}</option><option value="contract">{tx('Contract')}</option></select></Field>
              <label className="flex items-center gap-2 self-end text-sm"><input type="checkbox" checked={form.allowOverRelease} onChange={(e) => setForm({ ...form, allowOverRelease: e.target.checked })} />{tx('Allow releases beyond the contract quantity')}</label>
              <Field label={tx('Start')}><Input type="date" value={form.startDate} onChange={(e) => setForm({ ...form, startDate: e.target.value })} /></Field>
              <Field label={tx('End')}><Input type="date" value={form.endDate} onChange={(e) => setForm({ ...form, endDate: e.target.value })} /></Field>
            </div>
            <div className="space-y-2">
              {form.lines.map((l, i) => (
                <div key={i} className="grid grid-cols-[1fr_7rem_7rem_6rem_auto] items-end gap-2">
                  <Field label={tx('Product')}><ProductPicker value={l.productId} label={l.productName} onPick={(p) => { const lines = [...form.lines]; lines[i] = { ...l, productId: p?.id ?? '', productName: p?.name }; setForm({ ...form, lines }); }} /></Field>
                  <Field label={tx('Contract qty')}><Input type="number" value={l.contractQty} onChange={(e) => { const lines = [...form.lines]; lines[i] = { ...l, contractQty: Number(e.target.value) }; setForm({ ...form, lines }); }} /></Field>
                  <Field label={tx('Agreed price')}><Input type="number" value={l.unitPrice} onChange={(e) => { const lines = [...form.lines]; lines[i] = { ...l, unitPrice: Number(e.target.value) }; setForm({ ...form, lines }); }} /></Field>
                  <Field label={tx('Min release')}><Input type="number" value={l.minReleaseQty ?? 0} onChange={(e) => { const lines = [...form.lines]; lines[i] = { ...l, minReleaseQty: Number(e.target.value) }; setForm({ ...form, lines }); }} /></Field>
                  <Button variant="ghost" size="icon" onClick={() => setForm({ ...form, lines: form.lines.filter((_, j) => j !== i) })}><Trash2 className="h-4 w-4" /></Button>
                </div>
              ))}
              <Button variant="outline" size="sm" onClick={() => setForm({ ...form, lines: [...form.lines, { productId: '', contractQty: 0, unitPrice: 0 }] })}><Plus className="mr-1 h-4 w-4" />{tx('Add line')}</Button>
            </div>
            <Field label={tx('Notes')}><Textarea rows={2} value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} /></Field>
            <DialogFooter><Button variant="outline" onClick={() => setForm(null)}>{tx('Cancel')}</Button><Button disabled={!form.partnerId || !form.name || create.isPending} onClick={() => create.mutate(undefined)}>{tx('Save draft')}</Button></DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}

function AgreementDialog({ id, canManage, onClose }: { id: string; canManage: boolean; onClose: () => void }) {
  const { data: a } = useQuery({ queryKey: ['sales', 'agreement', id], queryFn: async () => (await api.get<Agreement>(`/sales/agreements/${id}`)).data });
  const act = useSave(async (action: string) => (await api.post(`/sales/agreements/${id}/${action}`, {})).data);
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-3xl">
        <DialogHeader><DialogTitle>{a ? `${a.number} · ${a.name}` : tx('Agreement')}</DialogTitle></DialogHeader>
        {a && (
          <div className="space-y-3 text-sm">
            <p>{a.partnerName} · {a.kind} · {date(a.startDate)} – {date(a.endDate)} · <b>{a.status}</b>{a.allowOverRelease && ` · ${tx('over-release allowed')}`}</p>
            <p>{tx('Contract value')} {money(a.contractValue ?? 0)} · {tx('released')} {money(a.releasedValue ?? 0)}</p>
            <table className="w-full text-sm">
              <thead className="text-left text-xs uppercase text-muted-foreground"><tr><th className="py-1">{tx('Product')}</th><th className="text-right">{tx('Contract')}</th><th className="text-right">{tx('Released')}</th><th className="text-right">{tx('Balance')}</th><th className="text-right">{tx('Price')}</th></tr></thead>
              <tbody>{(a.lines ?? []).map((l) => (
                <tr key={l.id} className="border-t"><td className="py-1">{l.productName}</td><td className="text-right tabular-nums">{l.contractQty}</td>
                  <td className="text-right tabular-nums">{l.releasedQty} <span className="text-xs text-muted-foreground">({l.releasedPercent}%)</span></td>
                  <td className={cn('text-right tabular-nums', Number(l.balanceQty) < 0 && 'text-rose-600')}>{l.balanceQty}</td><td className="text-right tabular-nums">{money(l.unitPrice)}</td></tr>
              ))}</tbody>
            </table>
            {Array.isArray(a.releases) && a.releases.length > 0 && (
              <ul className="text-xs">{a.releases.map((r) => <li key={r.id}><a className="text-primary hover:underline" href={`/sales/orders/${r.id}`}>{r.number}</a> · {r.status} · {money(r.total)}</li>)}</ul>
            )}
          </div>
        )}
        <DialogFooter>
          {canManage && a?.status === 'draft' && <Button onClick={() => act.mutate('activate')}>{tx('Activate')}</Button>}
          {canManage && (a?.status === 'active' || a?.status === 'expired') && <Button variant="outline" onClick={() => act.mutate('close')}>{tx('Close')}</Button>}
          {canManage && (a?.status === 'draft' || a?.status === 'active') && <Button variant="outline" onClick={() => act.mutate('cancel')}>{tx('Cancel agreement')}</Button>}
          <Button variant="ghost" onClick={onClose}>{tx('Done')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function Rebates() {
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.rebateManage));
  const { data: programs } = useQuery({ queryKey: ['sales', 'rebate-programs'], queryFn: async () => (await api.get<Program[]>('/sales/rebates/programs')).data });
  const { data: accruals } = useQuery({ queryKey: ['sales', 'rebate-accruals'], queryFn: async () => (await api.get<Accrual[]>('/sales/rebates/accruals')).data });
  const [form, setForm] = useState<{ name: string; partnerId: string; partnerName?: string; productId: string; productName?: string; periodType: string; basis: string; startDate: string; tiers: Tier[] } | null>(null);
  const save = useSave(async () => (await api.post('/sales/rebates/programs', form)).data, () => setForm(null));
  const accrue = useSave(async () => (await api.post('/sales/rebates/accrue', {})).data, () => toast.success(tx('Rebates accrued')));
  const settle = useSave(async (id: string) => (await api.post(`/sales/rebates/accruals/${id}/settle`, {}, { headers: { 'Idempotency-Key': crypto.randomUUID() } })).data, () => toast.success(tx('Credit note issued')));
  return (
    <div className="space-y-4">
      {canManage && (
        <div className="flex gap-2">
          <Button onClick={() => setForm({ name: '', partnerId: '', productId: '', periodType: 'quarter', basis: 'quantity', startDate: new Date().toISOString().slice(0, 10), tiers: [{ from: 0, rate: 0, rateType: 'per_unit' }] })}><Plus className="mr-1 h-4 w-4" />{tx('New programme')}</Button>
          <Button variant="outline" onClick={() => accrue.mutate(undefined)} disabled={accrue.isPending}><BadgePercent className="mr-1 h-4 w-4" />{tx('Accrue now')}</Button>
        </div>
      )}
      <ul className="space-y-1 text-sm">
        {(programs ?? []).map((p) => (
          <li key={p.id} className="rounded border p-2">
            <b>{p.name}</b> · {p.partnerName ?? tx('all customers')} · {p.productName ?? tx('all products')} · {p.periodType} · {p.basis} · {p.status}
            <span className="block text-xs text-muted-foreground">{p.tiers.map((t) => `≥ ${t.from}: ${t.rate}${t.rateType === 'percent' ? '%' : ' / ' + tx('unit')}`).join(' · ')} · {tx('accrued')} {money(p.accrued)}</span>
          </li>
        ))}
        {programs && programs.length === 0 && <li className="text-muted-foreground">{tx('No rebate programmes yet.')}</li>}
      </ul>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[820px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className={th}>{tx('Programme')}</th><th className={th}>{tx('Customer')}</th><th className={th}>{tx('Period')}</th><th className={cn(th, 'text-right')}>{tx('Qty')}</th>
            <th className={cn(th, 'text-right')}>{tx('Value')}</th><th className={cn(th, 'text-right')}>{tx('Rebate')}</th><th className={th}>{tx('Status')}</th><th /></tr></thead>
          <tbody>
            {(accruals ?? []).map((a) => (
              <tr key={a.id} className="border-t">
                <td className={th}>{a.programName}</td><td className={th}>{a.partnerName}</td><td className={th}>{date(a.periodStart)} – {date(a.periodEnd)}</td>
                <td className={cn(th, 'text-right tabular-nums')}>{a.qualifyingQty}</td><td className={cn(th, 'text-right tabular-nums')}>{money(a.qualifyingValue)}</td>
                <td className={cn(th, 'text-right tabular-nums')}>{money(a.amount)}</td><td className={th}>{a.status}{a.creditNoteNumber && ` · ${a.creditNoteNumber}`}</td>
                <td className={th}>{canManage && a.status === 'accrued' && Number(a.amount) > 0 && new Date(a.periodEnd) < new Date() && <Button size="sm" variant="outline" onClick={() => settle.mutate(a.id)}>{tx('Settle')}</Button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {form && (
        <Dialog open onOpenChange={(v) => !v && setForm(null)}>
          <DialogContent className="max-w-xl">
            <DialogHeader><DialogTitle>{tx('Rebate programme')}</DialogTitle></DialogHeader>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label={tx('Name')} className="sm:col-span-2"><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
              <Field label={tx('Customer (optional)')}><PartnerPicker value={form.partnerId} label={form.partnerName} onPick={(id, n) => setForm({ ...form, partnerId: id, partnerName: n })} /></Field>
              <Field label={tx('Product (optional)')}><ProductPicker value={form.productId} label={form.productName} onPick={(p) => setForm({ ...form, productId: p?.id ?? '', productName: p?.name })} /></Field>
              <Field label={tx('Period')}><select className={sel} value={form.periodType} onChange={(e) => setForm({ ...form, periodType: e.target.value })}><option value="month">{tx('Month')}</option><option value="quarter">{tx('Quarter')}</option><option value="year">{tx('Year')}</option></select></Field>
              <Field label={tx('Basis')}><select className={sel} value={form.basis} onChange={(e) => setForm({ ...form, basis: e.target.value })}><option value="quantity">{tx('Quantity')}</option><option value="value">{tx('Value')}</option></select></Field>
              <Field label={tx('Start')}><Input type="date" value={form.startDate} onChange={(e) => setForm({ ...form, startDate: e.target.value })} /></Field>
            </div>
            <div className="space-y-2">
              {form.tiers.map((t, i) => (
                <div key={i} className="grid grid-cols-[1fr_1fr_1fr_auto] items-end gap-2">
                  <Field label={tx('From')}><Input type="number" value={t.from} onChange={(e) => { const tiers = [...form.tiers]; tiers[i] = { ...t, from: Number(e.target.value) }; setForm({ ...form, tiers }); }} /></Field>
                  <Field label={tx('Rate')}><Input type="number" value={t.rate} onChange={(e) => { const tiers = [...form.tiers]; tiers[i] = { ...t, rate: Number(e.target.value) }; setForm({ ...form, tiers }); }} /></Field>
                  <Field label={tx('Rate type')}><select className={sel} value={t.rateType} onChange={(e) => { const tiers = [...form.tiers]; tiers[i] = { ...t, rateType: e.target.value as Tier['rateType'] }; setForm({ ...form, tiers }); }}><option value="per_unit">{tx('Per unit')}</option><option value="percent">{tx('% of value')}</option></select></Field>
                  <Button variant="ghost" size="icon" onClick={() => setForm({ ...form, tiers: form.tiers.filter((_, j) => j !== i) })}><Trash2 className="h-4 w-4" /></Button>
                </div>
              ))}
              <Button variant="outline" size="sm" onClick={() => setForm({ ...form, tiers: [...form.tiers, { from: 0, rate: 0, rateType: 'per_unit' }] })}><Plus className="mr-1 h-4 w-4" />{tx('Add tier')}</Button>
            </div>
            <DialogFooter><Button variant="outline" onClick={() => setForm(null)}>{tx('Cancel')}</Button><Button disabled={!form.name || save.isPending} onClick={() => save.mutate(undefined)}>{tx('Save')}</Button></DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- commissions + targets

/** Commission statements and rules, sales targets. */
export function CommissionsPage() {
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.commissionManage));
  const [tab, setTab] = useState<'statements' | 'rules' | 'targets'>('statements');
  const tabs: Array<['statements' | 'rules' | 'targets', string]> = [['statements', tx('Statements')], ['targets', tx('Targets')]];
  if (canManage) tabs.splice(1, 0, ['rules', tx('Rules')]);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={BadgePercent} title={tx('Commissions & targets')} description={tx('Monthly commission statements into payroll, commission rules and sales targets.')} />
      <Tabs tabs={tabs} value={tab} onChange={setTab} />
      {tab === 'statements' && <Statements canManage={canManage} />}
      {tab === 'rules' && <Rules />}
      {tab === 'targets' && <Targets />}
    </div>
  );
}

function Statements({ canManage }: { canManage: boolean }) {
  const [month, setMonth] = useState(new Date().toISOString().slice(0, 7));
  const { data } = useQuery({ queryKey: ['sales', 'commission-statements'], queryFn: async () => (await api.get<Statement[]>('/sales/commissions/statements')).data });
  const generate = useSave(async () => (await api.post('/sales/commissions/statements', { month })).data, () => toast.success(tx('Statements generated')));
  const act = useSave(async ({ id, action }: { id: string; action: string }) => (await api.post(`/sales/commissions/statements/${id}/${action}`, {})).data);
  const paid = useSave(async (id: string) => (await api.post(`/sales/commissions/statements/${id}/paid`, {}, { headers: { 'Idempotency-Key': crypto.randomUUID() } })).data);
  const [open, setOpen] = useState<string | null>(null);
  return (
    <div className="space-y-2">
      {open && <StatementDetail id={open} canManage={canManage} onClose={() => setOpen(null)} />}
      {canManage && (
        <div className="flex gap-2"><Input type="month" className="w-44" value={month} onChange={(e) => setMonth(e.target.value)} />
          <Button onClick={() => generate.mutate(undefined)} disabled={generate.isPending}>{tx('Generate statements')}</Button></div>
      )}
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[640px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className={th}>{tx('Number')}</th><th className={th}>{tx('Salesperson')}</th><th className={th}>{tx('Month')}</th><th className={cn(th, 'text-right')}>{tx('Total')}</th><th className={th}>{tx('Status')}</th><th /></tr></thead>
          <tbody>
            {(data ?? []).map((s) => (
              <tr key={s.id} className="border-t">
                <td className={cn(th, 'font-medium')}>{s.number}</td><td className={th}>{s.salespersonName}</td><td className={th}>{s.periodStart.slice(0, 7)}</td>
                <td className={cn(th, 'text-right tabular-nums', Number(s.total) < 0 && 'text-rose-600')}>{money(s.total)}</td>
                <td className={th}>{s.status}{s.payrollInputId && ` · ${tx('in payroll')}`}</td>
                <td className={cn(th, 'space-x-1 text-right')}><Button size="sm" variant="ghost" onClick={() => setOpen(s.id)}>{tx('Details')}</Button>{canManage && s.status === 'approved' && Number(s.total) > 0 && <Button size="sm" variant="outline" onClick={() => paid.mutate(s.id)}>{tx('Mark paid')}</Button>}{canManage && s.status === 'draft' && <>
                  <Button size="sm" onClick={() => act.mutate({ id: s.id, action: 'approve' })}>{tx('Approve')}</Button>
                  <Button size="sm" variant="outline" onClick={() => act.mutate({ id: s.id, action: 'cancel' })}>{tx('Cancel')}</Button></>}</td>
              </tr>
            ))}
            {data && data.length === 0 && <tr><td colSpan={6} className="p-6 text-center text-muted-foreground">{tx('No commission statements yet.')}</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}

interface StatementFull { id: string; number: string; status: string; total: number; linesTotal: number | null; adjustmentTotal: number; carriedIn: number; lines: Array<{ number: string; description: string; revenue: number; rate: number; amount: number; basis: string; paymentId?: string | null; splitFrom?: string | null }> | null; adjustments: Array<{ id: string; amount: number; reason: string }>; disputes: Array<{ id: string; reason: string; status: string; resolution: string | null }> }

/** One statement: every line, adjustments, what was carried in, disputes and the PDF. */
function StatementDetail({ id, canManage, onClose }: { id: string; canManage: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const key = ['sales', 'commission-statement', id];
  const { data: s } = useQuery({ queryKey: key, queryFn: async () => (await api.get<StatementFull>(`/sales/commissions/statements/${id}`)).data });
  const refresh = () => { void qc.invalidateQueries({ queryKey: key }); void qc.invalidateQueries({ queryKey: ['sales', 'commission-statements'] }); };
  const fail = (e: unknown) => toast.error(apiErrorMessage(e, tx('Action failed')));
  const pdf = async () => {
    try {
      const r = await api.get(`/sales/commissions/statements/${id}/pdf`, { responseType: 'blob' });
      const url = URL.createObjectURL(r.data as Blob);
      const a = document.createElement('a'); a.href = url; a.download = `${s?.number}.pdf`; a.click(); URL.revokeObjectURL(url);
    } catch (e) { fail(e); }
  };
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-h-[85vh] max-w-3xl overflow-y-auto">
        <DialogHeader><DialogTitle>{s?.number}</DialogTitle></DialogHeader>
        {s && <div className="space-y-3 text-sm">
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="outline" onClick={pdf}>PDF</Button>
            {canManage && s.status === 'draft' && <Button size="sm" variant="outline" onClick={() => { const amount = Number(prompt(tx('Adjustment amount (negative to reduce)'))); const reason = amount ? prompt(tx('Reason')) : null; if (amount && reason) api.post(`/sales/commissions/statements/${id}/adjustments`, { amount, reason }).then(refresh).catch(fail); }}>{tx('Add adjustment')}</Button>}
            <Button size="sm" variant="outline" onClick={() => { const reason = prompt(tx('What is wrong with this statement?')); if (reason) api.post(`/sales/commissions/statements/${id}/disputes`, { reason }).then(refresh).catch(fail); }}>{tx('Dispute')}</Button>
          </div>
          <p>{tx('Lines')} {money(s.linesTotal ?? 0)} + {tx('adjustments')} {money(s.adjustmentTotal)} + {tx('carried in')} {money(s.carriedIn)} = <strong>{money(s.total)}</strong></p>
          <table className="w-full"><thead className="text-left text-xs text-muted-foreground"><tr><th>{tx('Document')}</th><th>{tx('Description')}</th><th className="text-right">{tx('Base')}</th><th className="text-right">{tx('Rate')}</th><th className="text-right">{tx('Amount')}</th></tr></thead>
            <tbody>{(s.lines ?? []).map((l, i) => <tr key={i} className="border-t"><td className="py-1">{l.number}{l.paymentId && <span className="block text-[11px] text-muted-foreground">{tx('payment')} {l.paymentId.slice(0, 8)}</span>}{l.splitFrom && <span className="block text-[11px] text-muted-foreground">{tx('split')}</span>}</td><td className="py-1">{l.description}</td><td className="py-1 text-right tabular-nums">{money(l.revenue)}</td><td className="py-1 text-right tabular-nums">{Number(l.rate)}</td><td className="py-1 text-right tabular-nums">{money(l.amount)}</td></tr>)}</tbody></table>
          {s.adjustments.length > 0 && <div><h3 className="font-semibold">{tx('Adjustments')}</h3><ul className="list-disc pl-4">{s.adjustments.map((a) => <li key={a.id}>{money(a.amount)}: {a.reason}</li>)}</ul></div>}
          {s.disputes.length > 0 && <div><h3 className="font-semibold">{tx('Disputes')}</h3><ul className="space-y-1">{s.disputes.map((d) => <li key={d.id} className="flex items-center justify-between gap-2 rounded border p-2"><span>{d.reason} · {tx(d.status)}{d.resolution && ` · ${d.resolution}`}</span>{canManage && d.status === 'open' && <Button size="sm" variant="outline" onClick={() => { const resolution = prompt(tx('How was this resolved?')); if (resolution) api.post(`/sales/commissions/disputes/${d.id}/resolve`, { resolution }).then(refresh).catch(fail); }}>{tx('Resolve')}</Button>}</li>)}</ul></div>}
        </div>}
      </DialogContent>
    </Dialog>
  );
}

function Rules() {
  const { data } = useQuery({ queryKey: ['sales', 'commission-rules'], queryFn: async () => (await api.get<Rule[]>('/sales/commissions/rules')).data });
  const { data: lk } = useCrmLookups();
  const [edit, setEdit] = useState<Partial<Rule> | null>(null);
  const parseTiers = (t: string) => t.split(',').map((x) => x.trim()).filter(Boolean).map((x) => { const [from, rate] = x.split(':'); return { from: Number(from), rate: Number(rate) }; });
  const pick = (r: Partial<Rule> & { tierText?: string }) => ({ name: r.name, salespersonId: r.salespersonId ?? null, basis: r.basis, method: r.method, rate: r.rate, priority: r.priority ?? 0, isActive: r.isActive !== false, ...(r.tierText !== undefined ? { tiers: parseTiers(r.tierText) } : {}) });
  const save = useSave(async (r: Partial<Rule>) => (r.id ? await api.put(`/sales/commissions/rules/${r.id}`, pick(r)) : await api.post('/sales/commissions/rules', pick(r))).data, () => setEdit(null));
  const METHOD: Record<string, string> = { percent_revenue: tx('% of revenue'), percent_margin: tx('% of margin'), per_unit: tx('per unit') };
  return (
    <div className="space-y-2">
      <Button onClick={() => setEdit({ basis: 'invoiced', method: 'percent_revenue', rate: 0, priority: 0, isActive: true })}><Plus className="mr-1 h-4 w-4" />{tx('New rule')}</Button>
      <ul className="space-y-1 text-sm">
        {(data ?? []).map((r) => <li key={r.id} className="cursor-pointer rounded border p-2 hover:bg-muted/30" onClick={() => setEdit(r)}>
          <b>{r.name}</b> · {r.salespersonName ?? tx('everyone')}{r.teamName && ` · ${r.teamName}`}{r.productCategoryName && ` · ${r.productCategoryName}`} · {r.rate} {METHOD[r.method]} · {r.basis} · P{r.priority}{!r.isActive && ` · ${tx('inactive')}`}</li>)}
      </ul>
      {edit && (
        <Dialog open onOpenChange={(v) => !v && setEdit(null)}>
          <DialogContent className="max-w-lg">
            <DialogHeader><DialogTitle>{tx('Commission rule')}</DialogTitle></DialogHeader>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label={tx('Name')} className="sm:col-span-2"><Input value={edit.name ?? ''} onChange={(e) => setEdit({ ...edit, name: e.target.value })} /></Field>
              <Field label={tx('Salesperson')}><select className={sel} value={edit.salespersonId ?? ''} onChange={(e) => setEdit({ ...edit, salespersonId: e.target.value || null })}>
                <option value="">{tx('Everyone')}</option>{(lk?.users ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}</select></Field>
              <Field label={tx('Basis')}><select className={sel} value={edit.basis} onChange={(e) => setEdit({ ...edit, basis: e.target.value })}><option value="invoiced">{tx('Invoiced')}</option><option value="paid">{tx('Paid')}</option></select></Field>
              <Field label={tx('Method')}><select className={sel} value={edit.method} onChange={(e) => setEdit({ ...edit, method: e.target.value })}>{Object.entries(METHOD).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
              <Field label={tx('Rate')}><Input type="number" value={edit.rate ?? 0} onChange={(e) => setEdit({ ...edit, rate: Number(e.target.value) })} /></Field>
              <Field label={tx('Priority')}><Input type="number" value={edit.priority ?? 0} onChange={(e) => setEdit({ ...edit, priority: Number(e.target.value) })} /></Field>
              <Field label={tx('Tiers: from:rate, from:rate (optional)')} className="sm:col-span-2"><Input placeholder="0:1, 1000000:2" onChange={(e) => setEdit({ ...edit, tierText: e.target.value } as Partial<Rule>)} /></Field>
              <label className="flex items-center gap-2 self-end text-sm"><input type="checkbox" checked={edit.isActive !== false} onChange={(e) => setEdit({ ...edit, isActive: e.target.checked })} />{tx('Active')}</label>
            </div>
            <DialogFooter><Button variant="outline" onClick={() => setEdit(null)}>{tx('Cancel')}</Button><Button disabled={!edit.name} onClick={() => save.mutate(edit)}>{tx('Save')}</Button></DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}

function Targets() {
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.targetManage));
  const [month, setMonth] = useState(new Date().toISOString().slice(0, 7));
  const { data } = useQuery({ queryKey: ['sales', 'targets', month], queryFn: async () => (await api.get<Target[]>('/sales/targets/achievement', { params: { month } })).data });
  const { data: lk } = useCrmLookups();
  const [form, setForm] = useState<{ id?: string; salespersonId: string; amount: number; metric: string; periodType: string } | null>(null);
  const save = useSave(async () => { const body = { salespersonId: form?.salespersonId || null, amount: form?.amount, metric: form?.metric, periodType: form?.periodType, periodStart: `${month}-01` }; return (form?.id ? await api.put(`/sales/targets/${form.id}`, body) : await api.post('/sales/targets', body)).data; }, () => setForm(null));
  const METRIC: Record<string, string> = { revenue: tx('Revenue'), gross_profit: tx('Gross profit'), collection: tx('Collections'), new_customers: tx('New customers'), order_count: tx('Number of orders'), order_value: tx('Order value') };
  const del = useSave(async (id: string) => (await api.delete(`/sales/targets/${id}`)).data);
  return (
    <div className="space-y-2">
      <div className="flex gap-2"><Input type="month" className="w-44" value={month} onChange={(e) => setMonth(e.target.value)} />
        {canManage && <Button onClick={() => setForm({ salespersonId: '', amount: 0, metric: 'revenue', periodType: 'month' })}><Plus className="mr-1 h-4 w-4" />{tx('New target')}</Button>}</div>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[640px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className={th}>{tx('For')}</th><th className={cn(th, 'text-right')}>{tx('Target')}</th><th className={cn(th, 'text-right')}>{tx('Actual')}</th><th className={th}>{tx('Achievement')}</th><th /></tr></thead>
          <tbody>
            {(data ?? []).map((t) => (
              <tr key={t.id} className="border-t">
                <td className={th}>{[t.salespersonName, t.teamName, t.branchName, t.productCategoryName].filter(Boolean).join(' · ') || tx('Whole company')}<span className="block text-xs text-muted-foreground">{METRIC[(t as Target & { metric?: string }).metric ?? 'revenue']} · {tx((t as Target & { periodType?: string }).periodType ?? 'month')}</span></td>
                <td className={cn(th, 'text-right tabular-nums')}>{money(t.amount)}</td><td className={cn(th, 'text-right tabular-nums')}>{money(t.actualAmount ?? 0)}</td>
                <td className={th}><div className="flex items-center gap-2"><div className="h-2 w-32 overflow-hidden rounded bg-muted"><div className={cn('h-full', Number(t.achievementPercent) >= 100 ? 'bg-emerald-500' : 'bg-primary')} style={{ width: `${Math.min(100, Number(t.achievementPercent ?? 0))}%` }} /></div><span className="text-xs tabular-nums">{t.achievementPercent ?? '—'}%</span></div></td>
                <td className={th}>{canManage && <><Button size="sm" variant="ghost" onClick={() => setForm({ id: t.id, salespersonId: (t as Target & { salespersonId?: string | null }).salespersonId ?? '', amount: Number(t.amount), metric: (t as Target & { metric?: string }).metric ?? 'revenue', periodType: (t as Target & { periodType?: string }).periodType ?? 'month' })}>{tx('Edit')}</Button><Button size="icon" variant="ghost" onClick={() => del.mutate(t.id)}><Trash2 className="h-4 w-4" /></Button></>}</td>
              </tr>
            ))}
            {data && data.length === 0 && <tr><td colSpan={5} className="p-6 text-center text-muted-foreground">{tx('No targets for this month.')}</td></tr>}
          </tbody>
        </table>
      </div>
      {form && (
        <Dialog open onOpenChange={(v) => !v && setForm(null)}>
          <DialogContent className="max-w-md">
            <DialogHeader><DialogTitle>{tx('Sales target')} {month}</DialogTitle></DialogHeader>
            <Field label={tx('Salesperson')}><select className={sel} value={form.salespersonId} onChange={(e) => setForm({ ...form, salespersonId: e.target.value })}>
              <option value="">{tx('Whole company')}</option>{(lk?.users ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}</select></Field>
            <Field label={tx('Measure')}><select className={sel} value={form.metric} onChange={(e) => setForm({ ...form, metric: e.target.value })}>{Object.entries(METRIC).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
            <Field label={tx('Period')}><select className={sel} value={form.periodType} onChange={(e) => setForm({ ...form, periodType: e.target.value })}><option value="week">{tx('Week')}</option><option value="month">{tx('Month')}</option><option value="quarter">{tx('Quarter')}</option><option value="year">{tx('Year')}</option></select></Field>
            <Field label={tx('Target')}><Input type="number" value={form.amount} onChange={(e) => setForm({ ...form, amount: Number(e.target.value) })} /></Field>
            <DialogFooter><Button variant="outline" onClick={() => setForm(null)}>{tx('Cancel')}</Button><Button onClick={() => save.mutate(undefined)}>{tx('Save')}</Button></DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}

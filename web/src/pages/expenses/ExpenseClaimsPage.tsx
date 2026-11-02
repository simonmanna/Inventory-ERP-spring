// EX-3 (ADR-S052): employee expense claims and cash advances.
import { useEffect, useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { expenseCategoriesApi, type ExpenseCategory } from '@/lib/api/expenseCategories';
import { advancesApi, claimsApi, fmt, userName, type Row } from '@/lib/api/expenseExtras';
import { tx } from '@/lib/i18n/tx';
import {
  act, DataTable, ErrorBox, fieldLabel, FormDialog, MoneyDialog, PageShell, Pill, selectCls, Spinner, Tabs, useLoad,
} from './expense-ui';

interface Line { title: string; amount: string; expenseDate: string; categoryId: string; receiptNumber: string }

const today = () => new Date().toISOString().split('T')[0];
const blankLine = (): Line => ({ title: '', amount: '', expenseDate: today(), categoryId: '', receiptNumber: '' });

function ClaimDialog({ claim, advances, onClose, onSaved }: {
  claim?: Row | null; advances: Row[]; onClose: () => void; onSaved: () => void;
}) {
  const [categories, setCategories] = useState<ExpenseCategory[]>([]);
  const [title, setTitle] = useState(claim?.title ?? '');
  const [advanceId, setAdvanceId] = useState(claim?.advanceId ?? '');
  const [notes, setNotes] = useState(claim?.notes ?? '');
  const [lines, setLines] = useState<Line[]>(
    claim?.lines?.length
      ? claim.lines.map((l: Row) => ({ title: l.title, amount: String(l.amount), expenseDate: String(l.expenseDate).split('T')[0],
          categoryId: l.categoryId ?? '', receiptNumber: l.receiptNumber ?? '' }))
      : [blankLine()],
  );
  useEffect(() => { expenseCategoriesApi.list().then(setCategories).catch(() => setCategories([])); }, []);
  const setLine = (i: number, p: Partial<Line>) => setLines(lines.map((l, j) => (j === i ? { ...l, ...p } : l)));
  const total = lines.reduce((s, l) => s + (Number(l.amount) || 0), 0);
  const body = {
    title, notes: notes || undefined, advanceId: advanceId || (claim ? '' : undefined),
    lines: lines.filter((l) => l.title && l.amount).map((l) => ({
      title: l.title, amount: Number(l.amount), expenseDate: l.expenseDate, categoryId: l.categoryId || undefined,
      receiptNumber: l.receiptNumber || undefined,
    })),
  };
  return (
    <FormDialog title={claim ? tx('Edit claim {{n}}', { n: claim.claimNumber }) : tx('New expense claim')} onClose={onClose}
      onSubmit={async () => { await (claim ? claimsApi.update(claim.id, body) : claimsApi.create(body)); onSaved(); }}>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className={fieldLabel}>{tx('Title')}</label>
          <Input value={title} onChange={(e) => setTitle(e.target.value)} placeholder={tx('e.g. Gulu site visit')} />
        </div>
        <div>
          <label className={fieldLabel}>{tx('Settle against advance')}</label>
          <select className={selectCls} value={advanceId} onChange={(e) => setAdvanceId(e.target.value)}>
            <option value="">{tx('None')}</option>
            {advances.filter((a) => a.status === 'disbursed').map((a) => (
              <option key={a.id} value={a.id}>{a.advanceNumber} · {a.purpose} ({fmt(a.outstanding)})</option>
            ))}
          </select>
        </div>
      </div>
      <div>
        <div className="flex justify-between items-center">
          <span className={fieldLabel}>{tx('Lines')}</span>
          <button type="button" className="text-xs text-[#3c8dbc] flex items-center gap-1" onClick={() => setLines([...lines, blankLine()])}>
            <Plus className="w-3 h-3" />{tx('Add line')}
          </button>
        </div>
        {lines.map((l, i) => (
          <div key={i} className="grid grid-cols-12 gap-1 mt-1">
            <Input className="col-span-3" placeholder={tx('What')} value={l.title} onChange={(e) => setLine(i, { title: e.target.value })} />
            <select className={`${selectCls} col-span-3`} value={l.categoryId} onChange={(e) => setLine(i, { categoryId: e.target.value })}>
              <option value="">{tx('Category…')}</option>
              {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
            <Input className="col-span-2" type="date" value={l.expenseDate} onChange={(e) => setLine(i, { expenseDate: e.target.value })} />
            <Input className="col-span-2" type="number" min={0} placeholder={tx('Amount')} value={l.amount}
              onChange={(e) => setLine(i, { amount: e.target.value })} />
            <Input className="col-span-1" placeholder={tx('Rcpt')} value={l.receiptNumber} onChange={(e) => setLine(i, { receiptNumber: e.target.value })} />
            <button type="button" className="col-span-1 text-slate-400 hover:text-red-600" onClick={() => setLines(lines.filter((_, j) => j !== i))}>
              <Trash2 className="w-4 h-4" />
            </button>
          </div>
        ))}
        <p className="text-right text-sm font-semibold mt-2">{tx('Total')}: {fmt(total)}</p>
      </div>
      <div>
        <label className={fieldLabel}>{tx('Notes')}</label>
        <Input value={notes} onChange={(e) => setNotes(e.target.value)} />
      </div>
    </FormDialog>
  );
}

function ClaimDetail({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { data: c, loading, error, reload } = useLoad(() => claimsApi.get(id), [id]);
  const [pay, setPay] = useState(false);
  const refresh = () => { reload(); onChanged(); };
  const reason = (label: string) => prompt(label)?.trim();
  if (loading || !c) return <FormDialog title={tx('Claim')} onClose={onClose} onSubmit={async () => {}}>{error ? <ErrorBox error={error} /> : <Spinner />}</FormDialog>;
  return (
    <FormDialog title={`${c.claimNumber} · ${c.title}`} onClose={onClose} submitLabel={tx('Close')} onSubmit={async () => {}}>
      <div className="flex flex-wrap gap-2 items-center text-sm">
        <Pill status={c.status} />
        <span>{tx('Claimant')}: <b>{userName(c.claimant)}</b></span>
        <span>{tx('Total')}: <b>{fmt(c.totalAmount)}</b></span>
        <span>{tx('Advance applied')}: <b>{fmt(c.advanceApplied)}</b></span>
        <span>{tx('Paid')}: <b>{fmt(c.amountPaid)}</b></span>
        <span>{tx('Due')}: <b>{fmt(c.amountDue)}</b></span>
      </div>
      {c.decisionNotes && <p className="text-xs text-amber-700">{tx('Note')}: {c.decisionNotes}</p>}
      <DataTable rows={c.lines} columns={[
        { key: 'expenseCode', label: tx('Code') }, { key: 'title', label: tx('Line') },
        { key: 'categoryName', label: tx('Category') }, { key: 'receiptNumber', label: tx('Receipt') },
        { key: 'amount', label: tx('Amount'), right: true, render: (r) => fmt(r.amount) },
        { key: 'status', label: tx('Status'), render: (r) => <Pill status={r.status} /> },
      ]} />
      <div className="flex flex-wrap gap-2">
        {['draft', 'returned'].includes(c.status) && <>
          <Button size="sm" onClick={() => act(() => claimsApi.submit(c.id), refresh)}>{tx('Submit')}</Button>
          <Button size="sm" variant="outline" onClick={() => act(() => claimsApi.cancel(c.id), refresh)}>{tx('Cancel claim')}</Button>
        </>}
        {c.status === 'submitted' && <>
          <Button size="sm" onClick={() => act(() => claimsApi.approve(c.id), refresh)}>{tx('Approve')}</Button>
          <Button size="sm" variant="outline" onClick={() => { const r = reason(tx('Reason for returning')); if (r) act(() => claimsApi.returnForCorrection(c.id, r), refresh); }}>{tx('Return')}</Button>
          <Button size="sm" variant="outline" onClick={() => { const r = reason(tx('Reason for rejecting')); if (r) act(() => claimsApi.reject(c.id, r), refresh); }}>{tx('Reject')}</Button>
        </>}
        {['approved', 'partially_paid'].includes(c.status) && (
          <Button size="sm" onClick={() => setPay(true)}>{tx('Reimburse')}</Button>
        )}
      </div>
      {pay && <MoneyDialog title={tx('Reimburse {{n}}', { n: c.claimNumber })} maxAmount={c.amountDue}
        onClose={() => setPay(false)} onSubmit={async (b) => { await claimsApi.pay(c.id, b); refresh(); }} />}
    </FormDialog>
  );
}

function AdvanceDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState({ purpose: '', amount: '', neededBy: '' });
  return (
    <FormDialog title={tx('Request a cash advance')} onClose={onClose} onSubmit={async () => {
      await advancesApi.create({ purpose: form.purpose, amount: Number(form.amount), neededBy: form.neededBy || undefined });
      onSaved();
    }}>
      <div className="grid grid-cols-3 gap-3">
        <div className="col-span-3">
          <label className={fieldLabel}>{tx('Purpose')}</label>
          <Input value={form.purpose} onChange={(e) => setForm({ ...form, purpose: e.target.value })} />
        </div>
        <div>
          <label className={fieldLabel}>{tx('Amount')}</label>
          <Input type="number" min={0} value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
        </div>
        <div>
          <label className={fieldLabel}>{tx('Needed by')}</label>
          <Input type="date" value={form.neededBy} onChange={(e) => setForm({ ...form, neededBy: e.target.value })} />
        </div>
      </div>
    </FormDialog>
  );
}

export default function ExpenseClaimsPage() {
  const [tab, setTab] = useState('claims');
  const [mine, setMine] = useState(false);
  const claims = useLoad(() => claimsApi.list({ mine }), [mine]);
  const advances = useLoad(() => advancesApi.list(), []);
  const [dialog, setDialog] = useState<'claim' | 'advance' | null>(null);
  const [detail, setDetail] = useState<string | null>(null);
  const [money, setMoney] = useState<{ kind: 'disburse' | 'refund'; row: Row } | null>(null);

  return (
    <PageShell title={tx('Claims & advances')} subtitle={tx('Employee expense claims, reimbursements and cash advances')}
      actions={<>
        <Button size="sm" variant="outline" onClick={() => setDialog('advance')}><Plus className="w-4 h-4 mr-1" />{tx('Advance')}</Button>
        <Button size="sm" onClick={() => setDialog('claim')}><Plus className="w-4 h-4 mr-1" />{tx('Claim')}</Button>
      </>}>
      <Tabs tabs={[['claims', tx('Claims')], ['advances', tx('Advances')]]} value={tab} onChange={setTab} />
      {tab === 'claims' && <>
        <label className="text-sm flex items-center gap-2"><input type="checkbox" checked={mine} onChange={(e) => setMine(e.target.checked)} />{tx('Only my claims')}</label>
        <ErrorBox error={claims.error} />
        {claims.loading ? <Spinner /> : <DataTable rows={claims.data} onRow={(r) => setDetail(r.id)} columns={[
          { key: 'claimNumber', label: tx('Claim') }, { key: 'title', label: tx('Title') },
          { key: 'claimant', label: tx('Claimant'), render: (r) => userName(r.claimant) },
          { key: 'totalAmount', label: tx('Total'), right: true, render: (r) => fmt(r.totalAmount) },
          { key: 'amountDue', label: tx('Due'), right: true, render: (r) => fmt(r.amountDue) },
          { key: 'status', label: tx('Status'), render: (r) => <Pill status={r.status} /> },
        ]} />}
      </>}
      {tab === 'advances' && <>
        <ErrorBox error={advances.error} />
        {advances.loading ? <Spinner /> : <DataTable rows={advances.data} columns={[
          { key: 'advanceNumber', label: tx('Advance') }, { key: 'purpose', label: tx('Purpose') },
          { key: 'employee', label: tx('Employee'), render: (r) => userName(r.employee) },
          { key: 'amount', label: tx('Amount'), right: true, render: (r) => fmt(r.amount) },
          { key: 'outstanding', label: tx('Outstanding'), right: true, render: (r) => fmt(r.outstanding) },
          { key: 'status', label: tx('Status'), render: (r) => <Pill status={r.status} /> },
          { key: 'actions', label: '', render: (r) => (
            <div className="flex gap-1 justify-end">
              {r.status === 'requested' && <>
                <Button size="sm" variant="outline" onClick={() => act(() => advancesApi.approve(r.id), advances.reload)}>{tx('Approve')}</Button>
                <Button size="sm" variant="outline" onClick={() => { const why = prompt(tx('Reason')); if (why) act(() => advancesApi.reject(r.id, why), advances.reload); }}>{tx('Reject')}</Button>
              </>}
              {r.status === 'approved' && <Button size="sm" onClick={() => setMoney({ kind: 'disburse', row: r })}>{tx('Disburse')}</Button>}
              {r.status === 'disbursed' && <Button size="sm" variant="outline" onClick={() => setMoney({ kind: 'refund', row: r })}>{tx('Refund')}</Button>}
            </div>
          ) },
        ]} />}
      </>}
      {dialog === 'claim' && <ClaimDialog advances={advances.data ?? []} onClose={() => setDialog(null)} onSaved={claims.reload} />}
      {dialog === 'advance' && <AdvanceDialog onClose={() => setDialog(null)} onSaved={advances.reload} />}
      {detail && <ClaimDetail id={detail} onClose={() => setDetail(null)} onChanged={() => { claims.reload(); advances.reload(); }} />}
      {money && <MoneyDialog title={money.kind === 'disburse' ? tx('Disburse {{n}}', { n: money.row.advanceNumber }) : tx('Refund to {{n}}', { n: money.row.advanceNumber })}
        maxAmount={money.kind === 'refund' ? money.row.outstanding : undefined} onClose={() => setMoney(null)}
        onSubmit={async (b) => { await (money.kind === 'disburse' ? advancesApi.disburse(money.row.id, b) : advancesApi.refund(money.row.id, b)); advances.reload(); }} />}
    </PageShell>
  );
}

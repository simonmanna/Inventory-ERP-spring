// EX-4 (ADR-S052): petty cash funds — float, custodian, replenishment, cash counts, statement.
import { useEffect, useState } from 'react';
import { Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { fmt, lookupsApi, pettyCashApi, userName, type Row } from '@/lib/api/expenseExtras';
import { tx } from '@/lib/i18n/tx';
import { DataTable, ErrorBox, fieldLabel, FormDialog, PageShell, selectCls, Spinner, useLoad } from './expense-ui';

function FundDialog({ fund, onClose, onSaved }: { fund?: Row | null; onClose: () => void; onSaved: () => void }) {
  const [accounts, setAccounts] = useState<Row[]>([]);
  const [users, setUsers] = useState<Row[]>([]);
  const [f, setF] = useState({
    name: fund?.name ?? '', accountId: fund?.accountId ?? '', custodianId: fund?.custodianId ?? '',
    floatAmount: fund ? String(fund.floatAmount) : '', maxPayment: fund?.maxPayment != null ? String(fund.maxPayment) : '',
  });
  useEffect(() => {
    lookupsApi.cashAccounts().then(setAccounts);
    lookupsApi.users().then(setUsers);
  }, []);
  return (
    <FormDialog title={fund ? tx('Edit fund') : tx('New petty cash fund')} onClose={onClose} onSubmit={async () => {
      const body = { name: f.name, custodianId: f.custodianId, floatAmount: Number(f.floatAmount),
        maxPayment: f.maxPayment ? Number(f.maxPayment) : null };
      await (fund ? pettyCashApi.update(fund.id, body) : pettyCashApi.create({ ...body, accountId: f.accountId }));
      onSaved();
    }}>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className={fieldLabel}>{tx('Name')}</label>
          <Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
        </div>
        <div>
          <label className={fieldLabel}>{tx('GL cash account')}</label>
          <select className={selectCls} disabled={!!fund} value={f.accountId} onChange={(e) => setF({ ...f, accountId: e.target.value })}>
            <option value="">{tx('Select…')}</option>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </div>
        <div>
          <label className={fieldLabel}>{tx('Custodian')}</label>
          <select className={selectCls} value={f.custodianId} onChange={(e) => setF({ ...f, custodianId: e.target.value })}>
            <option value="">{tx('Anyone with expense:post')}</option>
            {users.map((u) => <option key={u.id} value={u.id}>{userName(u)}</option>)}
          </select>
        </div>
        <div>
          <label className={fieldLabel}>{tx('Float (imprest)')}</label>
          <Input type="number" min={0} value={f.floatAmount} onChange={(e) => setF({ ...f, floatAmount: e.target.value })} />
        </div>
        <div>
          <label className={fieldLabel}>{tx('Max per payment')}</label>
          <Input type="number" min={0} placeholder={tx('No cap')} value={f.maxPayment} onChange={(e) => setF({ ...f, maxPayment: e.target.value })} />
        </div>
      </div>
    </FormDialog>
  );
}

function FundDetail({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const fund = useLoad(() => pettyCashApi.get(id), [id]);
  const [from, setFrom] = useState(() => new Date(new Date().getFullYear(), new Date().getMonth(), 1).toISOString().split('T')[0]);
  const [to, setTo] = useState(() => new Date().toISOString().split('T')[0]);
  const st = useLoad(() => pettyCashApi.statement(id, from, to), [id, from, to]);
  const [mode, setMode] = useState<'replenish' | 'count' | 'edit' | null>(null);
  const [accounts, setAccounts] = useState<Row[]>([]);
  const [form, setForm] = useState({ sourceAccountId: '', amount: '', counted: '', notes: '' });
  useEffect(() => { lookupsApi.cashAccounts().then(setAccounts); }, []);
  const refresh = () => { fund.reload(); st.reload(); onChanged(); };
  const f = fund.data;
  return (
    <FormDialog title={f ? `${f.name} · ${f.account?.code ?? ''}` : tx('Fund')} onClose={onClose} submitLabel={tx('Close')} onSubmit={async () => {}}>
      {!f ? <Spinner /> : <>
        <div className="grid grid-cols-4 gap-2 text-sm">
          <div><div className="text-xs text-slate-500">{tx('Balance')}</div><b>{fmt(f.balance)}</b></div>
          <div><div className="text-xs text-slate-500">{tx('Float')}</div><b>{fmt(f.floatAmount)}</b></div>
          <div><div className="text-xs text-slate-500">{tx('To replenish')}</div><b>{fmt(f.replenishmentDue)}</b></div>
          <div><div className="text-xs text-slate-500">{tx('Custodian')}</div><b>{userName(f.custodian) || '—'}</b></div>
        </div>
        <div className="flex gap-2">
          <Button size="sm" onClick={() => setMode('replenish')}>{tx('Replenish')}</Button>
          <Button size="sm" variant="outline" onClick={() => setMode('count')}>{tx('Count cash')}</Button>
          <Button size="sm" variant="outline" onClick={() => setMode('edit')}>{tx('Edit')}</Button>
        </div>
        <div className="flex gap-2 items-end">
          <div><label className={fieldLabel}>{tx('From')}</label><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
          <div><label className={fieldLabel}>{tx('To')}</label><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
          {st.data && <span className="text-sm ml-auto">{tx('Opening')} <b>{fmt(st.data.openingBalance)}</b> → {tx('Closing')} <b>{fmt(st.data.closingBalance)}</b></span>}
        </div>
        <ErrorBox error={st.error} />
        <DataTable rows={st.data?.lines} columns={[
          { key: 'postingDate', label: tx('Date'), render: (r) => String(r.postingDate).split('T')[0] },
          { key: 'entryNumber', label: tx('Entry') }, { key: 'description', label: tx('Description') },
          { key: 'debit', label: tx('In'), right: true, render: (r) => (r.debit ? fmt(r.debit) : '') },
          { key: 'credit', label: tx('Out'), right: true, render: (r) => (r.credit ? fmt(r.credit) : '') },
          { key: 'balance', label: tx('Balance'), right: true, render: (r) => fmt(r.balance) },
        ]} />
        <h3 className="text-sm font-semibold">{tx('Recent counts')}</h3>
        <DataTable rows={f.counts} columns={[
          { key: 'countedAt', label: tx('When'), render: (r) => String(r.countedAt).replace('T', ' ').slice(0, 16) },
          { key: 'expectedBalance', label: tx('Expected'), right: true, render: (r) => fmt(r.expectedBalance) },
          { key: 'countedAmount', label: tx('Counted'), right: true, render: (r) => fmt(r.countedAmount) },
          { key: 'difference', label: tx('Over / short'), right: true, render: (r) => fmt(r.difference) },
          { key: 'notes', label: tx('Notes') },
        ]} />
      </>}
      {mode === 'replenish' && f && (
        <FormDialog title={tx('Replenish {{n}}', { n: f.name })} onClose={() => setMode(null)} onSubmit={async () => {
          await pettyCashApi.replenish(id, { sourceAccountId: form.sourceAccountId, amount: form.amount ? Number(form.amount) : undefined,
            paymentMethod: 'BANK_TRANSFER' });
          refresh();
        }}>
          <label className={fieldLabel}>{tx('From account')}</label>
          <select className={selectCls} value={form.sourceAccountId} onChange={(e) => setForm({ ...form, sourceAccountId: e.target.value })}>
            <option value="">{tx('Select…')}</option>
            {accounts.filter((a) => a.id !== f.accountId).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
          <label className={fieldLabel}>{tx('Amount')}</label>
          <Input type="number" min={0} placeholder={tx('Top up to float: {{n}}', { n: fmt(f.replenishmentDue) })} value={form.amount}
            onChange={(e) => setForm({ ...form, amount: e.target.value })} />
        </FormDialog>
      )}
      {mode === 'count' && f && (
        <FormDialog title={tx('Count {{n}}', { n: f.name })} onClose={() => setMode(null)} submitLabel={tx('Record count')} onSubmit={async () => {
          await pettyCashApi.count(id, { countedAmount: Number(form.counted), notes: form.notes || undefined });
          refresh();
        }}>
          <p className="text-sm">{tx('Expected (ledger)')}: <b>{fmt(f.balance)}</b></p>
          <label className={fieldLabel}>{tx('Cash counted')}</label>
          <Input type="number" min={0} value={form.counted} onChange={(e) => setForm({ ...form, counted: e.target.value })} />
          <label className={fieldLabel}>{tx('Notes')}</label>
          <Input value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
          <p className="text-xs text-slate-500">{tx('A difference is posted to Cash Short & Over.')}</p>
        </FormDialog>
      )}
      {mode === 'edit' && f && <FundDialog fund={f} onClose={() => setMode(null)} onSaved={refresh} />}
    </FormDialog>
  );
}

export default function PettyCashPage() {
  const funds = useLoad(() => pettyCashApi.list(), []);
  const [creating, setCreating] = useState(false);
  const [detail, setDetail] = useState<string | null>(null);
  return (
    <PageShell title={tx('Petty cash')} subtitle={tx('Imprest funds: pay small expenses from the fund account, replenish to float, count regularly')}
      actions={<Button size="sm" onClick={() => setCreating(true)}><Plus className="w-4 h-4 mr-1" />{tx('Fund')}</Button>}>
      <ErrorBox error={funds.error} />
      {funds.loading ? <Spinner /> : <DataTable rows={funds.data} onRow={(r) => setDetail(r.id)} columns={[
        { key: 'name', label: tx('Fund') },
        { key: 'account', label: tx('Account'), render: (r) => r.account ? `${r.account.code} · ${r.account.name}` : '—' },
        { key: 'custodian', label: tx('Custodian'), render: (r) => userName(r.custodian) || '—' },
        { key: 'floatAmount', label: tx('Float'), right: true, render: (r) => fmt(r.floatAmount) },
        { key: 'balance', label: tx('Balance'), right: true, render: (r) => fmt(r.balance) },
        { key: 'replenishmentDue', label: tx('To replenish'), right: true, render: (r) => fmt(r.replenishmentDue) },
        { key: 'lastCount', label: tx('Last count'), render: (r) => r.lastCount ? String(r.lastCount.countedAt).split('T')[0] : tx('never') },
      ]} />}
      {creating && <FundDialog onClose={() => setCreating(false)} onSaved={funds.reload} />}
      {detail && <FundDetail id={detail} onClose={() => setDetail(null)} onChanged={funds.reload} />}
    </PageShell>
  );
}

// EX-2 (ADR-S052): spending limits and expense settings (notifications, reminders, claim receipts).
import { useEffect, useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { expenseCategoriesApi, type ExpenseCategory } from '@/lib/api/expenseCategories';
import { controlsApi, errMsg, fmt, lookupsApi, userName, type Row } from '@/lib/api/expenseExtras';
import { tx } from '@/lib/i18n/tx';
import { act, DataTable, ErrorBox, fieldLabel, FormDialog, PageShell, selectCls, Spinner, useLoad } from './expense-ui';

function LimitDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [users, setUsers] = useState<Row[]>([]);
  const [branches, setBranches] = useState<Row[]>([]);
  const [categories, setCategories] = useState<ExpenseCategory[]>([]);
  const [f, setF] = useState({ name: '', userId: '', categoryId: '', branchId: '', period: 'per_expense', amount: '', action: 'block' });
  useEffect(() => {
    lookupsApi.users().then(setUsers);
    lookupsApi.branches().then(setBranches);
    expenseCategoriesApi.list().then(setCategories).catch(() => setCategories([]));
  }, []);
  const set = (p: Partial<typeof f>) => setF({ ...f, ...p });
  return (
    <FormDialog title={tx('New spending limit')} onClose={onClose} onSubmit={async () => {
      await controlsApi.createLimit({ ...f, amount: Number(f.amount), userId: f.userId || undefined,
        categoryId: f.categoryId || undefined, branchId: f.branchId || undefined });
      onSaved();
    }}>
      <div className="grid grid-cols-2 gap-3">
        <div className="col-span-2"><label className={fieldLabel}>{tx('Name')}</label><Input value={f.name} onChange={(e) => set({ name: e.target.value })} /></div>
        <div>
          <label className={fieldLabel}>{tx('Employee')}</label>
          <select className={selectCls} value={f.userId} onChange={(e) => set({ userId: e.target.value })}>
            <option value="">{tx('Everyone')}</option>
            {users.map((u) => <option key={u.id} value={u.id}>{userName(u)}</option>)}
          </select>
        </div>
        <div>
          <label className={fieldLabel}>{tx('Category')}</label>
          <select className={selectCls} value={f.categoryId} onChange={(e) => set({ categoryId: e.target.value })}>
            <option value="">{tx('Any')}</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
        <div>
          <label className={fieldLabel}>{tx('Branch')}</label>
          <select className={selectCls} value={f.branchId} onChange={(e) => set({ branchId: e.target.value })}>
            <option value="">{tx('Any')}</option>
            {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select>
        </div>
        <div>
          <label className={fieldLabel}>{tx('Period')}</label>
          <select className={selectCls} value={f.period} onChange={(e) => set({ period: e.target.value })}>
            <option value="per_expense">{tx('Per expense')}</option>
            <option value="monthly">{tx('Per month')}</option>
          </select>
        </div>
        <div><label className={fieldLabel}>{tx('Limit')}</label><Input type="number" min={0} value={f.amount} onChange={(e) => set({ amount: e.target.value })} /></div>
        <div>
          <label className={fieldLabel}>{tx('When exceeded')}</label>
          <select className={selectCls} value={f.action} onChange={(e) => set({ action: e.target.value })}>
            <option value="block">{tx('Block')}</option>
            <option value="warn">{tx('Warn (audit only)')}</option>
          </select>
        </div>
      </div>
    </FormDialog>
  );
}

function SettingsPanel() {
  const s = useLoad(() => controlsApi.settings(), []);
  const [msg, setMsg] = useState('');
  const save = async (patch: Row) => {
    try {
      await controlsApi.saveSettings(patch);
      setMsg(tx('Saved'));
      s.reload();
    } catch (e) {
      setMsg(errMsg(e));
    }
  };
  if (s.loading || !s.data) return <Spinner />;
  const flag = (k: string, label: string) => (
    <label className="flex items-center gap-2 text-sm">
      <input type="checkbox" checked={!!s.data![k]} onChange={(e) => save({ [k]: e.target.checked })} />{label}
    </label>
  );
  return (
    <div className="bg-white border rounded p-4 space-y-2">
      <h2 className="font-semibold">{tx('Settings')}</h2>
      {flag('notifyOnDecision', tx('Notify the raiser when an expense, claim or advance is approved, returned or rejected'))}
      {flag('notifyOnPayment', tx('Notify the raiser when it is paid'))}
      {flag('claimReceiptRequired', tx('Every claim line needs a receipt number'))}
      <label className="flex items-center gap-2 text-sm">
        {tx('Remind owners of recurring expenses')}
        <Input type="number" min={0} max={60} className="w-20" defaultValue={s.data.recurringReminderDays}
          onBlur={(e) => save({ recurringReminderDays: Number(e.target.value) })} />
        {tx('days before')}
      </label>
      <p className="text-xs text-slate-500">
        {tx('Separation of duties for payments (raiser / approver / claimant cannot pay) is switched on under Approval controls → "Expense payment".')}
      </p>
      {msg && <p className="text-xs text-slate-500">{msg}</p>}
    </div>
  );
}

export default function ExpenseControlsPage() {
  const limits = useLoad(() => controlsApi.limits(), []);
  const [adding, setAdding] = useState(false);
  return (
    <PageShell title={tx('Expense controls')} subtitle={tx('Spending limits and settings. Budgets: Accounting → Budgets, then set "Budget control" on a category.')}
      actions={<Button size="sm" onClick={() => setAdding(true)}><Plus className="w-4 h-4 mr-1" />{tx('Limit')}</Button>}>
      <ErrorBox error={limits.error} />
      {limits.loading ? <Spinner /> : <DataTable rows={limits.data} columns={[
        { key: 'name', label: tx('Limit') },
        { key: 'scope', label: tx('Applies to'), render: (r) => [r.userId && tx('employee'), r.categoryId && tx('category'), r.branchId && tx('branch')].filter(Boolean).join(' + ') || tx('everyone') },
        { key: 'period', label: tx('Period'), render: (r) => (r.period === 'monthly' ? tx('per month') : tx('per expense')) },
        { key: 'amount', label: tx('Amount'), right: true, render: (r) => fmt(r.amount) },
        { key: 'action', label: tx('Action') },
        { key: 'isActive', label: tx('Active'), render: (r) => (
          <input type="checkbox" checked={!!r.isActive} onChange={(e) => act(() => controlsApi.updateLimit(r.id, { isActive: e.target.checked }), limits.reload)} />
        ) },
        { key: 'del', label: '', render: (r) => (
          <button className="text-slate-400 hover:text-red-600" onClick={() => confirm(tx('Delete this limit?')) && act(() => controlsApi.deleteLimit(r.id), limits.reload)}>
            <Trash2 className="w-4 h-4" />
          </button>
        ) },
      ]} />}
      <SettingsPanel />
      {adding && <LimitDialog onClose={() => setAdding(false)} onSaved={limits.reload} />}
    </PageShell>
  );
}

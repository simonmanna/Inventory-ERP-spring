// EX-5 (ADR-S052): recurring expenses, prepaid release schedules and period-end accruals.
import { useEffect, useState } from 'react';
import { Play, Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { expenseCategoriesApi, type ExpenseCategory } from '@/lib/api/expenseCategories';
import { errMsg, fmt, schedulesApi } from '@/lib/api/expenseExtras';
import { tx } from '@/lib/i18n/tx';
import { act, DataTable, ErrorBox, fieldLabel, FormDialog, PageShell, Pill, selectCls, Spinner, Tabs, useLoad } from './expense-ui';

const day = (v: unknown) => (v ? String(v).split('T')[0] : '—');

function useCategories() {
  const [categories, setCategories] = useState<ExpenseCategory[]>([]);
  useEffect(() => { expenseCategoriesApi.list().then(setCategories).catch(() => setCategories([])); }, []);
  return categories;
}

function RecurrenceDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const categories = useCategories();
  const [f, setF] = useState({ name: '', title: '', amount: '', categoryId: '', frequency: 'monthly', intervalCount: '1',
    nextRunDate: new Date().toISOString().split('T')[0], endDate: '', dueDays: '0', reminderDays: '' });
  const set = (p: Partial<typeof f>) => setF({ ...f, ...p });
  return (
    <FormDialog title={tx('New recurring expense')} onClose={onClose} onSubmit={async () => {
      await schedulesApi.createRecurrence({ name: f.name, title: f.title || f.name, amount: Number(f.amount),
        categoryId: f.categoryId || undefined, frequency: f.frequency, intervalCount: Number(f.intervalCount),
        nextRunDate: f.nextRunDate, endDate: f.endDate || undefined, dueDays: Number(f.dueDays),
        reminderDays: f.reminderDays ? Number(f.reminderDays) : undefined });
      onSaved();
    }}>
      <div className="grid grid-cols-3 gap-3">
        <div><label className={fieldLabel}>{tx('Name')}</label><Input value={f.name} onChange={(e) => set({ name: e.target.value })} /></div>
        <div><label className={fieldLabel}>{tx('Expense title')}</label><Input value={f.title} onChange={(e) => set({ title: e.target.value })} /></div>
        <div><label className={fieldLabel}>{tx('Amount')}</label><Input type="number" min={0} value={f.amount} onChange={(e) => set({ amount: e.target.value })} /></div>
        <div>
          <label className={fieldLabel}>{tx('Category')}</label>
          <select className={selectCls} value={f.categoryId} onChange={(e) => set({ categoryId: e.target.value })}>
            <option value="">{tx('Select…')}</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
        <div>
          <label className={fieldLabel}>{tx('Every')}</label>
          <div className="flex gap-1">
            <Input type="number" min={1} max={12} className="w-16" value={f.intervalCount} onChange={(e) => set({ intervalCount: e.target.value })} />
            <select className={selectCls} value={f.frequency} onChange={(e) => set({ frequency: e.target.value })}>
              {['weekly', 'monthly', 'quarterly', 'yearly'].map((k) => <option key={k} value={k}>{tx(k)}</option>)}
            </select>
          </div>
        </div>
        <div><label className={fieldLabel}>{tx('First run')}</label><Input type="date" value={f.nextRunDate} onChange={(e) => set({ nextRunDate: e.target.value })} /></div>
        <div><label className={fieldLabel}>{tx('End date')}</label><Input type="date" value={f.endDate} onChange={(e) => set({ endDate: e.target.value })} /></div>
        <div><label className={fieldLabel}>{tx('Due after (days)')}</label><Input type="number" min={0} value={f.dueDays} onChange={(e) => set({ dueDays: e.target.value })} /></div>
        <div><label className={fieldLabel}>{tx('Remind (days before)')}</label><Input type="number" min={0} max={60} placeholder={tx('settings default')} value={f.reminderDays} onChange={(e) => set({ reminderDays: e.target.value })} /></div>
      </div>
    </FormDialog>
  );
}

function AccrualDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const categories = useCategories();
  const lastMonthEnd = () => { const d = new Date(); d.setDate(0); return d.toISOString().split('T')[0]; };
  const [f, setF] = useState({ title: '', amount: '', categoryId: '', accrualDate: lastMonthEnd(), reversalDate: '' });
  const set = (p: Partial<typeof f>) => setF({ ...f, ...p });
  return (
    <FormDialog title={tx('New period-end accrual')} onClose={onClose} submitLabel={tx('Post accrual')} onSubmit={async () => {
      await schedulesApi.createAccrual({ title: f.title, amount: Number(f.amount), categoryId: f.categoryId || undefined,
        accrualDate: f.accrualDate, reversalDate: f.reversalDate || undefined });
      onSaved();
    }}>
      <div className="grid grid-cols-2 gap-3">
        <div><label className={fieldLabel}>{tx('What is accrued')}</label><Input value={f.title} onChange={(e) => set({ title: e.target.value })} /></div>
        <div><label className={fieldLabel}>{tx('Amount')}</label><Input type="number" min={0} value={f.amount} onChange={(e) => set({ amount: e.target.value })} /></div>
        <div>
          <label className={fieldLabel}>{tx('Category (expense account)')}</label>
          <select className={selectCls} value={f.categoryId} onChange={(e) => set({ categoryId: e.target.value })}>
            <option value="">{tx('Default expense account')}</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
        <div><label className={fieldLabel}>{tx('Accrual date')}</label><Input type="date" value={f.accrualDate} onChange={(e) => set({ accrualDate: e.target.value })} /></div>
        <div><label className={fieldLabel}>{tx('Reverses on')}</label><Input type="date" value={f.reversalDate} placeholder={tx('first day of next month')} onChange={(e) => set({ reversalDate: e.target.value })} /></div>
      </div>
      <p className="text-xs text-slate-500">{tx('Posts Dr expense / Cr Accrued Expenses; reversed automatically on the reversal date.')}</p>
    </FormDialog>
  );
}

export default function ExpenseSchedulesPage() {
  const [tab, setTab] = useState('recurring');
  const rec = useLoad(() => schedulesApi.recurrences(), []);
  const pre = useLoad(() => schedulesApi.prepayments(), []);
  const acc = useLoad(() => schedulesApi.accruals(), []);
  const [dialog, setDialog] = useState<'recurring' | 'accrual' | null>(null);
  const [runMsg, setRunMsg] = useState('');
  const reloadAll = () => { rec.reload(); pre.reload(); acc.reload(); };
  const runDue = async () => {
    try {
      const r = await schedulesApi.runDue();
      setRunMsg(tx('{{g}} expenses raised, {{a}} accruals reversed, {{p}} prepaid months released, {{r}} reminders', {
        g: r.expensesGenerated, a: r.accrualsReversed, p: r.prepaidReleased, r: r.remindersSent }) + (r.errors?.length ? ` · ${r.errors.length} ${tx('errors')}` : ''));
      reloadAll();
    } catch (e) {
      setRunMsg(errMsg(e));
    }
  };
  return (
    <PageShell title={tx('Expense schedules')} subtitle={tx('Recurring expenses, prepaid releases and period-end accruals (run daily)')}
      actions={<>
        <Button size="sm" variant="outline" onClick={runDue}><Play className="w-4 h-4 mr-1" />{tx('Run due now')}</Button>
        {tab === 'recurring' && <Button size="sm" onClick={() => setDialog('recurring')}><Plus className="w-4 h-4 mr-1" />{tx('Recurring')}</Button>}
        {tab === 'accruals' && <Button size="sm" onClick={() => setDialog('accrual')}><Plus className="w-4 h-4 mr-1" />{tx('Accrual')}</Button>}
      </>}>
      {runMsg && <div className="p-2 text-sm bg-slate-50 border rounded">{runMsg}</div>}
      <Tabs tabs={[['recurring', tx('Recurring')], ['prepaid', tx('Prepaid')], ['accruals', tx('Accruals')]]} value={tab} onChange={setTab} />
      {tab === 'recurring' && (rec.loading ? <Spinner /> : <>
        <ErrorBox error={rec.error} />
        <DataTable rows={rec.data} columns={[
          { key: 'name', label: tx('Name') },
          { key: 'amount', label: tx('Amount'), right: true, render: (r) => fmt(r.amount) },
          { key: 'frequency', label: tx('Every'), render: (r) => `${r.intervalCount} ${tx(r.frequency)}` },
          { key: 'nextRunDate', label: tx('Next run'), render: (r) => day(r.nextRunDate) },
          { key: 'runCount', label: tx('Runs'), right: true },
          { key: 'status', label: tx('Status'), render: (r) => <Pill status={r.status} /> },
          { key: 'actions', label: '', render: (r) => (
            <div className="flex gap-1 justify-end">
              {r.status === 'active' && <>
                <Button size="sm" variant="outline" onClick={() => act(() => schedulesApi.runRecurrence(r.id), rec.reload)}>{tx('Run now')}</Button>
                <Button size="sm" variant="outline" onClick={() => act(() => schedulesApi.setRecurrence(r.id, 'pause'), rec.reload)}>{tx('Pause')}</Button>
              </>}
              {r.status === 'paused' && <Button size="sm" variant="outline" onClick={() => act(() => schedulesApi.setRecurrence(r.id, 'resume'), rec.reload)}>{tx('Resume')}</Button>}
            </div>
          ) },
        ]} />
      </>)}
      {tab === 'prepaid' && (pre.loading ? <Spinner /> : <>
        <ErrorBox error={pre.error} />
        <p className="text-xs text-slate-500">{tx('Enter a prepaid expense from the expense form ("Prepaid over (months)"); it is released here month by month.')}</p>
        <DataTable rows={pre.data} columns={[
          { key: 'expenseCode', label: tx('Expense') }, { key: 'title', label: tx('Title') },
          { key: 'startMonth', label: tx('From') }, { key: 'months', label: tx('Months'), right: true },
          { key: 'totalAmount', label: tx('Total'), right: true, render: (r) => fmt(r.totalAmount) },
          { key: 'releasedAmount', label: tx('Released'), right: true, render: (r) => fmt(r.releasedAmount) },
          { key: 'remaining', label: tx('Remaining'), right: true, render: (r) => fmt(r.remaining) },
          { key: 'status', label: tx('Status'), render: (r) => <Pill status={r.status} /> },
        ]} />
      </>)}
      {tab === 'accruals' && (acc.loading ? <Spinner /> : <>
        <ErrorBox error={acc.error} />
        <DataTable rows={acc.data} columns={[
          { key: 'accrualNumber', label: tx('Accrual') }, { key: 'title', label: tx('Title') },
          { key: 'amount', label: tx('Amount'), right: true, render: (r) => fmt(r.amount) },
          { key: 'accrualDate', label: tx('Accrued'), render: (r) => day(r.accrualDate) },
          { key: 'reversalDate', label: tx('Reverses'), render: (r) => day(r.reversalDate) },
          { key: 'status', label: tx('Status'), render: (r) => <Pill status={r.status} /> },
          { key: 'actions', label: '', render: (r) => r.status === 'posted'
            ? <Button size="sm" variant="outline" onClick={() => act(() => schedulesApi.reverseAccrual(r.id), acc.reload)}>{tx('Reverse now')}</Button> : null },
        ]} />
      </>)}
      {dialog === 'recurring' && <RecurrenceDialog onClose={() => setDialog(null)} onSaved={rec.reload} />}
      {dialog === 'accrual' && <AccrualDialog onClose={() => setDialog(null)} onSaved={acc.reload} />}
    </PageShell>
  );
}

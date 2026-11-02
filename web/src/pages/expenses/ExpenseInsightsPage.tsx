// EX-6 (ADR-S052): expense analysis — spend by dimension, monthly trend, pipeline, reimbursements, advances,
// petty cash, schedules and capital purchases.
import { useState } from 'react';
import { Input } from '@/components/ui/input';
import { expenseReportsApi, fmt } from '@/lib/api/expenseExtras';
import { tx } from '@/lib/i18n/tx';
import { DataTable, ErrorBox, fieldLabel, PageShell, selectCls, Spinner, Tabs, useLoad } from './expense-ui';

const GROUPS: [string, string][] = [
  ['category', 'Category'], ['branch', 'Branch'], ['department', 'Department'], ['costCenter', 'Cost centre'],
  ['project', 'Project'], ['supplier', 'Supplier'], ['claimant', 'Employee'], ['paidBy', 'Paid by'], ['month', 'Month'],
];

const STAGES: [string, string][] = [
  ['pendingApproval', 'Awaiting approval'], ['returned', 'Returned'], ['pendingPayment', 'Awaiting payment'],
  ['partiallyPaid', 'Part paid'], ['paid', 'Paid'],
];

function Spend() {
  const [groupBy, setGroupBy] = useState('category');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const sum = useLoad(() => expenseReportsApi.summary(groupBy, from || undefined, to || undefined), [groupBy, from, to]);
  const pipe = useLoad(() => expenseReportsApi.pipeline(from || undefined, to || undefined), [from, to]);
  const max = Math.max(1, ...((sum.data?.rows ?? []).map((r: any) => Number(r.amount) || 0)));
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2 items-end">
        <div>
          <label className={fieldLabel}>{tx('Group by')}</label>
          <select className={selectCls} value={groupBy} onChange={(e) => setGroupBy(e.target.value)}>
            {GROUPS.map(([k, l]) => <option key={k} value={k}>{tx(l)}</option>)}
          </select>
        </div>
        <div><label className={fieldLabel}>{tx('From')}</label><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
        <div><label className={fieldLabel}>{tx('To')}</label><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
      </div>
      {pipe.data && (
        <div className="grid grid-cols-2 md:grid-cols-5 gap-2">
          {STAGES.map(([k, l]) => (
            <div key={k} className="bg-white border rounded p-3">
              <div className="text-xs text-slate-500">{tx(l)}</div>
              <div className="text-lg font-bold tabular-nums">{fmt(pipe.data![k]?.amount)}</div>
              <div className="text-xs text-slate-400">{pipe.data![k]?.count} · {tx('open')} {fmt(pipe.data![k]?.outstanding)}</div>
            </div>
          ))}
        </div>
      )}
      <ErrorBox error={sum.error} />
      {sum.loading ? <Spinner /> : <DataTable rows={sum.data?.rows} columns={[
        { key: 'label', label: tx(GROUPS.find(([k]) => k === groupBy)?.[1] ?? '') },
        { key: 'bar', label: '', render: (r) => <div className="h-2 bg-[#3c8dbc]/70 rounded" style={{ width: `${(Number(r.amount) / max) * 100}%`, minWidth: 2 }} /> },
        { key: 'count', label: tx('Count'), right: true },
        { key: 'netAmount', label: tx('Net'), right: true, render: (r) => fmt(r.netAmount) },
        { key: 'taxAmount', label: tx('Tax'), right: true, render: (r) => fmt(r.taxAmount) },
        { key: 'amount', label: tx('Total'), right: true, render: (r) => fmt(r.amount) },
        { key: 'outstanding', label: tx('Unpaid'), right: true, render: (r) => fmt(r.outstanding) },
      ]} />}
      {sum.data && <p className="text-right text-sm">{tx('Total')}: <b>{fmt(sum.data.total)}</b></p>}
    </div>
  );
}

function Trend() {
  const t = useLoad(() => expenseReportsApi.trend(12), []);
  const rows = t.data?.months ?? [];
  const max = Math.max(1, ...rows.map((r: any) => Number(r.amount) || 0));
  return t.loading ? <Spinner /> : (
    <div className="bg-white border rounded p-4">
      <div className="flex items-end gap-2 h-48">
        {rows.map((r: any) => (
          <div key={r.month} className="flex-1 flex flex-col items-center justify-end h-full" title={`${r.month}: ${fmt(r.amount)}`}>
            <div className="w-full bg-[#3c8dbc]/70 rounded-t" style={{ height: `${(Number(r.amount) / max) * 100}%`, minHeight: 1 }} />
            <span className="text-[10px] text-slate-500 mt-1">{r.month.slice(2)}</span>
          </div>
        ))}
      </div>
      <DataTable rows={rows} columns={[
        { key: 'month', label: tx('Month') }, { key: 'count', label: tx('Count'), right: true },
        { key: 'amount', label: tx('Spend'), right: true, render: (r) => fmt(r.amount) },
        { key: 'paid', label: tx('Paid'), right: true, render: (r) => fmt(r.paid) },
      ]} />
    </div>
  );
}

function Balances() {
  const reimb = useLoad(() => expenseReportsApi.reimbursements(), []);
  const adv = useLoad(() => expenseReportsApi.advances(), []);
  const pc = useLoad(() => expenseReportsApi.pettyCash(), []);
  const sch = useLoad(() => expenseReportsApi.schedules(), []);
  const cap = useLoad(() => expenseReportsApi.capitalPurchases(), []);
  return (
    <div className="space-y-4">
      <h3 className="font-semibold">{tx('Owed to employees (reimbursements)')}</h3>
      <DataTable rows={reimb.data} columns={[
        { key: 'name', label: tx('Employee') }, { key: 'count', label: tx('Expenses'), right: true },
        { key: 'outstanding', label: tx('Owed'), right: true, render: (r) => fmt(r.outstanding) },
        { key: 'ageDays', label: tx('Oldest (days)'), right: true },
      ]} />
      <h3 className="font-semibold">{tx('Advances outstanding')} {adv.data && <span className="text-slate-500 font-normal">· {fmt(adv.data.total)}</span>}</h3>
      <DataTable rows={adv.data?.rows} columns={[
        { key: 'advanceNumber', label: tx('Advance') }, { key: 'name', label: tx('Employee') }, { key: 'purpose', label: tx('Purpose') },
        { key: 'outstanding', label: tx('Outstanding'), right: true, render: (r) => fmt(r.outstanding) },
        { key: 'ageDays', label: tx('Age (days)'), right: true },
      ]} />
      <h3 className="font-semibold">{tx('Petty cash')}</h3>
      <DataTable rows={pc.data} columns={[
        { key: 'name', label: tx('Fund') }, { key: 'balance', label: tx('Balance'), right: true, render: (r) => fmt(r.balance) },
        { key: 'floatAmount', label: tx('Float'), right: true, render: (r) => fmt(r.floatAmount) },
        { key: 'overShortThisYear', label: tx('Over / short (year)'), right: true, render: (r) => fmt(r.overShortThisYear) },
      ]} />
      {sch.data && (
        <div className="grid grid-cols-2 gap-2">
          <div className="bg-white border rounded p-3"><div className="text-xs text-slate-500">{tx('Prepaid still to release')}</div><b>{fmt(sch.data.prepaidRemaining)}</b></div>
          <div className="bg-white border rounded p-3"><div className="text-xs text-slate-500">{tx('Open accruals')}</div><b>{fmt(sch.data.openAccruals)}</b></div>
        </div>
      )}
      <h3 className="font-semibold">{tx('Recurring expenses due in 30 days')}</h3>
      <DataTable rows={sch.data?.upcomingRecurring} columns={[
        { key: 'name', label: tx('Name') }, { key: 'nextRunDate', label: tx('Next run'), render: (r) => String(r.nextRunDate).split('T')[0] },
        { key: 'amount', label: tx('Amount'), right: true, render: (r) => fmt(r.amount) },
      ]} />
      <h3 className="font-semibold">{tx('Capital purchases')}</h3>
      <DataTable rows={cap.data} columns={[
        { key: 'expenseCode', label: tx('Expense') }, { key: 'title', label: tx('Title') }, { key: 'categoryName', label: tx('Category') },
        { key: 'amount', label: tx('Amount'), right: true, render: (r) => fmt(r.amount) },
        { key: 'paymentStatus', label: tx('Payment') },
      ]} />
    </div>
  );
}

export default function ExpenseInsightsPage() {
  const [tab, setTab] = useState('spend');
  return (
    <PageShell title={tx('Expense analysis')} subtitle={tx('Spend by dimension, trend and open balances')}>
      <Tabs tabs={[['spend', tx('Spend')], ['trend', tx('Trend')], ['balances', tx('Open balances')]]} value={tab} onChange={setTab} />
      {tab === 'spend' && <Spend />}
      {tab === 'trend' && <Trend />}
      {tab === 'balances' && <Balances />}
    </PageShell>
  );
}

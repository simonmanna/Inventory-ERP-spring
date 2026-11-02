// EX-1 fields of the expense form (ADR-S052): dimensions, tax, receipt, currency, paid-by, prepaid, capitalise and
// percentage allocation lines. Kept out of ExpensesPage so the main form stays readable.
import { useEffect, useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { lookupsApi, type Row } from '@/lib/api/expenseExtras';
import { tx } from '@/lib/i18n/tx';

export interface ExtraAllocation {
  percent: string;
  costCenterId: string;
  branchId: string;
}

export interface ExpenseExtra {
  branchId: string;
  costCenterId: string;
  projectId: string;
  receiptNumber: string;
  dueDate: string;
  paidBy: 'COMPANY' | 'EMPLOYEE';
  taxId: string;
  taxAmount: string;
  currencyCode: string;
  foreignAmount: string;
  exchangeRate: string;
  prepaidMonths: string;
  prepaidStart: string;
  capitalize: boolean;
  allocations: ExtraAllocation[];
}

export function extraFromExpense(e?: Row | null): ExpenseExtra {
  return {
    branchId: e?.branchId ?? '',
    costCenterId: e?.costCenterId ?? '',
    projectId: e?.projectId ?? '',
    receiptNumber: e?.receiptNumber ?? '',
    dueDate: e?.dueDate ? String(e.dueDate).split('T')[0] : '',
    paidBy: e?.paidBy === 'EMPLOYEE' ? 'EMPLOYEE' : 'COMPANY',
    taxId: e?.taxId ?? '',
    taxAmount: e?.taxAmount ? String(e.taxAmount) : '',
    currencyCode: e?.currencyCode ?? '',
    foreignAmount: e?.foreignAmount != null ? String(e.foreignAmount) : '',
    exchangeRate: e?.exchangeRate != null ? String(e.exchangeRate) : '',
    prepaidMonths: e?.prepaidMonths != null ? String(e.prepaidMonths) : '',
    prepaidStart: e?.prepaidStart ? String(e.prepaidStart).split('T')[0] : '',
    capitalize: !!e?.capitalize,
    allocations: (e?.allocations ?? []).map((a: Row) => ({
      percent: a.percent != null ? String(a.percent) : '',
      costCenterId: a.costCenterId ?? '',
      branchId: a.branchId ?? '',
    })),
  };
}

/** Request fields for create / update; empty strings clear on update, are omitted on create. */
export function extraToBody(x: ExpenseExtra, isEdit: boolean): Row {
  const s = (v: string) => (v.trim() ? v.trim() : isEdit ? '' : undefined);
  const n = (v: string) => (v.trim() ? Number(v) : undefined);
  const body: Row = {
    branchId: s(x.branchId),
    costCenterId: s(x.costCenterId),
    projectId: s(x.projectId),
    receiptNumber: s(x.receiptNumber),
    dueDate: x.dueDate || undefined,
    paidBy: x.paidBy,
    taxId: s(x.taxId),
    taxAmount: n(x.taxAmount),
    currencyCode: s(x.currencyCode),
    foreignAmount: n(x.foreignAmount),
    exchangeRate: n(x.exchangeRate),
    prepaidMonths: n(x.prepaidMonths),
    prepaidStart: x.prepaidStart || undefined,
    capitalize: x.capitalize || undefined,
  };
  const lines = x.allocations.filter((a) => a.percent.trim());
  if (lines.length || isEdit) {
    body.allocations = lines.map((a) => ({
      percent: Number(a.percent),
      costCenterId: a.costCenterId || undefined,
      branchId: a.branchId || undefined,
    }));
  }
  Object.keys(body).forEach((k) => body[k] === undefined && delete body[k]);
  return body;
}

const label = 'text-xs font-bold uppercase text-gray-500';
const select = 'w-full h-9 rounded-none border border-gray-300 bg-white px-2 text-sm';

export function ExpenseExtraFields({ value, onChange }: { value: ExpenseExtra; onChange: (v: ExpenseExtra) => void }) {
  const [branches, setBranches] = useState<Row[]>([]);
  const [centers, setCenters] = useState<Row[]>([]);
  const [taxes, setTaxes] = useState<Row[]>([]);
  const [open, setOpen] = useState(
    () => !!(value.branchId || value.costCenterId || value.taxId || value.allocations.length || value.prepaidMonths),
  );

  useEffect(() => {
    lookupsApi.branches().then(setBranches);
    lookupsApi.costCenters().then(setCenters);
    lookupsApi.taxes().then(setTaxes);
  }, []);

  const set = (patch: Partial<ExpenseExtra>) => onChange({ ...value, ...patch });
  const setLine = (i: number, patch: Partial<ExtraAllocation>) =>
    set({ allocations: value.allocations.map((a, j) => (j === i ? { ...a, ...patch } : a)) });
  const pctTotal = value.allocations.reduce((s, a) => s + (Number(a.percent) || 0), 0);

  return (
    <div className="bg-white rounded border shadow-sm overflow-hidden">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="w-full text-left bg-gray-50 px-4 py-2 border-b text-sm font-bold text-gray-700 uppercase tracking-wider"
      >
        {tx('Accounting, tax & controls')} {open ? '▾' : '▸'}
      </button>
      {open && (
        <div className="p-4 grid grid-cols-1 sm:grid-cols-3 gap-3">
          <div>
            <label className={label}>{tx('Branch')}</label>
            <select className={select} value={value.branchId} onChange={(e) => set({ branchId: e.target.value })}>
              <option value="">{tx('—')}</option>
              {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </div>
          <div>
            <label className={label}>{tx('Cost centre')}</label>
            <select className={select} value={value.costCenterId} onChange={(e) => set({ costCenterId: e.target.value })}>
              <option value="">{tx('Category default')}</option>
              {centers.map((c) => <option key={c.id} value={c.id}>{c.code} · {c.name}</option>)}
            </select>
          </div>
          <div>
            <label className={label}>{tx('Receipt / invoice no.')}</label>
            <Input className="rounded-none" value={value.receiptNumber} onChange={(e) => set({ receiptNumber: e.target.value })} />
          </div>
          <div>
            <label className={label}>{tx('Tax')}</label>
            <select className={select} value={value.taxId} onChange={(e) => set({ taxId: e.target.value, taxAmount: '' })}>
              <option value="">{tx('Category default / none')}</option>
              {taxes.map((t) => <option key={t.id} value={t.id}>{t.name} ({Number(t.rate)}%)</option>)}
            </select>
          </div>
          <div>
            <label className={label}>{tx('Tax amount (incl. in amount)')}</label>
            <Input type="number" min={0} className="rounded-none" placeholder={tx('auto from tax rate')}
              value={value.taxAmount} onChange={(e) => set({ taxAmount: e.target.value })} />
          </div>
          <div>
            <label className={label}>{tx('Due date')}</label>
            <Input type="date" className="rounded-none" value={value.dueDate} onChange={(e) => set({ dueDate: e.target.value })} />
          </div>
          <div>
            <label className={label}>{tx('Paid by')}</label>
            <select className={select} value={value.paidBy}
              onChange={(e) => set({ paidBy: e.target.value as ExpenseExtra['paidBy'] })}>
              <option value="COMPANY">{tx('Company')}</option>
              <option value="EMPLOYEE">{tx('Employee (reimburse later)')}</option>
            </select>
          </div>
          <div>
            <label className={label}>{tx('Currency / foreign amount / rate')}</label>
            <div className="flex gap-1">
              <Input className="rounded-none w-16" placeholder="USD" value={value.currencyCode}
                onChange={(e) => set({ currencyCode: e.target.value.toUpperCase() })} />
              <Input type="number" className="rounded-none" placeholder={tx('amount')} value={value.foreignAmount}
                onChange={(e) => set({ foreignAmount: e.target.value })} />
              <Input type="number" className="rounded-none" placeholder={tx('rate')} value={value.exchangeRate}
                onChange={(e) => set({ exchangeRate: e.target.value })} />
            </div>
          </div>
          <div>
            <label className={label}>{tx('Prepaid over (months) / from')}</label>
            <div className="flex gap-1">
              <Input type="number" min={1} max={120} className="rounded-none w-20" value={value.prepaidMonths}
                onChange={(e) => set({ prepaidMonths: e.target.value })} />
              <Input type="date" className="rounded-none" value={value.prepaidStart}
                onChange={(e) => set({ prepaidStart: e.target.value })} />
            </div>
          </div>
          <label className="flex items-center gap-2 text-sm sm:col-span-3">
            <input type="checkbox" checked={value.capitalize} onChange={(e) => set({ capitalize: e.target.checked })} />
            {tx('Capital purchase — debit the category asset account (category must allow capitalisation)')}
          </label>
          <div className="sm:col-span-3">
            <div className="flex items-center justify-between">
              <span className={label}>{tx('Split across cost centres (%)')}</span>
              <button type="button" className="text-xs text-[#3c8dbc] flex items-center gap-1"
                onClick={() => set({ allocations: [...value.allocations, { percent: '', costCenterId: '', branchId: '' }] })}>
                <Plus className="w-3 h-3" /> {tx('Add line')}
              </button>
            </div>
            {value.allocations.map((a, i) => (
              <div key={i} className="flex gap-2 mt-1">
                <Input type="number" min={0} max={100} className="rounded-none w-24" placeholder="%" value={a.percent}
                  onChange={(e) => setLine(i, { percent: e.target.value })} />
                <select className={select} value={a.costCenterId} onChange={(e) => setLine(i, { costCenterId: e.target.value })}>
                  <option value="">{tx('Cost centre…')}</option>
                  {centers.map((c) => <option key={c.id} value={c.id}>{c.code} · {c.name}</option>)}
                </select>
                <select className={select} value={a.branchId} onChange={(e) => setLine(i, { branchId: e.target.value })}>
                  <option value="">{tx('Branch…')}</option>
                  {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
                </select>
                <button type="button" onClick={() => set({ allocations: value.allocations.filter((_, j) => j !== i) })}
                  className="text-slate-400 hover:text-red-600"><Trash2 className="w-4 h-4" /></button>
              </div>
            ))}
            {value.allocations.length > 0 && (
              <p className={`text-[11px] mt-1 ${Math.abs(pctTotal - 100) < 0.001 ? 'text-emerald-600' : 'text-amber-600'}`}>
                {tx('Total {{p}}% (must be 100%)', { p: pctTotal })}
              </p>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

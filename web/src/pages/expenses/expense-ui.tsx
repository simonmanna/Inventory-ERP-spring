// Small shared pieces for the ADR-S052 expense screens (claims, advances, petty cash, schedules, controls).
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ChevronLeft, Loader2 } from 'lucide-react';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { errMsg, lookupsApi, type MoneyBody, type Row } from '@/lib/api/expenseExtras';
import { tx } from '@/lib/i18n/tx';

export const fieldLabel = 'block text-xs font-bold uppercase text-gray-500 mb-1';
export const selectCls = 'w-full h-9 rounded-md border border-input bg-white px-2 text-sm';

/** Load data with a reload function; errors land in {@code error}. */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const reload = useCallback(() => {
    setLoading(true);
    setError('');
    fn()
      .then(setData)
      .catch((e) => setError(errMsg(e)))
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => reload(), [reload]);
  return { data, loading, error, reload };
}

export function PageShell({ title, subtitle, actions, children }: {
  title: string; subtitle?: string; actions?: ReactNode; children: ReactNode;
}) {
  return (
    <div className="p-4 md:p-6 space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <Link to="/expenses" className="text-xs text-slate-500 flex items-center gap-1 hover:text-slate-700">
            <ChevronLeft className="w-3 h-3" /> {tx('Expenses')}
          </Link>
          <h1 className="text-xl font-bold text-slate-800">{title}</h1>
          {subtitle && <p className="text-sm text-slate-500">{subtitle}</p>}
        </div>
        <div className="flex gap-2">{actions}</div>
      </div>
      {children}
    </div>
  );
}

export function Tabs({ tabs, value, onChange }: { tabs: [string, string][]; value: string; onChange: (v: string) => void }) {
  return (
    <div className="flex gap-1 border-b">
      {tabs.map(([k, label]) => (
        <button key={k} onClick={() => onChange(k)}
          className={`px-3 py-2 text-sm font-medium border-b-2 -mb-px ${value === k
            ? 'border-[#3c8dbc] text-[#3c8dbc]' : 'border-transparent text-slate-500 hover:text-slate-700'}`}>
          {label}
        </button>
      ))}
    </div>
  );
}

export function DataTable({ columns, rows, empty, onRow }: {
  columns: { key: string; label: string; render?: (r: Row) => ReactNode; right?: boolean }[];
  rows: Row[] | null | undefined; empty?: string; onRow?: (r: Row) => void;
}) {
  return (
    <div className="bg-white rounded border overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="bg-slate-50 text-xs uppercase text-slate-500">
          <tr>{columns.map((c) => <th key={c.key} className={`px-3 py-2 ${c.right ? 'text-right' : 'text-left'}`}>{c.label}</th>)}</tr>
        </thead>
        <tbody>
          {(rows ?? []).map((r, i) => (
            <tr key={r.id ?? i} onClick={onRow ? () => onRow(r) : undefined}
              className={`border-t ${onRow ? 'cursor-pointer hover:bg-slate-50' : ''}`}>
              {columns.map((c) => (
                <td key={c.key} className={`px-3 py-2 ${c.right ? 'text-right tabular-nums' : ''}`}>
                  {c.render ? c.render(r) : (r[c.key] ?? '—')}
                </td>
              ))}
            </tr>
          ))}
          {(!rows || rows.length === 0) && (
            <tr><td colSpan={columns.length} className="px-3 py-6 text-center text-slate-400">{empty ?? tx('Nothing here yet')}</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

const PILL: Record<string, string> = {
  draft: 'bg-slate-100 text-slate-600', requested: 'bg-slate-100 text-slate-600', submitted: 'bg-blue-50 text-blue-700',
  approved: 'bg-blue-50 text-blue-700', disbursed: 'bg-indigo-50 text-indigo-700', partially_paid: 'bg-amber-50 text-amber-700',
  returned: 'bg-amber-50 text-amber-700', paid: 'bg-emerald-50 text-emerald-700', settled: 'bg-emerald-50 text-emerald-700',
  completed: 'bg-emerald-50 text-emerald-700', active: 'bg-emerald-50 text-emerald-700', posted: 'bg-indigo-50 text-indigo-700',
  reversed: 'bg-slate-100 text-slate-600', paused: 'bg-amber-50 text-amber-700', rejected: 'bg-red-50 text-red-700',
  cancelled: 'bg-slate-100 text-slate-500', ended: 'bg-slate-100 text-slate-500',
};

export function Pill({ status }: { status?: string }) {
  const s = String(status ?? '').toLowerCase();
  return <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${PILL[s] ?? 'bg-slate-100 text-slate-600'}`}>{s.replace('_', ' ')}</span>;
}

export function ErrorBox({ error }: { error?: string }) {
  return error ? <div className="p-3 bg-red-50 border border-red-200 rounded text-sm text-red-700">{error}</div> : null;
}

export function Spinner() {
  return <div className="p-8 flex justify-center"><Loader2 className="w-5 h-5 animate-spin text-slate-400" /></div>;
}

/** Form dialog: renders children, runs {@code onSubmit}, shows its error. */
export function FormDialog({ title, onClose, onSubmit, children, submitLabel }: {
  title: string; onClose: () => void; onSubmit: () => Promise<unknown>; children: ReactNode; submitLabel?: string;
}) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const go = async () => {
    setSaving(true);
    setError('');
    try {
      await onSubmit();
      onClose();
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setSaving(false);
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader><DialogTitle>{title}</DialogTitle></DialogHeader>
        <div className="space-y-3 max-h-[65vh] overflow-y-auto">
          <ErrorBox error={error} />
          {children}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={go} disabled={saving}>{saving && <Loader2 className="w-4 h-4 mr-1 animate-spin" />}{submitLabel ?? tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export const PAYMENT_METHODS: [string, string][] = [
  ['CASH', 'Cash'], ['BANK_TRANSFER', 'Bank transfer'], ['MTN_MOBILE_MONEY', 'MTN Mobile Money'],
  ['AIRTEL_MONEY', 'Airtel Money'], ['CHEQUE', 'Cheque'],
];

/** Payment method + cash account + optional amount; used for reimbursements, disbursements and refunds. */
export function MoneyDialog({ title, maxAmount, onClose, onSubmit }: {
  title: string; maxAmount?: number; onClose: () => void; onSubmit: (b: MoneyBody) => Promise<unknown>;
}) {
  const [accounts, setAccounts] = useState<Row[]>([]);
  const [body, setBody] = useState({ paymentMethod: 'CASH', accountId: '', reference: '', amount: '' });
  useEffect(() => {
    lookupsApi.cashAccounts().then((a) => {
      setAccounts(a);
      setBody((b) => ({ ...b, accountId: b.accountId || a[0]?.id || '' }));
    });
  }, []);
  return (
    <FormDialog title={title} onClose={onClose} submitLabel={tx('Post')} onSubmit={() => onSubmit({
      paymentMethod: body.paymentMethod, accountId: body.accountId, reference: body.reference || undefined,
      amount: body.amount ? Number(body.amount) : undefined,
    })}>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className={fieldLabel}>{tx('Method')}</label>
          <select className={selectCls} value={body.paymentMethod} onChange={(e) => setBody({ ...body, paymentMethod: e.target.value })}>
            {PAYMENT_METHODS.map(([k, l]) => <option key={k} value={k}>{tx(l)}</option>)}
          </select>
        </div>
        <div>
          <label className={fieldLabel}>{tx('Account')}</label>
          <select className={selectCls} value={body.accountId} onChange={(e) => setBody({ ...body, accountId: e.target.value })}>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </div>
        <div>
          <label className={fieldLabel}>{tx('Amount')}{maxAmount != null ? ` (≤ ${maxAmount})` : ''}</label>
          <Input type="number" min={0} placeholder={tx('Full amount')} value={body.amount}
            onChange={(e) => setBody({ ...body, amount: e.target.value })} />
        </div>
        <div>
          <label className={fieldLabel}>{tx('Reference')}</label>
          <Input value={body.reference} onChange={(e) => setBody({ ...body, reference: e.target.value })} />
        </div>
      </div>
    </FormDialog>
  );
}

/** Run an action, then reload; alerts the API message on failure. */
export async function act(fn: () => Promise<unknown>, reload: () => void) {
  try {
    await fn();
    reload();
  } catch (e) {
    alert(errMsg(e));
  }
}

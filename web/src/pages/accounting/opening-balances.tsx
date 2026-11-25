import { useMemo, useState } from 'react';
import { Plus, RotateCcw, Trash2, Upload } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { isPostingAccount, useAccounts, useCurrencies } from '@/features/accounting/api';
import { PERMISSIONS } from '@erp/shared';
import { useAuthStore } from '@/stores/auth.store';
import { useOpeningBalances, usePostOpeningBalances, useReverseOpeningBalances, localDay } from '@/features/finance/api';
import { usePartners } from '@/features/partners/api';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

/** FX-2: `currencyCode` '' = functional; a foreign line is entered in its currency at `exchangeRate` (blank = rate table). */
type Fx = { currencyCode: string; exchangeRate: string };
type AccountRow = { accountId: string; debit: string; credit: string } & Fx;
type PartnerRow = { partnerId: string; amount: string; dueDate: string; reference: string } & Fx;

const emptyAccount = (): AccountRow => ({ accountId: '', debit: '', credit: '', currencyCode: '', exchangeRate: '' });
const emptyPartner = (): PartnerRow => ({ partnerId: '', amount: '', dueDate: '', reference: '', currencyCode: '', exchangeRate: '' });

/** The body's currency fields: only sent for a foreign line. */
const fxBody = (r: Fx) =>
  r.currencyCode
    ? { currencyCode: r.currencyCode, exchangeRate: Number(r.exchangeRate) > 0 ? Number(r.exchangeRate) : undefined }
    : {};

/** Functional value of a line amount: the amount itself, amount × rate for a foreign line, null when the rate is unknown. */
const functionalValue = (amount: number, r: Fx): number | null =>
  !r.currencyCode ? amount : Number(r.exchangeRate) > 0 ? amount * Number(r.exchangeRate) : null;

function CurrencyCells({ row, onChange, codes, base }: { row: Fx; onChange: (p: Partial<Fx>) => void; codes: string[]; base: string }) {
  return (
    <>
      <select
        className="h-9 rounded-md border border-input bg-background px-2 text-sm"
        value={row.currencyCode}
        onChange={(e) => onChange({ currencyCode: e.target.value, exchangeRate: '' })}
        aria-label={tx('Currency')}
      >
        <option value="">{base || tx('Functional')}</option>
        {codes.map((c) => <option key={c} value={c}>{c}</option>)}
      </select>
      <Input
        type="number"
        min={0}
        step="any"
        disabled={!row.currencyCode}
        placeholder={row.currencyCode ? tx('rate table') : '—'}
        value={row.exchangeRate}
        onChange={(e) => onChange({ exchangeRate: e.target.value })}
        aria-label={tx('Exchange rate')}
      />
    </>
  );
}

function FunctionalHint({ amount, row, base }: { amount: number; row: Fx; base: string }) {
  if (!row.currencyCode || !(amount > 0)) return null;
  const v = functionalValue(amount, row);
  return (
    <p className="col-span-full -mt-1 text-xs text-muted-foreground">
      {v == null
        ? tx('In {{v1}}; valued at the rate table on the opening date.', { v1: row.currencyCode })
        : tx('In {{v1}} = {{v2}} {{v3}}', { v1: row.currencyCode, v2: money(v), v3: base })}
    </p>
  );
}

export function OpeningBalancesPage() {
  const [day, setDay] = useState(localDay());
  const [reference, setReference] = useState('');
  const [accounts, setAccounts] = useState<AccountRow[]>([emptyAccount()]);
  const [customers, setCustomers] = useState<PartnerRow[]>([]);
  const [suppliers, setSuppliers] = useState<PartnerRow[]>([]);
  const { data: accountList } = useAccounts();
  const { data: partners } = usePartners({ page: 1, pageSize: 1000 });
  const { data: history } = useOpeningBalances();
  const post = usePostOpeningBalances();
  const reverse = useReverseOpeningBalances();
  const has = useAuthStore((st) => st.hasPermission);
  // B2: same permissions as POST /opening-balances/{batchId}/reverse
  const canReverse = has(PERMISSIONS.openingBalance.post) && has(PERMISSIONS.journalEntry.reverse);
  const batches = useMemo(() => {
    const m = new Map<string, { batchId: string; date: string; label: string; items: number; open: number }>();
    const touch = (batchId: string, day: string, label: string, open: boolean) => {
      const b = m.get(batchId) ?? { batchId, date: day, label, items: 0, open: 0 };
      b.items += 1;
      if (open) b.open += 1;
      if (label && !b.label) b.label = label;
      m.set(batchId, b);
    };
    (history?.entries ?? []).forEach((e) => touch(e.batchId, e.postingDate, e.description, e.status === 'posted'));
    (history?.documents ?? []).forEach((d) => touch(d.batchId, d.issueDate, '', d.status !== 'cancelled'));
    return [...m.values()];
  }, [history]);

  function reverseBatch(batchId: string) {
    const reason = window.prompt(
      tx('Reverse this whole opening-balance batch? Its invoices are cancelled, its bills voided and its journal entry reversed. Reason:'),
    );
    if (!reason || !reason.trim()) return;
    reverse.mutate({ batchId, reason: reason.trim() });
  }
  const base = useAuthStore((s) => s.organization?.currencyCode ?? '');
  const { data: currencies } = useCurrencies();
  const codes = useMemo(() => (currencies ?? []).map((c) => c.code).filter((c) => c !== base), [currencies, base]);

  const accountOptions = useMemo(
    () =>
      (accountList?.data ?? [])
        // AR / AP control accounts are refused here (use the customer / supplier sections).
        .filter((a) => isPostingAccount(a) && a.controlAccountType !== 'ar' && a.controlAccountType !== 'ap')
        .map((a) => ({ value: a.id, label: `${a.code} · ${a.name}`, code: a.code })),
    [accountList],
  );
  const customerOptions = useMemo(
    () => (partners?.data ?? []).filter((p) => p.isCustomer).map((p) => ({ value: p.id, label: p.name, code: p.code })),
    [partners],
  );
  const supplierOptions = useMemo(
    () => (partners?.data ?? []).filter((p) => p.isSupplier).map((p) => ({ value: p.id, label: p.name, code: p.code })),
    [partners],
  );

  // Totals in the functional currency; a foreign line without a rate is valued by the server (rate table).
  const debit = accounts.reduce((s, a) => s + (functionalValue(Number(a.debit || 0), a) ?? 0), 0);
  const credit = accounts.reduce((s, a) => s + (functionalValue(Number(a.credit || 0), a) ?? 0), 0);
  const diff = debit - credit;
  const unvalued = accounts.some((a) => a.currencyCode && !(Number(a.exchangeRate) > 0) && (Number(a.debit) > 0 || Number(a.credit) > 0));

  const submit = () => {
    const body = {
      date: day,
      reference: reference || undefined,
      accounts: accounts
        .filter((a) => a.accountId && (Number(a.debit) > 0 || Number(a.credit) > 0))
        .map((a) => ({
          accountId: a.accountId,
          debit: Number(a.debit) > 0 ? Number(a.debit) : undefined,
          credit: Number(a.credit) > 0 ? Number(a.credit) : undefined,
          ...fxBody(a),
        })),
      customers: customers
        .filter((c) => c.partnerId && Number(c.amount) > 0)
        .map((c) => ({ partnerId: c.partnerId, amount: Number(c.amount), dueDate: c.dueDate || undefined, reference: c.reference || undefined, ...fxBody(c) })),
      suppliers: suppliers
        .filter((c) => c.partnerId && Number(c.amount) > 0)
        .map((c) => ({ partnerId: c.partnerId, amount: Number(c.amount), dueDate: c.dueDate || undefined, reference: c.reference || undefined, ...fxBody(c) })),
    };
    if (!confirm(tx('Post these opening balances? This creates posted journal entries, invoices and bills.'))) return;
    post.mutate(body, {
      onSuccess: () => {
        setAccounts([emptyAccount()]);
        setCustomers([]);
        setSuppliers([]);
      },
    });
  };

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Opening Balances')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx(
            'Bring in balances from your previous system. Account lines post as one journal entry; any difference goes to opening-balance equity (retained earnings). Customer and supplier balances become open invoices and bills.',
          )}
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-3 rounded-lg border p-3">
        <div className="space-y-1">
          <Label>{tx('Opening date')}</Label>
          <Input type="date" value={day} onChange={(e) => setDay(e.target.value)} className="h-9" />
        </div>
        <div className="min-w-[260px] space-y-1">
          <Label>{tx('Reference')}</Label>
          <Input value={reference} onChange={(e) => setReference(e.target.value)} placeholder={tx('e.g. Migration from old system')} />
        </div>
      </div>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">{tx('Account balances')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {accounts.map((a, i) => (
            <div key={i} className="grid grid-cols-[1fr_140px_140px_100px_120px_40px] gap-2">
              <SearchableSelect
                value={a.accountId}
                onValueChange={(v) => setAccounts((rs) => rs.map((r, j) => (j === i ? { ...r, accountId: v } : r)))}
                options={accountOptions}
                placeholder={tx('Account…')}
              />
              <Input type="number" min={0} placeholder={tx('Debit')} value={a.debit}
                onChange={(e) => setAccounts((rs) => rs.map((r, j) => (j === i ? { ...r, debit: e.target.value, credit: '' } : r)))} />
              <Input type="number" min={0} placeholder={tx('Credit')} value={a.credit}
                onChange={(e) => setAccounts((rs) => rs.map((r, j) => (j === i ? { ...r, credit: e.target.value, debit: '' } : r)))} />
              <CurrencyCells row={a} codes={codes} base={base}
                onChange={(p) => setAccounts((rs) => rs.map((r, j) => (j === i ? { ...r, ...p } : r)))} />
              <Button size="icon" variant="ghost" onClick={() => setAccounts((rs) => rs.filter((_, j) => j !== i))}>
                <Trash2 className="h-4 w-4" />
              </Button>
              <FunctionalHint amount={Number(a.debit || a.credit || 0)} row={a} base={base} />
            </div>
          ))}
          <div className="flex items-center justify-between">
            <Button variant="outline" size="sm" onClick={() => setAccounts((rs) => [...rs, emptyAccount()])}>
              <Plus className="mr-1 h-4 w-4" />
              {tx('Add line')}
            </Button>
            <div className="text-sm">
              {tx('Debit')} {money(debit)} · {tx('Credit')} {money(credit)} ·{' '}
              <span className={diff === 0 ? 'text-emerald-600' : 'text-amber-600'}>
                {diff === 0 ? tx('Balanced') : `${tx('Equity adjustment')} ${money(Math.abs(diff))}`}
              </span>
              {unvalued && <span className="text-muted-foreground"> · {tx('foreign lines without a rate are valued on posting')}</span>}
            </div>
          </div>
        </CardContent>
      </Card>

      <PartnerSection title={tx('Customer balances (receivable)')} rows={customers} setRows={setCustomers} options={customerOptions} codes={codes} base={base} />
      <PartnerSection title={tx('Supplier balances (payable)')} rows={suppliers} setRows={setSuppliers} options={supplierOptions} codes={codes} base={base} />

      <div className="flex justify-end">
        <Button onClick={submit} disabled={post.isPending}>
          <Upload className="mr-2 h-4 w-4" />
          {tx('Post opening balances')}
        </Button>
      </div>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">{tx('Posted so far')}</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Date')}</TableHead>
                <TableHead>{tx('Document')}</TableHead>
                <TableHead>{tx('Partner')}</TableHead>
                <TableHead className="text-right">{tx('Amount')}</TableHead>
                <TableHead className="text-right">{tx('Open')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(history?.entries ?? []).map((e) => (
                <TableRow key={e.id}>
                  <TableCell>{date(e.postingDate)}</TableCell>
                  <TableCell>{e.entryNumber}</TableCell>
                  <TableCell>{tx('General ledger')}</TableCell>
                  <TableCell className="text-right">{money(e.total)}</TableCell>
                  <TableCell />
                </TableRow>
              ))}
              {(history?.documents ?? []).map((d) => (
                <TableRow key={d.id}>
                  <TableCell>{date(d.issueDate)}</TableCell>
                  <TableCell>{d.documentNumber}</TableCell>
                  <TableCell>{d.partnerName}</TableCell>
                  <TableCell className="text-right">{money(d.totalAmount)}</TableCell>
                  <TableCell className="text-right">{money(d.amountResidual)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {batches.length > 0 && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base">{tx('Batches')}</CardTitle>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Date')}</TableHead>
                  <TableHead>{tx('Description')}</TableHead>
                  <TableHead className="text-right">{tx('Items')}</TableHead>
                  <TableHead>{tx('Status')}</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {batches.map((b) => (
                  <TableRow key={b.batchId}>
                    <TableCell>{date(b.date)}</TableCell>
                    <TableCell>{b.label || tx('Opening balance')}</TableCell>
                    <TableCell className="text-right">{b.items}</TableCell>
                    <TableCell>{b.open === 0 ? tx('Reversed') : tx('Posted')}</TableCell>
                    <TableCell className="text-right">
                      {canReverse && b.open > 0 && (
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={reverse.isPending}
                          onClick={() => reverseBatch(b.batchId)}
                        >
                          <RotateCcw className="mr-1 h-3.5 w-3.5" />
                          {tx('Reverse batch')}
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function PartnerSection({
  title,
  rows,
  setRows,
  options,
  codes,
  base,
}: {
  title: string;
  rows: PartnerRow[];
  setRows: React.Dispatch<React.SetStateAction<PartnerRow[]>>;
  options: { value: string; label: string; code?: string }[];
  codes: string[];
  base: string;
}) {
  const update = (i: number, patch: Partial<PartnerRow>) => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-base">{title}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {rows.map((r, i) => (
          <div key={i} className="grid grid-cols-[1fr_140px_100px_120px_160px_180px_40px] gap-2">
            <SearchableSelect value={r.partnerId} onValueChange={(v) => update(i, { partnerId: v })} options={options} placeholder={tx('Partner…')} />
            <Input type="number" min={0} placeholder={tx('Amount')} value={r.amount} onChange={(e) => update(i, { amount: e.target.value })} />
            <CurrencyCells row={r} codes={codes} base={base} onChange={(p) => update(i, p)} />
            <Input type="date" value={r.dueDate} onChange={(e) => update(i, { dueDate: e.target.value })} />
            <Input placeholder={tx('Old invoice no.')} value={r.reference} onChange={(e) => update(i, { reference: e.target.value })} />
            <Button size="icon" variant="ghost" onClick={() => setRows((rs) => rs.filter((_, j) => j !== i))}>
              <Trash2 className="h-4 w-4" />
            </Button>
            <FunctionalHint amount={Number(r.amount || 0)} row={r} base={base} />
          </div>
        ))}
        <Button variant="outline" size="sm" onClick={() => setRows((rs) => [...rs, emptyPartner()])}>
          <Plus className="mr-1 h-4 w-4" />
          {tx('Add balance')}
        </Button>
      </CardContent>
    </Card>
  );
}

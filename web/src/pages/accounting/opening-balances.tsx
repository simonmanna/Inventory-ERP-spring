import { useMemo, useState } from 'react';
import { Plus, Trash2, Upload } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAccounts } from '@/features/accounting/api';
import { useOpeningBalances, usePostOpeningBalances, localDay } from '@/features/finance/api';
import { usePartners } from '@/features/partners/api';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

type AccountRow = { accountId: string; debit: string; credit: string };
type PartnerRow = { partnerId: string; amount: string; dueDate: string; reference: string };

const emptyAccount = (): AccountRow => ({ accountId: '', debit: '', credit: '' });
const emptyPartner = (): PartnerRow => ({ partnerId: '', amount: '', dueDate: '', reference: '' });

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

  const accountOptions = useMemo(
    () =>
      (accountList?.data ?? [])
        .filter((a) => !a.isGroup && !a.isControlAccount)
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

  const debit = accounts.reduce((s, a) => s + Number(a.debit || 0), 0);
  const credit = accounts.reduce((s, a) => s + Number(a.credit || 0), 0);
  const diff = debit - credit;

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
        })),
      customers: customers
        .filter((c) => c.partnerId && Number(c.amount) > 0)
        .map((c) => ({ partnerId: c.partnerId, amount: Number(c.amount), dueDate: c.dueDate || undefined, reference: c.reference || undefined })),
      suppliers: suppliers
        .filter((c) => c.partnerId && Number(c.amount) > 0)
        .map((c) => ({ partnerId: c.partnerId, amount: Number(c.amount), dueDate: c.dueDate || undefined, reference: c.reference || undefined })),
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
            <div key={i} className="grid grid-cols-[1fr_140px_140px_40px] gap-2">
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
              <Button size="icon" variant="ghost" onClick={() => setAccounts((rs) => rs.filter((_, j) => j !== i))}>
                <Trash2 className="h-4 w-4" />
              </Button>
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
            </div>
          </div>
        </CardContent>
      </Card>

      <PartnerSection title={tx('Customer balances (receivable)')} rows={customers} setRows={setCustomers} options={customerOptions} />
      <PartnerSection title={tx('Supplier balances (payable)')} rows={suppliers} setRows={setSuppliers} options={supplierOptions} />

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
    </div>
  );
}

function PartnerSection({
  title,
  rows,
  setRows,
  options,
}: {
  title: string;
  rows: PartnerRow[];
  setRows: React.Dispatch<React.SetStateAction<PartnerRow[]>>;
  options: { value: string; label: string; code?: string }[];
}) {
  const update = (i: number, patch: Partial<PartnerRow>) => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-base">{title}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {rows.map((r, i) => (
          <div key={i} className="grid grid-cols-[1fr_140px_160px_180px_40px] gap-2">
            <SearchableSelect value={r.partnerId} onValueChange={(v) => update(i, { partnerId: v })} options={options} placeholder={tx('Partner…')} />
            <Input type="number" min={0} placeholder={tx('Amount')} value={r.amount} onChange={(e) => update(i, { amount: e.target.value })} />
            <Input type="date" value={r.dueDate} onChange={(e) => update(i, { dueDate: e.target.value })} />
            <Input placeholder={tx('Old invoice no.')} value={r.reference} onChange={(e) => update(i, { reference: e.target.value })} />
            <Button size="icon" variant="ghost" onClick={() => setRows((rs) => rs.filter((_, j) => j !== i))}>
              <Trash2 className="h-4 w-4" />
            </Button>
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

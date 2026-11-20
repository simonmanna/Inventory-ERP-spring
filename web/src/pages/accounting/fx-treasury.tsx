import { useState } from 'react';
import { Link } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader } from '@/components/list';
import {
  useDesignateFxAccount,
  useFxSummary,
  useFxTreasuryAccounts,
  type FxTreasuryAccount,
} from '@/features/accounting/api';
import { money } from '@/lib/format';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const errorText = (e: any) => {
  const m = e?.response?.data?.message;
  return Array.isArray(m) ? m.join(', ') : m ?? tx('Error');
};

const monthStart = () => {
  const d = new Date();
  return new Date(d.getFullYear(), d.getMonth(), 1).toISOString().slice(0, 10);
};

/**
 * Foreign-currency accounts (R-8, ADR-S076): each cash, bank and mobile-money account held in a foreign currency, its
 * balance in that currency and its carrying value; legacy accounts are designated here; realised / unrealised FX.
 */
export default function FxTreasuryPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canDesignate = has(PERMISSIONS.account.update);
  const canSummary = has(PERMISSIONS.currency.read);
  const accounts = useFxTreasuryAccounts();
  const designate = useDesignateFxAccount();
  const [from, setFrom] = useState(monthStart());
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const summary = useFxSummary(canSummary ? from : '', canSummary ? to : '');

  const [target, setTarget] = useState<FxTreasuryAccount | null>(null);
  const [balance, setBalance] = useState('');
  const [note, setNote] = useState('');

  const open = (a: FxTreasuryAccount) => {
    setTarget(a);
    setBalance('');
    setNote('');
  };

  const submit = async () => {
    if (!target) return;
    try {
      await designate.mutateAsync({ accountId: target.accountId, foreignBalance: Number(balance), note: note.trim() || undefined });
      notify.success(tx('Account designated'));
      setTarget(null);
    } catch (e) {
      notify.error(errorText(e));
    }
  };

  const columns: Column<FxTreasuryAccount>[] = [
    { key: 'code', header: tx('Account'), render: (a) => <span><span className="font-mono">{a.code}</span> · {a.name}</span> },
    { key: 'currencyCode', header: tx('Currency'), render: (a) => a.currencyCode },
    {
      key: 'state',
      header: tx('State'),
      render: (a) => a.state === 'designated'
        ? <Badge variant="secondary">{tx('Designated')}</Badge>
        : <Badge variant="destructive">{tx('Needs designation')}</Badge>,
    },
    { key: 'foreignBalance', header: tx('Balance'), render: (a) => `${money(a.foreignBalance)} ${a.currencyCode}` },
    { key: 'carryingValue', header: tx('Book value'), render: (a) => `${money(a.carryingValue)} ${a.functionalCurrency}` },
    { key: 'carryingRate', header: tx('Book rate'), render: (a) => a.carryingRate ?? '—' },
    {
      key: 'unknownCurrencyBalance',
      header: tx('Unknown-currency balance'),
      render: (a) => (Number(a.unknownCurrencyBalance) !== 0 ? `${money(a.unknownCurrencyBalance)} ${a.functionalCurrency}` : '—'),
    },
    {
      key: 'actions',
      header: '',
      render: (a) => (a.state === 'undesignated' && canDesignate
        ? <Button size="sm" variant="outline" onClick={() => open(a)}>{tx('Designate')}</Button>
        : null),
    },
  ];

  return (
    <div className="space-y-6">
      <ListPageHeader
        title={tx('Foreign-currency accounts')}
        description={tx('Cash, bank and mobile-money accounts held in a currency other than the functional currency.')}
      />

      <ListCard>
        <DataTable
          columns={columns}
          data={accounts.data ?? []}
          loading={accounts.isLoading}
          getRowId={(a) => a.accountId}
          emptyMessage={tx('No foreign-currency accounts. Create a bank account with a currency to hold foreign cash.')}
        />
      </ListCard>

      {canSummary ? (
        <Card>
          <CardHeader className="flex flex-row flex-wrap items-end justify-between gap-3">
            <div className="space-y-1">
              <CardTitle>{tx('Exchange gains and losses')}</CardTitle>
              <Link to="/fx-treasury/report" className="text-xs text-primary hover:underline">{tx('Open the FX gain / loss report')}</Link>
            </div>
            <div className="flex flex-wrap items-end gap-2">
              <div className="space-y-1">
                <Label htmlFor="fx-from">{tx('From')}</Label>
                <Input id="fx-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
              </div>
              <div className="space-y-1">
                <Label htmlFor="fx-to">{tx('To')}</Label>
                <Input id="fx-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
              </div>
            </div>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            {summary.data ? (
              <>
                <dl className="grid grid-cols-3 gap-3">
                  <div><dt className="text-muted-foreground">{tx('Realised')}</dt><dd className="font-semibold">{money(summary.data.realised)} {summary.data.functionalCurrency}</dd></div>
                  <div><dt className="text-muted-foreground">{tx('Unrealised')}</dt><dd className="font-semibold">{money(summary.data.unrealised)} {summary.data.functionalCurrency}</dd></div>
                  <div><dt className="text-muted-foreground">{tx('Total')}</dt><dd className="font-semibold">{money(summary.data.total)} {summary.data.functionalCurrency}</dd></div>
                </dl>
                {summary.data.byOrigin.length ? (
                  <ul className="space-y-1">
                    {summary.data.byOrigin.map((o) => (
                      <li key={`${o.kind}-${o.origin}`} className="flex justify-between border-b py-1 last:border-0">
                        <span>{o.kind === 'realised' ? tx('Realised') : tx('Unrealised')} · {o.origin}</span>
                        <span>{money(o.gain)}</span>
                      </li>
                    ))}
                  </ul>
                ) : <p className="text-muted-foreground">{tx('No exchange differences in this period.')}</p>}
              </>
            ) : <p className="text-muted-foreground">{summary.isLoading ? tx('Loading…') : '—'}</p>}
          </CardContent>
        </Card>
      ) : null}

      <Dialog open={!!target} onOpenChange={(v) => { if (!v && !designate.isPending) setTarget(null); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Designate')} {target?.code} · {target?.name}</DialogTitle>
            <DialogDescription>
              {tx('This account holds')} {money(target?.unknownCurrencyBalance ?? '0')} {target?.functionalCurrency}{' '}
              {tx('recorded before foreign-currency treasury. State how much')} {target?.currencyCode}{' '}
              {tx('it actually holds (bank statement). Book values do not change; the next revaluation brings it to the current rate.')}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="fx-designate-balance">{tx('Balance in')} {target?.currencyCode}</Label>
              <Input id="fx-designate-balance" type="number" inputMode="decimal" step="0.01" value={balance} onChange={(e) => setBalance(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="fx-designate-note">{tx('Note')} <span className="font-normal text-muted-foreground">{tx('(optional)')}</span></Label>
              <Input id="fx-designate-note" value={note} onChange={(e) => setNote(e.target.value)} placeholder={tx('e.g. Bank letter 31 Oct')} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setTarget(null)} disabled={designate.isPending}>{tx('Cancel')}</Button>
            <Button onClick={submit} disabled={designate.isPending || balance === '' || Number(balance) === 0}>{tx('Designate')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

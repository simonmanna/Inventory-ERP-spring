import { useMemo, useState } from 'react';
import { FileDown, Mail } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { openPdf, statementPath, useEmailStatement, useStatement, localDay } from '@/features/finance/api';
import { usePartners } from '@/features/partners/api';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

export function StatementsPage() {
  const now = new Date();
  const [side, setSide] = useState<'ar' | 'ap'>('ar');
  const [partnerId, setPartnerId] = useState('');
  const [from, setFrom] = useState(localDay(new Date(now.getFullYear(), 0, 1)));
  const [to, setTo] = useState(localDay(now));
  const [recipient, setRecipient] = useState('');
  const { data: partners } = usePartners({ page: 1, pageSize: 1000 });
  const { data, isLoading } = useStatement(side, partnerId || undefined, { from, to });
  const email = useEmailStatement();

  const options = useMemo(
    () =>
      (partners?.data ?? [])
        .filter((p) => (side === 'ar' ? p.isCustomer : p.isSupplier))
        .map((p) => ({ value: p.id, label: p.name, code: p.code })),
    [partners, side],
  );

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Statements')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Customer and supplier statements of account with running balance and aging.')}
        </p>
      </div>
      <div className="flex flex-wrap items-end gap-3 rounded-lg border p-3">
        <div className="flex gap-1">
          <Button variant={side === 'ar' ? 'default' : 'outline'} onClick={() => { setSide('ar'); setPartnerId(''); }}>
            {tx('Customers')}
          </Button>
          <Button variant={side === 'ap' ? 'default' : 'outline'} onClick={() => { setSide('ap'); setPartnerId(''); }}>
            {tx('Suppliers')}
          </Button>
        </div>
        <div className="w-72 space-y-1">
          <Label>{side === 'ar' ? tx('Customer') : tx('Supplier')}</Label>
          <SearchableSelect value={partnerId} onValueChange={setPartnerId} options={options} placeholder={tx('Select…')} />
        </div>
        <div className="space-y-1">
          <Label>{tx('From')}</Label>
          <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="h-9" />
        </div>
        <div className="space-y-1">
          <Label>{tx('To')}</Label>
          <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="h-9" />
        </div>
        {partnerId && (
          <Button variant="outline" onClick={() => openPdf(`${statementPath(side, partnerId)}.pdf`, { from, to })}>
            <FileDown className="mr-2 h-4 w-4" />
            {tx('PDF')}
          </Button>
        )}
      </div>

      {!partnerId ? (
        <p className="text-sm text-muted-foreground">{tx('Choose a partner to see the statement.')}</p>
      ) : isLoading || !data ? (
        <Skeleton className="h-48 w-full" />
      ) : (
        <>
          {side === 'ar' && (
            <div className="flex flex-wrap items-end gap-2">
              <div className="w-72 space-y-1">
                <Label>{tx('Email to')}</Label>
                <Input value={recipient} onChange={(e) => setRecipient(e.target.value)} placeholder={data.partner.email ?? tx('customer@example.com')} />
              </div>
              <Button
                disabled={email.isPending}
                onClick={() => email.mutate({ partnerId, from, to, recipient: recipient || undefined })}
              >
                <Mail className="mr-2 h-4 w-4" />
                {tx('Email statement')}
              </Button>
            </div>
          )}
          <div className="grid gap-3 md:grid-cols-4">
            <Summary label={tx('Opening balance')} value={data.openingBalance} />
            <Summary label={tx('Debits')} value={data.totalDebit} />
            <Summary label={tx('Credits')} value={data.totalCredit} />
            <Summary label={tx('Closing balance')} value={data.closingBalance} strong />
          </div>
          <div className="rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Date')}</TableHead>
                  <TableHead>{tx('Reference')}</TableHead>
                  <TableHead>{tx('Type')}</TableHead>
                  <TableHead>{tx('Description')}</TableHead>
                  <TableHead className="text-right">{tx('Debit')}</TableHead>
                  <TableHead className="text-right">{tx('Credit')}</TableHead>
                  <TableHead className="text-right">{tx('Balance')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                <TableRow className="bg-muted/40">
                  <TableCell colSpan={6}>{tx('Balance brought forward')}</TableCell>
                  <TableCell className="text-right font-medium">{money(data.openingBalance)}</TableCell>
                </TableRow>
                {data.lines.map((l, i) => (
                  <TableRow key={`${l.entryId}-${i}`}>
                    <TableCell>{date(l.date)}</TableCell>
                    <TableCell>{l.reference}</TableCell>
                    <TableCell>{tx(l.type)}</TableCell>
                    <TableCell>{l.description}</TableCell>
                    <TableCell className="text-right">{Number(l.debit) ? money(l.debit) : ''}</TableCell>
                    <TableCell className="text-right">{Number(l.credit) ? money(l.credit) : ''}</TableCell>
                    <TableCell className="text-right">{money(l.balance)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Card>
            <CardContent className="grid grid-cols-6 gap-2 p-4 text-sm">
              {(['current', 'd1_30', 'd31_60', 'd61_90', 'd90_plus'] as const).map((k) => (
                <div key={k}>
                  <div className="text-xs text-muted-foreground">
                    {{ current: tx('Current'), d1_30: '1–30', d31_60: '31–60', d61_90: '61–90', d90_plus: '90+' }[k]}
                  </div>
                  <div className="font-medium">{money(data.aging.buckets[k])}</div>
                </div>
              ))}
              <div>
                <div className="text-xs text-muted-foreground">{tx('Total open')}</div>
                <div className="font-semibold">{money(data.aging.totalOpen)}</div>
              </div>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}

function Summary({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="rounded-md border p-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={strong ? 'text-xl font-bold' : 'text-lg font-medium'}>{money(value)}</div>
    </div>
  );
}

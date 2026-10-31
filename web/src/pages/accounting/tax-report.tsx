import { useState } from 'react';
import { Download } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useBranchOptions, useTaxPack, useTaxSummary, type TaxSide, localDay } from '@/features/finance/api';
import { exportCSV } from '@/lib/export-csv';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

export function TaxReportPage() {
  const now = new Date();
  const [from, setFrom] = useState(localDay(new Date(now.getFullYear(), now.getMonth(), 1)));
  const [to, setTo] = useState(localDay(now));
  const [branchId, setBranchId] = useState('');
  const { data: branches } = useBranchOptions();
  const { data, isLoading } = useTaxSummary({ from, to, branchId: branchId || undefined });
  const { data: pack } = useTaxPack({ from, to, branchId: branchId || undefined });

  const downloadPack = () => {
    if (!pack) return;
    exportCSV(
      `tax-return-${from}-${to}.csv`,
      [tx('Box'), tx('Description'), tx('Amount')],
      pack.boxes.map((b) => [String(b.box), b.label, b.amount]),
    );
  };

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Tax Report')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Output tax on sales, input tax on purchases and the net amount due for the period.')}
        </p>
      </div>
      <div className="flex flex-wrap items-end gap-3 rounded-lg border p-3">
        <div className="space-y-1">
          <Label>{tx('From')}</Label>
          <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="h-9" />
        </div>
        <div className="space-y-1">
          <Label>{tx('To')}</Label>
          <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="h-9" />
        </div>
        <div className="space-y-1">
          <Label>{tx('Branch')}</Label>
          <select className="h-9 rounded-md border bg-background px-2 text-sm" value={branchId} onChange={(e) => setBranchId(e.target.value)}>
            <option value="">{tx('All branches')}</option>
            {(branches ?? []).map((b) => (
              <option key={b.id} value={b.id}>
                {b.name}
              </option>
            ))}
          </select>
        </div>
      </div>

      {isLoading || !data ? (
        <Skeleton className="h-48 w-full" />
      ) : (
        <>
          <div className="grid gap-3 md:grid-cols-3">
            <Stat label={tx('Output tax (sales)')} value={money(data.output.taxTotal)} />
            <Stat label={tx('Input tax (purchases)')} value={money(data.input.taxTotal)} />
            <Stat
              label={data.position === 'payable' ? tx('Net tax payable') : tx('Net tax refundable')}
              value={money(Math.abs(Number(data.netTaxPayable)))}
              tone={data.position === 'payable' ? 'text-red-600' : 'text-emerald-600'}
            />
          </div>
          {pack && (
            <Card>
              <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
                <CardTitle className="text-base">{tx('Tax return pack')}</CardTitle>
                <div className="flex items-center gap-2 print:hidden">
                  <Badge variant={pack.reconciled ? 'default' : 'destructive'}>
                    {pack.reconciled
                      ? tx('Agrees with the ledger')
                      : tx('Differs from the ledger by {{amount}}', { amount: money(pack.glDifference) })}
                  </Badge>
                  <Button variant="outline" size="sm" onClick={downloadPack}>
                    <Download className="mr-2 h-4 w-4" />{tx('Download CSV')}
                  </Button>
                </div>
              </CardHeader>
              <CardContent>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-16">{tx('Box')}</TableHead>
                      <TableHead>{tx('Description')}</TableHead>
                      <TableHead className="text-right">{tx('Amount')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {pack.boxes.map((b) => (
                      <TableRow key={b.box}>
                        <TableCell>{b.box}</TableCell>
                        <TableCell>{b.label}</TableCell>
                        <TableCell className="text-right">{money(b.amount)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>
          )}
          <SideTable title={tx('Output tax — sales')} side={data.output} />
          <SideTable title={tx('Input tax — purchases')} side={data.input} />
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-base">{tx('General ledger cross-check (tax accounts)')}</CardTitle>
            </CardHeader>
            <CardContent>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{tx('Account')}</TableHead>
                    <TableHead className="text-right">{tx('Debit')}</TableHead>
                    <TableHead className="text-right">{tx('Credit')}</TableHead>
                    <TableHead className="text-right">{tx('Net (owed)')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data.gl.accounts.map((a) => (
                    <TableRow key={a.accountId}>
                      <TableCell>
                        {a.code} · {a.name}
                      </TableCell>
                      <TableCell className="text-right">{money(a.debit)}</TableCell>
                      <TableCell className="text-right">{money(a.credit)}</TableCell>
                      <TableCell className="text-right">{money(a.net)}</TableCell>
                    </TableRow>
                  ))}
                  <TableRow className="font-semibold">
                    <TableCell colSpan={3}>{tx('Total')}</TableCell>
                    <TableCell className="text-right">{money(data.gl.net)}</TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}

function Stat({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className="rounded-md border p-4">
      <div className="text-xs font-semibold uppercase text-muted-foreground">{label}</div>
      <div className={`mt-1 text-2xl font-bold ${tone ?? ''}`}>{value}</div>
    </div>
  );
}

function SideTable({ title, side }: { title: string; side: TaxSide }) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-base">{title}</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Tax')}</TableHead>
              <TableHead>{tx('Category')}</TableHead>
              <TableHead className="text-right">{tx('Rate')}</TableHead>
              <TableHead className="text-right">{tx('Documents')}</TableHead>
              <TableHead className="text-right">{tx('Taxable amount')}</TableHead>
              <TableHead className="text-right">{tx('Tax')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {side.lines.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} className="h-16 text-center text-muted-foreground">
                  {tx('Nothing in this period.')}
                </TableCell>
              </TableRow>
            ) : (
              side.lines.map((l) => (
                <TableRow key={l.taxId ?? 'none'}>
                  <TableCell>{l.name}</TableCell>
                  <TableCell>{tx(l.vatCategory)}</TableCell>
                  <TableCell className="text-right">{l.rate != null ? `${Number(l.rate)}%` : '—'}</TableCell>
                  <TableCell className="text-right">{l.documents}</TableCell>
                  <TableCell className="text-right">{money(l.taxableAmount)}</TableCell>
                  <TableCell className="text-right">{money(l.taxAmount)}</TableCell>
                </TableRow>
              ))
            )}
            <TableRow className="font-semibold">
              <TableCell colSpan={4}>{tx('Total')}</TableCell>
              <TableCell className="text-right">{money(side.taxableTotal)}</TableCell>
              <TableCell className="text-right">{money(side.taxTotal)}</TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

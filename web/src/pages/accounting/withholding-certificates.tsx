import { useState } from 'react';
import { Download, Pencil } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import {
  useUpdateWithholdingCertificate,
  useWithholdingCertificates,
  useWithholdingReport,
  type WithholdingCertificate,
} from '@/features/accounting/api';
import { localDay } from '@/features/finance/api';
import { useAuthStore } from '@/stores/auth.store';
import { exportCSV } from '@/lib/export-csv';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

/**
 * Withholding tax certificates (ADR-S071): issued to suppliers (bills and supplier payments that withheld) and received
 * from customers (receipts they withheld from), with the period summary by partner and tax for filing.
 */
export default function WithholdingCertificatesPage() {
  const now = new Date();
  const [direction, setDirection] = useState<'issued' | 'received'>('issued');
  const [from, setFrom] = useState(localDay(new Date(now.getFullYear(), now.getMonth(), 1)));
  const [to, setTo] = useState(localDay(now));
  const [page, setPage] = useState(1);
  const canUpdate = useAuthStore().hasPermission(PERMISSIONS.withholding.update);
  const list = useWithholdingCertificates({ direction, from, to, page, pageSize: 50 });
  const report = useWithholdingReport({ direction, from, to });
  const update = useUpdateWithholdingCertificate();
  const rows = list.data?.data ?? [];
  const meta = list.data?.meta;

  const editNumber = (c: WithholdingCertificate) => {
    const number = window.prompt(tx('Certificate number given by the customer'), c.number);
    if (number && number.trim() && number.trim() !== c.number) update.mutate({ id: c.id, number: number.trim() });
  };

  const download = () => {
    if (!report.data) return;
    exportCSV(
      `withholding-${direction}-${from}-${to}.csv`,
      [tx('Partner'), tx('TIN'), tx('Tax'), tx('Rate %'), tx('Base'), tx('Withheld'), tx('Certificates')],
      report.data.lines.map((l) => [l.partnerName, l.partnerTaxNumber ?? '', l.taxName ?? '', l.rate ?? '',
        l.baseAmount, l.amount, String(l.certificates)]),
    );
  };

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Withholding Tax Certificates')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Tax withheld from suppliers (owed to the authority) and withheld by customers (claimable), by period.')}
        </p>
      </div>
      <div className="flex flex-wrap items-end gap-3 rounded-lg border p-3">
        <div className="space-y-1">
          <Label>{tx('Direction')}</Label>
          <select
            className="h-9 rounded-md border bg-background px-2 text-sm"
            value={direction}
            onChange={(e) => { setDirection(e.target.value as 'issued' | 'received'); setPage(1); }}
          >
            <option value="issued">{tx('Issued to suppliers')}</option>
            <option value="received">{tx('Received from customers')}</option>
          </select>
        </div>
        <div className="space-y-1">
          <Label>{tx('From')}</Label>
          <Input type="date" value={from} onChange={(e) => { setFrom(e.target.value); setPage(1); }} className="h-9" />
        </div>
        <div className="space-y-1">
          <Label>{tx('To')}</Label>
          <Input type="date" value={to} onChange={(e) => { setTo(e.target.value); setPage(1); }} className="h-9" />
        </div>
        <Button variant="outline" size="sm" onClick={download} disabled={!report.data}>
          <Download className="h-4 w-4" /> {tx('Filing summary (CSV)')}
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tx('Summary for filing')}</CardTitle>
        </CardHeader>
        <CardContent>
          {report.isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : report.data ? (
            <div className="space-y-3">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{tx('Partner')}</TableHead>
                    <TableHead>{tx('TIN')}</TableHead>
                    <TableHead>{tx('Tax')}</TableHead>
                    <TableHead className="text-right">{tx('Base')}</TableHead>
                    <TableHead className="text-right">{tx('Withheld')}</TableHead>
                    <TableHead className="text-right">{tx('Certificates')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {report.data.lines.map((l) => (
                    <TableRow key={`${l.partnerId}:${l.taxId ?? ''}:${l.rate ?? ''}`}>
                      <TableCell>{l.partnerName}</TableCell>
                      <TableCell>{l.partnerTaxNumber ?? '—'}</TableCell>
                      <TableCell>{l.taxName ?? tx('Manual')}{l.rate ? ` (${Number(l.rate)}%)` : ''}</TableCell>
                      <TableCell className="text-right tabular-nums">{money(Number(l.baseAmount))}</TableCell>
                      <TableCell className="text-right tabular-nums">{money(Number(l.amount))}</TableCell>
                      <TableCell className="text-right">{l.certificates}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <div className="flex flex-wrap justify-end gap-6 text-sm">
                <span>{tx('Total withheld')}: <strong>{money(Number(report.data.amountTotal))}</strong></span>
                <span>{tx('Ledger movement')}: {money(Number(report.data.glMovement))}</span>
                {Number(report.data.glDifference) === 0 ? (
                  <Badge className="bg-green-100 text-green-700 hover:bg-green-100">{tx('Ties to the ledger')}</Badge>
                ) : (
                  <Badge variant="destructive">
                    {tx('Difference')} {money(Number(report.data.glDifference))}
                  </Badge>
                )}
              </div>
            </div>
          ) : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tx('Certificates')}</CardTitle>
        </CardHeader>
        <CardContent>
          {list.isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : rows.length === 0 ? (
            <p className="text-sm text-muted-foreground">{tx('No certificates in this period.')}</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Number')}</TableHead>
                  <TableHead>{tx('Date')}</TableHead>
                  <TableHead>{tx('Partner')}</TableHead>
                  <TableHead>{tx('Source')}</TableHead>
                  <TableHead>{tx('Tax')}</TableHead>
                  <TableHead className="text-right">{tx('Base')}</TableHead>
                  <TableHead className="text-right">{tx('Withheld')}</TableHead>
                  <TableHead>{tx('Status')}</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((c) => (
                  <TableRow key={c.id}>
                    <TableCell className="font-mono">{c.number}</TableCell>
                    <TableCell>{c.certificateDate.slice(0, 10)}</TableCell>
                    <TableCell>{c.partnerName}</TableCell>
                    <TableCell>{c.documentNumber ?? c.paymentNumber ?? '—'}</TableCell>
                    <TableCell>{c.taxName ?? tx('Manual')}</TableCell>
                    <TableCell className="text-right tabular-nums">{money(Number(c.baseAmount))}</TableCell>
                    <TableCell className="text-right tabular-nums">{money(Number(c.amount))}</TableCell>
                    <TableCell>
                      {c.status === 'issued' ? (
                        <Badge variant="secondary">{tx('Valid')}</Badge>
                      ) : (
                        <Badge variant="outline">{tx('Cancelled')}</Badge>
                      )}
                    </TableCell>
                    <TableCell className="text-right">
                      {canUpdate && c.direction === 'received' && c.status === 'issued' && (
                        <Button variant="ghost" size="icon" title={tx('Edit number')} onClick={() => editNumber(c)}>
                          <Pencil className="h-4 w-4" />
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          {meta && meta.totalPages > 1 && (
            <div className="flex justify-end gap-2 pt-3">
              <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>
                {tx('Previous')}
              </Button>
              <Button variant="outline" size="sm" disabled={page >= meta.totalPages} onClick={() => setPage((p) => p + 1)}>
                {tx('Next')}
              </Button>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Printer } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatusPill, type Tone } from '@/components/list';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { money, date, useOrgCurrency } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { CHANNEL_LABEL, DISPOSITION_LABEL, useReturn } from '@/features/returns/api';

const dispositionTone: Record<string, Tone> = { restock: 'success', waste: 'warning', no_return: 'neutral' };

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="text-xs uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="font-medium">{children}</div>
    </div>
  );
}

const qty = (v: string | number) => Number(v).toLocaleString(undefined, { maximumFractionDigits: 3 });

export function ReturnDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const currency = useOrgCurrency();
  const { data: ret, isLoading, isError } = useReturn(id);

  if (isError) return <div className="text-sm text-muted-foreground">{tx('Return not found.')}</div>;
  if (isLoading || !ret) return <div className="text-sm text-muted-foreground">{tx('Loading...')}</div>;

  const paidOut = ret.payments.reduce((s, p) => s + Number(p.amount || 0), 0);
  const arCredit = Number(ret.amount) - paidOut;

  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <div className="flex items-center justify-between print:hidden">
        <Button variant="outline" onClick={() => navigate('/returns')}>
          <ArrowLeft className="mr-1.5 h-4 w-4" /> {tx('Back')}
        </Button>
        <Button variant="outline" onClick={() => window.print()}>
          <Printer className="mr-2 h-4 w-4" /> {tx('Print')}
        </Button>
      </div>

      <Card>
        <CardContent className="space-y-6 pt-6">
          <div className="flex items-start justify-between border-b pb-4">
            <div>
              <h1 className="text-2xl font-semibold">{tx('Sales Return')}</h1>
              <p className="text-sm text-muted-foreground">{ret.returnNumber}</p>
            </div>
            <div className="space-y-1 text-right text-sm">
              <div className="text-muted-foreground">{date(ret.createdAt)}</div>
              <StatusPill tone={dispositionTone[ret.stockDisposition] ?? 'neutral'}>{DISPOSITION_LABEL[ret.stockDisposition]}</StatusPill>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
            <Field label={tx('Original sale')}>
              <Link to={`/pos/receipts/${ret.invoiceId}`} className="text-primary hover:underline">{ret.invoiceNumber}</Link>
            </Field>
            <Field label={tx('Sale date')}>{date(ret.invoiceDate)}</Field>
            <Field label={tx('Channel')}>{CHANNEL_LABEL[ret.channel]}</Field>
            <Field label={tx('Customer')}>{ret.partner?.name ?? tx('Walk-in')}</Field>
            <Field label={tx('Recorded by')}>{ret.createdByName ?? '—'}</Field>
            <Field label={tx('Approved by')}>{ret.approvedByName ?? '—'}</Field>
            <Field label={tx('Register')}>{ret.cashRegisterName ?? '—'}</Field>
            <Field label={tx('Journal entry')}>
              {ret.journalEntryId ? (
                <Link to={`/journal-entries/${ret.journalEntryId}`} className="text-primary hover:underline">
                  {ret.journalEntryNumber ?? tx('View')}
                </Link>
              ) : '—'}
            </Field>
            <Field label={tx('Sale status')}>{ret.invoiceStatus.replace(/_/g, ' ')}</Field>
          </div>

          <div className="rounded-md bg-muted/50 p-4">
            <div className="text-xs uppercase tracking-wide text-muted-foreground">{tx('Reason')}</div>
            <div className="mt-1 whitespace-pre-wrap">{ret.reason}</div>
          </div>

          <div>
            <div className="mb-2 text-sm font-medium">{tx('Returned items')}</div>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Description')}</TableHead>
                  <TableHead className="text-right">{tx('Qty')}</TableHead>
                  <TableHead className="text-right">{tx('Net')}</TableHead>
                  <TableHead className="text-right">{tx('Tax')}</TableHead>
                  <TableHead className="text-right">{tx('Total')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {ret.lines.map((l) => (
                  <TableRow key={l.lineId}>
                    <TableCell>
                      {l.description ?? '—'}
                      {l.variantName && <span className="text-muted-foreground"> · {l.variantName}</span>}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{qty(l.quantity)}</TableCell>
                    <TableCell className="text-right tabular-nums">{money(l.subtotal, currency)}</TableCell>
                    <TableCell className="text-right tabular-nums">{money(l.taxAmount, currency)}</TableCell>
                    <TableCell className="text-right tabular-nums font-medium">{money(l.total, currency)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <div className="mb-2 text-sm font-medium">{tx('Refund paid out')}</div>
              {ret.payments.length === 0 ? (
                <p className="text-sm text-muted-foreground">{tx('No money paid out.')}</p>
              ) : (
                <ul className="space-y-1.5 text-sm">
                  {ret.payments.map((p) => (
                    <li key={p.paymentId} className="flex justify-between gap-3">
                      <span>
                        <span className="capitalize">{(p.paymentMethod ?? '').replace(/_/g, ' ')}</span>
                        <span className="text-muted-foreground"> · {p.paymentNumber}</span>
                      </span>
                      <span className="tabular-nums">{money(p.amount, currency)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <div className="space-y-1.5 rounded-md border p-4 text-sm">
              {arCredit > 0.005 && (
                <div className="flex justify-between"><span className="text-muted-foreground">{tx('Credited to balance due')}</span><span className="tabular-nums">{money(arCredit, currency)}</span></div>
              )}
              <div className="flex justify-between"><span className="text-muted-foreground">{tx('Paid back')}</span><span className="tabular-nums">{money(paidOut, currency)}</span></div>
              <div className="flex justify-between border-t pt-1.5 text-base font-semibold">
                <span>{tx('Return total')}</span><span className="tabular-nums text-rose-600">{money(ret.amount, currency)}</span>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

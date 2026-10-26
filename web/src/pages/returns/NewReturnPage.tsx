import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';
import { ArrowLeft, Lock, Search, Undo2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ListPageHeader } from '@/components/list';
import { OverrideDialog } from '@/pages/pos/OverrideDialog';
import { money, date, useOrgCurrency } from '@/lib/format';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { tx } from '@/lib/i18n/tx';
import {
  CHANNEL_LABEL, useCreateReturn, useOpenRegisters, useReturnableInvoice, useReturnableInvoices,
  type ReturnableInvoice, type StockDisposition,
} from '@/features/returns/api';

const QUICK_REASONS: Record<'retail' | 'restaurant', string[]> = {
  retail: ['Damaged / defective', 'Wrong item', 'Changed mind', 'Expired product'],
  restaurant: ['Wrong order', 'Food quality', 'Cold / late', 'Customer complaint'],
};

const DISPOSITIONS: Array<{ value: StockDisposition; label: string; hint: string }> = [
  { value: 'restock', label: tx('Restock'), hint: tx('Saleable goods go back on the shelf') },
  { value: 'waste', label: tx('Waste'), hint: tx('Prepared, consumed or damaged — written off') },
  { value: 'no_return', label: tx('Not returned'), hint: tx('Money back only, no goods received') },
];

const num = (v: string | number | null | undefined) => Number(v ?? 0);
const fmtQty = (v: number) => v.toLocaleString(undefined, { maximumFractionDigits: 3 });

function InvoicePicker({ onPick }: { onPick: (id: string) => void }) {
  const currency = useOrgCurrency();
  const [search, setSearch] = useState('');
  const debounced = useDebouncedValue(search, 300);
  const { data, isLoading } = useReturnableInvoices(debounced.trim());
  const rows = data ?? [];

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">{tx('1. Find the original sale')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            autoFocus
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={tx('Scan or type receipt # / customer name…')}
            className="pl-9"
          />
        </div>
        <div className="divide-y rounded-md border">
          {isLoading && <div className="p-4 text-sm text-muted-foreground">{tx('Loading...')}</div>}
          {!isLoading && rows.length === 0 && (
            <div className="p-4 text-sm text-muted-foreground">{tx('No returnable sales found.')}</div>
          )}
          {rows.map((r) => (
            <button
              key={r.id}
              type="button"
              onClick={() => onPick(r.id)}
              className="flex w-full items-center justify-between gap-3 px-4 py-2.5 text-left hover:bg-muted/50"
            >
              <div>
                <div className="font-medium">{r.invoiceNumber}</div>
                <div className="text-xs text-muted-foreground">
                  {date(r.issueDate)} · {r.partner?.name ?? tx('Walk-in')} · {CHANNEL_LABEL[r.channel]}
                </div>
              </div>
              <div className="text-right">
                <div className="tabular-nums font-semibold">{money(r.totalAmount, currency)}</div>
                {num(r.amountRefunded) > 0 && (
                  <div className="text-xs tabular-nums text-rose-600">{money(r.amountRefunded, currency)} {tx('returned')}</div>
                )}
              </div>
            </button>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

function ReturnForm({ invoice, onChangeSale }: { invoice: ReturnableInvoice; onChangeSale: () => void }) {
  const navigate = useNavigate();
  const currency = useOrgCurrency();
  const create = useCreateReturn();
  const { data: registers = [] } = useOpenRegisters();
  const [qty, setQty] = useState<Record<string, string>>({});
  const [disposition, setDisposition] = useState<StockDisposition>(invoice.channel === 'retail' ? 'restock' : 'waste');
  const [reason, setReason] = useState('');
  const [cashSessionId, setCashSessionId] = useState('');
  const [showOverride, setShowOverride] = useState(false);

  const paidCash = invoice.tenders.some((t) => t.paymentMethod === 'cash' && num(t.amount) - num(t.refundedAmount) > 0);
  useEffect(() => {
    if (!cashSessionId && registers.length === 1) setCashSessionId(registers[0].id);
  }, [registers, cashSessionId]);

  const selections = invoice.lines
    .map((l) => ({ line: l, q: Math.min(num(qty[l.id]), num(l.remaining)) }))
    .filter((s) => s.q > 0);
  const total = selections.reduce((s, x) => s + x.q * num(x.line.unitRefund), 0);
  const canRestock = selections.every((s) => s.line.productId || s.line.menuItemId);
  const nothingLeft = invoice.lines.every((l) => num(l.remaining) <= 0);

  const blocker = useMemo(() => {
    if (!invoice.postingReady) return tx('This legacy sale must be reconciled before it can take a return.');
    if (nothingLeft) return tx('Everything on this sale has already been returned.');
    if (selections.length === 0) return tx('Enter a quantity for at least one item.');
    if (!reason.trim()) return tx('A reason is required.');
    if (disposition === 'restock' && !canRestock) return tx('Some selected lines cannot be restocked.');
    if (paidCash && !cashSessionId) return tx('Pick the open register the cash comes out of.');
    return null;
  }, [invoice.postingReady, nothingLeft, selections.length, reason, disposition, canRestock, paidCash, cashSessionId]);

  const returnAll = () =>
    setQty(Object.fromEntries(invoice.lines.filter((l) => num(l.remaining) > 0).map((l) => [l.id, String(num(l.remaining))])));

  const submit = async (managerId: string, pin?: string) => {
    try {
      const res = await create.mutateAsync({
        invoiceId: invoice.id,
        reason: reason.trim(),
        stockDisposition: disposition,
        overrideById: managerId,
        overridePin: pin,
        cashSessionId: cashSessionId || undefined,
        lines: selections.map((s) => ({ lineId: s.line.id, quantity: s.q })),
      });
      toast.success(`${tx('Return recorded')} · ${money(res.amount, currency)}`);
      navigate(`/returns/${res.refundId}`, { replace: true });
    } catch (e: any) {
      if (e?.response?.status === 409) {
        toast.info(tx('This return is already being processed.'));
        return;
      }
      toast.error(e?.response?.data?.message || tx('Failed to record the return'));
    }
  };

  return (
    <>
      <Card>
        <CardHeader className="flex flex-row items-start justify-between gap-3 pb-3">
          <div>
            <CardTitle className="text-base">{invoice.invoiceNumber}</CardTitle>
            <p className="text-sm text-muted-foreground">
              {date(invoice.issueDate)} · {invoice.partner?.name ?? tx('Walk-in')} · {CHANNEL_LABEL[invoice.channel]} ·{' '}
              {tx('Total')} {money(invoice.totalAmount, currency)}
              {num(invoice.amountRefunded) > 0 && <> · <span className="text-rose-600">{money(invoice.amountRefunded, currency)} {tx('already returned')}</span></>}
            </p>
          </div>
          <Button variant="outline" size="sm" onClick={onChangeSale}>{tx('Change sale')}</Button>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex items-center justify-between">
            <div className="text-sm font-medium">{tx('2. Items being returned')}</div>
            <Button variant="ghost" size="sm" onClick={returnAll} disabled={nothingLeft}>{tx('Return everything')}</Button>
          </div>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Item')}</TableHead>
                <TableHead className="text-right">{tx('Sold')}</TableHead>
                <TableHead className="text-right">{tx('Returnable')}</TableHead>
                <TableHead className="text-right">{tx('Unit refund')}</TableHead>
                <TableHead className="w-28 text-right">{tx('Return qty')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {invoice.lines.map((l) => {
                const remaining = num(l.remaining);
                const over = num(qty[l.id]) > remaining;
                return (
                  <TableRow key={l.id} className={remaining <= 0 ? 'opacity-50' : undefined}>
                    <TableCell>
                      {l.description}
                      {l.variantName && <span className="text-muted-foreground"> · {l.variantName}</span>}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{fmtQty(num(l.quantity))}</TableCell>
                    <TableCell className="text-right tabular-nums">{fmtQty(remaining)}</TableCell>
                    <TableCell className="text-right tabular-nums">{money(l.unitRefund, currency)}</TableCell>
                    <TableCell className="text-right">
                      <Input
                        type="number"
                        min={0}
                        max={remaining}
                        step="any"
                        inputMode="decimal"
                        disabled={remaining <= 0}
                        value={qty[l.id] ?? ''}
                        onChange={(e) => setQty((q) => ({ ...q, [l.id]: e.target.value }))}
                        className={`h-8 text-right tabular-nums ${over ? 'border-rose-500' : ''}`}
                        aria-label={`${tx('Return qty')} ${l.description}`}
                      />
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">{tx('3. Outcome')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-5">
          <div className="space-y-2">
            <Label>{tx('What happens to the goods?')}</Label>
            <div className="grid gap-2 sm:grid-cols-3">
              {DISPOSITIONS.map((d) => (
                <button
                  key={d.value}
                  type="button"
                  onClick={() => setDisposition(d.value)}
                  aria-pressed={disposition === d.value}
                  className={`rounded-lg border p-3 text-left transition-colors ${
                    disposition === d.value ? 'border-primary bg-primary/5 ring-1 ring-primary' : 'hover:bg-muted/50'
                  }`}
                >
                  <div className="text-sm font-medium">{tx(d.label)}</div>
                  <div className="text-xs text-muted-foreground">{tx(d.hint)}</div>
                </button>
              ))}
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="return-reason">{tx('Reason')}</Label>
            <div className="flex flex-wrap gap-1.5">
              {QUICK_REASONS[invoice.channel].map((r) => (
                <button
                  key={r}
                  type="button"
                  onClick={() => setReason(tx(r))}
                  className="rounded-full border px-2.5 py-1 text-xs hover:bg-muted"
                >
                  {tx(r)}
                </button>
              ))}
            </div>
            <Textarea id="return-reason" value={reason} onChange={(e) => setReason(e.target.value)} rows={2}
              placeholder={tx('Why is this being returned?')} />
          </div>

          {paidCash && (
            <div className="space-y-2">
              <Label htmlFor="return-register">{tx('Pay cash out of register')}</Label>
              <select
                id="return-register"
                className="h-9 w-full rounded-md border bg-background px-3 text-sm"
                value={cashSessionId}
                onChange={(e) => setCashSessionId(e.target.value)}
              >
                <option value="">{registers.length ? tx('Select an open register…') : tx('No register is open')}</option>
                {registers.map((r) => (
                  <option key={r.id} value={r.id}>{r.cashRegisterName}{r.userName ? ` — ${r.userName}` : ''}</option>
                ))}
              </select>
            </div>
          )}

          <div className="flex flex-wrap items-center justify-between gap-3 border-t pt-4">
            <div>
              <div className="text-xs uppercase tracking-wide text-muted-foreground">{tx('Return total')}</div>
              <div className="text-2xl font-semibold tabular-nums text-rose-600">{money(total, currency)}</div>
              <div className="text-xs text-muted-foreground">{tx('Refunded over the original payment methods; unpaid balance is reduced first.')}</div>
            </div>
            <div className="flex flex-col items-end gap-1">
              <Button
                variant="destructive"
                disabled={!!blocker || create.isPending}
                onClick={() => setShowOverride(true)}
              >
                <Lock className="mr-1.5 h-4 w-4" /> {create.isPending ? tx('Recording…') : tx('Approve & record return')}
              </Button>
              {blocker && <span className="text-xs text-muted-foreground">{blocker}</span>}
            </div>
          </div>
        </CardContent>
      </Card>

      <OverrideDialog
        open={showOverride}
        kind="manual_refund"
        title={tx('Manager approval for return')}
        onClose={() => setShowOverride(false)}
        onVerified={(result) => {
          setShowOverride(false);
          if (result) submit(result.managerId, result.pin);
        }}
      />
    </>
  );
}

export function NewReturnPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const invoiceId = params.get('invoiceId') ?? undefined;
  const { data: invoice, isLoading, isError } = useReturnableInvoice(invoiceId);

  const pick = (id: string | null) => setParams(id ? { invoiceId: id } : {}, { replace: false });

  return (
    <div className="mx-auto max-w-4xl space-y-4">
      <ListPageHeader
        icon={Undo2}
        title={tx('New Sales Return')}
        description={tx('Return goods or meals from a retail or café/restaurant sale. Requires manager approval.')}
        actions={
          <Button variant="outline" onClick={() => navigate('/returns')}>
            <ArrowLeft className="mr-1.5 h-4 w-4" /> {tx('Returns')}
          </Button>
        }
      />
      {!invoiceId && <InvoicePicker onPick={pick} />}
      {invoiceId && isLoading && <div className="text-sm text-muted-foreground">{tx('Loading...')}</div>}
      {invoiceId && isError && (
        <div className="space-y-2 text-sm text-muted-foreground">
          <p>{tx('Sale not found.')}</p>
          <Button variant="outline" size="sm" onClick={() => pick(null)}>{tx('Find another sale')}</Button>
        </div>
      )}
      {invoice && <ReturnForm key={invoice.id} invoice={invoice} onChangeSale={() => pick(null)} />}
    </div>
  );
}

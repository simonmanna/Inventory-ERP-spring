import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import {
  RFQ_STATUS_VARIANT,
  useAwardRfq,
  useRecordQuotation,
  useRfq,
  useRfqAction,
  useRfqComparison,
  type RfqDetail,
} from '@/features/procurement/api';
import { api } from '@/lib/api';
import { date, money, statusLabel } from '@/lib/format';
import { apiMessage, notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const selectClass = 'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm';

function QuotationDialog({ rfq, open, onClose }: { rfq: RfqDetail; open: boolean; onClose: () => void }) {
  const record = useRecordQuotation(rfq.id);
  const [partnerId, setPartnerId] = useState('');
  const [reference, setReference] = useState('');
  const [currencyCode, setCurrencyCode] = useState(rfq.currencyCode);
  const [exchangeRate, setExchangeRate] = useState('1');
  const [validUntil, setValidUntil] = useState('');
  const [leadTimeDays, setLeadTimeDays] = useState('');
  const [paymentTerms, setPaymentTerms] = useState('');
  const [prices, setPrices] = useState<Record<string, string>>({});

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const lines = rfq.lines
      .filter((l) => (prices[l.id] ?? '') !== '')
      .map((l) => ({ rfqLineId: l.id, unitPrice: Number(prices[l.id]) }));
    if (lines.length === 0) {
      notify.error(tx('Enter at least one price'));
      return;
    }
    try {
      await record.mutateAsync({
        partnerId,
        reference: reference || undefined,
        currencyCode,
        exchangeRate: Number(exchangeRate) || 1,
        validUntil: validUntil || undefined,
        leadTimeDays: leadTimeDays === '' ? undefined : Number(leadTimeDays),
        paymentTerms: paymentTerms || undefined,
        lines,
      });
      setPrices({});
      setPartnerId('');
      onClose();
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not save the quotation')));
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{tx('Record quotation')}</DialogTitle>
          <DialogDescription>{tx('Leave a price empty for an item the supplier did not quote.')}</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="space-y-3">
          <div className="space-y-2">
            <Label htmlFor="quoteSupplier">{tx('Supplier')}</Label>
            <select
              id="quoteSupplier"
              className={selectClass}
              value={partnerId}
              onChange={(e) => setPartnerId(e.target.value)}
              required
            >
              <option value="">{tx('Select supplier')}</option>
              {rfq.suppliers.map((s) => <option key={s.partnerId} value={s.partnerId}>{s.partnerName}</option>)}
            </select>
          </div>
          <div className="grid gap-3 sm:grid-cols-3">
            <div className="space-y-2">
              <Label htmlFor="quoteRef">{tx('Reference')}</Label>
              <Input id="quoteRef" value={reference} onChange={(e) => setReference(e.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="quoteCurrency">{tx('Currency')}</Label>
              <Input id="quoteCurrency" value={currencyCode} onChange={(e) => setCurrencyCode(e.target.value)} required />
            </div>
            <div className="space-y-2">
              <Label htmlFor="quoteRate">{tx('Exchange rate')}</Label>
              <Input
                id="quoteRate"
                type="number"
                step="any"
                min="0.00000001"
                value={exchangeRate}
                onChange={(e) => setExchangeRate(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="quoteValid">{tx('Valid until')}</Label>
              <Input id="quoteValid" type="date" value={validUntil} onChange={(e) => setValidUntil(e.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="quoteLead">{tx('Lead time (days)')}</Label>
              <Input
                id="quoteLead"
                type="number"
                min="0"
                value={leadTimeDays}
                onChange={(e) => setLeadTimeDays(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="quoteTerms">{tx('Payment terms')}</Label>
              <Input id="quoteTerms" value={paymentTerms} onChange={(e) => setPaymentTerms(e.target.value)} />
            </div>
          </div>
          {rfq.lines.map((l) => (
            <div key={l.id} className="grid grid-cols-3 items-center gap-2">
              <Label htmlFor={`price-${l.id}`} className="col-span-2 text-sm font-normal">
                {l.description} × {Number(l.quantity)}
              </Label>
              <Input
                id={`price-${l.id}`}
                type="number"
                step="any"
                min="0"
                aria-label={tx('Unit price')}
                value={prices[l.id] ?? ''}
                onChange={(e) => setPrices((p) => ({ ...p, [l.id]: e.target.value }))}
              />
            </div>
          ))}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
            <Button type="submit" disabled={record.isPending}>{tx('Save')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** One RFQ: its request, the recorded quotes side by side, and the award. */
export function RfqDetailPage() {
  const { id } = useParams();
  const { data: rfq, isLoading, isError } = useRfq(id);
  const hasQuotes = (rfq?.quotations.length ?? 0) > 0;
  const comparison = useRfqComparison(id, hasQuotes);
  const action = useRfqAction();
  const award = useAwardRfq(id ?? '');
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.procurement.rfq.manage);
  const canAward = has(PERMISSIONS.procurement.rfq.award);

  const [quoteOpen, setQuoteOpen] = useState(false);
  const [awarding, setAwarding] = useState<string | null>(null);
  const [warehouseId, setWarehouseId] = useState('');
  const warehouses = useQuery({
    queryKey: ['warehouses'],
    queryFn: async () =>
      (await api.get<{ data: { id: string; name: string }[] }>('/inventory/locations?type=warehouse')).data.data,
    enabled: awarding !== null,
  });

  if (isLoading) return <div className="text-sm text-muted-foreground">{tx('Loading...')}</div>;
  if (isError || !rfq) return <div className="text-sm text-destructive">{tx('Could not load this RFQ.')}</div>;

  const run = async (next: 'send' | 'cancel') => {
    try {
      await action.mutateAsync({ id: rfq.id, action: next });
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not update the RFQ')));
    }
  };

  const confirmAward = async () => {
    if (!awarding) return;
    try {
      const result = await award.mutateAsync({ quotationId: awarding, warehouseId });
      notify.success(tx('Purchase order {{number}} created', { number: result.purchaseOrder.orderNumber }));
      setAwarding(null);
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not award this quotation')));
    }
  };

  const data = comparison.data;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-semibold">{rfq.rfqNumber}</h1>
            <Badge variant={RFQ_STATUS_VARIANT[rfq.status]}>{statusLabel(rfq.status)}</Badge>
          </div>
          <p className="text-sm text-muted-foreground">
            {rfq.title}{rfq.dueDate ? ` · ${date(rfq.dueDate)}` : ''}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" asChild><Link to="/procurement/rfqs">{tx('Back')}</Link></Button>
          {rfq.purchaseOrderId && (
            <Button variant="outline" asChild>
              <Link to={`/procurement/purchase-orders/${rfq.purchaseOrderId}`}>{tx('View purchase order')}</Link>
            </Button>
          )}
          {canManage && rfq.status === 'draft' && (
            <>
              <Button variant="outline" asChild><Link to={`/procurement/rfqs/${rfq.id}/edit`}>{tx('Edit')}</Link></Button>
              <Button onClick={() => void run('send')} disabled={action.isPending}>{tx('Mark as sent')}</Button>
            </>
          )}
          {canManage && rfq.status === 'sent' && (
            <Button onClick={() => setQuoteOpen(true)}>{tx('Record quotation')}</Button>
          )}
          {canManage && (rfq.status === 'draft' || rfq.status === 'sent') && (
            <Button variant="outline" onClick={() => void run('cancel')} disabled={action.isPending}>
              {tx('Cancel RFQ')}
            </Button>
          )}
        </div>
      </div>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Requested items')}</CardTitle></CardHeader>
        <CardContent className="space-y-1 text-sm">
          {rfq.lines.map((l) => (
            <div key={l.id} className="flex justify-between gap-3">
              <span>{l.description}</span>
              <span className="text-muted-foreground">{Number(l.quantity)}</span>
            </div>
          ))}
          <p className="pt-2 text-xs text-muted-foreground">
            {tx('Invited')}: {rfq.suppliers.map((s) => s.partnerName).join(', ') || '-'}
          </p>
        </CardContent>
      </Card>

      {data && data.quotations.length > 0 && (
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Quotation comparison')}</CardTitle></CardHeader>
          <CardContent className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Item')}</TableHead>
                  <TableHead className="text-right">{tx('Qty')}</TableHead>
                  {data.quotations.map((q) => (
                    <TableHead key={q.quotationId} className="text-right">
                      {q.partnerName}
                      {q.quotationId === data.recommendedQuotationId && (
                        <Badge className="ml-2">{tx('Best')}</Badge>
                      )}
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.lines.map((l) => (
                  <TableRow key={l.rfqLineId}>
                    <TableCell>{l.description}</TableCell>
                    <TableCell className="text-right">{Number(l.quantity)}</TableCell>
                    {l.quotes.map((c) => (
                      <TableCell
                        key={c.quotationId}
                        className={`text-right ${c.cheapest ? 'font-semibold text-emerald-700 dark:text-emerald-400' : ''}`}
                      >
                        {c.baseUnitPrice === null ? '-' : money(c.baseUnitPrice)}
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
                <TableRow className="font-semibold">
                  <TableCell colSpan={2}>{tx('Total (base currency, net of tax)')}</TableCell>
                  {data.quotations.map((q) => <TableCell key={q.quotationId} className="text-right">{money(q.baseTotal)}</TableCell>)}
                </TableRow>
                <TableRow>
                  <TableCell colSpan={2}>{tx('Lead time (days)')}</TableCell>
                  {data.quotations.map((q) => <TableCell key={q.quotationId} className="text-right">{q.leadTimeDays ?? '-'}</TableCell>)}
                </TableRow>
                <TableRow>
                  <TableCell colSpan={2}>{tx('Valid until')}</TableCell>
                  {data.quotations.map((q) => (
                    <TableCell key={q.quotationId} className="text-right">
                      {q.validUntil ? date(q.validUntil) : '-'}
                      {q.expired && <Badge variant="destructive" className="ml-2">{tx('Expired')}</Badge>}
                    </TableCell>
                  ))}
                </TableRow>
                <TableRow>
                  <TableCell colSpan={2}>{tx('Lines quoted')}</TableCell>
                  {data.quotations.map((q) => (
                    <TableCell key={q.quotationId} className="text-right">{q.linesQuoted} / {data.lines.length}</TableCell>
                  ))}
                </TableRow>
                {rfq.status === 'sent' && canAward && (
                  <TableRow>
                    <TableCell colSpan={2} />
                    {data.quotations.map((q) => (
                      <TableCell key={q.quotationId} className="text-right">
                        <Button size="sm" disabled={q.expired} onClick={() => setAwarding(q.quotationId)}>
                          {tx('Award')}
                        </Button>
                      </TableCell>
                    ))}
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

      <QuotationDialog rfq={rfq} open={quoteOpen} onClose={() => setQuoteOpen(false)} />

      <Dialog open={awarding !== null} onOpenChange={(o) => !o && setAwarding(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Award quotation')}</DialogTitle>
            <DialogDescription>
              {tx('This raises a purchase order for the lines this supplier quoted and closes the RFQ.')}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="awardWarehouse">{tx('Warehouse')}</Label>
            <select
              id="awardWarehouse"
              className={selectClass}
              value={warehouseId}
              onChange={(e) => setWarehouseId(e.target.value)}
            >
              <option value="">{tx('Select warehouse…')}</option>
              {(warehouses.data ?? []).map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
            </select>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAwarding(null)}>{tx('Cancel')}</Button>
            <Button onClick={() => void confirmAward()} disabled={!warehouseId || award.isPending}>{tx('Award')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

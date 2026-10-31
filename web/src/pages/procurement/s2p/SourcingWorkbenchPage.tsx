import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { FileQuestion, PackageSearch, ShoppingCart } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ListPageHeader, StatusPill } from '@/components/list';
import { api } from '@/lib/api';
import { errMsg, rfqApi, useOpenRequestLines, useOrderFromRequests, useSupplierOptions, type OpenRequestLine } from '@/features/procurement/api';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

interface Pick { qty: string; price: string }

/**
 * Sourcing workbench (ADR-PROCUREMENT-001 wave 3): every approved purchase-request line still to be ordered, most
 * urgent first. Tick lines, pick the supplier (suggested from the request or the catalogue), adjust quantity and price,
 * and create the order in one step. Lines can be split across suppliers; nothing can be ordered twice.
 */
export function SourcingWorkbenchPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const requestId = params.get('requestId') ?? undefined;
  const { data: lines = [], isLoading } = useOpenRequestLines(requestId);
  const { data: suppliers = [] } = useSupplierOptions();
  const warehouses = useQuery({
    queryKey: ['warehouses'],
    queryFn: async () => (await api.get<{ data: Array<{ id: string; name: string; code: string }> }>('/inventory/locations?type=warehouse')).data.data ?? [],
  });
  const order = useOrderFromRequests();
  const [picked, setPicked] = useState<Record<string, Pick>>({});
  const [partnerId, setPartnerId] = useState('');
  const [warehouseId, setWarehouseId] = useState('');
  const [expected, setExpected] = useState('');
  const [paymentType, setPaymentType] = useState<'credit' | 'cash'>('credit');

  const selected = useMemo(() => lines.filter((l) => picked[l.id]), [lines, picked]);
  useEffect(() => {
    if (!partnerId && selected[0]?.suggestedSupplierId) setPartnerId(selected[0].suggestedSupplierId);
  }, [selected, partnerId]);
  useEffect(() => {
    if (!warehouseId && warehouses.data?.length) setWarehouseId(warehouses.data[0].id);
  }, [warehouses.data, warehouseId]);

  const toggle = (l: OpenRequestLine) => {
    setPicked((p) => {
      const next = { ...p };
      if (next[l.id]) delete next[l.id];
      else next[l.id] = { qty: l.openQuantity, price: l.estimatedUnitPrice ?? l.lastPrice?.unitPrice ?? '0' };
      return next;
    });
  };

  const total = selected.reduce((s, l) => s + Number(picked[l.id].qty || 0) * Number(picked[l.id].price || 0), 0);

  const [rfqBusy, setRfqBusy] = useState(false);
  const requestQuotes = async () => {
    if (!selected.length) return;
    setRfqBusy(true);
    try {
      const rfq = await rfqApi.create({
        title: tx('Quotation for {{n}} line(s)', { n: selected.length }),
        requestLineIds: selected.map((l) => l.id),
        supplierIds: partnerId ? [partnerId] : [],
      });
      notify.success(tx('RFQ {{n}} created', { n: rfq.rfqNumber }));
      setPicked({});
      navigate(`/procurement/rfqs/${rfq.id}`);
    } catch (e) {
      notify.error(tx('Could not create the RFQ'), errMsg(e));
    } finally {
      setRfqBusy(false);
    }
  };

  const create = async () => {
    if (!partnerId || !warehouseId || !selected.length) return;
    try {
      const po = await order.mutateAsync({
        partnerId,
        warehouseId,
        paymentType,
        expectedDeliveryDate: expected || undefined,
        lines: selected.map((l) => ({ requestLineId: l.id, quantity: Number(picked[l.id].qty), unitPrice: Number(picked[l.id].price) })),
      });
      notify.success(tx('Purchase order {{n}} created', { n: po.orderNumber }));
      (po.warnings ?? []).forEach((w) => notify.warning(w));
      setPicked({});
      navigate(`/procurement/purchase-orders/${po.id}`);
    } catch (e) {
      notify.error(tx('Could not create the order'), errMsg(e));
    }
  };

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader
        title={tx('Sourcing workbench')}
        description={tx('Approved purchase requests waiting to be ordered. Tick the lines to buy from one supplier and create the order.')}
        icon={PackageSearch}
      />
      <Card>
        <CardContent className="grid gap-3 pt-6 md:grid-cols-5">
          <div className="md:col-span-2">
            <Label>{tx('Supplier')}</Label>
            <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={partnerId} onChange={(e) => setPartnerId(e.target.value)}>
              <option value="">{tx('Select…')}</option>
              {suppliers.map((s) => (
                <option key={s.id} value={s.id}>{s.name}{s.lifecycleStatus && s.lifecycleStatus !== 'approved' ? ` (${s.lifecycleStatus})` : ''}</option>
              ))}
            </select>
          </div>
          <div>
            <Label>{tx('Receive into')}</Label>
            <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
              {(warehouses.data ?? []).map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
            </select>
          </div>
          <div>
            <Label>{tx('Expected delivery')}</Label>
            <Input type="date" value={expected} onChange={(e) => setExpected(e.target.value)} />
          </div>
          <div>
            <Label>{tx('Payment')}</Label>
            <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={paymentType} onChange={(e) => setPaymentType(e.target.value as 'credit' | 'cash')}>
              <option value="credit">{tx('On credit')}</option>
              <option value="cash">{tx('Cash')}</option>
            </select>
          </div>
          <div className="flex items-end justify-between gap-3 md:col-span-5">
            <div className="text-sm text-muted-foreground">
              {tx('{{n}} line(s) selected', { n: selected.length })} · {tx('Total')}: <span className="font-semibold text-foreground">{money(total)}</span>
            </div>
            <div className="flex gap-2">
              <Button variant="outline" onClick={requestQuotes} disabled={!selected.length || rfqBusy}>
                <FileQuestion className="mr-1 h-4 w-4" /> {tx('Request quotations')}
              </Button>
              <Button onClick={create} disabled={!selected.length || !partnerId || !warehouseId || order.isPending}>
                <ShoppingCart className="mr-1 h-4 w-4" /> {tx('Create purchase order')}
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead className="w-8" />
              <TableHead>{tx('Request')}</TableHead>
              <TableHead>{tx('Item')}</TableHead>
              <TableHead>{tx('Needed by')}</TableHead>
              <TableHead className="text-right">{tx('Open qty')}</TableHead>
              <TableHead className="w-28">{tx('Order qty')}</TableHead>
              <TableHead className="w-32">{tx('Unit price')}</TableHead>
              <TableHead>{tx('Suggested supplier')}</TableHead>
              <TableHead>{tx('Last price')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {isLoading && <TableRow><TableCell colSpan={9} className="text-muted-foreground">{tx('Loading…')}</TableCell></TableRow>}
              {!isLoading && !lines.length && (
                <TableRow><TableCell colSpan={9} className="py-8 text-center text-muted-foreground">{tx('Nothing to order — every approved request is fully ordered.')}</TableCell></TableRow>
              )}
              {lines.map((l) => {
                const p = picked[l.id];
                const due = l.requiredDate ?? l.neededBy;
                const late = due && due.slice(0, 10) < new Date().toISOString().slice(0, 10);
                return (
                  <TableRow key={l.id} className={p ? 'bg-primary/5' : ''}>
                    <TableCell><input type="checkbox" aria-label={tx('Select line')} checked={!!p} onChange={() => toggle(l)} /></TableCell>
                    <TableCell className="font-mono text-xs">{l.requestNumber}</TableCell>
                    <TableCell>{l.productName ?? l.description}<div className="text-xs text-muted-foreground">{l.productCode ?? l.demandSource}</div></TableCell>
                    <TableCell>{due ? <StatusPill tone={late ? 'danger' : 'neutral'}>{date(due)}</StatusPill> : '—'}</TableCell>
                    <TableCell className="text-right">{Number(l.openQuantity)}</TableCell>
                    <TableCell>
                      <Input disabled={!p} type="number" min={0} max={Number(l.openQuantity)} value={p?.qty ?? ''}
                        onChange={(e) => setPicked({ ...picked, [l.id]: { ...p!, qty: e.target.value } })} />
                    </TableCell>
                    <TableCell>
                      <Input disabled={!p} type="number" min={0} value={p?.price ?? ''}
                        onChange={(e) => setPicked({ ...picked, [l.id]: { ...p!, price: e.target.value } })} />
                    </TableCell>
                    <TableCell>{l.suggestedSupplier?.name ?? '—'}</TableCell>
                    <TableCell className="text-xs">{l.lastPrice ? `${money(Number(l.lastPrice.unitPrice), l.lastPrice.currencyCode)} · ${l.lastPrice.partnerName}` : '—'}</TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}

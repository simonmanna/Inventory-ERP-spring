import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { api } from '@/lib/api';
import { usePartners } from '@/features/partners/api';
import { errMsg, useSaveShipment, useShipment, useShipmentReceipt, useShipments } from '@/features/costing/api';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const STATUSES = ['open', 'in_transit', 'arrived', 'closed'] as const;

/** Import shipments (ADR-S007): group partial deliveries and their landed cost vouchers. */
export function ShipmentsPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canEdit = has(PERMISSIONS.landedCost.create);
  const { data: shipments = [] } = useShipments();
  const [selected, setSelected] = useState<string>();
  const { data: detail } = useShipment(selected);
  const save = useSaveShipment();
  const receipt = useShipmentReceipt();
  const partners = usePartners({ page: 1, pageSize: 500 });
  const grns = useQuery({
    queryKey: ['goods-receipts', 'for-shipment'],
    queryFn: async () => (await api.get<any>('/procurement/goods-receipts', { params: { pageSize: 200 } })).data?.data ?? [],
  });
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ reference: '', supplierId: '', transportMode: 'sea', billOfLading: '', origin: '', eta: '' });
  const [grnId, setGrnId] = useState('');

  const create = async () => {
    try {
      const s = await save.mutateAsync({ ...form, supplierId: form.supplierId || undefined, eta: form.eta || undefined });
      setOpen(false);
      setSelected(s.id);
    } catch (e) {
      notify.error(tx('Could not save shipment'), errMsg(e));
    }
  };

  return (
    <div className="space-y-6 p-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Import shipments')}</h1>
          <p className="text-sm text-muted-foreground">{tx('One consignment, its partial deliveries and the landed costs raised against it.')}</p>
        </div>
        <div className="flex gap-2">
          <Link to="/procurement/landed-cost-vouchers"><Button variant="outline">{tx('Landed cost vouchers')}</Button></Link>
          {canEdit && <Button onClick={() => setOpen(true)}><Plus className="mr-1 h-4 w-4" /> {tx('New shipment')}</Button>}
        </div>
      </div>

      <Card>
        <CardContent className="pt-6">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Shipment')}</TableHead>
                <TableHead>{tx('Reference / BL')}</TableHead>
                <TableHead>{tx('Supplier')}</TableHead>
                <TableHead>{tx('ETA')}</TableHead>
                <TableHead className="text-right">{tx('Receipts / vouchers')}</TableHead>
                <TableHead>{tx('Status')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {shipments.map((s) => (
                <TableRow key={s.id} className="cursor-pointer" onClick={() => setSelected(s.id)}>
                  <TableCell className="font-mono text-sm">{s.code}</TableCell>
                  <TableCell>{s.reference} {s.billOfLading ? <span className="text-xs text-muted-foreground">BL {s.billOfLading}</span> : null}</TableCell>
                  <TableCell>{s.supplierName ?? '—'}</TableCell>
                  <TableCell>{date(s.eta)}</TableCell>
                  <TableCell className="text-right">{s.receiptCount} / {s.voucherCount}</TableCell>
                  <TableCell><Badge variant="outline">{s.status}</Badge></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {detail && (
        <Card>
          <CardHeader className="flex flex-row items-center justify-between">
            <CardTitle>{detail.code} {detail.reference ? `· ${detail.reference}` : ''}</CardTitle>
            {canEdit && (
              <select className="rounded-md border p-2 text-sm" value={detail.status}
                onChange={(e) => save.mutate({ id: detail.id, status: e.target.value }, { onError: (er) => notify.error(tx('Failed'), errMsg(er)) })}>
                {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            )}
          </CardHeader>
          <CardContent className="space-y-4 text-sm">
            <div>
              <p className="mb-2 font-medium">{tx('Goods receipts')}</p>
              <Table>
                <TableBody>
                  {detail.receipts.map((r: any) => (
                    <TableRow key={r.id}>
                      <TableCell><Link className="font-mono text-xs text-primary hover:underline" to={`/procurement/goods-receipts/${r.id}`}>{r.receiptNumber}</Link></TableCell>
                      <TableCell>{date(r.receivedAt)}</TableCell>
                      <TableCell><Badge variant="outline">{r.status}</Badge></TableCell>
                      <TableCell className="text-right">{money(Number(r.stockValue))}</TableCell>
                      <TableCell className="text-right">
                        {canEdit && <Button size="icon" variant="ghost" onClick={() => receipt.mutate({ id: detail.id, goodsReceiptId: r.id, remove: true })}><Trash2 className="h-4 w-4" /></Button>}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              {canEdit && (
                <div className="mt-2 flex gap-2">
                  <select className="flex-1 rounded-md border p-2" value={grnId} onChange={(e) => setGrnId(e.target.value)}>
                    <option value="">{tx('Add a goods receipt…')}</option>
                    {(grns.data ?? []).map((g: any) => <option key={g.id} value={g.id}>{g.receiptNumber} · {g.status} · {date(g.receivedAt)}</option>)}
                  </select>
                  <Button size="sm" disabled={!grnId}
                    onClick={() => receipt.mutate({ id: detail.id, goodsReceiptId: grnId }, { onSuccess: () => setGrnId(''), onError: (e) => notify.error(tx('Failed'), errMsg(e)) })}>
                    {tx('Add')}
                  </Button>
                </div>
              )}
            </div>
            <div>
              <p className="mb-2 font-medium">{tx('Landed cost vouchers')}</p>
              {detail.vouchers.map((v: any) => (
                <div key={v.id} className="flex items-center gap-2">
                  <Link className="font-mono text-xs text-primary hover:underline" to={`/procurement/landed-cost-vouchers/${v.id}`}>{v.code}</Link>
                  <Badge variant="outline">{v.status}</Badge>
                  <span>{money(Number(v.totalAmount))}</span>
                </div>
              ))}
              {canEdit && <Link to="/procurement/landed-cost-vouchers/new"><Button size="sm" variant="outline" className="mt-2">{tx('New voucher for this shipment')}</Button></Link>}
            </div>
          </CardContent>
        </Card>
      )}

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{tx('New shipment')}</DialogTitle></DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1"><Label>{tx('Reference')}</Label><Input value={form.reference} onChange={(e) => setForm({ ...form, reference: e.target.value })} /></div>
            <div className="space-y-1">
              <Label>{tx('Supplier')}</Label>
              <select className="w-full rounded-md border p-2 text-sm" value={form.supplierId} onChange={(e) => setForm({ ...form, supplierId: e.target.value })}>
                <option value="">{tx('None')}</option>
                {(partners.data?.data ?? []).map((p: any) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1">
                <Label>{tx('Transport')}</Label>
                <select className="w-full rounded-md border p-2 text-sm" value={form.transportMode} onChange={(e) => setForm({ ...form, transportMode: e.target.value })}>
                  {['sea', 'air', 'road', 'rail'].map((m) => <option key={m} value={m}>{m}</option>)}
                </select>
              </div>
              <div className="space-y-1"><Label>{tx('ETA')}</Label><Input type="date" value={form.eta} onChange={(e) => setForm({ ...form, eta: e.target.value })} /></div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1"><Label>{tx('Bill of lading / AWB')}</Label><Input value={form.billOfLading} onChange={(e) => setForm({ ...form, billOfLading: e.target.value })} /></div>
              <div className="space-y-1"><Label>{tx('Origin')}</Label><Input value={form.origin} onChange={(e) => setForm({ ...form, origin: e.target.value })} /></div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>{tx('Cancel')}</Button>
            <Button onClick={create} disabled={save.isPending}>{tx('Create')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

export default ShipmentsPage;

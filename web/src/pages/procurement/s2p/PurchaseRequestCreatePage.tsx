import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, Save, Send, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { errMsg, useSupplierOptions } from '@/features/procurement/api';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

interface Line { productId: string; description: string; quantity: string; estimatedUnitPrice: string; requiredDate: string; preferredSupplierId: string; notes: string }

const blank = (): Line => ({ productId: '', description: '', quantity: '1', estimatedUnitPrice: '', requiredDate: '', preferredSupplierId: '', notes: '' });

/**
 * New purchase request (ADR-PROCUREMENT-001 wave 3): what is needed, how much, by when, a price estimate and an
 * optional suggested supplier per line. Save as draft or submit for approval straight away.
 */
export function PurchaseRequestCreatePage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [description, setDescription] = useState('');
  const [neededBy, setNeededBy] = useState('');
  const [lines, setLines] = useState<Line[]>([blank()]);
  const { data: suppliers = [] } = useSupplierOptions();
  const products = useQuery({
    queryKey: ['products', 'picker'],
    queryFn: async () => (await api.get<{ data: Array<{ id: string; name: string; code: string; costPrice?: string }> }>('/products?pageSize=200')).data.data ?? [],
  });

  const save = useMutation({
    mutationFn: async (submit: boolean) => {
      const body = {
        description: description || undefined,
        neededBy: neededBy || undefined,
        lines: lines.filter((l) => l.description.trim() && Number(l.quantity) > 0).map((l) => ({
          productId: l.productId || undefined,
          description: l.description.trim(),
          quantity: Number(l.quantity),
          estimatedUnitPrice: l.estimatedUnitPrice === '' ? undefined : Number(l.estimatedUnitPrice),
          requiredDate: l.requiredDate || undefined,
          preferredSupplierId: l.preferredSupplierId || undefined,
          notes: l.notes || undefined,
        })),
      };
      const created = (await api.post<{ id: string; requestNumber: string }>('/procurement/purchase-requests', body)).data;
      if (submit) await api.patch(`/procurement/purchase-requests/${created.id}/submit`);
      return { ...created, submitted: submit };
    },
    onSuccess: (pr) => {
      qc.invalidateQueries({ queryKey: ['purchase-requests'] });
      notify.success(pr.submitted ? tx('Request {{n}} submitted for approval', { n: pr.requestNumber }) : tx('Request {{n}} saved as draft', { n: pr.requestNumber }));
      navigate('/procurement/purchase-requests');
    },
    onError: (e) => notify.error(tx('Could not save the request'), errMsg(e)),
  });

  const set = (i: number, patch: Partial<Line>) => setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const pickProduct = (i: number, id: string) => {
    const p = products.data?.find((x) => x.id === id);
    set(i, { productId: id, description: p ? p.name : lines[i].description, estimatedUnitPrice: lines[i].estimatedUnitPrice || (p?.costPrice && Number(p.costPrice) > 0 ? String(Number(p.costPrice)) : '') });
  };
  const valid = lines.some((l) => l.description.trim() && Number(l.quantity) > 0);

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={tx('New purchase request')} description={tx('Ask for what you need. Once approved, buyers order it from the sourcing workbench.')} />
      <Card>
        <CardContent className="grid gap-3 pt-6 md:grid-cols-3">
          <div className="md:col-span-2"><Label>{tx('Purpose')}</Label><Input value={description} placeholder={tx('e.g. Crusher maintenance spares')} onChange={(e) => setDescription(e.target.value)} /></div>
          <div><Label>{tx('Needed by')}</Label><Input type="date" value={neededBy} onChange={(e) => setNeededBy(e.target.value)} /></div>
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>{tx('Items')}</CardTitle>
          <Button size="sm" variant="outline" onClick={() => setLines([...lines, blank()])}><Plus className="mr-1 h-4 w-4" />{tx('Add line')}</Button>
        </CardHeader>
        <CardContent className="space-y-4">
          {lines.map((l, i) => (
            <div key={i} className="grid gap-2 rounded-lg border p-3 md:grid-cols-12">
              <div className="md:col-span-3">
                <Label>{tx('Product')}</Label>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={l.productId} onChange={(e) => pickProduct(i, e.target.value)}>
                  <option value="">{tx('Free text / service')}</option>
                  {(products.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
              </div>
              <div className="md:col-span-3"><Label>{tx('Description')}</Label><Input value={l.description} onChange={(e) => set(i, { description: e.target.value })} /></div>
              <div className="md:col-span-1"><Label>{tx('Qty')}</Label><Input type="number" min={0} value={l.quantity} onChange={(e) => set(i, { quantity: e.target.value })} /></div>
              <div className="md:col-span-2"><Label>{tx('Est. unit price')}</Label><Input type="number" min={0} value={l.estimatedUnitPrice} onChange={(e) => set(i, { estimatedUnitPrice: e.target.value })} /></div>
              <div className="md:col-span-2"><Label>{tx('Required date')}</Label><Input type="date" value={l.requiredDate} onChange={(e) => set(i, { requiredDate: e.target.value })} /></div>
              <div className="flex items-end justify-end md:col-span-1">
                <Button size="icon" variant="ghost" aria-label={tx('Remove line')} disabled={lines.length === 1} onClick={() => setLines(lines.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
              </div>
              <div className="md:col-span-4">
                <Label>{tx('Suggested supplier')}</Label>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={l.preferredSupplierId} onChange={(e) => set(i, { preferredSupplierId: e.target.value })}>
                  <option value="">{tx('Let the buyer choose')}</option>
                  {suppliers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
              </div>
              <div className="md:col-span-8"><Label>{tx('Notes / specification')}</Label><Textarea rows={1} value={l.notes} onChange={(e) => set(i, { notes: e.target.value })} /></div>
            </div>
          ))}
        </CardContent>
      </Card>
      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={() => navigate('/procurement/purchase-requests')}>{tx('Cancel')}</Button>
        <Button variant="outline" disabled={!valid || save.isPending} onClick={() => save.mutate(false)}><Save className="mr-1 h-4 w-4" />{tx('Save draft')}</Button>
        <Button disabled={!valid || save.isPending} onClick={() => save.mutate(true)}><Send className="mr-1 h-4 w-4" />{tx('Submit for approval')}</Button>
      </div>
    </div>
  );
}

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useBranchChoices, useDepartmentChoices } from '@/features/approval-controls/api';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import { notify } from '@/lib/notify';

interface Line {
  description: string;
  quantity: string;
  estimatedUnitPrice: string;
}

const selectClass = 'h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm';
const emptyLine = (): Line => ({ description: '', quantity: '1', estimatedUnitPrice: '' });

export function PurchaseRequestCreatePage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const branches = useBranchChoices();
  const departments = useDepartmentChoices();
  const [description, setDescription] = useState('');
  const [neededBy, setNeededBy] = useState('');
  const [branchId, setBranchId] = useState('');
  const [departmentId, setDepartmentId] = useState('');
  const [lines, setLines] = useState<Line[]>([emptyLine()]);

  const setLine = (idx: number, patch: Partial<Line>) =>
    setLines((all) => all.map((l, i) => (i === idx ? { ...l, ...patch } : l)));

  const create = useMutation({
    mutationFn: async () => {
      const filled = lines.filter((l) => l.description.trim());
      if (!filled.length) throw new Error(tx('Add at least one line'));
      if (filled.some((l) => !(Number(l.quantity) > 0))) throw new Error(tx('Each line needs a quantity above zero'));
      return (await api.post('/procurement/purchase-requests', {
        description: description || undefined,
        neededBy: neededBy || undefined,
        branchId: branchId || undefined,
        departmentId: departmentId || undefined,
        lines: filled.map((l) => ({
          description: l.description,
          quantity: Number(l.quantity),
          estimatedUnitPrice: l.estimatedUnitPrice === '' ? undefined : Number(l.estimatedUnitPrice),
        })),
      })).data;
    },
    onSuccess: () => {
      notify.success(tx('Saved'));
      qc.invalidateQueries({ queryKey: ['purchase-requests'] });
      navigate('/procurement/purchase-requests');
    },
    onError: (e: any) => notify.error(e?.response?.data?.message ?? e.message),
  });

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">{tx('New PR')}</h1>
      <Card>
        <CardHeader><CardTitle>{tx('Details')}</CardTitle></CardHeader>
        <CardContent className="grid gap-3 md:grid-cols-2">
          <div className="space-y-1.5 md:col-span-2">
            <Label>{tx('Description')}</Label>
            <Input value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
          <div className="space-y-1.5">
            <Label>{tx('Needed by')}</Label>
            <Input type="date" value={neededBy} onChange={(e) => setNeededBy(e.target.value)} />
          </div>
          <div className="space-y-1.5">
            <Label>{tx('Branch')}</Label>
            <select className={selectClass} value={branchId} onChange={(e) => setBranchId(e.target.value)}>
              <option value="">{tx('Select a branch')}</option>
              {branches.data?.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </div>
          <div className="space-y-1.5">
            <Label>{tx('Department')}</Label>
            <select className={selectClass} value={departmentId} onChange={(e) => setDepartmentId(e.target.value)}>
              <option value="">{tx('All departments')}</option>
              {departments.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </div>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>{tx('Lines')}</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          {lines.map((l, idx) => (
            <div key={idx} className="grid gap-2 sm:grid-cols-[1fr_6rem_8rem_auto]">
              <Input placeholder={tx('Description')} value={l.description} onChange={(e) => setLine(idx, { description: e.target.value })} />
              <Input type="number" min={0} placeholder={tx('Qty')} value={l.quantity} onChange={(e) => setLine(idx, { quantity: e.target.value })} />
              <Input type="number" min={0} placeholder={tx('Unit price')} value={l.estimatedUnitPrice} onChange={(e) => setLine(idx, { estimatedUnitPrice: e.target.value })} />
              <Button variant="ghost" size="icon" disabled={lines.length === 1} onClick={() => setLines((all) => all.filter((_, i) => i !== idx))}>
                <Trash2 className="h-4 w-4 text-red-500" />
              </Button>
            </div>
          ))}
          <Button variant="outline" size="sm" onClick={() => setLines((all) => [...all, emptyLine()])}>
            <Plus className="mr-1 h-4 w-4" />{tx('Add line')}
          </Button>
        </CardContent>
      </Card>
      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={() => navigate('/procurement/purchase-requests')}>{tx('Cancel')}</Button>
        <Button disabled={create.isPending} onClick={() => create.mutate()}>{tx('Save')}</Button>
      </div>
    </div>
  );
}

import { useState } from 'react';
import { CheckCircle2, Plus, Rocket, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { useProductCategories } from '@/features/products/api';
import {
  errMsg,
  useCostPolicies,
  useCostPolicy,
  useCostPolicyAction,
  useCostPolicyPreview,
  useCreateCostPolicy,
  useCurrentCostPolicy,
  type CostMethod,
  type CostPolicy,
} from '@/features/costing/api';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const STATUS_VARIANT: Record<CostPolicy['status'], 'default' | 'secondary' | 'outline' | 'destructive'> = {
  draft: 'outline',
  approved: 'secondary',
  active: 'default',
  superseded: 'outline',
  cancelled: 'destructive',
};

const METHOD_HELP: Record<CostMethod, string> = {
  AVCO: tx('Weighted average — recomputed on every receipt; best for bulk, interchangeable goods (cement, aggregate).'),
  FIFO: tx('First in, first out — cost layers per receipt; best where receipt traceability matters (imported electronics).'),
  SPECIFIC: tx('Specific identification — serialised, individually identifiable items.'),
  STANDARD: tx('Standard cost — manufactured items; variances posted separately.'),
};

/**
 * Inventory costing policy (ADR-S007): the valuation method is an approved, effective-dated accounting policy,
 * not a per-product toggle. Draft → approve → activate (on or after the effective date); activation re-costs the
 * stock of products whose method changes, preserving total value.
 */
export function CostPoliciesPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.costingPolicy.manage);
  const canApprove = has(PERMISSIONS.costingPolicy.approve);
  const { data: policies = [] } = useCostPolicies();
  const { data: current } = useCurrentCostPolicy();
  const { data: categories = [] } = useProductCategories();
  const create = useCreateCostPolicy();
  const action = useCostPolicyAction();
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<string>();
  const { data: detail } = useCostPolicy(selected);
  const { data: preview } = useCostPolicyPreview(detail && ['draft', 'approved'].includes(detail.status) ? selected : undefined);
  const [form, setForm] = useState({ scopeType: 'organization', categoryId: '', method: 'AVCO' as CostMethod, effectiveFrom: new Date().toISOString().slice(0, 10), notes: '' });

  const save = async () => {
    try {
      await create.mutateAsync({
        scopeType: form.scopeType,
        categoryId: form.scopeType === 'category' ? form.categoryId : undefined,
        method: form.method,
        effectiveFrom: form.effectiveFrom,
        notes: form.notes.trim() || undefined,
      });
      notify.success(tx('Policy saved as draft'));
      setOpen(false);
    } catch (e) {
      notify.error(tx('Could not save policy'), errMsg(e));
    }
  };

  const act = async (id: string, a: 'approve' | 'activate' | 'cancel') => {
    try {
      const p = await action.mutateAsync({ id, action: a });
      notify.success(a === 'activate' && p.migrationSummary
        ? tx('Activated — {{n}} product(s) re-costed, value difference {{d}}', { n: p.migrationSummary.products, d: money(Number(p.migrationSummary.difference)) })
        : tx('Policy {{a}}d', { a }));
      setSelected(id);
    } catch (e) {
      notify.error(tx('Action failed'), errMsg(e));
    }
  };

  return (
    <div className="space-y-6 p-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Inventory costing policy')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Choose weighted average or FIFO as an approved accounting policy. A method change takes effect on its date and re-costs stock without changing its total value.')}
          </p>
        </div>
        {canManage && <Button onClick={() => setOpen(true)}><Plus className="mr-1 h-4 w-4" /> {tx('New policy')}</Button>}
      </div>

      <Card>
        <CardHeader><CardTitle>{tx('In force')}</CardTitle></CardHeader>
        <CardContent className="space-y-2 text-sm">
          {!current?.governed && (
            <p className="text-muted-foreground">
              {tx('No organisation policy is active: new products default to {{m}} and the method can be set per product.', { m: current?.defaultMethod ?? 'AVCO' })}
            </p>
          )}
          {(current?.active ?? []).map((p) => (
            <div key={p.id} className="flex items-center gap-2">
              <Badge>{p.method}</Badge>
              <span>{p.scopeType === 'organization' ? tx('Whole organisation') : tx('Category: {{c}}', { c: p.categoryName ?? '' })}</span>
              <span className="text-muted-foreground">{tx('since {{d}}', { d: date(p.effectiveFrom) })} · {p.code} v{p.version}</span>
            </div>
          ))}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>{tx('Policies')}</CardTitle></CardHeader>
        <CardContent>
          {!policies.length ? <p className="text-sm text-muted-foreground">{tx('No policies yet.')}</p> : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Code')}</TableHead>
                  <TableHead>{tx('Scope')}</TableHead>
                  <TableHead>{tx('Method')}</TableHead>
                  <TableHead>{tx('Effective')}</TableHead>
                  <TableHead>{tx('Status')}</TableHead>
                  <TableHead className="text-right">{tx('Actions')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {policies.map((p) => (
                  <TableRow key={p.id} className="cursor-pointer" onClick={() => setSelected(p.id)}>
                    <TableCell className="font-mono text-xs">{p.code} v{p.version}</TableCell>
                    <TableCell>{p.scopeType === 'organization' ? tx('Organisation') : p.categoryName}</TableCell>
                    <TableCell><Badge variant="secondary">{p.method}</Badge></TableCell>
                    <TableCell>{date(p.effectiveFrom)}</TableCell>
                    <TableCell><Badge variant={STATUS_VARIANT[p.status]}>{p.status}</Badge></TableCell>
                    <TableCell className="space-x-1 text-right" onClick={(e) => e.stopPropagation()}>
                      {canApprove && p.status === 'draft' && (
                        <Button size="sm" variant="outline" onClick={() => act(p.id, 'approve')}><CheckCircle2 className="mr-1 h-3 w-3" /> {tx('Approve')}</Button>
                      )}
                      {canApprove && p.status === 'approved' && (
                        <Button size="sm" onClick={() => act(p.id, 'activate')}><Rocket className="mr-1 h-3 w-3" /> {tx('Activate')}</Button>
                      )}
                      {canManage && (p.status === 'draft' || p.status === 'approved') && (
                        <Button size="sm" variant="ghost" onClick={() => act(p.id, 'cancel')}><XCircle className="h-4 w-4" /></Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {detail && (
        <Card>
          <CardHeader><CardTitle>{detail.code} · {detail.method}</CardTitle></CardHeader>
          <CardContent className="space-y-3 text-sm">
            <p className="text-muted-foreground">{METHOD_HELP[detail.method]}</p>
            {detail.notes && <p>{detail.notes}</p>}
            {preview && (
              <>
                <p className="font-medium">{tx('Activation will re-cost {{n}} product(s) worth {{v}}', { n: preview.products.length, v: money(Number(preview.totalValue)) })}</p>
                <ProductRows rows={preview.products} />
              </>
            )}
            {detail.migrationSummary && (
              <p>
                {tx('Re-costed {{p}} product(s) / {{q}} stock line(s): value {{b}} → {{a}} (difference {{d}})', {
                  p: detail.migrationSummary.products,
                  q: detail.migrationSummary.quants,
                  b: money(Number(detail.migrationSummary.valueBefore)),
                  a: money(Number(detail.migrationSummary.valueAfter)),
                  d: money(Number(detail.migrationSummary.difference)),
                })}
              </p>
            )}
            {!!detail.migrationLines?.length && <ProductRows rows={detail.migrationLines.map((m) => ({ ...m, name: m.productName, value: m.valueAfter }))} />}
          </CardContent>
        </Card>
      )}

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{tx('New costing policy')}</DialogTitle></DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1">
              <Label>{tx('Scope')}</Label>
              <select className="w-full rounded-md border p-2 text-sm" value={form.scopeType} onChange={(e) => setForm({ ...form, scopeType: e.target.value })}>
                <option value="organization">{tx('Whole organisation')}</option>
                <option value="category">{tx('One product category')}</option>
              </select>
            </div>
            {form.scopeType === 'category' && (
              <div className="space-y-1">
                <Label>{tx('Category')}</Label>
                <select className="w-full rounded-md border p-2 text-sm" value={form.categoryId} onChange={(e) => setForm({ ...form, categoryId: e.target.value })}>
                  <option value="">{tx('Select…')}</option>
                  {categories.map((c: any) => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select>
              </div>
            )}
            <div className="space-y-1">
              <Label>{tx('Method')}</Label>
              <select className="w-full rounded-md border p-2 text-sm" value={form.method} onChange={(e) => setForm({ ...form, method: e.target.value as CostMethod })}>
                <option value="AVCO">{tx('Weighted average (AVCO)')}</option>
                <option value="FIFO">{tx('First in, first out (FIFO)')}</option>
                <option value="SPECIFIC">{tx('Specific identification')}</option>
                <option value="STANDARD">{tx('Standard cost')}</option>
              </select>
              <p className="text-xs text-muted-foreground">{METHOD_HELP[form.method]}</p>
            </div>
            <div className="space-y-1">
              <Label>{tx('Effective from')}</Label>
              <Input type="date" value={form.effectiveFrom} onChange={(e) => setForm({ ...form, effectiveFrom: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label>{tx('Notes / approval reference')}</Label>
              <Input value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>{tx('Cancel')}</Button>
            <Button onClick={save} disabled={create.isPending || (form.scopeType === 'category' && !form.categoryId)}>{tx('Save draft')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function ProductRows({ rows }: { rows: any[] }) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>{tx('Product')}</TableHead>
          <TableHead>{tx('From → to')}</TableHead>
          <TableHead className="text-right">{tx('Quantity')}</TableHead>
          <TableHead className="text-right">{tx('Value')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.slice(0, 200).map((r, i) => (
          <TableRow key={r.id ?? r.productId ?? i}>
            <TableCell>{r.name}{r.sku ? <span className="ml-1 text-xs text-muted-foreground">{r.sku}</span> : null}</TableCell>
            <TableCell>{r.methodFrom} → {r.methodTo}</TableCell>
            <TableCell className="text-right">{Number(r.quantity).toLocaleString()}</TableCell>
            <TableCell className="text-right">{money(Number(r.value))}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

export default CostPoliciesPage;

import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { GitBranch, Lock, Plus, Save, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useBom } from '@/features/manufacturing/api';
import { useProductOptions } from '@/features/manufacturing/options';
import {
  useBomCost,
  useBomSubstitutes,
  useFreezeStandard,
  useReviseBom,
  useSaveSubstitutes,
  useStandardCosts,
  useWhereUsed,
} from '@/features/manufacturing/api-advanced';

const money = (s: unknown) => Number(s ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 4 });
const qty = (s: unknown) => Number(s ?? 0).toLocaleString(undefined, { maximumFractionDigits: 4 });
const errMsg = (e: any) => {
  const m = e?.response?.data?.message ?? e?.message;
  return Array.isArray(m) ? m.join(', ') : m;
};

/** One BOM: cost roll-up, standard costs, component substitutes, where-used and revision under an ECO. */
export function BomDetailPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canUpdate = hasPermission(PERMISSIONS.bom.update);
  const canCreate = hasPermission(PERMISSIONS.bom.create);
  const { data: bom } = useBom(id);
  const { data: cost } = useBomCost(id);
  const { data: standards } = useStandardCosts(id);
  const { data: lines } = useBomSubstitutes(id);
  const { data: used } = useWhereUsed((bom as any)?.outputProductId);
  const { data: products } = useProductOptions();
  const freeze = useFreezeStandard();
  const revise = useReviseBom();
  const saveSubs = useSaveSubstitutes();
  const [reason, setReason] = useState('');

  const productName = useMemo(() => {
    const m = new Map((products ?? []).map((p) => [p.id, `${p.code} · ${p.name}`]));
    return (pid: string) => m.get(pid) ?? pid;
  }, [products]);

  if (!bom) return <div className="p-6 text-sm text-muted-foreground">{tx('Loading…')}</div>;
  const b: any = bom;

  const doRevise = async () => {
    try {
      const r = await revise.mutateAsync({ bomId: id, title: `Revision of ${b.code}`, reason: reason || undefined });
      notify.success(tx('Draft version {{v}} created under {{eco}}.', { v: r.bom.version, eco: r.change.number }));
      navigate(`/manufacturing/boms/${r.bom.id}`);
    } catch (e: any) {
      notify.error(tx('Failed'), errMsg(e));
    }
  };

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{b.code} · {b.name}</h1>
          <p className="text-sm text-muted-foreground">
            {productName(b.outputProductId)} · {tx('v{{v}}', { v: b.version })} · {qty(b.outputQuantity)} {tx('per run')}{' '}
            <Badge variant="outline">{b.status}</Badge>
          </p>
        </div>
        <div className="flex items-end gap-2">
          {canCreate && (
            <>
              <Input className="w-56" placeholder={tx('Reason for change')} value={reason} onChange={(e) => setReason(e.target.value)} />
              <Button variant="outline" onClick={doRevise} disabled={revise.isPending}><GitBranch className="mr-2 h-4 w-4" /> {tx('Revise (ECO)')}</Button>
            </>
          )}
          {canUpdate && (
            <Button onClick={async () => {
              try {
                await freeze.mutateAsync(id);
                notify.success(tx('Standard cost frozen.'));
              } catch (e: any) {
                notify.error(tx('Failed'), errMsg(e));
              }
            }} disabled={freeze.isPending}>
              <Lock className="mr-2 h-4 w-4" /> {tx('Freeze standard cost')}
            </Button>
          )}
        </div>
      </div>

      <div className="grid gap-4 md:grid-cols-5">
        {(['materialCost', 'labourCost', 'subcontractCost', 'overheadCost', 'unitCost'] as const).map((k) => (
          <Card key={k}>
            <CardContent className="pt-6">
              <p className="text-xs text-muted-foreground">{tx(({ materialCost: 'Material', labourCost: 'Labour / machine', subcontractCost: 'Subcontract', overheadCost: 'Overhead', unitCost: 'Cost per unit' } as const)[k])}</p>
              <p className="text-xl font-semibold">{money(cost?.[k])}</p>
            </CardContent>
          </Card>
        ))}
      </div>

      <Card>
        <CardHeader><CardTitle>{tx('Cost roll-up (one run)')}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Type')}</TableHead>
                <TableHead>{tx('Item')}</TableHead>
                <TableHead className="text-right">{tx('Qty / minutes')}</TableHead>
                <TableHead className="text-right">{tx('Unit cost')}</TableHead>
                <TableHead className="text-right">{tx('Cost')}</TableHead>
                <TableHead>{tx('Source')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(cost?.breakdown ?? []).map((r: any, i: number) => (
                <TableRow key={i}>
                  <TableCell><Badge variant="outline">{r.kind}</Badge></TableCell>
                  <TableCell className="text-sm">{r.productId ? productName(r.productId) : r.operation}</TableCell>
                  <TableCell className="text-right">{qty(r.quantity ?? r.minutes)}</TableCell>
                  <TableCell className="text-right">{r.unitCost !== undefined ? money(r.unitCost) : '—'}</TableCell>
                  <TableCell className="text-right">{money(r.cost)}</TableCell>
                  <TableCell className="text-xs text-muted-foreground">{r.source ?? ''}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>{tx('Components & substitutes')}</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          {(lines ?? []).map((l: any) => (
            <SubstituteRow key={l.id} line={l} canEdit={canUpdate} products={products ?? []} productName={productName}
              onSave={async (subs) => {
                try {
                  await saveSubs.mutateAsync({ bomId: id, lineId: l.id, substitutes: subs });
                  notify.success(tx('Substitutes saved.'));
                } catch (e: any) {
                  notify.error(tx('Failed'), errMsg(e));
                }
              }} />
          ))}
        </CardContent>
      </Card>

      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader><CardTitle>{tx('Standard cost history')}</CardTitle></CardHeader>
          <CardContent>
            <Table>
              <TableHeader><TableRow><TableHead>{tx('Frozen')}</TableHead><TableHead>{tx('Version')}</TableHead><TableHead className="text-right">{tx('Per unit')}</TableHead><TableHead /></TableRow></TableHeader>
              <TableBody>
                {(standards ?? []).map((s: any) => (
                  <TableRow key={s.id}>
                    <TableCell className="text-xs">{new Date(s.computedAt).toLocaleString()}</TableCell>
                    <TableCell>v{s.bomVersion}</TableCell>
                    <TableCell className="text-right">{money(s.unitCost)}</TableCell>
                    <TableCell>{s.isCurrent && <Badge>{tx('current')}</Badge>}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle>{tx('Where used')}</CardTitle></CardHeader>
          <CardContent>
            {!used?.length ? <p className="text-sm text-muted-foreground">{tx('Not used in another active BOM.')}</p> : (
              <Table>
                <TableHeader><TableRow><TableHead>{tx('Level')}</TableHead><TableHead>{tx('BOM')}</TableHead><TableHead>{tx('Makes')}</TableHead></TableRow></TableHeader>
                <TableBody>
                  {used.map((u: any, i: number) => (
                    <TableRow key={i} className="cursor-pointer" onClick={() => navigate(`/manufacturing/boms/${u.bomId}`)}>
                      <TableCell>{u.level}</TableCell>
                      <TableCell className="font-mono text-xs">{u.code}</TableCell>
                      <TableCell>{u.outputName}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function SubstituteRow({ line, canEdit, products, productName, onSave }: {
  line: any;
  canEdit: boolean;
  products: Array<{ id: string; code: string; name: string }>;
  productName: (id: string) => string;
  onSave: (subs: Array<{ productId: string; ratio: number; priority: number }>) => Promise<void>;
}) {
  const [subs, setSubs] = useState<Array<{ productId: string; ratio: string }>>(
    (line.substitutes ?? []).map((s: any) => ({ productId: s.productId, ratio: String(Number(s.ratio)) })),
  );
  return (
    <div className="rounded border p-3">
      <div className="flex items-center justify-between">
        <div className="text-sm font-medium">
          {productName(line.componentProductId)} · {qty(line.quantity)}
          {Number(line.scrapPct) > 0 && <span className="text-muted-foreground"> (+{qty(line.scrapPct)}% {tx('scrap')})</span>}
        </div>
        {canEdit && (
          <Button size="sm" variant="ghost" onClick={() => setSubs((s) => [...s, { productId: '', ratio: '1' }])}>
            <Plus className="mr-1 h-3 w-3" /> {tx('Substitute')}
          </Button>
        )}
      </div>
      {subs.map((s, i) => (
        <div key={i} className="mt-2 flex items-center gap-2 pl-4">
          <Select value={s.productId} onValueChange={(v) => setSubs((xs) => xs.map((x, j) => (j === i ? { ...x, productId: v } : x)))} disabled={!canEdit}>
            <SelectTrigger className="w-72"><SelectValue placeholder={tx('Substitute product…')} /></SelectTrigger>
            <SelectContent>{products.filter((p) => p.id !== line.componentProductId).map((p) => <SelectItem key={p.id} value={p.id}>{p.code} · {p.name}</SelectItem>)}</SelectContent>
          </Select>
          <Label className="text-xs">{tx('Ratio')}</Label>
          <Input className="w-24" type="number" min="0" step="any" value={s.ratio} disabled={!canEdit}
            onChange={(e) => setSubs((xs) => xs.map((x, j) => (j === i ? { ...x, ratio: e.target.value } : x)))} />
          {canEdit && <Button size="icon" variant="ghost" onClick={() => setSubs((xs) => xs.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>}
        </div>
      ))}
      {canEdit && (
        <div className="mt-2 pl-4">
          <Button size="sm" variant="outline" onClick={() => onSave(subs.filter((s) => s.productId).map((s, k) => ({ productId: s.productId, ratio: Number(s.ratio) || 1, priority: k })))}>
            <Save className="mr-1 h-3 w-3" /> {tx('Save substitutes')}
          </Button>
        </div>
      )}
    </div>
  );
}

export default BomDetailPage;

import { useState } from 'react';
import { ArrowRight, CheckCircle2, GitBranch, Rocket, XCircle } from 'lucide-react';
import { Link } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useBomCompare, useEcoAction, useEcos, useVariances } from '@/features/manufacturing/api-advanced';

const errMsg = (e: any) => {
  const m = e?.response?.data?.message ?? e?.message;
  return Array.isArray(m) ? m.join(', ') : m;
};

/** Engineering change orders (review, apply, compare versions) and the standard-cost variance report. */
export function EngineeringChangesPage() {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canDecide = hasPermission(PERMISSIONS.bom.activate);
  const canReport = hasPermission(PERMISSIONS.production.report);
  const { data: ecos } = useEcos();
  const action = useEcoAction();
  const [selected, setSelected] = useState<any>();
  const { data: diff } = useBomCompare(selected?.bomId, selected?.newBomId);
  const { data: variances } = useVariances({}, canReport);

  const act = async (id: string, a: 'approve' | 'reject' | 'apply') => {
    try {
      await action.mutateAsync({ id, action: a });
      notify.success(tx('Change {{a}}d.', { a }));
    } catch (e: any) {
      notify.error(tx('Failed'), errMsg(e));
    }
  };

  return (
    <div className="space-y-6 p-6">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Engineering & costing')}</h1>
        <p className="text-sm text-muted-foreground">{tx('BOM revisions under change control, and how actual production compares with standard cost.')}</p>
      </div>

      {hasPermission(PERMISSIONS.plm.read) && (
        <Link
          to="/manufacturing/plm/changes"
          className="flex items-center gap-3 rounded-xl border border-primary/30 bg-primary/5 px-4 py-3 text-sm hover:bg-primary/10"
        >
          <GitBranch className="h-5 w-5 shrink-0 text-primary" />
          <span className="flex-1">
            <span className="font-medium">{tx('Product lifecycle management')}</span>{' '}
            <span className="text-muted-foreground">{tx('Staged approvals, impact analysis, routing and document changes: use the PLM change orders for the full workflow.')}</span>
          </span>
          <ArrowRight className="h-4 w-4 text-primary" />
        </Link>
      )}

      <Card>
        <CardHeader><CardTitle>{tx('Engineering change orders')}</CardTitle></CardHeader>
        <CardContent>
          {!ecos?.length ? <p className="text-sm text-muted-foreground">{tx('No changes yet. Use "Revise (ECO)" on a BOM.')}</p> : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('ECO')}</TableHead>
                  <TableHead>{tx('Title')}</TableHead>
                  <TableHead>{tx('Reason')}</TableHead>
                  <TableHead>{tx('Status')}</TableHead>
                  <TableHead className="text-right">{tx('Actions')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {ecos.map((e: any) => (
                  <TableRow key={e.id} className="cursor-pointer" onClick={() => setSelected(e)}>
                    <TableCell className="font-mono text-xs">{e.number}</TableCell>
                    <TableCell>{e.title}</TableCell>
                    <TableCell className="text-sm text-muted-foreground">{e.reason}</TableCell>
                    <TableCell><Badge variant="outline">{e.status}</Badge></TableCell>
                    <TableCell className="space-x-1 text-right" onClick={(ev) => ev.stopPropagation()}>
                      {canDecide && e.status === 'draft' && (
                        <Button size="sm" variant="outline" onClick={() => act(e.id, 'approve')}><CheckCircle2 className="mr-1 h-3 w-3" /> {tx('Approve')}</Button>
                      )}
                      {canDecide && e.status === 'approved' && (
                        <Button size="sm" onClick={() => act(e.id, 'apply')}><Rocket className="mr-1 h-3 w-3" /> {tx('Apply')}</Button>
                      )}
                      {canDecide && (e.status === 'draft' || e.status === 'approved') && (
                        <Button size="sm" variant="ghost" onClick={() => act(e.id, 'reject')}><XCircle className="h-4 w-4" /></Button>
                      )}
                      <a className="text-sm underline" href={`/manufacturing/boms/${e.newBomId}`}>{tx('New version')}</a>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {selected && diff && (
        <Card>
          <CardHeader><CardTitle>{tx('{{a}} v{{va}} → v{{vb}}', { a: selected.number, va: diff.from.version, vb: diff.to.version })}</CardTitle></CardHeader>
          <CardContent className="space-y-2 text-sm">
            {diff.header.map((h: any) => <div key={h.field}>{h.field}: <s>{String(h.from ?? '—')}</s> → <b>{String(h.to ?? '—')}</b></div>)}
            {diff.added.map((l: any) => <div key={l.id} className="text-green-700">+ {l.componentProductId} × {Number(l.quantity)}</div>)}
            {diff.removed.map((l: any) => <div key={l.id} className="text-red-700">− {l.componentProductId} × {Number(l.quantity)}</div>)}
            {diff.changed.map((c: any) => (
              <div key={c.componentProductId}>~ {c.componentProductId}: {Object.entries(c.changes).map(([k, v]: any) => `${k} ${v.from} → ${v.to}`).join('; ')}</div>
            ))}
            {!diff.header.length && !diff.added.length && !diff.removed.length && !diff.changed.length && (
              <p className="text-muted-foreground">{tx('No differences yet — edit the new draft version.')}</p>
            )}
          </CardContent>
        </Card>
      )}

      {canReport && (
        <Card>
          <CardHeader><CardTitle>{tx('Standard-cost variances')}</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            <div className="flex flex-wrap gap-2">
              {Object.entries(variances?.totals ?? {}).map(([k, v]: any) => (
                <Badge key={k} variant={Number(v) > 0 ? 'destructive' : 'secondary'}>{k}: {Number(v).toLocaleString()}</Badge>
              ))}
              {variances && <Badge variant="outline">{tx('Total')}: {Number(variances.total).toLocaleString()}</Badge>}
            </div>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Order')}</TableHead>
                  <TableHead>{tx('Kind')}</TableHead>
                  <TableHead className="text-right">{tx('Standard')}</TableHead>
                  <TableHead className="text-right">{tx('Actual')}</TableHead>
                  <TableHead className="text-right">{tx('Variance')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(variances?.rows ?? []).slice(0, 200).map((r: any) => (
                  <TableRow key={r.id}>
                    <TableCell><a className="font-mono text-xs underline" href={`/manufacturing/orders/${r.orderId}`}>{r.orderCode}</a></TableCell>
                    <TableCell>{r.kind}</TableCell>
                    <TableCell className="text-right">{Number(r.standardCost).toLocaleString()}</TableCell>
                    <TableCell className="text-right">{Number(r.actualCost).toLocaleString()}</TableCell>
                    <TableCell className={`text-right ${Number(r.amount) > 0 ? 'text-destructive' : ''}`}>{Number(r.amount).toLocaleString()}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <p className="text-xs text-muted-foreground">{tx('Positive = unfavourable. Analytic only; inventory stays at actual cost.')}</p>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

export default EngineeringChangesPage;

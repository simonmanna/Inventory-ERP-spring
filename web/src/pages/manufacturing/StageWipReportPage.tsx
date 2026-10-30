import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { RefreshCw, Save } from 'lucide-react';
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
import {
  type AllocationMethod,
  useAllocationRules,
  useSaveDefaultAllocationRule,
  useStageWipReport,
} from '@/features/manufacturing/api-stages';

const money = (s: unknown) => Number(s ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const qty = (s: unknown) => Number(s ?? 0).toLocaleString(undefined, { maximumFractionDigits: 4 });
const PAGE = 25;
const ALL = '__all';

/** ADR-S011: WIP per stage reconciled to the GL WIP account, intermediate stock, default joint-cost allocation. */
export function StageWipReportPage() {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const { data, isLoading, isError, refetch, isFetching } = useStageWipReport();
  const { data: rules } = useAllocationRules();
  const saveRule = useSaveDefaultAllocationRule();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState(ALL);
  const [page, setPage] = useState(0);
  const [method, setMethod] = useState<AllocationMethod>('quantity');

  useEffect(() => {
    if (rules) setMethod(rules.effectiveDefault.method);
  }, [rules]);
  useEffect(() => setPage(0), [search, status]);

  const rows = useMemo(() => (data?.stages ?? []).filter((r) =>
    (!search || r.orderCode.toLowerCase().includes(search.toLowerCase()) || r.name.toLowerCase().includes(search.toLowerCase()))
    && (status === ALL || r.status === status)), [data, search, status]);
  const pages = Math.max(1, Math.ceil(rows.length / PAGE));
  const visible = rows.slice(page * PAGE, page * PAGE + PAGE);

  const methods: Array<{ value: AllocationMethod; label: string }> = [
    { value: 'quantity', label: tx('By quantity') },
    { value: 'weight', label: tx('By weight') },
    { value: 'market_value', label: tx('By market value') },
    { value: 'fixed_pct', label: tx('By fixed %') },
  ];

  return (
    <div className="space-y-6 p-4 sm:p-6">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="text-xl font-semibold">{tx('Stage WIP')}</h1>
          <p className="text-sm text-muted-foreground">{tx('Work in progress per production stage, reconciled to the GL WIP account.')}</p>
        </div>
        <Button variant="outline" size="sm" onClick={() => refetch()} disabled={isFetching}><RefreshCw className="mr-1 h-3 w-3" /> {tx('Refresh')}</Button>
      </div>

      {isLoading && <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>}
      {isError && <p className="text-sm text-destructive">{tx('Could not load the WIP report.')}</p>}

      {data && (
        <>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Tile label={tx('Stage WIP')} value={money(data.totals.stageWip)} />
            <Tile label={tx('Single-step orders WIP')} value={money(data.totals.legacyOrdersWip)} />
            <Tile label={tx('Process costing WIP')} value={money(data.totals.processCostingWip)} />
            <Tile label={tx('GL WIP balance')} value={money(data.totals.glWipBalance)} />
          </div>
          <Card>
            <CardContent className="flex flex-wrap items-center gap-3 p-4 text-sm">
              <span>{tx('Expected WIP')}: <b>{money(data.totals.expectedWip)}</b></span>
              <span>{tx('Difference')}: <b>{money(data.totals.drift)}</b></span>
              <Badge variant={data.totals.balanced ? 'outline' : 'destructive'}>{data.totals.balanced ? tx('Reconciled') : tx('Not reconciled')}</Badge>
              <span className="text-muted-foreground">{tx('Intermediate stock in stage locations')}: {money(data.totals.intermediateStockValue)}</span>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex-row flex-wrap items-center justify-between gap-2">
              <CardTitle>{tx('Open stages')}</CardTitle>
              <div className="flex flex-wrap gap-2">
                <Input className="w-48" placeholder={tx('Search order or stage…')} value={search} onChange={(e) => setSearch(e.target.value)} />
                <Select value={status} onValueChange={setStatus}>
                  <SelectTrigger className="w-40"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value={ALL}>{tx('All statuses')}</SelectItem>
                    <SelectItem value="pending">{tx('Pending')}</SelectItem>
                    <SelectItem value="in_progress">{tx('In progress')}</SelectItem>
                    <SelectItem value="completed">{tx('Completed')}</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </CardHeader>
            <CardContent className="overflow-x-auto">
              {!rows.length ? <p className="text-sm text-muted-foreground">{tx('No open multi-stage orders.')}</p> : (
                <>
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>{tx('Order')}</TableHead>
                        <TableHead>{tx('Stage')}</TableHead>
                        <TableHead>{tx('Status')}</TableHead>
                        <TableHead className="text-right">{tx('Good')}</TableHead>
                        <TableHead className="text-right">{tx('Scrap')}</TableHead>
                        <TableHead className="text-right">{tx('Yield %')}</TableHead>
                        <TableHead className="text-right">{tx('WIP')}</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {visible.map((r) => (
                        <TableRow key={r.stageId}>
                          <TableCell><Link className="font-mono text-xs underline" to={`/manufacturing/orders/${r.orderId}`}>{r.orderCode}</Link></TableCell>
                          <TableCell>{r.sequence}. {r.name}</TableCell>
                          <TableCell><Badge variant="outline">{r.status.replace('_', ' ')}</Badge>{r.qcStatus !== 'not_required' && <Badge className="ml-1" variant="secondary">{tx('QC')} {r.qcStatus}</Badge>}</TableCell>
                          <TableCell className="text-right">{qty(r.qtyGood)}</TableCell>
                          <TableCell className="text-right">{qty(r.qtyScrap)}</TableCell>
                          <TableCell className="text-right">{r.yieldPct == null ? '—' : Number(r.yieldPct).toFixed(2)}</TableCell>
                          <TableCell className="text-right font-medium">{money(r.wipValue)}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                  {pages > 1 && (
                    <div className="mt-3 flex items-center justify-end gap-2 text-sm">
                      <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>{tx('Previous')}</Button>
                      <span>{page + 1} / {pages}</span>
                      <Button size="sm" variant="outline" disabled={page + 1 >= pages} onClick={() => setPage((p) => p + 1)}>{tx('Next')}</Button>
                    </div>
                  )}
                </>
              )}
            </CardContent>
          </Card>

          {data.intermediates.length > 0 && (
            <Card>
              <CardHeader><CardTitle>{tx('Intermediate stock in stage locations')}</CardTitle></CardHeader>
              <CardContent className="overflow-x-auto">
                <Table>
                  <TableHeader><TableRow><TableHead>{tx('Product')}</TableHead><TableHead>{tx('Location')}</TableHead><TableHead className="text-right">{tx('Quantity')}</TableHead><TableHead className="text-right">{tx('Value')}</TableHead></TableRow></TableHeader>
                  <TableBody>
                    {data.intermediates.map((i) => (
                      <TableRow key={`${i.productId}-${i.locationId}`}>
                        <TableCell>{i.productName}</TableCell>
                        <TableCell>{i.locationName}</TableCell>
                        <TableCell className="text-right">{qty(i.quantity)}</TableCell>
                        <TableCell className="text-right">{money(i.value)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>
          )}
        </>
      )}

      <Card>
        <CardHeader><CardTitle>{tx('Default joint-cost allocation')}</CardTitle></CardHeader>
        <CardContent className="flex flex-wrap items-end gap-3">
          <div className="space-y-1">
            <Label className="text-xs">{tx('Allocation method')}</Label>
            <Select value={method} onValueChange={(v) => setMethod(v as AllocationMethod)} disabled={!hasPermission(PERMISSIONS.bom.update)}>
              <SelectTrigger className="w-56"><SelectValue /></SelectTrigger>
              <SelectContent>{methods.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}</SelectContent>
            </Select>
          </div>
          {hasPermission(PERMISSIONS.bom.update) && (
            <Button size="sm" disabled={saveRule.isPending} onClick={async () => {
              try {
                await saveRule.mutateAsync({ method });
                notify.success(tx('Default allocation saved.'));
              } catch (e: any) {
                notify.error(tx('Save failed'), e?.response?.data?.message ?? e?.message);
              }
            }}><Save className="mr-1 h-3 w-3" /> {tx('Save')}</Button>
          )}
          <p className="w-full text-xs text-muted-foreground">
            {tx('Used by every BOM without its own rule.')} {rules?.bomOverrides.length ? `${tx('BOM overrides')}: ${rules.bomOverrides.length}` : ''}
          </p>
        </CardContent>
      </Card>
    </div>
  );
}

function Tile({ label, value }: { label: string; value: string }) {
  return (
    <Card>
      <CardContent className="p-4">
        <p className="text-xs text-muted-foreground">{label}</p>
        <p className="text-lg font-semibold">{value}</p>
      </CardContent>
    </Card>
  );
}

export default StageWipReportPage;

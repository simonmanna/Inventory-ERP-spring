import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import type { SupplierOption } from '@/features/procurement/api';
import { api } from '@/lib/api';
import { date, money } from '@/lib/format';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

interface Position {
  goodsReceiptLineId: string;
  receiptNumber: string | null;
  receivedAt: string;
  ageDays: number;
  partnerId: string | null;
  partnerName: string | null;
  purchaseOrderId: string | null;
  productId: string | null;
  productCode: string | null;
  productName: string | null;
  receivedQuantity: string;
  billedQuantity: string;
  openQuantity: string;
  unitCost: string;
  openValue: string;
  vouchered: boolean;
}

interface GrniReport {
  asOf: string;
  subledgerOpenValue: string;
  voucheredOpenValue: string;
  glBalance: string;
  difference: string;
  reconciled: boolean;
  ageBuckets: Array<{ label: string; value: string; count: number }>;
  positions: Position[];
}

const num = (v: unknown) => {
  const x = Number(v);
  return Number.isFinite(x) ? x : 0;
};

const BUCKET_LABELS: Record<string, () => string> = {
  '0-30': () => tx('Up to 30 days'),
  '31-60': () => tx('31 – 60 days'),
  '61-90': () => tx('61 – 90 days'),
  '90+': () => tx('Over 90 days'),
};

/**
 * GRNI reconciliation (review 2026-11-17, ease of use): goods received but not yet billed, aged, next to the GRNI
 * account they should add up to — with what to do about each kind of difference.
 */
export function GrniReconciliationPage() {
  const has = useAuthStore((st) => st.hasPermission);
  const [asOf, setAsOf] = useState('');
  const [minAge, setMinAge] = useState('');
  const [partnerId, setPartnerId] = useState('');
  const [partnerLabel, setPartnerLabel] = useState<string | null>(null);
  const [supplierSearch, setSupplierSearch] = useState('');
  const q = useDebouncedValue(supplierSearch, 250);
  const canSuppliers = has(PERMISSIONS.supplier.read);
  const suppliers = useQuery({
    queryKey: ['procurement-suppliers', 'options', q],
    enabled: canSuppliers,
    staleTime: 60_000,
    queryFn: async () => (await api.get<SupplierOption[]>('/procurement/suppliers/options', { params: { search: q || undefined } })).data,
  });
  const report = useQuery({
    queryKey: ['grni-reconciliation', asOf, minAge, partnerId],
    queryFn: async () =>
      (await api.get<GrniReport>('/procurement/reports/grni', {
        params: { asOf: asOf || undefined, minAgeDays: minAge || undefined, partnerId: partnerId || undefined },
      })).data,
  });
  const data = report.data;
  const open = useMemo(() => (data?.positions ?? []).filter((p) => !p.vouchered), [data]);
  const underPo = useMemo(() => (data?.positions ?? []).filter((p) => p.vouchered), [data]);
  const difference = num(data?.difference);

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Received, not yet billed (GRNI)')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Goods you have received but have not yet been billed for, valued at the receipt cost and aged — next to the GRNI account in the general ledger they should add up to.')}
        </p>
      </div>

      <Card>
        <CardContent className="flex flex-wrap items-end gap-3 py-4">
          <div className="grid gap-1">
            <Label className="text-xs text-muted-foreground">{tx('As at')}</Label>
            <Input type="date" className="w-44" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
          </div>
          {canSuppliers && (
            <div className="grid w-72 gap-1">
              <Label className="text-xs text-muted-foreground">{tx('Supplier')}</Label>
              <SearchableSelect
                value={partnerId}
                onValueChange={(v) => {
                  setPartnerId(v);
                  setPartnerLabel(suppliers.data?.find((x) => x.id === v)?.name ?? null);
                }}
                options={(suppliers.data ?? []).map((x) => ({ value: x.id, label: x.name, code: x.code ?? undefined }))}
                onQueryChange={setSupplierSearch}
                selectedLabel={partnerLabel}
                loading={suppliers.isFetching}
                placeholder={tx('All suppliers')}
                clearLabel={tx('All suppliers')}
              />
            </div>
          )}
          <div className="grid gap-1">
            <Label className="text-xs text-muted-foreground">{tx('Older than (days)')}</Label>
            <Input type="number" min={0} className="w-32" value={minAge} onChange={(e) => setMinAge(e.target.value)} />
          </div>
        </CardContent>
      </Card>

      {report.isLoading || !data ? (
        <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Card>
              <CardContent className="py-4">
                <div className="text-xs text-muted-foreground">{tx('Received, not billed (receipts)')}</div>
                <div className="text-xl font-semibold tabular-nums">{money(num(data.subledgerOpenValue))}</div>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="py-4">
                <div className="text-xs text-muted-foreground">{tx('GRNI account (general ledger)')}</div>
                <div className="text-xl font-semibold tabular-nums">{money(num(data.glBalance))}</div>
              </CardContent>
            </Card>
            <Card className={data.reconciled ? 'border-emerald-500/50' : 'border-destructive/50'}>
              <CardContent className="py-4">
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  {tx('Difference')}
                  {data.reconciled ? <Badge className="bg-emerald-600 text-white hover:bg-emerald-600">{tx('Agrees')}</Badge> : <Badge variant="destructive">{tx('Off')}</Badge>}
                </div>
                <div className={`text-xl font-semibold tabular-nums ${data.reconciled ? '' : 'text-destructive'}`}>{money(difference)}</div>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="py-4">
                <div className="text-xs text-muted-foreground">{tx('Received under purchase orders')}</div>
                <div className="text-xl font-semibold tabular-nums">{money(num(data.voucheredOpenValue))}</div>
                <div className="text-xs text-muted-foreground">{tx('Already in payables at receipt; listed apart, not part of the tie-out.')}</div>
              </CardContent>
            </Card>
          </div>

          {!data.reconciled && (
            <Card className="border-amber-500/50">
              <CardContent className="space-y-1 py-4 text-sm">
                <p className="font-medium">{tx('What a difference means')}</p>
                {difference > 0 ? (
                  <p>{tx('The GRNI account holds more than the open receipts: something was credited to GRNI without a receipt line — usually a manual journal, or a bill matched to the wrong receipt. Look for postings to the GRNI account that are not goods receipts or bills.')}</p>
                ) : (
                  <p>{tx('The open receipts are worth more than the GRNI account: a receipt line is open but its GRNI credit was cleared — usually a bill entered without matching the receipt, or a manual journal debiting GRNI. Match the bill to its receipt, or reverse the journal.')}</p>
                )}
              </CardContent>
            </Card>
          )}

          <Card>
            <CardHeader>
              <CardTitle>{tx('By age')}</CardTitle>
              <CardDescription>{tx('Old open receipts usually mean a bill that was never entered, or goods that will never be billed (a free replacement, a cancelled order).')}</CardDescription>
            </CardHeader>
            <CardContent className="grid gap-2 sm:grid-cols-4">
              {data.ageBuckets.map((b) => (
                <div key={b.label} className="rounded-md border p-3">
                  <div className="text-xs text-muted-foreground">{BUCKET_LABELS[b.label]?.() ?? b.label}</div>
                  <div className="font-semibold tabular-nums">{money(num(b.value))}</div>
                  <div className="text-xs text-muted-foreground">{tx('{{count}} line(s)', { count: b.count })}</div>
                </div>
              ))}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>{tx('Open receipt lines')}</CardTitle>
              <CardDescription>{tx('For each line: enter the supplier bill when it arrives, or reverse the receipt if it will never be billed.')}</CardDescription>
            </CardHeader>
            <CardContent>
              <PositionsTable rows={open} empty={tx('Every receipt in scope is fully billed.')} />
            </CardContent>
          </Card>

          {underPo.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle>{tx('Received under purchase orders, not yet billed')}</CardTitle>
                <CardDescription>{tx('These were posted to payables when received, so they are not in the GRNI account; the bill only confirms them.')}</CardDescription>
              </CardHeader>
              <CardContent>
                <PositionsTable rows={underPo} empty="" />
              </CardContent>
            </Card>
          )}
        </>
      )}
    </div>
  );
}

function PositionsTable({ rows, empty }: { rows: Position[]; empty: string }) {
  if (rows.length === 0) return <p className="text-sm text-muted-foreground">{empty}</p>;
  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{tx('Receipt')}</TableHead>
            <TableHead>{tx('Received')}</TableHead>
            <TableHead className="text-right">{tx('Age (days)')}</TableHead>
            <TableHead>{tx('Supplier')}</TableHead>
            <TableHead>{tx('Item')}</TableHead>
            <TableHead className="text-right">{tx('Received qty')}</TableHead>
            <TableHead className="text-right">{tx('Billed qty')}</TableHead>
            <TableHead className="text-right">{tx('Open qty')}</TableHead>
            <TableHead className="text-right">{tx('Unit cost')}</TableHead>
            <TableHead className="text-right">{tx('Open value')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((p) => (
            <TableRow key={p.goodsReceiptLineId}>
              <TableCell className="font-medium">
                <div>{p.receiptNumber ?? '—'}</div>
                {p.purchaseOrderId && (
                  <Link to={`/procurement/purchase-orders/${p.purchaseOrderId}`} className="text-xs font-normal text-primary hover:underline">{tx('Open the order')}</Link>
                )}
              </TableCell>
              <TableCell>{date(p.receivedAt)}</TableCell>
              <TableCell className={`text-right tabular-nums ${p.ageDays > 90 ? 'font-semibold text-destructive' : ''}`}>{p.ageDays}</TableCell>
              <TableCell>{p.partnerName ?? '—'}</TableCell>
              <TableCell>{p.productCode ? `${p.productCode} · ` : ''}{p.productName ?? '—'}</TableCell>
              <TableCell className="text-right tabular-nums">{num(p.receivedQuantity).toLocaleString()}</TableCell>
              <TableCell className="text-right tabular-nums">{num(p.billedQuantity).toLocaleString()}</TableCell>
              <TableCell className="text-right tabular-nums">{num(p.openQuantity).toLocaleString()}</TableCell>
              <TableCell className="text-right tabular-nums">{money(num(p.unitCost))}</TableCell>
              <TableCell className="text-right font-medium tabular-nums">{money(num(p.openValue))}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

export default GrniReconciliationPage;

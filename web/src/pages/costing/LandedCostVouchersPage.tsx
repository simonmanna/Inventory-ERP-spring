import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQueries, useQuery } from '@tanstack/react-query';
import { CheckCircle2, Plus, Send, Trash2, Undo2, Upload, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { api } from '@/lib/api';
import { usePartners } from '@/features/partners/api';
import { useAccounts } from '@/features/accounting/api';
import {
  errMsg,
  flattenTree,
  useCostCenterTree,
  useCostComponents,
  useLandedCostAction,
  useLandedCostPreview,
  useLandedCostVoucher,
  useLandedCostVouchers,
  useSaveLandedCostVoucher,
  useShipments,
  type AllocationMethod,
  type BillMode,
  type ChargeDraft,
  type Treatment,
} from '@/features/costing/api';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const STATUS_VARIANT: Record<string, 'default' | 'secondary' | 'outline' | 'destructive'> = {
  draft: 'outline', submitted: 'secondary', approved: 'secondary', posted: 'default', reversed: 'destructive', cancelled: 'destructive',
};

const METHODS: AllocationMethod[] = ['value', 'quantity', 'weight', 'volume', 'equal', 'manual'];
const METHOD_LABEL: Record<AllocationMethod, string> = {
  value: tx('By value'), quantity: tx('By quantity'), weight: tx('By weight'), volume: tx('By volume'), equal: tx('Equal'), manual: tx('Manual'),
};
const BILL_MODE_LABEL: Record<BillMode, string> = {
  generate: tx('Raise supplier bill on posting'), link: tx('Link existing supplier bill'), none: tx('No bill (clearing only)'),
};
const TREATMENT_LABEL: Record<Treatment, string> = {
  capitalize: tx('Add to inventory cost'), expense: tx('Expense'), recoverable_tax: tx('Recoverable tax'),
};

/** Landed cost vouchers (ADR-S007): list. */
export function LandedCostVouchersPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const [status, setStatus] = useState('');
  const { data: rows = [], isLoading } = useLandedCostVouchers(status ? { status } : undefined);
  return (
    <div className="space-y-6 p-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Landed cost vouchers')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Customs, freight, clearing and insurance added to the cost of the goods they brought in — across receipts and suppliers, allocated by value, quantity, weight, volume or manually.')}
          </p>
        </div>
        <div className="flex gap-2">
          <Link to="/procurement/shipments"><Button variant="outline">{tx('Shipments')}</Button></Link>
          <Link to="/procurement/landed-cost-reports"><Button variant="outline">{tx('Reports')}</Button></Link>
          {has(PERMISSIONS.landedCost.create) && <Link to="/procurement/landed-cost-vouchers/new"><Button><Plus className="mr-1 h-4 w-4" /> {tx('New voucher')}</Button></Link>}
        </div>
      </div>
      <Card>
        <CardContent className="space-y-3 pt-6">
          <select className="rounded-md border p-2 text-sm" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">{tx('All statuses')}</option>
            {Object.keys(STATUS_VARIANT).map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
          {isLoading ? <p className="text-sm text-muted-foreground">{tx('Loading…')}</p> : !rows.length ? (
            <p className="text-sm text-muted-foreground">{tx('No landed cost vouchers yet.')}</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Voucher')}</TableHead>
                  <TableHead>{tx('Date')}</TableHead>
                  <TableHead>{tx('Shipment')}</TableHead>
                  <TableHead className="text-right">{tx('Receipts / charges')}</TableHead>
                  <TableHead className="text-right">{tx('Total')}</TableHead>
                  <TableHead className="text-right">{tx('To inventory')}</TableHead>
                  <TableHead>{tx('Status')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((v) => (
                  <TableRow key={v.id}>
                    <TableCell><Link className="font-mono text-sm text-primary hover:underline" to={`/procurement/landed-cost-vouchers/${v.id}`}>{v.code}</Link></TableCell>
                    <TableCell>{date(v.date)}</TableCell>
                    <TableCell>{v.shipmentCode ?? '—'}</TableCell>
                    <TableCell className="text-right">{v.receiptCount} / {v.chargeCount}</TableCell>
                    <TableCell className="text-right">{money(Number(v.totalAmount))}</TableCell>
                    <TableCell className="text-right">{money(Number(v.capitalizedAmount))}</TableCell>
                    <TableCell><Badge variant={STATUS_VARIANT[v.status]}>{v.status}</Badge></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

type ChargeRow = ChargeDraft & { key: string; amountText: string; taxText: string; rateText: string; manualText: Record<string, string> };

const blankCharge = (): ChargeRow => ({ key: crypto.randomUUID(), amount: 0, amountText: '', taxText: '', rateText: '', manualText: {}, billMode: 'generate' });

/** Create or edit a draft voucher. */
export function LandedCostVoucherEditPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: existing } = useLandedCostVoucher(id);
  const { data: components = [] } = useCostComponents();
  const { data: shipments = [] } = useShipments();
  const { data: tree = [] } = useCostCenterTree();
  const centers = useMemo(() => flattenTree(tree), [tree]);
  const partners = usePartners({ page: 1, pageSize: 500 });
  const suppliers = (partners.data?.data ?? []).filter((p: any) => p.isSupplier !== false);
  const { data: accounts } = useAccounts();
  const grns = useQuery({
    queryKey: ['goods-receipts', 'posted-for-landed'],
    queryFn: async () => (await api.get<any>('/procurement/goods-receipts', { params: { status: 'posted', pageSize: 200 } })).data?.data ?? [],
  });
  const save = useSaveLandedCostVoucher();
  const postedBills = useQuery({
    queryKey: ['vendor-bills', 'for-landed-cost'],
    queryFn: async () => (await api.get<any>('/vendor-bills', { params: { pageSize: 200 } })).data?.data ?? [],
  });
  const linkableBills = (postedBills.data ?? []).filter((b: any) => b.status !== 'cancelled');

  const [shipmentId, setShipmentId] = useState('');
  const [receipts, setReceipts] = useState<string[]>([]);
  const [voucherDate, setVoucherDate] = useState(new Date().toISOString().slice(0, 10));
  const [costCenterId, setCostCenterId] = useState('');
  const [notes, setNotes] = useState('');
  const [charges, setCharges] = useState<ChargeRow[]>([blankCharge()]);

  useEffect(() => {
    if (!existing) return;
    setShipmentId(existing.shipmentId ?? '');
    setReceipts((existing.receipts ?? []).map((r: any) => r.id));
    setVoucherDate(String(existing.date).slice(0, 10));
    setCostCenterId(existing.costCenterId ?? '');
    setNotes(existing.notes ?? '');
    setCharges((existing.charges ?? []).map((c: any) => ({
      key: c.id, costComponentId: c.costComponentId ?? undefined, kind: c.kind, description: c.description ?? '', supplierId: c.supplierId ?? undefined,
      supplierInvoiceRef: c.supplierInvoiceRef ?? '', currencyCode: c.currencyCode ?? '', treatment: c.treatment, allocationMethod: c.allocationMethod,
      expenseAccountId: c.expenseAccountId ?? undefined, creditAccountId: c.creditAccountId ?? undefined, costCenterId: c.costCenterId ?? undefined,
      appliesToLineIds: c.appliesToLineIds ?? undefined, amount: Number(c.amount), amountText: String(Number(c.amount)), taxText: Number(c.taxAmount) ? String(Number(c.taxAmount)) : '',
      taxRecoverable: c.taxRecoverable ?? undefined,
      billMode: c.billMode ?? 'none', vendorBillId: c.vendorBillId ?? undefined, dueDate: c.dueDate ? String(c.dueDate).slice(0, 10) : undefined,
      invoiceDate: c.invoiceDate ? String(c.invoiceDate).slice(0, 10) : undefined,
      rateText: Number(c.exchangeRate) !== 1 ? String(Number(c.exchangeRate)) : '',
      manualText: Object.fromEntries(Object.entries(c.manualAllocations ?? {}).map(([k, v]) => [k, String(Number(v) / (Number(c.exchangeRate) || 1))])),
    })));
  }, [existing]);

  const shipment = shipments.find((s: any) => s.id === shipmentId);
  const selectedIds = receipts.length ? receipts : [];
  const lines = useQueries({
    queries: selectedIds.map((g) => ({
      queryKey: ['goods-receipts', g],
      queryFn: async () => (await api.get<any>(`/procurement/goods-receipts/${g}`)).data,
    })),
  });
  const receiptLines = lines.flatMap((q) => (q.data?.lines ?? []).map((l: any) => ({
    ...l, receiptNumber: q.data?.receiptNumber, goodsSupplierId: q.data?.partnerId ?? q.data?.partner?.id, goodsSupplierName: q.data?.partner?.name,
  })));
  // Goods suppliers on this voucher (requirement: one shipment may hold goods bought from several vendors).
  const goodsSuppliers = Array.from(new Map(receiptLines.filter((l: any) => l.goodsSupplierId)
    .map((l: any) => [l.goodsSupplierId, l.goodsSupplierName ?? l.goodsSupplierId])).entries());

  const patch = (key: string, p: Partial<ChargeRow>) => setCharges((cs) => cs.map((c) => (c.key === key ? { ...c, ...p } : c)));
  const pickComponent = (key: string, componentId: string) => {
    const comp = components.find((c) => c.id === componentId);
    patch(key, { costComponentId: componentId || undefined, treatment: comp?.treatment, allocationMethod: comp?.defaultAllocationMethod, kind: comp?.kind, description: comp?.name });
  };

  const submit = async () => {
    try {
      const body = {
        shipmentId: shipmentId || undefined,
        goodsReceiptIds: receipts.length ? receipts : undefined,
        date: voucherDate,
        notes: notes.trim() || undefined,
        costCenterId: costCenterId || undefined,
        charges: charges.filter((c) => Number(c.amountText) > 0).map((c) => ({
          costComponentId: c.costComponentId,
          kind: c.costComponentId ? undefined : c.kind ?? 'other',
          description: c.description?.trim() || undefined,
          supplierId: c.supplierId || undefined,
          supplierInvoiceRef: c.supplierInvoiceRef?.trim() || undefined,
          invoiceDate: c.invoiceDate || undefined,
          billMode: c.billMode ?? 'generate',
          vendorBillId: c.billMode === 'link' ? c.vendorBillId || undefined : undefined,
          dueDate: c.billMode === 'generate' ? c.dueDate || undefined : undefined,
          currencyCode: c.currencyCode?.trim() || undefined,
          exchangeRate: c.rateText ? Number(c.rateText) : undefined,
          amount: Number(c.amountText),
          taxAmount: c.taxText ? Number(c.taxText) : undefined,
          taxRecoverable: c.taxText && Number(c.taxText) ? c.taxRecoverable : undefined,
          treatment: c.treatment,
          allocationMethod: c.allocationMethod,
          expenseAccountId: c.expenseAccountId || undefined,
          creditAccountId: c.billMode === 'generate' ? undefined : c.creditAccountId || undefined,
          costCenterId: c.costCenterId || undefined,
          appliesToLineIds: c.appliesToLineIds?.length ? c.appliesToLineIds : undefined,
          manualAllocations: c.allocationMethod === 'manual'
            ? Object.fromEntries(Object.entries(c.manualText).filter(([, v]) => Number(v) > 0).map(([k, v]) => [k, Number(v)]))
            : undefined,
        })),
      };
      const v = await save.mutateAsync({ id, body });
      notify.success(tx('{{c}} saved', { c: v.code }));
      navigate(`/procurement/landed-cost-vouchers/${v.id}`);
    } catch (e) {
      notify.error(tx('Could not save voucher'), errMsg(e));
    }
  };

  const total = charges.reduce((s, c) => s + (Number(c.amountText) || 0) * (Number(c.rateText) || 1), 0);

  return (
    <div className="space-y-6 p-6">
      <h1 className="text-2xl font-semibold">{id ? tx('Edit {{c}}', { c: existing?.code ?? '' }) : tx('New landed cost voucher')}</h1>
      <Card>
        <CardHeader><CardTitle>{tx('Goods')}</CardTitle></CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-2">
          <div className="space-y-1">
            <Label>{tx('Shipment (optional — uses its receipts)')}</Label>
            <select className="w-full rounded-md border p-2 text-sm" value={shipmentId} onChange={(e) => setShipmentId(e.target.value)}>
              <option value="">{tx('None')}</option>
              {shipments.map((s: any) => <option key={s.id} value={s.id}>{s.code} {s.reference ? `· ${s.reference}` : ''}</option>)}
            </select>
            {shipment && !receipts.length && <p className="text-xs text-muted-foreground">{tx('{{n}} receipt(s) of this shipment will be used', { n: shipment.receiptCount })}</p>}
          </div>
          <div className="space-y-1">
            <Label>{tx('Posted goods receipts')}</Label>
            <div className="max-h-40 space-y-1 overflow-y-auto rounded-md border p-2 text-sm">
              {(grns.data ?? []).map((g: any) => (
                <label key={g.id} className="flex items-center gap-2">
                  <input type="checkbox" checked={receipts.includes(g.id)} onChange={(e) => setReceipts((r) => (e.target.checked ? [...r, g.id] : r.filter((x) => x !== g.id)))} />
                  <span className="font-mono text-xs">{g.receiptNumber}</span> <span className="text-muted-foreground">{g.partner?.name ?? ''} · {date(g.receivedAt)}</span>
                </label>
              ))}
            </div>
          </div>
          <div className="space-y-1"><Label>{tx('Voucher date')}</Label><Input type="date" value={voucherDate} onChange={(e) => setVoucherDate(e.target.value)} /></div>
          <div className="space-y-1">
            <Label>{tx('Cost centre (expensed share, e.g. import project)')}</Label>
            <select className="w-full rounded-md border p-2 text-sm" value={costCenterId} onChange={(e) => setCostCenterId(e.target.value)}>
              <option value="">{tx('None')}</option>
              {centers.map((c) => <option key={c.id} value={c.id}>{'  '.repeat(c.depth)}{c.code} · {c.name}</option>)}
            </select>
          </div>
          <div className="space-y-1 md:col-span-2"><Label>{tx('Notes')}</Label><Input value={notes} onChange={(e) => setNotes(e.target.value)} /></div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>{tx('Charges')}</CardTitle>
          <span className="text-sm text-muted-foreground">{tx('Total (base)')}: <b>{money(total)}</b></span>
        </CardHeader>
        <CardContent className="space-y-4">
          {charges.map((c, i) => (
            <div key={c.key} className="space-y-2 rounded-md border p-3">
              <div className="grid gap-2 md:grid-cols-6">
                <select className="rounded-md border p-2 text-sm md:col-span-2" value={c.costComponentId ?? ''} onChange={(e) => pickComponent(c.key, e.target.value)}>
                  <option value="">{tx('Cost component…')}</option>
                  {components.filter((x) => x.isActive).map((x) => <option key={x.id} value={x.id}>{x.code} · {x.name}</option>)}
                </select>
                <Input className="md:col-span-2" placeholder={tx('Description')} value={c.description ?? ''} onChange={(e) => patch(c.key, { description: e.target.value })} />
                <Input type="number" min="0" placeholder={tx('Amount')} value={c.amountText} onChange={(e) => patch(c.key, { amountText: e.target.value })} />
                <div className="flex gap-1">
                  <Input type="number" min="0" placeholder={tx('Tax')} value={c.taxText} onChange={(e) => patch(c.key, { taxText: e.target.value })} />
                  <select aria-label={tx('Tax recoverability')} className="rounded-md border p-2 text-sm" disabled={!Number(c.taxText)}
                    value={c.taxRecoverable === undefined ? '' : String(c.taxRecoverable)}
                    onChange={(e) => patch(c.key, { taxRecoverable: e.target.value === '' ? undefined : e.target.value === 'true' })}>
                    <option value="">{tx('Default')}</option>
                    <option value="true">{tx('Recoverable')}</option>
                    <option value="false">{tx('Part of cost')}</option>
                  </select>
                </div>
              </div>
              <div className="grid gap-2 md:grid-cols-6">
                <select className="rounded-md border p-2 text-sm md:col-span-2" value={c.billMode ?? 'generate'} onChange={(e) => patch(c.key, { billMode: e.target.value as BillMode })}>
                  {(Object.keys(BILL_MODE_LABEL) as BillMode[]).map((m) => <option key={m} value={m}>{BILL_MODE_LABEL[m]}</option>)}
                </select>
                {c.billMode === 'link' ? (
                  <select className="rounded-md border p-2 text-sm md:col-span-4" value={c.vendorBillId ?? ''}
                    onChange={(e) => {
                      const b = linkableBills.find((x: any) => x.id === e.target.value);
                      patch(c.key, { vendorBillId: e.target.value || undefined, supplierId: b?.partnerId ?? c.supplierId, supplierInvoiceRef: b?.reference ?? b?.documentNumber ?? c.supplierInvoiceRef });
                    }}>
                    <option value="">{tx('Supplier bill…')}</option>
                    {linkableBills.filter((b: any) => !c.supplierId || b.partnerId === c.supplierId).map((b: any) => (
                      <option key={b.id} value={b.id}>{b.documentNumber} · {b.partner?.name ?? ''} {b.reference ? `· ${b.reference}` : ''} · {money(Number(b.totalAmount))} ({b.status})</option>
                    ))}
                  </select>
                ) : (
                  <>
                    <Input type="date" title={tx('Supplier invoice date')} value={c.invoiceDate ?? ''} onChange={(e) => patch(c.key, { invoiceDate: e.target.value || undefined })} />
                    {c.billMode !== 'none' && <Input type="date" title={tx('Due date')} value={c.dueDate ?? ''} onChange={(e) => patch(c.key, { dueDate: e.target.value || undefined })} />}
                  </>
                )}
              </div>
              <div className="grid gap-2 md:grid-cols-6">
                <select className="rounded-md border p-2 text-sm md:col-span-2" value={c.supplierId ?? ''} onChange={(e) => patch(c.key, { supplierId: e.target.value || undefined })}>
                  <option value="">{tx('Supplier (forwarder, customs…)')}</option>
                  {suppliers.map((p: any) => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
                <Input placeholder={tx('Supplier invoice no.')} value={c.supplierInvoiceRef ?? ''} onChange={(e) => patch(c.key, { supplierInvoiceRef: e.target.value })} />
                <Input placeholder={tx('Currency')} value={c.currencyCode ?? ''} onChange={(e) => patch(c.key, { currencyCode: e.target.value })} />
                <Input type="number" min="0" placeholder={tx('Rate to base')} value={c.rateText} onChange={(e) => patch(c.key, { rateText: e.target.value })} />
                <Button variant="ghost" size="icon" onClick={() => setCharges((cs) => cs.filter((x) => x.key !== c.key))} disabled={charges.length === 1}><Trash2 className="h-4 w-4" /></Button>
              </div>
              <div className="grid gap-2 md:grid-cols-6">
                <select className="rounded-md border p-2 text-sm md:col-span-2" value={c.treatment ?? 'capitalize'} onChange={(e) => patch(c.key, { treatment: e.target.value as Treatment })}>
                  {(Object.keys(TREATMENT_LABEL) as Treatment[]).map((t) => <option key={t} value={t}>{TREATMENT_LABEL[t]}</option>)}
                </select>
                {(c.treatment ?? 'capitalize') === 'capitalize' && (
                  <select className="rounded-md border p-2 text-sm md:col-span-2" value={c.allocationMethod ?? 'value'} onChange={(e) => patch(c.key, { allocationMethod: e.target.value as AllocationMethod })}>
                    {METHODS.map((m) => <option key={m} value={m}>{METHOD_LABEL[m]}</option>)}
                  </select>
                )}
                {c.treatment === 'expense' && (
                  <select className="rounded-md border p-2 text-sm md:col-span-2" value={c.expenseAccountId ?? ''} onChange={(e) => patch(c.key, { expenseAccountId: e.target.value || undefined })}>
                    <option value="">{tx('Expense account (component default)')}</option>
                    {(accounts?.data ?? []).map((a: any) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
                  </select>
                )}
                <select className="rounded-md border p-2 text-sm md:col-span-2" value={c.costCenterId ?? ''} onChange={(e) => patch(c.key, { costCenterId: e.target.value || undefined })}>
                  <option value="">{tx('Cost centre (voucher default)')}</option>
                  {centers.map((x) => <option key={x.id} value={x.id}>{'  '.repeat(x.depth)}{x.code} · {x.name}</option>)}
                </select>
              </div>
              {(c.treatment ?? 'capitalize') === 'capitalize' && receiptLines.length > 0 && (
                <div className="space-y-1 text-sm">
                  <p className="text-xs text-muted-foreground">
                    {c.allocationMethod === 'manual' ? tx('Amount per receipt line (must total the charge):') : tx('Applies to (none ticked = all lines):')}
                  </p>
                  {c.allocationMethod !== 'manual' && goodsSuppliers.length > 1 && (
                    <div className="flex flex-wrap items-center gap-2 text-xs">
                      <span className="text-muted-foreground">{tx('Only goods from:')}</span>
                      {goodsSuppliers.map(([sid, name]) => (
                        <Button key={sid} type="button" variant="outline" size="sm" className="h-7"
                          onClick={() => patch(c.key, { appliesToLineIds: receiptLines.filter((l: any) => l.goodsSupplierId === sid).map((l: any) => l.id) })}>
                          {name}
                        </Button>
                      ))}
                      <Button type="button" variant="ghost" size="sm" className="h-7" onClick={() => patch(c.key, { appliesToLineIds: undefined })}>{tx('All goods')}</Button>
                    </div>
                  )}
                  {receiptLines.map((l: any) => (
                    <div key={l.id} className="flex items-center gap-2">
                      {c.allocationMethod === 'manual' ? (
                        <Input className="h-8 w-32" type="number" min="0" value={c.manualText[l.id] ?? ''} onChange={(e) => patch(c.key, { manualText: { ...c.manualText, [l.id]: e.target.value } })} />
                      ) : (
                        <input type="checkbox" checked={!!c.appliesToLineIds?.includes(l.id)}
                          onChange={(e) => patch(c.key, { appliesToLineIds: e.target.checked ? [...(c.appliesToLineIds ?? []), l.id] : (c.appliesToLineIds ?? []).filter((x) => x !== l.id) })} />
                      )}
                      <span className="font-mono text-xs">{l.receiptNumber}</span> {l.description} × {Number(l.quantity)}
                      {l.goodsSupplierName && <span className="text-xs text-muted-foreground">· {l.goodsSupplierName}</span>}
                    </div>
                  ))}
                </div>
              )}
              {i === charges.length - 1 && (
                <Button variant="outline" size="sm" onClick={() => setCharges((cs) => [...cs, blankCharge()])}><Plus className="mr-1 h-3 w-3" /> {tx('Add charge')}</Button>
              )}
            </div>
          ))}
          <p className="text-xs text-muted-foreground">
            {tx('Each supplier keeps its own invoice and payable: “Raise supplier bill” posts one bill per supplier invoice (debit landed-cost clearing, credit that supplier) when the voucher is posted. Link a bill you already entered against clearing instead of raising a second one.')}
          </p>
        </CardContent>
      </Card>
      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={() => navigate(-1)}>{tx('Cancel')}</Button>
        <Button onClick={submit} disabled={save.isPending || (!receipts.length && !shipmentId)}>{tx('Save draft')}</Button>
      </div>
    </div>
  );
}

/** One voucher: receipts, charges, allocation preview / posted allocation, workflow actions. */
export function LandedCostVoucherDetailPage() {
  const { id = '' } = useParams();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: v } = useLandedCostVoucher(id);
  const unposted = !!v && ['draft', 'submitted', 'approved'].includes(v.status);
  const { data: preview, error: previewError } = useLandedCostPreview(id, unposted);
  const action = useLandedCostAction();

  const act = async (a: 'submit' | 'approve' | 'reopen' | 'post' | 'reverse' | 'cancel') => {
    try {
      const r = await action.mutateAsync({ id, action: a });
      notify.success(a === 'post'
        ? tx('Posted — {{c}} added to inventory, {{e}} expensed', { c: money(Number(r.capitalizedAmount)), e: money(Number(r.expensedAmount)) })
        : tx('Voucher {{s}}', { s: r.status }));
    } catch (e) {
      notify.error(tx('Action failed'), errMsg(e));
    }
  };

  if (!v) return <div className="p-6 text-sm text-muted-foreground">{tx('Loading…')}</div>;
  const allocations = unposted ? preview?.allocations ?? [] : v.allocations ?? [];
  // Resulting landed cost per product (all charges), from the preview / posted allocation rows.
  const byProduct = Array.from(allocations.reduce((m: Map<string, any>, a: any) => {
    const k = `${a.productId}:${a.goodsReceiptId ?? ''}`;
    const row = m.get(k) ?? { productName: a.productName, receiptNumber: v.receipts.find((r: any) => r.id === a.goodsReceiptId)?.receiptNumber, qty: Number(a.receivedQty), original: Number(a.originalValue ?? 0), allocated: 0 };
    row.allocated += Number(a.allocatedAmount);
    m.set(k, row);
    return m;
  }, new Map<string, any>()).values()) as any[];
  const chargeLabel = (cid: string) => {
    const c = v.charges.find((x: any) => x.id === cid);
    return c ? `${c.lineNumber}. ${c.costComponentCode ?? c.kind}` : '';
  };

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{v.code} <Badge variant={STATUS_VARIANT[v.status]}>{v.status}</Badge></h1>
          <p className="text-sm text-muted-foreground">
            {date(v.date)} {v.shipment ? `· ${tx('Shipment')} ${v.shipment.code}` : ''} {v.notes ? `· ${v.notes}` : ''}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {v.status === 'draft' && has(PERMISSIONS.landedCost.create) && (
            <>
              <Link to={`/procurement/landed-cost-vouchers/${id}/edit`}><Button variant="outline">{tx('Edit')}</Button></Link>
              <Button variant="outline" onClick={() => act('submit')}><Send className="mr-1 h-4 w-4" /> {tx('Submit')}</Button>
            </>
          )}
          {v.status === 'submitted' && has(PERMISSIONS.landedCost.approve) && (
            <Button variant="outline" onClick={() => act('approve')}><CheckCircle2 className="mr-1 h-4 w-4" /> {tx('Approve')}</Button>
          )}
          {['submitted', 'approved'].includes(v.status) && has(PERMISSIONS.landedCost.create) && (
            <Button variant="outline" onClick={() => act('reopen')}><Undo2 className="mr-1 h-4 w-4" /> {tx('Reopen')}</Button>
          )}
          {v.status === 'approved' && has(PERMISSIONS.landedCost.post) && (
            <Button onClick={() => act('post')} disabled={action.isPending}><Upload className="mr-1 h-4 w-4" /> {tx('Post')}</Button>
          )}
          {v.status === 'posted' && has(PERMISSIONS.landedCost.reverse) && (
            <Button variant="destructive" onClick={() => act('reverse')} disabled={action.isPending}><Undo2 className="mr-1 h-4 w-4" /> {tx('Reverse')}</Button>
          )}
          {unposted && has(PERMISSIONS.landedCost.create) && (
            <Button variant="ghost" onClick={() => act('cancel')}><XCircle className="mr-1 h-4 w-4" /> {tx('Cancel')}</Button>
          )}
        </div>
      </div>

      <div className="grid gap-4 md:grid-cols-4">
        {[
          [tx('Total charges'), unposted ? preview?.totalAmount : v.totalAmount],
          [tx('Added to inventory'), unposted ? preview?.capitalizedAmount : v.capitalizedAmount],
          [tx('Expensed / to COGS'), unposted ? preview?.expensedAmount : v.expensedAmount],
          [tx('Recoverable tax'), unposted ? preview?.taxAmount : v.taxAmount],
        ].map(([label, value]) => (
          <Card key={label as string}><CardContent className="pt-6"><p className="text-xs text-muted-foreground">{label}</p><p className="text-xl font-semibold">{money(Number(value ?? 0))}</p></CardContent></Card>
        ))}
      </div>

      <Card>
        <CardHeader><CardTitle>{tx('Receipts')}</CardTitle></CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          {v.receipts.map((r: any) => (
            <Link key={r.id} to={`/procurement/goods-receipts/${r.id}`}><Badge variant="outline">{r.receiptNumber} · {r.partnerName ?? ''}</Badge></Link>
          ))}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>{tx('Suppliers')}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Supplier')}</TableHead>
                <TableHead>{tx('Invoices / bills')}</TableHead>
                <TableHead className="text-right">{tx('Charges (base)')}</TableHead>
                <TableHead className="text-right">{tx('Tax')}</TableHead>
                <TableHead className="text-right">{tx('Open payable')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(v.suppliers ?? []).map((s: any) => (
                <TableRow key={s.supplierId ?? 'none'}>
                  <TableCell>{s.supplierName ?? <span className="text-muted-foreground">{tx('No supplier (clearing)')}</span>}</TableCell>
                  <TableCell className="space-x-1 text-sm">
                    {s.invoices.length === 0 && '—'}
                    {s.invoices.map((i: any) => (
                      <span key={i.supplierInvoiceRef} className="inline-flex items-center gap-1">
                        <span className="font-mono text-xs">#{i.supplierInvoiceRef}</span>
                        {i.vendorBill
                          ? <Badge variant={i.vendorBill.status === 'posted' ? 'default' : 'outline'}>{i.vendorBill.documentNumber} · {i.vendorBill.status}</Badge>
                          : <Badge variant="outline">{i.billMode === 'generate' ? tx('bill on posting') : tx('no bill')}</Badge>}
                      </span>
                    ))}
                  </TableCell>
                  <TableCell className="text-right">{money(Number(s.baseAmount))}</TableCell>
                  <TableCell className="text-right">{Number(s.taxAmount) ? money(Number(s.taxAmount)) : '—'}</TableCell>
                  <TableCell className="text-right">
                    {s.invoices.some((i: any) => i.vendorBill)
                      ? money(s.invoices.reduce((t: number, i: any) => t + (i.vendorBill?.status === 'posted' ? Number(i.vendorBill.amountResidual ?? 0) : 0), 0))
                      : '—'}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>{tx('Charges')}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>#</TableHead>
                <TableHead>{tx('Component')}</TableHead>
                <TableHead>{tx('Supplier / invoice')}</TableHead>
                <TableHead>{tx('Treatment')}</TableHead>
                <TableHead>{tx('Basis')}</TableHead>
                <TableHead className="text-right">{tx('Amount')}</TableHead>
                <TableHead className="text-right">{tx('Base')}</TableHead>
                <TableHead className="text-right">{tx('Tax')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {v.charges.map((c: any) => (
                <TableRow key={c.id}>
                  <TableCell>{c.lineNumber}</TableCell>
                  <TableCell>{c.costComponentCode ?? c.kind} <span className="text-xs text-muted-foreground">{c.description}</span></TableCell>
                  <TableCell className="text-sm">
                    {c.supplierName ?? '—'} {c.supplierInvoiceRef ? <span className="font-mono text-xs">#{c.supplierInvoiceRef}</span> : null}
                    {c.vendorBill && <span className="ml-1 text-xs text-muted-foreground">({c.vendorBill.documentNumber})</span>}
                  </TableCell>
                  <TableCell>{TREATMENT_LABEL[c.treatment as Treatment] ?? c.treatment}</TableCell>
                  <TableCell>{c.treatment === 'capitalize' ? METHOD_LABEL[c.allocationMethod as AllocationMethod] : '—'}</TableCell>
                  <TableCell className="text-right">{Number(c.amount).toLocaleString()} {c.currencyCode ?? ''}</TableCell>
                  <TableCell className="text-right">{money(Number(c.baseAmount ?? c.amount))}</TableCell>
                  <TableCell className="text-right">{Number(c.taxAmount) ? money(Number(c.taxAmount) * Number(c.exchangeRate ?? 1)) : '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>{tx('Landed cost per product')}</CardTitle></CardHeader>
        <CardContent className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Product')}</TableHead>
                <TableHead>{tx('Receipt')}</TableHead>
                <TableHead className="text-right">{tx('Qty')}</TableHead>
                <TableHead className="text-right">{tx('Goods value')}</TableHead>
                <TableHead className="text-right">{tx('Additional costs')}</TableHead>
                <TableHead className="text-right">{tx('Landed value')}</TableHead>
                <TableHead className="text-right">{tx('Landed unit cost')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {byProduct.map((r, i) => (
                <TableRow key={i}>
                  <TableCell>{r.productName}</TableCell>
                  <TableCell className="font-mono text-xs">{r.receiptNumber ?? ''}</TableCell>
                  <TableCell className="text-right">{r.qty.toLocaleString()}</TableCell>
                  <TableCell className="text-right">{r.original ? money(r.original) : '—'}</TableCell>
                  <TableCell className="text-right">{money(r.allocated)}</TableCell>
                  <TableCell className="text-right">{r.original ? money(r.original + r.allocated) : '—'}</TableCell>
                  <TableCell className="text-right">{r.original && r.qty ? money((r.original + r.allocated) / r.qty) : '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>{unposted ? tx('Allocation preview') : tx('Allocation')}</CardTitle></CardHeader>
        <CardContent className="space-y-2">
          {unposted && previewError && <p className="text-sm text-destructive">{errMsg(previewError)}</p>}
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Charge')}</TableHead>
                  <TableHead>{tx('Product')}</TableHead>
                  <TableHead className="text-right">{tx('Qty')}</TableHead>
                  <TableHead className="text-right">{tx('Share')}</TableHead>
                  <TableHead className="text-right">{tx('Allocated')}</TableHead>
                  <TableHead className="text-right">{tx('On hand')}</TableHead>
                  <TableHead className="text-right">{tx('To inventory')}</TableHead>
                  <TableHead className="text-right">{tx('To COGS')}</TableHead>
                  <TableHead className="text-right">{tx('Unit cost before → after')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {allocations.map((a: any, i: number) => (
                  <TableRow key={a.id ?? i}>
                    <TableCell className="text-xs">{chargeLabel(a.chargeId)}</TableCell>
                    <TableCell>{a.productName}</TableCell>
                    <TableCell className="text-right">{Number(a.receivedQty).toLocaleString()}</TableCell>
                    <TableCell className="text-right">{a.sharePct != null ? `${(Number(a.sharePct) * (unposted ? 1 : 100)).toFixed(2)}%` : '—'}</TableCell>
                    <TableCell className="text-right">{money(Number(a.allocatedAmount))}</TableCell>
                    <TableCell className="text-right">{Number(a.onHandQty).toLocaleString()}</TableCell>
                    <TableCell className="text-right">{money(Number(a.capitalizedAmount))}</TableCell>
                    <TableCell className="text-right">{money(Number(a.expensedAmount))}</TableCell>
                    <TableCell className="text-right">{money(Number(a.unitCostBefore))} → {money(Number(a.unitCostAfter))}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          {v.journalEntryId && <Link className="text-sm text-primary hover:underline" to={`/journal-entries/${v.journalEntryId}`}>{tx('View journal entry')}</Link>}
        </CardContent>
      </Card>
    </div>
  );
}

export default LandedCostVouchersPage;

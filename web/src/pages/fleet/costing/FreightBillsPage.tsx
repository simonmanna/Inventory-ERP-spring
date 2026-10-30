import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Import, Plus, ReceiptText } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, StatusPill, useListState,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  FREIGHT_STATUS_LABEL, useFreightBills, useFreightCandidates, useFreightLookups, useGenerateFreightBills,
  useInboundFreight, type FreightBill, type FreightStatus,
} from '@/features/fleet/costing-api';
import { toOptions } from '../fleet-format';
import { Field, OptionalSelect } from '../fleet-ui';

const FILTERS = { status: '', direction: '' };
const DIRECTION_LABEL = { outbound: tx('Outbound trips'), inbound: tx('Inbound landed cost') };

export function FreightStatusPill({ status }: { status: FreightStatus }) {
  const tone = status === 'billed' ? 'success' : status === 'disputed' ? 'danger' : status === 'draft' ? 'info' : 'neutral';
  return <StatusPill tone={tone}>{FREIGHT_STATUS_LABEL[status]}</StatusPill>;
}

const today = () => new Date().toISOString().slice(0, 10);
const monthStart = () => `${today().slice(0, 8)}01`;

/**
 * Transporter freight bills (fleet 1.9): closed transporter trips collected per transporter and period, matched to the
 * supplier invoice (AP bill + accrual), and inbound freight capitalised on goods receipts as landed cost.
 */
export function FreightBillsPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const navigate = useNavigate();
  const list = useListState(FILTERS);
  const { data, isLoading, isError } = useFreightBills({
    page: list.page, pageSize: list.pageSize, status: list.filters.status || undefined,
    direction: list.filters.direction || undefined, search: list.search || undefined,
  });
  const canBill = has(PERMISSIONS.fleetFreight.bill);
  const [generating, setGenerating] = useState(false);
  const [inbound, setInbound] = useState(false);
  const rows = data?.data ?? [];
  const meta = data?.meta;

  const columns: Column<FreightBill>[] = [
    { key: 'code', header: tx('Bill'), render: (b) => <Link className="font-medium text-primary hover:underline" to={`/fleet/freight-bills/${b.id}`}>{b.code}</Link> },
    { key: 'transporter', header: tx('Transporter'), render: (b) => b.transporter?.name ?? '—' },
    { key: 'direction', header: tx('Kind'), render: (b) => DIRECTION_LABEL[b.direction] },
    { key: 'period', header: tx('Period'), render: (b) => <span className="whitespace-nowrap">{b.periodFrom ? `${date(b.periodFrom)} – ${date(b.periodTo)}` : '—'}</span> },
    { key: 'trips', header: tx('Trips'), className: 'text-right', render: (b) => b.tripCount },
    { key: 'total', header: tx('Freight'), className: 'text-right tabular-nums', render: (b) => money(b.totalAmount) },
    { key: 'supplier', header: tx('Supplier invoice'), render: (b) => (b.supplierReference ? `${b.supplierReference} · ${money(b.supplierAmount)}` : '—') },
    { key: 'variance', header: tx('Variance'), className: 'text-right tabular-nums', render: (b) => (b.varianceAmount == null ? '—' : <span className={Number(b.varianceAmount) !== 0 ? 'text-amber-600' : ''}>{money(b.varianceAmount)}</span>) },
    { key: 'ap', header: tx('AP bill'), render: (b) => b.vendorBillNumber ?? '—' },
    { key: 'status', header: tx('Status'), render: (b) => <FreightStatusPill status={b.status} /> },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={ReceiptText} title={tx('Freight Bills')}
        description={tx('Bill third-party transporters for closed trips once, match their invoice, and capitalise inbound freight as landed cost.')}
        actions={canBill && (
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" onClick={() => setInbound(true)}><Import className="mr-1.5 h-4 w-4" />{tx('Inbound freight')}</Button>
            <Button onClick={() => setGenerating(true)}><Plus className="mr-1.5 h-4 w-4" />{tx('Bill closed trips')}</Button>
          </div>
        )} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Bill, transporter or invoice…')} />
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter('status', v)} options={toOptions(FREIGHT_STATUS_LABEL)} allLabel={tx('All statuses')} />
        <FilterSelect value={list.filters.direction} onChange={(v) => list.setFilter('direction', v)} options={toOptions(DIRECTION_LABEL)} allLabel={tx('All kinds')} />
      </ListToolbar>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={rows} loading={isLoading} loadingRows={6} getRowId={(b) => b.id} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            onRowClick={(b) => navigate(`/fleet/freight-bills/${b.id}`)}
            emptyMessage={isError ? tx('Could not load the freight bills. Try again.') : tx('No freight bills yet.')} />
        </div>
      </ListCard>
      {meta && (
        <DataTablePagination page={meta.page} pageSize={list.pageSize} total={meta.total} totalPages={meta.totalPages}
          onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="bill" />
      )}
      {generating && <GenerateDialog onClose={() => setGenerating(false)} onDone={(id) => { setGenerating(false); if (id) navigate(`/fleet/freight-bills/${id}`); }} />}
      {inbound && <InboundDialog onClose={() => setInbound(false)} onDone={(id) => { setInbound(false); navigate(`/fleet/freight-bills/${id}`); }} />}
    </div>
  );
}

function GenerateDialog({ onClose, onDone }: { onClose: () => void; onDone: (id?: string) => void }) {
  const [from, setFrom] = useState(monthStart());
  const [to, setTo] = useState(today());
  const [transporter, setTransporter] = useState('');
  const [notes, setNotes] = useState('');
  const candidates = useFreightCandidates({ from, to, transporterPartnerId: transporter || undefined }, !!from && !!to);
  const generate = useGenerateFreightBills();
  const groups = candidates.data?.transporters ?? [];
  const tripCount = groups.reduce((s, g) => s + g.trips.length, 0);

  const submit = async () => {
    try {
      const out = await generate.mutateAsync({ from, to, transporterPartnerId: transporter || undefined, notes: notes || undefined });
      notify.success(tx('{{n}} freight bill(s) created', { n: out.created.length }));
      onDone(out.created.length === 1 ? out.created[0].id : undefined);
    } catch (e) {
      notify.error(apiMessage(e, tx('Could not create the freight bills')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{tx('Bill closed transporter trips')}</DialogTitle>
          <DialogDescription>{tx('One draft bill per transporter with the trips closed in the period that are not billed yet. A trip is billed once.')}</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label={tx('Closed from')}><Input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label={tx('Closed to')}><Input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} /></Field>
          <Field label={tx('Transporter')}>
            <OptionalSelect value={transporter} onChange={setTransporter} placeholder={tx('All transporters')} noneLabel={tx('All transporters')}
              options={groups.map((g) => ({ value: g.transporterPartnerId, label: g.transporterName }))} />
          </Field>
        </div>
        <div className="max-h-64 space-y-2 overflow-y-auto rounded-md border p-2 text-sm">
          {candidates.isLoading && <p className="text-muted-foreground">{tx('Loading…')}</p>}
          {!candidates.isLoading && groups.length === 0 && <p className="text-muted-foreground">{tx('No unbilled closed transporter trips in this period.')}</p>}
          {groups.map((g) => (
            <div key={g.transporterPartnerId}>
              <div className="flex justify-between font-medium"><span>{g.transporterName}</span><span className="tabular-nums">{money(g.total)}</span></div>
              {g.trips.map((t) => (
                <div key={t.id} className="flex justify-between pl-3 text-xs text-muted-foreground">
                  <span>{t.code} · {t.deliveryOrderCode} · {date(t.closedAt)}</span><span className="tabular-nums">{money(t.freightAmount)}</span>
                </div>
              ))}
            </div>
          ))}
        </div>
        <Field label={tx('Notes')}><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!tripCount || generate.isPending} onClick={submit}>{tx('Create bills ({{n}} trips)', { n: tripCount })}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function InboundDialog({ onClose, onDone }: { onClose: () => void; onDone: (id: string) => void }) {
  const [search, setSearch] = useState('');
  const lookups = useFreightLookups(search);
  const save = useInboundFreight();
  const [form, setForm] = useState({ goodsReceiptId: '', transporterPartnerId: '', supplierReference: '', invoiceDate: today(), amount: '', allocationMethod: 'value', notes: '' });
  const set = (k: keyof typeof form) => (v: string) => setForm((f) => ({ ...f, [k]: v }));
  const valid = form.goodsReceiptId && form.transporterPartnerId && form.supplierReference.trim() && Number(form.amount) > 0;
  const METHODS = { value: tx('By value'), quantity: tx('By quantity'), weight: tx('By weight'), volume: tx('By volume'), equal: tx('Equally') };

  const submit = async () => {
    try {
      const out = await save.mutateAsync({ ...form, amount: Number(form.amount), notes: form.notes || undefined });
      notify.success(tx('Freight capitalised · {{code}}', { code: out.code }));
      onDone(out.id);
    } catch (e) {
      notify.error(apiMessage(e, tx('Could not capitalise the freight')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>{tx('Inbound freight → landed cost')}</DialogTitle>
          <DialogDescription>{tx('The transporter\'s invoice for bringing goods in is added to the cost of a posted goods receipt (landed cost voucher), and its supplier bill is raised in Accounts Payable.')}</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={tx('Search')} className="sm:col-span-2"><Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={tx('Receipt number, supplier or transporter…')} /></Field>
          <Field label={tx('Goods receipt')}>
            <OptionalSelect value={form.goodsReceiptId} onChange={set('goodsReceiptId')} placeholder={tx('Choose…')}
              options={(lookups.data?.goodsReceipts ?? []).map((g) => ({ value: g.id, label: `${g.receiptNumber} · ${g.partnerName ?? ''}` }))} />
          </Field>
          <Field label={tx('Transporter')}>
            <OptionalSelect value={form.transporterPartnerId} onChange={set('transporterPartnerId')} placeholder={tx('Choose…')}
              options={(lookups.data?.transporters ?? []).map((t) => ({ value: t.id, label: t.name }))} />
          </Field>
          <Field label={tx('Supplier invoice no.')}><Input value={form.supplierReference} onChange={(e) => set('supplierReference')(e.target.value)} /></Field>
          <Field label={tx('Invoice date')}><Input type="date" value={form.invoiceDate} max={today()} onChange={(e) => set('invoiceDate')(e.target.value)} /></Field>
          <Field label={tx('Amount')}><Input inputMode="decimal" value={form.amount} onChange={(e) => set('amount')(e.target.value)} /></Field>
          <Field label={tx('Spread over the receipt')}>
            <OptionalSelect value={form.allocationMethod} onChange={set('allocationMethod')} placeholder={METHODS.value} options={toOptions(METHODS)} />
          </Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={form.notes} onChange={(e) => set('notes')(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!valid || save.isPending} onClick={submit}>{tx('Capitalise freight')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

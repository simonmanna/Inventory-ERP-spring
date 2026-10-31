import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertOctagon, Eye, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState,
  type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  NCR_SOURCE_LABEL, NCR_STATUS_LABEL, SEVERITY_LABEL, useCreateNcr, useNcrs, useUpdateNcr, type InspectionLot, type Ncr,
  type NcrSource, type NcrStatus, type Severity,
} from '@/features/quality/api';
import { Field, NcrStatusPill, OptionalSelect, ProductPicker, SeverityPill, UserPicker } from './quality-ui';
import { optNum, toOptions } from './quality-format';

const FILTERS = { status: '', severity: '', source: '' };

/** Nonconformance reports (ADR-S013): raised by hand or automatically when a lot fails. */
export function NcrsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { status, severity, source } = list.filters;
  const [creating, setCreating] = useState(false);
  const { data, isLoading, isFetching, isError } = useNcrs({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, status: status || undefined,
    severity: severity || undefined, source: source || undefined,
  });
  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(status ? [{ key: 'status', label: NCR_STATUS_LABEL[status as NcrStatus] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
    ...(severity ? [{ key: 'severity', label: SEVERITY_LABEL[severity as Severity] ?? severity, onRemove: () => list.setFilter('severity', '') }] : []),
    ...(source ? [{ key: 'source', label: NCR_SOURCE_LABEL[source as NcrSource] ?? source, onRemove: () => list.setFilter('source', '') }] : []),
  ];
  const columns: Column<Ncr>[] = [
    { key: 'number', header: tx('Number'), render: (n) => <span className="font-medium text-primary">{n.ncrNumber}</span> },
    { key: 'created', header: tx('Raised'), render: (n) => <span className="whitespace-nowrap">{dateTime(n.createdAt)}</span> },
    { key: 'title', header: tx('Title'), render: (n) => <span className="line-clamp-1 max-w-[18rem]">{n.title}</span> },
    { key: 'source', header: tx('Source'), render: (n) => NCR_SOURCE_LABEL[n.source] },
    { key: 'product', header: tx('Product'), render: (n) => <div><div>{n.productName ?? '—'}</div><div className="text-xs text-muted-foreground">{n.batchNumber ?? ''}</div></div> },
    { key: 'lot', header: tx('Lot'), render: (n) => n.lotNumber ?? '—' },
    { key: 'severity', header: tx('Severity'), render: (n) => <SeverityPill severity={n.severity} /> },
    { key: 'capa', header: tx('CAPA'), className: 'text-right', render: (n) => n.capaCount },
    { key: 'status', header: tx('Status'), render: (n) => <NcrStatusPill status={n.status} /> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (n) => <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View')} onClick={() => navigate(`/quality/ncrs/${n.id}`)}><Eye className="h-4 w-4" /></Button>,
    },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={AlertOctagon} title={tx('Nonconformance Reports')}
        description={tx('Nonconformances from incoming, in-process and final inspection, customers and suppliers, with their corrective actions.')}
        actions={has(PERMISSIONS.quality.ncr) && <Button onClick={() => setCreating(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('New NCR')}</Button>} />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search number, title, batch, product…')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(NCR_STATUS_LABEL)} allLabel={tx('All statuses')} />
        <FilterSelect value={severity} onChange={(v) => list.setFilter('severity', v)} options={toOptions(SEVERITY_LABEL)} allLabel={tx('Any severity')} />
        <FilterSelect value={source} onChange={(v) => list.setFilter('source', v)} options={toOptions(NCR_SOURCE_LABEL)} allLabel={tx('Any source')} />
      </ListToolbar>
      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(n) => n.id}
            onRowClick={(n) => navigate(`/quality/ncrs/${n.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the NCRs.') : chips.length ? tx('No NCRs match these filters.') : tx('No nonconformance reports.')} />
        </div>
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="NCR" />
        )}
      </ListCard>
      {creating && <NcrFormDialog onClose={() => setCreating(false)} onSaved={(n) => navigate(`/quality/ncrs/${n.id}`)} />}
    </div>
  );
}

/** Create an NCR (optionally for a lot), or edit an open one. */
export function NcrFormDialog({ ncr, lot, onClose, onSaved }: { ncr?: Ncr; lot?: InspectionLot; onClose: () => void; onSaved?: (n: Ncr) => void }) {
  const [title, setTitle] = useState(ncr?.title ?? (lot ? tx('Nonconformance on lot {{number}}', { number: lot.lotNumber }) : ''));
  const [description, setDescription] = useState(ncr?.description ?? '');
  const [source, setSource] = useState<NcrSource>(ncr?.source ?? (lot ? lot.stage : 'internal'));
  const [severity, setSeverity] = useState<Severity>(ncr?.severity ?? 'minor');
  const [productId, setProductId] = useState(ncr?.productId ?? '');
  const [batchNumber, setBatchNumber] = useState(ncr?.batchNumber ?? '');
  const [quantity, setQuantity] = useState(ncr?.quantityAffected ?? '');
  const [disposition, setDisposition] = useState(ncr?.disposition ?? '');
  const [rootCause, setRootCause] = useState(ncr?.rootCause ?? '');
  const [ownerUserId, setOwnerUserId] = useState(ncr?.ownerUserId ?? '');
  const create = useCreateNcr();
  const update = useUpdateNcr();
  const submit = async () => {
    const common = {
      title: title.trim(), description: description.trim() || undefined, severity, quantityAffected: optNum(String(quantity ?? '')),
      disposition: disposition.trim() || undefined, rootCause: rootCause.trim() || undefined, ownerUserId: ownerUserId || undefined,
    };
    try {
      const out = ncr
        ? await update.mutateAsync({ id: ncr.id, body: common })
        : await create.mutateAsync({
          ...common, source, lotId: lot?.id, productId: lot ? undefined : productId || undefined,
          batchNumber: lot ? undefined : batchNumber.trim() || undefined,
        });
      notify.success(ncr ? tx('NCR saved') : tx('NCR {{number}} raised', { number: out.ncrNumber }));
      onSaved?.(out);
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the NCR')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{ncr ? tx('Edit {{number}}', { number: ncr.ncrNumber }) : tx('New NCR')}</DialogTitle>
          <DialogDescription>{lot ? tx('Product, batch, supplier and work centre are taken from lot {{number}}.', { number: lot.lotNumber }) : tx('Describe the nonconformance; CAPAs follow from the NCR.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Title *')} className="sm:col-span-2"><Input value={title} onChange={(e) => setTitle(e.target.value)} /></Field>
          {!ncr && <Field label={tx('Source')}><OptionalSelect value={source} onChange={(v) => setSource((v || 'internal') as NcrSource)} placeholder={tx('Source')} options={toOptions(NCR_SOURCE_LABEL)} /></Field>}
          <Field label={tx('Severity')}><OptionalSelect value={severity} onChange={(v) => setSeverity((v || 'minor') as Severity)} placeholder={tx('Severity')} options={toOptions(SEVERITY_LABEL)} /></Field>
          {!ncr && !lot && (
            <>
              <Field label={tx('Product')}><ProductPicker value={productId} onChange={setProductId} /></Field>
              <Field label={tx('Batch number')}><Input value={batchNumber} onChange={(e) => setBatchNumber(e.target.value)} /></Field>
            </>
          )}
          <Field label={tx('Quantity affected')}><Input inputMode="decimal" value={quantity ?? ''} onChange={(e) => setQuantity(e.target.value)} /></Field>
          <Field label={tx('Owner')}><UserPicker value={ownerUserId} onChange={setOwnerUserId} /></Field>
          <Field label={tx('Description')} className="sm:col-span-2"><Textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
          <Field label={tx('Disposition')} className="sm:col-span-2"><Input value={disposition} onChange={(e) => setDisposition(e.target.value)} /></Field>
          <Field label={tx('Root cause')} className="sm:col-span-2"><Textarea rows={2} value={rootCause} onChange={(e) => setRootCause(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!title.trim() || create.isPending || update.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

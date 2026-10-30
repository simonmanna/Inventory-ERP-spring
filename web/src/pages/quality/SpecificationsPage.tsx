import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Eye, FlaskConical, Plus } from 'lucide-react';
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
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  SPEC_STATUS_LABEL, useCreateSpecification, useSpecifications, type Specification, type SpecStatus,
} from '@/features/quality/api';
import { Field, ProductPicker, SpecStatusPill } from './quality-ui';
import { toOptions } from './quality-format';

const FILTERS = { status: '' };

/** Specifications (ADR-S013): one row per version; a new code starts as a draft. */
export function SpecificationsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { status } = list.filters;
  const [creating, setCreating] = useState(false);
  const { data, isLoading, isFetching, isError } = useSpecifications({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, status: status || undefined,
  });
  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(status ? [{ key: 'status', label: SPEC_STATUS_LABEL[status as SpecStatus] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
  ];
  const columns: Column<Specification>[] = [
    { key: 'code', header: tx('Code'), render: (s) => <span className="font-medium text-primary">{s.code}</span> },
    { key: 'version', header: tx('Version'), render: (s) => `v${s.version}` },
    { key: 'name', header: tx('Name'), render: (s) => s.name },
    { key: 'product', header: tx('Product'), render: (s) => s.productName ?? '—' },
    { key: 'grade', header: tx('Grade'), render: (s) => s.grade ?? '—' },
    { key: 'params', header: tx('Parameters'), className: 'text-right', render: (s) => s.parameterCount ?? 0 },
    { key: 'from', header: tx('Effective from'), render: (s) => (s.effectiveFrom ? date(s.effectiveFrom) : '—') },
    { key: 'status', header: tx('Status'), render: (s) => <SpecStatusPill status={s.status} /> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (s) => (
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View')} onClick={() => navigate(`/quality/specifications/${s.id}`)}>
          <Eye className="h-4 w-4" />
        </Button>
      ),
    },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={FlaskConical} title={tx('Specifications')}
        description={tx('Product and grade specifications with test parameters, limits and grading envelopes. Every change is a new version.')}
        actions={has(PERMISSIONS.quality.configure) && <Button onClick={() => setCreating(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('New specification')}</Button>} />
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search code, name, grade, product…')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(SPEC_STATUS_LABEL)} allLabel={tx('All statuses')} />
      </ListToolbar>
      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(s) => s.id}
            onRowClick={(s) => navigate(`/quality/specifications/${s.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the specifications.') : chips.length ? tx('No specifications match these filters.') : tx('No specifications yet.')} />
        </div>
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="specification" />
        )}
      </ListCard>
      {creating && <SpecCreateDialog onClose={() => setCreating(false)} onSaved={(s) => navigate(`/quality/specifications/${s.id}`)} />}
    </div>
  );
}

function SpecCreateDialog({ onClose, onSaved }: { onClose: () => void; onSaved: (s: Specification) => void }) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [productId, setProductId] = useState('');
  const [grade, setGrade] = useState('');
  const [standard, setStandard] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [notes, setNotes] = useState('');
  const create = useCreateSpecification();
  const valid = code.trim().length > 0 && name.trim().length > 0;
  const submit = async () => {
    try {
      const out = await create.mutateAsync({
        code: code.trim(), name: name.trim(), productId: productId || undefined, grade: grade.trim() || undefined,
        standard: standard.trim() || undefined, effectiveFrom: effectiveFrom || undefined, notes: notes.trim() || undefined,
      });
      notify.success(tx('Specification created'));
      onSaved(out);
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the specification')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader><DialogTitle>{tx('New specification')}</DialogTitle><DialogDescription>{tx('Created as a draft; add the parameters, then activate it.')}</DialogDescription></DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Code *')}><Input value={code} onChange={(e) => setCode(e.target.value)} placeholder="AGG-20MM" /></Field>
          <Field label={tx('Name *')}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Product')} className="sm:col-span-2"><ProductPicker value={productId} onChange={setProductId} /></Field>
          <Field label={tx('Grade')}><Input value={grade} onChange={(e) => setGrade(e.target.value)} /></Field>
          <Field label={tx('Standard')}><Input value={standard} onChange={(e) => setStandard(e.target.value)} placeholder="BS EN 12620" /></Field>
          <Field label={tx('Effective from')}><Input type="date" value={effectiveFrom} onChange={(e) => setEffectiveFrom(e.target.value)} /></Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || create.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

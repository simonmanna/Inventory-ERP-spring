import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { LifeBuoy } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  CASE_STATUS_TONE, PRIORITY_TONE, caseApi, useCaseMutation, useCases, type CrmCase,
} from '@/features/crm/cases-api';
import { useCustomers } from '@/features/crm/suite-api';
import { Field } from './crm-ui';

const FILTERS = { status: '', caseType: '', priority: '', overdue: '', partnerId: '' };
const options = (values: string[]) => values.map((v) => ({ value: v, label: v.replace(/_/g, ' ') }));
const SELECT = 'h-9 w-full rounded-md border bg-background px-2 text-sm';

/** Customer complaints and service requests, most urgent first (CRM-1). */
export function CasesPage() {
  const list = useListState(FILTERS);
  const navigate = useNavigate();
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.caseWrite));
  const [creating, setCreating] = useState(false);
  const { data, isLoading, isError } = useCases({ page: list.page, pageSize: list.pageSize, q: list.search, ...list.filters });
  const rows = data?.data ?? [];
  const meta = data?.meta;

  const columns: Column<CrmCase>[] = [
    { key: 'code', header: tx('Case'), render: (c) => <span className="font-medium">{c.code}</span> },
    { key: 'customer', header: tx('Customer'), render: (c) => c.partnerName ?? '—' },
    { key: 'subject', header: tx('Subject'), render: (c) => c.subject },
    { key: 'type', header: tx('Type'), render: (c) => c.caseType.replace(/_/g, ' ') },
    { key: 'priority', header: tx('Priority'), render: (c) => <Badge className={PRIORITY_TONE[c.priority]}>{c.priority}</Badge> },
    { key: 'owner', header: tx('Owner'), render: (c) => c.assigneeName ?? '—' },
    {
      key: 'due', header: tx('Due'),
      render: (c) => <span className={c.overdue ? 'whitespace-nowrap font-medium text-rose-600' : 'whitespace-nowrap'}>{c.dueAt ? dateTime(c.dueAt) : '—'}</span>,
    },
    { key: 'status', header: tx('Status'), render: (c) => <Badge className={CASE_STATUS_TONE[c.status]}>{c.status.replace(/_/g, ' ')}</Badge> },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={LifeBuoy} title={tx('Complaints & service requests')}
        description={tx('Customer cases with an owner, a due time and a timeline.')}
        actions={canWrite ? <Button onClick={() => setCreating(true)}>{tx('New case')}</Button> : undefined} />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Code, subject, customer…')} />
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter('status', v)}
          options={options(['open', 'in_progress', 'resolved', 'closed', 'cancelled'])} allLabel={tx('All statuses')} />
        <FilterSelect value={list.filters.caseType} onChange={(v) => list.setFilter('caseType', v)}
          options={options(['complaint', 'service_request'])} allLabel={tx('All types')} />
        <FilterSelect value={list.filters.priority} onChange={(v) => list.setFilter('priority', v)}
          options={options(['urgent', 'high', 'normal', 'low'])} allLabel={tx('All priorities')} />
        <label className="flex items-center gap-1.5 text-sm">
          <input type="checkbox" checked={list.filters.overdue === 'true'} onChange={(e) => list.setFilter('overdue', e.target.checked ? 'true' : '')} />
          {tx('Overdue')}
        </label>
      </ListToolbar>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={rows} loading={isLoading} loadingRows={6} getRowId={(c) => c.id}
            onRowClick={(c) => navigate(`/crm/cases/${c.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the cases. Try again.') : tx('No cases.')} />
        </div>
      </ListCard>
      {meta && (
        <DataTablePagination page={meta.page} pageSize={list.pageSize} total={meta.total} totalPages={meta.totalPages}
          onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="case" />
      )}
      {creating && <NewCaseDialog onClose={() => setCreating(false)} />}
    </div>
  );
}

function NewCaseDialog({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const { data: customers } = useCustomers({ pageSize: 200 });
  const create = useCaseMutation(caseApi.create);
  const [form, setForm] = useState({
    caseType: 'complaint', category: 'other', priority: 'normal', partnerId: '', subject: '', description: '',
    contactName: '', contactPhone: '',
  });
  const set = (k: string, v: string) => setForm((f) => ({ ...f, [k]: v }));

  const submit = async () => {
    try {
      const out = await create.mutateAsync({ ...form, description: form.description || undefined });
      notify.success(tx('Case created'));
      navigate(`/crm/cases/${out.id}`);
    } catch (err) {
      notify.error(apiErrorMessage(err, tx('Could not create the case')));
    }
  };

  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('New case')}</DialogTitle>
          <DialogDescription>{tx('The priority sets the due time; you can change it on the case.')}</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <Field label={tx('Customer')}>
            <select className={SELECT} value={form.partnerId} onChange={(e) => set('partnerId', e.target.value)}>
              <option value="">{tx('Select…')}</option>
              {(customers?.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </Field>
          <Field label={tx('Subject')}>
            <Input value={form.subject} onChange={(e) => set('subject', e.target.value)} />
          </Field>
          <div className="grid grid-cols-3 gap-3">
            <Field label={tx('Type')}>
              <select className={SELECT} value={form.caseType} onChange={(e) => set('caseType', e.target.value)}>
                <option value="complaint">{tx('Complaint')}</option>
                <option value="service_request">{tx('Service request')}</option>
              </select>
            </Field>
            <Field label={tx('Category')}>
              <select className={SELECT} value={form.category} onChange={(e) => set('category', e.target.value)}>
                {['delivery', 'quality', 'billing', 'service', 'other'].map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            </Field>
            <Field label={tx('Priority')}>
              <select className={SELECT} value={form.priority} onChange={(e) => set('priority', e.target.value)}>
                {['low', 'normal', 'high', 'urgent'].map((p) => <option key={p} value={p}>{p}</option>)}
              </select>
            </Field>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label={tx('Contact name')}>
              <Input value={form.contactName} onChange={(e) => set('contactName', e.target.value)} />
            </Field>
            <Field label={tx('Contact phone')}>
              <Input value={form.contactPhone} onChange={(e) => set('contactPhone', e.target.value)} />
            </Field>
          </div>
          <Field label={tx('Description')}>
            <Textarea rows={3} value={form.description} onChange={(e) => set('description', e.target.value)} />
          </Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={create.isPending || !form.partnerId || !form.subject.trim()} onClick={submit}>{tx('Create')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

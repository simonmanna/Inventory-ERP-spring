import { useNavigate, useSearchParams } from 'react-router-dom';
import { FileText, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTablePagination, ListPageHeader, SearchInput } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { QUOTE_STATUS_LABEL, useCrmLookups, useQuotations, type QuoteStatus } from '@/features/crm/suite-api';
import { QuoteStatusPill } from './crm-ui';

const TABS: Array<{ key: string; label: string }> = [
  { key: '', label: tx('All') },
  { key: 'draft', label: QUOTE_STATUS_LABEL.draft },
  { key: 'pending_approval', label: QUOTE_STATUS_LABEL.pending_approval },
  { key: 'approved,sent', label: tx('Approved / sent') },
  { key: 'accepted', label: QUOTE_STATUS_LABEL.accepted },
  { key: 'converted', label: QUOTE_STATUS_LABEL.converted },
  { key: 'rejected,expired,cancelled', label: tx('Closed') },
];

/** Quotation register with status tabs (ADR-S010). */
export function QuotationsPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const { data: lk } = useCrmLookups();
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.quotationWrite));
  const status = params.get('status') ?? '';
  const set = (k: string, v: string) => setParams((p) => { const n = new URLSearchParams(p); if (v) n.set(k, v); else n.delete(k); if (k !== 'page') n.delete('page'); return n; }, { replace: true });
  const { data, isLoading } = useQuotations({
    status: status || undefined, q: params.get('q') || undefined, dealId: params.get('dealId') || undefined,
    ownerId: params.get('ownerId') || undefined, from: params.get('from') || undefined, to: params.get('to') || undefined,
    page: Number(params.get('page') ?? 1), pageSize: 50,
  });

  return (
    <div className="space-y-4">
      <ListPageHeader icon={FileText} title={tx('Quotations')} description={tx('Price, approve and send quotations; convert accepted ones into orders or proforma invoices.')}
        actions={canWrite && <Button onClick={() => navigate('/crm/quotations/new')}><Plus className="mr-1 h-4 w-4" />{tx('New quotation')}</Button>} />
      <div className="flex flex-wrap items-center gap-2">
        <div className="flex flex-wrap rounded-lg border bg-card p-0.5">
          {TABS.map((t) => (
            <button key={t.key} onClick={() => set('status', t.key)} className={cn('rounded-md px-3 py-1.5 text-sm', status === t.key ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>{t.label}</button>
          ))}
        </div>
        <SearchInput value={params.get('q') ?? ''} onChange={(v) => set('q', v)} placeholder={tx('Number, customer, opportunity…')} />
        <select className="h-9 rounded-md border bg-background px-2 text-sm" value={params.get('ownerId') ?? ''} onChange={(e) => set('ownerId', e.target.value)}>
          <option value="">{tx('Any salesperson')}</option>
          {(lk?.users ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
        </select>
        <input type="date" className="h-9 rounded-md border bg-background px-2 text-sm" value={params.get('from') ?? ''} onChange={(e) => set('from', e.target.value)} />
        <input type="date" className="h-9 rounded-md border bg-background px-2 text-sm" value={params.get('to') ?? ''} onChange={(e) => set('to', e.target.value)} />
        {params.get('dealId') && <Button variant="ghost" size="sm" onClick={() => set('dealId', '')}>{tx('Clear opportunity filter')}</Button>}
      </div>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[820px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            <tr><th className="px-3 py-2">{tx('Number')}</th><th className="px-3 py-2">{tx('Customer')}</th><th className="px-3 py-2">{tx('Opportunity')}</th>
              <th className="px-3 py-2">{tx('Salesperson')}</th><th className="px-3 py-2">{tx('Date')}</th><th className="px-3 py-2">{tx('Valid until')}</th>
              <th className="px-3 py-2">{tx('Status')}</th><th className="px-3 py-2 text-right">{tx('Total')}</th></tr>
          </thead>
          <tbody>
            {isLoading && <tr><td colSpan={8} className="p-3"><Skeleton className="h-32 w-full" /></td></tr>}
            {data?.data.map((q) => (
              <tr key={q.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => navigate(`/crm/quotations/${q.id}`)}>
                <td className="px-3 py-2 font-medium">{q.number}{q.revision > 0 && <span className="ml-1 text-xs text-muted-foreground">r{q.revision}</span>}</td>
                <td className="px-3 py-2">{q.partnerName}</td>
                <td className="px-3 py-2 text-muted-foreground">{q.dealName ?? '—'}</td>
                <td className="px-3 py-2">{q.ownerName ?? '—'}</td>
                <td className="px-3 py-2">{date(q.issueDate)}</td>
                <td className="px-3 py-2">{q.validUntil ? date(q.validUntil) : '—'}</td>
                <td className="px-3 py-2"><QuoteStatusPill status={q.status as QuoteStatus} /></td>
                <td className="px-3 py-2 text-right tabular-nums">{money(q.total, q.currencyCode)}</td>
              </tr>
            ))}
            {data && data.data.length === 0 && <tr><td colSpan={8} className="p-6 text-center text-muted-foreground">{tx('No quotations.')}</td></tr>}
          </tbody>
          {data && data.data.length > 0 && (
            <tfoot><tr className="border-t font-semibold"><td colSpan={7} className="px-3 py-2">{data.meta.total} {tx('quotations')}</td><td className="px-3 py-2 text-right tabular-nums">{money(data.totals?.total ?? 0)}</td></tr></tfoot>
          )}
        </table>
        {data && data.meta.totalPages > 1 && (
          <DataTablePagination page={data.meta.page} pageSize={data.meta.pageSize} total={data.meta.total} totalPages={data.meta.totalPages} onPageChange={(p) => set('page', String(p))} />
        )}
      </div>
    </div>
  );
}

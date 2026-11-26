import { useNavigate, useSearchParams } from 'react-router-dom';
import { ChevronDown, FileStack, FileText, Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { apiErrorMessage } from '@/lib/api-error';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTablePagination, ListPageHeader, SearchInput } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { QUOTE_STATUS_LABEL, crmApi, useCrmLookups, useCrmMutation, useQuotations, useQuoteTemplates, type QuoteStatus } from '@/features/crm/suite-api';
import { ErrorState, QuoteStatusPill } from './crm-ui';

const TABS: Array<{ key: string; label: string }> = [
  { key: '', label: tx('All') },
  { key: 'draft', label: QUOTE_STATUS_LABEL.draft },
  { key: 'pending_approval', label: QUOTE_STATUS_LABEL.pending_approval },
  { key: 'approved,sent', label: tx('Approved / sent') },
  { key: 'accepted', label: QUOTE_STATUS_LABEL.accepted },
  { key: 'converted', label: QUOTE_STATUS_LABEL.converted },
  { key: 'expiring', label: tx('Expiring soon') },
  { key: 'rejected,expired,cancelled', label: tx('Closed') },
];

/** Quotation register with status tabs (ADR-S010). */
export function QuotationsPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const { data: lk } = useCrmLookups();
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.quotationWrite) || s.hasPermission(PERMISSIONS.sales.quotationWrite));
  const base = window.location.pathname.startsWith('/sales') ? '/sales/quotations' : '/crm/quotations';
  const status = params.get('status') ?? '';
  // "Expiring soon": open offers (approved / sent) whose validity ends within 7 days.
  const expiring = status === 'expiring';
  const templates = useQuoteTemplates(canWrite);
  const deleteTemplate = useCrmMutation(crmApi.deleteQuoteTemplate);
  const set = (k: string, v: string) => setParams((p) => { const n = new URLSearchParams(p); if (v) n.set(k, v); else n.delete(k); if (k !== 'page') n.delete('page'); return n; }, { replace: true });
  const { data, isLoading, isError, refetch } = useQuotations({
    status: expiring ? undefined : status || undefined, expiringWithin: expiring ? 7 : undefined, q: params.get('q') || undefined, dealId: params.get('dealId') || undefined,
    ownerId: params.get('ownerId') || undefined, from: params.get('from') || undefined, to: params.get('to') || undefined,
    page: Number(params.get('page') ?? 1), pageSize: 50,
  });

  return (
    <div className="space-y-4">
      <ListPageHeader icon={FileText} title={tx('Quotations')} description={tx('Price, approve and send quotations; convert accepted ones into orders or proforma invoices.')}
        actions={canWrite && (
          <div className="flex items-center">
            <Button data-tour="sales.quotes.new" className="rounded-r-none" onClick={() => navigate(`${base}/new`)}><Plus className="mr-1 h-4 w-4" />{tx('New quotation')}</Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild><Button className="rounded-l-none border-l border-primary-foreground/30 px-2" aria-label={tx('New from a template')}><ChevronDown className="h-4 w-4" /></Button></DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="min-w-[240px]">
                <DropdownMenuLabel>{tx('New from a template')}</DropdownMenuLabel>
                <DropdownMenuSeparator />
                {(templates.data ?? []).length === 0 && <p className="px-2 py-1.5 text-xs text-muted-foreground">{tx('No templates yet. Open a quotation and use Actions → Save as template.')}</p>}
                {(templates.data ?? []).map((t) => (
                  <DropdownMenuItem key={t.id} onSelect={() => navigate(`${base}/new?templateId=${t.id}`)} className="flex items-center justify-between gap-2">
                    <span className="flex items-center gap-2"><FileStack className="h-4 w-4" />{t.name}<span className="text-xs text-muted-foreground">{t.lineCount} {tx('lines')}</span></span>
                    <button type="button" aria-label={tx('Delete template')} className="rounded p-1 text-muted-foreground hover:text-destructive"
                      onClick={(e) => { e.preventDefault(); e.stopPropagation(); if (confirm(tx('Delete this template?'))) deleteTemplate.mutate(t.id, { onError: (err) => toast.error(apiErrorMessage(err, tx('Could not delete'))) }); }}>
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        )} />
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
            {isError && <tr><td colSpan={8} className="p-3"><ErrorState message={tx('Could not load the quotations.')} onRetry={() => refetch()} /></td></tr>}
            {data?.data.map((q) => (
              <tr key={q.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => navigate(`${base}/${q.id}`)}>
                <td className="px-3 py-2 font-medium">{q.number}{q.revision > 0 && <span className="ml-1 text-xs text-muted-foreground">r{q.revision}</span>}</td>
                <td className="px-3 py-2">{q.partnerName}</td>
                <td className="px-3 py-2 text-muted-foreground">{q.dealName ?? '—'}</td>
                <td className="px-3 py-2">{q.ownerName ?? '—'}</td>
                <td className="px-3 py-2">{date(q.issueDate)}</td>
                <td className="px-3 py-2">{q.validUntil ? date(q.validUntil) : '—'}
                  {['approved', 'sent'].includes(q.status) && q.validityDaysLeft != null && q.validityDaysLeft >= 0 && q.validityDaysLeft <= 7 && (
                    <span className="ml-1.5 rounded-full bg-amber-100 px-1.5 text-[11px] text-amber-900 dark:bg-amber-950 dark:text-amber-200">{q.validityDaysLeft === 0 ? tx('today') : `${q.validityDaysLeft} ${tx('d left')}`}</span>
                  )}
                </td>
                <td className="px-3 py-2"><QuoteStatusPill status={q.status as QuoteStatus} /></td>
                <td className="px-3 py-2 text-right tabular-nums">{money(q.total, q.currencyCode)}</td>
              </tr>
            ))}
            {data && data.data.length === 0 && <tr><td colSpan={8} className="p-6 text-center text-muted-foreground">{tx('No quotations.')}</td></tr>}
          </tbody>
          {data && data.data.length > 0 && (
            <tfoot>
              {/* Audit fix 10: one total per currency, never different currencies added together. */}
              {(data.totals?.byCurrency?.length ? data.totals.byCurrency : [{ currencyCode: '', count: data.meta.total, total: data.totals?.total ?? 0 }]).map((t, i, all) => (
                <tr key={t.currencyCode || 'all'} className={cn('font-semibold', i === 0 && 'border-t')}>
                  <td colSpan={7} className="px-3 py-2">{i === 0 ? `${data.meta.total} ${tx('quotations')}` : ''}{all.length > 1 && <span className="ml-2 text-xs font-normal text-muted-foreground">{t.count} {tx('in')} {t.currencyCode}</span>}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{money(t.total, t.currencyCode || undefined)}</td>
                </tr>
              ))}
            </tfoot>
          )}
        </table>
        {data && data.meta.totalPages > 1 && (
          <DataTablePagination page={data.meta.page} pageSize={data.meta.pageSize} total={data.meta.total} totalPages={data.meta.totalPages} onPageChange={(p) => set('page', String(p))} />
        )}
      </div>
    </div>
  );
}

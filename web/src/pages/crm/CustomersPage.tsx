import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { AlertTriangle, Building2, CalendarPlus, FileText, Plus, Users } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { DataTablePagination, ListPageHeader, SearchInput } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date, money, relativeTime, statusLabel } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { crmApi, useCategoryOptions, useCrmMutation, useCustomer, useCustomers, useSavedFilters } from '@/features/crm/suite-api';
import { DealStatusPill, KpiCard, QuoteStatusPill, typeLabel } from './crm-ui';
import { OpportunityDialog, ScheduleDialog } from './crm-dialogs';

/** Customer directory with pipeline, revenue and receivables per customer (ADR-S010). */
export function CustomersPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const set = (k: string, v: string) => setParams((p) => { const n = new URLSearchParams(p); if (v) n.set(k, v); else n.delete(k); if (k !== 'page') n.delete('page'); return n; }, { replace: true });
  const { data, isLoading } = useCustomers({ q: params.get('q') || undefined, sort: params.get('sort') || undefined, creditHold: params.get('creditHold') || undefined, categoryId: params.get('categoryId') || undefined, page: Number(params.get('page') ?? 1), pageSize: 50 });
  const { data: categories } = useCategoryOptions('partner');
  const { data: segments } = useSavedFilters('customers');
  const saveSegment = useCrmMutation(crmApi.saveFilter);
  const [segmentName, setSegmentName] = useState('');
  const applySegment = (id: string) => {
    const segment = segments?.find((s) => s.id === id);
    if (segment) setParams(new URLSearchParams(segment.filters), { replace: true });
  };
  const currentFilters = () => {
    const out: Record<string, string> = {};
    for (const k of ['q', 'sort', 'creditHold', 'categoryId']) {
      const v = params.get(k);
      if (v) out[k] = v;
    }
    return out;
  };
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Users} title={tx('Customers')} description={tx('Customers and contacts with their pipeline, revenue and open receivables.')}
        actions={<Button variant="outline" asChild><Link to="/partners/new"><Plus className="mr-1 h-4 w-4" />{tx('New customer')}</Link></Button>} />
      <div className="flex flex-wrap items-center gap-2">
        <SearchInput value={params.get('q') ?? ''} onChange={(v) => set('q', v)} placeholder={tx('Name, code, email, phone…')} />
        <select className="h-9 rounded-md border bg-background px-2 text-sm" value={params.get('sort') ?? ''} onChange={(e) => set('sort', e.target.value)}>
          <option value="">{tx('Sort by name')}</option><option value="openValue">{tx('Open pipeline')}</option><option value="wonValue">{tx('Won revenue')}</option>
          <option value="receivable">{tx('Open receivables')}</option><option value="lastActivity">{tx('Last activity')}</option>
        </select>
        <label className="flex items-center gap-1.5 text-sm"><input type="checkbox" checked={params.get('creditHold') === 'true'} onChange={(e) => set('creditHold', e.target.checked ? 'true' : '')} />{tx('On credit hold')}</label>
        <select className="h-9 rounded-md border bg-background px-2 text-sm" value={params.get('categoryId') ?? ''} onChange={(e) => set('categoryId', e.target.value)}>
          <option value="">{tx('All categories')}</option>
          {(categories ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        <select className="h-9 rounded-md border bg-background px-2 text-sm" value="" onChange={(e) => applySegment(e.target.value)}>
          <option value="">{tx('Segments')}</option>
          {(segments ?? []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
        <Input className="h-9 w-40" placeholder={tx('Segment name')} value={segmentName} onChange={(e) => setSegmentName(e.target.value)} />
        <Button size="sm" variant="outline" disabled={!segmentName.trim() || saveSegment.isPending}
          onClick={() => saveSegment.mutate({ scope: 'customers', name: segmentName.trim(), filters: currentFilters(), shared: true }, { onSuccess: () => setSegmentName('') })}>
          {tx('Save segment')}
        </Button>
      </div>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[900px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            <tr><th className="px-3 py-2">{tx('Customer')}</th><th className="px-3 py-2">{tx('Contact')}</th><th className="px-3 py-2 text-right">{tx('Open opps')}</th>
              <th className="px-3 py-2 text-right">{tx('Pipeline')}</th><th className="px-3 py-2 text-right">{tx('Won')}</th><th className="px-3 py-2 text-right">{tx('Receivable')}</th>
              <th className="px-3 py-2">{tx('Credit')}</th><th className="px-3 py-2">{tx('Last activity')}</th></tr>
          </thead>
          <tbody>
            {isLoading && <tr><td colSpan={8} className="p-3"><Skeleton className="h-32 w-full" /></td></tr>}
            {data?.data.map((c) => (
              <tr key={c.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => navigate(`/crm/customers/${c.id}`)}>
                <td className="px-3 py-2"><span className="flex items-center gap-2">{c.isCompany ? <Building2 className="h-4 w-4 text-muted-foreground" /> : <Users className="h-4 w-4 text-muted-foreground" />}<span className="font-medium">{c.name}</span></span>
                  <span className="text-xs text-muted-foreground">{c.code}{c.categoryName ? ` · ${c.categoryName}` : ''}</span></td>
                <td className="px-3 py-2">{c.email ?? '—'}<div className="text-xs text-muted-foreground">{c.phone}</div></td>
                <td className="px-3 py-2 text-right tabular-nums">{c.openOpportunities}</td>
                <td className="px-3 py-2 text-right tabular-nums">{money(c.openValue)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{money(c.wonValue)}</td>
                <td className={cn('px-3 py-2 text-right tabular-nums', Number(c.overdueReceivables) > 0 && 'text-rose-600')}>{money(c.openReceivables)}</td>
                <td className="px-3 py-2">{c.creditHold ? <span className="inline-flex items-center gap-1 text-xs font-medium text-rose-700"><AlertTriangle className="h-3.5 w-3.5" />{tx('Hold')}</span>
                  : Number(c.creditLimit) > 0 ? <span className="text-xs text-muted-foreground">{money(c.creditLimit)}</span> : '—'}</td>
                <td className="px-3 py-2 text-muted-foreground">{c.lastActivityAt ? relativeTime(c.lastActivityAt) : '—'}</td>
              </tr>
            ))}
            {data && data.data.length === 0 && <tr><td colSpan={8} className="p-6 text-center text-muted-foreground">{tx('No customers found.')}</td></tr>}
          </tbody>
        </table>
        {data && data.meta.totalPages > 1 && (
          <DataTablePagination page={data.meta.page} pageSize={data.meta.pageSize} total={data.meta.total} totalPages={data.meta.totalPages} onPageChange={(p) => set('page', String(p))} />
        )}
      </div>
    </div>
  );
}

/** Customer 360: contacts, opportunities, quotations, orders, invoices, credit, special prices and activity. */
export function CustomerDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data, isLoading } = useCustomer(id);
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.dealWrite));
  const canQuote = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.quotationWrite));
  const [dialog, setDialog] = useState<'opp' | 'activity' | null>(null);
  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!data) return <p className="text-sm text-muted-foreground">{tx('Customer not found.')}</p>;
  const p = data.partner;
  const open = data.opportunities.filter((o) => o.status === 'open');
  const wonValue = data.opportunities.filter((o) => o.status === 'won').reduce((a, o) => a + Number(o.amount), 0);
  return (
    <div className="space-y-4">
      <div className="text-sm"><Link to="/crm/customers" className="font-medium text-primary hover:underline">{tx('Customers')}</Link><span className="mx-1.5 text-muted-foreground">/</span><span className="font-semibold">{p.name}</span></div>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">{p.name}</h1>
          <p className="text-sm text-muted-foreground">{[p.code, p.email, p.phone].filter(Boolean).join(' · ')}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {canWrite && <Button onClick={() => setDialog('opp')}><Plus className="mr-1 h-4 w-4" />{tx('New opportunity')}</Button>}
          {canQuote && <Button variant="secondary" onClick={() => navigate(`/crm/quotations/new?partnerId=${p.id}&partnerName=${encodeURIComponent(p.name)}`)}><FileText className="mr-1 h-4 w-4" />{tx('New quotation')}</Button>}
          <Button variant="outline" onClick={() => setDialog('activity')}><CalendarPlus className="mr-1 h-4 w-4" />{tx('Activity')}</Button>
          <Button variant="outline" asChild><Link to={`/partners/${p.id}`}>{tx('Edit contact')}</Link></Button>
        </div>
      </div>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <KpiCard label={tx('Open opportunities')} value={open.length} sub={money(open.reduce((a, o) => a + Number(o.amount), 0))} />
        <KpiCard label={tx('Won revenue')} value={money(wonValue)} tone="success" />
        <KpiCard label={tx('Open receivables')} value={money(data.credit?.openReceivables ?? 0)} sub={Number(data.credit?.overdueReceivables ?? 0) > 0 ? `${money(data.credit?.overdueReceivables ?? 0)} ${tx('overdue')}` : undefined} tone={Number(data.credit?.overdueReceivables ?? 0) > 0 ? 'danger' : undefined} />
        <KpiCard label={tx('Credit limit')} value={Number(data.credit?.creditLimit ?? 0) > 0 ? money(data.credit?.creditLimit ?? 0) : tx('None')} sub={data.credit?.creditHold ? tx('On credit hold') : data.credit?.headroom != null ? `${money(data.credit.headroom)} ${tx('headroom')}` : undefined} tone={data.credit?.creditHold ? 'danger' : undefined} />
        <KpiCard label={tx('Special prices')} value={data.priceRules.length} sub={tx('active price rules')} />
      </div>
      <Tabs defaultValue="opps">
        <TabsList className="flex-wrap">
          <TabsTrigger value="opps">{tx('Opportunities')} ({data.opportunities.length})</TabsTrigger>
          <TabsTrigger value="quotes">{tx('Quotations')} ({data.quotations.length})</TabsTrigger>
          <TabsTrigger value="orders">{tx('Orders')} ({data.orders.length})</TabsTrigger>
          <TabsTrigger value="invoices">{tx('Invoices')} ({data.invoices.length})</TabsTrigger>
          <TabsTrigger value="contacts">{tx('Contacts')} ({p.contacts.length})</TabsTrigger>
          <TabsTrigger value="prices">{tx('Prices')}</TabsTrigger>
          <TabsTrigger value="activity">{tx('Activity')}</TabsTrigger>
        </TabsList>
        <TabsContent value="opps"><SimpleTable rows={data.opportunities.map((o) => ({ key: o.id, link: `/crm/opportunities/${o.id}`, cells: [o.name, o.stageName ?? '', <DealStatusPill key="s" status={o.status} />, o.ownerName ?? '', money(o.amount, o.currencyCode)] }))} /></TabsContent>
        <TabsContent value="quotes"><SimpleTable rows={data.quotations.map((q) => ({ key: q.id, link: `/crm/quotations/${q.id}`, cells: [q.number, date(q.issueDate), <QuoteStatusPill key="s" status={q.status} />, q.dealName ?? '', money(q.total, q.currencyCode)] }))} /></TabsContent>
        <TabsContent value="orders"><SimpleTable rows={data.orders.map((o) => ({ key: o.id, link: `/orders/${o.id}`, cells: [o.orderNumber, date(o.createdAt), statusLabel(o.status), o.invoiceId ? tx('Invoiced') : '', money(o.total)] }))} /></TabsContent>
        <TabsContent value="invoices"><SimpleTable rows={data.invoices.map((d) => ({ key: d.id, link: `/invoices/${d.id}`, cells: [d.documentNumber, date(d.issueDate), statusLabel(d.documentType), statusLabel(d.paymentStatus), money(d.amountResidual) + ' / ' + money(d.totalAmount)] }))} /></TabsContent>
        <TabsContent value="contacts"><SimpleTable rows={p.contacts.map((c) => ({ key: c.id, cells: [[c.firstName, c.lastName].filter(Boolean).join(' ') + (c.isPrimary ? ' ★' : ''), c.position ?? '', c.email ?? '', c.phone ?? '', ''] }))} /></TabsContent>
        <TabsContent value="prices"><SimpleTable rows={data.priceRules.map((r) => ({ key: r.id, link: '/crm/pricing', cells: [r.name, r.productName ?? tx('All products'), r.kind === 'fixed_price' ? money(r.value) : `${r.value}% ${tx('off')}`, Number(r.minQuantity) > 0 ? `≥ ${r.minQuantity}` : '', [r.validFrom, r.validTo].filter(Boolean).join(' → ')] }))} /></TabsContent>
        <TabsContent value="activity"><SimpleTable rows={data.activities.map((a) => ({ key: a.id, link: a.dealId ? `/crm/opportunities/${a.dealId}` : undefined, cells: [a.title, typeLabel(a.type), statusLabel(a.status), a.outcome ?? '', relativeTime(a.occurredAt)] }))} /></TabsContent>
      </Tabs>
      <OpportunityDialog open={dialog === 'opp'} onOpenChange={(v) => !v && setDialog(null)} kind="opportunity" initial={null}
        onSaved={(o) => navigate(`/crm/opportunities/${o.id}`)} defaults={{ partnerId: p.id, partnerName: p.name }} />
      <ScheduleDialog open={dialog === 'activity'} onOpenChange={(v) => !v && setDialog(null)} partnerId={p.id} />
    </div>
  );
}

function SimpleTable({ rows }: { rows: Array<{ key: string; link?: string; cells: ReactNode[] }> }) {
  const navigate = useNavigate();
  if (rows.length === 0) return <p className="p-4 text-sm text-muted-foreground">{tx('Nothing yet.')}</p>;
  return (
    <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
      <table className="w-full min-w-[640px] text-sm">
        <tbody>
          {rows.map((r) => (
            <tr key={r.key} className={cn('border-b last:border-0', r.link && 'cursor-pointer hover:bg-muted/30')} onClick={() => r.link && navigate(r.link)}>
              {r.cells.map((c, i) => <td key={i} className={cn('px-3 py-2', i === 0 && 'font-medium', i === r.cells.length - 1 && 'text-right tabular-nums')}>{c}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Inbox, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTablePagination, ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useCrmLookups, useOpportunities, type Opportunity } from '@/features/crm/suite-api';
import { ActivityBadge, Avatar, DealStatusPill, Stars, TagChips } from './crm-ui';
import { ConvertDialog, OpportunityDialog } from './crm-dialogs';
import { CrmFilterBar, useCrmFilters } from './filter-bar';

/** Lead inbox: unqualified enquiries before they enter the pipeline (ADR-S010). */
export function LeadsPage() {
  const state = useCrmFilters({ status: 'open' });
  const navigate = useNavigate();
  const { data: lk } = useCrmLookups();
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.leadWrite));
  const [creating, setCreating] = useState(false);
  const [converting, setConverting] = useState<Opportunity | null>(null);
  const { data, isLoading } = useOpportunities({ ...state.filters, page: state.page, pageSize: 50 } as Record<string, string | number>, '/crm/leads');

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Inbox} title={tx('Leads')} description={tx('Qualify incoming enquiries, then convert the good ones into opportunities.')}
        actions={canWrite && <Button onClick={() => setCreating(true)}><Plus className="mr-1 h-4 w-4" />{tx('New lead')}</Button>} />
      <CrmFilterBar scope="leads" state={state} groupByOptions={[]} />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[900px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            <tr>
              <th className="px-3 py-2">{tx('Lead')}</th><th className="px-3 py-2">{tx('Company / contact')}</th>
              <th className="px-3 py-2">{tx('Email / phone')}</th><th className="px-3 py-2">{tx('Source')}</th>
              <th className="px-3 py-2">{tx('Salesperson')}</th><th className="px-3 py-2">{tx('Created')}</th>
              <th className="px-3 py-2">{tx('Status')}</th><th className="px-3 py-2" />
            </tr>
          </thead>
          <tbody>
            {isLoading && <tr><td colSpan={8} className="p-3"><Skeleton className="h-32 w-full" /></td></tr>}
            {data?.data.map((l) => (
              <tr key={l.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => navigate(`/crm/opportunities/${l.id}`)}>
                <td className="px-3 py-2">
                  <div className="flex items-center gap-2"><ActivityBadge state={l.activityState} type={l.nextActivity?.type} /><span className="font-medium">{l.name}</span></div>
                  <div className="mt-1 flex items-center gap-2"><Stars value={l.priority} /><TagChips ids={l.tagIds} tags={lk?.tags ?? []} /></div>
                </td>
                <td className="px-3 py-2">{l.partnerName ?? l.companyName ?? '—'}<div className="text-xs text-muted-foreground">{l.contactName}</div></td>
                <td className="px-3 py-2">{l.email ?? '—'}<div className="text-xs text-muted-foreground">{l.phone}</div></td>
                <td className="px-3 py-2">{lk?.sources.find((s) => s.id === l.sourceId)?.name ?? '—'}</td>
                <td className="px-3 py-2"><span className="flex items-center gap-2"><Avatar name={l.ownerName} />{l.ownerName ?? tx('Unassigned')}</span></td>
                <td className="px-3 py-2 text-muted-foreground">{date(l.createdAt)}</td>
                <td className="px-3 py-2"><DealStatusPill status={l.status} /></td>
                <td className="px-3 py-2 text-right">
                  {canWrite && l.status === 'open' && (
                    <Button size="sm" variant="outline" onClick={(e) => { e.stopPropagation(); setConverting(l); }}>{tx('Convert')}</Button>
                  )}
                </td>
              </tr>
            ))}
            {data && data.data.length === 0 && <tr><td colSpan={8} className="p-6 text-center text-muted-foreground">{tx('No leads match these filters.')}</td></tr>}
          </tbody>
        </table>
        {data && data.meta.totalPages > 1 && (
          <DataTablePagination page={data.meta.page} pageSize={data.meta.pageSize} total={data.meta.total} totalPages={data.meta.totalPages} onPageChange={state.setPage} />
        )}
      </div>
      <OpportunityDialog open={creating} onOpenChange={setCreating} kind="lead" onSaved={(o) => navigate(`/crm/opportunities/${o.id}`)} />
      {converting && <ConvertDialog open onOpenChange={(v) => !v && setConverting(null)} lead={converting} onDone={(o) => navigate(`/crm/opportunities/${o.id}`)} />}
    </div>
  );
}

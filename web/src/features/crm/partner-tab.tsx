import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AlertCircle, ArrowRight, CalendarPlus, Handshake, KanbanSquare, Plus, Trophy } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { useAuthStore } from '@/stores/auth.store';
import { date, money, relativeTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useCustomer } from './suite-api';
import { DealStatusPill, EmptyState, ErrorState, KpiCard, Panel, Reveal, typeLabel } from '@/pages/crm/crm-ui';
import { OpportunityDialog, ScheduleDialog } from '@/pages/crm/crm-dialogs';

/**
 * CRM tab of the customer / partner page, on the sales-suite API (the legacy /crm/deals screens are gone): pipeline
 * figures, this partner's opportunities and recent activity, with one-click New opportunity / Schedule and a link to
 * the full Customer 360.
 */
export function CrmPartnerTab({ partnerId }: { partnerId: string }) {
  const navigate = useNavigate();
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.dealWrite));
  const canActivity = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.activityWrite));
  const [dialog, setDialog] = useState<'opp' | 'activity' | null>(null);
  const { data, isLoading, isError, refetch } = useCustomer(partnerId);

  if (isLoading) return <div className="grid gap-4 sm:grid-cols-3">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-24 rounded-2xl" />)}</div>;
  if (isError || !data) return <ErrorState message={tx('Could not load the CRM data for this partner.')} onRetry={() => refetch()} />;

  const opps = data.opportunities;
  const cur = opps[0]?.currencyCode;
  const open = opps.filter((o) => o.status === 'open');
  const won = opps.filter((o) => o.status === 'won');
  const sum = (rows: typeof opps) => rows.reduce((a, o) => a + Number(o.amount ?? 0), 0);

  return (
    <div className="space-y-4">
      <Reveal>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <Link to={`/crm/customers/${partnerId}`} className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline">
            {tx('Open Customer 360')}<ArrowRight className="h-3.5 w-3.5" />
          </Link>
          <div className="flex gap-2">
            {canActivity && <Button size="sm" variant="outline" onClick={() => setDialog('activity')}><CalendarPlus className="mr-1.5 h-4 w-4" />{tx('Schedule')}</Button>}
            {canWrite && <Button size="sm" onClick={() => setDialog('opp')}><Plus className="mr-1.5 h-4 w-4" />{tx('New opportunity')}</Button>}
          </div>
        </div>
      </Reveal>
      <Reveal delay={60}>
        <div className="grid gap-3 sm:grid-cols-3">
          <KpiCard icon={KanbanSquare} label={tx('Open pipeline')} value={money(sum(open), cur)} sub={`${open.length} ${tx('open opportunities')}`} />
          <KpiCard icon={Trophy} label={tx('Won')} value={money(sum(won), cur)} sub={`${won.length} ${tx('deals won')}`} tone={won.length ? 'success' : undefined} />
          <KpiCard icon={AlertCircle} label={tx('Open receivable')} value={money(data.credit?.openReceivables ?? 0)}
            sub={data.credit?.overdueReceivables ? `${money(data.credit.overdueReceivables)} ${tx('overdue')}` : tx('Nothing overdue')}
            tone={data.credit?.overdueReceivables ? 'danger' : undefined} />
        </div>
      </Reveal>
      <Reveal delay={120}>
        <div className="grid gap-4 lg:grid-cols-2">
          <Panel title={tx('Opportunities')} icon={Handshake} href={`/crm/customers/${partnerId}`} action={tx('See all')}>
            {opps.length === 0
              ? <EmptyState icon={Handshake} title={tx('No opportunities for this partner yet.')} className="py-6" />
              : (
                <ul className="-mx-2 divide-y">
                  {opps.slice(0, 8).map((o) => (
                    <li key={o.id}>
                      <Link to={`/crm/${o.kind === 'lead' ? 'leads' : 'opportunities'}/${o.id}`} className="flex items-center gap-3 rounded-lg px-2 py-2.5 text-sm transition-colors hover:bg-muted/50">
                        <div className="min-w-0 flex-1">
                          <p className="truncate font-medium">{o.name}</p>
                          <p className="truncate text-xs text-muted-foreground">{o.stageName ?? '—'}{o.expectedClose ? ` · ${date(o.expectedClose)}` : ''}</p>
                        </div>
                        <DealStatusPill status={o.status} />
                        <span className="w-28 text-right font-medium tabular-nums">{money(o.amount, o.currencyCode)}</span>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
          </Panel>
          <Panel title={tx('Recent activity')} icon={CalendarPlus}>
            {data.activities.length === 0
              ? <EmptyState icon={CalendarPlus} title={tx('No activity logged for this partner.')} className="py-6" />
              : (
                <ol className="relative space-y-3 border-l pl-4">
                  {data.activities.slice(0, 10).map((a) => (
                    <li key={a.id} className="text-sm">
                      <span className="absolute -left-1.5 mt-1.5 h-3 w-3 rounded-full border-2 border-background bg-primary" />
                      <p className="font-medium">{a.title}</p>
                      <p className="text-[11px] text-muted-foreground">{typeLabel(a.type)} · {relativeTime(a.occurredAt)}{a.outcome ? ` · ${a.outcome}` : ''}</p>
                    </li>
                  ))}
                </ol>
              )}
          </Panel>
        </div>
      </Reveal>
      <OpportunityDialog open={dialog === 'opp'} onOpenChange={(v) => !v && setDialog(null)} kind="opportunity"
        defaults={{ partnerId, partnerName: data.partner.name as string }} onSaved={(o) => navigate(`/crm/opportunities/${o.id}`)} />
      <ScheduleDialog open={dialog === 'activity'} onOpenChange={(v) => !v && setDialog(null)} partnerId={partnerId} />
    </div>
  );
}

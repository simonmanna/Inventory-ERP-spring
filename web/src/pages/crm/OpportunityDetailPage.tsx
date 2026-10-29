import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';
import {
  ArrowRightLeft, CalendarPlus, Check, FileText, Mail, Pencil, Phone, RotateCcw, Trash2, Trophy, X,
} from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { date, dateTime, money, relativeTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  crmApi, useCrmLookups, useCrmMutation, useOpportunity, useTimeline, type OpportunityDetail, type TimelineItem,
} from '@/features/crm/suite-api';
import { ActivityBadge, Avatar, QuoteStatusPill, Stars, TYPE_ICON, TagChips, colorOf, typeLabel } from './crm-ui';
import { ConvertDialog, DoneDialog, LostDialog, OpportunityDialog, ScheduleDialog } from './crm-dialogs';

function duration(seconds: number) {
  const d = Math.floor(seconds / 86400);
  if (d >= 1) return `${d}d`;
  const h = Math.floor(seconds / 3600);
  return h >= 1 ? `${h}h` : `${Math.max(1, Math.floor(seconds / 60))}m`;
}

/** Lead / opportunity form: stage bar, won / lost, quotations, planned activities and the history chatter. */
export function OpportunityDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: o, isLoading, isError } = useOpportunity(id);
  const { data: lk } = useCrmLookups();
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.dealWrite));
  const canQuote = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.quotationWrite));
  const [dialog, setDialog] = useState<'edit' | 'lost' | 'schedule' | 'convert' | null>(null);
  const [doneId, setDoneId] = useState<string | null>(null);
  const move = useCrmMutation(crmApi.move);
  const won = useCrmMutation(crmApi.won);
  const reopen = useCrmMutation(crmApi.reopen);
  const archive = useCrmMutation(crmApi.archive);
  const prio = useCrmMutation(crmApi.update);
  const cancelAct = useCrmMutation(crmApi.cancelActivity);
  const err = (m: string) => (e: unknown) => toast.error(apiErrorMessage(e, m));

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (isError || !o) return <p className="text-sm text-muted-foreground">{tx('Opportunity not found.')}</p>;
  const isLead = o.kind === 'lead';
  const stages = (lk?.stages ?? []).filter((s) => s.isActive && (!s.teamId || s.teamId === o.teamId));
  const durations = new Map(o.stageDurations.map((d) => [d.stageId, Number(d.seconds)]));

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm">
          <Link to={isLead ? '/crm/leads' : '/crm/pipeline'} className="font-medium text-primary hover:underline">{isLead ? tx('Leads') : tx('Pipeline')}</Link>
          <span className="mx-1.5 text-muted-foreground">/</span>
          <span className="font-semibold">{o.name}</span>
        </div>
        <div className="flex gap-2">
          <Link to={`/crm/quotations?dealId=${o.id}`} className="flex items-center gap-2 rounded-lg border bg-card px-3 py-1.5 text-sm shadow-sm hover:bg-muted">
            <FileText className="h-4 w-4 text-primary" /><span><span className="block text-xs text-muted-foreground">{tx('Quotations')}</span><strong>{o.quotationCount}</strong></span>
          </Link>
          {o.partner && (
            <Link to={`/crm/customers/${o.partner.id}`} className="flex items-center gap-2 rounded-lg border bg-card px-3 py-1.5 text-sm shadow-sm hover:bg-muted">
              <Avatar name={o.partner.name} /><span><span className="block text-xs text-muted-foreground">{tx('Customer')}</span><strong className="line-clamp-1 max-w-[140px]">{o.partner.name}</strong></span>
            </Link>
          )}
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap gap-2">
          {isLead && canWrite && <Button onClick={() => setDialog('convert')}><ArrowRightLeft className="mr-1.5 h-4 w-4" />{tx('Convert to opportunity')}</Button>}
          {!isLead && canQuote && o.status !== 'lost' && (
            <Button onClick={() => navigate(`/crm/quotations/new?dealId=${o.id}`)} disabled={!o.partnerId} title={!o.partnerId ? tx('Set a customer first') : undefined}>{tx('New quotation')}</Button>
          )}
          {!isLead && canWrite && o.status === 'open' && (
            <Button variant="secondary" disabled={won.isPending} onClick={() => won.mutate(o.id, { onSuccess: () => toast.success(tx('Won! 🎉')), onError: err(tx('Could not mark won')) })}><Trophy className="mr-1.5 h-4 w-4" />{tx('Won')}</Button>
          )}
          {canWrite && o.status === 'open' && <Button variant="secondary" onClick={() => setDialog('lost')}><X className="mr-1.5 h-4 w-4" />{tx('Lost')}</Button>}
          {canWrite && o.status !== 'open' && (
            <Button variant="secondary" onClick={() => reopen.mutate(o.id, { onError: err(tx('Could not restore')) })}><RotateCcw className="mr-1.5 h-4 w-4" />{tx('Restore')}</Button>
          )}
          {canWrite && <Button variant="outline" onClick={() => setDialog('edit')}><Pencil className="mr-1.5 h-4 w-4" />{tx('Edit')}</Button>}
          <Button variant="outline" onClick={() => setDialog('schedule')}><CalendarPlus className="mr-1.5 h-4 w-4" />{tx('Activity')}</Button>
          {canWrite && o.status !== 'won' && (
            <Button variant="ghost" className="text-destructive" onClick={() => {
              if (!confirm(tx('Archive this record?'))) return;
              archive.mutate(o.id, { onSuccess: () => navigate(isLead ? '/crm/leads' : '/crm/pipeline'), onError: err(tx('Could not archive')) });
            }}><Trash2 className="h-4 w-4" /></Button>
          )}
        </div>
        {!isLead && (
          <div className="flex max-w-full overflow-x-auto rounded-md border bg-card text-sm shadow-sm">
            {stages.map((s, i) => {
              const current = s.id === o.stageId;
              const secs = current ? (Date.now() - new Date(o.stageChangedAt).getTime()) / 1000 : durations.get(s.id);
              return (
                <button key={s.id} disabled={!canWrite || current || o.status === 'lost'}
                  onClick={() => move.mutate({ id: o.id, stageId: s.id }, { onError: err(tx('Could not move')) })}
                  className={cn('relative whitespace-nowrap px-3 py-1.5 transition-colors', i > 0 && 'border-l',
                    current ? 'bg-primary/10 font-semibold text-primary' : 'text-muted-foreground hover:bg-muted hover:text-foreground')}>
                  {s.name}{current && secs ? <span className="ml-1.5 text-xs font-normal">{duration(secs)}</span> : null}
                </button>
              );
            })}
          </div>
        )}
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_380px]">
        <div className="relative overflow-hidden rounded-xl border bg-card p-5 shadow-sm">
          {o.status !== 'open' && (
            <div className={cn('absolute right-[-40px] top-5 w-40 rotate-45 py-1 text-center text-xs font-bold uppercase text-white', o.status === 'won' ? 'bg-emerald-500' : 'bg-rose-500')}>
              {o.status === 'won' ? tx('Won') : tx('Lost')}
            </div>
          )}
          {isLead && <span className="mb-2 inline-block rounded bg-sky-100 px-2 py-0.5 text-xs font-medium text-sky-800">{tx('Lead')}</span>}
          <h1 className="pr-16 text-3xl font-semibold tracking-tight">{o.name}</h1>
          <div className="mt-4 flex flex-wrap items-end gap-x-8 gap-y-2">
            <div><p className="text-sm font-medium text-muted-foreground">{tx('Expected revenue')}</p><p className="text-2xl tabular-nums">{money(o.amount, o.currencyCode)}</p></div>
            <div><p className="text-sm font-medium text-muted-foreground">{tx('Probability')}</p><p className="text-2xl tabular-nums"><span className="text-muted-foreground">{tx('at')}</span> {Number(o.probability).toFixed(0)} %</p></div>
            <div><p className="text-sm font-medium text-muted-foreground">{tx('Weighted')}</p><p className="text-lg tabular-nums text-muted-foreground">{money(o.weightedAmount, o.currencyCode)}</p></div>
          </div>
          <div className="mt-6 grid gap-x-10 gap-y-3 text-sm sm:grid-cols-2">
            <Info label={tx('Customer')} value={o.partner ? <Link className="text-primary hover:underline" to={`/crm/customers/${o.partner.id}`}>{o.partner.name}</Link> : o.companyName} />
            <Info label={tx('Salesperson')} value={<span className="flex items-center gap-2"><Avatar name={o.ownerName} />{o.ownerName ?? tx('Unassigned')}</span>} />
            <Info label={tx('Contact')} value={o.contactName} />
            <Info label={tx('Expected closing')} value={o.expectedClose ? date(o.expectedClose) : null} />
            <Info label={tx('Email')} value={o.email && <a className="inline-flex items-center gap-1 text-primary hover:underline" href={`mailto:${o.email}`}><Mail className="h-3.5 w-3.5" />{o.email}</a>} />
            <Info label={tx('Priority')} value={<Stars value={o.priority} onChange={canWrite ? (p) => prio.mutate({ id: o.id, priority: p }) : undefined} size="md" />} />
            <Info label={tx('Phone')} value={o.phone && <a className="inline-flex items-center gap-1 text-primary hover:underline" href={`tel:${o.phone}`}><Phone className="h-3.5 w-3.5" />{o.phone}</a>} />
            <Info label={tx('Tags')} value={<TagChips ids={o.tagIds} tags={lk?.tags ?? []} max={8} />} />
          </div>
          <Tabs defaultValue="notes" className="mt-6">
            <TabsList>
              <TabsTrigger value="notes">{tx('Internal notes')}</TabsTrigger>
              <TabsTrigger value="extra">{tx('Extra information')}</TabsTrigger>
              {!isLead && <TabsTrigger value="quotes">{tx('Quotations')} ({o.quotations.length})</TabsTrigger>}
            </TabsList>
            <TabsContent value="notes" className="whitespace-pre-wrap pt-3 text-sm text-muted-foreground">{o.notes || tx('No notes. Use Edit to add internal notes.')}</TabsContent>
            <TabsContent value="extra" className="pt-3">
              <div className="grid gap-x-10 gap-y-3 text-sm sm:grid-cols-2">
                <Info label={tx('Source')} value={lk?.sources.find((s) => s.id === o.sourceId)?.name} />
                <Info label={tx('Sales team')} value={lk?.teams.find((s) => s.id === o.teamId)?.name} />
                <Info label={tx('City')} value={o.city} />
                <Info label={tx('Country')} value={o.country} />
                <Info label={tx('Created')} value={dateTime(o.createdAt)} />
                <Info label={tx('In stage since')} value={`${date(o.stageChangedAt)} (${o.daysInStage} ${tx('days')})`} />
                {o.convertedAt && <Info label={tx('Converted from lead')} value={date(o.convertedAt)} />}
                {o.wonAt && <Info label={tx('Won on')} value={date(o.wonAt)} />}
                {o.status === 'lost' && <Info label={tx('Lost reason')} value={(lk?.lostReasons.find((r) => r.id === o.lostReasonId)?.name ?? tx('Not recorded')) + (o.lostNote ? ` — ${o.lostNote}` : '')} />}
              </div>
            </TabsContent>
            <TabsContent value="quotes" className="pt-3">
              <QuotesTab o={o} />
            </TabsContent>
          </Tabs>
        </div>

        <div className="space-y-4">
          <div className="rounded-xl border bg-card p-4 shadow-sm">
            <div className="mb-2 flex items-center justify-between">
              <h2 className="text-sm font-semibold">{tx('Planned activities')}</h2>
              <Button size="sm" variant="ghost" onClick={() => setDialog('schedule')}><CalendarPlus className="mr-1 h-4 w-4" />{tx('Schedule')}</Button>
            </div>
            {o.plannedActivities.length === 0 && <p className="text-xs text-muted-foreground">{tx('Nothing planned. Schedule the next step so the deal does not go cold.')}</p>}
            <div className="space-y-2">
              {o.plannedActivities.map((a) => {
                const due = a.dueAt ? new Date(a.dueAt) : null;
                const state = !due ? 'planned' : due < new Date(new Date().toDateString()) ? 'overdue' : due.toDateString() === new Date().toDateString() ? 'today' : 'planned';
                return (
                  <div key={a.id} className="flex items-start gap-2 rounded-lg border p-2 text-sm">
                    <ActivityBadge state={state} type={a.type} />
                    <div className="min-w-0 flex-1">
                      <p className="font-medium">{a.title}</p>
                      <p className={cn('text-xs', state === 'overdue' ? 'text-rose-600' : state === 'today' ? 'text-amber-600' : 'text-muted-foreground')}>
                        {typeLabel(a.type)} · {due ? relativeTime(a.dueAt) : tx('no due date')} · {a.assignedToName ?? ''}
                      </p>
                      {a.body && <p className="mt-0.5 text-xs text-muted-foreground">{a.body}</p>}
                    </div>
                    <div className="flex gap-1">
                      <button title={tx('Mark done')} className="rounded p-1 text-emerald-600 hover:bg-emerald-50" onClick={() => setDoneId(a.id)}><Check className="h-4 w-4" /></button>
                      <button title={tx('Cancel')} className="rounded p-1 text-muted-foreground hover:bg-muted" onClick={() => cancelAct.mutate(a.id)}><X className="h-4 w-4" /></button>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
          <Chatter id={o.id} />
        </div>
      </div>

      <OpportunityDialog open={dialog === 'edit'} onOpenChange={(v) => !v && setDialog(null)} kind={o.kind} initial={o} />
      <LostDialog open={dialog === 'lost'} onOpenChange={(v) => !v && setDialog(null)} id={o.id} />
      <ScheduleDialog open={dialog === 'schedule'} onOpenChange={(v) => !v && setDialog(null)} dealId={o.id} />
      <ConvertDialog open={dialog === 'convert'} onOpenChange={(v) => !v && setDialog(null)} lead={o} />
      <DoneDialog open={!!doneId} onOpenChange={(v) => !v && setDoneId(null)} activityId={doneId ?? ''} />
    </div>
  );
}

function Info({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="grid grid-cols-[130px_minmax(0,1fr)] items-center gap-2">
      <span className="font-medium text-foreground/80">{label}</span>
      <span className="min-w-0 truncate">{value || <span className="text-muted-foreground">—</span>}</span>
    </div>
  );
}

function QuotesTab({ o }: { o: OpportunityDetail }) {
  if (o.quotations.length === 0) return <p className="text-sm text-muted-foreground">{tx('No quotations yet.')}</p>;
  return (
    <div className="divide-y rounded-lg border">
      {o.quotations.map((q) => (
        <Link key={q.id} to={`/crm/quotations/${q.id}`} className="flex items-center justify-between gap-3 px-3 py-2 text-sm hover:bg-muted/40">
          <span className="font-medium">{q.number}</span>
          <span className="text-muted-foreground">{date(q.issueDate)}</span>
          <QuoteStatusPill status={q.status} />
          <span className="tabular-nums">{money(q.total, q.currencyCode)}</span>
        </Link>
      ))}
    </div>
  );
}

function describe(t: TimelineItem): { title: string; body?: string | null } {
  if (t.source === 'stage') {
    if (t.toStatus === 'won') return { title: tx('Marked as won') };
    if (t.toStatus === 'lost') return { title: tx('Marked as lost') };
    if (!t.fromStage) return { title: tx('Created in') + ' ' + (t.toStage ?? '') };
    return { title: `${t.fromStage ?? '—'} → ${t.toStage ?? '—'}`, body: t.secondsInStage ? tx('after') + ' ' + duration(t.secondsInStage) : null };
  }
  if (t.source === 'audit') {
    const keys = t.newValues ? Object.keys(t.newValues).filter((k) => !['name', 'kind'].includes(k) || t.action !== 'create') : [];
    const action = t.action === 'create' ? tx('Created') : t.action === 'delete' ? tx('Archived') : t.action === 'restore' ? tx('Restored') : tx('Updated');
    return { title: action, body: t.action === 'update' && keys.length ? keys.join(', ') : null };
  }
  return { title: t.title ?? '', body: [t.outcome, t.body].filter(Boolean).join(' — ') || null };
}

/** History chatter: logged activities, stage moves and audited changes. */
function Chatter({ id }: { id: string }) {
  const { data } = useTimeline(id);
  return (
    <div className="rounded-xl border bg-card p-4 shadow-sm">
      <h2 className="mb-3 text-sm font-semibold">{tx('History')}</h2>
      <ol className="relative space-y-3 border-l pl-4">
        {(data ?? []).map((t) => {
          const d = describe(t);
          const Icon = t.source === 'activity' ? TYPE_ICON[t.type ?? ''] : null;
          return (
            <li key={`${t.source}:${t.id}`} className="text-sm">
              <span className={cn('absolute -left-1.5 mt-1.5 h-3 w-3 rounded-full border-2 border-background',
                t.source === 'stage' ? colorOf(t.toStatus === 'won' ? 'emerald' : t.toStatus === 'lost' ? 'rose' : 'sky').dot : t.source === 'audit' ? 'bg-slate-300' : 'bg-primary')} />
              <p className="flex items-center gap-1.5 font-medium">{Icon && <Icon className="h-3.5 w-3.5 text-muted-foreground" />}{d.title}</p>
              {d.body && <p className="text-xs text-muted-foreground">{d.body}</p>}
              <p className="text-[11px] text-muted-foreground">{t.userName ?? tx('System')} · {relativeTime(t.at)}</p>
            </li>
          );
        })}
        {data && data.length === 0 && <li className="text-xs text-muted-foreground">{tx('No history yet.')}</li>}
      </ol>
    </div>
  );
}

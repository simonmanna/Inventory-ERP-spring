import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Columns3, List, Mail, Plus, Search } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { relativeTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { PRIORITY_CLASS, useMoveStage, useTeams, useTickets, type HdTicket } from '@/features/helpdesk/api';
import { NewTicketDialog } from './TicketDialogs';
import { channelLabel } from '@/features/helpdesk/labels';

/** Helpdesk tickets: kanban board per team (drag between stages) or list, with filters. */
export function TicketsPage() {
  const [params, setParams] = useSearchParams();
  const { data: teams } = useTeams();
  const teamId = params.get('team') ?? teams?.find((t) => t.isActive)?.id;
  const view = params.get('view') === 'list' ? 'list' : 'board';
  const mine = params.get('mine') === 'true';
  const [q, setQ] = useState('');
  const [priority, setPriority] = useState('all');
  const [open, setOpen] = useState(false);
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.helpdesk.ticketWrite));
  const move = useMoveStage();
  const team = teams?.find((t) => t.id === teamId);

  const { data, isLoading } = useTickets({
    teamId, mine: mine || undefined, q: q || undefined, priority: priority === 'all' ? undefined : priority,
    state: view === 'list' ? params.get('state') ?? 'open' : undefined, pageSize: 200,
  });
  const tickets = useMemo(() => data?.data ?? [], [data]);
  const byStage = useMemo(() => {
    const m = new Map<string, HdTicket[]>();
    tickets.forEach((t) => m.set(t.stageId, [...(m.get(t.stageId) ?? []), t]));
    return m;
  }, [tickets]);

  const set = (k: string, v?: string) => {
    const n = new URLSearchParams(params);
    if (v) n.set(k, v); else n.delete(k);
    setParams(n, { replace: true });
  };

  const drop = async (ticketId: string, stageId: string) => {
    const t = tickets.find((x) => x.id === ticketId);
    if (!t || t.stageId === stageId) return;
    try {
      await move.mutateAsync({ id: ticketId, stageId });
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not move the ticket')));
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="mr-auto text-2xl font-semibold">{tx('Tickets')}</h1>
        <Select value={teamId ?? ''} onValueChange={(v) => set('team', v)}>
          <SelectTrigger className="w-44"><SelectValue placeholder={tx('Team')} /></SelectTrigger>
          <SelectContent>{(teams ?? []).map((t) => <SelectItem key={t.id} value={t.id}>{t.name}</SelectItem>)}</SelectContent>
        </Select>
        <Select value={priority} onValueChange={setPriority}>
          <SelectTrigger className="w-32"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="all">{tx('All priorities')}</SelectItem>
            {['urgent', 'high', 'normal', 'low'].map((p) => <SelectItem key={p} value={p}>{tx(p)}</SelectItem>)}
          </SelectContent>
        </Select>
        <div className="relative w-48">
          <Search className="absolute left-2 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input className="pl-8" value={q} onChange={(e) => setQ(e.target.value)} placeholder={tx('Search…')} />
        </div>
        <Button variant={mine ? 'secondary' : 'outline'} size="sm" onClick={() => set('mine', mine ? undefined : 'true')}>
          {tx('My tickets')}
        </Button>
        <div className="flex rounded-md border">
          <Button variant={view === 'board' ? 'secondary' : 'ghost'} size="icon" className="h-9 w-9" onClick={() => set('view')}>
            <Columns3 className="h-4 w-4" />
          </Button>
          <Button variant={view === 'list' ? 'secondary' : 'ghost'} size="icon" className="h-9 w-9" onClick={() => set('view', 'list')}>
            <List className="h-4 w-4" />
          </Button>
        </div>
        {canWrite && <Button onClick={() => setOpen(true)}><Plus className="mr-1 h-4 w-4" />{tx('New ticket')}</Button>}
      </div>

      {isLoading ? <Skeleton className="h-96" /> : view === 'board' ? (
        <div className="flex gap-3 overflow-x-auto pb-2">
          {(team?.stages ?? []).map((s) => (
            <div key={s.id} className={cn('flex w-72 shrink-0 flex-col rounded-lg border bg-muted/30', s.folded && 'w-56 opacity-80')}
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => {
                const id = e.dataTransfer.getData('application/x-hd-ticket');
                if (id) void drop(id, s.id);
              }}>
              <div className="flex items-center justify-between border-b px-3 py-2 text-sm font-medium">
                <span>{s.name}</span>
                <Badge variant="outline">{byStage.get(s.id)?.length ?? 0}</Badge>
              </div>
              <div className="flex min-h-[200px] flex-col gap-2 p-2">
                {(byStage.get(s.id) ?? []).map((t) => <TicketCard key={t.id} t={t} draggable={canWrite} />)}
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <table className="w-full text-sm">
            <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
              <tr>
                <th className="p-2">{tx('Number')}</th><th className="p-2">{tx('Subject')}</th><th className="p-2">{tx('Customer')}</th>
                <th className="p-2">{tx('Stage')}</th><th className="p-2">{tx('Priority')}</th><th className="p-2">{tx('Assignee')}</th>
                <th className="p-2">{tx('Updated')}</th>
              </tr>
            </thead>
            <tbody>
              {tickets.map((t) => (
                <tr key={t.id} className="border-t hover:bg-accent/40">
                  <td className="whitespace-nowrap p-2 font-mono text-xs"><Link to={`/helpdesk/tickets/${t.id}`} className="text-primary hover:underline">{t.letterNumber ?? t.number}</Link></td>
                  <td className="p-2"><Link to={`/helpdesk/tickets/${t.id}`} className="hover:underline">{t.subject}</Link></td>
                  <td className="p-2">{t.partnerName ?? t.contactName ?? '—'}</td>
                  <td className="p-2">{t.stageName}</td>
                  <td className="p-2"><span className={cn('rounded px-1.5 py-0.5 text-xs', PRIORITY_CLASS[t.priority])}>{tx(t.priority)}</span></td>
                  <td className="p-2">{t.assigneeName ?? '—'}</td>
                  <td className="whitespace-nowrap p-2">{relativeTime(t.updatedAt)}</td>
                </tr>
              ))}
              {tickets.length === 0 && <tr><td colSpan={7} className="p-6 text-center text-muted-foreground">{tx('No tickets.')}</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {open && <NewTicketDialog open={open} onOpenChange={setOpen} defaults={teamId ? { teamId } : undefined} />}
    </div>
  );
}

function TicketCard({ t, draggable }: { t: HdTicket; draggable: boolean }) {
  return (
    <Link to={`/helpdesk/tickets/${t.id}`} draggable={draggable}
      onDragStart={(e) => e.dataTransfer.setData('application/x-hd-ticket', t.id)}
      className="block rounded-md border bg-card p-2.5 text-sm shadow-sm hover:border-primary/50">
      <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
        <span className="font-mono">{t.letterNumber ?? t.number}</span>
        {t.channel === 'letter' && <Mail className="h-3 w-3" />}
        <span className={cn('ml-auto rounded px-1.5 py-0.5', PRIORITY_CLASS[t.priority])}>{tx(t.priority)}</span>
      </div>
      <div className="mt-1 line-clamp-2 font-medium">{t.subject}</div>
      <div className="mt-1 flex items-center gap-2 text-xs text-muted-foreground">
        <span className="truncate">{t.partnerName ?? t.contactName ?? channelLabel(t.channel)}</span>
        <span className="ml-auto shrink-0">{t.assigneeName ?? tx('Unassigned')}</span>
      </div>
      {t.replyOverdue && <Badge variant="destructive" className="mt-1 text-[10px]">{tx('Reply overdue')}</Badge>}
    </Link>
  );
}

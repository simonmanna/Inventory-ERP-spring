import { Link } from 'react-router-dom';
import { AlarmClock, Clock, Inbox, Mail, User, UserX } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { relativeTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { PRIORITY_CLASS, useHelpdeskDashboard } from '@/features/helpdesk/api';
import { channelLabel } from '@/features/helpdesk/labels';

/** Helpdesk overview: open work, unassigned, urgent, letters awaiting reply, response time. */
export function HelpdeskDashboardPage() {
  const { data, isLoading } = useHelpdeskDashboard();
  if (isLoading || !data) return <Skeleton className="h-96" />;
  const tiles = [
    { label: tx('Open tickets'), value: data.open, icon: Inbox, to: '/helpdesk/tickets?view=list' },
    { label: tx('Assigned to me'), value: data.mine, icon: User, to: '/helpdesk/tickets?mine=true' },
    { label: tx('Unassigned'), value: data.unassigned, icon: UserX, to: '/helpdesk/tickets?view=list' },
    { label: tx('High / urgent'), value: data.urgent, icon: AlarmClock, to: '/helpdesk/tickets?view=list' },
    { label: tx('Letters awaiting reply'), value: data.lettersAwaitingReply, icon: Mail, to: '/helpdesk/letters', warn: data.lettersOverdue > 0 },
    { label: tx('Avg first response (h, 90 d)'), value: data.avgFirstResponseHours ?? '—', icon: Clock },
  ];
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">{tx('Helpdesk')}</h1>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
        {tiles.map((t) => {
          const inner = (
            <Card className={cn('h-full', t.warn && 'border-destructive')}>
              <CardContent className="space-y-1 pt-4">
                <t.icon className="h-4 w-4 text-muted-foreground" />
                <div className="text-2xl font-semibold tabular-nums">{t.value}</div>
                <div className="text-xs text-muted-foreground">{t.label}</div>
                {t.warn && <div className="text-xs text-destructive">{tx('{{n}} overdue', { n: data.lettersOverdue })}</div>}
              </CardContent>
            </Card>
          );
          return t.to ? <Link key={t.label} to={t.to}>{inner}</Link> : <div key={t.label}>{inner}</div>;
        })}
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Open by team')}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            {data.byTeam.map((t) => (
              <Link key={t.id} to={`/helpdesk/tickets?team=${t.id}`} className="flex justify-between rounded px-1 py-0.5 hover:bg-accent">
                <span>{t.name}</span><span className="tabular-nums">{t.open}</span>
              </Link>
            ))}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Open by channel')}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            {data.byChannel.length === 0 && <p className="text-muted-foreground">{tx('Nothing open.')}</p>}
            {data.byChannel.map((c) => (
              <div key={c.channel} className="flex justify-between"><span>{channelLabel(c.channel)}</span><span className="tabular-nums">{c.count}</span></div>
            ))}
            <div className="pt-2 text-xs text-muted-foreground">{tx('{{n}} closed in the last 30 days', { n: data.closedLast30Days })}</div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Recently updated')}</CardTitle></CardHeader>
          <CardContent className="space-y-1.5 text-sm">
            {data.recent.map((t) => (
              <Link key={t.id} to={`/helpdesk/tickets/${t.id}`} className="flex items-center gap-2 rounded px-1 py-0.5 hover:bg-accent">
                <span className={cn('rounded px-1 text-[10px]', PRIORITY_CLASS[t.priority])}>{tx(t.priority)}</span>
                <span className="flex-1 truncate">{t.subject}</span>
                <span className="text-xs text-muted-foreground">{relativeTime(t.updatedAt)}</span>
              </Link>
            ))}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

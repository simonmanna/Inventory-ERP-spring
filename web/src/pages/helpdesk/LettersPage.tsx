import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Inbox, Plus, Search, Send } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useAuthStore } from '@/stores/auth.store';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useLetters } from '@/features/helpdesk/api';
import { IncomingLetterDialog, OutgoingLetterDialog } from './TicketDialogs';
import { dispatchLabel } from '@/features/helpdesk/labels';

/** Correspondence register (HD-2): incoming and outgoing letters with numbers, references, dispatch and replies. */
export function LettersPage() {
  const [direction, setDirection] = useState<'inbound' | 'outbound'>('inbound');
  const year = new Date().getFullYear();
  const [y, setY] = useState(String(year));
  const [q, setQ] = useState('');
  const [awaiting, setAwaiting] = useState(false);
  const [page, setPage] = useState(1);
  const [incoming, setIncoming] = useState(false);
  const [outgoing, setOutgoing] = useState(false);
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.helpdesk.ticketWrite));
  const { data, isLoading } = useLetters({
    direction, year: y === 'all' ? undefined : y, q: q || undefined, awaitingReply: awaiting || undefined, page, pageSize: 50,
  });
  const rows = data?.data ?? [];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <div className="mr-auto">
          <h1 className="text-2xl font-semibold">{tx('Correspondence register')}</h1>
          <p className="text-sm text-muted-foreground">{tx('Letters received and sent, numbered per year, with their scans and replies.')}</p>
        </div>
        {canWrite && (
          <>
            <Button variant="outline" onClick={() => setIncoming(true)}><Inbox className="mr-1 h-4 w-4" />{tx('Register incoming')}</Button>
            <Button onClick={() => setOutgoing(true)}><Plus className="mr-1 h-4 w-4" />{tx('New outgoing letter')}</Button>
          </>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Tabs value={direction} onValueChange={(v) => { setDirection(v as 'inbound' | 'outbound'); setPage(1); }}>
          <TabsList>
            <TabsTrigger value="inbound"><Inbox className="mr-1 h-4 w-4" />{tx('Incoming')}</TabsTrigger>
            <TabsTrigger value="outbound"><Send className="mr-1 h-4 w-4" />{tx('Outgoing')}</TabsTrigger>
          </TabsList>
        </Tabs>
        <Select value={y} onValueChange={(v) => { setY(v); setPage(1); }}>
          <SelectTrigger className="w-28"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="all">{tx('All years')}</SelectItem>
            {[0, 1, 2, 3, 4].map((d) => <SelectItem key={d} value={String(year - d)}>{year - d}</SelectItem>)}
          </SelectContent>
        </Select>
        <div className="relative w-56">
          <Search className="absolute left-2 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input className="pl-8" value={q} onChange={(e) => { setQ(e.target.value); setPage(1); }} placeholder={tx('Number, reference, sender…')} />
        </div>
        {direction === 'inbound' && (
          <label className="flex items-center gap-1.5 text-sm">
            <input type="checkbox" checked={awaiting} onChange={(e) => setAwaiting(e.target.checked)} />{tx('Awaiting reply')}
          </label>
        )}
      </div>
      {isLoading ? <Skeleton className="h-64" /> : (
        <div className="overflow-x-auto rounded-lg border">
          <table className="w-full text-sm">
            <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
              <tr>
                <th className="p-2">{tx('Letter no.')}</th>
                <th className="p-2">{tx('Date')}</th>
                <th className="p-2">{direction === 'inbound' ? tx('From') : tx('To')}</th>
                <th className="p-2">{tx('Subject')}</th>
                <th className="p-2">{tx('Their reference')}</th>
                <th className="p-2">{direction === 'inbound' ? tx('Reply') : tx('Dispatch')}</th>
                <th className="p-2">{tx('Status')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((t) => (
                <tr key={t.id} className="border-t hover:bg-accent/40">
                  <td className="whitespace-nowrap p-2 font-mono text-xs">
                    <Link to={`/helpdesk/tickets/${t.id}`} className="text-primary hover:underline">{t.letterNumber}</Link>
                  </td>
                  <td className="whitespace-nowrap p-2">{t.letterDate ? date(t.letterDate) : '—'}</td>
                  <td className="p-2">{t.partnerName ?? t.contactName ?? '—'}</td>
                  <td className="p-2">
                    <Link to={`/helpdesk/tickets/${t.id}`} className="hover:underline">{t.subject}</Link>
                    {t.confidentiality !== 'normal' && <Badge variant="destructive" className="ml-1 text-[10px]">{tx(t.confidentiality)}</Badge>}
                  </td>
                  <td className="p-2">{t.externalRef ?? '—'}</td>
                  <td className="whitespace-nowrap p-2">
                    {direction === 'inbound'
                      ? (t.repliedByTicketId ? <Badge variant="outline">{tx('Replied')}</Badge>
                        : t.replyDueOn ? <span className={t.replyOverdue ? 'text-destructive' : ''}>{tx('Due {{d}}', { d: date(t.replyDueOn) })}</span> : '—')
                      : (t.sentAt ? `${dispatchLabel(t.dispatchMethod ?? '')} · ${date(t.sentAt)}` : <Badge variant="secondary">{tx('Not dispatched')}</Badge>)}
                  </td>
                  <td className="p-2">{t.stageName}</td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={7} className="p-6 text-center text-muted-foreground">{tx('No letters.')}</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {data && data.meta.totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 text-sm">
          <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage(page - 1)}>{tx('Previous')}</Button>
          <span>{tx('Page {{p}} of {{n}}', { p: data.meta.page, n: data.meta.totalPages })}</span>
          <Button variant="outline" size="sm" disabled={page >= data.meta.totalPages} onClick={() => setPage(page + 1)}>{tx('Next')}</Button>
        </div>
      )}
      {incoming && <IncomingLetterDialog open={incoming} onOpenChange={setIncoming} />}
      {outgoing && <OutgoingLetterDialog open={outgoing} onOpenChange={setOutgoing} />}
    </div>
  );
}

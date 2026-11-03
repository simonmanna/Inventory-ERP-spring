import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, CheckCircle2, FileSignature, Mail, MessageSquare, RotateCcw, Send, StickyNote, Truck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, relativeTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useUsers } from '@/features/staff/api';
import {
  PRIORITY_CLASS, useAddMessage, useAssignTicket, useCloseTicket, useMoveStage, useReopenTicket, useTicket,
} from '@/features/helpdesk/api';
import { DispatchDialog, ReplyLetterDialog } from './TicketDialogs';
import { channelLabel, dispatchLabel } from '@/features/helpdesk/labels';

const KIND_STYLE: Record<string, string> = {
  note: 'border-amber-300 bg-amber-50 dark:border-amber-800 dark:bg-amber-950/30',
  reply: 'border-sky-300 bg-sky-50 dark:border-sky-800 dark:bg-sky-950/30',
  inbound: 'border-emerald-300 bg-emerald-50 dark:border-emerald-800 dark:bg-emerald-950/30',
  system: 'border-transparent bg-transparent text-muted-foreground',
};

/** One ticket / letter: stage bar, thread with notes and replies, attachments, letter register details. */
export function TicketDetailPage() {
  const { id } = useParams();
  const { data: t, isLoading, isError } = useTicket(id);
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.helpdesk.ticketWrite));
  const canAssign = useAuthStore((s) => s.hasPermission(PERMISSIONS.helpdesk.ticketAssign));
  const canClose = useAuthStore((s) => s.hasPermission(PERMISSIONS.helpdesk.ticketClose));
  const canDispatch = useAuthStore((s) => s.hasPermission(PERMISSIONS.helpdesk.letterDispatch));
  const canUsers = useAuthStore((s) => s.hasPermission(PERMISSIONS.user.read));
  const { data: users } = useUsers({ page: 1, pageSize: 200 });
  const move = useMoveStage();
  const close = useCloseTicket();
  const reopen = useReopenTicket();
  const assign = useAssignTicket();
  const addMessage = useAddMessage();
  const [kind, setKind] = useState<'note' | 'reply' | 'inbound'>('note');
  const [body, setBody] = useState('');
  const [sendEmail, setSendEmail] = useState(true);
  const [replyOpen, setReplyOpen] = useState(false);
  const [dispatchOpen, setDispatchOpen] = useState(false);

  useEffect(() => setBody(''), [id]);

  if (isLoading) return <Skeleton className="h-96" />;
  if (isError || !t) return <p className="text-sm text-muted-foreground">{tx('Ticket not found.')}</p>;

  const run = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn();
      notify.success(ok);
    } catch (err) {
      notify.error(apiMessage(err, tx('Action failed')));
    }
  };
  const post = () => run(async () => {
    await addMessage.mutateAsync({ id: t.id, kind, body, sendEmail: kind === 'reply' && sendEmail && !!t.contactEmail });
    setBody('');
  }, kind === 'note' ? tx('Note added') : tx('Message added'));

  const letter = t.channel === 'letter';
  const outbound = t.direction === 'outbound';

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" asChild><Link to={letter ? '/helpdesk/letters' : '/helpdesk/tickets'}><ArrowLeft className="h-4 w-4" /></Link></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <span className="font-mono">{t.number}</span>
            {t.letterNumber && <Badge variant="outline" className="font-mono">{t.letterNumber}</Badge>}
            <span className={cn('rounded px-1.5 py-0.5 text-xs', PRIORITY_CLASS[t.priority])}>{tx(t.priority)}</span>
            <span>{channelLabel(t.channel)} · {tx(t.direction)}</span>
            {t.confidentiality !== 'normal' && <Badge variant="destructive">{tx(t.confidentiality)}</Badge>}
            {t.replyOverdue && <Badge variant="destructive">{tx('Reply overdue')}</Badge>}
          </div>
          <h1 className="text-xl font-semibold">{t.subject}</h1>
        </div>
        <div className="flex flex-wrap gap-2">
          {letter && !outbound && canWrite && !t.repliedByTicketId && (
            <Button onClick={() => setReplyOpen(true)}><FileSignature className="mr-1 h-4 w-4" />{tx('Reply with letter')}</Button>
          )}
          {letter && outbound && canDispatch && (
            <Button variant="outline" onClick={() => setDispatchOpen(true)}><Truck className="mr-1 h-4 w-4" />{t.sentAt ? tx('Update dispatch') : tx('Dispatch')}</Button>
          )}
          {canClose && (t.stageClosed
            ? <Button variant="outline" onClick={() => run(() => reopen.mutateAsync(t.id), tx('Reopened'))}><RotateCcw className="mr-1 h-4 w-4" />{tx('Reopen')}</Button>
            : <Button variant="outline" onClick={() => {
              const resolution = window.prompt(tx('Resolution (optional)')) ?? undefined;
              void run(() => close.mutateAsync({ id: t.id, resolution }), tx('Closed'));
            }}><CheckCircle2 className="mr-1 h-4 w-4" />{tx('Close')}</Button>)}
        </div>
      </div>

      {/* stage bar */}
      <div className="flex flex-wrap gap-1">
        {(t.stages ?? []).map((s) => (
          <button key={s.id} type="button" disabled={!canWrite || move.isPending || (s.isClosed && !canClose)}
            onClick={() => s.id !== t.stageId && run(() => move.mutateAsync({ id: t.id, stageId: s.id }), tx('Stage updated'))}
            className={cn('rounded-full border px-3 py-1 text-xs transition-colors disabled:cursor-not-allowed',
              s.id === t.stageId ? 'border-primary bg-primary text-primary-foreground' : 'hover:bg-accent')}>
            {s.name}
          </button>
        ))}
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_340px]">
        <div className="space-y-4">
          {t.description && (
            <Card><CardContent className="whitespace-pre-wrap pt-4 text-sm">{t.description}</CardContent></Card>
          )}
          <Card>
            <CardHeader className="pb-2"><CardTitle className="flex items-center gap-2 text-base"><MessageSquare className="h-4 w-4" />{tx('Conversation')}</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              <ol className="space-y-2">
                {(t.messages ?? []).map((m) => (
                  <li key={m.id} className={cn('rounded-md border p-2 text-sm', KIND_STYLE[m.kind])}>
                    <div className="mb-1 flex items-center gap-2 text-xs text-muted-foreground">
                      {m.kind === 'note' ? <StickyNote className="h-3 w-3" /> : m.kind === 'reply' ? <Send className="h-3 w-3" />
                        : m.kind === 'inbound' ? <Mail className="h-3 w-3" /> : null}
                      <span className="font-medium">{m.author ?? tx('System')}</span>
                      <span>{relativeTime(m.createdAt)}</span>
                      {m.emailSent && <Badge variant="outline" className="text-[10px]">{tx('e-mailed to {{e}}', { e: m.toEmail ?? '' })}</Badge>}
                    </div>
                    <div className="whitespace-pre-wrap">{m.body}</div>
                  </li>
                ))}
              </ol>
              {canWrite && (
                <div className="space-y-2 border-t pt-3">
                  <div className="flex gap-1">
                    {(['note', 'reply', 'inbound'] as const).map((k) => (
                      <Button key={k} size="sm" variant={kind === k ? 'secondary' : 'ghost'} onClick={() => setKind(k)}>
                        {k === 'note' ? tx('Internal note') : k === 'reply' ? tx('Reply to customer') : tx('Log customer message')}
                      </Button>
                    ))}
                  </div>
                  <Textarea rows={4} value={body} onChange={(e) => setBody(e.target.value)}
                    placeholder={kind === 'note' ? tx('Only visible to the team') : tx('Message')} />
                  <div className="flex items-center gap-3">
                    {kind === 'reply' && t.contactEmail && (
                      <label className="flex items-center gap-1.5 text-xs">
                        <input type="checkbox" checked={sendEmail} onChange={(e) => setSendEmail(e.target.checked)} />
                        {tx('E-mail to {{e}}', { e: t.contactEmail })}
                      </label>
                    )}
                    <Button size="sm" className="ml-auto" onClick={post} disabled={!body.trim() || addMessage.isPending}>{tx('Post')}</Button>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        </div>

        <div className="space-y-4">
          <Card>
            <CardContent className="space-y-2 pt-4 text-sm">
              <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1.5">
                <dt className="text-muted-foreground">{tx('Team')}</dt><dd>{t.teamName}</dd>
                <dt className="text-muted-foreground">{tx('Stage')}</dt><dd>{t.stageName}</dd>
                <dt className="text-muted-foreground">{tx('Customer')}</dt><dd>{t.partnerName ?? '—'}</dd>
                <dt className="text-muted-foreground">{tx('Contact')}</dt>
                <dd>{[t.contactName, t.contactEmail, t.contactPhone].filter(Boolean).join(' · ') || '—'}</dd>
                <dt className="text-muted-foreground">{tx('Created')}</dt><dd>{dateTime(t.createdAt)}</dd>
                {t.firstResponseAt && (<><dt className="text-muted-foreground">{tx('First response')}</dt><dd>{dateTime(t.firstResponseAt)}</dd></>)}
                {t.closedAt && (<><dt className="text-muted-foreground">{tx('Closed')}</dt><dd>{dateTime(t.closedAt)}</dd></>)}
                {t.resolution && (<><dt className="text-muted-foreground">{tx('Resolution')}</dt><dd>{t.resolution}</dd></>)}
                {t.related && (<><dt className="text-muted-foreground">{tx(t.related.typeLabel)}</dt>
                  <dd>{t.related.webPath ? <Link className="text-primary hover:underline" to={t.related.webPath}>{t.related.label}</Link> : t.related.label}</dd></>)}
              </dl>
              <div className="pt-1">
                <div className="mb-1 text-xs text-muted-foreground">{tx('Assignee')}</div>
                {canAssign && canUsers ? (
                  <SearchableSelect value={t.assigneeId ?? ''} placeholder={tx('Unassigned')}
                    onValueChange={(v) => run(() => assign.mutateAsync({ id: t.id, assigneeId: v || null }), tx('Assigned'))}
                    options={(users?.data ?? []).map((u) => ({ value: u.id, label: `${u.firstName} ${u.lastName ?? ''}`.trim(), hint: u.email }))} />
                ) : <span>{t.assigneeName ?? tx('Unassigned')}</span>}
              </div>
            </CardContent>
          </Card>

          {letter && (
            <Card>
              <CardHeader className="pb-2"><CardTitle className="flex items-center gap-2 text-base"><Mail className="h-4 w-4" />{tx('Correspondence')}</CardTitle></CardHeader>
              <CardContent className="text-sm">
                <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1.5">
                  <dt className="text-muted-foreground">{tx('Letter no.')}</dt><dd className="font-mono">{t.letterNumber}</dd>
                  <dt className="text-muted-foreground">{tx('Our reference')}</dt><dd>{t.ourRef ?? '—'}</dd>
                  <dt className="text-muted-foreground">{tx('Their reference')}</dt><dd>{t.externalRef ?? '—'}</dd>
                  <dt className="text-muted-foreground">{tx('Letter date')}</dt><dd>{t.letterDate ? date(t.letterDate) : '—'}</dd>
                  {!outbound && (<><dt className="text-muted-foreground">{tx('Received')}</dt><dd>{t.receivedAt ? dateTime(t.receivedAt) : '—'}</dd></>)}
                  {!outbound && (<><dt className="text-muted-foreground">{tx('Reply due')}</dt><dd>{t.replyDueOn ? date(t.replyDueOn) : '—'}</dd></>)}
                  {outbound && (<><dt className="text-muted-foreground">{tx('Dispatched')}</dt>
                    <dd>{t.sentAt ? `${dateTime(t.sentAt)} · ${dispatchLabel(t.dispatchMethod ?? '')}` : tx('Not yet')}</dd></>)}
                  {t.trackingNumber && (<><dt className="text-muted-foreground">{tx('Tracking')}</dt><dd className="font-mono">{t.trackingNumber}</dd></>)}
                </dl>
                {t.parent && (
                  <p className="mt-2 text-xs">{tx('In reply to')}{' '}
                    <Link className="text-primary hover:underline" to={`/helpdesk/tickets/${t.parent.id}`}>{t.parent.letterNumber ?? t.parent.number}</Link></p>
                )}
                {(t.children ?? []).length > 0 && (
                  <div className="mt-2 text-xs">{tx('Replies')}:{' '}
                    {(t.children ?? []).map((c) => (
                      <Link key={c.id} className="mr-2 text-primary hover:underline" to={`/helpdesk/tickets/${c.id}`}>{c.letterNumber ?? c.number}</Link>
                    ))}
                  </div>
                )}
              </CardContent>
            </Card>
          )}

          <AttachmentPanel resType="helpdesk_ticket" resId={t.id} writePermission={PERMISSIONS.helpdesk.ticketWrite} />
        </div>
      </div>
      {replyOpen && <ReplyLetterDialog ticket={t} open={replyOpen} onOpenChange={setReplyOpen} />}
      {dispatchOpen && <DispatchDialog ticket={t} open={dispatchOpen} onOpenChange={setDispatchOpen} />}
    </div>
  );
}

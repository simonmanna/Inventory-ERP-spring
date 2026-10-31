import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { LifeBuoy } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { CASE_STATUS_TONE, PRIORITY_TONE, caseApi, useCase, useCaseMutation } from '@/features/crm/cases-api';
import { useCrmLookups } from '@/features/crm/suite-api';

const NEXT: Record<string, { to: string; label: () => string; note?: 'required' }[]> = {
  open: [
    { to: 'in_progress', label: () => tx('Start') },
    { to: 'resolved', label: () => tx('Resolve'), note: 'required' },
    { to: 'cancelled', label: () => tx('Cancel case'), note: 'required' },
  ],
  in_progress: [
    { to: 'resolved', label: () => tx('Resolve'), note: 'required' },
    { to: 'cancelled', label: () => tx('Cancel case'), note: 'required' },
  ],
  resolved: [
    { to: 'closed', label: () => tx('Close') },
    { to: 'in_progress', label: () => tx('Reopen') },
  ],
};

/** One case: what happened, who owns it, its delivery and its timeline. */
export function CaseDetailPage() {
  const { id } = useParams();
  const { data: c, isLoading, isError } = useCase(id);
  const { data: lookups } = useCrmLookups();
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.caseWrite));
  const [text, setText] = useState('');
  const note = useCaseMutation((body: string) => caseApi.note(id as string, body));
  const status = useCaseMutation((a: { to: string; note?: string }) => caseApi.status(id as string, a.to, a.note));
  const assign = useCaseMutation((assigneeId: string | null) => caseApi.assign(id as string, assigneeId));

  if (isLoading) return <Skeleton className="h-64 w-full" />;
  if (isError || !c) return <p className="py-12 text-center text-sm text-destructive">{tx('Could not load the case.')}</p>;

  const live = c.status === 'open' || c.status === 'in_progress';
  const fail = (err: unknown) => notify.error(apiErrorMessage(err, tx('The action failed')));

  return (
    <div className="space-y-4">
      <ListPageHeader icon={LifeBuoy} title={`${c.code} · ${c.subject}`} description={c.partnerName ?? ''} />
      <div className="grid gap-4 md:grid-cols-3">
        <div className="space-y-4 md:col-span-2">
          <div className="rounded-xl border bg-card p-4 text-sm shadow-sm">
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <Badge className={CASE_STATUS_TONE[c.status]}>{c.status.replace(/_/g, ' ')}</Badge>
              <Badge className={PRIORITY_TONE[c.priority]}>{c.priority}</Badge>
              <span className="text-muted-foreground">{c.caseType.replace(/_/g, ' ')} · {c.category}</span>
              {c.overdue ? <span className="font-medium text-rose-600">{tx('Overdue')}</span> : null}
            </div>
            <p className="whitespace-pre-wrap">{c.description ?? '—'}</p>
            {c.resolution ? (
              <p className="mt-3 rounded-md bg-emerald-50 p-2 text-emerald-900">{tx('Resolution')}: {c.resolution}</p>
            ) : null}
          </div>
          {canWrite ? (
            <div className="space-y-2 rounded-xl border bg-card p-4 shadow-sm">
              <Textarea rows={3} placeholder={tx('Note, resolution or reason…')} value={text} onChange={(e) => setText(e.target.value)} />
              <div className="flex flex-wrap gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={note.isPending || !text.trim()}
                  onClick={async () => {
                    try {
                      await note.mutateAsync(text.trim());
                      setText('');
                    } catch (err) {
                      fail(err);
                    }
                  }}
                >
                  {tx('Add note')}
                </Button>
                {(NEXT[c.status] ?? []).map((n) => (
                  <Button
                    key={n.to}
                    size="sm"
                    disabled={status.isPending || (n.note === 'required' && !text.trim())}
                    onClick={async () => {
                      try {
                        await status.mutateAsync({ to: n.to, note: text.trim() || undefined });
                        notify.success(tx('Case updated'));
                        setText('');
                      } catch (err) {
                        fail(err);
                      }
                    }}
                  >
                    {n.label()}
                  </Button>
                ))}
              </div>
            </div>
          ) : null}
          <div className="rounded-xl border bg-card p-4 shadow-sm">
            <h2 className="mb-3 text-sm font-semibold">{tx('Timeline')}</h2>
            {c.events?.length ? (
              <ul className="space-y-2 text-sm">
                {c.events.map((e) => (
                  <li key={e.id} className="border-l-2 pl-3">
                    <div className="text-xs text-muted-foreground">
                      {dateTime(e.createdAt)} {e.actorName ? `· ${e.actorName}` : ''}
                    </div>
                    {e.kind === 'status' ? (
                      <div>{e.fromStatus ? `${e.fromStatus} → ` : ''}{e.toStatus}</div>
                    ) : null}
                    {e.kind === 'assignment' ? <div>{tx('Owner changed')}</div> : null}
                    {e.body ? <div className="whitespace-pre-wrap">{e.body}</div> : null}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-sm text-muted-foreground">{tx('Nothing yet.')}</p>
            )}
          </div>
        </div>
        <div className="space-y-3 rounded-xl border bg-card p-4 text-sm shadow-sm">
          <div>
            <div className="text-xs text-muted-foreground">{tx('Customer')}</div>
            <Link className="text-primary hover:underline" to={`/crm/customers/${c.partnerId}`}>{c.partnerName}</Link>
            {c.contactName ? <div>{c.contactName} {c.contactPhone ?? ''}</div> : null}
          </div>
          <div>
            <div className="text-xs text-muted-foreground">{tx('Owner')}</div>
            {canWrite && live ? (
              <select
                className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                value={c.assigneeId ?? ''}
                onChange={async (e) => {
                  try {
                    await assign.mutateAsync(e.target.value || null);
                  } catch (err) {
                    fail(err);
                  }
                }}
              >
                <option value="">{tx('Unassigned')}</option>
                {(lookups?.users ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
              </select>
            ) : (
              <div>{c.assigneeName ?? '—'}</div>
            )}
          </div>
          <div>
            <div className="text-xs text-muted-foreground">{tx('Due')}</div>
            <div>{c.dueAt ? dateTime(c.dueAt) : '—'}</div>
          </div>
          {c.deliveryOrderId ? (
            <div>
              <div className="text-xs text-muted-foreground">{tx('Delivery order')}</div>
              <Link className="text-primary hover:underline" to={`/fleet/delivery-orders/${c.deliveryOrderId}`}>{c.deliveryOrderCode}</Link>
            </div>
          ) : null}
          {c.discrepancyId ? (
            <div>
              <div className="text-xs text-muted-foreground">{tx('Discrepancy')}</div>
              <Link className="text-primary hover:underline" to={`/fleet/delivery-discrepancies?deliveryOrderId=${c.deliveryOrderId}`}>{c.discrepancyCode}</Link>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}

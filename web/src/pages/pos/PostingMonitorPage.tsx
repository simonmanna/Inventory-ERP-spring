import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, Loader2, CheckCircle2, Trash2, Inbox, RefreshCw, ListChecks, UserPlus, ExternalLink, Ban } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/textarea';
import { apiErrorMessage } from '@/lib/api-error';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { api } from '@/lib/api';
import { useAuthStore } from '@/stores/auth.store';
import { PERMISSIONS } from '@erp/shared';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

interface InventoryException {
  id: string;
  invoiceNumber: string | null;
  invoiceId: string | null;
  locationId: string | null;
  productId: string | null;
  menuItemId: string | null;
  description: string | null;
  quantity: string | number;
  kind: string;
  reason: string;
  status: string;
  assignedToId: string | null;
  resolutionNote: string | null;
  resolvedAt: string | null;
  createdAt: string;
  /** jsonb — an object, or its JSON text. kitchen_waste carries orderId / orderItemId / sourceId. */
  payload: Record<string, unknown> | string | null;
}

type JobFilter = 'all' | 'pending' | 'processing' | 'failed' | 'done' | 'abandoned';
type ExceptionFilter = 'open' | 'resolved' | 'discarded' | 'all';

/** Exception kinds the posting paths record (PosInvoiceService, KitchenWaste); unknown kinds still list. */
const KIND_LABELS: Record<string, () => string> = {
  product: () => tx('Product'),
  menu_recipe: () => tx('Recipe'),
  line_extras: () => tx('Add-ons'),
  whole_invoice: () => tx('Invoice'),
  kitchen_waste: () => tx('Waste'),
};
const kindLabel = (kind: string) => KIND_LABELS[kind]?.() ?? kind;

function payloadOf(ex: InventoryException): Record<string, unknown> | null {
  if (!ex.payload) return null;
  if (typeof ex.payload !== 'string') return ex.payload;
  try {
    return JSON.parse(ex.payload) as Record<string, unknown>;
  } catch {
    return { raw: ex.payload };
  }
}

/** Org users for the assignee picker / label; tolerates the paginated and the flat /users shape. */
function useAssignableUsers(enabled: boolean) {
  return useQuery({
    queryKey: ['posting-monitor', 'users'] as const,
    enabled,
    staleTime: 60_000,
    queryFn: async () => {
      const res = await api.get('/users', { params: { page: 1, pageSize: 200 } });
      const rows: Array<Record<string, unknown>> = Array.isArray(res.data) ? res.data : (res.data?.data ?? []);
      return rows.map((u) => ({
        id: String(u.id),
        name: [u.firstName, u.lastName].filter(Boolean).join(' ') || String(u.email ?? u.id),
      }));
    },
  });
}

function useLocationNames() {
  return useQuery({
    queryKey: ['posting-monitor', 'locations'] as const,
    staleTime: 60_000,
    retry: false,
    queryFn: async () => {
      const res = await api.get<{ data: { id: string; code: string; name: string }[] }>('/inventory/locations');
      return new Map((res.data?.data ?? []).map((l) => [l.id, l.name || l.code]));
    },
  });
}

interface StockPostingJob {
  id: string;
  invoiceNumber: string;
  invoiceId: string;
  orderId: string | null;
  status: string;
  attempts: number;
  maxAttempts: number;
  lastError: string | null;
  nextRetryAt: string;
  createdAt: string;
  processedAt: string | null;
}

interface Counts {
  jobs: { pending: number; processing: number; failed: number; doneWithReview: number };
  exceptions: { open: number };
}

/**
 * Posting Monitor (accounting hardening, Phase 1).
 *
 * Surfaces the durable inventory-posting queue and the exception work-queue that
 * replaced the old silent `stock_reconcile_needed` audit marker. A sale is never
 * blocked by stock, but every deduction that could not post is now visible and
 * actionable: retry a failed job (safe — it deducted nothing) or bring a pending one forward, or resolve an
 * exception once the drift has been corrected with a manual stock adjustment. A failed job that can never post is
 * abandoned with a note of how the stock was corrected by hand (review 2026-11-17, ACR-17).
 */
export function PostingMonitorPage() {
  const qc = useQueryClient();
  const [tab, setTab] = useState<'exceptions' | 'jobs'>('exceptions');
  const [exStatus, setExStatus] = useState<ExceptionFilter>('open');
  // `?kind=` preselects a finding kind (the inventory integrity page links each failing check here, IPR T33).
  const [searchParams] = useSearchParams();
  const [exKind, setExKind] = useState<string>(() => searchParams.get('kind') || 'all');
  const [jobStatus, setJobStatus] = useState<JobFilter>('failed');
  const [resolving, setResolving] = useState<{ ex: InventoryException; action: 'resolved' | 'discarded' } | null>(null);
  const [note, setNote] = useState('');
  const [assigning, setAssigning] = useState<InventoryException | null>(null);
  const [assignee, setAssignee] = useState('');
  const [abandoning, setAbandoning] = useState<StockPostingJob | null>(null);
  const [abandonNote, setAbandonNote] = useState('');
  // Retry / assign / resolve are pos:override on the server; viewing is pos:reports (route guard).
  const canOverride = useAuthStore((s) => s.permissions.includes(PERMISSIONS.pos.override));
  const canOpenReceipt = useAuthStore((s) => s.permissions.includes(PERMISSIONS.pos.read));
  const usersQ = useAssignableUsers(canOverride);
  const locationsQ = useLocationNames();
  const userName = (id: string | null) => (id ? usersQ.data?.find((u) => u.id === id)?.name ?? id : null);

  const countsQ = useQuery({
    queryKey: ['posting-monitor', 'counts'] as const,
    queryFn: async (): Promise<Counts> => (await api.get('/pos/posting-monitor/counts')).data,
    refetchInterval: 30_000,
  });

  const exQ = useQuery({
    queryKey: ['posting-monitor', 'exceptions', exStatus] as const,
    queryFn: async (): Promise<InventoryException[]> =>
      (await api.get(`/pos/posting-monitor/exceptions?status=${exStatus}`)).data,
    refetchInterval: 30_000,
    enabled: tab === 'exceptions',
  });

  const jobsQ = useQuery({
    queryKey: ['posting-monitor', 'jobs', jobStatus] as const,
    queryFn: async (): Promise<StockPostingJob[]> =>
      (await api.get(`/pos/posting-monitor/jobs?status=${jobStatus}`)).data,
    refetchInterval: 30_000,
    enabled: tab === 'jobs',
  });

  const resolve = useMutation({
    mutationFn: async () => {
      if (!resolving) return null;
      return (await api.post(`/pos/posting-monitor/exceptions/${resolving.ex.id}/resolve`, {
        status: resolving.action,
        note: note.trim(),
      })).data;
    },
    onSuccess: () => {
      notify.success(tx('Recorded'));
      setResolving(null);
      setNote('');
      qc.invalidateQueries({ queryKey: ['posting-monitor'] });
    },
    onError: (e: any) => notify.error(e?.response?.data?.message ?? tx('Failed')),
  });

  const assign = useMutation({
    mutationFn: async () => {
      if (!assigning || !assignee) return null;
      return (await api.post(`/pos/posting-monitor/exceptions/${assigning.id}/assign`, { assignedToId: assignee })).data;
    },
    onSuccess: () => {
      notify.success(tx('Saved'));
      setAssigning(null);
      setAssignee('');
      qc.invalidateQueries({ queryKey: ['posting-monitor'] });
    },
    onError: (e: any) => notify.error(e?.response?.data?.message ?? tx('Assign failed')),
  });

  const retry = useMutation({
    mutationFn: async (id: string) => (await api.post(`/pos/posting-monitor/jobs/${id}/retry`, {})).data,
    onSuccess: () => {
      notify.success(tx('Re-queued'));
      qc.invalidateQueries({ queryKey: ['posting-monitor'] });
    },
    onError: (e: any) => notify.error(e?.response?.data?.message ?? tx('Failed')),
  });

  const abandon = useMutation({
    mutationFn: async () => {
      if (!abandoning) return null;
      return (await api.post(`/pos/posting-monitor/jobs/${abandoning.id}/abandon`, { note: abandonNote.trim() })).data;
    },
    onSuccess: () => {
      notify.success(tx('Job abandoned'));
      setAbandoning(null);
      setAbandonNote('');
      qc.invalidateQueries({ queryKey: ['posting-monitor'] });
    },
    onError: (e: unknown) => notify.error(apiErrorMessage(e, tx('Failed'))),
  });

  const counts = countsQ.data;
  const openEx = counts?.exceptions.open ?? 0;
  const failedJobs = counts?.jobs.failed ?? 0;

  const exRows = useMemo(() => exQ.data ?? [], [exQ.data]);
  const kindOptions = useMemo(
    () => [...new Set([...Object.keys(KIND_LABELS), ...exRows.map((e) => e.kind), ...(exKind === 'all' ? [] : [exKind])])],
    [exRows, exKind],
  );
  const shownEx = exKind === 'all' ? exRows : exRows.filter((e) => e.kind === exKind);

  const jobFilters: Array<{ value: JobFilter; label: string }> = [
    { value: 'all', label: tx('All') },
    { value: 'pending', label: tx('Pending') },
    { value: 'processing', label: tx('In progress') },
    { value: 'failed', label: tx('Failed') },
    { value: 'done', label: tx('Done') },
    { value: 'abandoned', label: tx('Abandoned') },
  ];
  const exFilters: Array<{ value: ExceptionFilter; label: string }> = [
    { value: 'open', label: tx('Open') },
    { value: 'resolved', label: tx('Resolved') },
    { value: 'discarded', label: tx('Discarded') },
    { value: 'all', label: tx('All') },
  ];

  return (
    <div className="space-y-6 p-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-semibold">
            <ListChecks className="h-6 w-6" /> {tx('Posting monitor')}
          </h1>
          <p className="text-muted-foreground">
            {tx('Inventory postings that ran out-of-band after a sale. The sale is never blocked by stock — but nothing drifts silently: failures land here.')}
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant={tab === 'exceptions' ? 'default' : 'outline'} size="sm" onClick={() => setTab('exceptions')}>
            {tx('Exceptions')}{openEx > 0 ? ` (${openEx})` : ''}
          </Button>
          <Button variant={tab === 'jobs' ? 'default' : 'outline'} size="sm" onClick={() => setTab('jobs')}>
            {tx('Jobs')}{failedJobs > 0 ? tx(' ({{failedJobs}} failed)', { failedJobs }) : ''}
          </Button>
        </div>
      </div>

      {tab === 'exceptions' ? (
        <>
          <div className="flex flex-wrap items-center gap-2">
            {exFilters.map((f) => (
              <Button key={f.value} variant={exStatus === f.value ? 'default' : 'outline'} size="sm" onClick={() => setExStatus(f.value)}>
                {f.label}
              </Button>
            ))}
            <Label htmlFor="ex-kind" className="ml-2 text-sm text-muted-foreground">{tx('Kind')}</Label>
            <select
              id="ex-kind"
              className="h-8 rounded-md border border-input bg-background px-2 text-sm"
              value={exKind}
              onChange={(e) => setExKind(e.target.value)}
            >
              <option value="all">{tx('All kinds')}</option>
              {kindOptions.map((k) => (
                <option key={k} value={k}>{kindLabel(k)}</option>
              ))}
            </select>
          </div>
          {exQ.isLoading ? (
            <Skeleton className="h-32 w-full" />
          ) : shownEx.length === 0 ? (
            <EmptyState label={tx('No inventory exceptions')} hint={tx('Every sale\'s stock deducted cleanly.')} />
          ) : (
            <div className="space-y-3">
              {shownEx.map((ex) => {
                const payload = payloadOf(ex);
                const location = ex.locationId ? locationsQ.data?.get(ex.locationId) ?? ex.locationId : null;
                return (
                <Card key={ex.id} className={ex.status === 'open' ? 'border-rose-400/40' : undefined}>
                  <CardHeader className="pb-2">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <CardTitle className="flex items-center gap-2 text-base">
                        {ex.status === 'open' && <AlertTriangle className="h-4 w-4 text-rose-500" />}
                        <code className="font-mono text-sm" title={ex.kind}>{kindLabel(ex.kind)}</code>
                        <span className="font-medium">{ex.description ?? ex.productId ?? ex.menuItemId ?? 'line'}</span>
                        <span className="text-muted-foreground">× {Number(ex.quantity)}</span>
                      </CardTitle>
                      <span className="text-xs text-muted-foreground">{new Date(ex.createdAt).toLocaleString()}</span>
                    </div>
                    <CardDescription className="text-rose-400">{ex.reason}</CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-3">
                    <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs sm:grid-cols-4">
                      <Field label={tx('Product')} value={ex.description ?? ex.productId ?? ex.menuItemId ?? '—'} />
                      <Field label={tx('Location')} value={location ?? '—'} />
                      <Field label={tx('Quantity')} value={String(Number(ex.quantity))} />
                      <div>
                        <dt className="text-muted-foreground">{tx('Invoice')}</dt>
                        <dd className="font-medium">
                          {ex.invoiceId && canOpenReceipt ? (
                            <Link to={`/pos/receipts/${ex.invoiceId}`} className="inline-flex items-center gap-1 text-primary hover:underline">
                              {ex.invoiceNumber ?? tx('View receipt')} <ExternalLink className="h-3 w-3" />
                            </Link>
                          ) : (ex.invoiceNumber ?? tx('no invoice'))}
                        </dd>
                      </div>
                      {ex.assignedToId && <Field label={tx('Assigned to')} value={userName(ex.assignedToId) ?? ''} />}
                    </dl>
                    {payload && (
                      <details className="text-xs">
                        <summary className="cursor-pointer text-muted-foreground">{tx('Details')}</summary>
                        <pre className="mt-2 max-h-48 overflow-auto rounded-md border bg-muted/40 p-2">
                          {JSON.stringify(payload, null, 2)}
                        </pre>
                      </details>
                    )}
                    {ex.status === 'open' ? (
                      canOverride && (
                      <div className="flex justify-end gap-2">
                        <Button size="sm" variant="outline" onClick={() => { setAssigning(ex); setAssignee(ex.assignedToId ?? ''); }}>
                          <UserPlus className="h-3.5 w-3.5" /> {tx('Assign')}
                        </Button>
                        <Button size="sm" variant="outline" onClick={() => { setResolving({ ex, action: 'resolved' }); setNote(''); }}>
                          <CheckCircle2 className="h-3.5 w-3.5" /> {tx('Mark corrected')}
                        </Button>
                        <Button size="sm" variant="ghost" className="text-rose-400" onClick={() => { setResolving({ ex, action: 'discarded' }); setNote(''); }}>
                          <Trash2 className="h-3.5 w-3.5" /> {tx('Discard')}
                        </Button>
                      </div>
                      )
                    ) : (
                      <p className="text-xs text-muted-foreground">
                        <span className="font-medium capitalize">{ex.status}</span>
                        {ex.resolvedAt ? ` on ${new Date(ex.resolvedAt).toLocaleString()}` : ''}
                        {ex.resolutionNote ? ` — "${ex.resolutionNote}"` : ''}
                      </p>
                    )}
                  </CardContent>
                </Card>
                );
              })}
            </div>
          )}
        </>
      ) : (
        <>
          <div className="flex flex-wrap gap-2">
            {jobFilters.map((f) => (
              <Button key={f.value} variant={jobStatus === f.value ? 'default' : 'outline'} size="sm" onClick={() => setJobStatus(f.value)}>
                {f.label}
              </Button>
            ))}
          </div>
          {jobsQ.isLoading ? (
            <Skeleton className="h-32 w-full" />
          ) : (jobsQ.data ?? []).length === 0 ? (
            <EmptyState label={tx('No jobs')} hint={tx('Nothing pending or failed in the inventory-posting queue.')} />
          ) : (
            <div className="space-y-3">
              {(jobsQ.data ?? []).map((job) => (
                <Card key={job.id} className={job.status === 'failed' ? 'border-rose-400/40' : undefined}>
                  <CardHeader className="pb-2">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <CardTitle className="flex items-center gap-2 text-base">
                        <StatusDot status={job.status} />
                        {canOpenReceipt && job.invoiceId ? (
                          <Link to={`/pos/receipts/${job.invoiceId}`} className="font-mono text-sm text-primary hover:underline">{job.invoiceNumber}</Link>
                        ) : (
                          <code className="font-mono text-sm">{job.invoiceNumber}</code>
                        )}
                        <span className="text-xs text-muted-foreground">{tx('attempt')} {job.attempts}/{job.maxAttempts}</span>
                      </CardTitle>
                      <span className="text-xs text-muted-foreground">{new Date(job.createdAt).toLocaleString()}</span>
                    </div>
                    {job.lastError && <CardDescription className="text-amber-500">{job.lastError}</CardDescription>}
                  </CardHeader>
                  {/* ADR-S072: a failed job restarts; a pending job waiting for its next attempt runs on the next drain. */}
                  {(job.status === 'failed' || job.status === 'pending') && canOverride && (
                    <CardContent className="flex justify-end gap-2">
                      {/* Review 2026-11-17 (ACR-17): a failed job that can never post is abandoned, with a note. */}
                      {job.status === 'failed' && (
                        <Button size="sm" variant="ghost" className="text-rose-400" onClick={() => { setAbandoning(job); setAbandonNote(''); }}>
                          <Ban className="h-3.5 w-3.5" /> {tx('Abandon')}
                        </Button>
                      )}
                      <Button size="sm" variant="outline" disabled={retry.isPending} onClick={() => retry.mutate(job.id)}>
                        {retry.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5" />} {tx('Retry')}
                      </Button>
                    </CardContent>
                  )}
                  {job.status === 'abandoned' && (
                    <CardContent className="pt-0 text-xs text-muted-foreground">
                      {tx('Abandoned: the stock and cost of sales for this sale were corrected by hand (see the audit log for the note).')}
                    </CardContent>
                  )}
                </Card>
              ))}
            </div>
          )}
        </>
      )}

      <Dialog open={!!assigning} onOpenChange={(open) => !open && setAssigning(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Assign To')}</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-muted-foreground">
            {assigning ? `${kindLabel(assigning.kind)} · ${assigning.description ?? assigning.productId ?? ''}` : ''}
          </p>
          <SearchableSelect
            value={assignee}
            onValueChange={setAssignee}
            options={(usersQ.data ?? []).map((u) => ({ value: u.id, label: u.name }))}
            placeholder={tx('Select user')}
            loading={usersQ.isLoading}
          />
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setAssigning(null)}>{tx('Cancel')}</Button>
            <Button disabled={!assignee || assign.isPending} onClick={() => assign.mutate()}>
              {assign.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {tx('Assign')}
            </Button>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={!!abandoning} onOpenChange={(open) => !open && setAbandoning(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Abandon posting job {{invoice}}?', { invoice: abandoning?.invoiceNumber ?? '' })}</DialogTitle>
            <DialogDescription>
              {tx('Only for a job that can never post — for example its item or recipe no longer exists. The system will not deduct this sale’s stock or post its cost of sales: correct them by hand first (usually a stock adjustment), then record here how you did it. The job keeps its error and history and is never retried; its open exceptions are closed with your note.')}
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-1.5">
            <Label htmlFor="abandon-note">{tx('How were the stock and cost of sales corrected? (at least 10 characters, kept in the audit log)')}</Label>
            <Textarea
              id="abandon-note"
              rows={3}
              value={abandonNote}
              onChange={(e) => setAbandonNote(e.target.value)}
              placeholder={tx('Posted stock adjustment ADJ-2026-000045')}
            />
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setAbandoning(null)}>{tx('Cancel')}</Button>
            <Button variant="destructive" disabled={abandonNote.trim().length < 10 || abandon.isPending} onClick={() => abandon.mutate()}>
              {abandon.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {tx('Abandon')}
            </Button>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={!!resolving} onOpenChange={(open) => !open && setResolving(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{resolving?.action === 'resolved' ? tx('Mark as corrected') : tx('Discard this exception')}</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-muted-foreground">
            {resolving?.action === 'resolved'
              ? tx('Use this once the stock drift has been corrected — e.g. a manual stock adjustment or count was posted.')
              : tx('Use this only if the line must never deduct — a test, a duplicate, or an untracked item.')}
          </p>
          <div className="grid gap-1.5">
            <Label htmlFor="ex-note">{tx('What happened? (recorded in the audit log)')}</Label>
            <Input
              id="ex-note"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder={resolving?.action === 'resolved' ? tx('Posted stock adjustment ADJ-2026-000045') : tx('Untracked promo item')}
            />
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setResolving(null)}>{tx('Cancel')}</Button>
            <Button
              variant={resolving?.action === 'discarded' ? 'destructive' : 'default'}
              disabled={!note.trim() || resolve.isPending}
              onClick={() => resolve.mutate()}
            >
              {resolve.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {tx('Confirm')}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="font-medium break-all">{value}</dd>
    </div>
  );
}

function EmptyState({ label, hint }: { label: string; hint: string }) {
  return (
    <Card>
      <CardContent className="flex flex-col items-center gap-2 py-12 text-center">
        <CheckCircle2 className="h-8 w-8 text-emerald-500" />
        <p className="font-medium">{label}</p>
        <p className="text-sm text-muted-foreground">{hint}</p>
      </CardContent>
    </Card>
  );
}

function StatusDot({ status }: { status: string }) {
  const cls =
    status === 'failed' ? 'text-rose-500'
    : status === 'done' ? 'text-emerald-500'
    : status === 'processing' ? 'text-sky-500'
    : status === 'abandoned' ? 'text-muted-foreground'
    : 'text-amber-500';
  return <Inbox className={`h-4 w-4 ${cls}`} />;
}

export default PostingMonitorPage;

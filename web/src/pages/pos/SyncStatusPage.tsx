/**
 * ADR-S083 S9 — sync status dashboard: every device and branch relay node, ops applied today, open dead letters, this
 * browser's own queue, and the post-restore reconciliation (C2) with its report.
 */
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Activity, Building2, CheckCircle2, Inbox, Loader2, Play, RefreshCw, ShieldAlert } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { api } from '@/lib/api';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { relativeTime } from '@/lib/format';
import { useAuthStore } from '@/stores/auth.store';
import { syncEngineFor } from '@/lib/offline/web';
import type { SyncStatus } from '@/lib/offline/types';

type DeviceState = 'online' | 'idle' | 'stale' | 'never' | 'revoked';

interface StatusDevice {
  id: string;
  name: string;
  platform: string;
  branchId: string | null;
  branchName: string | null;
  serverId: string | null;
  protocolVersion: string | null;
  lastSyncAt: string | null;
  lastPushSeq: number;
  revokedAt: string | null;
  openDeadLetters: number;
  opsToday: number;
  state: DeviceState;
}

interface BranchTile {
  branchId: string;
  branchName: string | null;
  node: { deviceId: string; name: string; serverId: string | null; state: DeviceState; lastSyncAt: string | null } | null;
  tills: number;
  tillsOnline: number;
  tillsStale: number;
  opsToday: number;
  openDeadLetters: number;
  lastSyncAt: string | null;
}

interface ReconciliationSummary {
  id: string;
  trigger: string;
  status: 'clean' | 'issues' | 'failed';
  issueCount: number;
  startedAt: string;
}

interface StatusResponse {
  generatedAt: string;
  summary: { devices: number; online: number; stale: number; revoked: number; appliedToday: number; failedToday: number; openDeadLetters: number };
  opsToday: Record<string, number>;
  deadLetters: { failureCode: string; status: string; n: number }[];
  branches: BranchTile[];
  devices: StatusDevice[];
  protocolVersions: { version: string; devices: number }[];
  reconciliation: ReconciliationSummary | null;
}

interface ReconciliationCheck {
  key: string;
  label: string;
  ok: boolean | null;
  count: number;
  detail?: string;
  rows?: Record<string, unknown>[];
  truncated?: boolean;
}

interface ReconciliationReport extends ReconciliationSummary {
  checks: ReconciliationCheck[];
}

const STATE_VARIANT: Record<DeviceState, 'default' | 'secondary' | 'destructive' | 'outline'> = {
  online: 'default',
  idle: 'secondary',
  stale: 'destructive',
  never: 'outline',
  revoked: 'outline',
};

function StateBadge({ state }: { state: DeviceState }) {
  return <Badge variant={STATE_VARIANT[state]}>{tx(state)}</Badge>;
}

function Stat({ label, value, tone }: { label: string; value: number | string; tone?: 'bad' | 'good' }) {
  return (
    <Card>
      <CardContent className="pt-6">
        <p className="text-sm text-muted-foreground">{label}</p>
        <p className={`text-2xl font-semibold ${tone === 'bad' ? 'text-destructive' : tone === 'good' ? 'text-emerald-600' : ''}`}>{value}</p>
      </CardContent>
    </Card>
  );
}

function ThisBrowserQueue() {
  const org = useAuthStore((s) => s.organization?.id);
  const [status, setStatus] = useState<SyncStatus | null>(null);
  useEffect(() => {
    if (!org) return;
    return syncEngineFor(org).subscribe(setStatus);
  }, [org]);
  if (!status) return null;
  return (
    <p className="text-sm text-muted-foreground">
      {tx('This browser')}: {status.pending} {tx('waiting to sync')}, {status.inFlight} {tx('sending')}, {status.rejected} {tx('rejected')}
      {status.lastSyncAt ? ` · ${tx('last sync')} ${relativeTime(new Date(status.lastSyncAt))}` : ''}
    </p>
  );
}

function ReconciliationCard({ last }: { last: ReconciliationSummary | null }) {
  const qc = useQueryClient();
  const canRun = useAuthStore((s) => s.hasPermission(PERMISSIONS.organization.update));
  const report = useQuery({
    queryKey: ['sync-reconciliation', last?.id],
    queryFn: async () => (await api.get<ReconciliationReport>(`/sync/reconciliation/runs/${last!.id}`)).data,
    enabled: !!last,
  });
  const run = useMutation({
    mutationFn: async () => (await api.post<ReconciliationReport>('/sync/reconciliation/run', {})).data,
    onSuccess: (r) => {
      notify.success(r.status === 'clean' ? tx('Reconciliation clean') : `${tx('Reconciliation found')} ${r.issueCount} ${tx('issue(s)')}`);
      void qc.invalidateQueries({ queryKey: ['sync-status'] });
    },
    onError: (e: any) => notify.error(e?.response?.data?.message ?? tx('Reconciliation failed')),
  });
  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-4">
        <div>
          <CardTitle className="flex items-center gap-2"><ShieldAlert className="h-5 w-5" /> {tx('Post-restore reconciliation')}</CardTitle>
          <CardDescription>
            {tx('Runs after every server start and nightly. Read-only: it lists what a person should check.')}
          </CardDescription>
        </div>
        {canRun && (
          <Button size="sm" variant="outline" onClick={() => run.mutate()} disabled={run.isPending}>
            {run.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />} {tx('Run now')}
          </Button>
        )}
      </CardHeader>
      <CardContent className="space-y-3">
        {!last ? (
          <p className="text-sm text-muted-foreground">{tx('Not run yet.')}</p>
        ) : (
          <>
            <p className="text-sm">
              <Badge variant={last.status === 'clean' ? 'default' : 'destructive'}>{tx(last.status)}</Badge>{' '}
              {last.issueCount} {tx('issue(s)')} · {tx(last.trigger)} · {relativeTime(last.startedAt)}
            </p>
            {report.data?.checks?.map((c) => (
              <details key={c.key} className="rounded border p-2 text-sm" open={c.ok === false}>
                <summary className="flex cursor-pointer items-center gap-2">
                  {c.ok === false ? <ShieldAlert className="h-4 w-4 text-destructive" /> : <CheckCircle2 className="h-4 w-4 text-emerald-600" />}
                  <span className="font-medium">{c.label}</span>
                  <span className="text-muted-foreground">({c.count})</span>
                </summary>
                {c.detail && <p className="mt-1 text-muted-foreground">{c.detail}</p>}
                {c.rows && c.rows.length > 0 && (
                  <div className="mt-2 overflow-x-auto">
                    <Table>
                      <TableHeader>
                        <TableRow>{Object.keys(c.rows[0]).map((k) => <TableHead key={k}>{k}</TableHead>)}</TableRow>
                      </TableHeader>
                      <TableBody>
                        {c.rows.map((r, i) => (
                          <TableRow key={i}>{Object.keys(c.rows![0]).map((k) => <TableCell key={k} className="font-mono text-xs">{String(r[k] ?? '')}</TableCell>)}</TableRow>
                        ))}
                      </TableBody>
                    </Table>
                    {c.truncated && <p className="mt-1 text-xs text-muted-foreground">{tx('First 50 rows shown.')}</p>}
                  </div>
                )}
              </details>
            ))}
          </>
        )}
      </CardContent>
    </Card>
  );
}

export function SyncStatusPage() {
  const q = useQuery({
    queryKey: ['sync-status'],
    queryFn: async () => (await api.get<StatusResponse>('/sync/status')).data,
    refetchInterval: 30_000,
  });
  const s = q.data;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold"><Activity className="h-6 w-6" /> {tx('Sync status')}</h1>
          <p className="text-muted-foreground">{tx('Offline devices and branch servers: when each last synced and what needs attention.')}</p>
          <ThisBrowserQueue />
        </div>
        <div className="flex gap-2">
          <Link to="/settings/devices/rejected">
            <Button variant={s && s.summary.openDeadLetters > 0 ? 'destructive' : 'outline'} size="sm">
              <Inbox className="h-4 w-4" /> {s && s.summary.openDeadLetters > 0 ? `${s.summary.openDeadLetters} ${tx('rejected')}` : tx('Rejected ops')}
            </Button>
          </Link>
          <Button variant="outline" size="sm" onClick={() => q.refetch()} disabled={q.isFetching}>
            <RefreshCw className={`h-4 w-4 ${q.isFetching ? 'animate-spin' : ''}`} /> {tx('Refresh')}
          </Button>
        </div>
      </div>

      {!s ? (
        <Skeleton className="h-40 w-full" />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-6">
            <Stat label={tx('Devices')} value={s.summary.devices} />
            <Stat label={tx('Online now')} value={s.summary.online} tone="good" />
            <Stat label={tx('Not synced 24h')} value={s.summary.stale} tone={s.summary.stale > 0 ? 'bad' : undefined} />
            <Stat label={tx('Synced today')} value={s.summary.appliedToday} />
            <Stat label={tx('Rejected today')} value={s.summary.failedToday} tone={s.summary.failedToday > 0 ? 'bad' : undefined} />
            <Stat label={tx('Open rejected')} value={s.summary.openDeadLetters} tone={s.summary.openDeadLetters > 0 ? 'bad' : undefined} />
          </div>

          {s.branches.length > 0 && (
            <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
              {s.branches.map((b) => (
                <Card key={b.branchId}>
                  <CardHeader className="pb-2">
                    <CardTitle className="flex items-center gap-2 text-base"><Building2 className="h-4 w-4" /> {b.branchName ?? b.branchId}</CardTitle>
                    <CardDescription>
                      {b.node ? (
                        <span className="flex items-center gap-2">{tx('Branch server')} {b.node.serverId ?? b.node.name} <StateBadge state={b.node.state} /></span>
                      ) : tx('Tills sync to HQ directly')}
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-1 text-sm">
                    <p>{b.tills} {tx('tills')} · {b.tillsOnline} {tx('online')}{b.tillsStale > 0 ? ` · ${b.tillsStale} ${tx('not synced 24h')}` : ''}</p>
                    <p>{b.opsToday} {tx('ops today')} · {tx('last sync')} {relativeTime(b.lastSyncAt)}</p>
                    {b.openDeadLetters > 0 && <p className="text-destructive">{b.openDeadLetters} {tx('open rejected ops')}</p>}
                  </CardContent>
                </Card>
              ))}
            </div>
          )}

          <ReconciliationCard last={s.reconciliation} />

          <Card>
            <CardHeader>
              <CardTitle>{tx('Devices')}</CardTitle>
              <CardDescription>
                {tx('Protocol versions')}: {s.protocolVersions.map((p) => `${p.version} × ${p.devices}`).join(', ') || '-'}
              </CardDescription>
            </CardHeader>
            <CardContent className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{tx('Device')}</TableHead>
                    <TableHead>{tx('Type')}</TableHead>
                    <TableHead>{tx('Branch')}</TableHead>
                    <TableHead>{tx('State')}</TableHead>
                    <TableHead>{tx('Last sync')}</TableHead>
                    <TableHead className="text-right">{tx('Ops today')}</TableHead>
                    <TableHead className="text-right">{tx('Rejected')}</TableHead>
                    <TableHead>{tx('Protocol')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {s.devices.map((d) => (
                    <TableRow key={d.id} className={d.state === 'revoked' ? 'opacity-60' : ''}>
                      <TableCell className="font-medium">{d.name}</TableCell>
                      <TableCell>{d.platform}</TableCell>
                      <TableCell>{d.branchName ?? '-'}</TableCell>
                      <TableCell><StateBadge state={d.state} /></TableCell>
                      <TableCell>{relativeTime(d.lastSyncAt)}</TableCell>
                      <TableCell className="text-right">{d.opsToday}</TableCell>
                      <TableCell className={`text-right ${d.openDeadLetters > 0 ? 'text-destructive' : ''}`}>{d.openDeadLetters}</TableCell>
                      <TableCell>{d.protocolVersion ?? '-'}</TableCell>
                    </TableRow>
                  ))}
                  {s.devices.length === 0 && (
                    <TableRow><TableCell colSpan={8} className="text-center text-muted-foreground">{tx('No devices registered.')}</TableCell></TableRow>
                  )}
                </TableBody>
              </Table>
            </CardContent>
          </Card>

          {s.deadLetters.length > 0 && (
            <Card>
              <CardHeader><CardTitle>{tx('Open rejected ops by reason')}</CardTitle></CardHeader>
              <CardContent className="space-y-1 text-sm">
                {s.deadLetters.map((r) => (
                  <p key={`${r.failureCode}-${r.status}`}><span className="font-mono">{r.failureCode ?? '-'}</span> · {tx(r.status)} · {r.n}</p>
                ))}
              </CardContent>
            </Card>
          )}
        </>
      )}
    </div>
  );
}

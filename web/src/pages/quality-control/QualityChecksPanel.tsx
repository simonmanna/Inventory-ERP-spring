import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueries } from '@tanstack/react-query';
import { BellRing, ClipboardCheck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { PERMISSIONS } from '@erp/shared';
import { api } from '@/lib/api';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import type { PaginatedResult } from '@erp/shared';
import { CHECK_TYPE_LABEL, QUARANTINE_STATUS_LABEL, type QcCheck, type QcSourceType } from '@/features/quality-control/api';
import { BlocksPill, CheckStatusPill } from './qc-ui';
import { AlertDialog } from './alert-ui';
import { num } from './qc-format';

export interface QcSource { sourceType: Exclude<QcSourceType, 'manual'>; sourceId: string; label?: string }

/**
 * The quality checks of a document (ADR-S087 §6), on its detail page: what blocks it, what is pending, what failed.
 * Completion is enforced on the server; this panel shows why an action was refused and links to each check.
 */
export function QualityChecksPanel({ sources, title }: { sources: QcSource[]; title?: string }) {
  const can = useAuthStore((s) => s.hasPermission(PERMISSIONS.qualityControl.read));
  const canAlert = useAuthStore((s) => s.hasPermission(PERMISSIONS.qualityControl.alert));
  const [raising, setRaising] = useState(false);
  const results = useQueries({
    queries: sources.map((s) => ({
      queryKey: ['quality-control', 'checks', 'source', s.sourceType, s.sourceId],
      enabled: can && !!s.sourceId,
      queryFn: async () => (await api.get<PaginatedResult<QcCheck>>('/quality-control/checks', {
        params: { sourceType: s.sourceType, sourceId: s.sourceId, page: 1, pageSize: 100 },
      })).data,
    })),
  });
  if (!can) return null;
  const rows = results.flatMap((r, i) => (r.data?.data ?? []).filter((c) => c.status !== 'cancelled')
    .map((c) => ({ check: c, label: sources[i].label })));
  const loading = results.some((r) => r.isLoading);
  if (!loading && rows.length === 0) return null;
  const blocking = rows.filter((r) => r.check.blocksSource).length;
  const pending = rows.filter((r) => r.check.status === 'pending').length;
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between gap-2">
        <CardTitle className="flex items-center gap-2"><ClipboardCheck className="h-4 w-4" /> {title ?? tx('Quality checks')}</CardTitle>
        <div className="flex items-center gap-1.5">
          {blocking > 0 && <Badge variant="destructive">{tx('{{n}} blocking', { n: blocking })}</Badge>}
          {pending > 0 && <Badge variant="secondary">{tx('{{n}} pending', { n: pending })}</Badge>}
          {canAlert && sources.length > 0 && (
            <Button size="sm" variant="outline" onClick={() => setRaising(true)}><BellRing className="mr-1.5 h-3.5 w-3.5" /> {tx('Quality alert')}</Button>
          )}
        </div>
      </CardHeader>
      <CardContent className="p-0">
        {blocking > 0 && (
          <p className="px-4 pb-2 text-sm text-muted-foreground">
            {tx('Blocking checks must be done (or waived by a quality manager) before this document can be completed.')}
          </p>
        )}
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Check')}</TableHead>
              <TableHead>{tx('Type')}</TableHead>
              <TableHead>{tx('Product')}</TableHead>
              <TableHead className="text-right">{tx('Covers')}</TableHead>
              <TableHead className="text-right">{tx('Test qty')}</TableHead>
              <TableHead>{tx('Status')}</TableHead>
              <TableHead>{tx('Quarantine')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map(({ check: c, label }) => (
              <TableRow key={c.id}>
                <TableCell>
                  <Link to={`/quality-control/checks/${c.id}`} className="font-medium text-primary hover:underline">{c.checkNumber}</Link>
                  <div className="text-xs text-muted-foreground line-clamp-1 max-w-[16rem]">{label ? `${label} · ` : ''}{c.title}</div>
                </TableCell>
                <TableCell>{CHECK_TYPE_LABEL[c.checkType]}</TableCell>
                <TableCell>{c.productName ?? '—'}{c.batchNumber ? <div className="text-xs text-muted-foreground">{c.batchNumber}</div> : null}</TableCell>
                <TableCell className="text-right tabular-nums">{c.coveredQty != null ? num(c.coveredQty) : '—'}</TableCell>
                <TableCell className="text-right tabular-nums">{c.testQty != null ? num(c.testQty) : '—'}</TableCell>
                <TableCell><div className="flex items-center gap-1.5"><CheckStatusPill status={c.status} /><BlocksPill blocks={c.blocksSource} /></div></TableCell>
                <TableCell className="text-sm">
                  {c.quarantineStatus && c.quarantineStatus !== 'not_required'
                    ? <span className={c.quarantineStatus === 'failed' ? 'text-destructive' : ''}>{QUARANTINE_STATUS_LABEL[c.quarantineStatus]}{c.quarantinedQty != null && Number(c.quarantinedQty) > 0 ? ` · ${num(c.quarantinedQty)}` : ''}</span>
                    : '—'}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
      {raising && <AlertDialog source={{ sourceType: sources[0].sourceType, sourceId: sources[0].sourceId }} onClose={() => setRaising(false)} />}
    </Card>
  );
}

import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, FileUp, RefreshCw, Download, AlertTriangle } from 'lucide-react';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { api } from '@/lib/api';
import { useIdempotencyKey } from '@/lib/idempotency';
import { apiMessage, notify } from '@/lib/notify';
import { dateTime, money } from '@/lib/format';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import {
  errorsByRow, OPENING_STOCK_TEMPLATE, parseOpeningStockCsv, runStatusLabel, type OpeningStockRow,
} from '@/features/inventory/opening-stock-csv';

/** IPR R5 / ADR-S090: opening-stock CSV import — preview, approve, run status, batch retry, reconciliation. */

interface JournalLine {
  accountId: string; accountCode: string | null; accountName: string | null; debit: string; credit: string;
  productName?: string | null;
}
interface Journal { lines: JournalLine[]; totalDebit: string; totalCredit: string }
interface PreviewRow {
  rowNo: number; productCode: string | null; productName: string | null; variantName: string | null;
  locationCode: string | null; locationName: string | null; quantity: string | null; unitCost: string | null;
  costSource: string | null; value: string | null; batchNumber: string | null; expiryDate: string | null;
  serialNumbers: string[];
}
interface PreviewBatch { batchNo: number; locationName: string | null; rowCount: number; totalValue: string; journal: Journal }
interface Preview {
  valid: boolean; previewToken: string | null; rowCount: number; errorCount: number; globalErrors: string[];
  rows: PreviewRow[]; errors: Array<{ rowNo: number; message: string }>; batches: PreviewBatch[];
  totalValue: string; journal: Journal;
}
interface RunBatch {
  id: string; batchNo: number; locationName: string | null; status: string; rowCount: number; totalValue: string;
  ledgerValue: string | null; journalEntryId: string | null; attempts: number; error: string | null;
}
interface Run {
  id: string; runCode: string; status: string; fileName: string | null; rowCount: number; batchCount: number;
  totalValue: string; approvedAt: string; completedAt: string | null; batches?: RunBatch[];
}
interface Reconciliation {
  reconciled: boolean; complete: boolean; previewedValue: string; ledgerValue: string; journalAmount: string;
  equityCredit: string; valuationDelta: string;
  batches: Array<{ batchNo: number; status: string; ledgerValue: string; journalAmount: string; valuationDelta: string; ok: boolean }>;
}
interface BatchDetail { revalidation?: { valid: boolean; stale: boolean; batchToken: string | null; journal: Journal | null;
  errors: Array<{ rowNo: number; message: string }>; globalErrors: string[] } }

const BASE = '/inventory/opening-stock';

function JournalTable({ journal }: { journal: Journal }) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>{tx('Account')}</TableHead>
          <TableHead>{tx('Product')}</TableHead>
          <TableHead className="text-right">{tx('Debit')}</TableHead>
          <TableHead className="text-right">{tx('Credit')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {journal.lines.map((l, i) => (
          <TableRow key={`${l.accountId}-${i}`}>
            <TableCell>{l.accountCode ? `${l.accountCode} · ${l.accountName}` : l.accountId}</TableCell>
            <TableCell>{l.productName ?? ''}</TableCell>
            <TableCell className="text-right">{Number(l.debit) ? money(l.debit) : ''}</TableCell>
            <TableCell className="text-right">{Number(l.credit) ? money(l.credit) : ''}</TableCell>
          </TableRow>
        ))}
        <TableRow>
          <TableCell className="font-semibold">{tx('Total')}</TableCell>
          <TableCell />
          <TableCell className="text-right font-semibold">{money(journal.totalDebit)}</TableCell>
          <TableCell className="text-right font-semibold">{money(journal.totalCredit)}</TableCell>
        </TableRow>
      </TableBody>
    </Table>
  );
}

function StatusBadge({ status }: { status: string }) {
  const s = runStatusLabel(status);
  const variant = s.tone === 'ok' ? 'default' : s.tone === 'bad' ? 'destructive' : s.tone === 'warn' ? 'secondary' : 'outline';
  return <Badge variant={variant}>{tx(s.label)}</Badge>;
}

export function OpeningStockImportPage() {
  const qc = useQueryClient();
  const can = useAuthStore((s) => s.hasPermission);
  const canPreview = can('inventory_valuation:manage');
  const canPost = can('inventory_valuation:post');
  const [fileName, setFileName] = useState<string | null>(null);
  const [rows, setRows] = useState<OpeningStockRow[]>([]);
  const [fileErrors, setFileErrors] = useState<string[]>([]);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [selectedRun, setSelectedRun] = useState<string | null>(null);
  const importKey = useIdempotencyKey();
  const retryKey = useIdempotencyKey();

  const runs = useQuery({
    queryKey: ['opening-stock-runs'],
    queryFn: async () => (await api.get<Run[]>(`${BASE}/runs`)).data,
  });
  const run = useQuery({
    queryKey: ['opening-stock-run', selectedRun],
    enabled: !!selectedRun,
    queryFn: async () => (await api.get<Run>(`${BASE}/runs/${selectedRun}`)).data,
  });
  const reconciliation = useQuery({
    queryKey: ['opening-stock-reconciliation', selectedRun],
    enabled: !!selectedRun,
    queryFn: async () => (await api.get<Reconciliation>(`${BASE}/runs/${selectedRun}/reconciliation`)).data,
  });

  const refreshRun = () => {
    qc.invalidateQueries({ queryKey: ['opening-stock-runs'] });
    qc.invalidateQueries({ queryKey: ['opening-stock-run'] });
    qc.invalidateQueries({ queryKey: ['opening-stock-reconciliation'] });
  };

  const previewMut = useMutation({
    mutationFn: async () => (await api.post<Preview>(`${BASE}/preview`, { rows, fileName })).data,
    onSuccess: (p) => setPreview(p),
    onError: (e) => notify.error(apiMessage(e, tx('Preview failed'))),
  });

  const importMut = useMutation({
    mutationFn: async () => (await api.post<Run>(`${BASE}/import`, {
      rows, previewToken: preview?.previewToken, approve: true, fileName,
    }, importKey.config())).data,
    onSuccess: (r) => {
      importKey.settle();
      notify.success(tx('Opening stock imported'), `${r.runCode} · ${tx(runStatusLabel(r.status).label)}`);
      setPreview(null);
      setRows([]);
      setSelectedRun(r.id);
      refreshRun();
    },
    onError: (e) => {
      importKey.settle(e);
      notify.error(apiMessage(e, tx('Import failed')));
      if ((e as { response?: { status?: number } })?.response?.status === 409) setPreview(null);
    },
  });

  const retryMut = useMutation({
    mutationFn: async ({ batchId, batchToken }: { batchId: string; batchToken?: string | null }) => (await api.post<Run>(
      `${BASE}/runs/${selectedRun}/batches/${batchId}/retry`, { approve: true, batchToken: batchToken ?? null },
      retryKey.config())).data,
    onSuccess: () => {
      retryKey.settle();
      notify.success(tx('Batch posted'));
      refreshRun();
    },
    onError: (e) => {
      retryKey.settle(e);
      notify.error(apiMessage(e, tx('Retry failed')));
      refreshRun();
    },
  });

  const [reapprove, setReapprove] = useState<{ batchId: string; detail: BatchDetail } | null>(null);
  const openRetry = async (batchId: string) => {
    const detail = (await api.get<BatchDetail>(`${BASE}/runs/${selectedRun}/batches/${batchId}`)).data;
    if (detail.revalidation?.stale) setReapprove({ batchId, detail });
    else retryMut.mutate({ batchId });
  };

  const rowErrors = useMemo(() => errorsByRow(preview?.errors ?? []), [preview]);

  const onFile = async (file: File | undefined) => {
    if (!file) return;
    const parsed = parseOpeningStockCsv(await file.text());
    setFileName(file.name);
    setRows(parsed.rows);
    setFileErrors(parsed.errors);
    setPreview(null);
  };

  const downloadTemplate = () => {
    const url = URL.createObjectURL(new Blob([OPENING_STOCK_TEMPLATE], { type: 'text/csv;charset=utf-8' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'opening-stock-template.csv';
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="space-y-4 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="text-xl font-semibold">{tx('Opening stock import')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Only for locations with no stock history. The value posts against opening equity; each batch (up to 2,000 rows of one location) posts on its own.')}
          </p>
        </div>
        <Button variant="outline" onClick={downloadTemplate}><Download className="mr-1 h-4 w-4" />{tx('CSV template')}</Button>
      </div>

      {canPreview && (
        <Card>
          <CardContent className="space-y-3 pt-4">
            <label className="flex cursor-pointer items-center gap-2 text-sm">
              <FileUp className="h-4 w-4" />
              <input type="file" accept=".csv,text/csv" onChange={(e) => onFile(e.target.files?.[0])} />
            </label>
            {fileName && <p className="text-sm">{fileName}: {rows.length} {tx('row(s)')}</p>}
            {fileErrors.map((e) => <p key={e} className="text-sm text-destructive">{e}</p>)}
            <Button disabled={rows.length === 0 || fileErrors.length > 0 || previewMut.isPending} onClick={() => previewMut.mutate()}>
              {tx('Preview')}
            </Button>
          </CardContent>
        </Card>
      )}

      {preview && (
        <Card>
          <CardContent className="space-y-3 pt-4">
            <div className="flex flex-wrap items-center gap-3">
              {preview.valid
                ? <Badge><CheckCircle2 className="mr-1 h-3 w-3" />{tx('Valid')}</Badge>
                : <Badge variant="destructive"><AlertTriangle className="mr-1 h-3 w-3" />{preview.errorCount} {tx('error(s)')}</Badge>}
              <span className="text-sm">{preview.rowCount} {tx('row(s)')} · {preview.batches.length} {tx('batch(es)')} · {money(preview.totalValue)}</span>
            </div>
            {preview.globalErrors.map((e) => <p key={e} className="text-sm text-destructive">{e}</p>)}
            <div className="max-h-96 overflow-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>#</TableHead><TableHead>{tx('Product')}</TableHead><TableHead>{tx('Location')}</TableHead>
                    <TableHead className="text-right">{tx('Qty')}</TableHead><TableHead className="text-right">{tx('Unit cost')}</TableHead>
                    <TableHead className="text-right">{tx('Value')}</TableHead><TableHead>{tx('Lot / expiry / serials')}</TableHead>
                    <TableHead>{tx('Problems')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {preview.rows.map((r) => (
                    <TableRow key={r.rowNo} className={rowErrors.has(r.rowNo) ? 'bg-destructive/10' : ''}>
                      <TableCell>{r.rowNo}</TableCell>
                      <TableCell>{r.productName ?? r.productCode}{r.variantName ? ` · ${r.variantName}` : ''}</TableCell>
                      <TableCell>{r.locationName ?? r.locationCode}</TableCell>
                      <TableCell className="text-right">{r.quantity}</TableCell>
                      <TableCell className="text-right">{r.unitCost ? money(r.unitCost) : ''}{r.costSource && r.costSource !== 'file' ? ` (${tx(r.costSource === 'standard' ? 'standard' : 'cost price')})` : ''}</TableCell>
                      <TableCell className="text-right">{r.value ? money(r.value) : ''}</TableCell>
                      <TableCell className="text-xs">{[r.batchNumber, r.expiryDate, r.serialNumbers.join(', ')].filter(Boolean).join(' · ')}</TableCell>
                      <TableCell className="text-xs text-destructive">{(rowErrors.get(r.rowNo) ?? []).join('; ')}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            {preview.batches.length > 0 && (
              <div className="space-y-2">
                <h2 className="font-medium">{tx('Journal to post')}</h2>
                <JournalTable journal={preview.journal} />
                {preview.batches.map((b) => (
                  <p key={b.batchNo} className="text-xs text-muted-foreground">
                    {tx('Batch')} {b.batchNo} · {b.locationName} · {b.rowCount} {tx('row(s)')} · {money(b.totalValue)}
                  </p>
                ))}
              </div>
            )}
            {canPost && (
              <Button disabled={!preview.valid || importMut.isPending} onClick={() => importMut.mutate()}>
                <CheckCircle2 className="mr-1 h-4 w-4" />{tx('Approve and import')}
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      <Card>
        <CardContent className="space-y-2 pt-4">
          <h2 className="font-medium">{tx('Import runs')}</h2>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Run')}</TableHead><TableHead>{tx('File')}</TableHead><TableHead>{tx('Status')}</TableHead>
                <TableHead className="text-right">{tx('Rows')}</TableHead><TableHead className="text-right">{tx('Value')}</TableHead>
                <TableHead>{tx('Approved')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(runs.data ?? []).map((r) => (
                <TableRow key={r.id} className="cursor-pointer" onClick={() => setSelectedRun(r.id)}>
                  <TableCell className="font-medium">{r.runCode}</TableCell>
                  <TableCell>{r.fileName}</TableCell>
                  <TableCell><StatusBadge status={r.status} /></TableCell>
                  <TableCell className="text-right">{r.rowCount}</TableCell>
                  <TableCell className="text-right">{money(r.totalValue)}</TableCell>
                  <TableCell>{dateTime(r.approvedAt)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {run.data && (
        <Card>
          <CardContent className="space-y-3 pt-4">
            <div className="flex items-center gap-2">
              <h2 className="font-medium">{run.data.runCode}</h2>
              <StatusBadge status={run.data.status} />
              <Button size="sm" variant="ghost" onClick={refreshRun}><RefreshCw className="h-4 w-4" /></Button>
            </div>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Batch')}</TableHead><TableHead>{tx('Location')}</TableHead><TableHead>{tx('Status')}</TableHead>
                  <TableHead className="text-right">{tx('Rows')}</TableHead><TableHead className="text-right">{tx('Value')}</TableHead>
                  <TableHead>{tx('Error')}</TableHead><TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {(run.data.batches ?? []).map((b) => (
                  <TableRow key={b.id}>
                    <TableCell>{b.batchNo}</TableCell>
                    <TableCell>{b.locationName}</TableCell>
                    <TableCell>{b.status}</TableCell>
                    <TableCell className="text-right">{b.rowCount}</TableCell>
                    <TableCell className="text-right">{money(b.ledgerValue ?? b.totalValue)}</TableCell>
                    <TableCell className="text-xs text-destructive">{b.error}</TableCell>
                    <TableCell>
                      {canPost && b.status !== 'posted' && (
                        <Button size="sm" disabled={retryMut.isPending} onClick={() => openRetry(b.id)}>{tx('Retry')}</Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            {reapprove && (
              <div className="space-y-2 rounded border p-3">
                <p className="text-sm">{tx('Inputs changed since the preview. Review the batch journal as it would post now and approve it again.')}</p>
                {reapprove.detail.revalidation?.errors.map((e) => (
                  <p key={`${e.rowNo}-${e.message}`} className="text-sm text-destructive">{tx('Row')} {e.rowNo}: {e.message}</p>
                ))}
                {reapprove.detail.revalidation?.journal && <JournalTable journal={reapprove.detail.revalidation.journal} />}
                <div className="flex gap-2">
                  <Button
                    disabled={!reapprove.detail.revalidation?.valid || retryMut.isPending}
                    onClick={() => {
                      retryMut.mutate({ batchId: reapprove.batchId, batchToken: reapprove.detail.revalidation?.batchToken });
                      setReapprove(null);
                    }}
                  >{tx('Approve and post batch')}</Button>
                  <Button variant="outline" onClick={() => setReapprove(null)}>{tx('Cancel')}</Button>
                </div>
              </div>
            )}
            {reconciliation.data && (
              <div className="space-y-1 text-sm">
                <h3 className="font-medium">
                  {tx('Reconciliation')}{' '}
                  {reconciliation.data.reconciled
                    ? <Badge>{tx('Reconciled')}</Badge>
                    : <Badge variant="destructive">{reconciliation.data.complete ? tx('Difference') : tx('Not complete')}</Badge>}
                </h3>
                <p>{tx('Previewed')}: {money(reconciliation.data.previewedValue)} · {tx('Stock ledger')}: {money(reconciliation.data.ledgerValue)} · {tx('Journal')}: {money(reconciliation.data.journalAmount)} · {tx('Opening equity')}: {money(reconciliation.data.equityCredit)} · {tx('Valuation change')}: {money(reconciliation.data.valuationDelta)}</p>
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

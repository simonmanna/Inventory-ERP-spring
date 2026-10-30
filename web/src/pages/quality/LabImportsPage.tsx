import { useState } from 'react';
import { FlaskRound, Upload } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, ListCard, ListPageHeader, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  useFetchLab, useImportLabCsv, useInspectionLots, useLabAdapters, useLabImports, type LabImport,
} from '@/features/quality/api';
import { Field, OptionalSelect } from './quality-ui';

const SAMPLE = 'lotNumber,parameterCode,sampleNo,value,sieveMm,passingPct,testedAt,labReference\nQL-000001,MOIST,1,4.2,,,2026-10-30,LAB-77\nQL-000001,GRAD,1,,20,98,2026-10-30,LAB-77\n';

/** Laboratory results (ADR-S013): CSV import and fetch through a LabResultAdapter (REST / simulator), with history. */
export function LabImportsPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canImport = has(PERMISSIONS.quality.inspect);
  const [content, setContent] = useState('');
  const [filename, setFilename] = useState('');
  const [adapter, setAdapter] = useState('simulator');
  const [selected, setSelected] = useState<string[]>([]);
  const [page, setPage] = useState(1);
  const [last, setLast] = useState<LabImport | null>(null);
  const importCsv = useImportLabCsv();
  const fetchLab = useFetchLab();
  const adapters = useLabAdapters();
  const openLots = useInspectionLots({ page: 1, pageSize: 50, status: 'open' });
  const heldLots = useInspectionLots({ page: 1, pageSize: 50, status: 'quarantined' });
  const history = useLabImports({ page, pageSize: 20 });
  const lots = [...(openLots.data?.data ?? []), ...(heldLots.data?.data ?? [])];

  const onFile = async (f: File | undefined) => {
    if (!f) return;
    setFilename(f.name);
    setContent(await f.text());
  };
  const runCsv = async () => {
    try {
      const out = await importCsv.mutateAsync({ content, filename: filename || undefined });
      setLast(out);
      notify.success(tx('{{imported}} of {{total}} row(s) imported', { imported: out.rowsImported, total: out.rowsTotal }));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not import the file')));
    }
  };
  const runFetch = async () => {
    try {
      const out = await fetchLab.mutateAsync({ adapter, lotIds: selected });
      setLast(out);
      notify.success(tx('{{imported}} of {{total}} row(s) imported', { imported: out.rowsImported, total: out.rowsTotal }));
      setSelected([]);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not fetch the laboratory results')));
    }
  };
  const tone = (s: LabImport['status']) => (s === 'completed' ? 'success' : s === 'partial' ? 'warning' : 'danger');
  const statusLabel = (s: LabImport['status']) => (s === 'completed' ? tx('Completed') : s === 'partial' ? tx('Partial') : tx('Failed'));
  const columns: Column<LabImport>[] = [
    { key: 'at', header: tx('When'), render: (i) => dateTime(i.createdAt) },
    { key: 'adapter', header: tx('Source'), render: (i) => i.adapter + (i.filename ? ` · ${i.filename}` : '') },
    { key: 'rows', header: tx('Rows'), className: 'text-right', render: (i) => `${i.rowsImported} / ${i.rowsTotal}` },
    { key: 'by', header: tx('By'), render: (i) => i.importedByName || '—' },
    { key: 'status', header: tx('Status'), render: (i) => <StatusPill tone={tone(i.status)}>{statusLabel(i.status)}</StatusPill> },
  ];
  const fetchAdapters = (adapters.data ?? []).filter((a) => a.fetch);

  return (
    <div className="space-y-4">
      <ListPageHeader icon={FlaskRound} title={tx('Laboratory Results')}
        description={tx('Import results from an external laboratory as CSV, or fetch them through the laboratory connector.')} />
      {canImport && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <Card>
            <CardHeader><CardTitle className="text-base">{tx('CSV import')}</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              <p className="text-xs text-muted-foreground">{tx('Columns: lotNumber, parameterCode, value or sieveMm + passingPct, optional sampleNo, textValue, testedAt, labReference. One row per sieve.')}</p>
              <Input type="file" accept=".csv,text/csv,text/plain" onChange={(e) => onFile(e.target.files?.[0])} />
              <Textarea rows={8} className="font-mono text-xs" value={content} placeholder={SAMPLE} onChange={(e) => setContent(e.target.value)} />
              <div className="flex gap-2">
                <Button onClick={runCsv} disabled={!content.trim() || importCsv.isPending}><Upload className="mr-1.5 h-4 w-4" /> {tx('Import')}</Button>
                <Button variant="ghost" onClick={() => setContent(SAMPLE)}>{tx('Show example')}</Button>
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle className="text-base">{tx('Fetch from laboratory')}</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              <Field label={tx('Connector')}>
                <OptionalSelect value={adapter} onChange={(v) => setAdapter(v || 'simulator')} placeholder={tx('Connector')}
                  options={fetchAdapters.map((a) => ({ value: a.code, label: a.configured ? a.code : `${a.code} (${tx('not configured')})` }))} />
              </Field>
              <div className="max-h-64 overflow-y-auto rounded-md border">
                {lots.length === 0 && <p className="p-3 text-sm text-muted-foreground">{tx('No open lots.')}</p>}
                {lots.map((l) => (
                  <label key={l.id} className="flex items-center gap-2 border-b px-3 py-2 text-sm last:border-0">
                    <input type="checkbox" checked={selected.includes(l.id)} onChange={(e) => setSelected((xs) => (e.target.checked ? [...xs, l.id] : xs.filter((x) => x !== l.id)))} />
                    <span className="font-medium">{l.lotNumber}</span>
                    <span className="min-w-0 flex-1 truncate text-muted-foreground">{l.productName} {l.batchNumber ?? ''}</span>
                  </label>
                ))}
              </div>
              <Button onClick={runFetch} disabled={selected.length === 0 || fetchLab.isPending}>{tx('Fetch results')}</Button>
            </CardContent>
          </Card>
        </div>
      )}
      {last && last.errors.length > 0 && (
        <Card className="border-amber-200">
          <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Rejected rows')}</CardTitle></CardHeader>
          <CardContent>
            <ul className="space-y-1 text-sm">
              {last.errors.map((e, i) => <li key={i}><span className="font-medium">{tx('Row {{row}}', { row: e.row })}</span> {e.lotNumber ?? ''} {e.parameterCode ?? ''}: {e.message}</li>)}
            </ul>
          </CardContent>
        </Card>
      )}
      <ListCard>
        <DataTable columns={columns} data={history.data?.data ?? []} loading={history.isLoading} getRowId={(i) => i.id}
          onRowClick={(i) => setLast(i)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
          emptyMessage={history.isError ? tx('Could not load the imports.') : tx('No imports yet.')} />
        {history.data?.meta && history.data.meta.total > 0 && (
          <DataTablePagination page={history.data.meta.page} pageSize={20} total={history.data.meta.total} totalPages={history.data.meta.totalPages} onPageChange={setPage} noun="import" />
        )}
      </ListCard>
    </div>
  );
}

import { useState } from 'react';
import { toast } from 'sonner';
import { Download, Upload } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';

type Row = Record<string, string>;
interface Result { dryRun: boolean; total: number; valid: number; imported: number; rejected: number; errors: Array<{ row: number; name: string | null; errors: string[] }> }

function parseCsv(text: string): Row[] {
  const rows: string[][] = [];
  let cell = ''; let row: string[] = []; let quoted = false;
  const src = text.replace(/^\uFEFF/, '');
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    if (quoted) {
      if (ch === '"' && src[i + 1] === '"') { cell += '"'; i++; } else if (ch === '"') quoted = false; else cell += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ',') { row.push(cell); cell = ''; } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && src[i + 1] === '\n') i++;
      row.push(cell); rows.push(row); row = []; cell = '';
    } else cell += ch;
  }
  if (cell || row.length) { row.push(cell); rows.push(row); }
  const [head, ...body] = rows;
  if (!head) return [];
  return body.filter((r) => r.some((c) => c.trim())).map((r) => Object.fromEntries(head.map((h, i) => [h.trim(), r[i] ?? ''])));
}

/** Customer CSV export, and import with a check step: bad rows are listed and never written. */
export function CustomerImportExport() {
  const canRead = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.customerRead));
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.customerManage));
  const [rows, setRows] = useState<Row[] | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [busy, setBusy] = useState(false);
  if (!canRead) return null;
  const download = async () => {
    try {
      const r = await api.get('/sales/customers/export', { responseType: 'blob' });
      const url = URL.createObjectURL(r.data as Blob);
      const a = document.createElement('a'); a.href = url; a.download = 'customers.csv'; a.click(); URL.revokeObjectURL(url);
    } catch (e) { toast.error(apiErrorMessage(e, tx('Could not export'))); }
  };
  const send = async (data: Row[], dryRun: boolean) => {
    setBusy(true);
    try {
      const r = (await api.post<Result>('/sales/customers/import', { rows: data, dryRun })).data;
      setResult(r);
      if (!dryRun) toast.success(`${r.imported} ${tx('customers imported')}`);
    } catch (e) { toast.error(apiErrorMessage(e, tx('Could not import'))); } finally { setBusy(false); }
  };
  const pick = async (f: File | undefined) => {
    if (!f) return;
    const parsed = parseCsv(await f.text());
    setRows(parsed); setResult(null);
    await send(parsed, true);
  };
  return (
    <div className="flex flex-wrap justify-end gap-2">
      <Button variant="outline" size="sm" onClick={download}><Download className="mr-1.5 h-4 w-4" />{tx('Export CSV')}</Button>
      {canManage && (
        <label className="inline-flex h-9 cursor-pointer items-center rounded-md border bg-background px-3 text-sm hover:bg-muted">
          <Upload className="mr-1.5 h-4 w-4" />{tx('Import CSV')}
          <input type="file" accept=".csv,text/csv" className="hidden" onChange={(e) => { void pick(e.target.files?.[0]); e.target.value = ''; }} />
        </label>
      )}
      <Dialog open={!!rows} onOpenChange={(v) => { if (!v) { setRows(null); setResult(null); } }}>
        <DialogContent className="max-w-2xl">
          <DialogHeader><DialogTitle>{tx('Import customers')}</DialogTitle></DialogHeader>
          {result && (
            <div className="space-y-2 text-sm">
              <p>{result.total} {tx('rows')}: {result.valid} {tx('valid')}, {result.rejected} {tx('rejected')}{!result.dryRun && `, ${result.imported} ${tx('imported')}`}.</p>
              {result.errors.length > 0 && <ul className="max-h-64 list-disc overflow-auto pl-4 text-xs">{result.errors.map((e) => <li key={e.row}>{tx('Row')} {e.row}{e.name ? ` (${e.name})` : ''}: {e.errors.join('; ')}</li>)}</ul>}
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => { setRows(null); setResult(null); }}>{tx('Close')}</Button>
            {result?.dryRun && result.valid > 0 && <Button disabled={busy} onClick={() => send(rows!, false)}>{tx('Import valid rows')}</Button>}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

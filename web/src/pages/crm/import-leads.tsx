import { useState } from 'react';
import { toast } from 'sonner';
import { FileSpreadsheet } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { crmApi, useCrmMutation, type ImportResult } from '@/features/crm/suite-api';
import { downloadCsv, parseCsv } from './csv';

/** Import columns and the header spellings accepted for each (case and spacing ignored). */
const COLUMNS: Array<{ key: string; label: string; aliases: string[] }> = [
  { key: 'name', label: 'Name', aliases: ['name', 'lead', 'lead name', 'title', 'subject'] },
  { key: 'companyName', label: 'Company', aliases: ['company', 'company name', 'organisation', 'organization', 'business'] },
  { key: 'contactName', label: 'Contact', aliases: ['contact', 'contact name', 'contact person', 'person', 'full name'] },
  { key: 'email', label: 'Email', aliases: ['email', 'e-mail', 'email address', 'mail'] },
  { key: 'phone', label: 'Phone', aliases: ['phone', 'telephone', 'tel', 'mobile', 'phone number', 'cell'] },
  { key: 'city', label: 'City', aliases: ['city', 'town'] },
  { key: 'country', label: 'Country', aliases: ['country'] },
  { key: 'amount', label: 'Expected revenue', aliases: ['amount', 'expected revenue', 'value', 'revenue', 'budget'] },
  { key: 'expectedClose', label: 'Expected close', aliases: ['expected close', 'close date', 'expected closing', 'closing date'] },
  { key: 'source', label: 'Source', aliases: ['source', 'lead source', 'channel'] },
  { key: 'tags', label: 'Tags', aliases: ['tags', 'tag', 'labels'] },
  { key: 'owner', label: 'Salesperson email', aliases: ['owner', 'salesperson', 'salesperson email', 'owner email', 'assigned to'] },
  { key: 'notes', label: 'Notes', aliases: ['notes', 'note', 'description', 'comments'] },
];

const norm = (h: string) => h.trim().toLowerCase().replace(/[_\s]+/g, ' ');

/** Rows of a parsed CSV keyed by import column; unknown headers are listed so the user can see what was ignored. */
function mapRows(table: string[][]) {
  const [head = [], ...body] = table;
  const keys = head.map((h) => COLUMNS.find((c) => c.aliases.includes(norm(h)))?.key ?? null);
  const ignored = head.filter((_, i) => keys[i] == null).filter((h) => h.trim() !== '');
  const rows = body.map((r) => {
    const o: Record<string, string> = {};
    keys.forEach((k, i) => { if (k && (r[i] ?? '').trim() !== '') o[k] = r[i].trim(); });
    return o;
  });
  return { rows, ignored, mapped: COLUMNS.filter((c) => keys.includes(c.key)).map((c) => c.label) };
}

const STATUS_STYLE: Record<string, string> = {
  ok: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300',
  created: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300',
  duplicate: 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300',
  skipped: 'bg-muted text-muted-foreground',
  error: 'bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-300',
};

/** Lead CSV import: choose a file, preview (checks, duplicates), then import (CRM enhancements T2a). */
export function ImportLeadsDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const [file, setFile] = useState<string | null>(null);
  const [parsed, setParsed] = useState<ReturnType<typeof mapRows> | null>(null);
  const [skipDuplicates, setSkipDuplicates] = useState(true);
  const [result, setResult] = useState<ImportResult | null>(null);
  const m = useCrmMutation(crmApi.importLeads);

  const reset = () => { setFile(null); setParsed(null); setResult(null); };
  const pick = async (f: File | undefined) => {
    if (!f) return;
    reset();
    const table = parseCsv(await f.text());
    const p = mapRows(table);
    if (!p.mapped.includes('Name')) { toast.error(tx('The file needs a "Name" column.')); return; }
    if (p.rows.length === 0) { toast.error(tx('The file has no rows.')); return; }
    if (p.rows.length > 1000) { toast.error(tx('At most 1,000 rows per file.')); return; }
    setFile(f.name);
    setParsed(p);
    m.mutate({ rows: p.rows, dryRun: true, skipDuplicates: false }, { onSuccess: setResult, onError: (e) => toast.error(apiErrorMessage(e, tx('Could not check the file'))) });
  };
  const commit = () => parsed && m.mutate({ rows: parsed.rows, dryRun: false, skipDuplicates }, {
    onSuccess: (r) => { setResult(r); toast.success(`${r.created} ${tx('leads imported')}`); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not import'))),
  });
  const template = () => downloadCsv('crm-leads-template.csv', [COLUMNS.map((c) => c.label),
    ['Hotel lobby refurbishment', 'Acme Hotels', 'Jane Doe', 'jane@acme.example', '+256 700 000000', 'Kampala', 'Uganda', '25000000', '2026-12-31', 'Website', 'Hotel; Commercial', '', 'Wants marble samples']]);

  const counts = result && {
    ok: result.rows.filter((r) => r.status === 'ok').length,
    duplicate: result.rows.filter((r) => r.status === 'duplicate').length,
    error: result.errors,
  };
  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) reset(); onOpenChange(v); }}>
      <DialogContent className="max-h-[92vh] max-w-3xl overflow-y-auto">
        <DialogHeader><DialogTitle>{tx('Import leads')}</DialogTitle></DialogHeader>
        <div className="space-y-3 text-sm">
          <p className="text-muted-foreground">
            {tx('Upload a CSV file with a header row. Only "Name" is required; unknown sources and tags are skipped, and a row without a salesperson is assigned automatically.')}{' '}
            <button type="button" className="font-medium text-primary underline underline-offset-2" onClick={template}>{tx('Download a template')}</button>
          </p>
          <label className="flex cursor-pointer items-center gap-3 rounded-lg border border-dashed p-4 hover:bg-muted/40">
            <FileSpreadsheet className="h-6 w-6 text-muted-foreground" />
            <span>{file ?? tx('Choose a .csv file')}</span>
            <input type="file" accept=".csv,text/csv" className="sr-only" onChange={(e) => { void pick(e.target.files?.[0]); e.target.value = ''; }} />
          </label>
          {parsed && (
            <p className="text-xs text-muted-foreground">
              {parsed.rows.length} {tx('rows')} · {tx('columns')}: {parsed.mapped.join(', ')}
              {parsed.ignored.length > 0 && <> · {tx('ignored')}: {parsed.ignored.join(', ')}</>}
            </p>
          )}
          {m.isPending && <p className="text-muted-foreground">{tx('Checking…')}</p>}
          {result && (
            <>
              {result.dryRun ? (
                <div className="flex flex-wrap gap-2">
                  <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium', STATUS_STYLE.ok)}>{counts!.ok} {tx('ready')}</span>
                  <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium', STATUS_STYLE.duplicate)}>{counts!.duplicate} {tx('possible duplicates')}</span>
                  <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium', STATUS_STYLE.error)}>{counts!.error} {tx('with errors')}</span>
                </div>
              ) : (
                <p className="font-medium">{result.created} {tx('created')} · {result.skipped} {tx('skipped')} · {result.errors} {tx('errors')}</p>
              )}
              <div className="max-h-72 overflow-y-auto rounded-lg border">
                <table className="w-full text-xs">
                  <thead className="sticky top-0 bg-muted text-left"><tr><th className="px-2 py-1.5">#</th><th className="px-2 py-1.5">{tx('Name')}</th><th className="px-2 py-1.5">{tx('Result')}</th><th className="px-2 py-1.5">{tx('Details')}</th></tr></thead>
                  <tbody>
                    {result.rows.filter((r) => r.status !== 'ok' && r.status !== 'created' || (r.warnings?.length ?? 0) > 0).slice(0, 200).map((r) => (
                      <tr key={r.row} className="border-t">
                        <td className="px-2 py-1 tabular-nums">{r.row + 1}</td>
                        <td className="px-2 py-1">{r.name ?? '—'}</td>
                        <td className="px-2 py-1"><span className={cn('rounded px-1.5 py-0.5', STATUS_STYLE[r.status])}>{r.status}</span></td>
                        <td className="px-2 py-1 text-muted-foreground">{[r.error, r.duplicates ? `${r.duplicates} ${tx('match(es)')}` : null, ...(r.warnings ?? [])].filter(Boolean).join(' · ')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {result.rows.every((r) => (r.status === 'ok' || r.status === 'created') && !(r.warnings?.length)) && <p className="p-3 text-center text-muted-foreground">{tx('Every row is fine.')}</p>}
              </div>
              {result.dryRun && (
                <label className="flex items-center gap-2"><input type="checkbox" checked={skipDuplicates} onChange={(e) => setSkipDuplicates(e.target.checked)} className="h-4 w-4 accent-primary" />{tx('Skip rows that look like existing leads, opportunities or customers')}</label>
              )}
            </>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{result && !result.dryRun ? tx('Close') : tx('Cancel')}</Button>
          {result?.dryRun && (
            <Button disabled={m.isPending || counts!.ok + (skipDuplicates ? 0 : counts!.duplicate) === 0} onClick={commit}>
              {tx('Import')} {counts!.ok + (skipDuplicates ? 0 : counts!.duplicate)} {tx('leads')}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

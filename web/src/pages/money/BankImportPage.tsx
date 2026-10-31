import { useState } from 'react';
import { FileUp } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { ListPageHeader } from '@/components/list';
import { date, money } from '@/lib/format';
import { apiMessage, notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { Field, OptionalSelect } from '@/pages/fleet/fleet-ui';
import { useBankAccountOptions, useBankFileImport, type BankFileResult } from '@/features/invoicing/interchange-api';

const FORMATS = [
  { value: 'auto', label: tx('Detect from the file') },
  { value: 'csv', label: 'CSV' },
  { value: 'mt940', label: 'MT940' },
  { value: 'camt053', label: 'camt.053' },
];

/** Upload a bank statement (INT-3): preview first, then import; importing the same file twice adds nothing. */
export function BankImportPage() {
  const accounts = useBankAccountOptions();
  const run = useBankFileImport();
  const [bankAccountId, setBankAccountId] = useState('');
  const [format, setFormat] = useState('auto');
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<BankFileResult | null>(null);
  const ready = !!bankAccountId && !!file && !run.isPending;

  const go = async (dryRun: boolean) => {
    if (!file) return;
    try {
      const r = await run.mutateAsync({ bankAccountId, format, dryRun, file });
      setResult(r);
      if (!dryRun) notify.success(tx('{{imported}} lines imported, {{skipped}} already there', { imported: r.imported ?? 0, skipped: r.skipped ?? 0 }));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not read the statement')));
    }
  };

  return (
    <div className="space-y-4">
      <ListPageHeader icon={FileUp} title={tx('Import Bank Statement')} description={tx('CSV, MT940 or camt.053 files from your bank, ready for reconciliation.')} />
      <Card>
        <CardContent className="grid gap-3 pt-4 md:grid-cols-3">
          <Field label={tx('Bank account *')}>
            <OptionalSelect value={bankAccountId} onChange={setBankAccountId} placeholder={tx('Choose an account')}
              options={(accounts.data ?? []).map((a) => ({ value: a.id, label: a.bankName ? `${a.name} · ${a.bankName}` : a.name }))} />
          </Field>
          <Field label={tx('Format')}>
            <OptionalSelect value={format} onChange={(v) => setFormat(v || 'auto')} placeholder={tx('Detect from the file')} options={FORMATS} />
          </Field>
          <Field label={tx('Statement file *')}>
            <Input type="file" accept=".csv,.txt,.sta,.mt940,.xml" onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }} />
          </Field>
          <div className="flex gap-2 md:col-span-3">
            <Button variant="outline" disabled={!ready} onClick={() => go(true)}>{tx('Preview')}</Button>
            <Button disabled={!ready} onClick={() => go(false)}>{tx('Import')}</Button>
          </div>
        </CardContent>
      </Card>
      {result && (
        <Card>
          <CardContent className="space-y-2 pt-4 text-sm">
            <p>{tx('{{lines}} lines ({{format}}): {{in}} in, {{out}} out', { lines: result.lines, format: result.format, in: money(result.moneyIn), out: money(result.moneyOut) })}</p>
            {result.warnings.map((w) => <p key={w} className="text-amber-700 dark:text-amber-400">{w}</p>)}
            {result.preview?.map((l) => (
              <div key={l.externalRef} className="flex justify-between gap-2 border-t pt-1">
                <span>{date(l.postedAt)} · {l.description}</span>
                <span className="tabular-nums whitespace-nowrap">{money(l.amount)}</span>
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

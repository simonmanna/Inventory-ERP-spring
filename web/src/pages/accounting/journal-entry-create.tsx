import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useFieldArray, useForm } from 'react-hook-form';
import { Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { fromMinorUnits, money, toMinorUnits } from '@/lib/format';
import { localDay } from '@/lib/dates';
import { apiMessage } from '@/lib/notify';
import {
  allowsManualPosting, isPostingAccount, useAccounts, useBaseCurrencyDecimals, useCreateJournalEntry, useJournals,
} from '@/features/accounting/api';
import { tx } from '@/lib/i18n/tx';

const selectClass =
  'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring';

/** Amounts are kept as the typed decimal text and summed as integer minor units. */
interface LineForm {
  accountId: string;
  debit: string;
  credit: string;
  description?: string;
}
interface FormValues {
  journalCode: string;
  date: string;
  description?: string;
  lines: LineForm[];
}

const emptyLine = (): LineForm => ({ accountId: '', debit: '', credit: '' });

export function JournalEntryCreatePage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const journals = useJournals();
  const accounts = useAccounts();
  const createEntry = useCreateJournalEntry();
  const decimals = useBaseCurrencyDecimals();
  const [error, setError] = useState<string | null>(null);

  const { register, control, handleSubmit, watch, setValue } = useForm<FormValues>({
    defaultValues: {
      // Linked from a journal's "New entry" button.
      journalCode: searchParams.get('journalCode') ?? '',
      date: localDay(),
      description: '',
      lines: [emptyLine(), emptyLine()],
    },
  });
  const { fields, append, remove } = useFieldArray({ control, name: 'lines' });
  const lines = watch('lines');
  const journalCode = watch('journalCode');

  const journalList = journals.data?.data ?? [];
  // A journalCode from the URL that is not in the loaded list still gets an option so it stays selected.
  const unknownJournal = !!journalCode && !!journals.data && !journalList.some((j) => j.code === journalCode);

  // The server refuses group, inactive, deprecated and no-manual-posting (control) accounts here.
  const postable = (accounts.data?.data ?? []).filter((a) => isPostingAccount(a) && allowsManualPosting(a));

  const minor = (v: string | undefined) => toMinorUnits(v, decimals);
  const fmt = (m: number) => money(fromMinorUnits(m, decimals), undefined, decimals);
  const totalDebit = (lines ?? []).reduce((s, l) => s + minor(l.debit), 0);
  const totalCredit = (lines ?? []).reduce((s, l) => s + minor(l.credit), 0);
  const diff = totalDebit - totalCredit;
  const balanced = totalDebit > 0 && diff === 0;
  const isAmountLine = (l: LineForm) => !!l.accountId && (minor(l.debit) > 0 || minor(l.credit) > 0);
  const validLines = (lines ?? []).filter(isAmountLine);
  const badLine = (lines ?? []).some(
    (l) => minor(l.debit) < 0 || minor(l.credit) < 0 || (minor(l.debit) > 0 && minor(l.credit) > 0),
  );
  const canSubmit = Boolean(journalCode) && balanced && !badLine && validLines.length >= 2 && !createEntry.isPending;

  const onSubmit = handleSubmit(async (values) => {
    if (!canSubmit) return;
    setError(null);
    try {
      const entry = await createEntry.mutateAsync({
        journalCode: values.journalCode,
        date: values.date,
        description: values.description || undefined,
        lines: values.lines.filter(isAmountLine).map((l) => ({
          accountId: l.accountId,
          debit: minor(l.debit) > 0 ? Number(fromMinorUnits(minor(l.debit), decimals)) : undefined,
          credit: minor(l.credit) > 0 ? Number(fromMinorUnits(minor(l.credit), decimals)) : undefined,
          description: l.description || undefined,
        })),
      });
      // The entry is a DRAFT; it is posted (by a different user) from its detail page.
      navigate(`/journal-entries/${entry.id}`);
    } catch (e) {
      setError(apiMessage(e, tx('Failed to save the draft')));
    }
  });

  /** A line carries a debit OR a credit: typing into one side clears the other. */
  const amountField = (index: number, side: 'debit' | 'credit') => {
    const other = side === 'debit' ? 'credit' : 'debit';
    return register(`lines.${index}.${side}`, {
      onChange: (e: React.ChangeEvent<HTMLInputElement>) => {
        if (e.target.value !== '') setValue(`lines.${index}.${other}`, '');
      },
    });
  };

  return (
    <form onSubmit={onSubmit} className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{tx('New Journal Entry')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Saved as a draft. Another user with posting rights posts it from the entry page.')}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <Badge variant={balanced ? 'default' : 'secondary'}>
            {balanced ? tx('Balanced') : tx('Diff {{v1}}', { v1: fmt(diff) })}
          </Badge>
          <Button type="submit" disabled={!canSubmit}>
            {createEntry.isPending ? tx('Saving...') : tx('Save draft')}
          </Button>
        </div>
      </div>

      {error && <p className="text-sm text-destructive">{error}</p>}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tx('Header')}</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-3">
          <div className="space-y-2">
            <Label>{tx('Journal')}</Label>
            <select className={selectClass} {...register('journalCode', { required: true })}>
              <option value="">{tx('Select journal')}</option>
              {unknownJournal && <option value={journalCode}>{journalCode}</option>}
              {journalList.map((j) => (
                <option key={j.id} value={j.code}>
                  {j.code} — {j.name}
                </option>
              ))}
            </select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="date">{tx('Date')}</Label>
            <Input id="date" type="date" {...register('date', { required: true })} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="description">{tx('Description')}</Label>
            <Input id="description" {...register('description')} />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">{tx('Lines')}</CardTitle>
          <Button type="button" variant="outline" size="sm" onClick={() => append(emptyLine())}>
            <Plus className="h-4 w-4" /> {tx('Add line')}
          </Button>
        </CardHeader>
        <CardContent className="space-y-3">
          {fields.map((field, index) => (
            <div key={field.id} className="grid grid-cols-12 items-end gap-2">
              <div className="col-span-5">
                <Label className="text-xs">{tx('Account')}</Label>
                <select className={selectClass} {...register(`lines.${index}.accountId`)}>
                  <option value="">{tx('Select account')}</option>
                  {postable.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.code} — {a.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="col-span-3">
                <Label className="text-xs">{tx('Description')}</Label>
                <Input {...register(`lines.${index}.description`)} />
              </div>
              <div className="col-span-1">
                <Label className="text-xs">{tx('Debit')}</Label>
                <Input type="number" min={0} step="any" inputMode="decimal" {...amountField(index, 'debit')} />
              </div>
              <div className="col-span-2">
                <Label className="text-xs">{tx('Credit')}</Label>
                <Input type="number" min={0} step="any" inputMode="decimal" {...amountField(index, 'credit')} />
              </div>
              <div className="col-span-1">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  onClick={() => remove(index)}
                  disabled={fields.length <= 2}
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            </div>
          ))}

          {badLine && (
            <p className="text-sm text-destructive">{tx('Each line needs a positive debit or a positive credit, not both.')}</p>
          )}

          <div className="flex justify-end gap-8 border-t pt-3 text-sm">
            <div className="flex gap-2">
              <span className="text-muted-foreground">{tx('Total debit')}</span>
              <span className="w-24 text-right font-medium">{fmt(totalDebit)}</span>
            </div>
            <div className="flex gap-2">
              <span className="text-muted-foreground">{tx('Total credit')}</span>
              <span className="w-24 text-right font-medium">{fmt(totalCredit)}</span>
            </div>
          </div>
        </CardContent>
      </Card>
    </form>
  );
}

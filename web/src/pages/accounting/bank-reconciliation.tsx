import { useState, type ChangeEvent } from 'react';
import { Landmark, Lock, Upload, Wand2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination,
  FilterSelect,
  ListCard,
  ListPageHeader,
  ListToolbar,
  SearchInput,
  useListState,
} from '@/components/list';
import { useCashAccounts } from '@/features/accounting/api';
import {
  parseStatementCsv,
  useAutoMatch,
  useBankReconciliationSummary,
  useExcludeLine,
  useImportStatement,
  useLockStatement,
  useMatchLine,
  usePaymentCandidates,
  useReconciliationRuns,
  useReconciliationStatement,
  useStatementLines,
  useUnmatchLine,
  type StatementLineRow,
} from '@/features/accounting/bank-reconciliation';
import { date, dateTime, money } from '@/lib/format';
import { apiMessage, notify } from '@/lib/notify';
import { localDay } from '@/lib/dates';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const STATUS_VARIANT: Record<StatementLineRow['status'], 'default' | 'secondary' | 'outline'> = {
  matched: 'default',
  unmatched: 'secondary',
  excluded: 'outline',
};

function Tile({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardDescription>{label}</CardDescription>
        <CardTitle className="text-xl">{value}</CardTitle>
      </CardHeader>
      <CardContent className="text-xs text-muted-foreground">{hint ?? ' '}</CardContent>
    </Card>
  );
}

/**
 * Statement sign-off: the bank's closing balance on a date, proved against the
 * ledger (outstanding book items, unrecorded statement lines), then locked. Lock
 * is offered only when the server reports a zero difference.
 */
function StatementSignOff({ bankAccountId, canUpdate }: { bankAccountId: string; canUpdate: boolean }) {
  const [asOf, setAsOf] = useState(localDay());
  const [closing, setClosing] = useState('');
  const [notes, setNotes] = useState('');
  const debouncedClosing = useDebouncedValue(closing, 400);
  const statement = useReconciliationStatement({ bankAccountId, asOf, closingBalance: debouncedClosing });
  // Lock only what is on screen: the proof must be for the closing balance as typed.
  const proofCurrent = debouncedClosing === closing && !statement.isFetching;
  const lock = useLockStatement();
  const s = statement.data;

  const submitLock = async () => {
    if (!s?.reconciled || !proofCurrent) return;
    if (!confirm(tx('Lock this reconciliation through {{date}}? Statement lines on or before it are frozen.', { date: asOf }))) return;
    try {
      await lock.mutateAsync({ bankAccountId, asOf, closingBalance: Number(closing), notes: notes || undefined });
      notify.success(tx('Reconciliation locked through {{date}}', { date: asOf }));
      setNotes('');
    } catch (error) {
      const status = (error as { response?: { status?: number } })?.response?.status;
      // 403 / 5xx are already toasted by the HTTP client.
      if (status !== 403 && !(status && status >= 500)) {
        notify.error(apiMessage(error, tx('Could not lock the reconciliation')));
      }
    }
  };

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-base">{tx('Statement sign-off')}</CardTitle>
        <CardDescription>
          {tx('Enter the closing balance from the bank statement. It must equal the ledger once outstanding items are accounted for.')}
          {s?.lockedThrough && <> {tx('Locked through {{date}}.', { date: date(s.lockedThrough) })}</>}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-end gap-3">
          <div className="space-y-1">
            <Label>{tx('Statement date')}</Label>
            <Input type="date" className="h-9" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label>{tx('Closing balance')}</Label>
            <Input type="number" step="any" className="h-9 w-40" value={closing} onChange={(e) => setClosing(e.target.value)} />
          </div>
          {canUpdate && (
            <div className="min-w-[220px] flex-1 space-y-1">
              <Label>{tx('Note')}</Label>
              <Input className="h-9" value={notes} onChange={(e) => setNotes(e.target.value)} />
            </div>
          )}
          {canUpdate && (
            <Button onClick={() => void submitLock()} disabled={!s?.reconciled || !proofCurrent || lock.isPending}>
              <Lock className="mr-2 h-4 w-4" />{lock.isPending ? tx('Locking…') : tx('Lock')}
            </Button>
          )}
        </div>
        {statement.isError && (
          <p className="text-sm text-destructive">{apiMessage(statement.error, tx('Could not compute the statement'))}</p>
        )}
        {s && (
          <div className="grid gap-2 text-sm sm:grid-cols-2 lg:grid-cols-3">
            <div className="flex justify-between rounded border px-3 py-2">
              <span className="text-muted-foreground">{tx('Statement closing balance')}</span>
              <span className="font-medium">{money(s.closingBalance)}</span>
            </div>
            <div className="flex justify-between rounded border px-3 py-2">
              <span className="text-muted-foreground">{tx('+ Outstanding book items')}</span>
              <span className="font-medium">{money(s.outstandingBook)}</span>
            </div>
            <div className="flex justify-between rounded border px-3 py-2">
              <span className="text-muted-foreground">{tx('Ledger balance')}</span>
              <span className="font-medium">{money(s.ledgerBalance)}</span>
            </div>
            <div className="flex justify-between rounded border px-3 py-2">
              <span className="text-muted-foreground">{tx('+ Unrecorded statement lines')}</span>
              <span className="font-medium">{money(s.unrecordedStatement)}</span>
            </div>
            <div className={`flex justify-between rounded border px-3 py-2 ${s.reconciled ? 'border-emerald-300 bg-emerald-50' : 'border-amber-300 bg-amber-50'}`}>
              <span className="font-medium">{tx('Difference')}</span>
              <span className="font-semibold">{money(s.difference)}</span>
            </div>
            <div className="flex items-center rounded border px-3 py-2">
              <Badge variant={s.reconciled ? 'default' : 'secondary'}>
                {s.reconciled ? tx('Reconciled') : tx('Not reconciled')}
              </Badge>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

/** Match imported bank statement lines to posted payments; lines with no payment are excluded with a reason. */
export function BankReconciliationPage() {
  const list = useListState({ account: '', status: '' });
  const accountId = list.filters.account;
  const has = useAuthStore((s) => s.hasPermission);
  const org = useAuthStore((s) => s.organization);
  const canUpdate = has(PERMISSIONS.bankAccount.update);

  const { data: accounts = [] } = useCashAccounts();
  const account = accounts.find((a) => a.id === accountId);
  const summary = useBankReconciliationSummary(accountId);
  const runs = useReconciliationRuns(accountId);
  const lines = useStatementLines({
    bankAccountId: accountId,
    status: list.filters.status || undefined,
    search: list.search || undefined,
    page: list.page,
    pageSize: list.pageSize,
  });

  const importStatement = useImportStatement();
  const autoMatch = useAutoMatch();
  const matchLine = useMatchLine();
  const excludeLine = useExcludeLine();
  const unmatchLine = useUnmatchLine();

  const [importOpen, setImportOpen] = useState(false);
  const [csv, setCsv] = useState('');
  const [tolerance, setTolerance] = useState('3');
  const [matching, setMatching] = useState<StatementLineRow | null>(null);
  const [excluding, setExcluding] = useState<StatementLineRow | null>(null);
  const [excludeNote, setExcludeNote] = useState('');
  const candidates = usePaymentCandidates(matching?.id);
  const parsed = parseStatementCsv(csv);

  const onFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) setCsv(await file.text());
  };

  const submitImport = async () => {
    try {
      const result = await importStatement.mutateAsync({
        bankAccountId: accountId,
        currencyCode: account?.currencyCode ?? account?.baseCurrency ?? org?.currencyCode ?? 'USD',
        lines: parsed.lines,
      });
      notify.success(tx('{{imported}} imported, {{skipped}} already there', {
        imported: result.imported,
        skipped: result.skipped,
      }));
      setImportOpen(false);
      setCsv('');
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not import the statement')));
    }
  };

  const runAutoMatch = async () => {
    try {
      const result = await autoMatch.mutateAsync({
        bankAccountId: accountId,
        dateToleranceDays: Number(tolerance) || 0,
      });
      notify.success(tx('{{matched}} matched, {{unmatched}} left', {
        matched: result.matched,
        unmatched: result.unmatched,
      }));
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not run the match')));
    }
  };

  const pickPayment = async (paymentId: string) => {
    if (!matching) return;
    try {
      await matchLine.mutateAsync({ lineId: matching.id, paymentId });
      setMatching(null);
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not match this line')));
    }
  };

  const submitExclude = async () => {
    if (!excluding) return;
    try {
      await excludeLine.mutateAsync({ lineId: excluding.id, note: excludeNote });
      setExcluding(null);
      setExcludeNote('');
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not exclude this line')));
    }
  };

  const undo = async (lineId: string) => {
    try {
      await unmatchLine.mutateAsync(lineId);
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not undo this line')));
    }
  };

  const columns: Column<StatementLineRow>[] = [
    { key: 'postedAt', header: tx('Date'), render: (l) => date(l.postedAt) },
    { key: 'externalRef', header: tx('Reference'), render: (l) => l.externalRef ?? '-' },
    { key: 'description', header: tx('Description') },
    { key: 'amount', header: tx('Amount'), className: 'text-right', render: (l) => money(l.amount) },
    {
      key: 'status',
      header: tx('Status'),
      render: (l) => (
        <div className="space-y-0.5">
          <Badge variant={STATUS_VARIANT[l.status]}>
            {l.status === 'matched' ? tx('Matched') : l.status === 'excluded' ? tx('Excluded') : tx('Unmatched')}
          </Badge>
          {(l.matchedPaymentNumber || l.note) && (
            <p className="text-xs text-muted-foreground">{l.matchedPaymentNumber ?? l.note}</p>
          )}
        </div>
      ),
    },
    {
      key: 'actions',
      header: '',
      render: (l) => {
        if (!canUpdate) return null;
        if (l.status !== 'unmatched') {
          return (
            <Button variant="ghost" size="sm" onClick={() => void undo(l.id)} disabled={unmatchLine.isPending}>
              {tx('Undo')}
            </Button>
          );
        }
        return (
          <div className="flex gap-1">
            <Button variant="ghost" size="sm" onClick={() => setMatching(l)}>{tx('Match')}</Button>
            <Button variant="ghost" size="sm" onClick={() => setExcluding(l)}>{tx('Exclude')}</Button>
          </div>
        );
      },
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('Bank Reconciliation')}
        description={tx('Import a bank statement and match each line to a posted payment.')}
        icon={Landmark}
        actions={
          canUpdate && accountId ? (
            <>
              <Button variant="outline" onClick={() => setImportOpen(true)}>
                <Upload className="mr-2 h-4 w-4" />{tx('Import statement')}
              </Button>
              <Input
                type="number"
                min="0"
                aria-label={tx('Date tolerance in days')}
                title={tx('Date tolerance in days')}
                className="h-9 w-20"
                value={tolerance}
                onChange={(e) => setTolerance(e.target.value)}
              />
              <Button onClick={() => void runAutoMatch()} disabled={autoMatch.isPending}>
                <Wand2 className="mr-2 h-4 w-4" />{tx('Auto-match')}
              </Button>
            </>
          ) : undefined
        }
      />

      <ListToolbar>
        <FilterSelect
          value={accountId}
          onChange={(v) => list.setFilter('account', v)}
          allLabel={tx('Select account')}
          options={accounts.map((a) => ({ value: a.id, label: `${a.code} ${a.name}` }))}
          className="w-[260px]"
        />
        <FilterSelect
          value={list.filters.status}
          onChange={(v) => list.setFilter('status', v)}
          allLabel={tx('All statuses')}
          options={[
            { value: 'unmatched', label: tx('Unmatched') },
            { value: 'matched', label: tx('Matched') },
            { value: 'excluded', label: tx('Excluded') },
          ]}
        />
        <SearchInput
          value={list.searchInput}
          onChange={list.setSearchInput}
          placeholder={tx('Search by number or reference...')}
        />
      </ListToolbar>

      {!accountId ? (
        <Card>
          <CardContent className="p-8 text-center text-sm text-muted-foreground">
            {tx('Choose a bank account to start.')}
          </CardContent>
        </Card>
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Tile label={tx('Book balance')} value={money(summary.data?.bookBalance)} />
            <Tile label={tx('Statement total')} value={money(summary.data?.statementTotal)} />
            <Tile
              label={tx('Unmatched lines')}
              value={money(summary.data?.unmatchedTotal)}
              hint={tx('{{count}} line(s)', { count: summary.data?.unmatchedCount ?? 0 })}
            />
            <Tile
              label={tx('Payments not on a statement')}
              value={`${money(summary.data?.outstandingInbound)} / ${money(summary.data?.outstandingOutbound)}`}
              hint={tx('{{count}} payment(s), in / out', { count: summary.data?.outstandingPayments ?? 0 })}
            />
          </div>

          <StatementSignOff bankAccountId={accountId} canUpdate={canUpdate} />

          {lines.isError && <p className="text-sm text-destructive">{tx('Could not load the statement lines.')}</p>}

          <ListCard>
            <DataTable
              columns={columns}
              data={lines.data?.data ?? []}
              loading={lines.isLoading}
              getRowId={(l) => l.id}
              emptyMessage={tx('No statement lines. Import a statement to begin.')}
            />
            {lines.data?.meta && (
              <DataTablePagination
                page={lines.data.meta.page}
                pageSize={lines.data.meta.pageSize}
                total={lines.data.meta.total}
                totalPages={lines.data.meta.totalPages}
                onPageChange={list.setPage}
                onPageSizeChange={list.setPageSize}
                noun={tx('line')}
              />
            )}
          </ListCard>

          {(runs.data?.length ?? 0) > 0 && (
            <Card>
              <CardHeader>
                <CardTitle className="text-base">{tx('Recent matching runs')}</CardTitle>
              </CardHeader>
              <CardContent className="space-y-1 text-sm">
                {runs.data?.map((r) => (
                  <div key={r.id} className="flex justify-between">
                    <span>{dateTime(r.startedAt)}</span>
                    <span className="text-muted-foreground">
                      {tx('{{matched}} matched, {{unmatched}} left', { matched: r.matched, unmatched: r.unmatched })}
                    </span>
                  </div>
                ))}
              </CardContent>
            </Card>
          )}
        </>
      )}

      <Dialog open={importOpen} onOpenChange={setImportOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Import statement')}</DialogTitle>
            <DialogDescription>
              {tx('CSV columns: date (YYYY-MM-DD), reference, description, amount. Money out is negative.')}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-2">
              <Label htmlFor="statementFile">{tx('File')}</Label>
              <Input id="statementFile" type="file" accept=".csv,text/csv" onChange={(e) => void onFile(e)} />
            </div>
            <Textarea rows={8} value={csv} onChange={(e) => setCsv(e.target.value)} />
            <p className="text-sm text-muted-foreground">
              {tx('{{count}} line(s) ready', { count: parsed.lines.length })}
            </p>
            {parsed.errors.length > 0 && (
              <p className="text-sm text-destructive">
                {tx('Rows skipped, check them: {{rows}}', { rows: parsed.errors.join(', ') })}
              </p>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setImportOpen(false)}>{tx('Cancel')}</Button>
            <Button
              onClick={() => void submitImport()}
              disabled={parsed.lines.length === 0 || importStatement.isPending}
            >
              {tx('Import')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!matching} onOpenChange={(open) => !open && setMatching(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Match line')}</DialogTitle>
            <DialogDescription>
              {matching ? `${date(matching.postedAt)} · ${matching.description} · ${money(matching.amount)}` : ''}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            {candidates.isLoading && <p className="text-sm text-muted-foreground">{tx('Loading...')}</p>}
            {candidates.data?.length === 0 && (
              <p className="text-sm text-muted-foreground">{tx('No unmatched payment of this amount on this account.')}</p>
            )}
            {candidates.data?.map((c) => (
              <div key={c.id} className="flex items-center justify-between gap-3 rounded-md border p-2 text-sm">
                <div>
                  <p className="font-medium">{c.paymentNumber}</p>
                  <p className="text-xs text-muted-foreground">
                    {date(c.paymentDate)} · {c.partnerName ?? '-'}{c.reference ? ` · ${c.reference}` : ''}
                  </p>
                </div>
                <Button size="sm" onClick={() => void pickPayment(c.id)} disabled={matchLine.isPending}>
                  {tx('Match')}
                </Button>
              </div>
            ))}
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={!!excluding} onOpenChange={(open) => !open && setExcluding(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('Exclude line')}</DialogTitle>
            <DialogDescription>
              {tx('Use this for bank charges or transfers already in the books through a journal.')}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="excludeNote">{tx('Reason')}</Label>
            <Input id="excludeNote" value={excludeNote} onChange={(e) => setExcludeNote(e.target.value)} />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setExcluding(null)}>{tx('Cancel')}</Button>
            <Button onClick={() => void submitExclude()} disabled={!excludeNote.trim() || excludeLine.isPending}>
              {tx('Exclude')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

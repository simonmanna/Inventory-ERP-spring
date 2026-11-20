import { Fragment, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ListPageHeader } from '@/components/list';
import {
  useFxReport,
  useFxReportLines,
  type FxReportGroupBy,
  type FxReportLine,
  type FxReportLineFilter,
  type FxReportRow,
} from '@/features/accounting/fx-report-api';
import { date, money } from '@/lib/format';
import { firstOfMonth, localDay } from '@/lib/dates';
import { tx } from '@/lib/i18n/tx';

const GROUPS: { value: FxReportGroupBy; label: string }[] = [
  { value: 'partner', label: tx('Partner') },
  { value: 'account', label: tx('Account') },
  { value: 'currency', label: tx('Currency') },
  { value: 'origin', label: tx('Origin') },
  { value: 'document', label: tx('Document') },
];

/** Gain (positive) in green, loss (negative) in red. */
function Amount({ value, className = '' }: { value: string | number; className?: string }) {
  const n = Number(value);
  const tone = n > 0 ? 'text-emerald-700' : n < 0 ? 'text-red-700' : '';
  return <span className={`tabular-nums ${tone} ${className}`}>{money(value)}</span>;
}

/**
 * The drill-down filter for a report row. Partner, account and currency rows filter on the server; origin and document
 * rows (and a row without a key) are filtered here on the lines' source.
 */
function serverFilter(groupBy: FxReportGroupBy, row: FxReportRow, from: string, to: string): FxReportLineFilter {
  const f: FxReportLineFilter = { from, to };
  if (row.key) {
    if (groupBy === 'partner') f.partnerId = row.key;
    else if (groupBy === 'account') f.accountId = row.key;
    else if (groupBy === 'currency') f.currency = row.key;
  }
  return f;
}

function matchesRow(groupBy: FxReportGroupBy, row: FxReportRow, l: FxReportLine): boolean {
  switch (groupBy) {
    case 'partner': return row.key ? l.partnerId === row.key : !l.partnerId;
    case 'account': return row.key ? l.accountId === row.key : !l.accountId;
    case 'currency': return row.key ? l.currency === row.key : !l.currency;
    case 'origin': return (l.sourceType ?? null) === row.key;
    case 'document': return row.key === `${l.sourceType ?? ''}:${l.sourceId ?? ''}` || (!row.key && !l.sourceId);
    default: return true;
  }
}

/** FX gain / loss report (FX-7, ADR-S082): realised and unrealised exchange differences grouped, with drill-down. */
export default function FxReportPage() {
  const [from, setFrom] = useState(firstOfMonth());
  const [to, setTo] = useState(localDay());
  const [groupBy, setGroupBy] = useState<FxReportGroupBy>('partner');
  const report = useFxReport(from, to, groupBy);
  const [selected, setSelected] = useState<FxReportRow | null>(null);
  const fc = report.data?.functionalCurrency ?? '';

  return (
    <div className="space-y-6">
      <ListPageHeader
        title={tx('FX gain / loss report')}
        description={tx('Realised and unrealised exchange differences in the functional currency. Positive is a gain, negative a loss.')}
      />

      <div className="flex flex-wrap items-end gap-3 rounded-lg border p-3">
        <div className="space-y-1">
          <Label htmlFor="fxr-from">{tx('From')}</Label>
          <Input id="fxr-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="h-9" />
        </div>
        <div className="space-y-1">
          <Label htmlFor="fxr-to">{tx('To')}</Label>
          <Input id="fxr-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} className="h-9" />
        </div>
        <div className="space-y-1">
          <Label htmlFor="fxr-group">{tx('Group by')}</Label>
          <select
            id="fxr-group"
            className="h-9 rounded-md border border-input bg-background px-3 text-sm"
            value={groupBy}
            onChange={(e) => setGroupBy(e.target.value as FxReportGroupBy)}
          >
            {GROUPS.map((g) => <option key={g.value} value={g.value}>{g.label}</option>)}
          </select>
        </div>
      </div>

      <div className="grid gap-3 sm:grid-cols-3">
        {([
          ['realised', tx('Realised')],
          ['unrealised', tx('Unrealised')],
          ['total', tx('Total')],
        ] as const).map(([k, label]) => (
          <Card key={k}>
            <CardHeader className="pb-1"><CardTitle className="text-sm text-muted-foreground">{label}</CardTitle></CardHeader>
            <CardContent className="pt-0 text-xl font-semibold">
              {report.data ? <><Amount value={report.data[k]} /> <span className="text-sm font-normal text-muted-foreground">{fc}</span></> : <Skeleton className="h-6 w-24" />}
            </CardContent>
          </Card>
        ))}
      </div>

      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{GROUPS.find((g) => g.value === groupBy)?.label}</TableHead>
                <TableHead className="text-right">{tx('Realised')}</TableHead>
                <TableHead className="text-right">{tx('Unrealised')}</TableHead>
                <TableHead className="text-right">{tx('Total')}</TableHead>
                <TableHead className="text-right">{tx('Entries')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {report.isLoading ? (
                <TableRow><TableCell colSpan={5}><Skeleton className="h-4 w-full" /></TableCell></TableRow>
              ) : report.isError ? (
                <TableRow><TableCell colSpan={5} className="text-sm text-destructive">{tx('Could not load the report.')}</TableCell></TableRow>
              ) : (report.data?.rows ?? []).length === 0 ? (
                <TableRow><TableCell colSpan={5} className="h-16 text-center text-muted-foreground">{tx('No exchange differences in this period.')}</TableCell></TableRow>
              ) : (
                report.data!.rows.map((r) => (
                  <TableRow key={r.key ?? '(none)'} className="cursor-pointer hover:bg-muted/40" onClick={() => setSelected(r)}>
                    <TableCell className="font-medium text-primary">{r.label}</TableCell>
                    <TableCell className="text-right"><Amount value={r.realised} /></TableCell>
                    <TableCell className="text-right"><Amount value={r.unrealised} /></TableCell>
                    <TableCell className="text-right font-medium"><Amount value={r.total} /></TableCell>
                    <TableCell className="text-right tabular-nums">{r.entries}</TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {selected && (
        <DrillDown row={selected} groupBy={groupBy} from={from} to={to} functionalCurrency={fc} onClose={() => setSelected(null)} />
      )}
    </div>
  );
}

function DrillDown({ row, groupBy, from, to, functionalCurrency, onClose }: {
  row: FxReportRow; groupBy: FxReportGroupBy; from: string; to: string; functionalCurrency: string; onClose: () => void;
}) {
  const [kind, setKind] = useState<'' | 'realised' | 'unrealised'>('');
  const filter = useMemo(
    () => ({ ...serverFilter(groupBy, row, from, to), ...(kind ? { kind } : {}) }),
    [groupBy, row, from, to, kind],
  );
  const lines = useFxReportLines(filter);
  const rows = (lines.data ?? []).filter((l) => matchesRow(groupBy, row, l));

  return (
    <Dialog open onOpenChange={(v) => { if (!v) onClose(); }}>
      <DialogContent className="max-w-5xl">
        <DialogHeader>
          <DialogTitle>{row.label}</DialogTitle>
          <DialogDescription>
            {tx('FX journal lines from {{v1}} to {{v2}} ({{v3}}).', { v1: date(from), v2: date(to), v3: functionalCurrency })}
          </DialogDescription>
        </DialogHeader>
        <div className="flex items-center gap-2">
          <Label htmlFor="fxr-kind" className="text-xs text-muted-foreground">{tx('Kind')}</Label>
          <select id="fxr-kind" className="h-8 rounded-md border border-input bg-background px-2 text-xs" value={kind}
            onChange={(e) => setKind(e.target.value as '' | 'realised' | 'unrealised')}>
            <option value="">{tx('All')}</option>
            <option value="realised">{tx('Realised')}</option>
            <option value="unrealised">{tx('Unrealised')}</option>
          </select>
        </div>
        <div className="max-h-[60vh] overflow-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Entry')}</TableHead>
                <TableHead>{tx('Date')}</TableHead>
                <TableHead>{tx('Kind')}</TableHead>
                <TableHead>{tx('Source')}</TableHead>
                <TableHead>{tx('Partner')}</TableHead>
                <TableHead>{tx('Account')}</TableHead>
                <TableHead>{tx('Currency')}</TableHead>
                <TableHead className="text-right">{tx('Gain / loss')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {lines.isLoading ? (
                <TableRow><TableCell colSpan={8}><Skeleton className="h-4 w-full" /></TableCell></TableRow>
              ) : lines.isError ? (
                <TableRow><TableCell colSpan={8} className="text-sm text-destructive">{tx('Could not load the lines.')}</TableCell></TableRow>
              ) : rows.length === 0 ? (
                <TableRow><TableCell colSpan={8} className="h-16 text-center text-muted-foreground">{tx('No lines.')}</TableCell></TableRow>
              ) : (
                rows.map((l, i) => (
                  <Fragment key={`${l.journalEntryId}-${l.accountId ?? ''}-${i}`}>
                    <TableRow>
                      <TableCell>
                        <Link to={`/journal-entries/${l.journalEntryId}`} className="font-mono text-xs text-primary hover:underline">{l.entryNumber}</Link>
                      </TableCell>
                      <TableCell className="text-xs">{date(l.postingDate)}</TableCell>
                      <TableCell className="text-xs">{l.kind === 'realised' ? tx('Realised') : tx('Unrealised')}</TableCell>
                      <TableCell className="text-xs" title={l.description ?? undefined}>{l.sourceType ?? '—'}</TableCell>
                      <TableCell className="text-xs">{l.partnerName ?? '—'}</TableCell>
                      <TableCell className="text-xs">{l.accountCode ? `${l.accountCode} · ${l.accountName ?? ''}` : '—'}</TableCell>
                      <TableCell className="text-xs">{l.currency ?? '—'}</TableCell>
                      <TableCell className="text-right"><Amount value={l.gain} /></TableCell>
                    </TableRow>
                    {(l.allocations ?? []).map((a) => (
                      <TableRow key={`${l.journalEntryId}-${a.documentId}`} className="bg-muted/30">
                        <TableCell />
                        <TableCell colSpan={4} className="text-xs text-muted-foreground">
                          {tx('Allocated to {{v1}}: {{v2}} at {{v3}} (carrying {{v4}})', {
                            v1: a.documentNumber ?? a.documentId.slice(0, 8),
                            v2: money(a.amount),
                            v3: a.settlementRate,
                            v4: a.carryingRate,
                          })}
                        </TableCell>
                        <TableCell colSpan={2} />
                        <TableCell className="text-right text-xs"><Amount value={a.fxAmount} /></TableCell>
                      </TableRow>
                    ))}
                  </Fragment>
                ))
              )}
            </TableBody>
          </Table>
        </div>
      </DialogContent>
    </Dialog>
  );
}

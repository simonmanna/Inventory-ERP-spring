import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { AlertTriangle, CheckCircle2, CircleSlash } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import {
  useCloseFiscalYear,
  useFiscalYear,
  useReopenFiscalYear,
  useSignYearCloseStep,
  useYearCloseChecklist,
  useYearClosingBalances,
  type CloseChecklistSignOff,
} from '@/features/accounting/api';
import { date, dateTime } from '@/lib/format';
import { apiMessage, notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const LABELS: Record<string, string> = {
  earlier_years_closed: tx('Earlier fiscal years closed'),
  periods_closed: tx('Every period with postings closed'),
  profit_and_loss_closed: tx('Profit and loss closed to retained earnings'),
  trial_balance: tx('Trial balance balances'),
  draft_journals: tx('No draft journal entries in the year'),
  retained_earnings: tx('Retained earnings account mapped'),
  unmatched_bank_lines: tx('No unmatched bank statement lines'),
  stock_count: tx('Year-end stock count done and posted'),
  fixed_assets: tx('Depreciation run and asset register reviewed'),
  accountant_review: tx('Year-end accounts reviewed by the accountant'),
  tax_computation: tx('Annual tax computation prepared'),
};

function SignOffRow({ step, yearId, editable }: { step: CloseChecklistSignOff; yearId: string; editable: boolean }) {
  const sign = useSignYearCloseStep(yearId);
  const [note, setNote] = useState(step.note ?? '');
  const save = async (signed: boolean) => {
    try {
      await sign.mutateAsync({ key: step.key, signed, note: note || undefined });
    } catch (error) {
      notify.error(apiMessage(error, tx('Could not update this step')));
    }
  };
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-b py-2 last:border-0">
      <div className="min-w-[220px]">
        <p className="text-sm font-medium">{LABELS[step.key] ?? step.label}</p>
        {step.signed && (
          <p className="text-xs text-muted-foreground">{step.signedByName ?? '-'} · {dateTime(step.signedAt)}</p>
        )}
      </div>
      {editable ? (
        <div className="flex flex-1 items-center justify-end gap-2">
          <Input
            className="h-8 max-w-xs"
            placeholder={tx('Note (optional)')}
            aria-label={tx('Note (optional)')}
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
          <Button size="sm" variant={step.signed ? 'outline' : 'default'} onClick={() => void save(!step.signed)}
            disabled={sign.isPending}>
            {step.signed ? tx('Remove sign-off') : tx('Sign off')}
          </Button>
        </div>
      ) : (
        <span className="text-sm text-muted-foreground">{step.note ?? ''}</span>
      )}
    </div>
  );
}

/** Year-end close of one fiscal year: periods, live checks, sign-offs, close / reopen and the closing balances. */
export default function FiscalYearClosePage() {
  const { id } = useParams();
  const has = useAuthStore((s) => s.hasPermission);
  const year = useFiscalYear(id);
  const checklist = useYearCloseChecklist(id);
  const closed = year.data?.status === 'closed';
  const balances = useYearClosingBalances(id, closed);
  const close = useCloseFiscalYear();
  const reopen = useReopenFiscalYear();
  const [lockPeriods, setLockPeriods] = useState(true);

  if (year.isLoading || checklist.isLoading) {
    return <div className="text-sm text-muted-foreground">{tx('Loading…')}</div>;
  }
  if (!year.data || !checklist.data || !id) {
    return <div className="text-sm text-destructive">{tx('Could not load the fiscal year.')}</div>;
  }
  const y = year.data;
  const c = checklist.data;
  const editable = y.status === 'open' && has(PERMISSIONS.fiscalPeriod.update);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Year-end close')}: {y.name}</h1>
          <p className="text-sm text-muted-foreground">{date(y.startDate)} – {date(y.endDate)}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant={closed ? 'secondary' : c.readyToClose ? 'default' : 'destructive'}>
            {closed ? tx('Closed') : c.readyToClose ? tx('Ready to close') : tx('Blocked')}
          </Badge>
          {!closed && editable && (
            <>
              <label className="flex items-center gap-1 text-sm">
                <input type="checkbox" checked={lockPeriods} onChange={(e) => setLockPeriods(e.target.checked)} />
                {tx('Lock the periods')}
              </label>
              <Button onClick={() => close.mutate({ id, lockPeriods })} disabled={!c.readyToClose || close.isPending}>
                {tx('Close the year')}
              </Button>
            </>
          )}
          {closed && !y.lockedPeriods && has(PERMISSIONS.fiscalPeriod.reopen) && (
            <Button variant="outline" onClick={() => reopen.mutate({ id })} disabled={reopen.isPending}>
              {tx('Reopen the year')}
            </Button>
          )}
          <Button variant="outline" asChild>
            <Link to="/fiscal-years">{tx('Back')}</Link>
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Periods')}</CardTitle></CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          {y.periods.map((p) => (
            <Link key={p.id} to={`/fiscal-periods/${p.id}/checklist`}>
              <Badge variant={p.status === 'open' ? 'outline' : 'secondary'}>{p.name} · {p.status}</Badge>
            </Link>
          ))}
          {y.periods.length > 0 && y.status === 'open' && (
            <p className="w-full text-xs text-muted-foreground">
              {tx('Close each period that has postings from Fiscal Periods; periods without postings are closed with the year.')}
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Automatic checks')}</CardTitle></CardHeader>
        <CardContent className="divide-y">
          {c.checks.map((k) => {
            const Icon = k.ok ? CheckCircle2 : k.blocking ? CircleSlash : AlertTriangle;
            const tone = k.ok ? 'text-emerald-600' : k.blocking ? 'text-destructive' : 'text-amber-600';
            return (
              <div key={k.key} className="flex items-center justify-between gap-3 py-2 text-sm">
                <span className="flex items-center gap-2">
                  <Icon className={`h-4 w-4 ${tone}`} aria-hidden />
                  {LABELS[k.key] ?? k.label}
                </span>
                <span className="flex items-center gap-2 text-muted-foreground">
                  {!k.ok && <span>{k.count}</span>}
                  {k.blocking && <Badge variant="outline">{tx('Stops the close')}</Badge>}
                </span>
              </div>
            );
          })}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Sign-offs')}</CardTitle></CardHeader>
        <CardContent>
          {c.signOffs.map((s) => <SignOffRow key={s.key} step={s} yearId={id} editable={editable} />)}
        </CardContent>
      </Card>

      {closed && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base">{tx('Closing balances')} · {date(y.endDate)}</CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Account')}</TableHead>
                  <TableHead className="text-right">{tx('Debit')}</TableHead>
                  <TableHead className="text-right">{tx('Credit')}</TableHead>
                  <TableHead className="text-right">{tx('Balance')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(balances.data?.accounts ?? []).map((a) => (
                  <TableRow key={a.accountId}>
                    <TableCell>{a.code} {a.name}</TableCell>
                    <TableCell className="text-right tabular-nums">{a.debit}</TableCell>
                    <TableCell className="text-right tabular-nums">{a.credit}</TableCell>
                    <TableCell className="text-right tabular-nums">{a.balance}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { AlertTriangle, CheckCircle2, CircleSlash } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { useCloseChecklist, useSignCloseStep, type CloseChecklistSignOff } from '@/features/accounting/api';
import { date, dateTime } from '@/lib/format';
import { apiMessage, notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const LABELS: Record<string, string> = {
  register_shifts: tx('Register shifts reconciled'),
  stock_posting: tx('Stock postings finished'),
  inventory_mutations: tx('No pending stock documents'),
  inventory_exceptions: tx('No open inventory exceptions'),
  draft_journals: tx('No draft journal entries'),
  draft_invoices: tx('No draft sales invoices'),
  draft_bills: tx('No draft vendor bills'),
  draft_receipts: tx('No draft goods receipts'),
  unmatched_bank_lines: tx('No unmatched bank statement lines'),
  bank_reconciled: tx('Bank accounts reconciled'),
  accruals_booked: tx('Accruals and prepayments booked'),
  tax_return_reviewed: tx('Tax return reviewed'),
  management_review: tx('Management accounts reviewed'),
};

function SignOffRow({ step, periodId, editable }: { step: CloseChecklistSignOff; periodId: string; editable: boolean }) {
  const sign = useSignCloseStep(periodId);
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
          <p className="text-xs text-muted-foreground">
            {step.signedByName ?? '-'} · {dateTime(step.signedAt)}
          </p>
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
          <Button
            size="sm"
            variant={step.signed ? 'outline' : 'default'}
            onClick={() => void save(!step.signed)}
            disabled={sign.isPending}
          >
            {step.signed ? tx('Remove sign-off') : tx('Sign off')}
          </Button>
        </div>
      ) : (
        <span className="text-sm text-muted-foreground">{step.note ?? ''}</span>
      )}
    </div>
  );
}

/** What stands between a fiscal period and its close: live checks plus a person's sign-offs. */
export function PeriodCloseChecklistPage() {
  const { id } = useParams();
  const { data, isLoading, isError } = useCloseChecklist(id);
  const has = useAuthStore((s) => s.hasPermission);

  if (isLoading) return <div className="text-sm text-muted-foreground">{tx('Loading...')}</div>;
  if (isError || !data || !id) {
    return <div className="text-sm text-destructive">{tx('Could not load the checklist.')}</div>;
  }

  const editable = data.status === 'open' && has(PERMISSIONS.fiscalPeriod.update);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Close checklist')}: {data.name}</h1>
          <p className="text-sm text-muted-foreground">{date(data.startDate)} – {date(data.endDate)}</p>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant={data.readyToClose ? 'default' : 'destructive'}>
            {data.readyToClose ? tx('Ready to close') : tx('Blocked')}
          </Badge>
          <Button variant="outline" asChild>
            <Link to="/fiscal-periods">{tx('Back')}</Link>
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">{tx('Automatic checks')}</CardTitle>
        </CardHeader>
        <CardContent className="divide-y">
          {data.checks.map((c) => {
            const Icon = c.ok ? CheckCircle2 : c.blocking ? CircleSlash : AlertTriangle;
            const tone = c.ok ? 'text-emerald-600' : c.blocking ? 'text-destructive' : 'text-amber-600';
            return (
              <div key={c.key} className="flex items-center justify-between gap-3 py-2 text-sm">
                <span className="flex items-center gap-2">
                  <Icon className={`h-4 w-4 ${tone}`} aria-hidden />
                  {LABELS[c.key] ?? c.label}
                </span>
                <span className="flex items-center gap-2 text-muted-foreground">
                  {!c.ok && <span>{c.count}</span>}
                  {c.blocking && <Badge variant="outline">{tx('Stops the close')}</Badge>}
                </span>
              </div>
            );
          })}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">{tx('Sign-offs')}</CardTitle>
        </CardHeader>
        <CardContent>
          {data.signOffs.map((s) => (
            <SignOffRow key={s.key} step={s} periodId={id} editable={editable} />
          ))}
        </CardContent>
      </Card>
    </div>
  );
}

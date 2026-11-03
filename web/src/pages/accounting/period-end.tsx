import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  useFxRevaluationPreview,
  useRunFxRevaluation,
  useSettleTax,
  useTaxSettlementPreview,
} from '@/features/accounting/period-end-api';
import { useAuthStore } from '@/stores/auth.store';
import { PERMISSIONS } from '@erp/shared';
import { money, date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

/** Whether the signed-in user holds `permission` (re-renders when permissions refresh). */
function useHasPermission(permission: string): boolean {
  return useAuthStore((s) => s.permissions).includes(permission);
}

const today = () => new Date().toISOString().slice(0, 10);
const monthStart = () => {
  const d = new Date();
  return new Date(Date.UTC(d.getFullYear(), d.getMonth(), 1)).toISOString().slice(0, 10);
};

/** Period-end tasks (ACC-2 / ACC-7): unrealised FX revaluation and VAT settlement with its period lock. */
export function PeriodEndPage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Period-end')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Revalue foreign-currency balances and settle VAT before closing the period.')}
        </p>
      </div>
      <FxRevaluationCard />
      <VatSettlementCard />
    </div>
  );
}

function FxRevaluationCard() {
  const [asOf, setAsOf] = useState(today());
  const preview = useFxRevaluationPreview(asOf);
  const run = useRunFxRevaluation();
  const canRun = useHasPermission(PERMISSIONS.currency.update);
  const pending = (preview.data ?? []).filter((l) => Number(l.toPost) !== 0).length;

  return (
    <Card>
      <CardHeader>
        <CardTitle>{tx('FX revaluation')}</CardTitle>
        <CardDescription>
          {tx('Monetary balances in foreign currency at the closing rate; each adjustment reverses automatically the next day.')}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-end gap-3">
          <div className="space-y-1">
            <Label htmlFor="fx-asof" className="text-xs text-muted-foreground">{tx('As of')}</Label>
            <Input id="fx-asof" type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} className="h-9" />
          </div>
          {canRun && (
            <Button onClick={() => run.mutate(asOf)} disabled={run.isPending || pending === 0}>
              {tx('Post revaluation')}
            </Button>
          )}
        </div>
        {preview.isError ? (
          <p className="text-sm text-destructive">{tx('Could not compute the revaluation (check the closing rates).')}</p>
        ) : (
          <div className="rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Account')}</TableHead>
                  <TableHead>{tx('Currency')}</TableHead>
                  <TableHead className="text-right">{tx('Foreign balance')}</TableHead>
                  <TableHead className="text-right">{tx('Carrying value')}</TableHead>
                  <TableHead className="text-right">{tx('Rate')}</TableHead>
                  <TableHead className="text-right">{tx('Revalued')}</TableHead>
                  <TableHead className="text-right">{tx('To post')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {preview.isLoading ? (
                  <TableRow>
                    <TableCell colSpan={7}><Skeleton className="h-4 w-full" /></TableCell>
                  </TableRow>
                ) : (preview.data ?? []).length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={7} className="h-16 text-center text-muted-foreground">
                      {tx('No foreign-currency monetary balances.')}
                    </TableCell>
                  </TableRow>
                ) : (
                  preview.data!.map((l) => (
                    <TableRow key={l.accountId + l.currencyCode}>
                      <TableCell>{l.accountCode}</TableCell>
                      <TableCell>{l.currencyCode}</TableCell>
                      <TableCell className="text-right">{money(l.foreignBalance)}</TableCell>
                      <TableCell className="text-right">{money(l.carryingBase)}</TableCell>
                      <TableCell className="text-right">{String(l.rate)}</TableCell>
                      <TableCell className="text-right">{money(l.revaluedBase)}</TableCell>
                      <TableCell className="text-right font-medium">{money(l.toPost)}</TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function VatSettlementCard() {
  const [from, setFrom] = useState(monthStart());
  const [to, setTo] = useState(today());
  const preview = useTaxSettlementPreview(from, to);
  const settle = useSettleTax();
  const canSettle = useHasPermission(PERMISSIONS.journalEntry.create);
  const p = preview.data;

  return (
    <Card>
      <CardHeader>
        <CardTitle>{tx('VAT settlement')}</CardTitle>
        <CardDescription>
          {tx('Offsets input VAT against output VAT and locks the period: no more tax can post on or before its end.')}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-end gap-3">
          <div className="space-y-1">
            <Label htmlFor="vat-from" className="text-xs text-muted-foreground">{tx('From')}</Label>
            <Input id="vat-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="h-9" />
          </div>
          <div className="space-y-1">
            <Label htmlFor="vat-to" className="text-xs text-muted-foreground">{tx('To')}</Label>
            <Input id="vat-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} className="h-9" />
          </div>
          {canSettle && (
            <Button
              variant="destructive"
              onClick={() => {
                if (window.confirm(tx('Settle and lock VAT from {{v1}} to {{v2}}?', { v1: from, v2: to }))) {
                  settle.mutate({ from, to });
                }
              }}
              disabled={settle.isPending || !p}
            >
              {tx('Settle and lock')}
            </Button>
          )}
        </div>
        {preview.isError ? (
          <p className="text-sm text-destructive">{tx('Could not compute the VAT for this period.')}</p>
        ) : preview.isLoading || !p ? (
          <Skeleton className="h-16 w-full" />
        ) : (
          <div className="grid gap-3 sm:grid-cols-3">
            <Stat label={tx('Output VAT')} value={money(p.outputTax)} />
            <Stat label={tx('Input VAT')} value={money(p.inputTax)} />
            <Stat label={Number(p.netPayable) >= 0 ? tx('Net payable') : tx('Net refundable')}
              value={money(Math.abs(Number(p.netPayable)))} />
          </div>
        )}
        {p?.settledThrough && (
          <p className="text-xs text-muted-foreground">
            {tx('VAT is settled through {{v1}}.', { v1: date(p.settledThrough) })}
          </p>
        )}
      </CardContent>
    </Card>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border p-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-lg font-semibold">{value}</div>
    </div>
  );
}

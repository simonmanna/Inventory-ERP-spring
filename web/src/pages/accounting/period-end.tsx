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
  useFxRevaluationAccounts,
  useSetFxRevaluationAccount,
  type FxRevaluationSetting,
  useFxRevaluationPreview,
  useFxRevaluationWarnings,
  useRunFxRevaluation,
  useSettleTax,
  useTaxSettlementPreview,
  useUndoLatestTaxSettlement,
} from '@/features/accounting/period-end-api';
import { useAuthStore } from '@/stores/auth.store';
import { PERMISSIONS } from '@erp/shared';
import { money, date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { firstOfMonth, localDay } from '@/lib/dates';

/** Whether the signed-in user holds `permission` (re-renders when permissions refresh). */
function useHasPermission(permission: string): boolean {
  return useAuthStore((s) => s.permissions).includes(permission);
}

const today = () => localDay();
const monthStart = () => firstOfMonth();

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
      <FxAccountSettingsCard />
      <VatSettlementCard />
    </div>
  );
}

function FxRevaluationCard() {
  const [asOf, setAsOf] = useState(today());
  const preview = useFxRevaluationPreview(asOf);
  const run = useRunFxRevaluation();
  const warnings = useFxRevaluationWarnings(asOf);
  const [acknowledged, setAcknowledged] = useState(false);
  const canRun = useHasPermission(PERMISSIONS.currency.update);
  const pending = (preview.data ?? []).filter((l) => Number(l.toPost) !== 0).length;
  const warningList = warnings.data ?? [];
  const blocked = warningList.length > 0 && !acknowledged;

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
            <Button onClick={() => run.mutate({ asOf, acknowledgeWarnings: acknowledged })}
              disabled={run.isPending || pending === 0 || blocked}>
              {tx('Post revaluation')}
            </Button>
          )}
        </div>
        {warningList.length > 0 && (
          <div className="rounded-md border border-amber-300 bg-amber-50 dark:bg-amber-950/30 p-3 space-y-1 text-sm">
            {warningList.map((w) => <p key={w.code + w.accountId}>{w.message}</p>)}
            <label className="flex items-center gap-2 pt-1 text-xs font-medium">
              <input type="checkbox" checked={acknowledged} onChange={(e) => setAcknowledged(e.target.checked)} />
              {tx('I have reviewed these warnings; revalue anyway')}
            </label>
          </div>
        )}
        {preview.isError ? (
          <p className="text-sm text-destructive">{tx('Could not compute the revaluation (check the closing rates).')}</p>
        ) : (
          <div className="rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Account')}</TableHead>
                  <TableHead>{tx('Currency')}</TableHead>
                  <TableHead>{tx('Partner')}</TableHead>
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
                    <TableCell colSpan={8}><Skeleton className="h-4 w-full" /></TableCell>
                  </TableRow>
                ) : (preview.data ?? []).length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={8} className="h-16 text-center text-muted-foreground">
                      {tx('No foreign-currency monetary balances.')}
                    </TableCell>
                  </TableRow>
                ) : (
                  preview.data!.map((l) => (
                    <TableRow key={l.accountId + l.currencyCode + (l.partnerId ?? '')}>
                      <TableCell>{l.accountCode}</TableCell>
                      <TableCell>{l.currencyCode}</TableCell>
                      <TableCell>{l.partnerName ?? '—'}</TableCell>
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

/** FX-2 C: which balance-sheet accounts the revaluation treats as monetary (revalued) or non-monetary. */
function FxAccountSettingsCard() {
  const canRead = useHasPermission(PERMISSIONS.currency.read);
  const canEdit = useHasPermission(PERMISSIONS.currency.policy);
  const [open, setOpen] = useState(false);
  const accounts = useFxRevaluationAccounts(canRead && open);
  const save = useSetFxRevaluationAccount();
  const [filter, setFilter] = useState('');
  if (!canRead) return null;
  const q = filter.trim().toLowerCase();
  const rows = (accounts.data ?? []).filter((a) =>
    !q || a.code.toLowerCase().includes(q) || a.name.toLowerCase().includes(q) || (a.categoryKey ?? '').toLowerCase().includes(q));
  const settingLabel = (v: FxRevaluationSetting, monetaryDefault: boolean) =>
    v === 'monetary' ? tx('Monetary (revalue)')
      : v === 'non_monetary' ? tx('Non-monetary (do not revalue)')
        : tx('Default ({{v1}})', { v1: monetaryDefault ? tx('revalued') : tx('not revalued') });

  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <div>
            <CardTitle>{tx('Account revaluation settings')}</CardTitle>
            <CardDescription>
              {tx('Monetary accounts (cash, receivables, payables, loans) are revalued at the closing rate; non-monetary ones (stock, fixed assets, prepayments) keep their historical rate.')}
            </CardDescription>
          </div>
          <Button variant="outline" size="sm" onClick={() => setOpen((v) => !v)}>
            {open ? tx('Hide') : tx('Show')}
          </Button>
        </div>
      </CardHeader>
      {open && (
        <CardContent className="space-y-3">
          <Input placeholder={tx('Filter by code, name or category')} value={filter} onChange={(e) => setFilter(e.target.value)} className="h-9 max-w-sm" />
          <div className="rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Code')}</TableHead>
                  <TableHead>{tx('Name')}</TableHead>
                  <TableHead>{tx('Category')}</TableHead>
                  <TableHead>{tx('Setting')}</TableHead>
                  <TableHead>{tx('Effective')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {accounts.isLoading ? (
                  <TableRow><TableCell colSpan={5}><Skeleton className="h-4 w-full" /></TableCell></TableRow>
                ) : accounts.isError ? (
                  <TableRow><TableCell colSpan={5} className="text-sm text-destructive">{tx('Could not load the accounts.')}</TableCell></TableRow>
                ) : rows.length === 0 ? (
                  <TableRow><TableCell colSpan={5} className="h-16 text-center text-muted-foreground">{tx('No accounts.')}</TableCell></TableRow>
                ) : (
                  rows.map((a) => (
                    <TableRow key={a.id}>
                      <TableCell className="font-mono text-xs">{a.code}</TableCell>
                      <TableCell>{a.name}</TableCell>
                      <TableCell className="text-xs text-muted-foreground">{a.categoryKey ?? '—'}</TableCell>
                      <TableCell>
                        {canEdit ? (
                          <select
                            className="h-8 rounded-md border border-input bg-background px-2 text-xs"
                            value={a.fxRevaluation}
                            disabled={save.isPending}
                            onChange={(e) => save.mutate({ id: a.id, fxRevaluation: e.target.value as FxRevaluationSetting })}
                          >
                            {(['inherit', 'monetary', 'non_monetary'] as const).map((v) => (
                              <option key={v} value={v}>{settingLabel(v, a.monetaryDefault)}</option>
                            ))}
                          </select>
                        ) : (
                          <span className="text-xs">{settingLabel(a.fxRevaluation, a.monetaryDefault)}</span>
                        )}
                      </TableCell>
                      <TableCell>
                        <span className={a.monetary ? 'text-xs font-medium text-emerald-700' : 'text-xs text-muted-foreground'}>
                          {a.monetary ? tx('Revalued') : tx('Not revalued')}
                        </span>
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </div>
        </CardContent>
      )}
    </Card>
  );
}

function VatSettlementCard() {
  const [from, setFrom] = useState(monthStart());
  const [to, setTo] = useState(today());
  const preview = useTaxSettlementPreview(from, to);
  const settle = useSettleTax();
  const undo = useUndoLatestTaxSettlement();
  // Finance audit A4: settling and undoing need tax:settle (they used to need only journal_entry:create).
  const canSettle = useHasPermission(PERMISSIONS.tax.settle);
  const p = preview.data;
  const settledThrough = p?.settledThrough;

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
        {settledThrough && (
          <div className="flex flex-wrap items-center gap-3">
            <p className="text-xs text-muted-foreground">
              {tx('VAT is settled through {{v1}}.', { v1: date(settledThrough) })}
            </p>
            {canSettle && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  if (window.confirm(tx('Undo the VAT settlement through {{v1}}? Its offset entry is reversed and the period is unlocked.', { v1: date(settledThrough) }))) {
                    undo.mutate();
                  }
                }}
                disabled={undo.isPending}
              >
                {tx('Undo latest settlement')}
              </Button>
            )}
          </div>
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

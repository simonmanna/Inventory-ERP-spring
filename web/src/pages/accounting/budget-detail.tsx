import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { CheckCircle2, Lock, Plus, RotateCcw, Save, Trash2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useAccounts } from '@/features/accounting/api';
import {
  useBudget,
  useBudgetAction,
  useBudgetVsActual,
  useDeleteBudget,
  useSaveBudgetLines,
  type BudgetLine,
} from '@/features/finance/api';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

function monthsBetween(start: string, end: string): string[] {
  const out: string[] = [];
  let [y, m] = start.slice(0, 7).split('-').map(Number);
  const [ey, em] = end.slice(0, 7).split('-').map(Number);
  while (y < ey || (y === ey && m <= em)) {
    out.push(`${y}-${String(m).padStart(2, '0')}`);
    m += 1;
    if (m > 12) {
      m = 1;
      y += 1;
    }
  }
  return out;
}

/** Grid row: one account, one amount per month. */
type GridRow = { accountId: string; amounts: Record<string, string> };

export function BudgetDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: budget, isLoading } = useBudget(id);
  const { data: accounts } = useAccounts();
  const save = useSaveBudgetLines(id!);
  const action = useBudgetAction(id!);
  const remove = useDeleteBudget();
  const [rows, setRows] = useState<GridRow[]>([]);
  const [newAccount, setNewAccount] = useState('');

  const months = useMemo(() => (budget ? monthsBetween(budget.startDate, budget.endDate) : []), [budget]);
  const locked = budget?.status === 'approved';

  useEffect(() => {
    if (!budget) return;
    const byAccount = new Map<string, GridRow>();
    for (const l of budget.lines ?? []) {
      const r = byAccount.get(l.accountId) ?? { accountId: l.accountId, amounts: {} };
      r.amounts[l.period] = String(Number(l.amount));
      byAccount.set(l.accountId, r);
    }
    setRows([...byAccount.values()]);
  }, [budget]);

  const accountOptions = useMemo(
    () =>
      (accounts?.data ?? [])
        .filter((a) => !a.isGroup && (a.allowBudgeting ?? a.category?.allowBudgeting ?? false))
        .map((a) => ({ value: a.id, label: `${a.code} · ${a.name}`, code: a.code })),
    [accounts],
  );
  const accountLabel = (accountId: string) => accountOptions.find((o) => o.value === accountId)?.label ?? accountId;

  const setAmount = (accountId: string, period: string, value: string) =>
    setRows((rs) => rs.map((r) => (r.accountId === accountId ? { ...r, amounts: { ...r.amounts, [period]: value } } : r)));

  const spread = (accountId: string) => {
    const total = Number(prompt(tx('Annual amount to spread evenly across months')) ?? '');
    if (!Number.isFinite(total) || total <= 0) return;
    const each = Math.round((total / months.length) * 100) / 100;
    setRows((rs) =>
      rs.map((r) =>
        r.accountId === accountId ? { ...r, amounts: Object.fromEntries(months.map((m) => [m, String(each)])) } : r,
      ),
    );
  };

  const onSave = () => {
    const lines: BudgetLine[] = [];
    for (const r of rows) {
      for (const m of months) {
        const v = Number(r.amounts[m] ?? 0);
        if (v > 0) lines.push({ accountId: r.accountId, period: m, amount: v });
      }
    }
    save.mutate(lines);
  };

  if (isLoading || !budget) return <Skeleton className="h-64 w-full" />;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-semibold">
            {budget.name}
            <Badge variant={locked ? 'default' : 'secondary'}>{tx(budget.status)}</Badge>
          </h1>
          <p className="text-sm text-muted-foreground">
            {tx('Fiscal year')} {budget.fiscalYear} · {tx('Total')} {money(budget.totalAmount)}
          </p>
        </div>
        <div className="flex gap-2">
          {locked ? (
            <Button variant="outline" onClick={() => action.mutate('reopen')}>
              <RotateCcw className="mr-2 h-4 w-4" />
              {tx('Reopen')}
            </Button>
          ) : (
            <>
              <Button variant="outline" onClick={onSave} disabled={save.isPending}>
                <Save className="mr-2 h-4 w-4" />
                {tx('Save lines')}
              </Button>
              <Button onClick={() => action.mutate('approve')} disabled={action.isPending}>
                <CheckCircle2 className="mr-2 h-4 w-4" />
                {tx('Approve')}
              </Button>
              <Button
                variant="ghost"
                onClick={() => {
                  if (confirm(tx('Delete this budget?'))) remove.mutate(budget.id, { onSuccess: () => navigate('/budgets') });
                }}
              >
                <Trash2 className="h-4 w-4" />
              </Button>
            </>
          )}
        </div>
      </div>

      <Tabs defaultValue="plan">
        <TabsList>
          <TabsTrigger value="plan">{tx('Plan')}</TabsTrigger>
          <TabsTrigger value="actual">{tx('Budget vs actual')}</TabsTrigger>
        </TabsList>

        <TabsContent value="plan" className="space-y-3">
          {locked && (
            <p className="flex items-center gap-2 text-sm text-muted-foreground">
              <Lock className="h-4 w-4" />
              {tx('Approved budgets are locked. Reopen to edit.')}
            </p>
          )}
          {!locked && (
            <div className="flex items-end gap-2">
              <div className="w-96">
                <SearchableSelect
                  value={newAccount}
                  onValueChange={setNewAccount}
                  options={accountOptions.filter((o) => !rows.some((r) => r.accountId === o.value))}
                  placeholder={tx('Add an income or expense account…')}
                />
              </div>
              <Button
                variant="outline"
                disabled={!newAccount}
                onClick={() => {
                  setRows((rs) => [...rs, { accountId: newAccount, amounts: {} }]);
                  setNewAccount('');
                }}
              >
                <Plus className="mr-2 h-4 w-4" />
                {tx('Add account')}
              </Button>
            </div>
          )}
          <div className="overflow-x-auto rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="sticky left-0 min-w-[220px] bg-background">{tx('Account')}</TableHead>
                  {months.map((m) => (
                    <TableHead key={m} className="min-w-[90px] text-right">
                      {m}
                    </TableHead>
                  ))}
                  <TableHead className="text-right">{tx('Total')}</TableHead>
                  {!locked && <TableHead />}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={months.length + 3} className="h-20 text-center text-muted-foreground">
                      {tx('No budget lines yet. Add an account above.')}
                    </TableCell>
                  </TableRow>
                ) : (
                  rows.map((r) => (
                    <TableRow key={r.accountId}>
                      <TableCell className="sticky left-0 bg-background text-sm">
                        <div>{accountLabel(r.accountId)}</div>
                        {!locked && (
                          <button className="text-xs text-primary underline" onClick={() => spread(r.accountId)}>
                            {tx('Spread annual amount')}
                          </button>
                        )}
                      </TableCell>
                      {months.map((m) => (
                        <TableCell key={m} className="p-1">
                          <Input
                            type="number"
                            min={0}
                            disabled={locked}
                            className="h-8 text-right text-xs"
                            value={r.amounts[m] ?? ''}
                            onChange={(e) => setAmount(r.accountId, m, e.target.value)}
                          />
                        </TableCell>
                      ))}
                      <TableCell className="text-right font-medium">
                        {money(months.reduce((s, m) => s + Number(r.amounts[m] ?? 0), 0))}
                      </TableCell>
                      {!locked && (
                        <TableCell>
                          <Button
                            size="icon"
                            variant="ghost"
                            onClick={() => setRows((rs) => rs.filter((x) => x.accountId !== r.accountId))}
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        </TableCell>
                      )}
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </div>
        </TabsContent>

        <TabsContent value="actual">
          <VsActual id={budget.id} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

function VsActual({ id }: { id: string }) {
  const { data, isLoading } = useBudgetVsActual(id, {});
  if (isLoading || !data) return <Skeleton className="h-40 w-full" />;
  return (
    <div className="space-y-3">
      <div className="grid gap-3 md:grid-cols-3">
        {(['income', 'expense', 'net'] as const).map((k) => (
          <div key={k} className="rounded-md border p-3">
            <div className="text-xs font-semibold uppercase text-muted-foreground">{tx({ income: 'Income', expense: 'Expenses', net: 'Net' }[k])}</div>
            <div className="mt-1 text-sm">
              {tx('Budget')} {money(data.totals[k].budget)}
            </div>
            <div className="text-lg font-semibold">
              {tx('Actual')} {money(data.totals[k].actual)}
            </div>
            <div className="text-xs text-muted-foreground">
              {tx('Variance')} {money(data.totals[k].variance)}
            </div>
          </div>
        ))}
      </div>
      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Account')}</TableHead>
              <TableHead>{tx('Type')}</TableHead>
              <TableHead className="text-right">{tx('Budget')}</TableHead>
              <TableHead className="text-right">{tx('Actual')}</TableHead>
              <TableHead className="text-right">{tx('Variance')}</TableHead>
              <TableHead className="text-right">{tx('Used')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.rows.map((r) => (
              <TableRow key={r.accountId}>
                <TableCell>
                  {r.code} · {r.name}
                </TableCell>
                <TableCell>{r.type === 'income' ? tx('Income') : tx('Expense')}</TableCell>
                <TableCell className="text-right">{money(r.budget)}</TableCell>
                <TableCell className="text-right">{money(r.actual)}</TableCell>
                <TableCell className={`text-right font-medium ${r.favorable ? 'text-emerald-600' : 'text-red-600'}`}>
                  {money(r.variance)}
                  {r.variancePct != null && <span className="ml-1 text-xs">({r.variancePct}%)</span>}
                </TableCell>
                <TableCell className="text-right">{r.utilizationPct != null ? `${r.utilizationPct}%` : '—'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}

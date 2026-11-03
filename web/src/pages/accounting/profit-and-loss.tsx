import { useState } from 'react';
import { TrendingUp, TrendingDown, DollarSign, Download } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { exportCSV } from '@/lib/export-csv';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { usePnL } from '@/features/accounting/api';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

export function ProfitAndLossPage() {
  const today = new Date();
  const defaultFrom = new Date(today.getFullYear(), today.getMonth(), 1).toISOString().slice(0, 10);
  const defaultTo = today.toISOString().slice(0, 10);
  const [from, setFrom] = useState(defaultFrom);
  const [to, setTo] = useState(defaultTo);

  const { data, isLoading, isError, error } = usePnL({ from, to });
  const net = data ? (data.netProfit ?? data.operatingProfit) : undefined;

  const onExport = () => {
    if (!data) return;
    exportCSV(`profit-and-loss-${from}-${to}.csv`, [tx('Line'), tx('Amount')], [
      [tx('Gross Revenue'), data.revenue],
      [tx('Contra Revenue'), data.contraRevenue],
      [tx('Net Revenue'), data.netRevenue],
      [tx('COGS'), data.cogs],
      [tx('Gross Profit'), data.grossProfit],
      [tx('Operating Expenses'), data.expense],
      [tx('Operating Profit'), data.operatingProfit],
      [tx('Other Income'), data.otherIncome],
      [tx('Other Expense'), data.otherExpense ?? '0'],
      [tx('Net Profit'), net ?? '0'],
    ]);
  };

  const profitColor = net !== undefined ? (Number(net) >= 0 ? 'text-emerald-600' : 'text-red-600') : '';

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="border-l-4 border-[#3b82f6] pl-4 space-y-1">
          <h1 className="text-3xl font-bold tracking-tight text-foreground">{tx('Profit & Loss')}</h1>
          <p className="text-sm text-muted-foreground">{tx('Revenue, costs, and operating profit for the selected period.')}</p>
        </div>
        <Button variant="outline" size="sm" onClick={onExport} disabled={!data}>
          <Download className="mr-2 h-4 w-4" />
          {tx('Export CSV')}
        </Button>
      </div>

      <div className="flex flex-wrap items-end gap-3 p-3 bg-card border rounded-lg shadow-sm">
        <div className="space-y-1 min-w-[150px]">
          <Label className="text-[10px] font-bold text-muted-foreground uppercase tracking-wider">{tx('From')}</Label>
          <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="h-9 text-xs" />
        </div>
        <div className="space-y-1 min-w-[150px]">
          <Label className="text-[10px] font-bold text-muted-foreground uppercase tracking-wider">{tx('To')}</Label>
          <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="h-9 text-xs" />
        </div>
      </div>

      {isError && (
        <div className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive">
          {tx('Could not load profit and loss: {{v1}}', { v1: (error as Error)?.message ?? '' })}
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {/* Revenue Card */}
        <Card>
          <CardHeader className="pb-2 pt-4 px-5 bg-emerald-50/50 border-b rounded-t-lg">
            <div className="flex items-center gap-2">
              <TrendingUp className="h-4 w-4 text-emerald-600" />
              <CardTitle className="text-xs font-bold text-emerald-700 uppercase tracking-wider">{tx('Revenue')}</CardTitle>
            </div>
          </CardHeader>
          <CardContent className="p-5">
            {isLoading ? (
              <Skeleton className="h-8 w-32" />
            ) : (
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs text-muted-foreground">{tx('Gross Revenue')}</span>
                  <span className="text-lg font-bold text-emerald-600">{data ? money(data.revenue) : '—'}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-xs text-muted-foreground">{tx('Contra Revenue')}</span>
                  <span className="text-sm font-medium text-red-500">({data ? money(data.contraRevenue) : '—'})</span>
                </div>
                <div className="border-t pt-2 mt-2 flex items-center justify-between">
                  <span className="text-xs font-semibold text-muted-foreground">{tx('Net Revenue')}</span>
                  <span className="text-base font-bold text-emerald-600">{data ? money(data.netRevenue) : '—'}</span>
                </div>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Expenses Card */}
        <Card>
          <CardHeader className="pb-2 pt-4 px-5 bg-red-50/50 border-b rounded-t-lg">
            <div className="flex items-center gap-2">
              <TrendingDown className="h-4 w-4 text-red-500" />
              <CardTitle className="text-xs font-bold text-red-700 uppercase tracking-wider">{tx('Costs')}</CardTitle>
            </div>
          </CardHeader>
          <CardContent className="p-5">
            {isLoading ? (
              <Skeleton className="h-8 w-32" />
            ) : (
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs text-muted-foreground">{tx('COGS')}</span>
                  <span className="text-lg font-bold text-red-500">{data ? money(data.cogs) : '—'}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-xs text-muted-foreground">{tx('Operating Expenses')}</span>
                  <span className="text-sm font-medium text-red-500">{data ? money(data.expense) : '—'}</span>
                </div>
                <div className="border-t pt-2 mt-2 flex items-center justify-between">
                  <span className="text-xs font-semibold text-muted-foreground">{tx('Total Costs')}</span>
                  <span className="text-base font-bold text-red-500">{data ? money(Number(data.cogs) + Number(data.expense)) : '—'}</span>
                </div>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Profit Card */}
        <Card>
          <CardHeader className="pb-2 pt-4 px-5 bg-blue-50/50 border-b rounded-t-lg">
            <div className="flex items-center gap-2">
              <DollarSign className={`h-4 w-4 ${profitColor}`} />
              <CardTitle className={`text-xs font-bold uppercase tracking-wider ${profitColor}`}>{tx('Operating Profit')}</CardTitle>
            </div>
          </CardHeader>
          <CardContent className="p-5">
            {isLoading ? (
              <Skeleton className="h-8 w-32" />
            ) : (
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-xs text-muted-foreground">{tx('Gross Profit')}</span>
                  <span className="text-sm font-semibold">{data ? money(data.grossProfit) : '—'}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-xs text-muted-foreground">{tx('Operating Profit')}</span>
                  <span className="text-sm font-semibold">{data ? money(data.operatingProfit) : '—'}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-xs text-muted-foreground">{tx('Other Income')}</span>
                  <span className="text-sm font-medium text-emerald-600">{data ? money(data.otherIncome) : '—'}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-xs text-muted-foreground">{tx('Other Expense')}</span>
                  <span className="text-sm font-medium text-red-500">({data ? money(data.otherExpense ?? '0') : '—'})</span>
                </div>
                <div className="border-t pt-2 flex items-center justify-between">
                  <span className="text-sm font-bold text-muted-foreground">{tx('Net Profit')}</span>
                  <span className={`text-xl font-bold ${profitColor}`}>{net !== undefined ? money(net) : '—'}</span>
                </div>
                <div className="text-[10px] text-muted-foreground italic pt-1">
                  {data ? (data.source === 'snapshot' ? tx('Snapshotted {{v1}}', { v1: data.asOf ? new Date(data.asOf).toLocaleDateString() : '' }) : tx('Live computation')) : ''}
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

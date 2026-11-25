import { useState } from 'react';
import { Scale, RotateCcw, RefreshCw, ChevronRight } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  toastMutationError,
  useBalanceSheetDetailed,
  useBaseCurrencyDecimals,
  useRebuildSnapshots,
} from '@/features/accounting/api';
import { PERMISSIONS } from '@erp/shared';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import { toast } from 'sonner';
import { localDay } from '@/lib/dates';
import { tx } from '@/lib/i18n/tx';

const today = () => localDay();

function fmtMoney(value?: string, decimals = 2): string {
  if (!value && value !== '0') return '-';
  const n = Number(value);
  if (Number.isNaN(n)) return '-';
  const abs = Math.abs(n);
  const formatted = abs.toLocaleString(undefined, {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
  return n < 0 ? `(${formatted})` : formatted;
}

function SectionSkeleton() {
  return (
    <div className="space-y-3">
      {Array.from({ length: 5 }).map((_, i) => (
        <Skeleton key={i} className="h-5 w-full" />
      ))}
      <Skeleton className="h-6 w-2/3 mt-2" />
    </div>
  );
}

type IconColor = 'green' | 'red' | 'blue' | 'amber';

interface SectionPanelProps {
  title: string;
  icon: React.ElementType;
  iconColor: IconColor;
  loading: boolean;
  rows: { name: string; balance?: string }[];
  subtotal?: string;
  subtotalLabel?: string;
  total?: string;
  totalLabel?: string;
  isGrandTotal?: boolean;
  /** Base-currency minor units. */
  decimals?: number;
}

function SectionPanel({
  title,
  icon: Icon,
  iconColor,
  loading,
  rows,
  subtotal,
  subtotalLabel,
  total,
  totalLabel,
  isGrandTotal = false,
  decimals = 2,
}: SectionPanelProps) {
  const fmt = (v?: string) => fmtMoney(v, decimals);
  const colorMap: Record<IconColor, { bg: string; text: string; border: string }> = {
    green: { bg: 'bg-emerald-50', text: 'text-emerald-600', border: 'border-emerald-500' },
    red: { bg: 'bg-red-50', text: 'text-red-600', border: 'border-red-500' },
    blue: { bg: 'bg-blue-50', text: 'text-blue-600', border: 'border-blue-500' },
    amber: { bg: 'bg-amber-50', text: 'text-amber-600', border: 'border-amber-500' },
  };
  const c = colorMap[iconColor];

  return (
    <Card className={cn('border-t-4', c.border)}>
      <CardHeader className="pb-3">
        <CardTitle className="text-base font-bold flex items-center gap-2">
          <span className={cn('rounded-md p-1.5', c.bg)}>
            <Icon className={cn('h-4 w-4', c.text)} />
          </span>
          {title}
        </CardTitle>
      </CardHeader>
      <CardContent>
        {loading ? (
          <SectionSkeleton />
        ) : rows.length === 0 ? (
          <p className="text-sm text-slate-400 py-2">{tx('No accounts found.')}</p>
        ) : (
          <div className="space-y-0">
            {rows.map((row, idx) => (
              <div
                key={idx}
                className="flex justify-between items-center py-1.5 text-sm border-b border-slate-100 last:border-b-0"
              >
                <span className="text-slate-700">{row.name}</span>
                <span className="font-mono text-slate-700">
                  {fmt(row.balance)}
                </span>
              </div>
            ))}

            {subtotal && (
              <div className="flex justify-between pt-3 mt-2 border-t-2 border-slate-200 font-semibold text-sm">
                <span>{subtotalLabel || tx('Subtotal')}</span>
                <span className="font-mono">{fmt(subtotal)}</span>
              </div>
            )}
          </div>
        )}

        {total && !isGrandTotal && (
          <div className="flex justify-between pt-3 mt-3 border-t-2 border-slate-300 font-bold text-sm">
            <span>{totalLabel || tx('Total')}</span>
            <span className="font-mono">{fmt(total)}</span>
          </div>
        )}

        {isGrandTotal && total && (
          <div className="flex justify-between pt-3 mt-3 border-t-2 border-slate-800 bg-slate-50 px-3 py-3 -mx-4 -mb-4 rounded-b-xl">
            <span className="font-bold">{totalLabel || tx('Total')}</span>
            <span className="font-mono font-bold text-base">{fmt(total)}</span>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export function BalanceSheetPage() {
  const [asOf, setAsOf] = useState(today);
  const { data, isLoading, isError, refetch } = useBalanceSheetDetailed(asOf);
  const rebuildMutation = useRebuildSnapshots();
  const decimals = useBaseCurrencyDecimals();
  // Finance audit A4: the rebuild also needs fiscal_period:update, not only the read permission of the reports.
  const permissions = useAuthStore((s) => s.permissions);
  const canRebuild =
    permissions.includes(PERMISSIONS.report.accounting) && permissions.includes(PERMISSIONS.fiscalPeriod.update);

  async function handleRebuild() {
    try {
      await rebuildMutation.mutateAsync();
      toast.success(tx('Snapshots rebuilt'));
    } catch (e) {
      toastMutationError(tx('Could not rebuild snapshots'), e);
    }
  }

  const sectionMap = new Map((data?.sections ?? []).map((s) => [s.key, s]));
  const currentAssets = sectionMap.get('current_assets');
  const nonCurrentAssets = sectionMap.get('non_current_assets');
  const currentLiabilities = sectionMap.get('current_liabilities');
  const longTermLiabilities = sectionMap.get('long_term_liabilities');
  const equity = sectionMap.get('equity');
  // Unclosed P&L belongs to equity; without this row equity + liabilities would not add up to the total.
  const equityRows = [
    ...(equity?.rows ?? []),
    ...(data?.currentYearEarnings !== undefined
      ? [{ name: tx('Current year earnings'), balance: data.currentYearEarnings }]
      : []),
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Balance Sheet')}</h1>
          <p className="text-sm text-slate-500">
            {tx('Financial position as of a point in time.')}
          </p>
        </div>
        {data && (
          <div className="flex items-center gap-3">
            <Badge
              variant={data.balanced ? 'default' : 'destructive'}
              className="gap-1.5 px-3 py-1"
            >
              <Scale className="h-3.5 w-3.5" />
              {data.balanced ? tx('Balanced') : tx('Out of balance')}
            </Badge>
            <Badge
              variant="outline"
              className="gap-1.5 px-3 py-1 text-slate-500"
            >
              <RotateCcw className="h-3.5 w-3.5" />
              {data.source === 'snapshot' ? tx('Snapshot') : tx('Live')}
            </Badge>
          </div>
        )}
      </div>

      <div className="flex items-end gap-3">
        <div className="space-y-1.5">
          <label className="text-xs font-medium text-slate-500">{tx('As of date')}</label>
          <Input
            type="date"
            value={asOf}
            onChange={(e) => setAsOf(e.target.value)}
            className="h-9 w-48"
          />
        </div>
        <Button
          variant="outline"
          size="sm"
          className="h-9 gap-1.5"
          onClick={() => refetch()}
        >
          <RefreshCw className="h-3.5 w-3.5" />
          {tx('Refresh')}
        </Button>
        {canRebuild && (
          <Button
            variant="outline"
            size="sm"
            className="h-9 gap-1.5"
            onClick={handleRebuild}
            disabled={rebuildMutation.isPending}
          >
            <RotateCcw
              className={cn(
                'h-3.5 w-3.5',
                rebuildMutation.isPending && 'animate-spin',
              )}
            />
            {rebuildMutation.isPending ? tx('Rebuilding…') : tx('Rebuild Snapshots')}
          </Button>
        )}
      </div>

      {isError && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {tx('Failed to load balance sheet. Check that the account mappings are configured.')}
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Left column: Assets */}
        <div className="space-y-4">
          <SectionPanel
            title={tx('Current Assets')}
            icon={Scale}
            iconColor="green"
            loading={isLoading}
            decimals={decimals}
            rows={currentAssets?.rows ?? []}
            subtotal={currentAssets?.subtotal}
            subtotalLabel={tx('Total current assets')}
          />

          <SectionPanel
            title={tx('Non-current Assets')}
            icon={Scale}
            iconColor="green"
            loading={isLoading}
            decimals={decimals}
            rows={nonCurrentAssets?.rows ?? []}
            total={data?.totals.assets}
            totalLabel={tx('Total assets')}
            isGrandTotal
          />
        </div>

        {/* Right column: Liabilities + Equity */}
        <div className="space-y-4">
          <SectionPanel
            title={tx('Current Liabilities')}
            icon={Scale}
            iconColor="red"
            loading={isLoading}
            decimals={decimals}
            rows={currentLiabilities?.rows ?? []}
            subtotal={currentLiabilities?.subtotal}
            subtotalLabel={tx('Total current liabilities')}
          />

          <SectionPanel
            title={tx('Long-term Liabilities')}
            icon={Scale}
            iconColor="red"
            loading={isLoading}
            decimals={decimals}
            rows={longTermLiabilities?.rows ?? []}
            total={data?.totals.liabilities}
            totalLabel={tx('Total liabilities')}
          />

          <div className="flex items-center gap-2 text-slate-400">
            <div className="flex-1 h-px bg-slate-200" />
            <ChevronRight className="h-4 w-4" />
            <div className="flex-1 h-px bg-slate-200" />
          </div>

          <SectionPanel
            title={tx('Stockholders\' Equity')}
            icon={Scale}
            iconColor="blue"
            loading={isLoading}
            decimals={decimals}
            rows={equityRows}
            total={data?.totals.liabilitiesAndEquity}
            totalLabel={tx('Total liabilities & stockholders\' equity')}
            isGrandTotal
          />
        </div>
      </div>

      {data && (
        <p className="text-xs text-slate-400">
          {tx('As of')} {new Date(data.asOf).toLocaleDateString()} {tx('· Source:')}{' '}
          {data.source === 'snapshot' ? tx('Rebuilt snapshot') : tx('Live aggregation')}
        </p>
      )}
    </div>
  );
}

import { useQueryClient } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useReportFilters } from './inventory-reports/filters';
import { MovementReport } from './inventory-reports/MovementReport';
import { ValuationReport } from './inventory-reports/ValuationReport';
import { AnalysisReport } from './inventory-reports/AnalysisReport';
import { ExpiringReport, NegativeStockReport, ReorderReport } from './inventory-reports/ExceptionReports';
import { StockHealthReport } from './inventory-reports/StockHealthReport';
import { RegisterReport, type RegisterKind } from './inventory-reports/RegisterReport';
import { tx } from '@/lib/i18n/tx';

const REGISTER_TABS: RegisterKind[] = ['stock_in', 'stock_out', 'damages', 'adjustments', 'transfers'];

const TABS = [
  { value: 'movements', label: tx('Stock Movement'), description: tx('Opening, in, out and closing per item — quantity and value.') },
  { value: 'stock_in', label: tx('Stock In'), description: tx('Everything received — purchases, direct stock-in, returns, production — with trends and sources.') },
  { value: 'stock_out', label: tx('Stock Out'), description: tx('Everything issued — POS sales, recipe consumption, internal use, supplier returns.') },
  { value: 'damages', label: tx('Damages'), description: tx('Waste, breakage, expiry write-offs and damage/theft adjustments — cost of loss and root causes.') },
  { value: 'adjustments', label: tx('Adjustments'), description: tx('Count corrections: gains vs losses, reasons, and who adjusted what.') },
  { value: 'transfers', label: tx('Stock Transfers'), description: tx('Stock moved between locations — routes, items and value in transit.') },
  { value: 'valuation', label: tx('Stock Valuation'), description: tx('Current on-hand at running average cost, by status and category.') },
  { value: 'health', label: tx('Stock Health'), description: tx('Aging, turnover, days of cover and slow-moving / dead stock per item and location.') },
  { value: 'analysis', label: tx('Movement Analysis'), description: tx('Flow by movement type, daily trend, top consumed / wasted / received.') },
  { value: 'reorder', label: tx('Reorder'), description: tx('Items at or below par with suggested purchase quantities.') },
  { value: 'expiring', label: tx('Expiring Batches'), description: tx('Batch-tracked stock nearing or past expiry.') },
  { value: 'negative', label: tx('Negative Stock'), description: tx('Items sold before being received, and the valuation exposure.') },
];

export function InventoryReportsPage() {
  const ctl = useReportFilters();
  const qc = useQueryClient();
  const tab = TABS.some((t) => t.value === ctl.filters.tab) ? ctl.filters.tab : 'movements';
  const current = TABS.find((t) => t.value === tab)!;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Inventory Reports')}</h1>
          <p className="text-sm text-muted-foreground">{current.description}</p>
        </div>
        <Button variant="outline" size="sm" onClick={() => qc.invalidateQueries({ queryKey: ['inventory-report'] })}>
          <RefreshCw className="mr-2 h-3 w-3" />{tx('Refresh')}
        </Button>
      </div>

      <Tabs value={tab} onValueChange={(v) => ctl.set({ tab: v, source: '', reason: '', staffId: '', direction: '' })}>
        <div className="overflow-x-auto">
          <TabsList className="h-auto flex-wrap justify-start">
            {TABS.map((t) => <TabsTrigger key={t.value} value={t.value}>{t.label}</TabsTrigger>)}
          </TabsList>
        </div>
        <TabsContent value="movements"><MovementReport ctl={ctl} /></TabsContent>
        {REGISTER_TABS.map((k) => (
          <TabsContent key={k} value={k}>{tab === k && <RegisterReport kind={k} ctl={ctl} />}</TabsContent>
        ))}
        <TabsContent value="valuation"><ValuationReport ctl={ctl} /></TabsContent>
        <TabsContent value="health">{tab === 'health' && <StockHealthReport ctl={ctl} />}</TabsContent>
        <TabsContent value="analysis"><AnalysisReport ctl={ctl} /></TabsContent>
        <TabsContent value="reorder"><ReorderReport ctl={ctl} /></TabsContent>
        <TabsContent value="expiring"><ExpiringReport ctl={ctl} /></TabsContent>
        <TabsContent value="negative"><NegativeStockReport ctl={ctl} /></TabsContent>
      </Tabs>
    </div>
  );
}

export default InventoryReportsPage;

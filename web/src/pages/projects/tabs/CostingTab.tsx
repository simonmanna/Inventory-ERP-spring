import { useState } from 'react';
import { AlertTriangle, Boxes, Clock, Coins, Lock, Shuffle, Truck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { PERMISSIONS } from '@erp/shared';
import { useAuthStore } from '@/stores/auth.store';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { type Workspace } from '@/features/projects/api';
import { useJobProject } from '@/features/projects/costing-api';
import { EmptyState } from '@/features/projects/ui';
import { SummarySection } from './costing/SummarySection';
import { BudgetsSection } from './costing/BudgetsSection';
import { ForecastSection } from './costing/ForecastSection';
import { CostLinesSection } from './costing/CostLinesSection';
import { StructureSection } from './costing/StructureSection';
import { CaptureCostDialog, type CaptureKind } from './costing/CaptureCostDialog';

type View = 'summary' | 'budgets' | 'forecast' | 'costs' | 'structure';

/**
 * Job costing of one project (CM-8a): cost report, budgets, cost-to-complete forecast, the cost sub-ledger with
 * capture and reversal, and the WBS / sites the cost is booked against. View needs `project:read`; budgets, forecast,
 * WBS and sites need `project:manage`; capturing and reversing cost needs `project:post`.
 */
export function CostingTab({ ws }: { ws: Workspace }) {
  const has = useAuthStore((s) => s.hasPermission);
  const canRead = has(PERMISSIONS.project.read);
  const canManage = has(PERMISSIONS.project.manage);
  const canPost = has(PERMISSIONS.project.post);
  const [view, setView] = useState<View>('summary');
  const [capture, setCapture] = useState<CaptureKind | null>(null);
  const { data: project, isLoading } = useJobProject(ws.id, canRead);

  if (!canRead) {
    return <EmptyState icon={Lock} title={tx('No access to job costing')} hint={tx('Ask an administrator for the project:read permission.')} />;
  }
  if (isLoading || !project) return <Skeleton className="h-96 w-full" />;

  const active = project.status === 'active';
  const noWbs = project.wbs.filter((w) => w.isCostable).length === 0;
  const views: Array<{ id: View; label: string }> = [
    { id: 'summary', label: tx('Cost report') },
    { id: 'budgets', label: tx('Budgets') },
    { id: 'forecast', label: tx('Forecast') },
    { id: 'costs', label: tx('Cost lines') },
    { id: 'structure', label: tx('WBS & sites') },
  ];
  const captures: Array<{ id: CaptureKind; label: string; icon: typeof Boxes }> = [
    { id: 'issue', label: tx('Issue stock'), icon: Boxes },
    { id: 'timesheets', label: tx('Timesheets'), icon: Clock },
    { id: 'equipment', label: tx('Equipment'), icon: Truck },
    { id: 'reclass', label: tx('Reclass expense'), icon: Shuffle },
  ];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="inline-flex rounded-lg border bg-muted/40 p-0.5">
          {views.map((v) => (
            <button
              key={v.id}
              type="button"
              onClick={() => setView(v.id)}
              className={cn('rounded-md px-3 py-1.5 text-sm', view === v.id ? 'bg-background font-medium shadow-sm' : 'text-muted-foreground hover:text-foreground')}
            >
              {v.label}
            </button>
          ))}
        </div>
        {canPost && (
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="mr-1 flex items-center gap-1 text-xs text-muted-foreground"><Coins className="h-3.5 w-3.5" />{tx('Capture cost')}:</span>
            {captures.map((c) => (
              <Button
                key={c.id}
                variant="outline"
                size="sm"
                disabled={!active || noWbs}
                title={!active ? tx('Cost can only be captured on an active project') : noWbs ? tx('Add a costable WBS element first') : undefined}
                onClick={() => setCapture(c.id)}
              >
                <c.icon className="mr-1.5 h-4 w-4" />{c.label}
              </Button>
            ))}
          </div>
        )}
      </div>

      {canPost && !active && (
        <p className="flex items-center gap-2 rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-300">
          <AlertTriangle className="h-4 w-4 shrink-0" />{tx('Cost can only be captured on an active project')}
        </p>
      )}

      {view === 'summary' && <SummarySection project={project} />}
      {view === 'budgets' && <BudgetsSection project={project} canManage={canManage} />}
      {view === 'forecast' && <ForecastSection project={project} canManage={canManage} />}
      {view === 'costs' && <CostLinesSection project={project} canPost={canPost} />}
      {view === 'structure' && <StructureSection project={project} canManage={canManage} />}

      <CaptureCostDialog kind={capture} project={project} onClose={() => setCapture(null)} />
    </div>
  );
}

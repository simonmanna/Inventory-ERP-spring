import { useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { BellRing, BrainCircuit, RefreshCw, ShieldAlert } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import {
  AlertEventsTab, AlertExceptionReport, AlertSettingsTab, useOpenAlertCounts, useRunAlertScan,
} from '@/pages/alerts/AlertsTabs';
import { CamerasTab } from '@/pages/loss-prevention/CamerasTab';
import { EvidenceTab } from '@/pages/loss-prevention/EvidenceTab';
import { InsightsTab } from '@/pages/loss-prevention/InsightsTab';
import { IntelligenceOverview } from '@/pages/loss-prevention/IntelligenceOverview';
import {
  CaseDialog, CasesTab, ENGINE_LABELS, ReportsTab, RulesTab, RunsTab, useLpLicence, useLpSyncAction,
} from '@/pages/loss-prevention/LossPreventionTabs';
import { MetersTab } from '@/pages/loss-prevention/MetersTab';
import { ResearchTab } from '@/pages/loss-prevention/ResearchTab';
import { AI_OPS_ALERTS_ENABLED, AI_OPS_LP_ENABLED } from './access';

/**
 * AI Operations Intelligence: alerts & exceptions (ADR-S021) and loss prevention (ADR-S077) on one page. Alerts are
 * raised by scheduled scans; loss-prevention cases are variances found by reconciling recorded sales, voids, cash and
 * stock counts. Each part shows only when its module is switched on and the user holds its permission.
 */
export function AiOperationsPage() {
  const can = useAuthStore((s) => s.hasPermission);
  const alerts = AI_OPS_ALERTS_ENABLED && can(PERMISSIONS.alerts.read);
  const lp = AI_OPS_LP_ENABLED && can(PERMISSIONS.lossPrevention.view);
  const canManageAlerts = alerts && can(PERMISSIONS.alerts.manage);
  const canConfigure = lp && can(PERMISSIONS.lossPrevention.configure);
  const canInvestigate = lp && can(PERMISSIONS.lossPrevention.investigate);
  const canEvidence = lp && can(PERMISSIONS.lossPrevention.evidence);
  const licence = useLpLicence();
  const vision = lp && licence.vision;
  const insights = lp && licence.insights;

  const tabs: { value: string; label: string; show: boolean }[] = [
    { value: 'overview', label: tx('Overview'), show: alerts || lp },
    { value: 'alerts', label: tx('Alerts'), show: alerts },
    { value: 'cases', label: tx('Cases'), show: lp },
    { value: 'reports', label: tx('Reports'), show: alerts || lp },
    { value: 'insights', label: tx('Insights'), show: insights },
    { value: 'runs', label: tx('Reconciliation runs'), show: lp },
    { value: 'rules', label: tx('Rules & settings'), show: alerts || lp },
    { value: 'cameras', label: tx('Cameras'), show: vision && canConfigure },
    { value: 'meters', label: tx('Pour meters'), show: canConfigure },
    { value: 'evidence', label: tx('Evidence'), show: vision && canEvidence },
    { value: 'research', label: tx('Research'), show: vision && canInvestigate },
  ];
  const visible = tabs.filter((t) => t.show);

  // The tab lives in the URL so alert links (`?tab=alerts&type=…`) and the old /alerts and /loss-prevention addresses
  // land on the right place.
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  const tab = visible.some((t) => t.value === requested) ? (requested as string) : visible[0]?.value ?? 'overview';
  const setTab = (value: string) => setParams((p) => {
    const next = new URLSearchParams(p);
    next.set('tab', value);
    if (value !== 'alerts') next.delete('type');
    return next;
  }, { replace: true });
  const [overviewCase, setOverviewCase] = useState<string | null>(null);

  const scan = useRunAlertScan();
  const sync = useLpSyncAction();
  const actions = (canManageAlerts || canConfigure) ? (
    <div className="flex flex-wrap gap-2">
      {canManageAlerts && (
        <Button variant="outline" disabled={scan.pending} onClick={scan.onRun}>
          <RefreshCw className="mr-1.5 h-4 w-4" />{tx('Scan alerts now')}
        </Button>
      )}
      {canConfigure && (
        <Button variant="outline" disabled={sync.pending} onClick={sync.onSync}>
          <RefreshCw className="mr-1.5 h-4 w-4" />{tx('Read records now')}
        </Button>
      )}
    </div>
  ) : undefined;

  return (
    <div className="space-y-4">
      <ListPageHeader icon={BrainCircuit} title={tx('AI Operations Intelligence')}
        description={tx('Alerts from scheduled scans and loss-prevention cases from reconciled sales, voids, cash and stock counts, in one place. A case is a variance to review, not an accusation.')}
        actions={actions} />
      {visible.length === 0 ? (
        <p className="text-muted-foreground">{tx('Alerts and loss prevention are not switched on for this installation.')}</p>
      ) : (
        <Tabs value={tab} onValueChange={setTab}>
          <TabsList className="h-auto flex-wrap justify-start">
            {visible.map((t) => <TabsTrigger key={t.value} value={t.value}>{t.label}</TabsTrigger>)}
          </TabsList>
          <TabsContent value="overview" className="mt-4 space-y-4">
            {alerts && <AlertsSummary onOpen={() => setTab('alerts')} />}
            {lp && (
              <IntelligenceOverview vision={vision} canEvidence={canEvidence} insights={insights} engineLabels={ENGINE_LABELS}
                onOpenCase={setOverviewCase} onViewAll={() => setTab('cases')} />
            )}
            {lp && overviewCase && (
              <CaseDialog id={overviewCase} canInvestigate={canInvestigate} canConfigure={canConfigure}
                showEvidence={vision && canEvidence} onClose={() => setOverviewCase(null)} />
            )}
          </TabsContent>
          {alerts && <TabsContent value="alerts" className="mt-4 space-y-4"><AlertEventsTab /></TabsContent>}
          {lp && (
            <TabsContent value="cases" className="mt-4 space-y-4">
              <CasesTab canInvestigate={canInvestigate} canConfigure={canConfigure} vision={vision} canEvidence={canEvidence} />
            </TabsContent>
          )}
          <TabsContent value="reports" className="mt-4 space-y-6">
            {lp && <Part show={alerts} icon={ShieldAlert} title={tx('Loss prevention')}><ReportsTab /></Part>}
            {alerts && <Part show={lp} icon={BellRing} title={tx('Exception report')}><AlertExceptionReport /></Part>}
          </TabsContent>
          {insights && (
            <TabsContent value="insights" className="mt-4 space-y-4">
              <InsightsTab canConfigure={canConfigure} canInvestigate={canInvestigate} vision={vision} />
            </TabsContent>
          )}
          {lp && <TabsContent value="runs" className="mt-4 space-y-4"><RunsTab canConfigure={canConfigure} vision={vision} /></TabsContent>}
          <TabsContent value="rules" className="mt-4 space-y-6">
            {lp && <Part show={alerts} icon={ShieldAlert} title={tx('Loss-prevention rules')}><RulesTab canConfigure={canConfigure} /></Part>}
            {alerts && <Part show={lp} icon={BellRing} title={tx('Alert settings')}><AlertSettingsTab canManage={canManageAlerts} /></Part>}
          </TabsContent>
          {vision && canConfigure && (
            <TabsContent value="cameras" className="mt-4 space-y-4"><CamerasTab canEvidence={canEvidence} /></TabsContent>
          )}
          {canConfigure && <TabsContent value="meters" className="mt-4 space-y-4"><MetersTab vision={vision} /></TabsContent>}
          {vision && canEvidence && (
            <TabsContent value="evidence" className="mt-4 space-y-4"><EvidenceTab canConfigure={canConfigure} /></TabsContent>
          )}
          {vision && canInvestigate && (
            <TabsContent value="research" className="mt-4 space-y-4">
              <ResearchTab canEvidence={canEvidence} canConfigure={canConfigure} />
            </TabsContent>
          )}
        </Tabs>
      )}
    </div>
  );
}

/** A titled block inside a shared tab; the title shows only when the other module shares the tab. */
function Part({ show, icon: Icon, title, children }: {
  show: boolean; icon: typeof BellRing; title: string; children: ReactNode;
}) {
  return (
    <section className="space-y-4">
      {show && (
        <h2 className="flex items-center gap-2 text-base font-semibold">
          <Icon className="h-4 w-4 text-muted-foreground" aria-hidden />{title}
        </h2>
      )}
      {children}
    </section>
  );
}

function AlertsSummary({ onOpen }: { onOpen: () => void }) {
  const { open, critical } = useOpenAlertCounts();
  const value = (v: number | undefined) => (v === undefined ? '—' : v.toLocaleString());
  return (
    <Card>
      <CardContent className="flex flex-wrap items-center gap-x-8 gap-y-3 p-4">
        <div className="flex items-center gap-2">
          <BellRing className="h-5 w-5 text-muted-foreground" aria-hidden />
          <span className="font-medium">{tx('Alerts & exceptions')}</span>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">{tx('Open alerts')}</p>
          <p className="text-2xl font-semibold tabular-nums">{value(open)}</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">{tx('Critical, not acknowledged')}</p>
          <p className={`text-2xl font-semibold tabular-nums ${critical ? 'text-destructive' : ''}`}>{value(critical)}</p>
        </div>
        <Button variant="outline" size="sm" className="ml-auto" onClick={onOpen}>{tx('View alerts')}</Button>
      </CardContent>
    </Card>
  );
}

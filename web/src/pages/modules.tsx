import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Power, PowerOff, Boxes } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { api } from '@/lib/api';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

interface OrgModule {
  id: string;
  moduleName: string;
  isActive: boolean;
  config: Record<string, unknown>;
  enabledAt: string;
  disabledAt: string | null;
}

interface CatalogModule {
  name: string;
  version: string;
  dependencies: string[];
}

/**
 * Presentation only. The catalog itself comes from
 * `GET /feature-flags/modules/catalog`, which returns what this deployment
 * actually booted (ADR-005 manifests) — a module missing from this map still
 * renders, using its manifest name.
 */
const MODULE_META: Record<string, { label: string; description: string }> = {
  pos: { label: tx('Point of Sale'), description: tx('Tables, KDS, tabs, split bills, cash sessions, receipts') },
  core: { label: tx('Core'), description: tx('Partners, products, organizations — required by everything') },
  accounting: { label: tx('Accounting'), description: tx('Chart of accounts, journals, general ledger, fiscal periods') },
  inventory: { label: tx('Inventory'), description: tx('Stock, locations, batches, serials, counts, valuation') },
  invoicing: { label: tx('Invoicing'), description: tx('Invoices, credit notes, vendor bills, payments, AR/AP') },
  procurement: { label: tx('Procurement'), description: tx('Purchase requests, orders, goods receipts, debit notes') },
  expenses: { label: tx('Expenses'), description: tx('Petty cash and operating expense tracking') },
  crm: { label: 'CRM', description: tx('Deals, pipeline, activity timeline, sales analytics') },
  manufacturing: { label: tx('Manufacturing'), description: tx('Bills of materials, production and work orders, MRP') },
  rental: { label: tx('Rental'), description: tx('Rentable units, reservations, agreements, returns, deposits') },
  repair: { label: tx('Repair'), description: tx('Repair orders, diagnosis, jobs, parts, warranties, contracts') },
  hr: { label: tx('HR & Payroll'), description: tx('Employees, attendance, timesheets, leave, payroll runs') },
  'fixed-asset': { label: tx('Fixed Assets'), description: tx('Asset register, depreciation, transfers, disposals') },
  beverage: { label: tx('Beverage Control'), description: tx('Bottle weighing, pour variance, bar shrinkage control') },
  task: { label: tx('Tasks'), description: tx('Task board, assignments, recurring checklists, verification') },
  backup: { label: tx('Backup'), description: tx('Scheduled database backups and restore points') },
  sync: { label: tx('Offline Sync'), description: tx('Device registry and pull/push data plane for offline clients') },
  communication: { label: tx('Communication'), description: tx('Staff chat, unified inbox, WhatsApp/Telegram, event-driven messaging') },
  school: { label: tx('School ERP'), description: tx('Students, enrollment, fee schedules, term invoicing') },
  kernel: { label: tx('Platform Kernel'), description: tx('Tenancy, auth, audit, events, workflow, approvals') },
};

/** Modules that are infrastructure rather than something a tenant switches off. */
const NON_TOGGLEABLE = new Set(['kernel', 'core', 'accounting', 'inventory', 'invoicing', 'sync']);

export function ModulesPage() {
  const qc = useQueryClient();
  const list = useQuery<OrgModule[]>({
    queryKey: ['org-modules'],
    queryFn: async () => (await api.get<OrgModule[]>('/feature-flags/modules')).data,
  });
  const catalog = useQuery<CatalogModule[]>({
    queryKey: ['org-modules-catalog'],
    queryFn: async () => (await api.get<CatalogModule[]>('/feature-flags/modules/catalog')).data,
  });
  const enable = useMutation({
    mutationFn: async (name: string) => await api.post(`/feature-flags/modules/${name}/enable`, { config: {} }),
    onSuccess: () => {
      notify.success(tx('Module enabled'));
      qc.invalidateQueries({ queryKey: ['org-modules'] });
    },
  });
  const disable = useMutation({
    mutationFn: async (name: string) => await api.patch(`/feature-flags/modules/${name}/disable`),
    onSuccess: () => {
      notify.success(tx('Module disabled'));
      qc.invalidateQueries({ queryKey: ['org-modules'] });
    },
  });

  const byName = new Map((list.data ?? []).map((m) => [m.moduleName, m]));
  const modules = (catalog.data ?? []).filter((m) => !NON_TOGGLEABLE.has(m.name));
  const loading = list.isLoading || catalog.isLoading;

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Modules')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Enable vertical apps for your organization. Each module is a thin layer that uses the core ERP — your data stays in one place. Only modules this server was started with can be enabled here.')}
        </p>
      </div>
      {loading && <Skeleton className="h-32 w-full" />}
      {!loading && modules.length === 0 && (
        <p className="text-sm text-muted-foreground">
          {tx('No optional modules are available on this server.')}
        </p>
      )}
      <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
        {modules.map((v) => {
          const meta = MODULE_META[v.name] ?? { label: v.name, description: '' };
          const m = byName.get(v.name);
          const active = !!m?.isActive;
          return (
            <Card key={v.name}>
              <CardHeader>
                <div className="flex items-start justify-between">
                  <div className="flex items-center gap-2">
                    <Boxes className="h-5 w-5 text-primary" />
                    <CardTitle className="text-base">{meta.label}</CardTitle>
                  </div>
                  <Badge variant={active ? 'default' : 'outline'}>
                    {active ? 'enabled' : 'disabled'}
                  </Badge>
                </div>
                <CardDescription>{meta.description}</CardDescription>
              </CardHeader>
              <CardContent>
                {active ? (
                  <Button size="sm" variant="outline" className="w-full" onClick={() => disable.mutate(v.name)}>
                    <PowerOff className="mr-2 h-3 w-3" />{tx('Disable')}
                  </Button>
                ) : (
                  <Button size="sm" className="w-full" onClick={() => enable.mutate(v.name)}>
                    <Power className="mr-2 h-3 w-3" />{tx('Enable')}
                  </Button>
                )}
              </CardContent>
            </Card>
          );
        })}
      </div>
    </div>
  );
}

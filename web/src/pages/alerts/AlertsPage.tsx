import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { BellRing, Download, RefreshCw } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, DateRangeFilter, FilterSelect, ListCard, ListPageHeader, ListToolbar } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { apiMessage, notify } from '@/lib/notify';
import {
  downloadAlerts, useAcknowledgeAlert, useAlertEvents, useAlertRules, useExceptionReport, useRunAlerts,
  useSaveAlertRule, type AlertEvent, type AlertRule, type ReportRow,
} from '@/features/alerts/api';

const SEVERITY: Record<string, 'destructive' | 'default' | 'secondary'> = {
  critical: 'destructive',
  warning: 'default',
  info: 'secondary',
};
const SELECT = 'h-9 w-full rounded-md border bg-background px-2 text-sm';

const severityLabel = (s: string) => (s === 'critical' ? tx('Critical') : s === 'warning' ? tx('Warning') : tx('Info'));
const repeatLabel = (r: string) => (r === 'daily' ? tx('Once a day') : r === 'weekly' ? tx('Once a week') : tx('Once a month'));
const channelLabel = (c: string) => (c === 'in_app' ? tx('In-app') : c === 'email' ? tx('Email') : tx('WhatsApp'));
const unitLabel = (u: AlertRule['thresholdUnit']) =>
  u === 'percent' ? '%' : u === 'days' ? tx('days') : u === 'hours' ? tx('hours') : '';

/** Alerts raised by the scheduled scans, the exception report and the settings per alert type (ADR-S021). */
export function AlertsPage() {
  const can = useAuthStore((s) => s.hasPermission);
  const canManage = can(PERMISSIONS.alerts.manage);
  const run = useRunAlerts();
  const onRun = () => run.mutate(undefined, {
    onSuccess: () => notify.success(tx('Scan finished')),
    onError: (e) => notify.error(apiMessage(e, tx('The scan failed'))),
  });
  return (
    <div className="space-y-4">
      <ListPageHeader icon={BellRing} title={tx('Alerts & Exceptions')}
        description={tx('Scans run on a schedule. Each subject alerts once per period, however often they run.')}
        actions={canManage ? (
          <Button variant="outline" disabled={run.isPending} onClick={onRun}>
            <RefreshCw className="mr-1.5 h-4 w-4" />{tx('Scan now')}
          </Button>
        ) : undefined} />
      <Tabs defaultValue="events">
        <TabsList>
          <TabsTrigger value="events">{tx('Alerts')}</TabsTrigger>
          <TabsTrigger value="report">{tx('Exception report')}</TabsTrigger>
          <TabsTrigger value="rules">{tx('Settings')}</TabsTrigger>
        </TabsList>
        <TabsContent value="events" className="mt-4 space-y-4"><EventsTab /></TabsContent>
        <TabsContent value="report" className="mt-4 space-y-4"><ReportTab /></TabsContent>
        <TabsContent value="rules" className="mt-4 space-y-4"><RulesTab canManage={canManage} /></TabsContent>
      </Tabs>
    </div>
  );
}

function EventsTab() {
  const [params] = useSearchParams();
  const [alertType, setAlertType] = useState(params.get('type') ?? '');
  const [severity, setSeverity] = useState('');
  const [status, setStatus] = useState('open');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const rules = useAlertRules();
  const filters = { alertType: alertType || undefined, severity: severity || undefined, status: status || undefined };
  const { data, isLoading, isError } = useAlertEvents({ ...filters, page, pageSize });
  const acknowledge = useAcknowledgeAlert();
  const labels = new Map((rules.data ?? []).map((r) => [r.alertType, r.label] as const));
  const onAck = (id: string) => acknowledge.mutate(id, {
    onError: (e) => notify.error(apiMessage(e, tx('The action failed'))),
  });
  const onExport = () => downloadAlerts(filters).catch((e) => notify.error(apiMessage(e, tx('Could not download the file'))));
  const columns: Column<AlertEvent>[] = [
    { key: 'raisedAt', header: tx('Raised'), render: (a) => dateTime(a.raisedAt) },
    { key: 'severity', header: tx('Severity'), render: (a) => <Badge variant={SEVERITY[a.severity]}>{severityLabel(a.severity)}</Badge> },
    { key: 'alertType', header: tx('Type'), render: (a) => labels.get(a.alertType) ?? a.alertType },
    {
      key: 'title', header: tx('Alert'), render: (a) => (
        <div className="min-w-[16rem]">
          {a.link ? <Link to={a.link} className="font-medium hover:underline">{a.title}</Link> : <span className="font-medium">{a.title}</span>}
          {a.body && <p className="text-xs text-muted-foreground">{a.body}</p>}
        </div>
      ),
    },
    {
      key: 'actions', header: '', render: (a) => (a.status === 'open' ? (
        <Button size="sm" variant="outline" disabled={acknowledge.isPending} onClick={() => onAck(a.id)}>{tx('Acknowledge')}</Button>
      ) : <span className="text-xs text-muted-foreground">{tx('Acknowledged')}</span>),
    },
  ];
  return (
    <>
      <ListToolbar>
        <FilterSelect value={alertType} onChange={(v) => { setAlertType(v); setPage(1); }} allLabel={tx('All types')} className="w-[220px]"
          options={(rules.data ?? []).map((r) => ({ value: r.alertType, label: r.label }))} />
        <FilterSelect value={severity} onChange={(v) => { setSeverity(v); setPage(1); }} allLabel={tx('All severities')}
          options={['critical', 'warning', 'info'].map((s) => ({ value: s, label: severityLabel(s) }))} />
        <FilterSelect value={status} onChange={(v) => { setStatus(v); setPage(1); }} allLabel={tx('All')}
          options={[{ value: 'open', label: tx('Open') }, { value: 'acknowledged', label: tx('Acknowledged') }]} />
        <Button size="sm" variant="outline" onClick={onExport}><Download className="mr-1.5 h-4 w-4" />{tx('Export CSV')}</Button>
      </ListToolbar>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} loadingRows={6} getRowId={(a) => a.id}
            cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the alerts. Try again.') : tx('No alerts here.')} />
        </div>
      </ListCard>
      {data?.meta && (
        <DataTablePagination page={data.meta.page} pageSize={pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
          onPageChange={setPage} onPageSizeChange={setPageSize} noun="alert" />
      )}
    </>
  );
}

function ReportTab() {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const { data, isLoading, isError } = useExceptionReport({ from: from || undefined, to: to || undefined });
  const rules = useAlertRules();
  const labels = new Map((rules.data ?? []).map((r) => [r.alertType, r.label] as const));
  const columns: Column<ReportRow>[] = [
    { key: 'alertType', header: tx('Type'), render: (r) => labels.get(r.alertType) ?? r.alertType },
    { key: 'total', header: tx('Total'), className: 'text-right', render: (r) => r.total },
    { key: 'critical', header: tx('Critical'), className: 'text-right', render: (r) => r.critical },
    { key: 'open', header: tx('Not acknowledged'), className: 'text-right', render: (r) => r.open },
    { key: 'lastRaisedAt', header: tx('Last raised'), render: (r) => dateTime(r.lastRaisedAt) },
  ];
  return (
    <>
      <ListToolbar>
        <DateRangeFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t); }} />
      </ListToolbar>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={data?.rows ?? []} loading={isLoading} loadingRows={4} getRowId={(r) => r.alertType}
            cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the report. Try again.') : tx('No exceptions in this period.')} />
        </div>
      </ListCard>
    </>
  );
}

function RulesTab({ canManage }: { canManage: boolean }) {
  const { data, isLoading, isError } = useAlertRules();
  const [editing, setEditing] = useState<AlertRule | null>(null);
  if (isLoading) return <p className="text-muted-foreground">{tx('Loading…')}</p>;
  if (isError) return <p className="text-destructive">{tx('Could not load the settings.')}</p>;
  return (
    <div className="grid gap-3 md:grid-cols-2">
      {(data ?? []).map((r) => (
        <Card key={r.alertType}>
          <CardContent className="space-y-2 p-4">
            <div className="flex items-center justify-between gap-2">
              <p className="font-medium">{r.label}</p>
              <Badge variant={r.enabled ? 'default' : 'secondary'}>{r.enabled ? tx('On') : tx('Off')}</Badge>
            </div>
            {r.thresholdHelp && <p className="text-xs text-muted-foreground">{r.thresholdHelp}</p>}
            <p className="text-sm">
              {r.thresholdUnit && <span className="mr-2 font-medium">{`${Number(r.threshold)} ${unitLabel(r.thresholdUnit)}`}</span>}
              {repeatLabel(r.repeatEvery)}
              {' · '}
              {r.channels.length ? r.channels.map(channelLabel).join(', ') : tx('List only')}
            </p>
            {canManage && <Button size="sm" variant="outline" onClick={() => setEditing(r)}>{tx('Edit')}</Button>}
          </CardContent>
        </Card>
      ))}
      {editing && <RuleDialog rule={editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function RuleDialog({ rule, onClose }: { rule: AlertRule; onClose: () => void }) {
  const [enabled, setEnabled] = useState(rule.enabled);
  const [threshold, setThreshold] = useState(rule.threshold === null ? '' : String(rule.threshold));
  const [repeatEvery, setRepeatEvery] = useState<string>(rule.repeatEvery);
  const [channels, setChannels] = useState<string[]>(rule.channels);
  const [permission, setPermission] = useState(rule.recipientPermission);
  const save = useSaveAlertRule();
  const invalid = rule.thresholdUnit !== null && (threshold === '' || Number(threshold) < 0);
  const toggle = (c: string) => setChannels((list) => (list.includes(c) ? list.filter((x) => x !== c) : [...list, c]));
  const onSave = () => save.mutate({
    type: rule.alertType,
    body: {
      enabled,
      threshold: rule.thresholdUnit === null ? null : Number(threshold),
      repeatEvery,
      channels,
      recipientPermission: permission.trim() || null,
      recipientUserIds: rule.recipientUserIds,
    },
  }, {
    onSuccess: () => { notify.success(tx('Saved')); onClose(); },
    onError: (e) => notify.error(apiMessage(e, tx('Could not save'))),
  });
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{rule.label}</DialogTitle>
          {rule.thresholdHelp && <DialogDescription>{rule.thresholdHelp}</DialogDescription>}
        </DialogHeader>
        <div className="space-y-3">
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
            {tx('This alert is on')}
          </label>
          {rule.thresholdUnit && (
            <div className="space-y-1">
              <Label>{tx('Threshold')} ({unitLabel(rule.thresholdUnit)})</Label>
              <Input type="number" min={0} value={threshold} onChange={(e) => setThreshold(e.target.value)} />
            </div>
          )}
          <div className="space-y-1">
            <Label>{tx('Alert the same subject')}</Label>
            <select className={SELECT} value={repeatEvery} onChange={(e) => setRepeatEvery(e.target.value)}>
              {['daily', 'weekly', 'monthly'].map((r) => <option key={r} value={r}>{repeatLabel(r)}</option>)}
            </select>
          </div>
          <div className="space-y-1">
            <Label>{tx('Channels')}</Label>
            <div className="flex flex-wrap gap-4">
              {['in_app', 'email', 'whatsapp'].map((c) => (
                <label key={c} className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={channels.includes(c)} onChange={() => toggle(c)} />
                  {channelLabel(c)}
                </label>
              ))}
            </div>
            <p className="text-xs text-muted-foreground">{tx('WhatsApp reaches users whose email matches a contact with a WhatsApp identity.')}</p>
          </div>
          <div className="space-y-1">
            <Label>{tx('Users holding this permission receive it')}</Label>
            <Input value={permission} onChange={(e) => setPermission(e.target.value)} placeholder="alerts:read" />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={invalid || save.isPending} onClick={onSave}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

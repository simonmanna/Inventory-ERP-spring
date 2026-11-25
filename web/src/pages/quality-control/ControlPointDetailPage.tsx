import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Edit, Plus, Power, RefreshCw } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  CHECK_TYPE_LABEL, CONTROL_PER_LABEL, FREQUENCY_LABEL, OPERATION_LABEL, RECEIPT_MODE_LABEL, SPC_SUBGROUP_LABEL,
  useControlPoint, useQcChecks, useSetPointActive,
} from '@/features/quality-control/api';
import { ActivePill, CheckStatusPill, Field, Item } from './qc-ui';
import { tolerance } from './qc-format';
import { ControlPointDialog } from './ControlPointsPage';
import { ManualCheckDialog } from './ChecksPage';

/** Control point detail (ADR-S087 §1): configuration, version history, documents, recent checks. */
export function ControlPointDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: p, isLoading, isError, refetch } = useControlPoint(id);
  const checks = useQcChecks({ page: 1, pageSize: 10, controlPointId: id });
  const has = useAuthStore((s) => s.hasPermission);
  const [editing, setEditing] = useState(false);
  const [toggling, setToggling] = useState(false);
  const [newCheck, setNewCheck] = useState(false);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !p) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Control point not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const canConfigure = has(PERMISSIONS.qualityControl.configure);
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          <Button variant="ghost" size="icon" onClick={() => navigate('/quality-control/control-points')} aria-label={tx('Back')}><ArrowLeft className="h-4 w-4" /></Button>
          <div>
            <h1 className="flex flex-wrap items-center gap-2 text-xl font-semibold">{p.reference} <ActivePill active={p.isActive} /> <span className="text-sm font-normal text-muted-foreground">v{p.version}</span></h1>
            <p className="text-sm text-muted-foreground">{p.title}</p>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          {p.isActive && has(PERMISSIONS.qualityControl.check) && <Button variant="outline" onClick={() => setNewCheck(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('Manual check')}</Button>}
          {canConfigure && <Button variant="outline" onClick={() => setToggling(true)}><Power className="mr-1.5 h-4 w-4" /> {p.isActive ? tx('Deactivate') : tx('Activate')}</Button>}
          {canConfigure && <Button onClick={() => setEditing(true)}><Edit className="mr-1.5 h-4 w-4" /> {tx('Edit')}</Button>}
        </div>
      </div>
      {p.note && <div className="rounded-lg border bg-muted/40 p-3 text-sm">{p.note}</div>}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Configuration')}</CardTitle></CardHeader>
            <CardContent>
              <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                <Item label={tx('Operation')}>{OPERATION_LABEL[p.operation]}</Item>
                <Item label={tx('Type')}>{CHECK_TYPE_LABEL[p.checkType]}</Item>
                <Item label={tx('Products')}>{p.productIds.length ? tx('{{n}} selected', { n: p.productIds.length }) : tx('All')}</Item>
                {p.checkType === 'measure' && <Item label={tx('Tolerance')}>{tolerance(p.toleranceMin, p.toleranceMax, p.unit)}</Item>}
                {p.checkType === 'measure' && <Item label={tx('Norm')}>{p.norm != null ? `${p.norm}${p.unit ? ` ${p.unit}` : ''}` : '—'}</Item>}
                {p.checkType === 'worksheet' && <Item label={tx('Worksheet')}>{p.worksheetTemplateName}</Item>}
                <Item label={tx('Control per')}>{CONTROL_PER_LABEL[p.controlPer]}{p.controlPer === 'quantity' && p.testQtyPct ? ` · ${p.testQtyPct}%` : ''}</Item>
                <Item label={tx('Frequency')}>{FREQUENCY_LABEL[p.frequency]}{p.frequency === 'random' ? ` · ${p.randomPct}%` : p.frequency === 'periodic' ? ` · ${p.periodValue} ${p.periodUnit}` : ''}</Item>
                <Item label={tx('Blocks')}>{p.blocking ? (p.blockOnFailure ? tx('Until passed') : tx('Until done')) : p.blockOnFailure ? tx('When failed') : tx('No')}</Item>
                {p.operation === 'receipt' && <Item label={tx('Receipt mode')}>{p.receiptMode ? RECEIPT_MODE_LABEL[p.receiptMode] : '—'}</Item>}
                {p.operation === 'receipt' && <Item label={tx('Supplier')}>{p.supplierName ?? tx('Any')}</Item>}
                {p.operation === 'work_order' && <Item label={tx('Routing operation')}>{p.routingOperationName ?? tx('Any')}</Item>}
                <Item label={tx('Team')}>{p.teamName}</Item>
                <Item label={tx('Responsible')}>{p.responsibleName || '—'}</Item>
                <Item label={tx('Quarantine location')}>{p.failureLocationName ?? tx('Default (QC-QUARANTINE)')}</Item>
                <Item label={tx('Failure class required')}>{p.failureClassRequired ? tx('Yes') : tx('No')}</Item>
                <Item label={tx('Alert on failure')}>{p.autoAlert ? tx('Yes') : tx('No')}</Item>
                {p.checkType === 'measure' && <Item label={tx('Chart subgroup')}>{SPC_SUBGROUP_LABEL[p.spcSubgroup] ?? p.spcSubgroup}</Item>}
              </dl>
              {p.instructions && <p className="mt-4 whitespace-pre-wrap text-sm">{p.instructions}</p>}
              {p.failureMessage && <p className="mt-2 whitespace-pre-wrap text-sm text-muted-foreground"><span className="font-medium">{tx('If it fails:')}</span> {p.failureMessage}</p>}
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Recent checks')}</CardTitle></CardHeader>
            <CardContent className="space-y-1">
              {!checks.data?.data.length && <p className="py-4 text-center text-sm text-muted-foreground">{tx('No checks yet.')}</p>}
              {checks.data?.data.map((c) => (
                <button key={c.id} type="button" onClick={() => navigate(`/quality-control/checks/${c.id}`)}
                  className="flex w-full items-center justify-between rounded-md px-2 py-1.5 text-left text-sm hover:bg-muted/50">
                  <span><span className="font-medium text-primary">{c.checkNumber}</span> <span className="text-muted-foreground">{c.productName ?? ''} {c.sourceRef ?? ''}</span></span>
                  <CheckStatusPill status={c.status} />
                </button>
              ))}
            </CardContent>
          </Card>
        </div>
        <div className="space-y-4">
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-base">{tx('Versions')}</CardTitle></CardHeader>
            <CardContent>
              <ol className="space-y-2 text-sm">
                {(p.versions ?? []).map((v) => (
                  <li key={v.id} className="border-l-2 pl-3">
                    <div className="font-medium">v{v.version}</div>
                    <div className="text-xs text-muted-foreground">{dateTime(v.changedAt)}{v.changedByName ? ` · ${v.changedByName}` : ''}</div>
                    {v.reason && <div className="text-muted-foreground">“{v.reason}”</div>}
                  </li>
                ))}
              </ol>
            </CardContent>
          </Card>
          <AttachmentPanel resType="qc_control_point" resId={p.id} title={tx('Instructions & documents')} writePermission={PERMISSIONS.qualityControl.configure} />
        </div>
      </div>
      {editing && <ControlPointDialog point={p} onClose={() => setEditing(false)} />}
      {toggling && <ToggleDialog id={p.id} active={!p.isActive} onClose={() => setToggling(false)} />}
      {newCheck && <ManualCheckDialog controlPointId={p.id} onClose={() => setNewCheck(false)} onSaved={(c) => navigate(`/quality-control/checks/${c.id}`)} />}
    </div>
  );
}

function ToggleDialog({ id, active, onClose }: { id: string; active: boolean; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const toggle = useSetPointActive();
  const submit = async () => {
    try {
      await toggle.mutateAsync({ id, active, reason: reason.trim() || undefined });
      notify.success(active ? tx('Control point activated') : tx('Control point deactivated'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not change the control point')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{active ? tx('Activate the control point') : tx('Deactivate the control point')}</DialogTitle>
          <DialogDescription>{active ? tx('New checks will be created on matching operations.') : tx('No new checks are created. Checks already created stay valid; pending ones still block until done or waived.')}</DialogDescription>
        </DialogHeader>
        <Field label={tx('Reason')}><Input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={toggle.isPending}>{active ? tx('Activate') : tx('Deactivate')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

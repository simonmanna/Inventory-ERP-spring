import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { AlertTriangle, DoorOpen, Plus, Printer, Ban } from 'lucide-react';
import { toast } from 'sonner';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import {
  BEARER_LABELS, WARNING_LABELS, gatePassApi, useGatePassMutation, useGatePassSource,
  type BearerType, type GatePass, type GatePassSourceType, type GatePassState, type SourceView,
} from './api';

const STATE_STYLE: Record<GatePassState, string> = {
  issued: 'bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-200',
  used: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200',
  void: 'bg-muted text-muted-foreground line-through',
  expired: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200',
};

export const STATE_LABELS: Record<GatePassState, string> = {
  issued: tx('Issued'),
  used: tx('Gated out'),
  void: tx('Void'),
  expired: tx('Expired'),
};

export function GatePassStateBadge({ state }: { state: GatePassState }) {
  return <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium', STATE_STYLE[state])}>{STATE_LABELS[state]}</span>;
}

/** Open the printable pass in a new tab (every print is counted; reprints say COPY n). */
export async function printGatePass(id: string, onDone?: () => void) {
  try {
    const r = await api.get(`/gate-passes/${id}/pdf`, { responseType: 'blob' });
    const url = URL.createObjectURL(r.data as Blob);
    const w = window.open(url, '_blank');
    if (!w) toast.error(tx('Allow pop-ups to print the gate pass'));
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
    onDone?.();
  } catch (e) {
    toast.error(apiErrorMessage(e, tx('Could not print the gate pass')));
  }
}

export function WarningList({ warnings, className }: { warnings: string[]; className?: string }) {
  if (!warnings.length) return null;
  return (
    <div className={cn('space-y-1', className)}>
      {warnings.map((w) => (
        <p key={w} className="flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 px-2.5 py-1.5 text-xs font-medium text-amber-900 dark:border-amber-700 dark:bg-amber-950/40 dark:text-amber-200">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />{WARNING_LABELS[w] ?? w}
        </p>
      ))}
    </div>
  );
}

/**
 * Gate passes of one source document (delivery, order, invoice, cash sale, transfer, pick order, rental, repair,
 * supplier return): the passes issued so far with print / void, and "Issue gate pass". Renders nothing for a user
 * without gate pass access.
 */
export function GatePassPanel({ sourceType, sourceId, compact = false, className }: {
  sourceType: GatePassSourceType;
  sourceId: string | null | undefined;
  compact?: boolean;
  className?: string;
}) {
  const permissions = useAuthStore((s) => s.permissions);
  const canRead = permissions.includes(PERMISSIONS.gatePass.read) || permissions.includes(PERMISSIONS.gatePass.issue);
  const canIssue = permissions.includes(PERMISSIONS.gatePass.issue);
  const canVoid = permissions.includes(PERMISSIONS.gatePass.void);
  const view = useGatePassSource(sourceType, sourceId, canRead);
  const [issuing, setIssuing] = useState(false);
  const [voiding, setVoiding] = useState<GatePass | null>(null);
  if (!canRead || !sourceId) return null;
  const passes = view.data?.passes ?? [];
  const left = (view.data?.lines ?? []).some((l) => l.remaining > 0);
  const cancelled = view.data?.source.cancelled ?? false;
  return (
    <div className={cn('rounded-lg border bg-card p-3', className)}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="flex items-center gap-2 text-sm font-semibold"><DoorOpen className="h-4 w-4" />{tx('Gate pass')}</p>
        {canIssue && (
          <Button size="sm" variant="outline" disabled={!view.data || cancelled || !left} onClick={() => setIssuing(true)}
            title={cancelled ? tx('The document is cancelled') : !left ? tx('Every item is already on a gate pass') : undefined}>
            <Plus className="mr-1 h-4 w-4" />{tx('Issue gate pass')}
          </Button>
        )}
      </div>
      {view.isError && <p className="mt-2 text-xs text-destructive">{apiErrorMessage(view.error, tx('Could not load gate passes'))}</p>}
      {!compact && view.data && passes.length === 0 && (
        <p className="mt-2 text-xs text-muted-foreground">{tx('No gate pass yet. Issue one for the driver or person taking the goods out.')}</p>
      )}
      {passes.length > 0 && (
        <ul className="mt-2 divide-y text-sm">
          {passes.map((p) => (
            <li key={p.id} className="flex flex-wrap items-center justify-between gap-2 py-1.5">
              <div className="min-w-0">
                <p className="flex flex-wrap items-center gap-2 font-medium">{p.number}<GatePassStateBadge state={p.state} />
                  {p.warnings.length > 0 && p.state === 'issued' && <AlertTriangle className="h-3.5 w-3.5 text-amber-600" aria-label={tx('Has warnings')} />}
                </p>
                <p className="truncate text-xs text-muted-foreground">
                  {[p.bearerName, p.vehiclePlate, p.state === 'used' ? `${tx('out')} ${dateTime(p.gateOutAt)}` : `${tx('valid until')} ${dateTime(p.validUntil)}`].filter(Boolean).join(' · ')}
                </p>
              </div>
              <div className="flex gap-1">
                <Button size="sm" variant="ghost" onClick={() => printGatePass(p.id, () => view.refetch())}><Printer className="mr-1 h-4 w-4" />{tx('Print')}</Button>
                {canVoid && p.status === 'issued' && (
                  <Button size="sm" variant="ghost" className="text-destructive" onClick={() => setVoiding(p)}><Ban className="mr-1 h-4 w-4" />{tx('Void')}</Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      {issuing && view.data && (
        <IssueGatePassDialog view={view.data} onClose={() => setIssuing(false)} />
      )}
      {voiding && <VoidGatePassDialog pass={voiding} onClose={() => setVoiding(null)} />}
    </div>
  );
}

function IssueGatePassDialog({ view, onClose }: { view: SourceView; onClose: () => void }) {
  const pf = view.prefill;
  const [bearerType, setBearerType] = useState<BearerType>(pf.bearerType);
  const [f, setF] = useState({
    bearerName: pf.bearerName ?? '', bearerIdNumber: pf.bearerIdNumber ?? '', bearerPhone: pf.bearerPhone ?? '',
    vehiclePlate: pf.vehiclePlate ?? '', vehicleType: pf.vehicleType ?? '', transportCompany: pf.transportCompany ?? '',
    destination: pf.destination ?? '', purpose: '', remarks: '', validHours: 24,
  });
  const open = useMemo(() => view.lines.filter((l) => l.remaining > 0), [view.lines]);
  const [qty, setQty] = useState<Record<string, string>>({});
  useEffect(() => setQty(Object.fromEntries(open.map((l) => [l.sourceLineId, String(l.remaining)]))), [open]);
  const issue = useGatePassMutation(gatePassApi.issue);
  const set = (k: keyof typeof f, v: string | number) => setF((x) => ({ ...x, [k]: v }));
  const lines = open.map((l) => ({ sourceLineId: l.sourceLineId, quantity: Number(qty[l.sourceLineId] ?? 0) }))
    .filter((l) => l.quantity > 0);
  const tooMuch = open.some((l) => Number(qty[l.sourceLineId] ?? 0) > l.remaining);
  const needsPlate = bearerType === 'driver' && !f.vehiclePlate.trim();
  const submit = () => issue.mutate({
    sourceType: view.source.type, sourceId: view.source.id, bearerType, bearerName: f.bearerName.trim(),
    bearerIdNumber: f.bearerIdNumber || null, bearerPhone: f.bearerPhone || null, vehiclePlate: f.vehiclePlate || null,
    vehicleType: f.vehicleType || null, transportCompany: f.transportCompany || null, destination: f.destination || null,
    purpose: f.purpose || null, remarks: f.remarks || null, validHours: Number(f.validHours) || 24, lines,
  }, {
    onSuccess: (p) => {
      toast.success(`${tx('Gate pass')} ${p.number}`);
      onClose();
      void printGatePass(p.id);
    },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not issue the gate pass'))),
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{tx('Issue gate pass')} · {view.source.label} {view.source.number}</DialogTitle>
        </DialogHeader>
        <WarningList warnings={view.warnings} />
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="sm:col-span-2">
            <Label>{tx('Who takes the goods out')}</Label>
            <div className="mt-1 flex flex-wrap gap-1.5">
              {(Object.keys(BEARER_LABELS) as BearerType[]).map((b) => (
                <button key={b} type="button" onClick={() => setBearerType(b)}
                  className={cn('rounded-full border px-3 py-1 text-xs', bearerType === b ? 'border-primary bg-primary/10 text-primary' : 'hover:bg-muted')}>
                  {BEARER_LABELS[b]}
                </button>
              ))}
            </div>
          </div>
          <Field label={bearerType === 'driver' ? tx('Driver name') : tx('Name')} required>
            <Input value={f.bearerName} onChange={(e) => set('bearerName', e.target.value)} maxLength={120} />
          </Field>
          <Field label={bearerType === 'driver' ? tx('Driving licence no.') : tx('ID number')}>
            <Input value={f.bearerIdNumber} onChange={(e) => set('bearerIdNumber', e.target.value)} maxLength={60} />
          </Field>
          <Field label={tx('Phone')}><Input value={f.bearerPhone} onChange={(e) => set('bearerPhone', e.target.value)} maxLength={40} /></Field>
          <Field label={tx('Vehicle plate')} required={bearerType === 'driver'}>
            <Input value={f.vehiclePlate} onChange={(e) => set('vehiclePlate', e.target.value.toUpperCase())} maxLength={30} />
          </Field>
          <Field label={tx('Vehicle type')}><Input value={f.vehicleType} onChange={(e) => set('vehicleType', e.target.value)} maxLength={60} placeholder={tx('e.g. tipper, pickup, motorcycle')} /></Field>
          <Field label={tx('Transport company')}><Input value={f.transportCompany} onChange={(e) => set('transportCompany', e.target.value)} maxLength={120} /></Field>
          <Field label={tx('Destination')}><Input value={f.destination} onChange={(e) => set('destination', e.target.value)} maxLength={300} /></Field>
          <Field label={tx('Valid for (hours)')}><Input type="number" min={1} max={168} value={f.validHours} onChange={(e) => set('validHours', e.target.value)} /></Field>
          <Field label={tx('Purpose')} className="sm:col-span-2"><Input value={f.purpose} onChange={(e) => set('purpose', e.target.value)} maxLength={300} /></Field>
        </div>
        <div>
          <Label>{tx('Items on this pass')}</Label>
          <div className="mt-1 overflow-x-auto rounded-md border">
            <table className="w-full text-sm">
              <thead className="bg-muted/50 text-xs text-muted-foreground">
                <tr><th className="px-2 py-1.5 text-left">{tx('Item')}</th><th className="px-2 py-1.5 text-right">{tx('Left to pass')}</th><th className="w-32 px-2 py-1.5 text-right">{tx('On this pass')}</th></tr>
              </thead>
              <tbody>
                {open.map((l) => {
                  const over = Number(qty[l.sourceLineId] ?? 0) > l.remaining;
                  return (
                    <tr key={l.sourceLineId} className="border-t">
                      <td className="px-2 py-1.5">{l.description}</td>
                      <td className="px-2 py-1.5 text-right tabular-nums">{l.remaining} {l.unit ?? ''}</td>
                      <td className="px-2 py-1">
                        <Input type="number" min={0} max={l.remaining} step="any" value={qty[l.sourceLineId] ?? ''} aria-invalid={over}
                          className={cn('h-8 text-right', over && 'border-destructive')}
                          onChange={(e) => setQty((q) => ({ ...q, [l.sourceLineId]: e.target.value }))} />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <p className="mt-1 text-xs text-muted-foreground">{tx('Set 0 to leave an item off. Items already on another pass are not offered again.')}</p>
        </div>
        <Field label={tx('Remarks')}><Textarea rows={2} value={f.remarks} onChange={(e) => set('remarks', e.target.value)} maxLength={1000} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!f.bearerName.trim() || needsPlate || tooMuch || lines.length === 0 || issue.isPending} onClick={submit}>
            <Printer className="mr-1 h-4 w-4" />{tx('Issue & print')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function VoidGatePassDialog({ pass, onClose }: { pass: GatePass; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const voidPass = useGatePassMutation(gatePassApi.void);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Void gate pass')} {pass.number}</DialogTitle></DialogHeader>
        <p className="text-sm text-muted-foreground">{tx('A void pass can no longer be used at the gate. Its items can go on a new pass.')}</p>
        <Field label={tx('Reason')} required><Textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button variant="destructive" disabled={!reason.trim() || voidPass.isPending} onClick={() => voidPass.mutate({ id: pass.id, reason: reason.trim() }, {
            onSuccess: () => { toast.success(tx('Gate pass voided')); onClose(); },
            onError: (e) => toast.error(apiErrorMessage(e, tx('Could not void the gate pass'))),
          })}>{tx('Void')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function Field({ label, required, className, children }: { label: string; required?: boolean; className?: string; children: ReactNode }) {
  return (
    <div className={className}>
      <Label className="text-xs">{label}{required && <span className="text-destructive"> *</span>}</Label>
      <div className="mt-1">{children}</div>
    </div>
  );
}


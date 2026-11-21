/**
 * ADR-S083 O2 — sync state of a registered POS device: work recorded offline, its provisional and final numbers, and
 * anything the server refused (a supervisor resolves those under Sync dead letters). Renders nothing on a browser
 * that is not registered as a sync device.
 */
import { useState } from 'react';
import { CloudUpload, AlertTriangle, ShieldOff } from 'lucide-react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { usePosSync } from '@/features/pos/use-pos-sync';
import { attentionOps } from '@/features/pos/pos-ops';
import type { SyncOp } from '@/lib/offline/types';
import { tx } from '@/lib/i18n/tx';

const LABELS: Record<string, string> = {
  'sale.checkout': 'Sale',
  'tab.settle': 'Tab settled',
  'cash_session.open': 'Shift opened',
  'cash_session.close': 'Shift closed',
  'cash_session.movement': 'Cash movement',
  'cash_drawer.open': 'Drawer opened',
  'customer.upsert': 'Customer',
};

const STATE_TEXT: Record<string, string> = {
  token_expired: 'This device needs to be registered again. Queued work is still sent.',
  revoked: 'This device was revoked. Unsynced work was exported for a supervisor.',
  upgrade_required: 'Reload the app to update it. Queued work is still sent.',
  unregistered: 'This device is not registered for offline use.',
};

function numberOf(op: SyncOp): string {
  const p = op.payload as { provisionalNumber?: string };
  const final = op.result?.finalNumbers as { invoiceNumber?: string } | undefined;
  if (final?.invoiceNumber) return p.provisionalNumber ? `${p.provisionalNumber} → ${final.invoiceNumber}` : final.invoiceNumber;
  return p.provisionalNumber ?? '';
}

export default function DeviceSyncBadge() {
  const { registered, status, ops, syncNow } = usePosSync();
  const [open, setOpen] = useState(false);
  if (!registered) return null;
  const { pending, rejected, held } = attentionOps(ops);
  const recent = [...ops].sort((a, b) => b.deviceSeq - a.deviceSeq).slice(0, 50);
  const warn = rejected.length + held.length > 0 || (status && STATE_TEXT[status.state]);
  if (pending.length === 0 && !warn && !open) {
    return null;
  }
  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className={`inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium ${
          warn ? 'bg-red-100 text-red-800' : 'bg-amber-100 text-amber-800'}`}
        title={tx('Offline work on this device')}
      >
        {warn ? <AlertTriangle className="h-3.5 w-3.5" /> : <CloudUpload className="h-3.5 w-3.5" />}
        {pending.length > 0 && tx('{{n}} to sync', { n: pending.length })}
        {rejected.length + held.length > 0 && ` · ${tx('{{n}} refused', { n: rejected.length + held.length })}`}
        {status?.state === 'revoked' && <ShieldOff className="h-3.5 w-3.5" />}
      </button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>{tx('Offline work on this device')}</DialogTitle>
          </DialogHeader>
          {status && STATE_TEXT[status.state] && (
            <p className="rounded bg-red-50 p-2 text-sm text-red-800">{tx(STATE_TEXT[status.state])}</p>
          )}
          {rejected.length + held.length > 0 && (
            <p className="rounded bg-amber-50 p-2 text-sm text-amber-900">
              {tx('The server refused some work. Nothing is retried automatically: a supervisor resolves it under POS → Sync dead letters.')}
            </p>
          )}
          <div className="max-h-96 overflow-y-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-muted-foreground">
                  <th className="py-1">{tx('What')}</th>
                  <th>{tx('Number')}</th>
                  <th>{tx('Recorded')}</th>
                  <th>{tx('Status')}</th>
                </tr>
              </thead>
              <tbody>
                {recent.map((o) => (
                  <tr key={o.opId} className="border-t">
                    <td className="py-1">{tx(LABELS[o.type] ?? o.type)}</td>
                    <td className="font-mono text-xs">{numberOf(o)}</td>
                    <td className="text-xs">{new Date(o.occurredAt).toLocaleString()}</td>
                    <td className="text-xs" title={o.lastError ?? ''}>
                      {o.status === 'applied' ? tx('Posted') : o.status === 'rejected' ? tx('Refused') : o.status === 'held'
                        ? tx('Held for a supervisor') : tx('Waiting')}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex justify-end">
            <button type="button" className="rounded-md border px-3 py-1 text-sm" onClick={() => void syncNow()}>
              {tx('Sync now')}
            </button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}

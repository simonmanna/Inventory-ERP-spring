/**
 * ADR-S083 O2 — closing a shift while the server is unreachable, on a registered device.
 *
 * The cashier counts the drawer and enters each tracked tender account's balance (the synced payment methods say
 * which). The close is recorded as a `cash_session.close` op; expected cash, the variance and every close rule are
 * worked out by the server when it syncs. A close the server would need a manager for is refused at sync and lands
 * under Sync dead letters for a supervisor — the shift stays open on the server until then.
 */
import { useEffect, useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { toast } from 'sonner';
import { posEngine } from '@/features/pos/pos-sync';
import type { CashSession } from './types';
import { useCloseShift } from './api';
import { tx } from '@/lib/i18n/tx';

interface Tracked { accountId: string; label: string }

interface Props {
  open: boolean;
  session: CashSession | null;
  onClose: () => void;
  onClosed: () => void;
}

export function OfflineShiftCloseDialog({ open, session, onClose, onClosed }: Props) {
  const closeShift = useCloseShift();
  const [registered, setRegistered] = useState<boolean | null>(null);
  const [tracked, setTracked] = useState<Tracked[]>([]);
  const [counted, setCounted] = useState('');
  const [accounts, setAccounts] = useState<Record<string, string>>({});
  const [notes, setNotes] = useState('');
  const [varianceReason, setVarianceReason] = useState('');

  useEffect(() => {
    if (!open) return;
    void (async () => {
      const engine = await posEngine();
      setRegistered(!!engine);
      if (!engine) return;
      const methods = await engine.store.getRows<{ accountId?: string | null; label?: string; trackInShift?: boolean;
        kind?: string; isActive?: boolean }>('paymentMethods');
      const seen = new Map<string, Tracked>();
      for (const m of methods) {
        if (m.trackInShift && m.accountId && m.isActive !== false && m.kind !== 'cash' && !seen.has(m.accountId)) {
          seen.set(m.accountId, { accountId: m.accountId, label: m.label ?? m.accountId });
        }
      }
      setTracked([...seen.values()]);
    })();
  }, [open]);

  const countedValid = counted.trim() !== '' && Number.isFinite(Number(counted)) && Number(counted) >= 0;
  const accountsValid = tracked.every((t) => accounts[t.accountId]?.trim() && Number.isFinite(Number(accounts[t.accountId])));

  const submit = async () => {
    if (!session) return;
    try {
      const closingAccounts = Object.fromEntries(tracked.map((t) => [t.accountId, Number(accounts[t.accountId])]));
      await closeShift.mutateAsync({
        sessionId: session.id,
        closingCounted: Number(counted),
        closingAccounts: tracked.length ? closingAccounts : undefined,
        notes: notes.trim() || undefined,
        varianceReason: varianceReason.trim() || undefined,
      });
      toast.warning(tx('Shift close recorded offline. The server works out expected cash and the variance when it syncs.'));
      onClosed();
      onClose();
    } catch (e: any) {
      toast.error(e?.response?.data?.message || e?.message || tx('Could not record the close'));
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{tx('Close shift (offline)')}</DialogTitle>
          <DialogDescription>
            {tx('The server cannot be reached. Count the drawer now; expected cash and the variance are worked out when this device syncs. A close that needs a manager goes to a supervisor.')}
          </DialogDescription>
        </DialogHeader>
        {registered === false ? (
          <p className="text-sm text-red-700">{tx('This device is not registered for offline use. Reconnect to close the shift.')}</p>
        ) : (
          <div className="space-y-3">
            <div>
              <Label htmlFor="offline-counted">{tx('Cash counted in the drawer')}</Label>
              <Input id="offline-counted" inputMode="decimal" value={counted} onChange={(e) => setCounted(e.target.value)} />
            </div>
            {tracked.map((t) => (
              <div key={t.accountId}>
                <Label htmlFor={`acct-${t.accountId}`}>{tx('{{label}} balance', { label: t.label })}</Label>
                <Input id={`acct-${t.accountId}`} inputMode="decimal" value={accounts[t.accountId] ?? ''}
                  onChange={(e) => setAccounts((a) => ({ ...a, [t.accountId]: e.target.value }))} />
              </div>
            ))}
            <div>
              <Label htmlFor="offline-variance">{tx('Reason for any difference (optional)')}</Label>
              <Input id="offline-variance" value={varianceReason} onChange={(e) => setVarianceReason(e.target.value)} />
            </div>
            <div>
              <Label htmlFor="offline-notes">{tx('Notes')}</Label>
              <Textarea id="offline-notes" value={notes} onChange={(e) => setNotes(e.target.value)} />
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={onClose}>{tx('Cancel')}</Button>
              <Button onClick={submit} disabled={!session || !countedValid || !accountsValid || closeShift.isPending}>
                {tx('Record close')}
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

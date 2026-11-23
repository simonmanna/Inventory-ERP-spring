import { useEffect, useRef, useState } from 'react';
import { PERMISSIONS } from '@erp/shared';
import { useAuthStore } from '@/stores/auth.store';
import { CheckCircle2, DoorOpen, Printer, ScanLine, ShieldX, XCircle } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { ListPageHeader } from '@/components/list';
import { apiErrorMessage } from '@/lib/api-error';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  BEARER_LABELS, PROBLEM_LABELS, SOURCE_LABELS, gatePassApi, useGatePassMutation, useGatePasses, type GatePassDetail,
} from '@/features/gate-pass/api';
import { Field, GatePassStateBadge, WarningList, printGatePass } from '@/features/gate-pass/GatePassPanel';

/**
 * Gate / security screen: scan the QR on the pass (a USB or phone scanner types it into the box) or type the pass
 * number, check the pass, the bearer, the vehicle and the load, then let out or refuse. Gate-out marks the delivery,
 * handover or cash sale as dispatched where that applies.
 */
export function GateCheckPage() {
  const input = useRef<HTMLInputElement>(null);
  const [code, setCode] = useState('');
  const [pass, setPass] = useState<GatePassDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [plate, setPlate] = useState('');
  const [note, setNote] = useState('');
  const [refusing, setRefusing] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const gateOut = useGatePassMutation(gatePassApi.gateOut);
  const refuse = useGatePassMutation(gatePassApi.refuse);
  const recent = useGatePasses({ status: 'used', pageSize: 10 });
  const canPrint = useAuthStore((s) => s.permissions.includes(PERMISSIONS.gatePass.read) || s.permissions.includes(PERMISSIONS.gatePass.issue));

  useEffect(() => input.current?.focus(), []);

  const reset = () => {
    setPass(null); setCode(''); setPlate(''); setNote(''); setRefusing(false); setReason(''); setError(null);
    setTimeout(() => input.current?.focus(), 0);
  };

  const check = async () => {
    const c = code.trim();
    if (!c) return;
    setBusy(true); setError(null); setPass(null);
    try {
      const p = await gatePassApi.verify(c);
      setPass(p);
      setPlate('');
    } catch (e) {
      setError(apiErrorMessage(e, tx('Gate pass not found')));
    } finally {
      setBusy(false);
    }
  };

  const verifyCode = code.includes('|') ? code.split('|')[1]?.trim() : undefined;

  return (
    <div className="mx-auto max-w-4xl space-y-4 p-4 sm:p-6">
      <ListPageHeader title={tx('Gate check')} icon={DoorOpen}
        description={tx('Scan the QR code on the gate pass or type its number. Check the bearer, the vehicle and the load before letting them out.')} />

      <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); void check(); }}>
        <div className="relative flex-1">
          <ScanLine className="pointer-events-none absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-muted-foreground" />
          <Input ref={input} value={code} onChange={(e) => setCode(e.target.value)} placeholder={tx('Scan or type GP-2026-00001')}
            className="h-12 pl-10 text-lg" autoComplete="off" aria-label={tx('Gate pass number')} />
        </div>
        <Button type="submit" size="lg" className="h-12" disabled={!code.trim() || busy}>{tx('Check')}</Button>
      </form>

      {error && (
        <div className="flex items-center gap-3 rounded-xl border-2 border-destructive bg-destructive/10 p-4 text-destructive">
          <XCircle className="h-8 w-8 shrink-0" /><p className="text-lg font-semibold">{error}</p>
        </div>
      )}

      {pass && (
        <div className={cn('space-y-4 rounded-xl border-2 p-4', pass.canLetOut ? 'border-emerald-500' : 'border-destructive')}>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <p className="flex items-center gap-2 text-2xl font-bold">{pass.number}<GatePassStateBadge state={pass.state} /></p>
              <p className="text-sm text-muted-foreground">
                {SOURCE_LABELS[pass.sourceType]} {pass.sourceNumber}{pass.partnerName ? ` · ${pass.partnerName}` : ''}
              </p>
              <p className="text-xs text-muted-foreground">{tx('Valid until')} {dateTime(pass.validUntil)}
                {pass.codeVerified ? ` · ${tx('QR code verified')}` : ` · ${tx('Number typed — compare the printed pass')}`}</p>
            </div>
            {pass.canLetOut
              ? <p className="flex items-center gap-2 text-lg font-semibold text-emerald-700 dark:text-emerald-400"><CheckCircle2 className="h-7 w-7" />{tx('Valid')}</p>
              : <p className="flex items-center gap-2 text-lg font-semibold text-destructive"><ShieldX className="h-7 w-7" />{tx('Do not let out')}</p>}
          </div>

          {pass.problems.map((p) => (
            <p key={p} className="rounded-md bg-destructive/10 px-3 py-2 text-sm font-semibold text-destructive">{PROBLEM_LABELS[p] ?? p}</p>
          ))}
          {pass.state === 'used' && <p className="text-sm">{tx('Gated out')} {dateTime(pass.gateOutAt)}{pass.gateOutPlate ? ` · ${pass.gateOutPlate}` : ''}</p>}
          <WarningList warnings={pass.liveWarnings} />

          <div className="grid gap-3 sm:grid-cols-3">
            <Info label={BEARER_LABELS[pass.bearerType]} value={pass.bearerName} big />
            <Info label={tx('Vehicle plate')} value={pass.vehiclePlate} big />
            <Info label={tx('ID / licence no.')} value={pass.bearerIdNumber} />
            <Info label={tx('Phone')} value={pass.bearerPhone} />
            <Info label={tx('Vehicle type')} value={pass.vehicleType} />
            <Info label={tx('Destination')} value={pass.destination} />
          </div>

          <div className="overflow-x-auto rounded-md border">
            <table className="w-full text-sm">
              <thead className="bg-muted/50 text-xs text-muted-foreground">
                <tr><th className="px-3 py-2 text-left">#</th><th className="px-3 py-2 text-left">{tx('Item')}</th><th className="px-3 py-2 text-right">{tx('Quantity')}</th></tr>
              </thead>
              <tbody>
                {pass.lines.map((l) => (
                  <tr key={l.id} className="border-t">
                    <td className="px-3 py-2">{l.lineNo}</td>
                    <td className="px-3 py-2">{l.description}</td>
                    <td className="px-3 py-2 text-right text-base font-semibold tabular-nums">{Number(l.quantity)} {l.unit ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {pass.canLetOut && !refusing && (
            <div className="grid gap-3 sm:grid-cols-2">
              {pass.vehiclePlate && (
                <Field label={tx('Plate seen at the gate (optional)')}>
                  <Input value={plate} onChange={(e) => setPlate(e.target.value.toUpperCase())} placeholder={pass.vehiclePlate} maxLength={30} />
                </Field>
              )}
              <Field label={tx('Note (optional)')}><Input value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} /></Field>
            </div>
          )}

          {refusing && (
            <Field label={tx('Why are you refusing?')} required>
              <Textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} autoFocus />
            </Field>
          )}

          <div className="flex flex-wrap justify-end gap-2">
            {canPrint && <Button variant="ghost" onClick={() => printGatePass(pass.id)}><Printer className="mr-1 h-4 w-4" />{tx('Print')}</Button>}
            <Button variant="outline" onClick={reset}>{tx('Next vehicle')}</Button>
            {pass.status === 'issued' && !refusing && (
              <Button variant="outline" className="text-destructive" onClick={() => setRefusing(true)}>{tx('Refuse')}</Button>
            )}
            {refusing && (
              <Button variant="destructive" disabled={!reason.trim() || refuse.isPending} onClick={() => refuse.mutate({ id: pass.id, reason: reason.trim() }, {
                onSuccess: () => { toast.success(tx('Refusal recorded')); reset(); },
                onError: (e) => toast.error(apiErrorMessage(e, tx('Could not record the refusal'))),
              })}>{tx('Record refusal')}</Button>
            )}
            {pass.canLetOut && !refusing && (
              <Button size="lg" className="bg-emerald-600 hover:bg-emerald-700" disabled={gateOut.isPending}
                onClick={() => gateOut.mutate({ id: pass.id, verifyCode, plate: plate || undefined, note: note || undefined }, {
                  onSuccess: (p) => {
                    toast.success(`${p.number} ${tx('gated out')}`, { description: p.sourceUpdate });
                    reset();
                  },
                  onError: (e) => toast.error(apiErrorMessage(e, tx('Could not record the gate-out'))),
                })}>
                <DoorOpen className="mr-2 h-5 w-5" />{tx('Let out')}
              </Button>
            )}
          </div>
        </div>
      )}

      <div className="rounded-lg border bg-card p-3">
        <p className="mb-2 text-sm font-semibold">{tx('Recently gated out')}</p>
        {(recent.data?.data ?? []).length === 0
          ? <p className="text-xs text-muted-foreground">{tx('Nothing yet.')}</p>
          : (
            <ul className="divide-y text-sm">
              {recent.data!.data.map((p) => (
                <li key={p.id} className="flex flex-wrap justify-between gap-2 py-1.5">
                  <span className="font-medium">{p.number} · {p.bearerName}{p.vehiclePlate ? ` · ${p.vehiclePlate}` : ''}</span>
                  <span className="text-xs text-muted-foreground">{dateTime(p.gateOutAt)}</span>
                </li>
              ))}
            </ul>
          )}
      </div>
    </div>
  );
}

function Info({ label, value, big }: { label: string; value: string | null | undefined; big?: boolean }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className={cn('font-medium', big && 'text-xl font-bold')}>{value || '—'}</p>
    </div>
  );
}

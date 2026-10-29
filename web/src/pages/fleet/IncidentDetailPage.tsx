import { useRef, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Camera, Edit, MapPin, RefreshCw, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { StatusPill, type Tone } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  AT_FAULT_LABEL, CLAIM_STATUS_LABEL, FINE_STATUS_LABEL, INCIDENT_STATUS_LABEL, INCIDENT_TYPE_LABEL, useClaimStatus,
  useCreateClaim, useDeleteIncidentFile, useIncident, useIncidentStatus, useUploadIncidentFile, type ClaimStatus,
  type InsuranceClaim, type IncidentStatus,
} from '@/features/fleet/tyres-api';
import { mapUrl } from '@/features/fleet/telematics-api';
import { Field } from './fleet-ui';
import { IncidentStatusPill, SeverityPill } from './IncidentsPage';

const NEXT: Record<IncidentStatus, IncidentStatus[]> = {
  reported: ['investigating', 'repairing', 'closed', 'cancelled'],
  investigating: ['repairing', 'closed', 'cancelled'],
  repairing: ['investigating', 'closed', 'cancelled'],
  closed: ['investigating'],
  cancelled: [],
};
const CLAIM_NEXT: Record<ClaimStatus, ClaimStatus[]> = {
  draft: ['submitted', 'withdrawn'], submitted: ['approved', 'rejected', 'withdrawn'], approved: ['settled', 'withdrawn'],
  rejected: [], settled: [], withdrawn: [],
};
const CLAIM_TONE: Record<ClaimStatus, Tone> = {
  draft: 'neutral', submitted: 'info', approved: 'accent', rejected: 'danger', settled: 'success', withdrawn: 'neutral',
};

function Item({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-0.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm font-medium">{children ?? '—'}</dd>
    </div>
  );
}

/** Incident detail (fleet 1.7): facts, status workflow, photos / documents, insurance claims and linked fines. */
export function IncidentDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: i, isLoading, isError, refetch } = useIncident(id);
  const has = useAuthStore((s) => s.hasPermission);
  const canUpdate = has(PERMISSIONS.fleetIncident.update);
  const [statusTo, setStatusTo] = useState<IncidentStatus | null>(null);
  const [claimOpen, setClaimOpen] = useState(false);
  const [claimAction, setClaimAction] = useState<{ claim: InsuranceClaim; to: ClaimStatus } | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const upload = useUploadIncidentFile();
  const removeFile = useDeleteIncidentFile();

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !i) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Incident not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const editable = canUpdate && i.status !== 'cancelled';

  const onFile = async (f: File | undefined) => {
    if (!f) return;
    try {
      await upload.mutateAsync({ id: i.id, file: f, kind: f.type.startsWith('image/') ? 'photo' : 'document' });
      notify.success(tx('File uploaded'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not upload the file')));
    } finally {
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/fleet/incidents')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold">{i.incidentNumber}</h1>
            <IncidentStatusPill status={i.status} />
            <SeverityPill severity={i.severity} />
          </div>
          <p className="text-sm text-muted-foreground">
            {INCIDENT_TYPE_LABEL[i.incidentType] ?? i.incidentType} · {dateTime(i.occurredAt)} ·{' '}
            <Link className="text-primary hover:underline" to={`/fleet/vehicles/${i.vehicle.id}`}>{i.vehicle.code}</Link>
          </p>
        </div>
        {editable && (
          <div className="flex flex-wrap gap-2">
            {NEXT[i.status].map((s) => (
              <Button key={s} variant={s === 'cancelled' ? 'ghost' : 'outline'} onClick={() => setStatusTo(s)}>
                {i.status === 'closed' ? tx('Reopen') : INCIDENT_STATUS_LABEL[s]}
              </Button>
            ))}
            <Button variant="outline" onClick={() => navigate(`/fleet/incidents/${i.id}/edit`)}><Edit className="mr-1.5 h-4 w-4" /> {tx('Edit')}</Button>
          </div>
        )}
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle className="text-base">{tx('Incident')}</CardTitle></CardHeader>
          <CardContent className="space-y-4">
            <p className="whitespace-pre-wrap text-sm">{i.description}</p>
            <dl className="grid grid-cols-2 gap-4 sm:grid-cols-3">
              <Item label={tx('Driver / operator')}>{i.driver?.name}</Item>
              <Item label={tx('Place')}>{i.location}</Item>
              <Item label={tx('GPS')}>
                {i.latitude && i.longitude ? (
                  <a className="inline-flex items-center gap-1 text-primary hover:underline" href={mapUrl(i.latitude, i.longitude)} target="_blank" rel="noreferrer">
                    <MapPin className="h-3.5 w-3.5" /> {i.latitude}, {i.longitude}
                  </a>
                ) : null}
              </Item>
              <Item label={tx('At fault')}>{AT_FAULT_LABEL[i.atFault]}</Item>
              <Item label={tx('Estimated cost')}>{i.estimatedCost ? money(i.estimatedCost) : null}</Item>
              <Item label={tx('Injuries')}>{i.injuries}</Item>
              <Item label={tx('Police report number')}>{i.policeReportNumber}</Item>
              <Item label={tx('Police station')}>{i.policeStation}</Item>
              <Item label={tx('Repair')}>{[i.repairReference, i.repairCost ? money(i.repairCost) : null].filter(Boolean).join(' · ') || null}</Item>
              {i.workOrderId && <Item label={tx('Maintenance work order (id)')}>{i.workOrderId}</Item>}
            </dl>
            {i.damageDescription && <div><div className="text-xs text-muted-foreground">{tx('Damage')}</div><p className="whitespace-pre-wrap text-sm">{i.damageDescription}</p></div>}
            {i.statusReason && <p className="text-sm text-muted-foreground">{tx('Status reason')}: {i.statusReason}</p>}
            {i.thirdParties.length > 0 && (
              <div>
                <div className="mb-1 text-xs text-muted-foreground">{tx('Third parties')}</div>
                <ul className="space-y-1 text-sm">
                  {i.thirdParties.map((p, n) => (
                    <li key={n} className="rounded border px-3 py-1.5">{[p.name, p.phone, p.vehiclePlate, p.insurer].filter(Boolean).join(' · ')}</li>
                  ))}
                </ul>
              </div>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0">
            <CardTitle className="text-base">{tx('Photos & documents')}</CardTitle>
            {editable && (
              <>
                <input ref={fileRef} type="file" accept="image/*,application/pdf" capture="environment" className="hidden" onChange={(e) => onFile(e.target.files?.[0])} />
                <Button size="sm" variant="outline" disabled={upload.isPending} onClick={() => fileRef.current?.click()}><Camera className="mr-1 h-4 w-4" /> {tx('Add')}</Button>
              </>
            )}
          </CardHeader>
          <CardContent className="space-y-2">
            {(i.attachments ?? []).length === 0 && <p className="text-sm text-muted-foreground">{tx('No photos yet.')}</p>}
            <div className="grid grid-cols-2 gap-2">
              {(i.attachments ?? []).map((a) => (
                <div key={a.id} className="relative overflow-hidden rounded border">
                  {a.kind === 'photo'
                    ? <a href={a.url} target="_blank" rel="noreferrer"><img src={a.url} alt={a.title ?? a.filename} className="h-24 w-full object-cover" /></a>
                    : <a href={a.url} target="_blank" rel="noreferrer" className="block p-3 text-xs text-primary hover:underline">{a.filename}</a>}
                  {editable && (
                    <button type="button" aria-label={tx('Delete')} className="absolute right-1 top-1 rounded bg-background/80 p-1"
                      onClick={() => removeFile.mutateAsync({ id: i.id, attachmentId: a.id }).catch((err) => notify.error(apiMessage(err, tx('Could not delete the file'))))}>
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  )}
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">{tx('Insurance claims')}</CardTitle>
          {editable && <Button size="sm" variant="outline" onClick={() => setClaimOpen(true)}>{tx('New claim')}</Button>}
        </CardHeader>
        <CardContent className="space-y-2">
          {(i.claims ?? []).length === 0 && <p className="text-sm text-muted-foreground">{tx('No insurance claims.')}</p>}
          {(i.claims ?? []).map((c) => (
            <div key={c.id} className="flex flex-wrap items-center gap-3 rounded-lg border p-3 text-sm">
              <StatusPill tone={CLAIM_TONE[c.status]}>{CLAIM_STATUS_LABEL[c.status]}</StatusPill>
              <span className="font-medium">{c.insurerName ?? '—'}{c.claimNumber ? ` · ${c.claimNumber}` : ''}</span>
              <span className="tabular-nums text-muted-foreground">
                {tx('Claimed')} {money(c.amountClaimed)}
                {c.amountApproved ? ` · ${tx('Approved')} ${money(c.amountApproved)}` : ''}
                {Number(c.amountReceived) > 0 ? ` · ${tx('Received')} ${money(c.amountReceived)}` : ''}
              </span>
              {c.submittedOn && <span className="text-xs text-muted-foreground">{tx('Submitted')} {date(c.submittedOn)}</span>}
              {c.statusReason && <span className="text-xs text-muted-foreground">{c.statusReason}</span>}
              <span className="flex-1" />
              {canUpdate && CLAIM_NEXT[c.status].map((s) => (
                <Button key={s} size="sm" variant="outline" onClick={() => setClaimAction({ claim: c, to: s })}>{CLAIM_STATUS_LABEL[s]}</Button>
              ))}
            </div>
          ))}
        </CardContent>
      </Card>

      {(i.fines ?? []).length > 0 && (
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Traffic fines')}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            {(i.fines ?? []).map((f) => (
              <Link key={f.id} to={`/fleet/fines/${f.id}`} className="flex justify-between rounded px-2 py-1 hover:bg-muted">
                <span>{f.fineNumber} · {f.offence}</span><span>{money(f.amount)} · {FINE_STATUS_LABEL[f.status]}</span>
              </Link>
            ))}
          </CardContent>
        </Card>
      )}

      {statusTo && <IncidentStatusDialog incidentId={i.id} from={i.status} to={statusTo} onClose={() => setStatusTo(null)} />}
      {claimOpen && <ClaimDialog incidentId={i.id} onClose={() => setClaimOpen(false)} />}
      {claimAction && <ClaimStatusDialog claim={claimAction.claim} to={claimAction.to} onClose={() => setClaimAction(null)} />}
    </div>
  );
}

function IncidentStatusDialog({ incidentId, from, to, onClose }: { incidentId: string; from: IncidentStatus; to: IncidentStatus; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const change = useIncidentStatus();
  const needsReason = to === 'cancelled' || from === 'closed';
  const submit = async () => {
    try {
      await change.mutateAsync({ id: incidentId, status: to, reason: reason.trim() || undefined });
      notify.success(tx('Status changed to {{status}}', { status: INCIDENT_STATUS_LABEL[to] }));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not change the status')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{from === 'closed' ? tx('Reopen') : INCIDENT_STATUS_LABEL[to]}</DialogTitle>
          <DialogDescription>{to === 'closed' ? tx('An incident closes once its insurance claims are settled, rejected or withdrawn.') : ''}</DialogDescription>
        </DialogHeader>
        <Field label={needsReason ? tx('Reason *') : tx('Reason')}><Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={(needsReason && !reason.trim()) || change.isPending}>{tx('Confirm')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ClaimDialog({ incidentId, onClose }: { incidentId: string; onClose: () => void }) {
  const [insurer, setInsurer] = useState('');
  const [claimNumber, setClaimNumber] = useState('');
  const [policy, setPolicy] = useState('');
  const [amount, setAmount] = useState('');
  const [excess, setExcess] = useState('');
  const [notes, setNotes] = useState('');
  const create = useCreateClaim();
  const valid = insurer.trim().length > 0 && amount !== '' && Number(amount) >= 0 && (excess === '' || Number(excess) >= 0);
  const submit = async () => {
    try {
      await create.mutateAsync({ incidentId, body: {
        insurerName: insurer.trim(), claimNumber: claimNumber.trim() || undefined, policyNumber: policy.trim() || undefined,
        amountClaimed: Number(amount), excessAmount: excess === '' ? undefined : Number(excess), notes: notes.trim() || undefined,
      } });
      notify.success(tx('Claim created'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not create the claim')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('New claim')}</DialogTitle><DialogDescription>{tx('The claim starts as a draft; submit it when sent to the insurer.')}</DialogDescription></DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Insurer *')} className="sm:col-span-2"><Input value={insurer} onChange={(e) => setInsurer(e.target.value)} /></Field>
          <Field label={tx('Claim number')}><Input value={claimNumber} onChange={(e) => setClaimNumber(e.target.value)} /></Field>
          <Field label={tx('Policy number')}><Input value={policy} onChange={(e) => setPolicy(e.target.value)} /></Field>
          <Field label={tx('Amount claimed *')}><Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
          <Field label={tx('Excess')}><Input inputMode="decimal" value={excess} onChange={(e) => setExcess(e.target.value)} /></Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || create.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ClaimStatusDialog({ claim, to, onClose }: { claim: InsuranceClaim; to: ClaimStatus; onClose: () => void }) {
  const [approved, setApproved] = useState(claim.amountApproved ?? claim.amountClaimed);
  const [received, setReceived] = useState(claim.amountApproved ?? '');
  const [reason, setReason] = useState('');
  const change = useClaimStatus();
  const needsReason = to === 'rejected' || to === 'withdrawn';
  const valid = (to !== 'approved' || (approved !== '' && Number(approved) >= 0))
    && (to !== 'settled' || (received !== '' && Number(received) > 0)) && (!needsReason || reason.trim().length > 0);
  const submit = async () => {
    try {
      await change.mutateAsync({ id: claim.id, body: {
        status: to, amountApproved: to === 'approved' ? Number(approved) : undefined,
        amountReceived: to === 'settled' ? Number(received) : undefined, reason: reason.trim() || undefined,
      } });
      notify.success(CLAIM_STATUS_LABEL[to]);
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not update the claim')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{CLAIM_STATUS_LABEL[to]} · {claim.insurerName ?? ''}</DialogTitle><DialogDescription>{tx('Claimed')} {money(claim.amountClaimed)}</DialogDescription></DialogHeader>
        <div className="space-y-3">
          {to === 'approved' && <Field label={tx('Amount approved *')}><Input inputMode="decimal" value={approved} onChange={(e) => setApproved(e.target.value)} /></Field>}
          {to === 'settled' && <Field label={tx('Amount received *')}><Input inputMode="decimal" value={received} onChange={(e) => setReceived(e.target.value)} /></Field>}
          <Field label={needsReason ? tx('Reason *') : tx('Notes')}><Textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!valid || change.isPending}>{tx('Confirm')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

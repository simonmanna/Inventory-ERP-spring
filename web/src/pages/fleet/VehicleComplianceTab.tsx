import { useRef, useState } from 'react';
import { Edit, Paperclip, Plus, RefreshCcw, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  DOC_TYPE_LABEL, useCreateCompliance, useDeleteCompliance, useRenewCompliance, useUpdateCompliance,
  useUploadAttachment, useVehicleCompliance, type ComplianceDocument, type DocType, type Vehicle,
} from '@/features/fleet/api';
import { ComplianceStatePill, Field, OptionalSelect } from './fleet-ui';
import { toOptions } from './fleet-format';

/** Compliance documents of a vehicle: registration, insurance, licence, inspection, permits (fleet 1.1). */
export function VehicleComplianceTab({ vehicle }: { vehicle: Vehicle }) {
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetVehicle.compliance));
  const canUpload = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetVehicle.update));
  const { data, isLoading, isError } = useVehicleCompliance(vehicle.id);
  const [editing, setEditing] = useState<ComplianceDocument | 'new' | null>(null);
  const [renewing, setRenewing] = useState<ComplianceDocument | null>(null);
  const remove = useDeleteCompliance();
  const upload = useUploadAttachment();
  const fileInput = useRef<HTMLInputElement>(null);
  const [uploadFor, setUploadFor] = useState<string | null>(null);
  const writable = vehicle.status !== 'disposed';

  const onFile = async (file: File | undefined) => {
    if (!file || !uploadFor) return;
    try {
      await upload.mutateAsync({ vehicleId: vehicle.id, file, kind: 'document', complianceDocumentId: uploadFor });
      notify.success(tx('File attached'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not upload the file')));
    } finally {
      setUploadFor(null);
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const del = async (d: ComplianceDocument) => {
    if (!window.confirm(tx('Delete this document?'))) return;
    try {
      await remove.mutateAsync(d.id);
      notify.success(tx('Document deleted'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not delete the document')));
    }
  };

  const columns: Column<ComplianceDocument>[] = [
    { key: 'docType', header: tx('Document'), render: (d) => <span className="font-medium">{DOC_TYPE_LABEL[d.docType]}</span> },
    { key: 'documentNumber', header: tx('Number'), render: (d) => d.documentNumber ?? '—' },
    { key: 'issuer', header: tx('Issuer'), render: (d) => d.issuer ?? '—' },
    { key: 'issuedOn', header: tx('Issued'), render: (d) => (d.issuedOn ? date(d.issuedOn) : '—') },
    { key: 'expiresOn', header: tx('Expires'), render: (d) => (d.expiresOn ? date(d.expiresOn) : '—') },
    { key: 'cost', header: tx('Premium / fee'), className: 'text-right tabular-nums', render: (d) => (d.costAmount ? money(d.costAmount) : '—') },
    {
      key: 'state', header: tx('Status'),
      render: (d) => (
        <div className="flex items-center gap-2">
          <ComplianceStatePill state={d.state} />
          {d.status === 'active' && d.daysToExpiry !== null && (
            <span className="text-xs text-muted-foreground">
              {d.daysToExpiry < 0 ? tx('{{n}} days ago', { n: -d.daysToExpiry }) : tx('in {{n}} days', { n: d.daysToExpiry })}
            </span>
          )}
        </div>
      ),
    },
    {
      key: 'files', header: tx('Files'),
      render: (d) => (d.attachments?.length ? <span className="inline-flex items-center gap-1 text-sm"><Paperclip className="h-3.5 w-3.5" />{d.attachments.length}</span> : '—'),
    },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (d) => d.status === 'active' && writable && (
        <div className="flex justify-end gap-1">
          {canUpload && (
            <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Attach file')}
              onClick={() => { setUploadFor(d.id); fileInput.current?.click(); }}>
              <Paperclip className="h-4 w-4" />
            </Button>
          )}
          {canManage && (
            <>
              <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Renew')} onClick={() => setRenewing(d)}><RefreshCcw className="h-4 w-4" /></Button>
              <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setEditing(d)}><Edit className="h-4 w-4" /></Button>
              <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Delete')} onClick={() => del(d)}><Trash2 className="h-4 w-4" /></Button>
            </>
          )}
        </div>
      ),
    },
  ];

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <CardTitle className="text-base">{tx('Compliance documents')}</CardTitle>
        {canManage && writable && <Button size="sm" onClick={() => setEditing('new')}><Plus className="mr-1 h-4 w-4" /> {tx('Add document')}</Button>}
      </CardHeader>
      <CardContent>
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(d) => d.id}
          emptyMessage={isError ? tx('Could not load the documents.') : tx('No compliance documents recorded yet.')} />
        <input ref={fileInput} type="file" className="hidden" onChange={(e) => onFile(e.target.files?.[0])} />
      </CardContent>
      {editing && <ComplianceDialog vehicleId={vehicle.id} doc={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      {renewing && <RenewDialog doc={renewing} onClose={() => setRenewing(null)} />}
    </Card>
  );
}

function ComplianceDialog({ vehicleId, doc, onClose }: { vehicleId: string; doc: ComplianceDocument | null; onClose: () => void }) {
  const [docType, setDocType] = useState<DocType | ''>(doc?.docType ?? '');
  const [number, setNumber] = useState(doc?.documentNumber ?? '');
  const [issuer, setIssuer] = useState(doc?.issuer ?? '');
  const [issuedOn, setIssuedOn] = useState(doc?.issuedOn ?? '');
  const [expiresOn, setExpiresOn] = useState(doc?.expiresOn ?? '');
  const [lead, setLead] = useState(doc ? String(doc.reminderLeadDays) : '');
  const [notes, setNotes] = useState(doc?.notes ?? '');
  const [cost, setCost] = useState(doc?.costAmount ?? '');
  const create = useCreateCompliance();
  const update = useUpdateCompliance();
  const datesOk = !issuedOn || !expiresOn || expiresOn >= issuedOn;
  const leadOk = lead === '' || (Number.isInteger(Number(lead)) && Number(lead) >= 0 && Number(lead) <= 365);
  const costOk = cost === '' || (Number.isFinite(Number(cost)) && Number(cost) >= 0);

  const submit = async () => {
    const body: Record<string, unknown> = {
      docType, documentNumber: number.trim() || undefined, issuer: issuer.trim() || undefined,
      issuedOn: issuedOn || undefined, expiresOn: expiresOn || undefined,
      reminderLeadDays: lead === '' ? undefined : Number(lead), notes: notes.trim() || undefined,
      costAmount: cost === '' ? undefined : Number(cost),
    };
    try {
      if (doc) {
        const clear = ([['documentNumber', number], ['issuer', issuer], ['issuedOn', issuedOn], ['expiresOn', expiresOn], ['notes', notes], ['costAmount', cost]] as const)
          .filter(([k, v]) => !v.trim() && doc[k]).map(([k]) => k);
        await update.mutateAsync({ id: doc.id, body: { ...body, clear: clear.length ? clear : undefined } });
        notify.success(tx('Document updated'));
      } else {
        await create.mutateAsync({ vehicleId, body });
        notify.success(tx('Document added'));
      }
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the document')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{doc ? tx('Edit document') : tx('Add document')}</DialogTitle>
          <DialogDescription>{tx('Reminders start the given number of days before expiry (blank = organization default).')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Document type')} className="sm:col-span-2">
            <OptionalSelect value={docType} onChange={(v) => setDocType(v as DocType | '')} placeholder={tx('Choose a type')} options={toOptions(DOC_TYPE_LABEL)} />
          </Field>
          <Field label={tx('Number')}><Input value={number} onChange={(e) => setNumber(e.target.value)} /></Field>
          <Field label={tx('Issuer')}><Input value={issuer} onChange={(e) => setIssuer(e.target.value)} /></Field>
          <Field label={tx('Issued on')}><Input type="date" value={issuedOn} onChange={(e) => setIssuedOn(e.target.value)} /></Field>
          <Field label={tx('Expires on')} error={datesOk ? undefined : tx('The expiry date must be on or after the issue date')}>
            <Input type="date" value={expiresOn} onChange={(e) => setExpiresOn(e.target.value)} />
          </Field>
          <Field label={tx('Reminder lead (days)')} error={leadOk ? undefined : tx('Enter 0 to 365 days')}>
            <Input inputMode="numeric" value={lead} onChange={(e) => setLead(e.target.value)} />
          </Field>
          <Field label={tx('Premium / fee paid')} error={costOk ? undefined : tx('Enter an amount of 0 or more')}>
            <Input inputMode="decimal" value={cost} onChange={(e) => setCost(e.target.value)} />
          </Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!docType || !datesOk || !leadOk || !costOk || create.isPending || update.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function RenewDialog({ doc, onClose }: { doc: ComplianceDocument; onClose: () => void }) {
  const [number, setNumber] = useState('');
  const [issuedOn, setIssuedOn] = useState('');
  const [expiresOn, setExpiresOn] = useState('');
  const renew = useRenewCompliance();
  const ok = !!expiresOn && (!doc.expiresOn || expiresOn > doc.expiresOn) && (!issuedOn || expiresOn >= issuedOn);

  const submit = async () => {
    try {
      await renew.mutateAsync({ id: doc.id, body: { documentNumber: number.trim() || undefined, issuedOn: issuedOn || undefined, expiresOn } });
      notify.success(tx('Document renewed'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not renew the document')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Renew {{doc}}', { doc: DOC_TYPE_LABEL[doc.docType] })}</DialogTitle>
          <DialogDescription>{tx('The current document is kept in the history as renewed.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('New number (blank = same)')} className="sm:col-span-2"><Input value={number} onChange={(e) => setNumber(e.target.value)} /></Field>
          <Field label={tx('Issued on')}><Input type="date" value={issuedOn} onChange={(e) => setIssuedOn(e.target.value)} /></Field>
          <Field label={tx('Expires on *')}><Input type="date" value={expiresOn} min={doc.expiresOn ?? undefined} onChange={(e) => setExpiresOn(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!ok || renew.isPending}>{tx('Renew')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

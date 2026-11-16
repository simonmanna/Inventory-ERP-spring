import { useState } from 'react';
import { AlertTriangle, CheckCircle2, KeyRound, Paperclip, Plus, ShieldAlert, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';
import { useAuthStore } from '@/stores/auth.store';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { selectClass, shortDate, todayIso } from '@/features/projects/ui';
import {
  DOCUMENT_TYPES, constructionApi, documentTypeLabel, modeLabel, opt, useConstructionMutation, useRequirements,
  useSubcontractCompliance, useSupplierCompliance, type ComplianceDocument, type DocumentType, type GateIssue, type GateMode,
  type SubcontractCompliance,
} from '@/features/projects/construction-api';
import { Empty, Field, FormDialog, Money, Section, Table, isAmount, num, td, tdRight } from '../tabs/contract/shared';

/** Compliance permissions of the signed-in user. */
export function useCompliancePerms() {
  const has = useAuthStore((s) => s.hasPermission);
  return { read: has(PERMISSIONS.compliance.read), manage: has(PERMISSIONS.compliance.manage), override: has(PERMISSIONS.compliance.override) };
}

const STATE_TONE: Record<string, string> = {
  verified: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300',
  valid: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300',
  pending: 'bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
  missing: 'bg-slate-100 text-slate-700 dark:bg-slate-500/20 dark:text-slate-300',
  expired: 'bg-rose-100 text-rose-700 dark:bg-rose-500/20 dark:text-rose-300',
  rejected: 'bg-rose-100 text-rose-700 dark:bg-rose-500/20 dark:text-rose-300',
};

function Pill({ state, label }: { state: string; label?: string }) {
  return <span className={cn('inline-flex rounded px-1.5 py-0.5 text-xs font-medium', STATE_TONE[state] ?? STATE_TONE.missing)}>{label ?? state}</span>;
}

const expired = (d: ComplianceDocument) => !!d.validTo && d.validTo < todayIso();

/** Documents (or lien waivers) with verify / reject and the attachment of each. */
export function DocumentsTable({ docs, waivers = false }: { docs: ComplianceDocument[]; waivers?: boolean }) {
  const perms = useCompliancePerms();
  const [files, setFiles] = useState<ComplianceDocument | null>(null);
  const verify = useConstructionMutation<string>((id) => constructionApi.verifyDocument(id), { success: tx('Document verified') });
  const reject = useConstructionMutation<{ id: string; reason: string }>(({ id, reason }) => constructionApi.rejectDocument(id, reason),
    { success: tx('Document rejected') });
  if (docs.length === 0) return <Empty>{waivers ? tx('No lien waivers yet.') : tx('No compliance documents yet.')}</Empty>;
  return (
    <>
      <Table head={waivers
        ? [tx('Waiver'), tx('For'), { label: tx('Amount'), right: true }, tx('Through'), tx('Status'), '']
        : [tx('Document'), tx('Number'), tx('Issuer'), tx('Valid'), tx('Status'), '']}>
        {docs.map((d) => (
          <tr key={d.id}>
            <td className={td}>
              {waivers ? `${d.waiverType === 'unconditional' ? tx('Unconditional') : tx('Conditional')}` : documentTypeLabel(d.documentType)}
              {d.subcontractNumber && !waivers && <div className="text-xs text-muted-foreground">{d.subcontractNumber}</div>}
            </td>
            {waivers ? (
              <>
                <td className={td}>{d.waiverScope === 'valuation' ? `${tx('Valuation')} ${d.valuationNumber ?? ''}` : `${tx('Payment')} ${d.paymentNumber ?? ''}`}</td>
                <td className={tdRight}><Money value={d.amount} /></td>
                <td className={td}>{shortDate(d.throughDate)}</td>
              </>
            ) : (
              <>
                <td className={cn(td, 'font-mono text-xs')}>{d.documentNumber ?? '—'}</td>
                <td className={td}>{d.issuer ?? '—'}</td>
                <td className={cn(td, expired(d) && 'text-rose-600')}>{d.validFrom ? `${shortDate(d.validFrom)} → ` : ''}{shortDate(d.validTo)}</td>
              </>
            )}
            <td className={td}><Pill state={!waivers && d.status === 'verified' && expired(d) ? 'expired' : d.status} /></td>
            <td className={cn(td, 'whitespace-nowrap text-right')}>
              <Button size="icon" variant="ghost" title={tx('Attachments')} onClick={() => setFiles(d)}><Paperclip className="h-4 w-4" /></Button>
              {perms.manage && d.status === 'pending' && (
                <>
                  <Button size="icon" variant="ghost" title={tx('Verify')} disabled={verify.isPending} onClick={() => verify.mutate(d.id)}>
                    <CheckCircle2 className="h-4 w-4 text-emerald-600" />
                  </Button>
                  <Button size="icon" variant="ghost" title={tx('Reject')} disabled={reject.isPending} onClick={() => {
                    const reason = window.prompt(tx('Reason for rejecting this document'));
                    if (reason && reason.trim()) reject.mutate({ id: d.id, reason: reason.trim() });
                  }}>
                    <XCircle className="h-4 w-4 text-rose-600" />
                  </Button>
                </>
              )}
            </td>
          </tr>
        ))}
      </Table>
      <Dialog open={!!files} onOpenChange={(o) => !o && setFiles(null)}>
        <DialogContent className="max-w-2xl">
          <DialogHeader><DialogTitle>{files ? documentTypeLabel(files.documentType) : ''}</DialogTitle></DialogHeader>
          {files && <AttachmentPanel resType="compliance_document" resId={files.id} writePermission={PERMISSIONS.compliance.manage} bare />}
        </DialogContent>
      </Dialog>
    </>
  );
}

/** Record a compliance document or a lien waiver. */
export function DocumentFormDialog({ open, onClose, partnerId, subcontract, waiver = false }: {
  open: boolean; onClose: () => void; partnerId: string; subcontract?: SubcontractCompliance; waiver?: boolean;
}) {
  const [f, setF] = useState({
    documentType: (waiver ? 'lien_waiver' : 'tax_clearance') as DocumentType, documentNumber: '', issuer: '', validFrom: '', validTo: '',
    amount: '', waiverType: 'conditional' as 'conditional' | 'unconditional', waiverScope: 'valuation' as 'valuation' | 'payment',
    valuationId: '', paymentId: '', throughDate: todayIso(), notes: '', scoped: !!subcontract,
  });
  const isWaiver = f.documentType === 'lien_waiver';
  const save = useConstructionMutation<void>(() => constructionApi.createDocument({
    partnerId, subcontractId: subcontract && (f.scoped || isWaiver) ? subcontract.subcontract.id : undefined, documentType: f.documentType,
    documentNumber: opt(f.documentNumber), issuer: opt(f.issuer), validFrom: opt(f.validFrom), validTo: opt(f.validTo), amount: opt(f.amount),
    waiverType: isWaiver ? f.waiverType : undefined, waiverScope: isWaiver ? f.waiverScope : undefined,
    valuationId: isWaiver && f.waiverScope === 'valuation' ? opt(f.valuationId) : undefined,
    paymentId: isWaiver && f.waiverScope === 'payment' ? opt(f.paymentId) : undefined, throughDate: isWaiver ? opt(f.throughDate) : undefined,
    notes: opt(f.notes),
  }), { success: tx('Document recorded; verify it once the original is checked') });
  const valid = isWaiver
    ? !!subcontract && isAmount(f.amount) && !!f.throughDate && (f.waiverScope === 'valuation' ? !!f.valuationId : !!f.paymentId)
    : !!f.validTo && (!f.validFrom || f.validFrom <= f.validTo);
  const types = DOCUMENT_TYPES.filter((t) => (subcontract ? true : t !== 'lien_waiver'));

  return (
    <FormDialog open={open} onClose={onClose} title={isWaiver ? tx('Record a lien waiver') : tx('Record a compliance document')}
      submitLabel={tx('Record')} valid={valid} pending={save.isPending} onSubmit={() => save.mutate(undefined, { onSuccess: onClose })}>
      <Field label={tx('Type')} className="sm:col-span-2">
        <select className={selectClass} value={f.documentType} onChange={(e) => setF({ ...f, documentType: e.target.value as DocumentType })}>
          {types.map((t) => <option key={t} value={t}>{documentTypeLabel(t)}</option>)}
        </select>
      </Field>
      {isWaiver ? (
        <>
          <Field label={tx('Waiver type')}>
            <select className={selectClass} value={f.waiverType} onChange={(e) => setF({ ...f, waiverType: e.target.value as 'conditional' | 'unconditional' })}>
              <option value="conditional">{tx('Conditional (before payment)')}</option>
              <option value="unconditional">{tx('Unconditional (after payment)')}</option>
            </select>
          </Field>
          <Field label={tx('Covers')}>
            <select className={selectClass} value={f.waiverScope} onChange={(e) => setF({ ...f, waiverScope: e.target.value as 'valuation' | 'payment' })}>
              <option value="valuation">{tx('A valuation')}</option>
              <option value="payment">{tx('A payment')}</option>
            </select>
          </Field>
          {f.waiverScope === 'valuation' ? (
            <Field label={tx('Valuation')} className="sm:col-span-2">
              <select className={selectClass} value={f.valuationId} onChange={(e) => setF({ ...f, valuationId: e.target.value })}>
                <option value="">{tx('Select…')}</option>
                {(subcontract?.valuations ?? []).map((v) => <option key={v.id} value={v.id}>{v.number} · {shortDate(v.valuationDate)} · {num(v.netAmount).toFixed(2)}</option>)}
              </select>
            </Field>
          ) : (
            <Field label={tx('Payment')} className="sm:col-span-2">
              <select className={selectClass} value={f.paymentId} onChange={(e) => setF({ ...f, paymentId: e.target.value })}>
                <option value="">{tx('Select…')}</option>
                {(subcontract?.payments ?? []).map((p) => <option key={p.id} value={p.id}>{p.paymentNumber} · {shortDate(p.paymentDate)} · {num(p.amount).toFixed(2)}</option>)}
              </select>
            </Field>
          )}
          <Field label={tx('Amount')}><Input type="number" step="0.01" min={0} value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} /></Field>
          <Field label={tx('Through date')}><Input type="date" value={f.throughDate} onChange={(e) => setF({ ...f, throughDate: e.target.value })} /></Field>
        </>
      ) : (
        <>
          <Field label={tx('Number')}><Input value={f.documentNumber} onChange={(e) => setF({ ...f, documentNumber: e.target.value })} /></Field>
          <Field label={tx('Issuer')}><Input value={f.issuer} onChange={(e) => setF({ ...f, issuer: e.target.value })} placeholder={tx('e.g. TRA, NSSF, insurer')} /></Field>
          <Field label={tx('Valid from')}><Input type="date" value={f.validFrom} onChange={(e) => setF({ ...f, validFrom: e.target.value })} /></Field>
          <Field label={tx('Valid to')}><Input type="date" value={f.validTo} onChange={(e) => setF({ ...f, validTo: e.target.value })} /></Field>
          {f.documentType === 'insurance_certificate' && (
            <Field label={tx('Sum insured')}><Input type="number" step="0.01" min={0} value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} /></Field>
          )}
          {subcontract && (
            <label className="flex items-center gap-2 text-sm sm:col-span-2">
              <input type="checkbox" checked={f.scoped} onChange={(e) => setF({ ...f, scoped: e.target.checked })} />
              {tx('Only for subcontract {{number}} (otherwise for every subcontract of this supplier)', { number: subcontract.subcontract.number })}
            </label>
          )}
        </>
      )}
      <Field label={tx('Notes')} className="sm:col-span-2"><Input value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} /></Field>
    </FormDialog>
  );
}

/** Organisation-wide gating per document type (off / warn / block). */
export function RequirementsPanel() {
  const perms = useCompliancePerms();
  const { data: reqs = [] } = useRequirements(perms.read);
  const save = useConstructionMutation<{ documentType: DocumentType; mode: GateMode }>((r) => constructionApi.setRequirements([r]),
    { success: tx('Gating updated') });
  return (
    <Section title={tx('Compliance gating')} hint={tx('Applied when a subcontract valuation is certified and when a subcontract bill is paid. Organisation-wide.')}>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {reqs.map((r) => (
          <Field key={r.documentType} label={documentTypeLabel(r.documentType)}>
            <select className={selectClass} value={r.mode} disabled={!perms.manage || save.isPending}
              onChange={(e) => save.mutate({ documentType: r.documentType, mode: e.target.value as GateMode })}>
              {(['off', 'warn', 'block'] as GateMode[]).map((m) => <option key={m} value={m}>{modeLabel(m)}</option>)}
            </select>
          </Field>
        ))}
      </div>
    </Section>
  );
}

function Issues({ issues }: { issues: GateIssue[] }) {
  if (issues.length === 0) {
    return <p className="flex items-center gap-1.5 text-sm text-emerald-700 dark:text-emerald-400"><CheckCircle2 className="h-4 w-4" />{tx('The next valuation is clear.')}</p>;
  }
  return (
    <ul className="space-y-1">
      {issues.map((i, n) => (
        <li key={n} className={cn('flex items-start gap-1.5 text-sm', i.mode === 'block' ? 'text-rose-700 dark:text-rose-400' : 'text-amber-700 dark:text-amber-400')}>
          {i.mode === 'block' ? <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" /> : <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />}
          {i.message} <span className="text-xs opacity-70">({modeLabel(i.mode)})</span>
        </li>
      ))}
    </ul>
  );
}

/** One-time override of a blocking gate (compliance:override), with a reason. */
function OverrideDialog({ data, onClose }: { data: SubcontractCompliance; onClose: () => void }) {
  const [f, setF] = useState({ gate: 'valuation' as 'valuation' | 'payment', billId: '', reason: '' });
  const save = useConstructionMutation<void>(() => constructionApi.override({
    subcontractId: data.subcontract.id, gate: f.gate, billId: f.gate === 'payment' ? f.billId : undefined, reason: f.reason.trim(),
  }), { success: tx('Override recorded; it is used by the next matching gate') });
  const bills = data.valuations.filter((v) => v.billId && num(v.billResidual) > 0);
  const valid = f.reason.trim().length >= 10 && (f.gate === 'valuation' || !!f.billId);
  return (
    <FormDialog open onClose={onClose} title={tx('Override compliance gate')} submitLabel={tx('Record override')} valid={valid}
      pending={save.isPending} onSubmit={() => save.mutate(undefined, { onSuccess: onClose })}
      description={tx('The override is audited and passes one blocked valuation, or the payment of one bill.')}>
      <Field label={tx('Gate')}>
        <select className={selectClass} value={f.gate} onChange={(e) => setF({ ...f, gate: e.target.value as 'valuation' | 'payment' })}>
          <option value="valuation">{tx('Next valuation')}</option>
          <option value="payment">{tx('Payment of a bill')}</option>
        </select>
      </Field>
      {f.gate === 'payment' && (
        <Field label={tx('Bill')}>
          <select className={selectClass} value={f.billId} onChange={(e) => setF({ ...f, billId: e.target.value })}>
            <option value="">{tx('Select…')}</option>
            {bills.map((v) => <option key={v.billId!} value={v.billId!}>{v.billNumber ?? v.number} · {num(v.billResidual).toFixed(2)}</option>)}
          </select>
        </Field>
      )}
      <Field label={tx('Reason (at least 10 characters)')} className="sm:col-span-2">
        <Input value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} />
      </Field>
    </FormDialog>
  );
}

/** Compliance of one subcontract: gate status, documents, lien waivers, overrides and the gate log. */
export function SubcontractComplianceDialog({ subcontractId, onClose }: { subcontractId: string | null; onClose: () => void }) {
  const perms = useCompliancePerms();
  const { data, isLoading } = useSubcontractCompliance(subcontractId);
  const [adding, setAdding] = useState<'document' | 'waiver' | null>(null);
  const [overriding, setOverriding] = useState(false);
  return (
    <Dialog open={!!subcontractId} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-4xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{data ? tx('Compliance · {{number}} · {{supplier}}', { number: data.subcontract.number, supplier: data.supplierName ?? '' }) : tx('Compliance')}</DialogTitle>
        </DialogHeader>
        {isLoading || !data ? <Skeleton className="h-64 w-full" /> : (
          <div className="space-y-4">
            <div className="flex flex-wrap items-start justify-between gap-2 rounded-lg border p-3">
              <Issues issues={data.valuationGate} />
              <div className="flex flex-wrap gap-2">
                {perms.manage && <Button size="sm" variant="outline" onClick={() => setAdding('document')}><Plus className="mr-1 h-3.5 w-3.5" />{tx('Document')}</Button>}
                {perms.manage && <Button size="sm" variant="outline" onClick={() => setAdding('waiver')}><Plus className="mr-1 h-3.5 w-3.5" />{tx('Lien waiver')}</Button>}
                {perms.override && <Button size="sm" variant="outline" onClick={() => setOverriding(true)}><KeyRound className="mr-1 h-3.5 w-3.5" />{tx('Override')}</Button>}
              </div>
            </div>
            <Section title={tx('Documents')}><DocumentsTable docs={data.documents} /></Section>
            <Section title={tx('Lien waivers')}><DocumentsTable docs={data.lienWaivers} waivers /></Section>
            {data.overrides.length > 0 && (
              <Section title={tx('Overrides')}>
                <Table head={[tx('Created'), tx('Gate'), tx('Reason'), tx('Used')]}>
                  {data.overrides.map((o) => (
                    <tr key={o.id}><td className={td}>{shortDate(o.createdAt)}</td><td className={td}>{o.gate}</td><td className={td}>{o.reason}</td>
                      <td className={td}>{o.consumedAt ? shortDate(o.consumedAt) : tx('Not yet')}</td></tr>
                  ))}
                </Table>
              </Section>
            )}
            {data.log.length > 0 && (
              <Section title={tx('Gate log')}>
                <Table head={[tx('When'), tx('Gate'), tx('Outcome'), tx('Issues')]}>
                  {data.log.map((l) => (
                    <tr key={l.id}><td className={td}>{shortDate(l.createdAt)}</td><td className={td}>{l.gate}</td><td className={td}>{l.outcome}</td>
                      <td className={td}>{(l.issues ?? []).map((i) => i.message).join('; ')}</td></tr>
                  ))}
                </Table>
              </Section>
            )}
            {adding && <DocumentFormDialog open partnerId={data.subcontract.supplierId} subcontract={data} waiver={adding === 'waiver'} onClose={() => setAdding(null)} />}
            {overriding && <OverrideDialog data={data} onClose={() => setOverriding(false)} />}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

/** Compliance tab of a supplier: what gating would see today, its documents, its subcontracts and the settings. */
export function SupplierComplianceTab({ partnerId }: { partnerId: string }) {
  const perms = useCompliancePerms();
  const { data, isLoading } = useSupplierCompliance(partnerId, perms.read);
  const [adding, setAdding] = useState(false);
  const [sub, setSub] = useState<string | null>(null);
  if (!perms.read) return <Empty>{tx('You need the compliance read permission.')}</Empty>;
  if (isLoading || !data) return <Skeleton className="h-64 w-full" />;
  return (
    <div className="space-y-4">
      <Section title={tx('Status today')} action={perms.manage && <Button size="sm" onClick={() => setAdding(true)}><Plus className="mr-1.5 h-4 w-4" />{tx('Record document')}</Button>}>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {data.summary.map((s) => (
            <div key={s.documentType} className="rounded-lg border p-3">
              <p className="text-xs text-muted-foreground">{documentTypeLabel(s.documentType)}</p>
              <div className="mt-1 flex items-center justify-between gap-2">
                <Pill state={s.state} label={s.state === 'valid' ? tx('Valid') : s.state === 'expired' ? tx('Expired') : tx('Missing')} />
                {s.validTo && <span className="text-xs text-muted-foreground">{tx('to')} {shortDate(s.validTo)}</span>}
              </div>
            </div>
          ))}
        </div>
      </Section>
      <Section title={tx('Documents')}><DocumentsTable docs={data.documents.filter((d) => d.documentType !== 'lien_waiver')} /></Section>
      <Section title={tx('Subcontracts')}>
        {data.subcontracts.length === 0 ? <Empty>{tx('No subcontracts with this supplier.')}</Empty> : (
          <Table head={[tx('Number'), tx('Project'), tx('Status'), { label: tx('Value'), right: true }, '']}>
            {data.subcontracts.map((s) => (
              <tr key={s.id}>
                <td className={cn(td, 'font-mono text-xs')}>{s.number}</td>
                <td className={td}>{s.projectCode} · {s.projectName}</td>
                <td className={td}>{s.status}</td>
                <td className={tdRight}><Money value={s.value} /></td>
                <td className={cn(td, 'text-right')}><Button size="sm" variant="outline" onClick={() => setSub(s.id)}>{tx('Compliance')}</Button></td>
              </tr>
            ))}
          </Table>
        )}
      </Section>
      <RequirementsPanel />
      {adding && <DocumentFormDialog open partnerId={partnerId} onClose={() => setAdding(false)} />}
      <SubcontractComplianceDialog subcontractId={sub} onClose={() => setSub(null)} />
    </div>
  );
}

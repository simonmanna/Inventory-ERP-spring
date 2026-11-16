import { useState, type ReactNode } from 'react';
import { CalendarPlus, CheckCircle2, FileCheck2, Gavel, MinusCircle, Pencil, Plus, ShieldCheck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';
import { useAuthStore } from '@/stores/auth.store';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { usePartnerOptions } from '@/features/crm/suite-api';
import { useProjectBoard } from '@/features/projects/api';
import { selectClass, shortDate, todayIso } from '@/features/projects/ui';
import {
  BOND_TYPES, LIVE_BOND, bondStatusLabel, bondTypeLabel, constructionApi, directionLabel, opt, useBond,
  useConstructionMutation, type Bond, type BondAction, type BondBody, type BondDirection, type BondType,
} from '@/features/projects/construction-api';
import { Empty, Field, FormDialog, Money, Table, isAmount, num, td, tdRight } from '../tabs/contract/shared';

/** Bond permissions of the signed-in user. */
export function useBondPerms() {
  const has = useAuthStore((s) => s.hasPermission);
  return { read: has(PERMISSIONS.bond.read), manage: has(PERMISSIONS.bond.manage), post: has(PERMISSIONS.bond.post) };
}

const TONE: Record<string, string> = {
  draft: 'bg-slate-100 text-slate-700 dark:bg-slate-500/20 dark:text-slate-300',
  active: 'bg-sky-100 text-sky-700 dark:bg-sky-500/20 dark:text-sky-300',
  extended: 'bg-sky-100 text-sky-700 dark:bg-sky-500/20 dark:text-sky-300',
  reduced: 'bg-sky-100 text-sky-700 dark:bg-sky-500/20 dark:text-sky-300',
  released: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300',
  called: 'bg-rose-100 text-rose-700 dark:bg-rose-500/20 dark:text-rose-300',
  expired: 'bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
};

export function BondStatus({ status }: { status: string }) {
  return <span className={cn('inline-flex rounded px-1.5 py-0.5 text-xs font-medium', TONE[status] ?? TONE.draft)}>{bondStatusLabel(status)}</span>;
}

function Expiry({ bond }: { bond: Bond }) {
  const d = bond.daysToExpiry;
  return (
    <span className={cn(d != null && d <= 7 ? 'font-medium text-rose-600' : d != null && d <= 30 ? 'text-amber-600' : '')}>
      {shortDate(bond.expiryDate)}{d != null && <span className="ml-1 text-xs text-muted-foreground">({d < 0 ? tx('lapsed') : tx('{{n}} d', { n: d })})</span>}
    </span>
  );
}

/** Bonds table with the lifecycle actions the user may take. */
export function BondTable({ bonds, showProject = true, onOpen }: { bonds: Bond[]; showProject?: boolean; onOpen: (b: Bond) => void }) {
  if (bonds.length === 0) return <Empty>{tx('No bonds or guarantees yet.')}</Empty>;
  return (
    <Table head={[tx('Number'), tx('Type'), tx('Direction'), ...(showProject ? [tx('Project')] : []), tx('Counterparty'), tx('Issuer'),
      { label: tx('Amount'), right: true }, tx('Expiry'), tx('Status')]}>
      {bonds.map((b) => (
        <tr key={b.id} className="cursor-pointer hover:bg-muted/40" onClick={() => onOpen(b)}>
          <td className={cn(td, 'font-mono text-xs')}>{b.number}{b.bondNumber && <div className="text-muted-foreground">{b.bondNumber}</div>}</td>
          <td className={td}>{bondTypeLabel(b.bondType)}</td>
          <td className={td}>{directionLabel(b.direction)}</td>
          {showProject && <td className={td}>{b.projectCode ?? '—'}</td>}
          <td className={td}>{b.counterpartyName}</td>
          <td className={td}>{b.issuer}</td>
          <td className={tdRight}><Money value={b.amount} /> <span className="text-xs text-muted-foreground">{b.currencyCode}</span></td>
          <td className={td}><Expiry bond={b} /></td>
          <td className={td}><BondStatus status={b.status} /></td>
        </tr>
      ))}
    </Table>
  );
}

const emptyForm = (projectId?: string): Required<Omit<BondBody, 'subcontractId' | 'currencyCode'>> & { currencyCode: string } => ({
  bondType: 'performance', direction: 'issued', projectId: projectId ?? '', counterpartyId: '', issuer: '', issuerType: 'bank',
  bondNumber: '', amount: '', currencyCode: '', issueDate: todayIso(), expiryDate: '', notes: '',
});

/** Create (or edit a draft) bond / guarantee. */
export function BondFormDialog({ open, onClose, projectId, bond }: { open: boolean; onClose: () => void; projectId?: string; bond?: Bond | null }) {
  const [f, setF] = useState(() => (bond ? {
    bondType: bond.bondType, direction: bond.direction, projectId: bond.projectId ?? '', counterpartyId: bond.counterpartyId,
    issuer: bond.issuer, issuerType: bond.issuerType, bondNumber: bond.bondNumber ?? '', amount: String(bond.amount),
    currencyCode: bond.currencyCode, issueDate: bond.issueDate, expiryDate: bond.expiryDate, notes: bond.notes ?? '',
  } : emptyForm(projectId)));
  const [search, setSearch] = useState('');
  const { data: partners = [] } = usePartnerOptions(search, open);
  const { data: projects = [] } = useProjectBoard({});
  const body: BondBody = {
    bondType: f.bondType as BondType, direction: f.direction as BondDirection, projectId: opt(f.projectId), counterpartyId: f.counterpartyId,
    issuer: f.issuer.trim(), issuerType: f.issuerType as 'bank' | 'insurer', bondNumber: opt(f.bondNumber), amount: f.amount.trim(),
    currencyCode: opt(f.currencyCode), issueDate: f.issueDate, expiryDate: f.expiryDate, notes: opt(f.notes),
  };
  const save = useConstructionMutation<void, Bond>(() => (bond ? constructionApi.updateBond(bond.id, body) : constructionApi.createBond(body)),
    { success: bond ? tx('Bond updated') : tx('Bond recorded') });
  const valid = !!f.counterpartyId && f.issuer.trim() !== '' && isAmount(f.amount) && num(f.amount) > 0 && !!f.issueDate
    && !!f.expiryDate && f.expiryDate >= f.issueDate;

  return (
    <FormDialog open={open} onClose={onClose} title={bond ? tx('Edit bond {{number}}', { number: bond.number }) : tx('New bond or guarantee')}
      submitLabel={bond ? tx('Save') : tx('Record bond')} valid={valid} pending={save.isPending}
      onSubmit={() => save.mutate(undefined, { onSuccess: onClose })}>
      <Field label={tx('Type')}>
        <select className={selectClass} value={f.bondType} onChange={(e) => setF({ ...f, bondType: e.target.value as BondType })}>
          {BOND_TYPES.map((t) => <option key={t} value={t}>{bondTypeLabel(t)}</option>)}
        </select>
      </Field>
      <Field label={tx('Direction')}>
        <select className={selectClass} value={f.direction} onChange={(e) => setF({ ...f, direction: e.target.value as BondDirection })}>
          <option value="issued">{directionLabel('issued')}</option>
          <option value="received">{directionLabel('received')}</option>
        </select>
      </Field>
      <Field label={tx('Project')} className="sm:col-span-2">
        <select className={selectClass} value={f.projectId} disabled={!!projectId} onChange={(e) => setF({ ...f, projectId: e.target.value })}>
          <option value="">{tx('No project (e.g. a bid bond)')}</option>
          {projects.map((p) => <option key={p.id} value={p.id}>{p.code} · {p.name}</option>)}
        </select>
      </Field>
      <Field label={f.direction === 'issued' ? tx('Beneficiary (client)') : tx('Principal (subcontractor / supplier)')} className="sm:col-span-2">
        <SearchableSelect value={f.counterpartyId} onValueChange={(id) => setF({ ...f, counterpartyId: id })} onQueryChange={setSearch}
          options={partners.map((p) => ({ value: p.id, label: p.name, code: p.code ?? undefined }))} placeholder={tx('Select a partner…')} />
      </Field>
      <Field label={tx('Issuing bank / insurer')}>
        <Input value={f.issuer} onChange={(e) => setF({ ...f, issuer: e.target.value })} />
      </Field>
      <Field label={tx('Issuer type')}>
        <select className={selectClass} value={f.issuerType} onChange={(e) => setF({ ...f, issuerType: e.target.value as 'bank' | 'insurer' })}>
          <option value="bank">{tx('Bank')}</option>
          <option value="insurer">{tx('Insurer')}</option>
        </select>
      </Field>
      <Field label={tx('Bond / guarantee number')}>
        <Input value={f.bondNumber} onChange={(e) => setF({ ...f, bondNumber: e.target.value })} />
      </Field>
      <Field label={tx('Amount')}>
        <Input type="number" step="0.01" min={0} value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} />
      </Field>
      <Field label={tx('Currency')} hint={tx('Blank = base currency')}>
        <Input value={f.currencyCode} maxLength={3} onChange={(e) => setF({ ...f, currencyCode: e.target.value.toUpperCase() })} />
      </Field>
      <Field label={tx('Issue date')}>
        <Input type="date" value={f.issueDate} onChange={(e) => setF({ ...f, issueDate: e.target.value })} />
      </Field>
      <Field label={tx('Expiry date')}>
        <Input type="date" value={f.expiryDate} onChange={(e) => setF({ ...f, expiryDate: e.target.value })} />
      </Field>
      <Field label={tx('Notes')} className="sm:col-span-2">
        <Input value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} />
      </Field>
    </FormDialog>
  );
}

const ACTIONS: Array<{ id: BondAction; label: () => string; icon: typeof Plus }> = [
  { id: 'activate', label: () => tx('Activate'), icon: CheckCircle2 },
  { id: 'extend', label: () => tx('Extend'), icon: CalendarPlus },
  { id: 'reduce', label: () => tx('Reduce'), icon: MinusCircle },
  { id: 'release', label: () => tx('Release'), icon: ShieldCheck },
  { id: 'call', label: () => tx('Call'), icon: Gavel },
];

function allowed(b: Bond, a: BondAction) {
  if (a === 'activate') return b.status === 'draft';
  if (a === 'release') return LIVE_BOND.includes(b.status) || b.status === 'expired';
  return LIVE_BOND.includes(b.status);
}

/** One lifecycle step; the fields shown depend on the step and the bond's direction. */
function BondActionDialog({ bond, action, onClose }: { bond: Bond; action: BondAction | null; onClose: () => void }) {
  const [f, setF] = useState({ date: todayIso(), amount: '', expiryDate: '', margin: '', commission: '', recoveryTo: 'project_cost', reason: '' });
  const issued = bond.direction === 'issued';
  const run = useConstructionMutation<void, Bond>(() => constructionApi.bondAction(bond.id, action!, {
    date: opt(f.date), amount: opt(f.amount), expiryDate: opt(f.expiryDate), margin: opt(f.margin), commission: opt(f.commission),
    recoveryTo: action === 'call' && !issued ? (f.recoveryTo as 'project_cost' | 'claim_income') : undefined, reason: opt(f.reason),
  }), { success: tx('Bond updated') });
  const valid = action === 'extend' ? !!f.expiryDate && f.expiryDate > bond.expiryDate
    : action === 'reduce' ? isAmount(f.amount) && num(f.amount) > 0 && num(f.amount) < num(bond.amount)
      : action === 'call' ? f.reason.trim() !== '' : true;
  if (!action) return null;
  return (
    <FormDialog open onClose={onClose} title={`${ACTIONS.find((a) => a.id === action)?.label()} · ${bond.number}`}
      description={issued ? tx('Margin and commission post to the bank account mapped as default bank.') : undefined}
      submitLabel={tx('Confirm')} valid={valid} pending={run.isPending} onSubmit={() => run.mutate(undefined, { onSuccess: onClose })}>
      <Field label={tx('Date')}><Input type="date" value={f.date} onChange={(e) => setF({ ...f, date: e.target.value })} /></Field>
      {action === 'extend' && (
        <Field label={tx('New expiry date')}><Input type="date" value={f.expiryDate} min={bond.expiryDate} onChange={(e) => setF({ ...f, expiryDate: e.target.value })} /></Field>
      )}
      {(action === 'reduce' || action === 'call') && (
        <Field label={action === 'reduce' ? tx('New guaranteed amount') : tx('Amount called (blank = all)')}>
          <Input type="number" step="0.01" min={0} value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} />
        </Field>
      )}
      {issued && action === 'activate' && (
        <Field label={tx('Cash margin lodged')}><Input type="number" step="0.01" min={0} value={f.margin} onChange={(e) => setF({ ...f, margin: e.target.value })} /></Field>
      )}
      {issued && action === 'reduce' && (
        <Field label={tx('Margin returned')} hint={tx('Held: {{m}}', { m: num(bond.marginHeld).toFixed(2) })}>
          <Input type="number" step="0.01" min={0} value={f.margin} onChange={(e) => setF({ ...f, margin: e.target.value })} />
        </Field>
      )}
      {issued && (action === 'activate' || action === 'extend') && (
        <Field label={tx('Bank commission')}><Input type="number" step="0.01" min={0} value={f.commission} onChange={(e) => setF({ ...f, commission: e.target.value })} /></Field>
      )}
      {!issued && action === 'call' && (
        <Field label={tx('Recovery to')}>
          <select className={selectClass} value={f.recoveryTo} onChange={(e) => setF({ ...f, recoveryTo: e.target.value })}>
            <option value="project_cost">{tx('Project cost (reduces WIP / CIP)')}</option>
            <option value="claim_income">{tx('Claim income')}</option>
          </select>
        </Field>
      )}
      <Field label={tx('Reason')} className="sm:col-span-2"><Input value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} /></Field>
    </FormDialog>
  );
}

/** Bond detail: figures, lifecycle actions, history and the scanned guarantee. */
export function BondDetailDialog({ bondId, onClose }: { bondId: string | null; onClose: () => void }) {
  const perms = useBondPerms();
  const { data: bond } = useBond(bondId);
  const [action, setAction] = useState<BondAction | null>(null);
  const [editing, setEditing] = useState(false);
  return (
    <Dialog open={!!bondId} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-3xl overflow-y-auto">
        <DialogHeader><DialogTitle>{bond ? `${bond.number} · ${bondTypeLabel(bond.bondType)}` : tx('Bond')}</DialogTitle></DialogHeader>
        {bond && (
          <div className="space-y-4 text-sm">
            <div className="grid gap-3 sm:grid-cols-3">
              <Info label={tx('Direction')} value={directionLabel(bond.direction)} />
              <Info label={tx('Counterparty')} value={bond.counterpartyName} />
              <Info label={tx('Issuer')} value={`${bond.issuer}${bond.bondNumber ? ` · ${bond.bondNumber}` : ''}`} />
              <Info label={tx('Amount')} value={<><Money value={bond.amount} /> {bond.currencyCode}</>} />
              <Info label={tx('Valid')} value={`${shortDate(bond.issueDate)} → ${shortDate(bond.expiryDate)}`} />
              <Info label={tx('Status')} value={<BondStatus status={bond.status} />} />
              <Info label={tx('Margin held')} value={<Money value={bond.marginHeld} />} />
              <Info label={tx('Commission paid')} value={<Money value={bond.commissionPaid} />} />
              {num(bond.calledAmount) > 0 && <Info label={tx('Called')} value={<Money value={bond.calledAmount} />} />}
            </div>
            <div className="flex flex-wrap gap-2">
              {perms.manage && bond.status === 'draft' && (
                <Button size="sm" variant="outline" onClick={() => setEditing(true)}><Pencil className="mr-1 h-3.5 w-3.5" />{tx('Edit')}</Button>
              )}
              {perms.post && ACTIONS.filter((a) => allowed(bond, a.id)).map((a) => (
                <Button key={a.id} size="sm" variant={a.id === 'call' ? 'destructive' : 'outline'} onClick={() => setAction(a.id)}>
                  <a.icon className="mr-1 h-3.5 w-3.5" />{a.label()}
                </Button>
              ))}
            </div>
            <Table head={[tx('Date'), tx('Step'), { label: tx('Amount'), right: true }, tx('Expiry'), { label: tx('Margin'), right: true },
              { label: tx('Commission'), right: true }, tx('Reason')]}>
              {(bond.events ?? []).map((e) => (
                <tr key={e.id}>
                  <td className={td}>{shortDate(e.eventDate)}</td>
                  <td className={td}>{e.action}{e.journalEntryId && <FileCheck2 className="ml-1 inline h-3.5 w-3.5 text-emerald-600" />}</td>
                  <td className={tdRight}>{num(e.calledAmount) > 0 ? <Money value={e.calledAmount} /> : e.amount != null ? <Money value={e.amount} /> : '—'}</td>
                  <td className={td}>{e.expiryDate ? shortDate(e.expiryDate) : '—'}</td>
                  <td className={tdRight}>{num(e.margin) !== 0 ? <Money value={e.margin} /> : '—'}</td>
                  <td className={tdRight}>{num(e.commission) !== 0 ? <Money value={e.commission} /> : '—'}</td>
                  <td className={td}>{e.reason ?? ''}{e.recoveryTo && <span className="text-xs text-muted-foreground"> · {e.recoveryTo}</span>}</td>
                </tr>
              ))}
            </Table>
            <AttachmentPanel resType="project_bond" resId={bond.id} title={tx('Guarantee documents')} writePermission={PERMISSIONS.bond.manage} bare />
            {action && <BondActionDialog bond={bond} action={action} onClose={() => setAction(null)} />}
            {editing && <BondFormDialog open bond={bond} onClose={() => setEditing(false)} />}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

function Info({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <div className="font-medium">{value ?? '—'}</div>
    </div>
  );
}


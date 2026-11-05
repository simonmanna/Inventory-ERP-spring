import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Pencil, Plus, Trash2, Star, MoreHorizontal, UserCheck, UserX, Users } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { SearchableSelect } from '@/components/ui/searchable-select';
import {
  Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription,
  AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';
import { api } from '@/lib/api';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { useAccounts, usePaymentTerms } from '@/features/accounting/api';
import { useHrEmployees, type HrEmployee } from '@/features/hr/api';
import { usePaymentModes } from '@/features/sales/cash-sales-api';
import {
  INCOTERMS, type AutoPostBills, type ContactInput, type PartnerBankAccount, type PartnerContact, type PartnerProfile,
} from '@/features/partners/api';

/* ------------------------------------------------------------------ layout primitives */

export const selectCls =
  'flex h-9 w-full rounded-md border border-input bg-background px-3 text-sm shadow-sm transition-colors '
  + 'focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-60';

/** Section heading + hairline, Odoo-style form group. */
export function FormSection({ title, children, className, action }: {
  title: string; children: React.ReactNode; className?: string; action?: React.ReactNode;
}) {
  return (
    <section className={cn('space-y-1', className)}>
      <div className="flex items-center justify-between border-b pb-1.5 mb-2">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{title}</h3>
        {action}
      </div>
      {children}
    </section>
  );
}

/** Label on the left, control on the right; stacks on phones. */
export function FieldRow({ label, hint, children, htmlFor }: {
  label: string; hint?: string; children: React.ReactNode; htmlFor?: string;
}) {
  return (
    <div className="grid grid-cols-1 sm:grid-cols-[170px_1fr] sm:items-center gap-1 sm:gap-4 py-1.5">
      <Label htmlFor={htmlFor} className="text-sm font-medium text-foreground/80" title={hint}>
        {label}
        {hint ? <span className="ml-1 text-muted-foreground cursor-help">?</span> : null}
      </Label>
      <div className="min-w-0">{children}</div>
    </div>
  );
}

export function ReadValue({ children }: { children: React.ReactNode }) {
  return <div className="text-sm min-h-9 flex items-center">{children ?? <span className="text-muted-foreground">—</span>}</div>;
}

/* ------------------------------------------------------------------ general info */

export interface GeneralValues {
  name: string;
  code: string;
  isCompany: boolean;
  email: string;
  phone: string;
  website: string;
  taxNumber: string;
  categoryId: string;
  membershipLevel: string;
  gender: string;
  status: string;
  notes: string;
}

export const EMPTY_GENERAL: GeneralValues = {
  name: '', code: '', isCompany: false, email: '', phone: '', website: '', taxNumber: '',
  categoryId: '', membershipLevel: '', gender: '', status: 'active', notes: '',
};

export function usePartnerCategories() {
  return useQuery({
    queryKey: ['partner-categories', 'all'],
    queryFn: async () =>
      (await api.get<{ data: Array<{ id: string; name: string }> }>('/partner-categories', { params: { pageSize: 200 } }))
        .data.data ?? [],
    staleTime: 60_000,
  });
}

/** Kind switch (individual / company) shown in the page header. */
export function KindToggle({ isCompany, onChange }: { isCompany: boolean; onChange: (v: boolean) => void }) {
  return (
    <div className="inline-flex rounded-md border bg-muted/40 p-0.5 text-xs">
      {[{ v: false, l: tx('Individual') }, { v: true, l: tx('Company') }].map((o) => (
        <button
          key={String(o.v)}
          type="button"
          onClick={() => onChange(o.v)}
          className={cn(
            'rounded px-3 py-1 font-medium transition-colors',
            isCompany === o.v ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {o.l}
        </button>
      ))}
    </div>
  );
}

export function GeneralFields({ value, onChange, isCustomer }: {
  value: GeneralValues; onChange: (patch: Partial<GeneralValues>) => void; isCustomer: boolean;
}) {
  const { data: categories = [] } = usePartnerCategories();
  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-x-10 gap-y-4">
      <FormSection title={tx('Contact details')}>
        <FieldRow label={tx('Email')} htmlFor="p-email">
          <Input id="p-email" type="email" value={value.email} onChange={(e) => onChange({ email: e.target.value })} placeholder="name@example.com" />
        </FieldRow>
        <FieldRow label={tx('Phone')} htmlFor="p-phone">
          <Input id="p-phone" value={value.phone} onChange={(e) => onChange({ phone: e.target.value })} />
        </FieldRow>
        <FieldRow label={tx('Website')} htmlFor="p-web">
          <Input id="p-web" value={value.website} onChange={(e) => onChange({ website: e.target.value })} placeholder="https://" />
        </FieldRow>
        <FieldRow label={tx('Tax Number')} htmlFor="p-tax">
          <Input id="p-tax" value={value.taxNumber} onChange={(e) => onChange({ taxNumber: e.target.value })} />
        </FieldRow>
      </FormSection>
      <FormSection title={tx('Classification')}>
        <FieldRow label={tx('Code')} htmlFor="p-code" hint={tx('Leave empty to generate one')}>
          <Input id="p-code" value={value.code} onChange={(e) => onChange({ code: e.target.value })} placeholder={tx('Auto')} />
        </FieldRow>
        <FieldRow label={tx('Category')} htmlFor="p-cat">
          <select id="p-cat" className={selectCls} value={value.categoryId} onChange={(e) => onChange({ categoryId: e.target.value })}>
            <option value="">{tx('None')}</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </FieldRow>
        {isCustomer && (
          <FieldRow label={tx('Membership')} htmlFor="p-mem">
            <select id="p-mem" className={selectCls} value={value.membershipLevel} onChange={(e) => onChange({ membershipLevel: e.target.value })}>
              <option value="">{tx('None')}</option>
              <option value="bronze">{tx('Bronze')}</option>
              <option value="silver">{tx('Silver')}</option>
              <option value="gold">{tx('Gold')}</option>
            </select>
          </FieldRow>
        )}
        {!value.isCompany && (
          <FieldRow label={tx('Gender')} htmlFor="p-gender">
            <select id="p-gender" className={selectCls} value={value.gender} onChange={(e) => onChange({ gender: e.target.value })}>
              <option value="">{tx('Not specified')}</option>
              <option value="male">{tx('Male')}</option>
              <option value="female">{tx('Female')}</option>
              <option value="other">{tx('Other')}</option>
              <option value="prefer_not_to_say">{tx('Prefer not to say')}</option>
            </select>
          </FieldRow>
        )}
        <FieldRow label={tx('Status')} htmlFor="p-status">
          <select id="p-status" className={selectCls} value={value.status} onChange={(e) => onChange({ status: e.target.value })}>
            <option value="active">{tx('Active')}</option>
            <option value="inactive">{tx('Inactive')}</option>
            <option value="archived">{tx('Archived')}</option>
          </select>
        </FieldRow>
      </FormSection>
      <FormSection title={tx('Internal notes')} className="lg:col-span-2">
        <Textarea rows={3} value={value.notes} onChange={(e) => onChange({ notes: e.target.value })} placeholder={tx('Notes visible to your team only')} />
      </FormSection>
    </div>
  );
}

/** PATCH / POST body from the general form (blank → null so cleared fields really clear). */
export function generalPayload(v: GeneralValues) {
  const n = (s: string) => (s.trim() ? s.trim() : null);
  return {
    name: v.name.trim(),
    ...(v.code.trim() ? { code: v.code.trim() } : {}),
    isCompany: v.isCompany,
    email: n(v.email),
    phone: n(v.phone),
    website: n(v.website),
    taxNumber: n(v.taxNumber),
    categoryId: n(v.categoryId),
    membershipLevel: n(v.membershipLevel),
    gender: v.isCompany ? null : n(v.gender),
    status: v.status || 'active',
    notes: n(v.notes),
  };
}

/* ------------------------------------------------------------------ sales configuration */

export function SalesConfigFields({ value, onChange, disabled }: {
  value: PartnerProfile; onChange: (patch: Partial<PartnerProfile>) => void; disabled?: boolean;
}) {
  const { data: employees } = useHrEmployees({ pageSize: 200 });
  const { data: terms = [] } = usePaymentTerms();
  const { data: modes = [] } = usePaymentModes();
  const people = ((employees as { data?: HrEmployee[] } | undefined)?.data ?? []);
  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-x-10 gap-y-4">
      <FormSection title={tx('Sales')}>
        <FieldRow label={tx('Salesperson')} htmlFor="sc-sp" hint={tx('Default salesperson on new quotations, orders and invoices')}>
          <select id="sc-sp" disabled={disabled} className={selectCls} value={value.salespersonId ?? ''} onChange={(e) => onChange({ salespersonId: e.target.value || null })}>
            <option value="">{tx('None')}</option>
            {people.map((e) => <option key={e.id} value={e.id}>{[e.firstName, e.lastName].filter(Boolean).join(' ')}</option>)}
          </select>
        </FieldRow>
        <FieldRow label={tx('Payment Terms')} htmlFor="sc-pt">
          <select id="sc-pt" disabled={disabled} className={selectCls} value={value.paymentTermId ?? ''} onChange={(e) => onChange({ paymentTermId: e.target.value || null })}>
            <option value="">{tx('Immediate / organization default')}</option>
            {terms.filter((t) => t.isActive || t.id === value.paymentTermId).map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
        </FieldRow>
        <FieldRow label={tx('Payment Method')} htmlFor="sc-pm">
          <select id="sc-pm" disabled={disabled} className={selectCls} value={value.paymentModeId ?? ''} onChange={(e) => onChange({ paymentModeId: e.target.value || null })}>
            <option value="">{tx('Any')}</option>
            {modes.map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}
          </select>
        </FieldRow>
      </FormSection>
      <FormSection title={tx('Shipping')}>
        <FieldRow label={tx('Incoterm')} htmlFor="sc-inc" hint={tx('International commercial terms used on documents')}>
          <select id="sc-inc" disabled={disabled} className={selectCls} value={value.incoterm ?? ''} onChange={(e) => onChange({ incoterm: e.target.value || null })}>
            <option value="">{tx('None')}</option>
            {INCOTERMS.map((i) => <option key={i} value={i}>{i}</option>)}
          </select>
        </FieldRow>
        <FieldRow label={tx('Incoterm Location')} htmlFor="sc-loc">
          <Input id="sc-loc" disabled={disabled} value={value.incotermLocation ?? ''} onChange={(e) => onChange({ incotermLocation: e.target.value || null })} placeholder={tx('e.g. Port of Mombasa')} />
        </FieldRow>
      </FormSection>
    </div>
  );
}

/* ------------------------------------------------------------------ accounting */

const AUTO_POST_LABEL: Record<AutoPostBills, string> = {
  ask: tx('Ask after 3 validations without edits'),
  always: tx('Always'),
  never: tx('Never'),
};

const EMPTY_BANK: PartnerBankAccount = { bankName: null, accountNumber: null, holderName: null, iban: null, swift: null };

export function AccountingFields({ value, onChange, disabled }: {
  value: PartnerProfile; onChange: (patch: Partial<PartnerProfile>) => void; disabled?: boolean;
}) {
  const { data: accounts } = useAccounts();
  const options = useMemo(() => {
    const rows = [...(accounts?.data ?? [])].sort((a, b) => a.code.localeCompare(b.code));
    return [{ value: '', label: tx('Organization default') }, ...rows.map((a) => ({ value: a.id, code: a.code, label: a.name, keywords: a.code }))];
  }, [accounts]);
  const banks = value.bankAccounts ?? [];
  const setBank = (i: number, patch: Partial<PartnerBankAccount>) =>
    onChange({ bankAccounts: banks.map((b, j) => (j === i ? { ...b, ...patch } : b)) });

  return (
    <div className="space-y-6">
      <FormSection
        title={tx('Bank accounts')}
        action={!disabled && (
          <Button type="button" size="sm" variant="ghost" className="h-7 text-xs" onClick={() => onChange({ bankAccounts: [...banks, { ...EMPTY_BANK }] })}>
            <Plus className="mr-1 h-3.5 w-3.5" /> {tx('Add bank account')}
          </Button>
        )}
      >
        {banks.length === 0 ? (
          <p className="py-3 text-sm text-muted-foreground">{tx('No bank accounts yet.')}</p>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Bank')}</TableHead>
                  <TableHead>{tx('Account Number')}</TableHead>
                  <TableHead>{tx('Account Holder')}</TableHead>
                  <TableHead>{tx('IBAN')}</TableHead>
                  <TableHead>{tx('SWIFT / BIC')}</TableHead>
                  <TableHead className="w-10" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {banks.map((b, i) => (
                  <TableRow key={i}>
                    {(['bankName', 'accountNumber', 'holderName', 'iban', 'swift'] as const).map((k) => (
                      <TableCell key={k} className="py-1.5">
                        <Input
                          disabled={disabled}
                          className="h-8 min-w-[120px]"
                          value={b[k] ?? ''}
                          aria-label={k}
                          onChange={(e) => setBank(i, { [k]: e.target.value || null })}
                        />
                      </TableCell>
                    ))}
                    <TableCell className="py-1.5">
                      {!disabled && (
                        <Button type="button" size="icon" variant="ghost" className="h-8 w-8 text-muted-foreground hover:text-destructive" aria-label={tx('Remove')} onClick={() => onChange({ bankAccounts: banks.filter((_, j) => j !== i) })}>
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </FormSection>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-x-10 gap-y-4">
        <FormSection title={tx('Accounting entries')}>
          <FieldRow label={tx('Account Receivable')}>
            <SearchableSelect disabled={disabled} value={value.receivableAccountId ?? ''} onValueChange={(v) => onChange({ receivableAccountId: v || null })} options={options} placeholder={tx('Organization default')} />
          </FieldRow>
          <FieldRow label={tx('Account Payable')}>
            <SearchableSelect disabled={disabled} value={value.payableAccountId ?? ''} onValueChange={(v) => onChange({ payableAccountId: v || null })} options={options} placeholder={tx('Organization default')} />
          </FieldRow>
        </FormSection>
        <FormSection title={tx('Vendor bills')}>
          <FieldRow label={tx('Auto-post bills')} htmlFor="ac-auto" hint={tx('Post vendor bills of this partner automatically once validated')}>
            <select id="ac-auto" disabled={disabled} className={selectCls} value={value.autoPostBills} onChange={(e) => onChange({ autoPostBills: e.target.value as AutoPostBills })}>
              {(Object.keys(AUTO_POST_LABEL) as AutoPostBills[]).map((k) => <option key={k} value={k}>{AUTO_POST_LABEL[k]}</option>)}
            </select>
          </FieldRow>
        </FormSection>
      </div>
    </div>
  );
}

/** Drop incomplete bank rows (no account number) before saving. */
export function cleanProfile(p: PartnerProfile): PartnerProfile {
  return { ...p, bankAccounts: (p.bankAccounts ?? []).filter((b) => (b.accountNumber ?? '').trim()) };
}

/* ------------------------------------------------------------------ contacts table */

type ContactRow = PartnerContact;

const EMPTY_CONTACT: ContactInput = { firstName: '', lastName: '', position: '', email: '', phone: '', isPrimary: false };

function contactName(c: ContactRow) {
  return [c.firstName, c.lastName].filter(Boolean).join(' ') || '—';
}

/**
 * Contacts list with add / edit / set-primary / (de)activate / delete. Works against the API (detail page) or a
 * local array (new partner page) — the caller supplies the handlers.
 */
export function ContactsTable({ contacts, canEdit, busy, onAdd, onUpdate, onDelete }: {
  contacts: ContactRow[];
  canEdit: boolean;
  busy?: boolean;
  onAdd: (c: ContactInput) => Promise<unknown> | void;
  onUpdate: (id: string, c: ContactInput) => Promise<unknown> | void;
  onDelete: (id: string) => Promise<unknown> | void;
}) {
  const [editing, setEditing] = useState<ContactRow | 'new' | null>(null);
  const [form, setForm] = useState<ContactInput>(EMPTY_CONTACT);
  const [deleting, setDeleting] = useState<ContactRow | null>(null);
  const [error, setError] = useState<string | null>(null);

  const open = (c: ContactRow | 'new') => {
    setEditing(c);
    setError(null);
    setForm(c === 'new' ? { ...EMPTY_CONTACT, isPrimary: contacts.length === 0 } : {
      firstName: c.firstName, lastName: c.lastName ?? '', position: c.position ?? '', email: c.email ?? '',
      phone: c.phone ?? '', isPrimary: c.isPrimary,
    });
  };

  const save = async () => {
    if (!form.firstName?.trim()) {
      setError(tx('First name is required'));
      return;
    }
    if (form.email && !/^\S+@\S+\.\S+$/.test(form.email)) {
      setError(tx('Invalid email'));
      return;
    }
    const n = (s?: string | null) => (s && s.trim() ? s.trim() : null);
    const body: ContactInput = {
      firstName: form.firstName.trim(), lastName: n(form.lastName), position: n(form.position),
      email: n(form.email), phone: n(form.phone), isPrimary: !!form.isPrimary,
    };
    try {
      if (editing === 'new') await onAdd(body);
      else if (editing) await onUpdate(editing.id, body);
      setEditing(null);
    } catch (e: any) {
      setError(e?.response?.data?.message ?? e?.message ?? tx('Could not save contact'));
    }
  };

  const sorted = [...contacts].sort((a, b) => Number(b.isPrimary) - Number(a.isPrimary)
    || Number(b.isActive !== false) - Number(a.isActive !== false));

  return (
    <div className="rounded-xl border bg-card">
      <div className="flex items-center justify-between px-4 py-3 border-b">
        <div className="text-sm text-muted-foreground">
          {contacts.length === 0 ? tx('No contacts yet') : `${contacts.length} ${contacts.length === 1 ? tx('contact') : tx('contacts')}`}
        </div>
        {canEdit && (
          <Button size="sm" onClick={() => open('new')} disabled={busy}>
            <Plus className="mr-1.5 h-4 w-4" /> {tx('Add contact')}
          </Button>
        )}
      </div>
      {contacts.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-12 text-center">
          <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-muted">
            <Users className="h-6 w-6 text-muted-foreground" />
          </div>
          <p className="text-sm text-muted-foreground">{tx('Add the people you deal with at this partner.')}</p>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Name')}</TableHead>
                <TableHead>{tx('Position')}</TableHead>
                <TableHead>{tx('Email')}</TableHead>
                <TableHead>{tx('Phone')}</TableHead>
                <TableHead>{tx('Status')}</TableHead>
                {canEdit && <TableHead className="w-12" />}
              </TableRow>
            </TableHeader>
            <TableBody>
              {sorted.map((c) => {
                const active = c.isActive !== false;
                return (
                  <TableRow key={c.id} className={cn(!active && 'opacity-60')}>
                    <TableCell className="font-medium">
                      <div className="flex items-center gap-2">
                        <div className="flex h-7 w-7 items-center justify-center rounded-full bg-primary/10 text-[11px] font-semibold text-primary">
                          {(c.firstName?.[0] ?? '?').toUpperCase()}
                        </div>
                        {contactName(c)}
                      </div>
                    </TableCell>
                    <TableCell className="text-sm">{c.position ?? <span className="text-muted-foreground">—</span>}</TableCell>
                    <TableCell className="text-sm">{c.email ?? <span className="text-muted-foreground">—</span>}</TableCell>
                    <TableCell className="text-sm">{c.phone ?? <span className="text-muted-foreground">—</span>}</TableCell>
                    <TableCell>
                      <div className="flex gap-1">
                        {c.isPrimary && <Badge variant="outline" className="border-primary/30 bg-primary/5 text-primary text-xs">{tx('Primary')}</Badge>}
                        {active
                          ? <Badge variant="outline" className="border-emerald-200 bg-emerald-50 text-emerald-700 text-xs">{tx('Active')}</Badge>
                          : <Badge variant="secondary" className="text-xs">{tx('Inactive')}</Badge>}
                      </div>
                    </TableCell>
                    {canEdit && (
                      <TableCell>
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Contact actions')} disabled={busy}>
                              <MoreHorizontal className="h-4 w-4" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end">
                            <DropdownMenuItem onClick={() => open(c)}><Pencil className="mr-2 h-4 w-4" />{tx('Edit')}</DropdownMenuItem>
                            {!c.isPrimary && active && (
                              <DropdownMenuItem onClick={() => onUpdate(c.id, { isPrimary: true })}><Star className="mr-2 h-4 w-4" />{tx('Set as primary')}</DropdownMenuItem>
                            )}
                            {active ? (
                              !c.isPrimary && <DropdownMenuItem onClick={() => onUpdate(c.id, { isActive: false })}><UserX className="mr-2 h-4 w-4" />{tx('Deactivate')}</DropdownMenuItem>
                            ) : (
                              <DropdownMenuItem onClick={() => onUpdate(c.id, { isActive: true })}><UserCheck className="mr-2 h-4 w-4" />{tx('Activate')}</DropdownMenuItem>
                            )}
                            <DropdownMenuSeparator />
                            <DropdownMenuItem className="text-destructive focus:text-destructive" onClick={() => setDeleting(c)}>
                              <Trash2 className="mr-2 h-4 w-4" />{tx('Delete')}
                            </DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </TableCell>
                    )}
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      )}

      <Dialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)}>
        <DialogContent className="sm:max-w-[520px]">
          <DialogHeader>
            <DialogTitle>{editing === 'new' ? tx('New contact') : tx('Edit contact')}</DialogTitle>
          </DialogHeader>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 py-2">
            <div className="space-y-1.5">
              <Label htmlFor="c-first">{tx('First Name')} *</Label>
              <Input id="c-first" autoFocus value={form.firstName ?? ''} onChange={(e) => setForm({ ...form, firstName: e.target.value })} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="c-last">{tx('Last Name')}</Label>
              <Input id="c-last" value={form.lastName ?? ''} onChange={(e) => setForm({ ...form, lastName: e.target.value })} />
            </div>
            <div className="space-y-1.5 sm:col-span-2">
              <Label htmlFor="c-pos">{tx('Position')}</Label>
              <Input id="c-pos" value={form.position ?? ''} onChange={(e) => setForm({ ...form, position: e.target.value })} placeholder={tx('e.g. Purchasing manager')} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="c-email">{tx('Email')}</Label>
              <Input id="c-email" type="email" value={form.email ?? ''} onChange={(e) => setForm({ ...form, email: e.target.value })} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="c-phone">{tx('Phone')}</Label>
              <Input id="c-phone" value={form.phone ?? ''} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
            </div>
            <label className="flex items-center gap-2 text-sm sm:col-span-2 pt-1">
              <input type="checkbox" className="h-4 w-4 rounded border-input accent-primary" checked={!!form.isPrimary} onChange={(e) => setForm({ ...form, isPrimary: e.target.checked })} />
              {tx('Primary contact')}
            </label>
            {error && <p className="text-sm text-destructive sm:col-span-2">{error}</p>}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditing(null)}>{tx('Cancel')}</Button>
            <Button onClick={save} disabled={busy}>{tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AlertDialog open={!!deleting} onOpenChange={(o) => !o && setDeleting(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{tx('Delete contact')}</AlertDialogTitle>
            <AlertDialogDescription>
              {tx('Remove {{name}} from this partner? Deactivate instead to keep the history.', { name: deleting ? contactName(deleting) : '' })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{tx('Cancel')}</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={async () => { if (deleting) await onDelete(deleting.id); setDeleting(null); }}
            >
              {tx('Delete')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

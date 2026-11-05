import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ChevronRight, Users, Building2, Save, FileText, MapPin, SlidersHorizontal, Landmark, Plus, Trash2,
} from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import {
  EMPTY_PROFILE, useCreatePartner, useUpdatePartnerProfile, type ContactInput, type PartnerContact,
  type PartnerProfile,
} from '@/features/partners/api';
import {
  AccountingFields, ContactsTable, EMPTY_GENERAL, GeneralFields, KindToggle, SalesConfigFields, cleanProfile,
  generalPayload, selectCls, type GeneralValues,
} from './profile-forms';

interface AddressDraft {
  key: string;
  type: 'billing' | 'shipping';
  line1: string;
  line2: string;
  city: string;
  state: string;
  postalCode: string;
  country: string;
  isPrimary: boolean;
}

const newKey = () => Math.random().toString(36).slice(2);

function AddressesEditor({ rows, onChange }: { rows: AddressDraft[]; onChange: (rows: AddressDraft[]) => void }) {
  const set = (i: number, patch: Partial<AddressDraft>) =>
    onChange(rows.map((r, j) => (j === i ? { ...r, ...patch } : patch.isPrimary ? { ...r, isPrimary: false } : r)));
  return (
    <div className="rounded-xl border bg-card">
      <div className="flex items-center justify-between px-4 py-3 border-b">
        <div className="text-sm text-muted-foreground">{rows.length === 0 ? tx('No addresses yet') : `${rows.length} ${tx('address(es)')}`}</div>
        <Button
          size="sm"
          onClick={() => onChange([...rows, {
            key: newKey(), type: rows.length === 0 ? 'billing' : 'shipping', line1: '', line2: '', city: '', state: '',
            postalCode: '', country: '', isPrimary: rows.length === 0,
          }])}
        >
          <Plus className="mr-1.5 h-4 w-4" /> {tx('Add address')}
        </Button>
      </div>
      {rows.length > 0 && (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Type')}</TableHead>
                <TableHead>{tx('Address')}</TableHead>
                <TableHead>{tx('Line 2')}</TableHead>
                <TableHead>{tx('City')}</TableHead>
                <TableHead>{tx('State')}</TableHead>
                <TableHead>{tx('Postal Code')}</TableHead>
                <TableHead>{tx('Country')}</TableHead>
                <TableHead>{tx('Primary')}</TableHead>
                <TableHead className="w-10" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r, i) => (
                <TableRow key={r.key}>
                  <TableCell className="py-1.5">
                    <select className={`${selectCls} h-8 min-w-[110px]`} value={r.type} onChange={(e) => set(i, { type: e.target.value as AddressDraft['type'] })}>
                      <option value="billing">{tx('Billing')}</option>
                      <option value="shipping">{tx('Shipping')}</option>
                    </select>
                  </TableCell>
                  {(['line1', 'line2', 'city', 'state', 'postalCode', 'country'] as const).map((k) => (
                    <TableCell key={k} className="py-1.5">
                      <Input className="h-8 min-w-[110px]" aria-label={k} value={r[k]} onChange={(e) => set(i, { [k]: e.target.value })} />
                    </TableCell>
                  ))}
                  <TableCell className="py-1.5 text-center">
                    <input type="radio" name="addr-primary" className="h-4 w-4 accent-primary" checked={r.isPrimary} onChange={() => set(i, { isPrimary: true })} aria-label={tx('Primary')} />
                  </TableCell>
                  <TableCell className="py-1.5">
                    <Button size="icon" variant="ghost" className="h-8 w-8 text-muted-foreground hover:text-destructive" aria-label={tx('Remove')} onClick={() => onChange(rows.filter((_, j) => j !== i))}>
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}

/** Full-page form for a new customer / supplier, laid out like the detail page. */
export function PartnerCreatePage({ partnerType }: { partnerType: 'customer' | 'supplier' }) {
  const navigate = useNavigate();
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.partners.create));
  const create = useCreatePartner();
  const updateProfile = useUpdatePartnerProfile();
  const isCustomer = partnerType === 'customer';
  const plural = isCustomer ? 'customers' : 'suppliers';
  const [general, setGeneral] = useState<GeneralValues>({ ...EMPTY_GENERAL, isCompany: !isCustomer });
  const [profile, setProfile] = useState<PartnerProfile>(EMPTY_PROFILE);
  const [contacts, setContacts] = useState<PartnerContact[]>([]);
  const [addresses, setAddresses] = useState<AddressDraft[]>([]);
  const [tab, setTab] = useState('overview');
  const [nameError, setNameError] = useState(false);
  const saving = create.isPending || updateProfile.isPending;

  if (!canCreate) {
    return (
      <div className="p-6">
        <div className="rounded-lg border border-destructive/20 bg-destructive/5 p-6 text-center">
          <h1 className="text-xl font-semibold text-destructive">{tx('Access Denied')}</h1>
          <p className="mt-2 text-sm text-muted-foreground">{tx('You do not have permission to create partners.')}</p>
        </div>
      </div>
    );
  }

  // Local contacts behave like the API: one primary at a time.
  const addContact = (c: ContactInput) => {
    const row: PartnerContact = {
      id: newKey(), firstName: c.firstName ?? '', lastName: c.lastName ?? null, position: c.position ?? null,
      email: c.email ?? null, phone: c.phone ?? null, isPrimary: !!c.isPrimary, isActive: true,
    };
    setContacts((cs) => [...(row.isPrimary ? cs.map((x) => ({ ...x, isPrimary: false })) : cs), row]);
  };
  const updateContact = (id: string, c: ContactInput) => {
    if (c.isActive === false && contacts.find((x) => x.id === id)?.isPrimary) {
      throw new Error(tx('A primary contact cannot be deactivated'));
    }
    setContacts((cs) => cs.map((x) => {
      if (x.id === id) return { ...x, ...c } as PartnerContact;
      return c.isPrimary ? { ...x, isPrimary: false } : x;
    }));
  };

  const onSave = async () => {
    if (!general.name.trim()) {
      setNameError(true);
      notify.error(tx('Name is required'));
      return;
    }
    if (general.email.trim() && !/^\S+@\S+\.\S+$/.test(general.email.trim())) {
      setTab('overview');
      notify.error(tx('Invalid email'));
      return;
    }
    const clean = cleanProfile(profile);
    const body: Record<string, unknown> = {
      ...generalPayload(general),
      isCustomer,
      isSupplier: !isCustomer,
      paymentTermId: clean.paymentTermId,
    };
    if (contacts.length) {
      body.contacts = contacts.map(({ firstName, lastName, position, email, phone, isPrimary }) =>
        ({ firstName, lastName, position, email, phone, isPrimary }));
    }
    const addr = addresses.filter((a) => a.line1.trim());
    if (addr.length) {
      body.addresses = addr.map((a, i) => ({
        type: a.type, line1: a.line1.trim(), line2: a.line2.trim() || null, city: a.city.trim() || null,
        state: a.state.trim() || null, postalCode: a.postalCode.trim() || null, country: a.country.trim() || null,
        isPrimary: addr.some((x) => x.isPrimary) ? a.isPrimary : i === 0,
      }));
    }
    try {
      const created = await create.mutateAsync(body as any);
      const extra: Partial<PartnerProfile> = {};
      (Object.keys(EMPTY_PROFILE) as (keyof PartnerProfile)[]).forEach((k) => {
        if (k !== 'paymentTermId' && JSON.stringify(clean[k]) !== JSON.stringify(EMPTY_PROFILE[k])) {
          (extra as Record<string, unknown>)[k] = clean[k];
        }
      });
      if (Object.keys(extra).length) {
        try {
          await updateProfile.mutateAsync({ id: created.id, data: extra });
        } catch (e: any) {
          notify.warning(tx('Partner created, but some settings were not saved'), e?.response?.data?.message ?? e?.message);
        }
      }
      notify.success(isCustomer ? tx('Customer created') : tx('Supplier created'));
      navigate(`/${plural}/${created.id}`, { replace: true });
    } catch (e: any) {
      notify.error(tx('Could not create partner'), e?.response?.data?.message ?? e?.message);
    }
  };

  const Icon = general.isCompany ? Building2 : Users;
  const tabs = [
    { value: 'overview', label: tx('Overview'), icon: FileText },
    { value: 'contacts', label: tx('Contacts'), icon: Users, count: contacts.length },
    { value: 'addresses', label: tx('Addresses'), icon: MapPin, count: addresses.length },
    ...(isCustomer ? [{ value: 'sales-config', label: tx('Sales Config'), icon: SlidersHorizontal }] : []),
    { value: 'accounting', label: tx('Accounting'), icon: Landmark },
  ];

  return (
    <div className="space-y-5 px-2 md:px-4 py-3 mx-auto">
      <nav className="flex items-center gap-1.5 text-sm text-muted-foreground">
        <button onClick={() => navigate(`/${plural}`)} className="hover:text-foreground transition-colors">
          {isCustomer ? tx('Customers') : tx('Suppliers')}
        </button>
        <ChevronRight className="h-3.5 w-3.5" />
        <span className="text-foreground font-medium">{tx('New')}</span>
      </nav>

      <form
        onSubmit={(e) => { e.preventDefault(); void onSave(); }}
        className="space-y-5"
      >
        <div className="rounded-xl border bg-card px-5 py-4 shadow-sm">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div className="flex min-w-0 flex-1 items-start gap-4">
              <div className="flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                <Icon className="h-6 w-6" />
              </div>
              <div className="min-w-0 flex-1 space-y-2">
                <Input
                  autoFocus
                  aria-label={tx('Name')}
                  aria-invalid={nameError}
                  className={`h-10 max-w-xl text-lg font-semibold ${nameError ? 'border-destructive' : ''}`}
                  placeholder={general.isCompany ? tx('e.g. Acme Trading Ltd') : tx('e.g. Jane Doe')}
                  value={general.name}
                  onChange={(e) => { setGeneral({ ...general, name: e.target.value }); setNameError(false); }}
                />
                <div className="flex flex-wrap items-center gap-2">
                  <KindToggle isCompany={general.isCompany} onChange={(v) => setGeneral({ ...general, isCompany: v })} />
                  <Badge variant="outline" className="text-xs">{isCustomer ? tx('Customer') : tx('Supplier')}</Badge>
                </div>
              </div>
            </div>
            <div className="flex flex-shrink-0 gap-2">
              <Button type="button" size="sm" variant="outline" onClick={() => navigate(`/${plural}`)}>{tx('Discard')}</Button>
              <Button type="submit" size="sm" disabled={saving}>
                <Save className="mr-1.5 h-4 w-4" /> {saving ? tx('Saving...') : tx('Save')}
              </Button>
            </div>
          </div>
        </div>
      </form>

      <Tabs value={tab} onValueChange={setTab}>
        <div className="overflow-x-auto">
          <TabsList className="bg-muted/50 p-1">
            {tabs.map((t) => (
              <TabsTrigger key={t.value} value={t.value} className="flex items-center gap-1.5 data-[state=active]:bg-background">
                <t.icon className="h-4 w-4" />
                <span>{t.label}</span>
                {'count' in t && t.count ? <span className="rounded-full bg-muted px-1.5 text-[10px] font-semibold">{t.count}</span> : null}
              </TabsTrigger>
            ))}
          </TabsList>
        </div>

        <TabsContent value="overview" className="mt-5">
          <div className="rounded-xl border bg-card p-5">
            <GeneralFields value={general} onChange={(p) => setGeneral({ ...general, ...p })} isCustomer={isCustomer} />
          </div>
        </TabsContent>

        <TabsContent value="contacts" className="mt-5">
          <ContactsTable
            contacts={contacts}
            canEdit
            onAdd={addContact}
            onUpdate={updateContact}
            onDelete={(id) => setContacts((cs) => cs.filter((x) => x.id !== id))}
          />
        </TabsContent>

        <TabsContent value="addresses" className="mt-5">
          <AddressesEditor rows={addresses} onChange={setAddresses} />
        </TabsContent>

        {isCustomer && (
          <TabsContent value="sales-config" className="mt-5">
            <div className="rounded-xl border bg-card p-5">
              <SalesConfigFields value={profile} onChange={(p) => setProfile({ ...profile, ...p })} />
            </div>
          </TabsContent>
        )}

        <TabsContent value="accounting" className="mt-5">
          <div className="rounded-xl border bg-card p-5">
            <AccountingFields value={profile} onChange={(p) => setProfile({ ...profile, ...p })} />
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
}

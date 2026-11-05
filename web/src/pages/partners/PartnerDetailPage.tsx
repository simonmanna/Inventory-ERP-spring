import { useEffect, useState } from 'react';
import {
  ChevronRight, ArrowLeft, Users, Building2, Mail, Phone, Pencil, Save, SlidersHorizontal, Landmark,
  MapPin, FileText, DollarSign, ShoppingCart, BookOpen, Activity, Receipt, Handshake,
} from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Input } from '@/components/ui/input';
import { notify } from '@/lib/notify';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';
import { money, date, dateTime } from '@/lib/format';
import { api } from '@/lib/api';
import type { PaginatedResult } from '@erp/shared';
import { useNavigate, useParams } from 'react-router-dom';
import { CustomFieldsPanel } from '@/features/customization/CustomFieldInputs';
import { useAuthStore } from '@/stores/auth.store';
import {
  usePartner, useCustomerStatement, useUpdatePartner, useAddContact, useUpdateContact, useDeleteContact,
  usePartnerProfile, useUpdatePartnerProfile, EMPTY_PROFILE, type Partner, type PartnerContact, type PartnerProfile,
} from '@/features/partners/api';
import {
  AccountingFields, ContactsTable, EMPTY_GENERAL, FieldRow, FormSection, GeneralFields, KindToggle, ReadValue,
  SalesConfigFields, cleanProfile, generalPayload, type GeneralValues,
} from './profile-forms';
import { FollowupPanel } from './FollowupTab';
import { useOpenCreditInvoices } from '@/pages/pos/api';
import { RecordCreditPaymentDialog, type CreditPaymentTarget } from '@/pages/pos/RecordCreditPaymentDialog';
import { useSupplierLedger } from '@/features/invoicing/api';
import { CrmPartnerTab } from '@/features/crm/partner-tab';
import { tx } from '@/lib/i18n/tx';

function PartnerStatusBadge({ status }: { status: string }) {
  if (status === 'active') return <Badge variant="outline" className="border-emerald-200 bg-emerald-50 text-emerald-700 text-xs">{tx('Active')}</Badge>;
  return <Badge variant="secondary" className="text-xs capitalize">{status === 'archived' ? tx('Archived') : tx('Inactive')}</Badge>;
}

function toGeneral(p: Partner): GeneralValues {
  return {
    name: p.name ?? '',
    code: p.code ?? '',
    isCompany: !!p.isCompany,
    email: p.email ?? '',
    phone: p.phone ?? '',
    website: p.website ?? '',
    taxNumber: p.taxNumber ?? '',
    categoryId: p.categoryId ?? '',
    membershipLevel: p.membershipLevel ?? '',
    gender: p.gender ?? '',
    status: p.status ?? 'active',
    notes: p.notes ?? '',
  };
}

function TabOverview({ partner, editing, value, onChange }: {
  partner: Partner; editing: boolean; value: GeneralValues; onChange: (p: Partial<GeneralValues>) => void;
}) {
  if (editing) {
    return (
      <div className="rounded-xl border bg-card p-5">
        <GeneralFields value={value} onChange={onChange} isCustomer={partner.isCustomer} />
      </div>
    );
  }
  return (
    <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
      <div className="lg:col-span-2 rounded-xl border bg-card p-5">
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-x-10 gap-y-4">
          <FormSection title={tx('Contact details')}>
            <FieldRow label={tx('Email')}><ReadValue>{partner.email ? <a className="text-primary hover:underline" href={`mailto:${partner.email}`}>{partner.email}</a> : null}</ReadValue></FieldRow>
            <FieldRow label={tx('Phone')}><ReadValue>{partner.phone}</ReadValue></FieldRow>
            <FieldRow label={tx('Website')}><ReadValue>{partner.website}</ReadValue></FieldRow>
            <FieldRow label={tx('Tax Number')}><ReadValue>{partner.taxNumber ? <span className="font-mono text-xs">{partner.taxNumber}</span> : null}</ReadValue></FieldRow>
          </FormSection>
          <FormSection title={tx('Classification')}>
            <FieldRow label={tx('Code')}><ReadValue><span className="font-mono text-xs">{partner.code}</span></ReadValue></FieldRow>
            <FieldRow label={tx('Category')}><ReadValue>{partner.category?.name}</ReadValue></FieldRow>
            {partner.isCustomer && <FieldRow label={tx('Membership')}><ReadValue>{partner.membershipLevel ? <span className="capitalize">{partner.membershipLevel}</span> : null}</ReadValue></FieldRow>}
            {!partner.isCompany && <FieldRow label={tx('Gender')}><ReadValue>{partner.gender ? <span className="capitalize">{partner.gender.replace(/_/g, ' ')}</span> : null}</ReadValue></FieldRow>}
            <FieldRow label={tx('Status')}><ReadValue><PartnerStatusBadge status={partner.status} /></ReadValue></FieldRow>
          </FormSection>
        </div>
        {partner.notes ? (
          <FormSection title={tx('Internal notes')} className="mt-5">
            <p className="whitespace-pre-wrap text-sm">{partner.notes}</p>
          </FormSection>
        ) : null}
      </div>
      <div className="rounded-xl border bg-card p-5">
        <FormSection title={tx('Record')}>
          <FieldRow label={tx('Created')}><ReadValue>{dateTime(partner.createdAt)}</ReadValue></FieldRow>
          <FieldRow label={tx('Updated')}><ReadValue>{dateTime(partner.updatedAt)}</ReadValue></FieldRow>
          <FieldRow label={tx('Roles')}>
            <div className="flex flex-wrap gap-1.5 py-1.5">
              {partner.isCustomer && <Badge variant="outline" className="text-xs">{tx('Customer')}</Badge>}
              {partner.isSupplier && <Badge variant="outline" className="text-xs">{tx('Supplier')}</Badge>}
              {partner.isEmployee && <Badge variant="outline" className="text-xs">{tx('Employee')}</Badge>}
            </div>
          </FieldRow>
        </FormSection>
      </div>
    </div>
  );
}

function TabContacts({ partner, canEdit }: { partner: Partner; canEdit: boolean }) {
  const add = useAddContact(partner.id);
  const update = useUpdateContact(partner.id);
  const del = useDeleteContact(partner.id);
  const fail = (e: any) => notify.error(tx('Could not update contact'), e?.response?.data?.message ?? e?.message);
  return (
    <ContactsTable
      contacts={(partner.contacts ?? []) as PartnerContact[]}
      canEdit={canEdit}
      busy={add.isPending || update.isPending || del.isPending}
      onAdd={async (c) => { await add.mutateAsync(c); notify.success(tx('Contact added')); }}
      onUpdate={async (id, c) => {
        try {
          await update.mutateAsync({ id, data: c });
          notify.success(tx('Contact updated'));
        } catch (e) {
          // Dialog edits surface their own error; quick actions toast it.
          if (c.firstName === undefined) fail(e);
          else throw e;
        }
      }}
      onDelete={async (id) => {
        try { await del.mutateAsync(id); notify.success(tx('Contact deleted')); } catch (e) { fail(e); }
      }}
    />
  );
}

/** Editable profile tab (sales config or accounting) with its own Save / Discard bar. */
function TabProfile({ partnerId, canEdit, kind }: { partnerId: string; canEdit: boolean; kind: 'sales' | 'accounting' }) {
  const { data, isLoading } = usePartnerProfile(partnerId);
  const update = useUpdatePartnerProfile();
  const [value, setValue] = useState<PartnerProfile | null>(null);
  useEffect(() => { if (data) setValue({ ...EMPTY_PROFILE, ...data }); }, [data]);
  if (isLoading || !value || !data) return <Skeleton className="h-56 w-full rounded-xl" />;
  const original = { ...EMPTY_PROFILE, ...data };
  const keys: (keyof PartnerProfile)[] = kind === 'sales'
    ? ['salespersonId', 'paymentTermId', 'paymentModeId', 'incoterm', 'incotermLocation']
    : ['receivableAccountId', 'payableAccountId', 'autoPostBills', 'bankAccounts'];
  const dirty = keys.some((k) => JSON.stringify(value[k]) !== JSON.stringify(original[k]));
  const onSave = async () => {
    const clean = cleanProfile(value);
    const body: Partial<PartnerProfile> = {};
    keys.forEach((k) => {
      if (JSON.stringify(clean[k]) !== JSON.stringify(original[k])) (body as Record<string, unknown>)[k] = clean[k];
    });
    try {
      await update.mutateAsync({ id: partnerId, data: body });
      notify.success(kind === 'sales' ? tx('Sales configuration saved') : tx('Accounting settings saved'));
    } catch (e: any) {
      notify.error(tx('Could not save'), e?.response?.data?.message ?? e?.message);
    }
  };
  const onChange = (patch: Partial<PartnerProfile>) => setValue({ ...value, ...patch });
  return (
    <div className="rounded-xl border bg-card p-5">
      {canEdit && (
        <div className={`mb-3 flex justify-end gap-2 transition-opacity ${dirty ? 'opacity-100' : 'pointer-events-none opacity-0'}`}>
          <Button size="sm" variant="ghost" onClick={() => setValue(original)}>{tx('Discard')}</Button>
          <Button size="sm" onClick={onSave} disabled={update.isPending}>{tx('Save')}</Button>
        </div>
      )}
      {kind === 'sales'
        ? <SalesConfigFields value={value} onChange={onChange} disabled={!canEdit} />
        : <AccountingFields value={value} onChange={onChange} disabled={!canEdit} />}
    </div>
  );
}

function TabAddresses({ partner }: { partner: Partner }) {
  const addresses = partner.addresses ?? [];
  return (
    <Card>
      <CardContent className="p-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Type')}</TableHead>
              <TableHead>{tx('Address')}</TableHead>
              <TableHead>{tx('City')}</TableHead>
              <TableHead>{tx('State')}</TableHead>
              <TableHead>{tx('Postal Code')}</TableHead>
              <TableHead>{tx('Country')}</TableHead>
              <TableHead>{tx('Primary')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {addresses.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7} className="py-8 text-center text-muted-foreground">{tx('No addresses registered.')}</TableCell>
              </TableRow>
            ) : addresses.map((a) => (
              <TableRow key={a.id}>
                <TableCell className="capitalize">{a.type}</TableCell>
                <TableCell>{[a.line1, a.line2].filter(Boolean).join(', ')}</TableCell>
                <TableCell>{a.city ?? '-'}</TableCell>
                <TableCell>{a.state ?? '-'}</TableCell>
                <TableCell>{a.postalCode ?? '-'}</TableCell>
                <TableCell>{a.country ?? '-'}</TableCell>
                <TableCell>{a.isPrimary ? <Badge className="bg-emerald-100 text-emerald-700 text-xs">{tx('Primary')}</Badge> : '-'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

interface InvoiceRow {
  id: string;
  documentNumber: string;
  issueDate: string;
  totalAmount: string;
  status: string;
  paymentStatus: string;
}

function TabInvoices({ partnerId }: { partnerId: string }) {
  const [invoices, setInvoices] = useState<InvoiceRow[]>([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    api.get<{ data: InvoiceRow[] }>('/invoices', { params: { partnerId, pageSize: 10 } })
      .then((r) => setInvoices(r.data.data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [partnerId]);

  if (loading) return <div className="p-8 text-center text-muted-foreground"><Skeleton className="h-48 w-full" /></div>;

  return (
    <Card>
      <CardContent className="p-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Document #')}</TableHead>
              <TableHead>{tx('Date')}</TableHead>
              <TableHead className="text-right">{tx('Amount')}</TableHead>
              <TableHead>{tx('Status')}</TableHead>
              <TableHead>{tx('Payment')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {invoices.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">{tx('No invoices found.')}</TableCell>
              </TableRow>
            ) : invoices.map((inv) => (
              <TableRow key={inv.id} className="cursor-pointer hover:bg-muted/50" onClick={() => navigate(`/invoices/${inv.id}`)}>
                <TableCell className="font-mono text-xs font-medium">{inv.documentNumber}</TableCell>
                <TableCell className="text-sm">{date(inv.issueDate)}</TableCell>
                <TableCell className="text-right font-medium">{money(inv.totalAmount)}</TableCell>
                <TableCell><Badge variant={inv.status === 'posted' ? 'default' : 'secondary'} className="text-xs capitalize">{inv.status}</Badge></TableCell>
                <TableCell><Badge variant="outline" className="text-xs capitalize">{inv.paymentStatus}</Badge></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

interface PaymentRow {
  id: string;
  paymentNumber: string;
  paymentDate: string;
  amount: string;
  direction: string;
  status: string;
  paymentMethod: string;
}

function TabPayments({ partnerId, direction }: { partnerId: string; direction: 'inbound' | 'outbound' }) {
  const [payments, setPayments] = useState<PaymentRow[]>([]);
  const [loading, setLoading] = useState(true);
  const endpoint = direction === 'inbound' ? '/payments' : '/supplier-payments';

  useEffect(() => {
    api.get<{ data: PaymentRow[] }>(endpoint, { params: { partnerId, pageSize: 10 } })
      .then((r) => setPayments(r.data.data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [partnerId, endpoint]);

  if (loading) return <div className="p-8 text-center text-muted-foreground"><Skeleton className="h-48 w-full" /></div>;

  return (
    <Card>
      <CardContent className="p-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Payment #')}</TableHead>
              <TableHead>{tx('Date')}</TableHead>
              <TableHead className="text-right">{tx('Amount')}</TableHead>
              <TableHead>{tx('Method')}</TableHead>
              <TableHead>{tx('Status')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {payments.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">{tx('No payments found.')}</TableCell>
              </TableRow>
            ) : payments.map((p) => (
              <TableRow key={p.id}>
                <TableCell className="font-mono text-xs">{p.paymentNumber}</TableCell>
                <TableCell className="text-sm">{date(p.paymentDate)}</TableCell>
                <TableCell className="text-right font-medium">{money(p.amount)}</TableCell>
                <TableCell className="text-sm capitalize">{p.paymentMethod}</TableCell>
                <TableCell><Badge variant={p.status === 'posted' ? 'default' : 'secondary'} className="text-xs capitalize">{p.status}</Badge></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

interface PORow {
  id: string;
  orderNumber: string;
  orderDate: string;
  totalAmount: number;
  status: string;
}

function TabPurchaseOrders({ partnerId }: { partnerId: string }) {
  const [orders, setOrders] = useState<PORow[]>([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    api.get<PaginatedResult<PORow>>('/procurement/purchase-orders', { params: { partnerId, pageSize: 10 } })
      .then((r) => setOrders(r.data.data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [partnerId]);

  if (loading) return <div className="p-8 text-center text-muted-foreground"><Skeleton className="h-48 w-full" /></div>;

  const statusColors: Record<string, string> = {
    draft: 'bg-gray-100 text-gray-700',
    active: 'bg-blue-100 text-blue-700',
    partially_received: 'bg-amber-100 text-amber-700',
    received: 'bg-emerald-100 text-emerald-700',
    cancelled: 'bg-red-100 text-red-700',
  };

  return (
    <Card>
      <CardContent className="p-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Order #')}</TableHead>
              <TableHead>{tx('Date')}</TableHead>
              <TableHead className="text-right">{tx('Amount')}</TableHead>
              <TableHead>{tx('Status')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {orders.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4} className="py-8 text-center text-muted-foreground">{tx('No purchases found.')}</TableCell>
              </TableRow>
            ) : orders.map((po) => (
              <TableRow key={po.id} className="cursor-pointer hover:bg-muted/50" onClick={() => navigate(`/procurement/purchase-orders/${po.id}`)}>
                <TableCell className="font-mono text-xs font-medium">{po.orderNumber}</TableCell>
                <TableCell className="text-sm">{date(po.orderDate)}</TableCell>
                <TableCell className="text-right font-medium">{money(po.totalAmount)}</TableCell>
                <TableCell>
                  <span className={`inline-flex px-2.5 py-0.5 rounded text-xs font-semibold ${statusColors[po.status] ?? 'bg-gray-100 text-gray-700'}`}>
                    {po.status.replace(/_/g, ' ')}
                  </span>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

function TabLedger({ partnerId }: { partnerId: string }) {
  const { data, isLoading } = useSupplierLedger(partnerId);

  if (isLoading) return <div className="p-8"><Skeleton className="h-48 w-full" /></div>;
  if (!data) return <div className="p-8 text-center text-muted-foreground">{tx('No ledger data.')}</div>;

  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <Card>
          <CardContent className="pt-6">
            <div className="text-sm text-muted-foreground">{tx('Opening Balance')}</div>
            <div className="text-2xl font-bold">{money(data.openingBalance)}</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <div className="text-sm text-muted-foreground">{tx('Closing Balance')}</div>
            <div className="text-2xl font-bold">{money(data.closingBalance)}</div>
          </CardContent>
        </Card>
      </div>
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Date')}</TableHead>
                <TableHead>{tx('Type')}</TableHead>
                <TableHead>{tx('Reference')}</TableHead>
                <TableHead>{tx('Description')}</TableHead>
                <TableHead className="text-right">{tx('Debit')}</TableHead>
                <TableHead className="text-right">{tx('Credit')}</TableHead>
                <TableHead className="text-right">{tx('Balance')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.transactions.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} className="py-8 text-center text-muted-foreground">{tx('No transactions found.')}</TableCell>
                </TableRow>
              ) : data.transactions.map((t) => (
                <TableRow key={t.id}>
                  <TableCell className="text-sm">{date(t.date)}</TableCell>
                  <TableCell>
                    <Badge variant={t.type === 'payment' ? 'default' : t.type === 'payment_void' ? 'destructive' : 'secondary'} className="text-xs capitalize">
                      {t.type.replace(/_/g, ' ')}
                    </Badge>
                  </TableCell>
                  <TableCell className="font-mono text-xs">{t.reference}</TableCell>
                  <TableCell className="text-sm">{t.description}</TableCell>
                  <TableCell className="text-right">{t.debit ? money(t.debit) : '-'}</TableCell>
                  <TableCell className="text-right">{t.credit ? money(t.credit) : '-'}</TableCell>
                  <TableCell className="text-right font-medium">{money(t.balance)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}

function TabStatement({ partnerId }: { partnerId: string }) {
  const { data, isLoading } = useCustomerStatement(partnerId);
  const { data: open } = useOpenCreditInvoices({ partnerId, pageSize: 100 });
  const [target, setTarget] = useState<CreditPaymentTarget | null>(null);

  if (isLoading) return <div className="p-8"><Skeleton className="h-48 w-full" /></div>;
  if (!data) return <div className="p-8 text-center text-muted-foreground">{tx('No statement data.')}</div>;

  const typeBadge: Record<string, string> = {
    credit_issue: 'bg-blue-50 text-blue-700',
    payment: 'bg-emerald-50 text-emerald-700',
    write_off: 'bg-red-50 text-red-700',
  };

  const openRows = open?.rows ?? [];

  return (
    <div className="space-y-4">
      {data.creditHold ? (
        <div className="rounded-lg border-2 border-rose-200 bg-rose-50 px-4 py-2.5 text-sm font-semibold text-rose-700">
          {tx('On credit hold — new credit sales are blocked for this customer.')}
        </div>
      ) : null}
      <div className="grid gap-4 sm:grid-cols-3">
        <Card>
          <CardContent className="pt-6">
            <div className="text-sm text-muted-foreground">{tx('Credit Limit')}</div>
            <div className="text-2xl font-bold">{data.creditLimit > 0 ? money(data.creditLimit) : tx('No limit')}</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <div className="text-sm text-muted-foreground">{tx('Outstanding')}</div>
            <div className="text-2xl font-bold">{money(data.outstanding)}</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <div className="text-sm text-muted-foreground">{tx('Available')}</div>
            <div className="text-2xl font-bold">{data.available == null ? '—' : money(data.available)}</div>
          </CardContent>
        </Card>
      </div>

      {openRows.length > 0 ? (
        <Card>
          <CardContent className="pt-6">
            <div className="mb-3 text-sm font-semibold">{tx('Open invoices')}</div>
            <div className="space-y-2">
              {openRows.map((r) => (
                <div key={r.invoiceId} className="flex items-center justify-between rounded border px-3 py-2 text-sm">
                  <div className="min-w-0">
                    <span className="font-mono text-xs">{r.invoiceNumber}</span>
                    <span className="ml-2 text-xs text-muted-foreground">{date(r.issueDate)} · {r.daysOutstanding}d</span>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="font-semibold">{money(r.amountResidual)}</span>
                    <Button
                      size="sm"
                      className="bg-emerald-600 hover:bg-emerald-700"
                      onClick={() => setTarget({
                        invoiceId: r.invoiceId,
                        invoiceNumber: r.invoiceNumber,
                        partnerName: r.partnerName,
                        amountResidual: r.amountResidual,
                      })}
                    >
                      {tx('Record payment')}
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      ) : null}

      <RecordCreditPaymentDialog target={target} onClose={() => setTarget(null)} />
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Date')}</TableHead>
                <TableHead>{tx('Type')}</TableHead>
                <TableHead>{tx('Reference')}</TableHead>
                <TableHead className="text-right">{tx('Charge')}</TableHead>
                <TableHead className="text-right">{tx('Payment')}</TableHead>
                <TableHead className="text-right">{tx('Balance')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.entries.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="py-8 text-center text-muted-foreground">{tx('No credit activity.')}</TableCell>
                </TableRow>
              ) : data.entries.map((e, i) => (
                <TableRow key={`${e.invoiceId}-${e.type}-${i}`}>
                  <TableCell className="text-sm">{date(e.date)}</TableCell>
                  <TableCell>
                    <Badge variant="secondary" className={`text-xs capitalize ${typeBadge[e.type] ?? ''}`}>
                      {e.type.replace(/_/g, ' ')}
                    </Badge>
                  </TableCell>
                  <TableCell className="font-mono text-xs">{e.reference}</TableCell>
                  <TableCell className="text-right">{e.amount > 0 ? money(e.amount) : '-'}</TableCell>
                  <TableCell className="text-right">{e.amount < 0 ? money(-e.amount) : '-'}</TableCell>
                  <TableCell className="text-right font-medium">{money(e.runningBalance)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}

interface ExpenseRow {
  id: string;
  expenseCode: string;
  title: string;
  expenseDate: string;
  amount: string;
  status: string;
}

interface ExpenseApiResponse {
  data: ExpenseRow[];
  total: number;
}

function TabExpensesPurchases({ partnerId }: { partnerId: string }) {
  const [expenses, setExpenses] = useState<ExpenseRow[]>([]);
  const [expensesLoading, setExpensesLoading] = useState(true);
  const [pos, setPos] = useState<PORow[]>([]);
  const [posLoading, setPosLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    api.get<ExpenseApiResponse>('/expenses', { params: { supplierId: partnerId, limit: 5 } })
      .then((r) => setExpenses(r.data.data ?? []))
      .catch(() => {})
      .finally(() => setExpensesLoading(false));
    api.get<PaginatedResult<PORow>>('/procurement/purchase-orders', { params: { partnerId, pageSize: 5 } })
      .then((r) => setPos(r.data.data ?? []))
      .catch(() => {})
      .finally(() => setPosLoading(false));
  }, [partnerId]);

  const expStatusColors: Record<string, string> = {
    DRAFT: 'bg-gray-100 text-gray-700',
    APPROVED: 'bg-blue-100 text-blue-700',
    POSTED: 'bg-emerald-100 text-emerald-700',
    REJECTED: 'bg-red-100 text-red-700',
    VOID: 'bg-red-100 text-red-700',
  };

  const poStatusColors: Record<string, string> = {
    draft: 'bg-gray-100 text-gray-700',
    active: 'bg-blue-100 text-blue-700',
    partially_received: 'bg-amber-100 text-amber-700',
    received: 'bg-emerald-100 text-emerald-700',
    cancelled: 'bg-red-100 text-red-700',
  };

  return (
    <div className="space-y-6">
      {/* Expenses Section */}
      <Card>
        <CardHeader className="pb-3 px-5 pt-4 bg-muted/30 border-b rounded-t-lg flex flex-row items-center justify-between">
          <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">{tx('Expenses')}</CardTitle>
          <Button variant="link" size="sm" className="h-auto p-0 text-xs" onClick={() => navigate('/expenses')}>
            {tx('View all expenses →')}
          </Button>
        </CardHeader>
        <CardContent className="p-0">
          {expensesLoading ? (
            <div className="p-4"><Skeleton className="h-32 w-full" /></div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Code')}</TableHead>
                  <TableHead>{tx('Title')}</TableHead>
                  <TableHead>{tx('Date')}</TableHead>
                  <TableHead className="text-right">{tx('Amount')}</TableHead>
                  <TableHead>{tx('Status')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {expenses.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">{tx('No expenses found.')}</TableCell>
                  </TableRow>
                ) : expenses.map((exp) => (
                  <TableRow key={exp.id} className="cursor-pointer hover:bg-muted/50" onClick={() => navigate(`/expenses/${exp.id}`)}>
                    <TableCell className="font-mono text-xs font-medium">{exp.expenseCode}</TableCell>
                    <TableCell className="text-sm">{exp.title}</TableCell>
                    <TableCell className="text-sm">{date(exp.expenseDate)}</TableCell>
                    <TableCell className="text-right font-medium">{money(exp.amount)}</TableCell>
                    <TableCell>
                      <span className={`inline-flex px-2.5 py-0.5 rounded text-xs font-semibold ${expStatusColors[exp.status] ?? 'bg-gray-100 text-gray-700'}`}>
                        {exp.status}
                      </span>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Purchase Orders Section */}
      <Card>
        <CardHeader className="pb-3 px-5 pt-4 bg-muted/30 border-b rounded-t-lg flex flex-row items-center justify-between">
          <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">{tx('Purchases')}</CardTitle>
          <Button variant="link" size="sm" className="h-auto p-0 text-xs" onClick={() => navigate('/procurement/purchase-orders')}>
            {tx('View all POs →')}
          </Button>
        </CardHeader>
        <CardContent className="p-0">
          {posLoading ? (
            <div className="p-4"><Skeleton className="h-32 w-full" /></div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Order #')}</TableHead>
                  <TableHead>{tx('Date')}</TableHead>
                  <TableHead className="text-right">{tx('Amount')}</TableHead>
                  <TableHead>{tx('Status')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {pos.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={4} className="py-8 text-center text-muted-foreground">{tx('No purchases found.')}</TableCell>
                  </TableRow>
                ) : pos.map((po) => (
                  <TableRow key={po.id} className="cursor-pointer hover:bg-muted/50" onClick={() => navigate(`/procurement/purchase-orders/${po.id}`)}>
                    <TableCell className="font-mono text-xs font-medium">{po.orderNumber}</TableCell>
                    <TableCell className="text-sm">{date(po.orderDate)}</TableCell>
                    <TableCell className="text-right font-medium">{money(po.totalAmount)}</TableCell>
                    <TableCell>
                      <span className={`inline-flex px-2.5 py-0.5 rounded text-xs font-semibold ${poStatusColors[po.status] ?? 'bg-gray-100 text-gray-700'}`}>
                        {po.status.replace(/_/g, ' ')}
                      </span>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function PartnerDetailPage({ partnerType }: { partnerType: 'customer' | 'supplier' }) {
  const { partnerId } = useParams<{ partnerId: string }>();
  const navigate = useNavigate();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canCrm = hasPermission('crm:deal:read');
  const canEdit = hasPermission(PERMISSIONS.partners.edit);
  const canFollowup = hasPermission('sales:collections:read');
  const canManageFollowup = hasPermission('sales:collections:manage');
  const { data: partner, isLoading } = usePartner(partnerId);
  const updatePartner = useUpdatePartner();
  const [editing, setEditing] = useState(false);
  const [general, setGeneral] = useState<GeneralValues>(EMPTY_GENERAL);
  const [tab, setTab] = useState('overview');

  if (isLoading) {
    return (
      <div className="space-y-6 px-2 md:px-4 py-3 mx-auto">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-28 w-full rounded-xl" />
        <Skeleton className="h-64 w-full rounded-xl" />
      </div>
    );
  }

  if (!partner) {
    return (
      <div className="space-y-6 px-2 md:px-4 py-3 mx-auto">
        <div className="rounded-lg border border-destructive/20 bg-destructive/5 p-6 text-center">
          <h1 className="text-2xl font-semibold text-destructive">{tx('Not Found')}</h1>
          <p className="mt-2 text-sm text-muted-foreground">{tx('Partner not found or you don\'t have permission to view it.')}</p>
          <Button variant="outline" className="mt-4" onClick={() => navigate(partnerType === 'customer' ? '/customers' : '/suppliers')}>
            <ArrowLeft className="mr-2 h-4 w-4" /> {tx('Back to')} {partnerType === 'customer' ? tx('Customers') : tx('Suppliers')}
          </Button>
        </div>
      </div>
    );
  }

  const isCustomer = partnerType === 'customer';
  const labelPlural = isCustomer ? 'Customers' : 'Suppliers';
  const Icon = partner.isCompany ? Building2 : isCustomer ? Users : Building2;
  const primary = partner.contacts?.find((c) => c.isPrimary);

  const startEdit = () => {
    setGeneral(toGeneral(partner));
    setEditing(true);
    setTab('overview');
  };
  const saveEdit = async () => {
    if (!general.name.trim()) {
      notify.error(tx('Name is required'));
      return;
    }
    if (general.email.trim() && !/^\S+@\S+\.\S+$/.test(general.email.trim())) {
      notify.error(tx('Invalid email'));
      return;
    }
    try {
      await updatePartner.mutateAsync({ id: partner.id, data: generalPayload(general) as any });
      notify.success(tx('Partner updated successfully'));
      setEditing(false);
    } catch (e: any) {
      notify.error(tx('Could not update partner'), e?.response?.data?.message ?? e?.message);
    }
  };

  const tabs = [
    { value: 'overview', label: tx('Overview'), icon: FileText },
    { value: 'contacts', label: tx('Contacts'), icon: Users },
    { value: 'addresses', label: tx('Addresses'), icon: MapPin },
  ];
  if (isCustomer) tabs.push({ value: 'sales-config', label: tx('Sales Config'), icon: SlidersHorizontal });
  tabs.push({ value: 'accounting', label: tx('Accounting'), icon: Landmark });
  tabs.push({ value: isCustomer ? 'invoices' : 'expenses-purchases', label: isCustomer ? tx('Invoices') : tx('Expenses/Purchases'), icon: isCustomer ? DollarSign : Receipt });
  tabs.push({ value: 'payments', label: isCustomer ? tx('Payments and Follow-ups') : tx('Payments'), icon: Activity });
  if (canCrm) {
    tabs.push({ value: 'crm', label: 'CRM', icon: Handshake });
  }
  if (isCustomer) {
    tabs.push({ value: 'statement', label: tx('Statement'), icon: BookOpen });
  } else {
    tabs.push({ value: 'purchase-orders', label: tx('Purchases'), icon: ShoppingCart });
    tabs.push({ value: 'ledger', label: tx('Ledger'), icon: BookOpen });
  }

  return (
    <div className="space-y-5 px-2 md:px-4 py-3 mx-auto">
      {/* Breadcrumb */}
      <nav className="flex items-center gap-1.5 text-sm text-muted-foreground">
        <button onClick={() => navigate(`/${labelPlural.toLowerCase()}`)} className="hover:text-foreground transition-colors">{tx(labelPlural)}</button>
        <ChevronRight className="h-3.5 w-3.5" />
        <span className="text-foreground font-medium truncate">{partner.name}</span>
      </nav>

      {/* Header */}
      <div className="rounded-xl border bg-card px-5 py-4 shadow-sm">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div className="flex min-w-0 items-start gap-4">
            <div className="flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
              <Icon className="h-6 w-6" />
            </div>
            <div className="min-w-0 flex-1">
              {editing ? (
                <div className="space-y-2">
                  <Input
                    aria-label={tx('Name')}
                    className="h-10 text-lg font-semibold"
                    value={general.name}
                    onChange={(e) => setGeneral({ ...general, name: e.target.value })}
                  />
                  <KindToggle isCompany={general.isCompany} onChange={(v) => setGeneral({ ...general, isCompany: v })} />
                </div>
              ) : (
                <>
                  <h1 className="truncate text-xl font-semibold text-foreground">{partner.name}</h1>
                  <div className="mt-1.5 flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                    <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs text-foreground/80">{partner.code}</code>
                    <PartnerStatusBadge status={partner.status} />
                    <span className="text-xs">{partner.isCompany ? tx('Company') : tx('Individual')}</span>
                    {partner.email && <span className="flex items-center gap-1"><Mail className="h-3.5 w-3.5" />{partner.email}</span>}
                    {partner.phone && <span className="flex items-center gap-1"><Phone className="h-3.5 w-3.5" />{partner.phone}</span>}
                    {primary && <span className="flex items-center gap-1"><Users className="h-3.5 w-3.5" />{[primary.firstName, primary.lastName].filter(Boolean).join(' ')}</span>}
                  </div>
                </>
              )}
            </div>
          </div>
          <div className="flex flex-shrink-0 gap-2">
            {editing ? (
              <>
                <Button size="sm" variant="outline" onClick={() => setEditing(false)}>{tx('Discard')}</Button>
                <Button size="sm" onClick={saveEdit} disabled={updatePartner.isPending}>
                  <Save className="mr-1.5 h-4 w-4" /> {tx('Save')}
                </Button>
              </>
            ) : (
              <>
                <Button size="sm" variant="outline" onClick={() => navigate(`/${labelPlural.toLowerCase()}`)}>
                  <ArrowLeft className="mr-1.5 h-4 w-4" /> {tx('Back')}
                </Button>
                {canEdit && (
                  <Button size="sm" variant="outline" onClick={startEdit}>
                    <Pencil className="mr-1.5 h-4 w-4" /> {tx('Edit')}
                  </Button>
                )}
              </>
            )}
          </div>
        </div>
      </div>

      {/* Tabs */}
      <Tabs value={tab} onValueChange={setTab}>
        <div className="overflow-x-auto">
          <TabsList className="bg-muted/50 p-1">
            {tabs.map((t) => (
              <TabsTrigger key={t.value} value={t.value} className="flex items-center gap-1.5 data-[state=active]:bg-background">
                <t.icon className="h-4 w-4" />
                <span>{t.label}</span>
              </TabsTrigger>
            ))}
          </TabsList>
        </div>

        <TabsContent value="overview" className="mt-5">
          <TabOverview partner={partner} editing={editing} value={general} onChange={(p) => setGeneral({ ...general, ...p })} />
          <div className="mt-4"><CustomFieldsPanel entity="partner" recordId={partnerId ?? ''} /></div>
        </TabsContent>

        <TabsContent value="contacts" className="mt-5">
          <TabContacts partner={partner} canEdit={canEdit} />
        </TabsContent>

        <TabsContent value="addresses" className="mt-5">
          <TabAddresses partner={partner} />
        </TabsContent>

        {isCustomer && (
          <TabsContent value="sales-config" className="mt-5">
            <TabProfile partnerId={partner.id} canEdit={canEdit} kind="sales" />
          </TabsContent>
        )}

        <TabsContent value="accounting" className="mt-5">
          <TabProfile partnerId={partner.id} canEdit={canEdit} kind="accounting" />
        </TabsContent>

        <TabsContent value={isCustomer ? 'invoices' : 'expenses-purchases'} className="mt-5">
          {isCustomer ? <TabInvoices partnerId={partner.id} /> : <TabExpensesPurchases partnerId={partner.id} />}
        </TabsContent>

        <TabsContent value="payments" className="mt-5 space-y-5">
          {isCustomer && canFollowup && <FollowupPanel partnerId={partner.id} canManage={canManageFollowup} />}
          <div>
            {isCustomer && <h3 className="mb-2 text-sm font-semibold">{tx('Payments received')}</h3>}
            <TabPayments partnerId={partner.id} direction={isCustomer ? 'inbound' : 'outbound'} />
          </div>
        </TabsContent>

        {canCrm && (
          <TabsContent value="crm" className="mt-5">
            <CrmPartnerTab partnerId={partner.id} />
          </TabsContent>
        )}

        {isCustomer ? (
          <TabsContent value="statement" className="mt-5">
            <TabStatement partnerId={partner.id} />
          </TabsContent>
        ) : (
          <>
            <TabsContent value="purchase-orders" className="mt-5">
              <TabPurchaseOrders partnerId={partner.id} />
            </TabsContent>
            <TabsContent value="ledger" className="mt-5">
              <TabLedger partnerId={partner.id} />
            </TabsContent>
          </>
        )}
      </Tabs>
    </div>
  );
}

export { PartnerDetailPage };

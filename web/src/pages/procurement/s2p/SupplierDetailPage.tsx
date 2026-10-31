import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { AlertTriangle, ArrowLeft, BadgeCheck, Ban, Landmark, Plus, ShieldCheck, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { errMsg, SUPPLIER_STATUS_TONE, supplierApi, useSupplier, useSupplierMutation } from '@/features/procurement/api';
import { notify } from '@/lib/notify';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { SUPPLIER_STATUS_LABEL } from './SuppliersPage';

type Action = 'approve' | 'provisional' | 'suspend' | 'blacklist' | 'reinstate';

const ACTIONS: Record<string, Action[]> = {
  none: ['provisional', 'approve', 'blacklist'],
  prospect: ['provisional', 'approve', 'suspend', 'blacklist'],
  provisional: ['approve', 'suspend', 'blacklist'],
  approved: ['suspend', 'blacklist'],
  suspended: ['approve', 'reinstate', 'blacklist'],
  blacklisted: ['reinstate'],
};

const ACTION_LABEL: Record<Action, string> = {
  approve: tx('Approve'),
  provisional: tx('Mark provisional'),
  suspend: tx('Suspend'),
  blacklist: tx('Blacklist'),
  reinstate: tx('Reinstate'),
};

/**
 * Supplier 360 (ADR-PROCUREMENT-001): qualification status and lifecycle, profile, dual-control bank accounts,
 * certificates with expiry, and the supplier's catalogue. Eligibility explains whether orders may be raised now.
 */
export function SupplierDetailPage() {
  const { partnerId } = useParams();
  const { data, isLoading } = useSupplier(partnerId);
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.supplier.manage);
  const canApprove = has(PERMISSIONS.supplier.approve);
  const canVerify = has(PERMISSIONS.supplier.verifyBank);
  const run = useSupplierMutation((fn: () => Promise<unknown>) => fn());
  const [pending, setPending] = useState<Action | null>(null);
  const [reason, setReason] = useState('');
  const [bankOpen, setBankOpen] = useState(false);
  const [bank, setBank] = useState({ bankName: '', branchName: '', accountName: '', accountNumber: '', swiftCode: '', isDefault: true });
  const [certOpen, setCertOpen] = useState(false);
  const [cert, setCert] = useState({ type: '', number: '', issuer: '', validFrom: '', validTo: '' });
  const [profileOpen, setProfileOpen] = useState(false);
  const [profile, setProfile] = useState({ riskClass: 'medium', categories: '', leadTimeDays: '', registrationNumber: '', tradingName: '', notes: '' });

  if (isLoading || !data) return <div className="p-6 text-sm text-muted-foreground">{tx('Loading…')}</div>;
  const status = data.profile?.lifecycleStatus ?? 'none';

  const exec = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await run.mutateAsync(fn);
      notify.success(ok);
      return true;
    } catch (e) {
      notify.error(tx('Action failed'), errMsg(e));
      return false;
    }
  };

  const doAction = async (a: Action) => {
    if ((a === 'suspend' || a === 'blacklist') && !reason.trim()) {
      notify.error(tx('A reason is required'));
      return;
    }
    if (await exec(() => supplierApi.transition(partnerId!, a, reason.trim() || undefined), tx('Supplier updated'))) {
      setPending(null);
      setReason('');
    }
  };

  const openProfile = () => {
    setProfile({
      riskClass: data.profile?.riskClass ?? 'medium',
      categories: (data.profile?.categories ?? []).join(', '),
      leadTimeDays: data.profile?.leadTimeDays != null ? String(data.profile.leadTimeDays) : '',
      registrationNumber: data.profile?.registrationNumber ?? '',
      tradingName: data.profile?.tradingName ?? '',
      notes: data.profile?.notes ?? '',
    });
    setProfileOpen(true);
  };

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link to="/procurement/suppliers" className="mb-1 inline-flex items-center gap-1 text-sm text-muted-foreground hover:underline">
            <ArrowLeft className="h-3 w-3" /> {tx('Suppliers')}
          </Link>
          <h1 className="text-2xl font-semibold">{data.partner.name}</h1>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <StatusPill tone={SUPPLIER_STATUS_TONE[status]}>{SUPPLIER_STATUS_LABEL[status === 'none' ? 'unqualified' : status]}</StatusPill>
            {data.partner.taxNumber && <span>{tx('TIN')}: {data.partner.taxNumber}</span>}
            {data.partner.phone && <span>{data.partner.phone}</span>}
            {data.partner.email && <span>{data.partner.email}</span>}
          </div>
        </div>
        {canApprove && (
          <div className="flex flex-wrap gap-2">
            {ACTIONS[status].map((a) => (
              <Button key={a} size="sm" variant={a === 'approve' ? 'default' : a === 'blacklist' ? 'destructive' : 'outline'}
                onClick={() => (a === 'suspend' || a === 'blacklist' ? setPending(a) : doAction(a))}>
                {a === 'approve' ? <BadgeCheck className="mr-1 h-4 w-4" /> : a === 'blacklist' ? <Ban className="mr-1 h-4 w-4" /> : null}
                {ACTION_LABEL[a]}
              </Button>
            ))}
          </div>
        )}
      </div>

      <Card className={data.eligibility.eligible ? '' : 'border-destructive/50'}>
        <CardContent className="flex items-start gap-3 pt-6 text-sm">
          {data.eligibility.eligible
            ? <ShieldCheck className="mt-0.5 h-5 w-5 text-emerald-600" />
            : <AlertTriangle className="mt-0.5 h-5 w-5 text-destructive" />}
          <div>
            <div className="font-medium">
              {data.eligibility.eligible ? tx('Purchase orders can be raised for this supplier') : tx('Purchase orders are blocked for this supplier')}
            </div>
            {[...data.eligibility.blocked, ...data.eligibility.warnings].map((m) => <div key={m} className="text-muted-foreground">{m}</div>)}
            {data.profile?.statusReason && <div className="text-muted-foreground">{tx('Reason')}: {data.profile.statusReason}</div>}
          </div>
        </CardContent>
      </Card>

      <Tabs defaultValue="profile">
        <TabsList>
          <TabsTrigger value="profile">{tx('Profile')}</TabsTrigger>
          <TabsTrigger value="bank">{tx('Bank accounts')}</TabsTrigger>
          <TabsTrigger value="certificates">{tx('Certificates')}</TabsTrigger>
          <TabsTrigger value="catalogue">{tx('Catalogue')}</TabsTrigger>
        </TabsList>

        <TabsContent value="profile">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle>{tx('Profile')}</CardTitle>
              {canManage && <Button size="sm" variant="outline" onClick={openProfile}>{tx('Edit')}</Button>}
            </CardHeader>
            <CardContent className="grid gap-3 text-sm sm:grid-cols-3">
              <Field label={tx('Risk')} value={data.profile?.riskClass} />
              <Field label={tx('Categories')} value={(data.profile?.categories ?? []).join(', ')} />
              <Field label={tx('Lead time')} value={data.profile?.leadTimeDays != null ? tx('{{n}} days', { n: data.profile.leadTimeDays }) : null} />
              <Field label={tx('Registration no.')} value={data.profile?.registrationNumber} />
              <Field label={tx('Trading name')} value={data.profile?.tradingName} />
              <Field label={tx('Approved on')} value={data.profile?.approvedAt ? date(data.profile.approvedAt) : null} />
              <div className="sm:col-span-3"><Field label={tx('Notes')} value={data.profile?.notes} /></div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="bank">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle className="flex items-center gap-2"><Landmark className="h-4 w-4" /> {tx('Bank accounts')}</CardTitle>
              {canManage && <Button size="sm" onClick={() => setBankOpen(true)}><Plus className="mr-1 h-4 w-4" /> {tx('Add account')}</Button>}
            </CardHeader>
            <CardContent>
              <p className="mb-3 text-xs text-muted-foreground">
                {tx('A new or changed account must be verified by a second person before it can be used for payments.')}
              </p>
              <Table>
                <TableHeader><TableRow>
                  <TableHead>{tx('Bank')}</TableHead><TableHead>{tx('Account')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead className="text-right">{tx('Actions')}</TableHead>
                </TableRow></TableHeader>
                <TableBody>
                  {data.bankAccounts.map((b) => (
                    <TableRow key={b.id}>
                      <TableCell>{b.bankName}{b.branchName ? ` · ${b.branchName}` : ''}{b.isDefault && <span className="ml-2 text-xs text-muted-foreground">({tx('default')})</span>}</TableCell>
                      <TableCell>{b.accountName}<div className="font-mono text-xs">{b.accountNumberMasked}</div></TableCell>
                      <TableCell>
                        <StatusPill tone={b.status === 'verified' ? 'success' : b.status === 'revoked' ? 'neutral' : 'warning'}>
                          {b.status === 'verified' ? tx('Verified') : b.status === 'revoked' ? tx('Revoked') : tx('Pending verification')}
                        </StatusPill>
                      </TableCell>
                      <TableCell className="space-x-2 text-right">
                        {canVerify && b.status === 'pending_verification' && (
                          <Button size="sm" variant="outline" onClick={() => exec(() => supplierApi.verifyBankAccount(b.id), tx('Account verified'))}>{tx('Verify')}</Button>
                        )}
                        {canManage && b.status !== 'revoked' && (
                          <Button size="sm" variant="ghost" onClick={() => exec(() => supplierApi.revokeBankAccount(b.id), tx('Account revoked'))}>{tx('Revoke')}</Button>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                  {!data.bankAccounts.length && <TableRow><TableCell colSpan={4} className="text-muted-foreground">{tx('No bank accounts yet.')}</TableCell></TableRow>}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="certificates">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle>{tx('Certificates')}</CardTitle>
              {canManage && <Button size="sm" onClick={() => setCertOpen(true)}><Plus className="mr-1 h-4 w-4" /> {tx('Add certificate')}</Button>}
            </CardHeader>
            <CardContent>
              <Table>
                <TableHeader><TableRow>
                  <TableHead>{tx('Type')}</TableHead><TableHead>{tx('Number')}</TableHead><TableHead>{tx('Issuer')}</TableHead><TableHead>{tx('Valid until')}</TableHead><TableHead />
                </TableRow></TableHeader>
                <TableBody>
                  {data.certificates.map((c) => {
                    const expired = c.validTo && c.validTo < new Date().toISOString().slice(0, 10);
                    return (
                      <TableRow key={c.id}>
                        <TableCell className="font-medium">{c.type}</TableCell>
                        <TableCell>{c.number ?? '—'}</TableCell>
                        <TableCell>{c.issuer ?? '—'}</TableCell>
                        <TableCell>{c.validTo ? <StatusPill tone={expired ? 'danger' : 'neutral'}>{date(c.validTo)}</StatusPill> : '—'}</TableCell>
                        <TableCell className="text-right">
                          {canManage && <Button size="icon" variant="ghost" aria-label={tx('Remove')} onClick={() => exec(() => supplierApi.removeCertificate(c.id), tx('Certificate removed'))}><Trash2 className="h-4 w-4" /></Button>}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                  {!data.certificates.length && <TableRow><TableCell colSpan={5} className="text-muted-foreground">{tx('No certificates yet.')}</TableCell></TableRow>}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="catalogue">
          <Card>
            <CardHeader><CardTitle>{tx('Catalogue')}</CardTitle></CardHeader>
            <CardContent>
              <Table>
                <TableHeader><TableRow>
                  <TableHead>{tx('Product')}</TableHead><TableHead>{tx('Supplier SKU')}</TableHead><TableHead>{tx('Lead time')}</TableHead><TableHead>{tx('Preferred')}</TableHead>
                </TableRow></TableHeader>
                <TableBody>
                  {data.products.map((p) => (
                    <TableRow key={p.id}>
                      <TableCell>{p.productName}<div className="text-xs text-muted-foreground">{p.productCode}</div></TableCell>
                      <TableCell>{p.supplierSku ?? '—'}</TableCell>
                      <TableCell>{p.leadTimeDays != null ? tx('{{n}} days', { n: p.leadTimeDays }) : '—'}</TableCell>
                      <TableCell>{p.preferred ? tx('Yes') : tx('No')}</TableCell>
                    </TableRow>
                  ))}
                  {!data.products.length && <TableRow><TableCell colSpan={4} className="text-muted-foreground">{tx('Products this supplier sells appear here once you buy from them or add them.')}</TableCell></TableRow>}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      <Dialog open={!!pending} onOpenChange={(o) => !o && setPending(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>{pending ? ACTION_LABEL[pending] : ''}</DialogTitle></DialogHeader>
          <Label>{tx('Reason')}</Label>
          <Textarea value={reason} onChange={(e) => setReason(e.target.value)} />
          <DialogFooter>
            <Button variant="outline" onClick={() => setPending(null)}>{tx('Cancel')}</Button>
            <Button variant="destructive" onClick={() => pending && doAction(pending)}>{tx('Confirm')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={bankOpen} onOpenChange={setBankOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Add bank account')}</DialogTitle></DialogHeader>
          <div className="grid gap-3">
            <div><Label>{tx('Bank')}</Label><Input value={bank.bankName} onChange={(e) => setBank({ ...bank, bankName: e.target.value })} /></div>
            <div><Label>{tx('Branch')}</Label><Input value={bank.branchName} onChange={(e) => setBank({ ...bank, branchName: e.target.value })} /></div>
            <div><Label>{tx('Account name')}</Label><Input value={bank.accountName} onChange={(e) => setBank({ ...bank, accountName: e.target.value })} /></div>
            <div><Label>{tx('Account number')}</Label><Input value={bank.accountNumber} autoComplete="off" onChange={(e) => setBank({ ...bank, accountNumber: e.target.value })} /></div>
            <div><Label>{tx('SWIFT / BIC')}</Label><Input value={bank.swiftCode} onChange={(e) => setBank({ ...bank, swiftCode: e.target.value })} /></div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setBankOpen(false)}>{tx('Cancel')}</Button>
            <Button onClick={async () => {
              if (await exec(() => supplierApi.addBankAccount(partnerId!, { ...bank, branchName: bank.branchName || undefined, swiftCode: bank.swiftCode || undefined }), tx('Account added — awaiting verification'))) {
                setBankOpen(false);
                setBank({ bankName: '', branchName: '', accountName: '', accountNumber: '', swiftCode: '', isDefault: true });
              }
            }}>{tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={certOpen} onOpenChange={setCertOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Add certificate')}</DialogTitle></DialogHeader>
          <div className="grid gap-3">
            <div><Label>{tx('Type (e.g. tin, trade_licence, explosives_licence)')}</Label><Input value={cert.type} onChange={(e) => setCert({ ...cert, type: e.target.value })} /></div>
            <div><Label>{tx('Number')}</Label><Input value={cert.number} onChange={(e) => setCert({ ...cert, number: e.target.value })} /></div>
            <div><Label>{tx('Issuer')}</Label><Input value={cert.issuer} onChange={(e) => setCert({ ...cert, issuer: e.target.value })} /></div>
            <div className="grid grid-cols-2 gap-3">
              <div><Label>{tx('Valid from')}</Label><Input type="date" value={cert.validFrom} onChange={(e) => setCert({ ...cert, validFrom: e.target.value })} /></div>
              <div><Label>{tx('Valid until')}</Label><Input type="date" value={cert.validTo} onChange={(e) => setCert({ ...cert, validTo: e.target.value })} /></div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCertOpen(false)}>{tx('Cancel')}</Button>
            <Button onClick={async () => {
              const body = Object.fromEntries(Object.entries(cert).filter(([, v]) => v));
              if (await exec(() => supplierApi.addCertificate(partnerId!, body), tx('Certificate added'))) {
                setCertOpen(false);
                setCert({ type: '', number: '', issuer: '', validFrom: '', validTo: '' });
              }
            }}>{tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={profileOpen} onOpenChange={setProfileOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Edit profile')}</DialogTitle></DialogHeader>
          <div className="grid gap-3">
            <div>
              <Label>{tx('Risk')}</Label>
              <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={profile.riskClass} onChange={(e) => setProfile({ ...profile, riskClass: e.target.value })}>
                <option value="low">{tx('Low')}</option>
                <option value="medium">{tx('Medium')}</option>
                <option value="high">{tx('High')}</option>
              </select>
            </div>
            <div><Label>{tx('Categories (comma separated)')}</Label><Input value={profile.categories} onChange={(e) => setProfile({ ...profile, categories: e.target.value })} /></div>
            <div><Label>{tx('Lead time (days)')}</Label><Input type="number" min={0} value={profile.leadTimeDays} onChange={(e) => setProfile({ ...profile, leadTimeDays: e.target.value })} /></div>
            <div><Label>{tx('Registration no.')}</Label><Input value={profile.registrationNumber} onChange={(e) => setProfile({ ...profile, registrationNumber: e.target.value })} /></div>
            <div><Label>{tx('Trading name')}</Label><Input value={profile.tradingName} onChange={(e) => setProfile({ ...profile, tradingName: e.target.value })} /></div>
            <div><Label>{tx('Notes')}</Label><Textarea value={profile.notes} onChange={(e) => setProfile({ ...profile, notes: e.target.value })} /></div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setProfileOpen(false)}>{tx('Cancel')}</Button>
            <Button onClick={async () => {
              const body = {
                riskClass: profile.riskClass,
                categories: profile.categories.split(',').map((c) => c.trim()).filter(Boolean),
                leadTimeDays: profile.leadTimeDays === '' ? undefined : Number(profile.leadTimeDays),
                registrationNumber: profile.registrationNumber || undefined,
                tradingName: profile.tradingName || undefined,
                notes: profile.notes || undefined,
              };
              if (await exec(() => supplierApi.saveProfile(partnerId!, body), tx('Profile saved'))) setProfileOpen(false);
            }}>{tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function Field({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div>{value || '—'}</div>
    </div>
  );
}

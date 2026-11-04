import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Plus, Settings2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import { Field } from '@/pages/crm/crm-ui';
import { PartnerPicker } from '@/pages/crm/crm-dialogs';
import { useBranchProfiles, type BranchProfile } from '@/features/sales/cash-sales-api';

interface Opt { id: string; name: string; code?: string | null; branchId?: string | null }

const ACCOUNTS: Array<[keyof BranchProfile, string]> = [
  ['cashAccountId', tx('Cash on hand')], ['momoMtnAccountId', tx('MTN mobile money clearing')],
  ['momoAirtelAccountId', tx('Airtel mobile money clearing')], ['bankClearingAccountId', tx('Bank clearing')],
];

/**
 * Branch sales profiles (ADR-S056): which stock location a showroom sells from, which account receives each payment
 * method, the walk-in customer, numbering, segregation and the fiscal trigger. The server validates every account.
 */
export function BranchProfilesPage() {
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.branchProfileManage));
  const { data } = useBranchProfiles();
  const [edit, setEdit] = useState<Partial<BranchProfile> | null>(null);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Settings2} title={tx('Branch Sales Profiles')}
        description={tx('Stock location, receiving accounts, walk-in customer and controls for each showroom that submits cash sales to head office.')}
        actions={canManage ? <Button onClick={() => setEdit({ requireSegregation: false, isActive: true, rejectedAlertDays: 7, fiscalProvider: 'none' })}><Plus className="mr-1 h-4 w-4" />{tx('New profile')}</Button> : undefined} />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[760px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className="px-3 py-2">{tx('Branch')}</th><th className="px-3 py-2">{tx('Stock location')}</th><th className="px-3 py-2">{tx('Prefix')}</th>
            <th className="px-3 py-2">{tx('Walk-in customer')}</th><th className="px-3 py-2">{tx('Segregation')}</th><th className="px-3 py-2">{tx('Fiscal')}</th><th className="px-3 py-2">{tx('Active')}</th>
          </tr></thead>
          <tbody>
            {(data ?? []).map((p) => (
              <tr key={p.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => canManage && setEdit(p)}>
                <td className="px-3 py-2 font-medium">{p.branchName}</td><td className="px-3 py-2">{p.locationName}</td><td className="px-3 py-2">{p.numberPrefix}</td>
                <td className="px-3 py-2">{p.walkInPartnerName ?? '—'}</td><td className="px-3 py-2">{p.requireSegregation ? tx('Strict') : tx('Basic')}</td>
                <td className="px-3 py-2 text-xs">{p.fiscalTrigger ? `${p.fiscalTrigger.replace('_', ' ')} · ${p.fiscalProvider}` : tx('Not configured')}</td>
                <td className="px-3 py-2">{p.isActive ? tx('Yes') : tx('No')}</td>
              </tr>
            ))}
            {data && data.length === 0 && <tr><td colSpan={7} className="p-6 text-center text-muted-foreground">{tx('No branch sales profile yet.')}</td></tr>}
          </tbody>
        </table>
      </div>
      {edit && <ProfileDialog initial={edit} onClose={() => setEdit(null)} />}
    </div>
  );
}

function ProfileDialog({ initial, onClose }: { initial: Partial<BranchProfile>; onClose: () => void }) {
  const qc = useQueryClient();
  const [v, setV] = useState<Partial<BranchProfile>>(initial);
  const [busy, setBusy] = useState(false);
  const branches = useQuery({ queryKey: ['branches', 'options'], queryFn: async () => (await api.get<{ data: Opt[] }>('/branches', { params: { pageSize: 200 } })).data.data });
  const locations = useQuery({ queryKey: ['inventory', 'locations', 'options'], queryFn: async () => (await api.get<{ data: Opt[] }>('/inventory/locations', { params: { pageSize: 500 } })).data.data });
  const accounts = useQuery({ queryKey: ['accounts', 'options'], queryFn: async () => (await api.get<{ data: Opt[] }>('/accounts', { params: { pageSize: 500 } })).data.data });
  const set = (k: keyof BranchProfile, x: unknown) => setV({ ...v, [k]: x });
  const save = async () => {
    setBusy(true);
    try {
      const body: Record<string, unknown> = {
        locationId: v.locationId, numberPrefix: v.numberPrefix, walkInPartnerId: v.walkInPartnerId ?? null,
        requireSegregation: !!v.requireSegregation, fiscalTrigger: v.fiscalTrigger ?? null, fiscalProvider: v.fiscalProvider ?? 'none',
        rejectedAlertDays: Number(v.rejectedAlertDays ?? 7), isActive: v.isActive !== false,
      };
      ACCOUNTS.forEach(([k]) => { body[k] = v[k] ?? null; });
      if (v.id) await api.patch(`/sales/branch-profiles/${v.id}`, body);
      else await api.post('/sales/branch-profiles', { ...body, branchId: v.branchId });
      toast.success(tx('Profile saved'));
      await qc.invalidateQueries({ queryKey: ['cash-sales', 'profiles'] });
      onClose();
    } catch (e) { toast.error(apiErrorMessage(e, tx('Action failed'))); } finally { setBusy(false); }
  };
  const select = (k: keyof BranchProfile, opts: Opt[] | undefined, placeholder: string) => (
    <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={(v[k] as string) ?? ''} onChange={(e) => set(k, e.target.value || null)}>
      <option value="">{placeholder}</option>
      {(opts ?? []).map((o) => <option key={o.id} value={o.id}>{o.code ? `${o.code} · ` : ''}{o.name}</option>)}
    </select>
  );
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader><DialogTitle>{v.id ? v.branchName : tx('New branch sales profile')}</DialogTitle></DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          {!v.id && <Field label={tx('Branch')}>{select('branchId', branches.data, tx('Select branch…'))}</Field>}
          <Field label={tx('Stock location')}>{select('locationId', (locations.data ?? []).filter((l) => !v.branchId || l.branchId === v.branchId), tx('Select location…'))}</Field>
          <Field label={tx('Number prefix')} hint="CSS-<prefix>-YYYY-00001"><Input value={v.numberPrefix ?? ''} onChange={(e) => set('numberPrefix', e.target.value.toUpperCase())} /></Field>
          <Field label={tx('Walk-in customer')}><PartnerPicker value={v.walkInPartnerId ?? ''} label={v.walkInPartnerName} onPick={(id, name) => setV({ ...v, walkInPartnerId: id, walkInPartnerName: name })} /></Field>
          {ACCOUNTS.map(([k, label]) => <Field key={k} label={label}>{select(k, accounts.data, tx('Not accepted'))}</Field>)}
          <Field label={tx('Fiscal trigger')} hint={tx('Pending accountant sign-off (VAT tax point / EFRIS).')}>
            <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={v.fiscalTrigger ?? ''} onChange={(e) => set('fiscalTrigger', e.target.value || null)}>
              <option value="">{tx('Not configured')}</option><option value="at_confirm">{tx('At showroom confirm')}</option><option value="at_invoice">{tx('At head-office invoice')}</option>
            </select>
          </Field>
          <Field label={tx('Fiscal provider')}>
            <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={v.fiscalProvider ?? 'none'} onChange={(e) => set('fiscalProvider', e.target.value)}>
              <option value="none">{tx('None')}</option><option value="mock">{tx('Simulated')}</option>
            </select>
          </Field>
          <Field label={tx('Alert on unresolved rejections after (days)')}><Input type="number" min={1} value={v.rejectedAlertDays ?? 7} onChange={(e) => set('rejectedAlertDays', Number(e.target.value))} /></Field>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={!!v.requireSegregation} onChange={(e) => set('requireSegregation', e.target.checked)} />{tx('Strict segregation (verifier ≠ collector ≠ confirmer ≠ approver ≠ invoicer)')}</label>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={v.isActive !== false} onChange={(e) => set('isActive', e.target.checked)} />{tx('Active')}</label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Close')}</Button>
          <Button disabled={busy || !v.locationId || !v.numberPrefix || (!v.id && !v.branchId)} onClick={save}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

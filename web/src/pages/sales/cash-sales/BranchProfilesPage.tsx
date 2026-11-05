import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Plus, Settings2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import { Field } from '@/pages/crm/crm-ui';
import { useBranchProfiles, type BranchProfile } from '@/features/sales/cash-sales-api';

interface Opt { id: string; name: string; code?: string | null; branchId?: string | null }

/**
 * Branch sales profiles (ADR-S056), kept basic: the branch and the stock location its showroom sells from. The
 * number prefix is derived from the branch; the receiving account of each payment comes from the payment mode the
 * user picks on the cash sale (Billing & payments details).
 */
export function BranchProfilesPage() {
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.branchProfileManage));
  const { data } = useBranchProfiles();
  const [edit, setEdit] = useState<Partial<BranchProfile> | null>(null);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Settings2} title={tx('Branch Sales Profiles')}
        description={tx('The branch and the stock location each showroom sells from.')}
        actions={canManage ? <Button onClick={() => setEdit({})}><Plus className="mr-1 h-4 w-4" />{tx('New profile')}</Button> : undefined} />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className="px-3 py-2">{tx('Branch')}</th><th className="px-3 py-2">{tx('Branch location')}</th>
          </tr></thead>
          <tbody>
            {(data ?? []).map((p) => (
              <tr key={p.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => canManage && setEdit(p)}>
                <td className="px-3 py-2 font-medium">{p.branchName}</td><td className="px-3 py-2">{p.locationName}</td>
              </tr>
            ))}
            {data && data.length === 0 && <tr><td colSpan={2} className="p-6 text-center text-muted-foreground">{tx('No branch sales profile yet.')}</td></tr>}
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
  const branchLocations = (locations.data ?? []).filter((l) => !v.branchId || l.branchId === v.branchId);
  const save = async () => {
    setBusy(true);
    try {
      const body: Record<string, unknown> = { locationId: v.locationId };
      if (v.id) await api.patch(`/sales/branch-profiles/${v.id}`, body);
      else await api.post('/sales/branch-profiles', { ...body, branchId: v.branchId });
      toast.success(tx('Profile saved'));
      await qc.invalidateQueries({ queryKey: ['cash-sales', 'profiles'] });
      onClose();
    } catch (e) { toast.error(apiErrorMessage(e, tx('Action failed'))); } finally { setBusy(false); }
  };
  const select = (k: keyof BranchProfile, opts: Opt[] | undefined, placeholder: string) => (
    <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={(v[k] as string) ?? ''} onChange={(e) => setV({ ...v, [k]: e.target.value || null, ...(k === 'branchId' ? { locationId: undefined } : {}) })}>
      <option value="">{placeholder}</option>
      {(opts ?? []).map((o) => <option key={o.id} value={o.id}>{o.code ? `${o.code} · ` : ''}{o.name}</option>)}
    </select>
  );
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{v.id ? v.branchName : tx('New branch sales profile')}</DialogTitle>
          <DialogDescription>
            {tx('Choose the branch and the stock location its showroom sells from. Cash sales of this branch issue stock from that location. The sale number prefix is taken from the branch code, and the receiving account of each payment comes from the payment mode picked on the cash sale.')}
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          {!v.id && <Field label={tx('Branch name')}>{select('branchId', branches.data, tx('Select branch…'))}</Field>}
          <Field label={tx('Branch location')} hint={tx('Only active stock locations of the selected branch are listed.')}>{select('locationId', branchLocations, tx('Select location…'))}</Field>
          {v.branchId && locations.data && branchLocations.length === 0 && (
            <p className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900 dark:bg-transparent">{tx('This branch has no active stock location yet. Create one under Inventory → Locations first.')}</p>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Close')}</Button>
          <Button disabled={busy || !v.locationId || (!v.id && !v.branchId)} onClick={save}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

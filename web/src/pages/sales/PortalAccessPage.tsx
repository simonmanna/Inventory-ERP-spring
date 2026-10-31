import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Copy, Globe, Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { Field } from '@/pages/crm/crm-ui';
import { PartnerPicker } from '@/pages/crm/crm-dialogs';

interface Access { id: string; partnerName: string; email: string | null; expiresAt: string; revokedAt: string | null; lastUsedAt: string | null; createdAt: string }
interface Issued extends Access { url: string; emailed: boolean }

/** Customer portal links (licence customerPortal, ADR-S011 S10). */
export function PortalAccessPage() {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['sales', 'portal-access'], queryFn: async () => (await api.get<Access[]>('/sales/portal/access')).data });
  const [form, setForm] = useState<{ partnerId: string; partnerName?: string; email: string; days: number; sendEmail: boolean } | null>(null);
  const [issued, setIssued] = useState<Issued | null>(null);
  const issue = useMutation({
    mutationFn: async () => (await api.post<Issued>('/sales/portal/access', { ...form, baseUrl: window.location.origin })).data,
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ['sales', 'portal-access'] }); setForm(null); setIssued(r); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not create the link'))),
  });
  const revoke = useMutation({
    mutationFn: async (id: string) => (await api.post(`/sales/portal/access/${id}/revoke`, {})).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['sales', 'portal-access'] }),
  });
  const now = new Date();
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Globe} title={tx('Customer portal')} description={tx('Personal links that let a customer see quotations, orders, deliveries, invoices and statements, and pay online.')} />
      <Button onClick={() => setForm({ partnerId: '', email: '', days: 30, sendEmail: true })}><Plus className="mr-1 h-4 w-4" />{tx('New portal link')}</Button>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[720px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className="px-3 py-2">{tx('Customer')}</th><th className="px-3 py-2">{tx('E-mail')}</th><th className="px-3 py-2">{tx('Valid until')}</th><th className="px-3 py-2">{tx('Last used')}</th><th className="px-3 py-2">{tx('Status')}</th><th /></tr></thead>
          <tbody>
            {(data ?? []).map((a) => {
              const active = !a.revokedAt && new Date(a.expiresAt) > now;
              return (
                <tr key={a.id} className="border-t">
                  <td className="px-3 py-2">{a.partnerName}</td><td className="px-3 py-2">{a.email ?? '—'}</td><td className="px-3 py-2">{date(a.expiresAt)}</td>
                  <td className="px-3 py-2">{a.lastUsedAt ? date(a.lastUsedAt) : '—'}</td>
                  <td className="px-3 py-2">{a.revokedAt ? tx('Revoked') : active ? tx('Active') : tx('Expired')}</td>
                  <td className="px-3 py-2 text-right">{active && <Button size="sm" variant="outline" onClick={() => revoke.mutate(a.id)}>{tx('Revoke')}</Button>}</td>
                </tr>
              );
            })}
            {data && data.length === 0 && <tr><td colSpan={6} className="p-6 text-center text-muted-foreground">{tx('No portal links yet.')}</td></tr>}
          </tbody>
        </table>
      </div>
      {form && (
        <Dialog open onOpenChange={(v) => !v && setForm(null)}>
          <DialogContent className="max-w-md">
            <DialogHeader><DialogTitle>{tx('New portal link')}</DialogTitle></DialogHeader>
            <Field label={tx('Customer')}><PartnerPicker value={form.partnerId} label={form.partnerName} onPick={(id, n) => setForm({ ...form, partnerId: id, partnerName: n })} /></Field>
            <Field label={tx('E-mail (default: the customer\'s)')}><Input value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></Field>
            <Field label={tx('Valid for (days)')}><Input type="number" value={form.days} onChange={(e) => setForm({ ...form, days: Number(e.target.value) })} /></Field>
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={form.sendEmail} onChange={(e) => setForm({ ...form, sendEmail: e.target.checked })} />{tx('E-mail the link to the customer')}</label>
            <DialogFooter><Button variant="outline" onClick={() => setForm(null)}>{tx('Cancel')}</Button><Button disabled={!form.partnerId || issue.isPending} onClick={() => issue.mutate()}>{tx('Create link')}</Button></DialogFooter>
          </DialogContent>
        </Dialog>
      )}
      {issued && (
        <Dialog open onOpenChange={(v) => !v && setIssued(null)}>
          <DialogContent className="max-w-lg">
            <DialogHeader><DialogTitle>{tx('Portal link for')} {issued.partnerName}</DialogTitle></DialogHeader>
            <p className="text-sm text-muted-foreground">{issued.emailed ? tx('The link was e-mailed to the customer.') : tx('Share this link with the customer. It is shown only once.')}</p>
            <div className="flex gap-2"><Input readOnly value={issued.url} onFocus={(e) => e.target.select()} />
              <Button variant="outline" size="icon" onClick={() => navigator.clipboard.writeText(issued.url).then(() => toast.success(tx('Copied')))}><Copy className="h-4 w-4" /></Button></div>
            <DialogFooter><Button onClick={() => setIssued(null)}>{tx('Done')}</Button></DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}

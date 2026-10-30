import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { Calculator, Plus, Tags } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useProductOptions } from '@/features/crm/suite-api';
import { pricingApi, usePricelists, useSalesMutation, type PricelistType, type ResolvedPrice } from '@/features/sales/api';
import { Field } from '@/pages/crm/crm-ui';
import { PartnerPicker } from '@/pages/crm/crm-dialogs';

export const PRICELIST_TYPE_LABEL: Record<PricelistType, string> = {
  retail: tx('Retail'), wholesale: tx('Wholesale'), contractor: tx('Contractor'), distributor: tx('Distributor'),
  contract: tx('Contract / negotiated'), other: tx('Other'),
};

/** Pricelists (ADR-S011 phase S2) with a price simulator. */
export function PricelistsPage() {
  const { data, isLoading } = usePricelists();
  const navigate = useNavigate();
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.sales.pricingManage));
  const [creating, setCreating] = useState(false);
  const [sim, setSim] = useState(false);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Tags} title={tx('Pricelists')} description={tx('Retail, wholesale, contractor and negotiated prices by customer, category, quantity, unit, site zone and branch.')}
        actions={<div className="flex gap-2">
          <Button variant="outline" onClick={() => setSim(true)}><Calculator className="mr-1 h-4 w-4" />{tx('Price simulator')}</Button>
          {canManage && <Button onClick={() => setCreating(true)}><Plus className="mr-1 h-4 w-4" />{tx('New pricelist')}</Button>}
        </div>} />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[720px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            <tr><th className="px-3 py-2">{tx('Code')}</th><th className="px-3 py-2">{tx('Name')}</th><th className="px-3 py-2">{tx('Type')}</th>
              <th className="px-3 py-2">{tx('Currency')}</th><th className="px-3 py-2 text-right">{tx('Rules')}</th><th className="px-3 py-2 text-right">{tx('Customers')}</th><th className="px-3 py-2">{tx('Status')}</th></tr>
          </thead>
          <tbody>
            {isLoading && <tr><td colSpan={7} className="p-3"><Skeleton className="h-24 w-full" /></td></tr>}
            {data?.map((p) => (
              <tr key={p.id} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => navigate(`/sales/pricelists/${p.id}`)}>
                <td className="px-3 py-2 font-medium">{p.code}</td>
                <td className="px-3 py-2">{p.name}{p.isDefault && <span className="ml-2 rounded bg-primary/10 px-1.5 text-xs text-primary">{tx('Default')}</span>}</td>
                <td className="px-3 py-2">{PRICELIST_TYPE_LABEL[p.type]}</td>
                <td className="px-3 py-2">{p.currencyCode ?? tx('Any')}</td>
                <td className="px-3 py-2 text-right tabular-nums">{p.itemCount ?? 0}</td>
                <td className="px-3 py-2 text-right tabular-nums">{p.customerCount ?? 0}</td>
                <td className="px-3 py-2 text-xs">{p.isActive ? tx('Active') : tx('Inactive')}</td>
              </tr>
            ))}
            {data && data.length === 0 && <tr><td colSpan={7} className="p-6 text-center text-muted-foreground">{tx('No pricelists yet.')}</td></tr>}
          </tbody>
        </table>
      </div>
      {creating && <NewPricelistDialog onClose={() => setCreating(false)} onCreated={(id) => navigate(`/sales/pricelists/${id}`)} />}
      {sim && <SimulatorDialog onClose={() => setSim(false)} />}
    </div>
  );
}

function NewPricelistDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [type, setType] = useState<PricelistType>('wholesale');
  const save = useSalesMutation(pricingApi.savePricelist);
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('New pricelist')}</DialogTitle></DialogHeader>
        <div className="grid gap-3">
          <Field label={tx('Code')}><Input value={code} onChange={(e) => setCode(e.target.value)} placeholder="WHOLESALE" /></Field>
          <Field label={tx('Name')}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Type')}>
            <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={type} onChange={(e) => setType(e.target.value as PricelistType)}>
              {Object.entries(PRICELIST_TYPE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!code.trim() || !name.trim() || save.isPending} onClick={() => save.mutate({ code, name, type }, {
            onSuccess: (p) => onCreated(p.id), onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
          })}>{tx('Create')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** "What price would this customer pay?" */
function SimulatorDialog({ onClose }: { onClose: () => void }) {
  const products = useProductOptions();
  const opts = useMemo(() => (products.data ?? []).map((p) => ({ value: p.id, label: p.name })), [products.data]);
  const [partnerId, setPartnerId] = useState('');
  const [partnerName, setPartnerName] = useState<string | null>(null);
  const [productId, setProductId] = useState('');
  const [qty, setQty] = useState(1);
  const [res, setRes] = useState<ResolvedPrice | null>(null);
  const run = async () => {
    try { setRes((await pricingApi.resolve({ partnerId: partnerId || undefined, lines: [{ productId, quantity: qty }] })).lines[0]); }
    catch (e) { toast.error(apiErrorMessage(e, tx('Could not price'))); }
  };
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{tx('Price simulator')}</DialogTitle></DialogHeader>
        <div className="grid gap-3">
          <Field label={tx('Customer')}><PartnerPicker value={partnerId} label={partnerName} onPick={(i, n) => { setPartnerId(i); setPartnerName(n); }} /></Field>
          <Field label={tx('Product')}><SearchableSelect value={productId} onValueChange={setProductId} options={opts} placeholder={tx('Product…')} /></Field>
          <Field label={tx('Quantity')}><Input type="number" min={0} value={qty} onChange={(e) => setQty(Number(e.target.value))} /></Field>
        </div>
        {res && (
          <div className="rounded-lg border p-3 text-sm">
            <p className="text-lg font-semibold">{money(res.net)} <span className="text-xs font-normal text-muted-foreground">/ {tx('unit')}</span></p>
            <p className="text-xs text-muted-foreground">{tx('List')} {money(res.listPrice)} · {tx('Source')}: {res.source}{res.pricelistName ? ` (${res.pricelistName})` : ''}</p>
            {res.floorPrice != null && <p className="text-xs">{tx('Margin floor')}: {res.minMarginPercent}% → {tx('min. price')} {money(res.floorPrice)}</p>}
            {res.marginPercent != null && <p className="text-xs">{tx('Margin')}: {res.marginPercent}%</p>}
          </div>
        )}
        <DialogFooter><Button disabled={!productId} onClick={run}>{tx('Calculate')}</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

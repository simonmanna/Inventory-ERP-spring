import { useQuery } from '@tanstack/react-query';
import { Input } from '@/components/ui/input';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import { InlineField } from './sales-ui';

export interface OrderHeader { branchId: string; locationId: string; paymentTermId: string; currencyCode: string; exchangeRate: number; priority: number }
export const blankHeader: OrderHeader = { branchId: '', locationId: '', paymentTermId: '', currencyCode: '', exchangeRate: 1, priority: 0 };

type Opt = { id: string; name?: string; code?: string };
const list = (d: unknown): Opt[] => (Array.isArray(d) ? d : ((d as { data?: Opt[] })?.data ?? []));

/** Options a sales document header picks from; a route the user cannot read leaves its list empty. */
export function useHeaderOptions() {
  const get = (path: string) => async () => { try { return list((await api.get(path)).data); } catch { return []; } };
  const opts = { staleTime: 300_000, retry: false } as const;
  return {
    branches: useQuery({ queryKey: ['header-options', 'branches'], queryFn: get('/branches'), ...opts }).data ?? [],
    locations: useQuery({ queryKey: ['header-options', 'locations'], queryFn: get('/inventory/locations?pageSize=500'), ...opts }).data ?? [],
    terms: useQuery({ queryKey: ['header-options', 'terms'], queryFn: get('/payment-terms'), ...opts }).data ?? [],
    currencies: useQuery({ queryKey: ['header-options', 'currencies'], queryFn: get('/currencies'), ...opts }).data ?? [],
  };
}

const cls = 'h-9 w-full rounded-md border bg-background px-2 text-sm';

export function HeaderSelect({ value, options, disabled, onChange, none }: { value: string; options: Opt[]; disabled?: boolean; onChange: (v: string) => void; none: string }) {
  return (
    <select className={cls} disabled={disabled} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{none}</option>
      {options.map((o) => <option key={o.id} value={o.id}>{o.name ?? o.code}</option>)}
    </select>
  );
}

/** PO number, branch, warehouse, terms, currency, rate and priority of a sales order. */
export function OrderHeaderFields({ value, customerRef, disabled, onChange, onCustomerRef }: { value: OrderHeader; customerRef: string; disabled?: boolean; onChange: (h: OrderHeader) => void; onCustomerRef: (v: string) => void }) {
  const o = useHeaderOptions();
  const set = (p: Partial<OrderHeader>) => onChange({ ...value, ...p });
  const currencies = o.currencies.map((c) => ({ id: c.code ?? c.id, name: c.code ?? c.name }));
  return (
    <>
      <InlineField label={tx('Customer PO')}><Input disabled={disabled} value={customerRef} onChange={(e) => onCustomerRef(e.target.value)} /></InlineField>
      <InlineField label={tx('Branch')}><HeaderSelect value={value.branchId} options={o.branches} disabled={disabled} none={tx('From my scope')} onChange={(branchId) => set({ branchId })} /></InlineField>
      <InlineField label={tx('Warehouse')}><HeaderSelect value={value.locationId} options={o.locations} disabled={disabled} none={tx('Best available')} onChange={(locationId) => set({ locationId })} /></InlineField>
      <InlineField label={tx('Payment terms')}><HeaderSelect value={value.paymentTermId} options={o.terms} disabled={disabled} none={tx('Customer default')} onChange={(paymentTermId) => set({ paymentTermId })} /></InlineField>
      <InlineField label={tx('Currency')}>
        <div className="flex gap-2">
          <HeaderSelect value={value.currencyCode} options={currencies} disabled={disabled} none={tx('Base currency')} onChange={(currencyCode) => set({ currencyCode })} />
          <Input aria-label={tx('Exchange rate')} title={tx('Exchange rate')} disabled={disabled || !value.currencyCode} type="number" min={0} step="any" className="w-28" value={value.exchangeRate} onChange={(e) => set({ exchangeRate: Number(e.target.value) })} />
        </div>
      </InlineField>
      <InlineField label={tx('Priority')}>
        <select className={cls} disabled={disabled} value={value.priority} onChange={(e) => set({ priority: Number(e.target.value) })}>
          <option value={0}>{tx('Normal')}</option><option value={1}>{tx('High')}</option><option value={2}>{tx('Urgent')}</option>
        </select>
      </InlineField>
    </>
  );
}

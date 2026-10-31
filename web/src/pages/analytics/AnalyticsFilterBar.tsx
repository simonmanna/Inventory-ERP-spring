import { Input } from '@/components/ui/input';
import { SearchableSelect, type SearchableSelectOption } from '@/components/ui/searchable-select';
import { tx } from '@/lib/i18n/tx';
import { useFilterOptions, type AnalyticsQuery, type Option } from './api';

const selectClass = 'h-10 rounded-md border bg-background px-2 text-sm';

const toOptions = (rows: Option[] | undefined, allLabel: string): SearchableSelectOption[] => [
  { value: '', label: allLabel },
  ...(rows ?? []).map((r) => ({ value: r.id, label: r.name, code: r.code ?? undefined })),
];

function Select({ label, all, value, rows, onChange }: {
  label: string; all: string; value?: string; rows?: Option[]; onChange: (v: string) => void;
}) {
  return (
    <select aria-label={label} className={selectClass} value={value ?? ''} onChange={(e) => onChange(e.target.value)}>
      <option value="">{all}</option>
      {(rows ?? []).map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
    </select>
  );
}

/** Dates and the filters this dashboard honours (`supported`), in one row above the charts. */
export function AnalyticsFilterBar({ query, update, supported, suppliers }: {
  query: AnalyticsQuery;
  update: (patch: Partial<AnalyticsQuery>) => void;
  supported: string[];
  /** The partner filter picks suppliers instead of customers (procurement). */
  suppliers?: boolean;
}) {
  const { data: options } = useFilterOptions(suppliers ? undefined : query.partnerId);
  const has = (f: string) => supported.includes(f);
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Input type="date" aria-label={tx('From')} className="w-40" value={query.from} onChange={(e) => update({ from: e.target.value })} />
      <Input type="date" aria-label={tx('To')} className="w-40" value={query.to} onChange={(e) => update({ to: e.target.value })} />
      {has('branch') && (
        <Select label={tx('Branch')} all={tx('All branches')} value={query.branchId} rows={options?.branches}
          onChange={(v) => update({ branchId: v })} />
      )}
      {has('site') && (
        <Select label={tx('Site')} all={tx('All sites')} value={query.siteId} rows={options?.sites}
          onChange={(v) => update({ siteId: v })} />
      )}
      {has('product') && (
        <SearchableSelect className="w-56" value={query.productId ?? ''} placeholder={tx('All products')}
          options={toOptions(options?.products, tx('All products'))} onValueChange={(v) => update({ productId: v })} />
      )}
      {has('partner') && (
        <SearchableSelect className="w-56" value={query.partnerId ?? ''}
          placeholder={suppliers ? tx('All suppliers') : tx('All customers')}
          options={suppliers ? toOptions(options?.suppliers, tx('All suppliers')) : toOptions(options?.customers, tx('All customers'))}
          onValueChange={(v) => update({ partnerId: v, deliverySiteId: '' })} />
      )}
      {has('deliverySite') && query.partnerId && (
        <Select label={tx('Delivery site')} all={tx('All delivery sites')} value={query.deliverySiteId}
          rows={options?.deliverySites} onChange={(v) => update({ deliverySiteId: v })} />
      )}
    </div>
  );
}

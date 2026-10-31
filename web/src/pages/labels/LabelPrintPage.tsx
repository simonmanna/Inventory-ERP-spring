import { useState } from 'react';
import { FileDown, Printer, Tag } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput } from '@/components/list';
import { apiMessage, notify } from '@/lib/notify';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { tx } from '@/lib/i18n/tx';
import { renderLabels, useLabelSources, type LabelType } from '@/features/invoicing/interchange-api';

const TYPES: Array<{ value: LabelType; label: string }> = [
  { value: 'stockpile', label: tx('Stockpiles') },
  { value: 'ticket', label: tx('Weighbridge tickets') },
];

/** Pick items and print 100 × 60 mm labels (INT-3): PDF for any printer, ZPL for a Zebra thermal printer. */
export function LabelPrintPage() {
  const [type, setType] = useState<LabelType>('product');
  const [searchInput, setSearchInput] = useState('');
  const search = useDebouncedValue(searchInput, 300);
  const { data, isLoading, isError } = useLabelSources(type, search);
  const [selected, setSelected] = useState<string[]>([]);
  const [copies, setCopies] = useState('1');
  const [symbology, setSymbology] = useState<'qr' | 'code128'>('qr');
  const [busy, setBusy] = useState(false);

  const toggle = (id: string) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));
  const count = Math.max(1, Math.min(50, Number(copies) || 1));

  const print = async (format: 'pdf' | 'zpl') => {
    setBusy(true);
    try {
      await renderLabels({ type, ids: selected, copies: count, format, symbology });
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not render the labels')));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Tag} title={tx('Label Printing')} description={tx('Barcode labels for products, stockpiles and weighbridge tickets.')} />
      <ListToolbar>
        <FilterSelect value={type === 'product' ? '' : type} onChange={(v) => { setType((v || 'product') as LabelType); setSelected([]); }} options={TYPES} allLabel={tx('Products')} />
        <SearchInput value={searchInput} onChange={setSearchInput} placeholder={tx('Search…')} />
        <FilterSelect value={symbology === 'code128' ? 'code128' : ''} onChange={(v) => setSymbology(v === 'code128' ? 'code128' : 'qr')} options={[{ value: 'code128', label: 'Code 128' }]} allLabel={tx('QR code')} />
        <Input type="number" min={1} max={50} className="w-24" aria-label={tx('Copies')} value={copies} onChange={(e) => setCopies(e.target.value)} />
      </ListToolbar>
      <ListCard>
        <div className="divide-y">
          {isLoading && <p className="p-4 text-sm text-muted-foreground">…</p>}
          {isError && <p className="p-4 text-sm text-muted-foreground">{tx('Could not load the list. Try again.')}</p>}
          {data?.length === 0 && <p className="p-4 text-sm text-muted-foreground">{tx('Nothing to label here.')}</p>}
          {data?.map((s) => (
            <label key={s.id} className="flex cursor-pointer items-center gap-3 p-3 text-sm hover:bg-accent">
              <input type="checkbox" className="h-4 w-4" checked={selected.includes(s.id)} onChange={() => toggle(s.id)} />
              <span className="min-w-0 flex-1">
                <span className="block font-medium">{s.title}</span>
                <span className="block text-xs text-muted-foreground">{[s.subtitle, s.code].filter(Boolean).join(' · ')}</span>
              </span>
            </label>
          ))}
        </div>
      </ListCard>
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm text-muted-foreground">{tx('{{n}} selected', { n: selected.length })}</span>
        <Button disabled={selected.length === 0 || busy} onClick={() => print('pdf')}><Printer className="mr-1.5 h-4 w-4" /> {tx('Print PDF')}</Button>
        <Button variant="outline" disabled={selected.length === 0 || busy} onClick={() => print('zpl')}><FileDown className="mr-1.5 h-4 w-4" /> {tx('Download ZPL')}</Button>
      </div>
    </div>
  );
}

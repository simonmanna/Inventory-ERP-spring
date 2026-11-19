import { useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { Eye, FileText } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import { Field } from '@/pages/crm/crm-ui';

/** How a quotation prints (`SalesSettings.quoteLayout`); the server fills in defaults for missing keys. */
export type QuoteLayout = {
  title: string;
  companyName: string;
  showLogo: boolean;
  showCompanyDetails: boolean;
  accentColor: string;
  paperSize: 'A4' | 'A5' | 'Letter';
  orientation: 'portrait' | 'landscape';
  showUnit: boolean;
  showDiscount: boolean;
  showTax: boolean;
  showSize: boolean;
  showSections: boolean;
  showSalesperson: boolean;
  showNotes: boolean;
  showTerms: boolean;
  showSignature: boolean;
  headerText: string;
  footerText: string;
};

const SELECT = 'h-9 w-full rounded-md border bg-background px-2 text-sm';

const HEADER_FLAGS: Array<[keyof QuoteLayout, string]> = [
  ['showLogo', 'Company logo'],
  ['showCompanyDetails', 'Company address, phone and TIN'],
  ['showSalesperson', 'Salesperson'],
];
const LINE_FLAGS: Array<[keyof QuoteLayout, string]> = [
  ['showUnit', 'Unit column'],
  ['showDiscount', 'Discount column'],
  ['showTax', 'Tax column'],
  ['showSize', 'Size under the description (pieces × length × width)'],
  ['showSections', 'Section and note rows'],
];
const FOOT_FLAGS: Array<[keyof QuoteLayout, string]> = [
  ['showNotes', 'Notes'],
  ['showTerms', 'Terms and conditions'],
  ['showSignature', 'Signature block (prepared by / accepted by)'],
];

function Check({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <input type="checkbox" className="h-4 w-4" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      {label}
    </label>
  );
}

/**
 * Sales settings → Quotation layout: what the printed / PDF / emailed quotation shows, with a live PDF preview of the
 * unsaved choices (on the latest quotation, or sample lines). The company name defaults to the company settings.
 */
export function QuoteLayoutSettings({ saved }: { saved?: QuoteLayout }) {
  const qc = useQueryClient();
  const companyName = useAuthStore((s) => s.organization?.name) ?? '';
  const [layout, setLayout] = useState<QuoteLayout | undefined>(saved);
  const [preview, setPreview] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const previewUrl = useRef<string | null>(null);
  useEffect(() => { if (saved) setLayout(saved); }, [saved]);
  useEffect(() => () => { if (previewUrl.current) URL.revokeObjectURL(previewUrl.current); }, []);

  const save = useMutation({
    mutationFn: async () => (await api.put('/sales/settings', { quoteLayout: layout })).data,
    onSuccess: () => { toast.success(tx('Quotation layout saved')); qc.invalidateQueries({ queryKey: ['sales', 'settings'] }); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
  });

  if (!layout) return null;
  const set = <K extends keyof QuoteLayout>(k: K, v: QuoteLayout[K]) => setLayout((l) => (l ? { ...l, [k]: v } : l));

  const showPreview = async () => {
    setLoading(true);
    try {
      const r = await api.post('/sales/settings/quote-layout/preview', { layout }, { responseType: 'blob' });
      if (previewUrl.current) URL.revokeObjectURL(previewUrl.current);
      previewUrl.current = URL.createObjectURL(new Blob([r.data as Blob], { type: 'application/pdf' }));
      setPreview(previewUrl.current);
    } catch (e) {
      toast.error(apiErrorMessage(e, tx('Preview failed')));
    } finally {
      setLoading(false);
    }
  };

  const flags = (title: string, list: Array<[keyof QuoteLayout, string]>) => (
    <div className="space-y-2">
      <p className="text-xs font-medium uppercase text-muted-foreground">{tx(title)}</p>
      {list.map(([k, label]) => (
        <Check key={k} label={tx(label)} checked={layout[k] === true} onChange={(v) => set(k, v as never)} />
      ))}
    </div>
  );

  return (
    <section className="rounded-xl border bg-card p-5 shadow-sm">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="flex items-center gap-2 font-semibold"><FileText className="h-4 w-4" />{tx('Quotation layout')}</h2>
          <p className="text-sm text-muted-foreground">{tx('How quotations look when printed, downloaded as PDF or emailed.')}</p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" disabled={loading} onClick={showPreview}><Eye className="mr-2 h-4 w-4" />{loading ? tx('Loading…') : tx('Preview')}</Button>
          <Button disabled={save.isPending} onClick={() => save.mutate()}>{tx('Save layout')}</Button>
        </div>
      </div>
      <div className="grid gap-6 xl:grid-cols-2">
        <div className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label={tx('Document title')}>
              <Input value={layout.title} maxLength={60} onChange={(e) => set('title', e.target.value)} placeholder="QUOTATION" />
            </Field>
            <Field label={tx('Company name')} hint={tx('Leave empty to use the name from the company settings.')}>
              <Input value={layout.companyName} maxLength={120} onChange={(e) => set('companyName', e.target.value)} placeholder={companyName} />
            </Field>
            <Field label={tx('Accent colour')}>
              <div className="flex items-center gap-2">
                <input type="color" aria-label={tx('Accent colour')} className="h-9 w-12 cursor-pointer rounded border bg-background"
                  value={/^#[0-9a-fA-F]{6}$/.test(layout.accentColor) ? layout.accentColor : '#1F2937'}
                  onChange={(e) => set('accentColor', e.target.value)} />
                <Input value={layout.accentColor} maxLength={7} onChange={(e) => set('accentColor', e.target.value)} className="font-mono" />
              </div>
            </Field>
            <div className="grid grid-cols-2 gap-2">
              <Field label={tx('Paper')}>
                <select className={SELECT} value={layout.paperSize} onChange={(e) => set('paperSize', e.target.value as QuoteLayout['paperSize'])}>
                  <option value="A4">A4</option><option value="A5">A5</option><option value="Letter">Letter</option>
                </select>
              </Field>
              <Field label={tx('Orientation')}>
                <select className={SELECT} value={layout.orientation} onChange={(e) => set('orientation', e.target.value as QuoteLayout['orientation'])}>
                  <option value="portrait">{tx('Portrait')}</option><option value="landscape">{tx('Landscape')}</option>
                </select>
              </Field>
            </div>
          </div>
          <div className="grid gap-4 sm:grid-cols-3">
            {flags('Header', HEADER_FLAGS)}
            {flags('Lines', LINE_FLAGS)}
            {flags('Footer', FOOT_FLAGS)}
          </div>
          <Field label={tx('Text under the header')}>
            <Textarea rows={2} maxLength={2000} value={layout.headerText} onChange={(e) => set('headerText', e.target.value)}
              placeholder={tx('e.g. Thank you for your enquiry. We are pleased to quote as follows.')} />
          </Field>
          <Field label={tx('Footer text (every page)')}>
            <Input maxLength={500} value={layout.footerText} onChange={(e) => set('footerText', e.target.value)}
              placeholder={tx('e.g. Bank: ... · Prices valid for 30 days')} />
          </Field>
          <p className="text-xs text-muted-foreground">
            {tx('The company name, logo and address come from the company settings.')}{' '}
            <Link to="/settings/developer" className="underline">{tx('Company settings')}</Link>
            {' · '}{tx('Product images print when "Product images on quotations" is on and the user shows the Image column.')}
          </p>
        </div>
        <div>
          {preview
            ? <iframe title={tx('Preview')} src={preview} className="h-[720px] w-full rounded border bg-white" />
            : <div className="flex h-full min-h-48 items-center justify-center rounded border border-dashed p-6 text-center text-sm text-muted-foreground">
                {tx('Click Preview to see the latest quotation with these settings (nothing is saved).')}
              </div>}
        </div>
      </div>
    </section>
  );
}

import { useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { Eye, Wallet } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import { Field } from '@/pages/crm/crm-ui';

/** How a customer deposit receipt is headed (`SalesSettings.depositReceiptLayout`); the server fills in defaults. */
export type DepositReceiptLayout = {
  title: string;
  companyName: string;
  showLogo: boolean;
  showCompanyDetails: boolean;
  detailsText: string;
  showSignature: boolean;
  footerText: string;
};

function Check({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <input type="checkbox" className="h-4 w-4" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      {label}
    </label>
  );
}

/**
 * Sales settings → Deposit receipt: the business name, detail lines, logo, title, signature line and footer printed on
 * customer deposit receipts, with a PDF preview of the unsaved choices (latest deposit, or a sample).
 */
export function DepositReceiptSettings({ saved }: { saved?: DepositReceiptLayout }) {
  const qc = useQueryClient();
  const companyName = useAuthStore((s) => s.organization?.name) ?? '';
  const [layout, setLayout] = useState<DepositReceiptLayout | undefined>(saved);
  const [preview, setPreview] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const previewUrl = useRef<string | null>(null);
  useEffect(() => { if (saved) setLayout(saved); }, [saved]);
  useEffect(() => () => { if (previewUrl.current) URL.revokeObjectURL(previewUrl.current); }, []);

  const save = useMutation({
    mutationFn: async () => (await api.put('/sales/settings', { depositReceiptLayout: layout })).data,
    onSuccess: () => { toast.success(tx('Deposit receipt saved')); qc.invalidateQueries({ queryKey: ['sales', 'settings'] }); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
  });

  if (!layout) return null;
  const set = <K extends keyof DepositReceiptLayout>(k: K, v: DepositReceiptLayout[K]) => setLayout((l) => (l ? { ...l, [k]: v } : l));

  const showPreview = async () => {
    setLoading(true);
    try {
      const r = await api.post('/sales/settings/deposit-receipt/preview', { layout }, { responseType: 'blob' });
      if (previewUrl.current) URL.revokeObjectURL(previewUrl.current);
      previewUrl.current = URL.createObjectURL(new Blob([r.data as Blob], { type: 'application/pdf' }));
      setPreview(previewUrl.current);
    } catch (e) {
      toast.error(apiErrorMessage(e, tx('Preview failed')));
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="rounded-xl border bg-card p-5 shadow-sm">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="flex items-center gap-2 font-semibold"><Wallet className="h-4 w-4" />{tx('Deposit receipt')}</h2>
          <p className="text-sm text-muted-foreground">{tx('The header and footer printed on customer deposit / advance receipts.')}</p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" disabled={loading} onClick={showPreview}><Eye className="mr-2 h-4 w-4" />{loading ? tx('Loading…') : tx('Preview')}</Button>
          <Button disabled={save.isPending} onClick={() => save.mutate()}>{tx('Save receipt layout')}</Button>
        </div>
      </div>
      <div className="grid gap-6 xl:grid-cols-2">
        <div className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label={tx('Business name on the receipt')} hint={tx('Leave empty to use the name from the company settings.')}>
              <Input value={layout.companyName} maxLength={120} onChange={(e) => set('companyName', e.target.value)} placeholder={companyName} />
            </Field>
            <Field label={tx('Receipt title')}>
              <Input value={layout.title} maxLength={60} onChange={(e) => set('title', e.target.value)} placeholder="DEPOSIT RECEIPT" />
            </Field>
          </div>
          <Field label={tx('Lines under the name')} hint={tx('One per line, e.g. address, phone, TIN. Leave empty to use the address, phone, e-mail and TIN from the company settings.')}>
            <Textarea rows={4} maxLength={600} value={layout.detailsText} disabled={!layout.showCompanyDetails}
              onChange={(e) => set('detailsText', e.target.value)} placeholder={tx('Plot 1 Kampala Road\nTel: +256 700 000000\nTIN: 1000000000')} />
          </Field>
          <div className="grid gap-2 sm:grid-cols-3">
            <Check label={tx('Company logo')} checked={layout.showLogo} onChange={(v) => set('showLogo', v)} />
            <Check label={tx('Lines under the name')} checked={layout.showCompanyDetails} onChange={(v) => set('showCompanyDetails', v)} />
            <Check label={tx('Received by / signature line')} checked={layout.showSignature} onChange={(v) => set('showSignature', v)} />
          </div>
          <Field label={tx('Footer note')} hint={tx('Leave empty for no note.')}>
            <Textarea rows={3} maxLength={500} value={layout.footerText} onChange={(e) => set('footerText', e.target.value)} />
          </Field>
          <p className="text-xs text-muted-foreground">
            {tx('The logo and the default name and details come from the company settings.')}{' '}
            <Link to="/settings/developer" className="underline">{tx('Company settings')}</Link>
          </p>
        </div>
        <div>
          {preview
            ? <iframe title={tx('Preview')} src={preview} className="h-[640px] w-full rounded border bg-white" />
            : <div className="flex h-full min-h-48 items-center justify-center rounded border border-dashed p-6 text-center text-sm text-muted-foreground">
                {tx('Click Preview to see the latest deposit receipt with these settings (nothing is saved).')}
              </div>}
        </div>
      </div>
    </section>
  );
}

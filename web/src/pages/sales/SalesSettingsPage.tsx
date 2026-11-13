import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Settings2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { useSalesSettings } from '@/features/sales/api';
import { Field } from '@/pages/crm/crm-ui';

type Kind = 'bool' | 'num' | 'text' | string[];
const GROUPS: Array<[string, Array<[string, string, Kind]>]> = [
  ['Order confirmation', [
    ['stockCheckPolicy', 'When stock is short', ['none', 'warn', 'block']],
    ['allowAlternativeWarehouse', 'Allow picking another warehouse', 'bool'],
    ['orderApprovalAlways', 'Every order needs approval', 'bool'],
    ['orderApprovalAmount', 'Approval above this amount (0 = off)', 'num'],
    ['orderMaxDiscountPercent', 'Approval above this discount %', 'num'],
    ['marginCheck', 'Check margin floors', 'bool'],
    ['notifyOnHold', 'Alert the owner when an order is held', 'bool'],
    ['notifyOnShortage', 'Alert the owner on a stock shortage', 'bool'],
  ]],
  ['Quotations', [
    ['quoteValidityDays', 'Validity (days)', 'num'],
    ['quoteMaxDiscountPercent', 'Approval above this discount %', 'num'],
    ['quoteApprovalAmount', 'Approval above this amount (0 = off)', 'num'],
    ['defaultQuoteTerms', 'Default terms', 'text'],
    ['quoteLineImages', 'Product images on quotations (optional Image column, print and PDF)', 'bool'],
  ]],
  ['Proforma invoices', [
    ['proformaAdvancePercent', 'Default advance %', 'num'],
    ['proformaBankDetails', 'Bank details', 'text'],
    ['proformaMobileDetails', 'Mobile money details', 'text'],
  ]],
  ['Delivery and invoicing', [
    ['deliveryPostingApproval', 'Deliveries need approval before stock is issued', 'bool'],
    ['requireLineTax', 'Every product line needs a tax before invoicing', 'bool'],
    ['requireTaxNumberAbove', 'Customer tax number required above this amount (0 = off)', 'num'],
    ['numberingScope', 'Order numbers run per', ['organization', 'branch']],
    ['salesReturnsAccountId', 'Sales Returns account ID (empty = reverse revenue)', 'text'],
    ['badDebtAccountId', 'Bad debt account ID, for write-offs', 'text'],
  ]],
  ['Credit', [
    ['overLimitPolicy', 'Order over the credit limit', ['flag', 'approve', 'block']],
    ['cashBypassesCredit', 'Cash orders skip the credit check', 'bool'],
    ['commissionDefaultBasis', 'New commission rules pay on', ['paid', 'invoiced']],
  ]],
];

/** Sales policies of this organization. */
export function SalesSettingsPage() {
  const { data } = useSalesSettings();
  const [form, setForm] = useState<Record<string, unknown>>({});
  const qc = useQueryClient();
  useEffect(() => { if (data) setForm(data); }, [data]);
  const save = useMutation({
    mutationFn: async () => {
      const body: Record<string, unknown> = {};
      GROUPS.forEach(([, fs]) => fs.forEach(([k]) => { if (k in form) body[k] = form[k]; }));
      return (await api.put('/sales/settings', body)).data;
    },
    onSuccess: () => { toast.success(tx('Saved')); qc.invalidateQueries({ queryKey: ['sales', 'settings'] }); },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
  });
  const set = (k: string, v: unknown) => setForm((f) => ({ ...f, [k]: v }));
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Settings2} title={tx('Sales Settings')} description={tx('Policies that apply to every sales document in this organization.')} actions={<Button disabled={save.isPending} onClick={() => save.mutate()}>{tx('Save')}</Button>} />
      {GROUPS.filter(([, fs]) => fs.some(([k]) => k in form)).map(([title, fields]) => (
        <section key={title} className="rounded-xl border bg-card p-5 shadow-sm">
          <h2 className="mb-3 font-semibold">{tx(title)}</h2>
          <div className="grid gap-4 sm:grid-cols-2">
            {fields.filter(([k]) => k in form).map(([k, label, kind]) => (
              <Field key={k} label={tx(label)}>
                {kind === 'bool' ? <input type="checkbox" className="mt-2 h-4 w-4" checked={form[k] === true} onChange={(e) => set(k, e.target.checked)} />
                  : kind === 'num' ? <Input type="number" min={0} step="any" value={String(form[k] ?? 0)} onChange={(e) => set(k, Number(e.target.value))} />
                    : kind === 'text' ? <Textarea rows={2} value={String(form[k] ?? '')} onChange={(e) => set(k, e.target.value)} />
                      : <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={String(form[k] ?? '')} onChange={(e) => set(k, e.target.value)}>{(kind as string[]).map((o) => <option key={o} value={o}>{tx(o)}</option>)}</select>}
              </Field>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

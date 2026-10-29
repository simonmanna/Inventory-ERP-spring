import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import {
  PRIORITY_LABEL, toNum, useCreateDeliveryOrder, useCustomerSites, useDispatchLookups, type ProductOption,
} from '@/features/fleet/dispatch-api';
import { Field, OptionalSelect } from '../fleet-ui';
import { num, toOptions } from '../fleet-format';
import { CustomerPicker, ProductPicker } from './dispatch-ui';
import { todayIso } from './dispatch-format';

interface LineDraft { key: number; product: ProductOption | null; quantity: string; unitPrice: string; batchNumber: string }

const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/;

/** Manual delivery order (fleet 1.3): customer, site, yard, requested date + window, product lines. */
export function DeliveryOrderFormPage() {
  const navigate = useNavigate();
  const lookups = useDispatchLookups();
  const create = useCreateDeliveryOrder();
  const [partner, setPartner] = useState<{ id: string; name: string }>({ id: '', name: '' });
  const [siteId, setSiteId] = useState('');
  const [locationId, setLocationId] = useState('');
  const [requestedDate, setRequestedDate] = useState(todayIso());
  const [windowFrom, setWindowFrom] = useState('');
  const [windowTo, setWindowTo] = useState('');
  const [priority, setPriority] = useState('normal');
  const [contactName, setContactName] = useState('');
  const [contactPhone, setContactPhone] = useState('');
  const [notes, setNotes] = useState('');
  const [lines, setLines] = useState<LineDraft[]>([{ key: 1, product: null, quantity: '', unitPrice: '', batchNumber: '' }]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const sites = useCustomerSites({ page: 1, pageSize: 200, partnerId: partner.id || undefined }, !!partner.id);

  const setLine = (key: number, patch: Partial<LineDraft>) => setLines((ls) => ls.map((l) => (l.key === key ? { ...l, ...patch } : l)));

  const validate = () => {
    const e: Record<string, string> = {};
    if (!partner.id) e.partner = tx('Choose the customer');
    if (!requestedDate) e.date = tx('Enter the requested date');
    if (windowFrom && !HHMM.test(windowFrom)) e.window = tx('Use a time like 08:00');
    if (windowTo && !HHMM.test(windowTo)) e.window = tx('Use a time like 08:00');
    if (windowFrom && windowTo && windowTo <= windowFrom) e.window = tx('The delivery window must end after it starts');
    lines.forEach((l) => {
      if (!l.product) e[`p${l.key}`] = tx('Choose the product');
      if (!(Number(l.quantity) > 0)) e[`q${l.key}`] = tx('Quantity must be more than zero');
    });
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const submit = async () => {
    if (!validate()) return;
    try {
      const out = await create.mutateAsync({
        partnerId: partner.id, siteId: siteId || undefined, locationId: locationId || undefined, requestedDate,
        windowFrom: windowFrom || undefined, windowTo: windowTo || undefined, priority,
        contactName: contactName || undefined, contactPhone: contactPhone || undefined, notes: notes || undefined,
        lines: lines.map((l) => ({
          productId: l.product!.id, quantity: toNum(l.quantity), unitPrice: toNum(l.unitPrice),
          batchNumber: l.batchNumber || undefined,
        })),
      });
      notify.success(tx('Delivery order {{code}} created', { code: out.code }));
      navigate(`/fleet/delivery-orders/${out.id}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not create the delivery order')));
    }
  };

  return (
    <div className="mx-auto max-w-4xl space-y-4">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/fleet/delivery-orders')}><ArrowLeft className="h-5 w-5" /></Button>
        <h1 className="text-2xl font-bold">{tx('New delivery order')}</h1>
      </div>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Customer and site')}</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Customer')} error={errors.partner}>
            <CustomerPicker value={partner.id} label={partner.name} onPick={(id, name) => { setPartner({ id, name }); setSiteId(''); }} />
          </Field>
          <Field label={tx('Customer site')}>
            <OptionalSelect value={siteId} onChange={setSiteId} disabled={!partner.id} noneLabel={tx('No site')}
              options={(sites.data?.data ?? []).map((s) => ({ value: s.id, label: `${s.code} · ${s.name}` }))} placeholder={tx('No site')} />
          </Field>
          <Field label={tx('Yard / warehouse')}>
            <OptionalSelect value={locationId} onChange={setLocationId} noneLabel={tx('Default')}
              options={(lookups.data?.locations ?? []).map((l) => ({ value: l.id, label: l.name }))} placeholder={tx('Default')} />
          </Field>
          <Field label={tx('Priority')}>
            <OptionalSelect value={priority} onChange={(v) => setPriority(v || 'normal')} options={toOptions(PRIORITY_LABEL)} placeholder={tx('Normal')} />
          </Field>
          <Field label={tx('Requested date')} error={errors.date}>
            <Input type="date" value={requestedDate} onChange={(e) => setRequestedDate(e.target.value)} />
          </Field>
          <Field label={tx('Delivery window')} error={errors.window}>
            <div className="flex items-center gap-2">
              <Input type="time" value={windowFrom} onChange={(e) => setWindowFrom(e.target.value)} />
              <span>–</span>
              <Input type="time" value={windowTo} onChange={(e) => setWindowTo(e.target.value)} />
            </div>
          </Field>
          <Field label={tx('Contact name')}><Input value={contactName} onChange={(e) => setContactName(e.target.value)} /></Field>
          <Field label={tx('Contact phone')}><Input value={contactPhone} onChange={(e) => setContactPhone(e.target.value)} /></Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">{tx('Products')}</CardTitle>
          <Button size="sm" variant="outline" onClick={() => setLines((ls) => [...ls, { key: Math.max(...ls.map((l) => l.key)) + 1, product: null, quantity: '', unitPrice: '', batchNumber: '' }])}>
            <Plus className="mr-1.5 h-4 w-4" /> {tx('Add line')}
          </Button>
        </CardHeader>
        <CardContent className="space-y-3">
          {lines.map((l) => (
            <div key={l.key} className="grid grid-cols-1 gap-3 rounded-lg border p-3 sm:grid-cols-12">
              <Field label={tx('Product')} error={errors[`p${l.key}`]} className="sm:col-span-5">
                <ProductPicker value={l.product?.id ?? ''} label={l.product?.name} onPick={(p) => setLine(l.key, { product: p, unitPrice: p?.salesPrice ?? '' })} />
                {l.product && (
                  <p className="text-xs text-muted-foreground">
                    {l.product.uomCode ?? ''}{l.product.tonnesPerUnit ? ` · ${num(l.product.tonnesPerUnit, 3)} t/${tx('unit')}` : ` · ${tx('no weight data')}`}
                  </p>
                )}
              </Field>
              <Field label={tx('Quantity')} error={errors[`q${l.key}`]} className="sm:col-span-2">
                <Input type="number" min={0} step="0.001" value={l.quantity} onChange={(e) => setLine(l.key, { quantity: e.target.value })} />
              </Field>
              <Field label={tx('Unit price')} className="sm:col-span-2">
                <Input type="number" min={0} step="0.01" value={l.unitPrice} onChange={(e) => setLine(l.key, { unitPrice: e.target.value })} />
              </Field>
              <Field label={tx('Batch')} className="sm:col-span-2">
                <Input value={l.batchNumber} onChange={(e) => setLine(l.key, { batchNumber: e.target.value })} />
              </Field>
              <div className="flex items-end sm:col-span-1">
                <Button variant="ghost" size="icon" aria-label={tx('Remove line')} disabled={lines.length === 1} onClick={() => setLines((ls) => ls.filter((x) => x.key !== l.key))}>
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            </div>
          ))}
        </CardContent>
      </Card>
      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={() => navigate('/fleet/delivery-orders')}>{tx('Cancel')}</Button>
        <Button onClick={submit} disabled={create.isPending}>{tx('Create delivery order')}</Button>
      </div>
    </div>
  );
}

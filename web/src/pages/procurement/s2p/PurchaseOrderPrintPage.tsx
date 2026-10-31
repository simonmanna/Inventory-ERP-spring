import { useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { usePoLifecycle } from '@/features/procurement/api';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

/** Printable purchase order (send to the supplier as PDF via the browser's print dialog). */
export function PurchaseOrderPrintPage() {
  const { id } = useParams();
  const { data: po } = usePoLifecycle(id);
  const company = useQuery({ queryKey: ['company-settings'], queryFn: async () => (await api.get('/settings/company')).data, retry: false });
  useEffect(() => {
    if (po) setTimeout(() => window.print(), 300);
  }, [po]);
  if (!po) return <div className="p-6 text-sm">{tx('Loading…')}</div>;
  const c: any = company.data ?? {};
  return (
    <div className="mx-auto max-w-3xl bg-white p-8 text-sm text-black print:p-0">
      <div className="flex items-start justify-between border-b pb-4">
        <div>
          <div className="text-xl font-bold">{c.name ?? c.companyName ?? ''}</div>
          <div className="whitespace-pre-line text-xs">{[c.address, c.phone, c.email, c.taxNumber ? `${tx('TIN')}: ${c.taxNumber}` : ''].filter(Boolean).join('\n')}</div>
        </div>
        <div className="text-right">
          <div className="text-2xl font-bold uppercase">{tx('Purchase order')}</div>
          <div className="font-mono">{po.orderNumber}</div>
          <div>{tx('Date')}: {date(po.orderDate)}</div>
          {(po.promisedDate || po.expectedDeliveryDate) && <div>{tx('Delivery by')}: {date(po.promisedDate ?? po.expectedDeliveryDate!)}</div>}
        </div>
      </div>
      <div className="py-4">
        <div className="text-xs uppercase text-gray-500">{tx('Supplier')}</div>
        <div className="font-semibold">{po.partnerName}</div>
      </div>
      <table className="w-full border-collapse">
        <thead>
          <tr className="border-b text-left text-xs uppercase text-gray-500">
            <th className="py-2">#</th><th>{tx('Description')}</th><th className="text-right">{tx('Qty')}</th>
            <th className="text-right">{tx('Unit price')}</th><th className="text-right">{tx('Amount')}</th>
          </tr>
        </thead>
        <tbody>
          {po.lines.map((l, i) => (
            <tr key={l.id} className="border-b">
              <td className="py-2">{i + 1}</td><td>{l.description}</td><td className="text-right">{Number(l.quantity)}</td>
              <td className="text-right">{money(Number(l.unitPrice), po.currencyCode)}</td>
              <td className="text-right">{money(Number(l.quantity) * Number(l.unitPrice), po.currencyCode)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="mt-4 text-right text-base font-semibold">{tx('Total')}: {money(Number(po.totalAmount), po.currencyCode)}</div>
      <div className="mt-12 grid grid-cols-2 gap-8 text-xs">
        <div className="border-t pt-2">{tx('Authorised by')}</div>
        <div className="border-t pt-2">{tx('Supplier acceptance')}</div>
      </div>
    </div>
  );
}

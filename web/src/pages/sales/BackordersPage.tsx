import { useNavigate } from 'react-router-dom';
import { PackageX } from 'lucide-react';
import { ListPageHeader } from '@/components/list';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useBackorders } from '@/features/sales/api';

/** Open order lines not fully reserved, by priority (ADR-S011 S4). */
export function BackordersPage() {
  const { data } = useBackorders();
  const navigate = useNavigate();
  return (
    <div className="space-y-4">
      <ListPageHeader icon={PackageX} title={tx('Backorders')} description={tx('Confirmed order quantities without reserved stock, with the next planned supply.')} />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[820px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr>
            <th className="px-3 py-2">{tx('Order')}</th><th className="px-3 py-2">{tx('Customer')}</th><th className="px-3 py-2">{tx('Line')}</th>
            <th className="px-3 py-2 text-right">{tx('Open')}</th><th className="px-3 py-2 text-right">{tx('Reserved')}</th><th className="px-3 py-2 text-right">{tx('Backorder')}</th>
            <th className="px-3 py-2">{tx('Promised')}</th><th className="px-3 py-2">{tx('Next supply')}</th></tr></thead>
          <tbody>
            {(data ?? []).map((b) => (
              <tr key={b.lineId} className="cursor-pointer border-t hover:bg-muted/30" onClick={() => navigate(`/sales/orders/${b.orderId}`)}>
                <td className="px-3 py-2 font-medium">{b.number}{b.priority > 0 && <span className="ml-1 text-xs text-amber-700">P{b.priority}</span>}</td>
                <td className="px-3 py-2">{b.partnerName}</td><td className="px-3 py-2">{b.description}</td>
                <td className="px-3 py-2 text-right tabular-nums">{Number(b.open)}</td><td className="px-3 py-2 text-right tabular-nums">{Number(b.reserved)}</td>
                <td className="px-3 py-2 text-right font-medium tabular-nums">{Number(b.backorder)}</td>
                <td className={b.promiseAtRisk ? 'px-3 py-2 text-rose-600' : 'px-3 py-2'}>{b.promisedDate ? date(b.promisedDate) : '—'}</td>
                <td className="px-3 py-2">{b.nextSupply ? date(b.nextSupply) : '—'}</td>
              </tr>
            ))}
            {data && data.length === 0 && <tr><td colSpan={8} className="p-6 text-center text-muted-foreground">{tx('No backorders.')}</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}

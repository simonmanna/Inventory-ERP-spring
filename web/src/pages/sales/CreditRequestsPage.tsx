import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ShieldQuestion } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { ListPageHeader } from '@/components/list';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';

interface Req { id: string; orderId: string; orderNumber: string; partnerName: string | null; amount: number; exposureAtRequest: number | null; limitAtRequest: number | null; exposureAtDecision: number | null; reason: string; status: string; requestedByName: string | null; decidedByName: string | null; decidedAt: string | null; decisionComment: string | null; createdAt: string }
const th = 'px-3 py-2';

/** Orders asking for credit beyond the limit: approve or reject with a reason. */
export function CreditRequestsPage() {
  const [status, setStatus] = useState('pending');
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['sales', 'credit-requests', status], queryFn: async () => (await api.get<Req[]>('/sales/credit-requests', { params: { status: status || undefined } })).data });
  const canDecide = useAuthStore((s) => s.hasPermission(PERMISSIONS.credit.override));
  const decide = (r: Req, action: 'approve' | 'reject') => {
    const comment = prompt(action === 'approve' ? tx('Why is this approved?') : tx('Why is this rejected?'));
    if (comment) api.post(`/sales/credit-requests/${r.id}/${action}`, { comment }).then(() => { toast.success(tx('Recorded')); void qc.invalidateQueries({ queryKey: ['sales'] }); }).catch((e) => toast.error(apiErrorMessage(e, tx('Action failed'))));
  };
  return (
    <div className="space-y-4">
      <ListPageHeader icon={ShieldQuestion} title={tx('Credit Requests')} description={tx('Orders over the credit limit, waiting for a decision. Who decided and why is kept.')} />
      <select className="h-9 rounded-md border bg-background px-2 text-sm" value={status} onChange={(e) => setStatus(e.target.value)}><option value="pending">{tx('Waiting')}</option><option value="approved">{tx('Approved')}</option><option value="rejected">{tx('Rejected')}</option><option value="">{tx('All')}</option></select>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <table className="w-full min-w-[900px] text-sm">
          <thead className="bg-muted/40 text-left text-xs font-semibold uppercase text-muted-foreground"><tr><th className={th}>{tx('Order')}</th><th className={th}>{tx('Customer')}</th><th className={`${th} text-right`}>{tx('Amount')}</th><th className={`${th} text-right`}>{tx('Exposure')}</th><th className={`${th} text-right`}>{tx('Limit')}</th><th className={th}>{tx('Reason')}</th><th className={th}>{tx('Decision')}</th><th /></tr></thead>
          <tbody>
            {(data ?? []).map((r) => (
              <tr key={r.id} className="border-t align-top"><td className={th}><Link className="text-primary hover:underline" to={`/sales/orders/${r.orderId}`}>{r.orderNumber}</Link></td><td className={th}>{r.partnerName}</td><td className={`${th} text-right tabular-nums`}>{money(r.amount)}</td><td className={`${th} text-right tabular-nums`}>{money(r.exposureAtRequest ?? 0)}</td><td className={`${th} text-right tabular-nums`}>{money(r.limitAtRequest ?? 0)}</td>
                <td className={th}>{r.reason}<span className="block text-xs text-muted-foreground">{r.requestedByName} · {date(r.createdAt)}</span></td>
                <td className={th}>{tx(r.status)}{r.decidedAt && <span className="block text-xs text-muted-foreground">{r.decidedByName} · {date(r.decidedAt)}: {r.decisionComment}</span>}</td>
                <td className={`${th} whitespace-nowrap`}>{r.status === 'pending' && canDecide && <><Button size="sm" onClick={() => decide(r, 'approve')}>{tx('Approve')}</Button> <Button size="sm" variant="outline" onClick={() => decide(r, 'reject')}>{tx('Reject')}</Button></>}</td></tr>
            ))}
            {data && !data.length && <tr><td colSpan={8} className="p-6 text-center text-muted-foreground">{tx('Nothing waiting.')}</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}

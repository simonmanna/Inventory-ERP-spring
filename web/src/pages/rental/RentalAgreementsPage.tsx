import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { CalendarDays, Plus } from 'lucide-react';
import { useDepositClassificationReview, useRentalAgreements } from '@/features/rental/api';
import { PERMISSIONS } from '@erp/shared';
import { useAuthStore } from '@/stores/auth.store';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const fmt = (n: number | string) => `Rp ${Number(n || 0).toLocaleString('id-ID')}`;

const STATUS_FILTERS = [
  { value: '', label: tx('All') },
  { value: 'draft', label: tx('Draft') },
  { value: 'pending_approval', label: tx('Pending approval') },
  { value: 'confirmed', label: tx('Confirmed') },
  { value: 'checked_out', label: tx('Checked out') },
  { value: 'overdue', label: tx('Overdue') },
  { value: 'closed', label: tx('Closed') },
  { value: 'cancelled', label: tx('Cancelled') },
];

const STATUS_STYLES: Record<string, string> = {
  draft: 'bg-muted text-muted-foreground',
  pending_approval: 'bg-amber-100 text-amber-800',
  confirmed: 'bg-blue-100 text-blue-800',
  checked_out: 'bg-emerald-100 text-emerald-800',
  overdue: 'bg-red-100 text-red-800',
  closed: 'bg-muted text-muted-foreground',
  cancelled: 'bg-muted text-muted-foreground',
};

export function RentalAgreementsPage() {
  // A dashboard link can open this list already filtered.
  const [params] = useSearchParams();
  const [status, setStatus] = useState(params.get('status') ?? '');
  const [search, setSearch] = useState(params.get('search') ?? '');
  const { data } = useRentalAgreements({ status: status || undefined, search: search || undefined, pageSize: 50 });

  const rows = useMemo(() => data?.items ?? [], [data]);
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.rental.manage));
  const { data: toClassify } = useDepositClassificationReview(canManage);

  return (
    <div className="space-y-4 p-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">{tx('Rental Agreements')}</h1>
          <p className="text-sm text-muted-foreground">{tx('Quote → hold → agreement → checkout → return lifecycle.')}</p>
        </div>
        <Link to="/rental/agreements/new">
          <Button><Plus className="h-4 w-4" /> {tx('New agreement')}</Button>
        </Link>
      </div>

      {canManage && (toClassify?.length ?? 0) > 0 && (
        <Card>
          <CardHeader className="bg-amber-50 border-b rounded-t-lg">
            <CardTitle className="text-sm">{tx('Deposits to classify')} ({toClassify!.length})</CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            <p className="px-4 pt-3 text-xs text-muted-foreground">
              {tx('Older deposits are not yet marked as a refundable security deposit or an advance (prepaid rent). Open the agreement and classify its deposit.')}
            </p>
            <table className="w-full text-sm">
              <thead className="text-left text-xs uppercase text-muted-foreground">
                <tr><th className="px-4 py-2">{tx('Agreement')}</th><th className="px-4 py-2 text-right">{tx('Collected')}</th><th className="px-4 py-2 text-right">{tx('Refunded')}</th><th className="px-4 py-2">{tx('Created')}</th><th /></tr>
              </thead>
              <tbody>
                {toClassify!.map((d) => (
                  <tr key={d.id} className="border-t">
                    <td className="px-4 py-2 font-mono text-xs">{d.agreementId.slice(0, 8)}…</td>
                    <td className="px-4 py-2 text-right">{fmt(d.totalCollected ?? 0)}</td>
                    <td className="px-4 py-2 text-right">{fmt(d.totalRefunded ?? 0)}</td>
                    <td className="px-4 py-2">{d.createdAt ? date(d.createdAt) : '—'}</td>
                    <td className="px-4 py-2 text-right">
                      <Link to={`/rental/agreements/${d.agreementId}`} className="text-primary hover:underline">{tx('Open')}</Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>
      )}

      <div className="flex items-center gap-2">
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder={tx('Search agreement number…')}
          className="w-64 rounded-md border bg-card px-3 py-2 text-sm"
        />
        <select value={status} onChange={(e) => setStatus(e.target.value)} className="rounded-md border bg-card px-3 py-2 text-sm">
          {STATUS_FILTERS.map((f) => (
            <option key={f.value} value={f.value}>{f.label}</option>
          ))}
        </select>
      </div>

      <Card>
        <CardHeader className="bg-muted/30 border-b rounded-t-lg">
          <CardTitle className="text-sm">{rows.length} {tx('agreements')}</CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <div className="divide-y">
            {rows.length === 0 && <p className="p-6 text-sm text-muted-foreground">{tx('No agreements match the filters.')}</p>}
            {rows.map((a) => (
              <Link key={a.id} to={`/rental/agreements/${a.id}`} className="flex items-center justify-between px-4 py-3 hover:bg-muted/40">
                <div className="flex items-center gap-3">
                  <CalendarDays className="h-4 w-4 text-muted-foreground" />
                  <div>
                    <p className="text-sm font-medium">{a.agreementNumber}</p>
                    <p className="text-xs text-muted-foreground">
                      {a.startAt ? date(a.startAt) : '—'} → {a.dueAt ? date(a.dueAt) : '—'}
                    </p>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <span className="text-sm font-medium">{fmt(a.lines?.reduce((s, l) => s + Number(l.lineTotal), 0) ?? 0)}</span>
                  <Badge variant="outline" className={STATUS_STYLES[a.status] ?? ''}>{a.status}</Badge>
                </div>
              </Link>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

import type { ReactNode } from 'react';
import { RefreshCw } from 'lucide-react';
import { StatusPill, type Tone } from '@/components/list';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { tx } from '@/lib/i18n/tx';

/** Shared bits of the customer portal: states, status pills, pager. */

const TRIP_LABEL: Record<string, string> = {
  planned: tx('Planned'),
  loading: tx('Loading'),
  weighed: tx('Weighed'),
  dispatched: tx('Dispatched'),
  in_transit: tx('On the way'),
  delivered: tx('Delivered'),
  closed: tx('Delivered'),
  cancelled: tx('Cancelled'),
  returned: tx('Returned'),
};

const TRIP_TONE: Record<string, Tone> = {
  dispatched: 'info',
  in_transit: 'info',
  delivered: 'success',
  closed: 'success',
  cancelled: 'neutral',
  returned: 'warning',
};

export function TripStatus({ status }: { status: string }) {
  return <StatusPill tone={TRIP_TONE[status] ?? 'neutral'}>{TRIP_LABEL[status] ?? status}</StatusPill>;
}

export function InvoiceStatus({ residual }: { residual: string }) {
  return Number(residual) > 0
    ? <StatusPill tone="warning">{tx('Balance due')}</StatusPill>
    : <StatusPill tone="success">{tx('Paid')}</StatusPill>;
}

export function Loading() {
  return <div className="space-y-3"><Skeleton className="h-20 w-full" /><Skeleton className="h-20 w-full" /></div>;
}

export function LoadError({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="p-8 text-center text-muted-foreground">
      <p>{tx('Could not load this page.')}</p>
      <Button variant="link" onClick={onRetry}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="rounded-xl border bg-card p-6 text-center text-muted-foreground">{children}</div>;
}

export function Pager({ page, totalPages, onPage }: { page: number; totalPages: number; onPage: (p: number) => void }) {
  if (totalPages <= 1) return null;
  return (
    <div className="flex items-center justify-between">
      <Button variant="outline" disabled={page <= 1} onClick={() => onPage(page - 1)}>{tx('Previous')}</Button>
      <span className="text-sm text-muted-foreground">{tx('Page {{page}} of {{total}}', { page, total: totalPages })}</span>
      <Button variant="outline" disabled={page >= totalPages} onClick={() => onPage(page + 1)}>{tx('Next')}</Button>
    </div>
  );
}

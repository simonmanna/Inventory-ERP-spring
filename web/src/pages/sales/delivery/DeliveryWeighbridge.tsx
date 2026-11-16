import { Link } from 'react-router-dom';
import { FileText, Scale } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { StatusPill, type Tone } from '@/components/list';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import type { LinkedWeighTicket, WeighVerification } from '@/features/fleet/dispatch-api';
import { openWeighTicketCopy, type SalesDelivery } from '@/features/sales/delivery-api';
import { kg } from '../../fleet/weighbridge/weighbridge-format';
import { ManualFlag, TicketStatusPill } from '../../fleet/weighbridge/weighbridge-ui';

/**
 * Weighbridge evidence of a Sales delivery (Phase A1): the Fleet weighbridge tickets of the truck, read-only. Fleet stays
 * the source of truth — nothing here weighs, edits or voids. The PDF is a view copy (not counted as a print); the link to
 * the weighing screen only shows for users with weighbridge:read.
 */

const VERIFICATION: Record<WeighVerification, { label: string; tone: Tone }> = {
  verified: { label: tx('Verified'), tone: 'success' },
  approved_exception: { label: tx('Approved exception'), tone: 'warning' },
  pending_exception: { label: tx('Exception pending'), tone: 'danger' },
  in_progress: { label: tx('Weighing in progress'), tone: 'info' },
  cancelled: { label: tx('Cancelled'), tone: 'neutral' },
  voided: { label: tx('Voided'), tone: 'neutral' },
};

export function VerificationPill({ value }: { value: WeighVerification | null | undefined }) {
  if (!value) return null;
  const v = VERIFICATION[value];
  return <StatusPill tone={v?.tone ?? 'neutral'}>{v?.label ?? value}</StatusPill>;
}

const SOURCE: Record<string, string> = { device: tx('Indicator'), manual: tx('Manual'), stored_tare: tx('Stored tare') };

/** One-line summary for the details tab: ticket number, net weight and verification. */
export function WeighSummary({ d }: { d: Pick<SalesDelivery, 'weighTicketNumber' | 'weighNetKg' | 'weighVerification' | 'weighbridge'> }) {
  if (!d.weighTicketNumber && !d.weighbridge?.required) return null;
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-xl border px-4 py-2.5 text-sm">
      <Scale className="h-4 w-4 text-primary" />
      {d.weighTicketNumber ? (
        <>
          <span className="font-medium">{tx('Weighbridge ticket')} {d.weighTicketNumber}</span>
          <span className="tabular-nums text-muted-foreground">{tx('Net')} {kg(d.weighNetKg)}</span>
          <VerificationPill value={d.weighVerification} />
        </>
      ) : (
        <span className="text-amber-700">{tx('Weighbridge required for dispatch — no completed ticket yet.')}</span>
      )}
    </div>
  );
}

function Weighing({ label, w, at, source, by }: { label: string; w?: string | null; at?: string | null; source?: string | null; by?: string | null }) {
  return (
    <div className="space-y-0.5">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="font-medium tabular-nums">{kg(w)}</div>
      <div className="text-xs text-muted-foreground">
        {[at ? dateTime(at) : null, source ? SOURCE[source] ?? source : null, by || null].filter(Boolean).join(' · ') || '—'}
      </div>
    </div>
  );
}

function TicketCard({ deliveryId, t, all, canOpen }: { deliveryId: string; t: LinkedWeighTicket; all: LinkedWeighTicket[]; canOpen: boolean }) {
  const number = (id?: string | null) => all.find((x) => x.id === id)?.ticketNumber ?? null;
  const printable = t.status === 'completed' || t.status === 'void';
  return (
    <div className={cn('space-y-3 rounded-xl border p-4 text-sm', t.feedsTrip && 'border-emerald-300 dark:border-emerald-800')}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          {canOpen
            ? <Link className="font-medium text-primary hover:underline" to={`/fleet/weighbridge/tickets/${t.id}`}>{t.ticketNumber}</Link>
            : <span className="font-medium">{t.ticketNumber}</span>}
          <TicketStatusPill status={t.status} />
          <VerificationPill value={t.verification} />
          {t.isManual && <ManualFlag />}
          {t.usedStoredTare && <StatusPill tone="info" dot={false}>{tx('Stored tare')}</StatusPill>}
          {t.feedsTrip && <StatusPill tone="success" dot={false}>{tx('Loaded quantity source')}</StatusPill>}
        </div>
        {printable && (
          <Button size="sm" variant="outline" onClick={() => openWeighTicketCopy(deliveryId, t.id)}>
            <FileText className="mr-1.5 h-4 w-4" />{tx('View PDF copy')}
          </Button>
        )}
      </div>
      <div className="text-muted-foreground">
        {[t.registrationPlate, t.trailerPlate ? `${tx('Trailer')} ${t.trailerPlate}` : null, t.driverName].filter(Boolean).join(' · ')}
      </div>
      <div className="grid gap-3 sm:grid-cols-3">
        <Weighing label={tx('First weighing')} w={t.firstWeightKg} at={t.firstWeighedAt} source={t.firstSource} by={t.firstOperator} />
        <Weighing label={tx('Second weighing')} w={t.secondWeightKg} at={t.secondWeighedAt} source={t.secondSource} by={t.secondOperator} />
        <div className="space-y-0.5">
          <div className="text-xs text-muted-foreground">{tx('Gross · Tare · Net')}</div>
          <div className="tabular-nums">{kg(t.grossKg)} · {kg(t.tareKg)} · <span className="font-semibold">{kg(t.netKg)}</span></div>
          <div className={cn('text-xs', t.withinTolerance === false ? 'text-amber-700' : 'text-muted-foreground')}>
            {t.expectedKg ? tx('Expected {{expected}} · variance {{variance}}', { expected: kg(t.expectedKg), variance: kg(t.varianceKg) }) : '—'}
          </div>
        </div>
      </div>
      {(t.exceptionStatus || t.voidReason || t.reissuedFromId || t.replacedById) && (
        <div className="space-y-0.5 text-xs text-muted-foreground">
          {t.exceptionStatus && <div>{tx('Tolerance exception: {{status}}', { status: t.exceptionStatus })}</div>}
          {t.voidReason && <div>{tx('Voided: {{reason}}', { reason: t.voidReason })}{t.voidedAt ? ` (${dateTime(t.voidedAt)})` : ''}</div>}
          {t.replacedById && <div>{tx('Replaced by {{n}}', { n: number(t.replacedById) ?? '—' })}</div>}
          {t.reissuedFromId && <div>{tx('Reissued from {{n}}', { n: number(t.reissuedFromId) ?? '—' })}</div>}
        </div>
      )}
    </div>
  );
}

/** The Weighbridge tab of the delivery dialog. */
export function DeliveryWeighbridgeTab({ d }: { d: Pick<SalesDelivery, 'id' | 'weighbridgeTickets' | 'weighbridge'> }) {
  const canOpen = useAuthStore((s) => s.hasPermission(PERMISSIONS.weighbridge.read));
  const tickets = d.weighbridgeTickets ?? [];
  return (
    <div className="space-y-3">
      {d.weighbridge?.required && !d.weighbridge.feedingTicketId && (
        <p className="text-sm text-amber-700">{tx('Weighbridge required for dispatch — no completed ticket yet.')}</p>
      )}
      {tickets.length === 0
        ? <p className="text-sm text-muted-foreground">{tx('No weighbridge ticket for this delivery.')}</p>
        : tickets.map((t) => <TicketCard key={t.id} deliveryId={d.id} t={t} all={tickets} canOpen={canOpen} />)}
    </div>
  );
}

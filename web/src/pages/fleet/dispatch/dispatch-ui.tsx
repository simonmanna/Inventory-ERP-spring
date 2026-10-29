import { useState, type ReactNode } from 'react';
import { StatusPill, type Tone } from '@/components/list';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { tx } from '@/lib/i18n/tx';
import {
  DISCREPANCY_STATUS_LABEL, DO_STATUS_LABEL, TRIP_STATUS_LABEL, useCustomerPicker, useProductPicker,
  type DeliveryOrderStatus, type DiscrepancyStatus, type ProductOption, type TripStatus,
} from '@/features/fleet/dispatch-api';
import { Field, OptionalSelect, RemotePicker } from '../fleet-ui';

/** Shared bits of the dispatch screens (fleet 1.3): status pills, pickers, a reason prompt. */

const DO_TONE: Record<DeliveryOrderStatus, Tone> = {
  open: 'info', planned: 'warning', in_progress: 'warning', delivered: 'success', closed: 'neutral', cancelled: 'neutral',
};

export function DeliveryOrderStatusPill({ status }: { status: DeliveryOrderStatus }) {
  return <StatusPill tone={DO_TONE[status] ?? 'neutral'}>{DO_STATUS_LABEL[status] ?? status}</StatusPill>;
}

const TRIP_TONE: Record<TripStatus, Tone> = {
  planned: 'info', loading: 'warning', weighed: 'warning', dispatched: 'warning', in_transit: 'warning',
  delivered: 'success', closed: 'neutral', cancelled: 'neutral', returned: 'danger',
};

export function TripStatusPill({ status }: { status: TripStatus }) {
  return <StatusPill tone={TRIP_TONE[status] ?? 'neutral'}>{TRIP_STATUS_LABEL[status] ?? status}</StatusPill>;
}

const DISC_TONE: Record<DiscrepancyStatus, Tone> = { pending: 'warning', approved: 'success', rejected: 'neutral' };

export function DiscrepancyStatusPill({ status }: { status: DiscrepancyStatus }) {
  return <StatusPill tone={DISC_TONE[status] ?? 'neutral'}>{DISCREPANCY_STATUS_LABEL[status] ?? status}</StatusPill>;
}

export function Item({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-0.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm font-medium">{children ?? '—'}</dd>
    </div>
  );
}

export function CustomerPicker({ value, label, onPick }: { value: string; label?: string | null; onPick: (id: string, name: string) => void }) {
  const [search, setSearch] = useState('');
  const q = useCustomerPicker(search, search.trim().length > 0);
  return (
    <RemotePicker
      value={value}
      selectedLabel={label}
      search={search}
      onSearch={setSearch}
      loading={q.isFetching}
      results={(q.data ?? []).map((c) => ({ value: c.id, label: c.name, hint: c.code ?? undefined }))}
      onPick={onPick}
      placeholder={tx('Search customer…')}
      clearLabel={tx('Clear')}
    />
  );
}

export function ProductPicker({ value, label, onPick }: { value: string; label?: string | null; onPick: (p: ProductOption | null) => void }) {
  const [search, setSearch] = useState('');
  const q = useProductPicker(search, search.trim().length > 0);
  const rows = q.data ?? [];
  return (
    <RemotePicker
      value={value}
      selectedLabel={label}
      search={search}
      onSearch={setSearch}
      loading={q.isFetching}
      results={rows.map((p) => ({ value: p.id, label: p.name, hint: [p.code, p.uomCode].filter(Boolean).join(' · ') || undefined }))}
      onPick={(id) => onPick(rows.find((p) => p.id === id) ?? null)}
      placeholder={tx('Search product…')}
      clearLabel={tx('Clear')}
    />
  );
}

/** A dialog asking for a reason (cancel, reject, return…); `options` adds a reason-code select. */
export function ReasonDialog({ open, title, description, confirmLabel, required = true, options, busy, onClose, onConfirm }: {
  open: boolean;
  title: string;
  description?: string;
  confirmLabel: string;
  required?: boolean;
  options?: Array<{ value: string; label: string }>;
  busy?: boolean;
  onClose: () => void;
  onConfirm: (reason: string, code: string) => void;
}) {
  const [reason, setReason] = useState('');
  const [code, setCode] = useState('');
  const invalid = required && !reason.trim();
  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) { setReason(''); setCode(''); onClose(); } }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <div className="space-y-3">
          {options && (
            <Field label={tx('Reason code')}>
              <OptionalSelect value={code} onChange={setCode} options={options} placeholder={tx('Choose…')} />
            </Field>
          )}
          <Field label={required ? tx('Reason') : tx('Note')}>
            <Textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} />
          </Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={invalid || busy} onClick={() => { onConfirm(reason.trim(), code); setReason(''); setCode(''); }}>{confirmLabel}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** A dialog with one odometer input (dispatch / loading / close). */
export function OdometerDialog({ open, title, confirmLabel, withDistance, busy, onClose, onConfirm }: {
  open: boolean;
  title: string;
  confirmLabel: string;
  withDistance?: boolean;
  busy?: boolean;
  onClose: () => void;
  onConfirm: (odometer: string, distanceKm: string) => void;
}) {
  const [odo, setOdo] = useState('');
  const [km, setKm] = useState('');
  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>{title}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <Field label={tx('Odometer (km, optional)')}>
            <Input inputMode="decimal" type="number" min={0} step="0.1" value={odo} onChange={(e) => setOdo(e.target.value)} className="h-12 text-lg" />
          </Field>
          {withDistance && (
            <Field label={tx('Trip distance (km, optional)')}>
              <Input inputMode="decimal" type="number" min={0} step="0.1" value={km} onChange={(e) => setKm(e.target.value)} className="h-12 text-lg" />
            </Field>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={busy} onClick={() => { onConfirm(odo, km); setOdo(''); setKm(''); }}>{confirmLabel}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

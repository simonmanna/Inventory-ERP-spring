import type { ReactNode } from 'react';
import { StatusPill, type Tone } from '@/components/list';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { tx } from '@/lib/i18n/tx';
import { useLocationOptions, useProductOptions } from '@/features/manufacturing/options';
import { usePartners } from '@/features/partners/api';
import { useStaffOptions } from '@/features/inventory/staff-options';
import {
  CAPA_STATUS_LABEL, LOT_STATUS_LABEL, NCR_STATUS_LABEL, RESULT_LABEL, SEVERITY_LABEL, SPEC_STATUS_LABEL,
  type CapaStatus, type LotResult, type LotStatus, type NcrStatus, type Severity, type SpecStatus,
} from '@/features/quality/api';

/** Shared bits of the quality screens (ADR-S013): status pills, read-only items, master-data pickers. */

export { Field, OptionalSelect } from '../fleet/fleet-ui';

const SPEC_TONE: Record<SpecStatus, Tone> = { draft: 'warning', active: 'success', superseded: 'neutral', archived: 'neutral' };
const LOT_TONE: Record<LotStatus, Tone> = { open: 'info', hold: 'warning', quarantined: 'accent', closed: 'success', cancelled: 'neutral' };
const RESULT_TONE: Record<LotResult | 'deviation', Tone> = { pending: 'neutral', pass: 'success', fail: 'danger', deviation: 'warning' };
const NCR_TONE: Record<NcrStatus, Tone> = { open: 'danger', investigating: 'warning', action: 'info', closed: 'success', cancelled: 'neutral' };
const SEVERITY_TONE: Record<Severity, Tone> = { minor: 'neutral', major: 'warning', critical: 'danger' };
const CAPA_TONE: Record<CapaStatus, Tone> = { open: 'warning', in_progress: 'info', verification: 'accent', closed: 'success', cancelled: 'neutral' };

export const SpecStatusPill = ({ status }: { status: SpecStatus }) =>
  <StatusPill tone={SPEC_TONE[status] ?? 'neutral'}>{SPEC_STATUS_LABEL[status] ?? status}</StatusPill>;
export const LotStatusPill = ({ status }: { status: LotStatus }) =>
  <StatusPill tone={LOT_TONE[status] ?? 'neutral'}>{LOT_STATUS_LABEL[status] ?? status}</StatusPill>;
export const ResultPill = ({ result }: { result: LotResult | 'deviation' }) =>
  <StatusPill tone={RESULT_TONE[result] ?? 'neutral'}>{RESULT_LABEL[result] ?? result}</StatusPill>;
export const NcrStatusPill = ({ status }: { status: NcrStatus }) =>
  <StatusPill tone={NCR_TONE[status] ?? 'neutral'}>{NCR_STATUS_LABEL[status] ?? status}</StatusPill>;
export const SeverityPill = ({ severity }: { severity: Severity }) =>
  <StatusPill tone={SEVERITY_TONE[severity] ?? 'neutral'} dot={false}>{SEVERITY_LABEL[severity] ?? severity}</StatusPill>;
export const CapaStatusPill = ({ status }: { status: CapaStatus }) =>
  <StatusPill tone={CAPA_TONE[status] ?? 'neutral'}>{CAPA_STATUS_LABEL[status] ?? status}</StatusPill>;

export function Item({ label, children }: { label: string; children?: ReactNode }) {
  return (
    <div className="space-y-0.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm font-medium break-words">{children ?? '—'}</dd>
    </div>
  );
}

export function ProductPicker({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder?: string }) {
  const products = useProductOptions();
  return (
    <SearchableSelect value={value} onValueChange={onChange} placeholder={placeholder ?? tx('Choose a product')}
      searchPlaceholder={tx('Search products…')}
      options={(products.data ?? []).map((p) => ({ value: p.id, label: p.name, code: p.code }))} />
  );
}

export function LocationPicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const locations = useLocationOptions();
  return (
    <SearchableSelect value={value} onValueChange={onChange} placeholder={tx('Choose a location')}
      searchPlaceholder={tx('Search locations…')}
      options={(locations.data ?? []).map((l) => ({ value: l.id, label: l.name, code: l.code }))} />
  );
}

export function SupplierPicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const partners = usePartners({ page: 1, pageSize: 200 });
  return (
    <SearchableSelect value={value} onValueChange={onChange} placeholder={tx('Any supplier')}
      searchPlaceholder={tx('Search suppliers…')}
      options={(partners.data?.data ?? []).filter((p) => p.isSupplier !== false).map((p) => ({ value: p.id, label: p.name, code: p.code }))} />
  );
}

export function UserPicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const staff = useStaffOptions();
  return (
    <SearchableSelect value={value} onValueChange={onChange} placeholder={tx('Choose an owner')}
      searchPlaceholder={tx('Search staff…')} options={staff.data ?? []} />
  );
}

import { StatusPill, type Tone } from '@/components/list';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { tx } from '@/lib/i18n/tx';
import { useStaffOptions } from '@/features/inventory/staff-options';
import {
  CHECK_STATUS_LABEL, ORIGIN_LABEL, useFailureClasses, useQcTeams, type QcCheckStatus, type QcOrigin,
} from '@/features/quality-control/api';

/** Shared bits of the quality-control screens (ADR-S087). */

export { Field, OptionalSelect } from '../fleet/fleet-ui';
export { Item, ProductPicker, LocationPicker, SupplierPicker } from '../quality/quality-ui';

const STATUS_TONE: Record<QcCheckStatus, Tone> = {
  pending: 'info', passed: 'success', failed: 'danger', waived: 'warning', cancelled: 'neutral',
};
const ORIGIN_TONE: Record<QcOrigin, Tone> = { generated: 'accent', manual: 'neutral', correction: 'warning' };

export const CheckStatusPill = ({ status }: { status: QcCheckStatus }) =>
  <StatusPill tone={STATUS_TONE[status] ?? 'neutral'}>{CHECK_STATUS_LABEL[status] ?? status}</StatusPill>;
export const OriginPill = ({ origin }: { origin: QcOrigin }) =>
  <StatusPill tone={ORIGIN_TONE[origin] ?? 'neutral'} dot={false}>{ORIGIN_LABEL[origin] ?? origin}</StatusPill>;
export const BlocksPill = ({ blocks }: { blocks: boolean }) =>
  blocks ? <StatusPill tone="danger" dot={false}>{tx('Blocking')}</StatusPill> : null;
export const ActivePill = ({ active }: { active: boolean }) =>
  <StatusPill tone={active ? 'success' : 'neutral'}>{active ? tx('Active') : tx('Inactive')}</StatusPill>;

export function StaffPicker({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder?: string }) {
  const staff = useStaffOptions();
  return (
    <SearchableSelect value={value} onValueChange={onChange} placeholder={placeholder ?? tx('Choose a person')}
      searchPlaceholder={tx('Search staff…')} options={staff.data ?? []} />
  );
}

export function TeamPicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const teams = useQcTeams();
  return (
    <SearchableSelect value={value} onValueChange={onChange} placeholder={tx('No team')} searchPlaceholder={tx('Search teams…')}
      options={(teams.data ?? []).filter((t) => t.isActive || t.id === value).map((t) => ({ value: t.id, label: t.name }))} />
  );
}

export function FailureClassPicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const classes = useFailureClasses(true);
  return (
    <SearchableSelect value={value} onValueChange={onChange} placeholder={tx('Choose a failure class')}
      searchPlaceholder={tx('Search classes…')}
      options={(classes.data ?? []).map((c) => ({ value: c.id, label: c.name, code: c.code }))} />
  );
}

/** Yes / no toggle without a dedicated switch component. */
export function Toggle({ checked, onChange, label, hint }: { checked: boolean; onChange: (v: boolean) => void; label: string; hint?: string }) {
  return (
    <label className="flex cursor-pointer items-start gap-2 text-sm">
      <input type="checkbox" className="mt-0.5 h-4 w-4 accent-primary" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span>
        <span className="font-medium">{label}</span>
        {hint && <span className="block text-xs text-muted-foreground">{hint}</span>}
      </span>
    </label>
  );
}

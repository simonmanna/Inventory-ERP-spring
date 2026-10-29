import { StatusPill, type Tone } from '@/components/list';
import { useVehicles } from '@/features/fleet/api';
import {
  DEFECT_STATUS_LABEL, DIMENSION_UNIT, DUE_STATE_LABEL, PRIORITY_LABEL, SEVERITY_LABEL, WO_STATUS_LABEL,
  type DefectStatus, type DueDimension, type DueState, type Priority, type Severity, type UnitRef, type WorkOrderStatus,
} from '@/features/fleet/maintenance-api';
import { num } from '../fleet-format';

/** Shared bits of the maintenance screens (fleet 1.6). */

const DUE_TONE: Record<DueState, Tone> = { ok: 'success', due: 'warning', overdue: 'danger' };
export const DueStatePill = ({ state }: { state: DueState }) => <StatusPill tone={DUE_TONE[state]}>{DUE_STATE_LABEL[state]}</StatusPill>;

const WO_TONE: Record<WorkOrderStatus, Tone> = {
  draft: 'neutral', open: 'info', in_progress: 'warning', completed: 'success', closed: 'neutral', cancelled: 'neutral',
};
export const WorkOrderStatusPill = ({ status }: { status: WorkOrderStatus }) => <StatusPill tone={WO_TONE[status]}>{WO_STATUS_LABEL[status]}</StatusPill>;

const SEV_TONE: Record<Severity, Tone> = { minor: 'info', major: 'warning', critical: 'danger' };
export const SeverityPill = ({ severity }: { severity: Severity }) => <StatusPill tone={SEV_TONE[severity]}>{SEVERITY_LABEL[severity]}</StatusPill>;

const DEFECT_TONE: Record<DefectStatus, Tone> = { open: 'danger', in_repair: 'warning', resolved: 'success', dismissed: 'neutral' };
export const DefectStatusPill = ({ status }: { status: DefectStatus }) => <StatusPill tone={DEFECT_TONE[status]}>{DEFECT_STATUS_LABEL[status]}</StatusPill>;

const PRIORITY_TONE: Record<Priority, Tone> = { low: 'neutral', normal: 'info', high: 'warning', urgent: 'danger' };
export const PriorityPill = ({ priority }: { priority: Priority }) => <StatusPill tone={PRIORITY_TONE[priority]} dot={false}>{PRIORITY_LABEL[priority]}</StatusPill>;

export const unitLabel = (v?: UnitRef | null) => (v ? [v.code, v.registrationPlate].filter(Boolean).join(' · ') : '—');

/** "1,200 km left · 14 days left" / "300 km over". */
export function dueText(dims: DueDimension[]): string {
  return dims
    .filter((d) => d.remaining !== null)
    .map((d) => {
      const n = Number(d.remaining);
      const unit = DIMENSION_UNIT[d.dimension];
      return n < 0 ? `${num(-n, 1)} ${unit} ↑` : `${num(n, 1)} ${unit}`;
    })
    .join(' · ') || '—';
}

/** Live units for pickers (first 200 by code). */
export function useUnitOptions() {
  const { data } = useVehicles({ page: 1, pageSize: 200, sortBy: 'code', sortOrder: 'asc' });
  return (data?.data ?? [])
    .filter((v) => v.status !== 'disposed')
    .map((v) => ({ value: v.id, label: [v.code, v.registrationPlate, v.name].filter(Boolean).join(' · '), categoryId: v.categoryId }));
}

export const toNumber = (v: string) => (v.trim() === '' ? undefined : Number(v));

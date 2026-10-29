import { StatusPill, type Tone } from '@/components/list';
import {
  CLAIM_STATUS_LABEL, DOC_STATE_LABEL, DRIVER_STATUS_LABEL, type ClaimStatus, type DocState, type DriverStatus,
} from '@/features/fleet/drivers-api';

/** Status pills of the fleet 1.2 screens (drivers, documents, claims). */

const DRIVER_TONE: Record<DriverStatus, Tone> = { active: 'success', suspended: 'warning', inactive: 'neutral' };

export function DriverStatusPill({ status }: { status: DriverStatus }) {
  return <StatusPill tone={DRIVER_TONE[status] ?? 'neutral'}>{DRIVER_STATUS_LABEL[status] ?? status}</StatusPill>;
}

const DOC_TONE: Record<DocState, Tone> = { expired: 'danger', expiring: 'warning', valid: 'success', no_expiry: 'info' };

export function DocStatePill({ state }: { state: DocState }) {
  return <StatusPill tone={DOC_TONE[state] ?? 'neutral'}>{DOC_STATE_LABEL[state] ?? state}</StatusPill>;
}

const CLAIM_TONE: Record<ClaimStatus, Tone> = {
  draft: 'neutral',
  submitted: 'warning',
  approved: 'info',
  rejected: 'danger',
  paid: 'success',
  exported: 'accent',
  cancelled: 'neutral',
};

export function ClaimStatusPill({ status }: { status: ClaimStatus }) {
  return <StatusPill tone={CLAIM_TONE[status] ?? 'neutral'}>{CLAIM_STATUS_LABEL[status] ?? status}</StatusPill>;
}

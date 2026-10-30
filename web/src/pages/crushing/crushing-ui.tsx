import { useState } from 'react';
import { StatusPill, type Tone } from '@/components/list';
import { tx } from '@/lib/i18n/tx';
import { RemotePicker } from '@/pages/fleet/fleet-ui';
import {
  PROFILE_STATUS_LABEL, RUN_STATUS_LABEL, SURVEY_STATUS_LABEL, useCrushingProductSearch,
  type ProfileStatus, type RunStatus, type SurveyStatus,
} from '@/features/crushing/api';

/** Shared bits of the crushing screens (ADR-S016); generic tiles / sections come from the quarry screens. */
export { EmptyState, JournalTable, KeyValues, ReasonDialog, Section, StatTile, qty, toOptions, tonnes } from '@/pages/quarry/quarry-ui';

export const pct = (v: string | number | null | undefined) => (v === null || v === undefined || v === '' ? '—' : `${Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 })} %`);

const RUN_TONE: Record<RunStatus, Tone> = { draft: 'warning', posted: 'success', reversed: 'neutral' };
export const RunStatusPill = ({ status }: { status: RunStatus }) => (
  <StatusPill tone={RUN_TONE[status] ?? 'neutral'}>{RUN_STATUS_LABEL[status] ?? status}</StatusPill>
);
const SURVEY_TONE: Record<SurveyStatus, Tone> = { draft: 'warning', submitted: 'info', posted: 'success', rejected: 'danger', reversed: 'neutral' };
export const SurveyStatusPill = ({ status }: { status: SurveyStatus }) => (
  <StatusPill tone={SURVEY_TONE[status] ?? 'neutral'}>{SURVEY_STATUS_LABEL[status] ?? status}</StatusPill>
);
const PROFILE_TONE: Record<ProfileStatus, Tone> = { draft: 'warning', active: 'success', archived: 'neutral' };
export const ProfileStatusPill = ({ status }: { status: ProfileStatus }) => (
  <StatusPill tone={PROFILE_TONE[status] ?? 'neutral'}>{PROFILE_STATUS_LABEL[status] ?? status}</StatusPill>
);

export function CrushingProductPicker({ value, label, onChange }: { value: string; label?: string | null; onChange: (id: string, label: string) => void }) {
  const [search, setSearch] = useState('');
  const q = useCrushingProductSearch(search);
  return (
    <RemotePicker value={value} selectedLabel={label} search={search} onSearch={setSearch}
      results={(q.data ?? []).map((p) => ({ value: p.id, label: p.name, hint: p.code }))}
      loading={q.isFetching} onPick={onChange} placeholder={tx('Type the product name or code…')} clearLabel={tx('Clear')} />
  );
}

/** Local date (YYYY-MM-DD) of today. */
export const today = () => new Date(Date.now() - new Date().getTimezoneOffset() * 60_000).toISOString().slice(0, 10);
/** '' → undefined, else a number. */
export const num = (v: string) => (v.trim() === '' ? undefined : Number(v));

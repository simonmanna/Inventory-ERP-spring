import type { ReactNode } from 'react';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { StatusPill, type Tone } from '@/components/list';
import { cn } from '@/lib/utils';
import {
  COMPLIANCE_STATE_LABEL, STATUS_LABEL, type ComplianceStateValue, type VehicleStatus,
} from '@/features/fleet/api';

/** Shared bits of the fleet screens (ADR-S009): status pills, labelled fields, optional selects. */

const STATUS_TONE: Record<VehicleStatus, Tone> = {
  active: 'success',
  in_workshop: 'warning',
  breakdown: 'danger',
  idle: 'neutral',
  disposed: 'neutral',
};

export function VehicleStatusPill({ status }: { status: VehicleStatus }) {
  return <StatusPill tone={STATUS_TONE[status] ?? 'neutral'}>{STATUS_LABEL[status] ?? status}</StatusPill>;
}

const STATE_TONE: Record<ComplianceStateValue, Tone> = {
  expired: 'danger',
  expiring: 'warning',
  valid: 'success',
  no_expiry: 'info',
  renewed: 'neutral',
};

export function ComplianceStatePill({ state }: { state: ComplianceStateValue }) {
  return <StatusPill tone={STATE_TONE[state] ?? 'neutral'}>{COMPLIANCE_STATE_LABEL[state] ?? state}</StatusPill>;
}

export function Field({ label, error, children, className }: {
  label: string; error?: string; children: ReactNode; className?: string;
}) {
  return (
    <div className={cn('space-y-1.5', className)}>
      <Label>{label}</Label>
      {children}
      {error && <p className="text-xs text-destructive">{error}</p>}
    </div>
  );
}

/** Radix Select forbids an empty-string item value, so "none" uses a sentinel. */
const NONE = '__none__';

export function OptionalSelect({ value, onChange, options, placeholder, noneLabel, disabled }: {
  value: string;
  onChange: (v: string) => void;
  options: Array<{ value: string; label: string }>;
  placeholder: string;
  noneLabel?: string;
  disabled?: boolean;
}) {
  return (
    <Select value={value || NONE} onValueChange={(v) => onChange(v === NONE ? '' : v)} disabled={disabled}>
      <SelectTrigger><SelectValue placeholder={placeholder} /></SelectTrigger>
      <SelectContent>
        {noneLabel !== undefined && <SelectItem value={NONE}>{noneLabel}</SelectItem>}
        {options.map((o) => <SelectItem key={o.value} value={o.value}>{o.label}</SelectItem>)}
      </SelectContent>
    </Select>
  );
}

/**
 * Type-ahead picker over a server search (partners, fixed assets): type to search, click a match to pick it.
 * `selectedLabel` shows the current pick; "Clear" empties it.
 */
export function RemotePicker({ value, selectedLabel, search, onSearch, results, loading, onPick, placeholder, clearLabel }: {
  value: string;
  selectedLabel?: string | null;
  search: string;
  onSearch: (s: string) => void;
  results: Array<{ value: string; label: string; hint?: string }>;
  loading?: boolean;
  onPick: (value: string, label: string) => void;
  placeholder: string;
  clearLabel: string;
}) {
  return (
    <div className="space-y-1.5">
      {value && (
        <div className="flex items-center justify-between rounded-md border bg-muted/40 px-3 py-1.5 text-sm">
          <span className="truncate">{selectedLabel ?? value}</span>
          <button type="button" className="text-xs text-primary hover:underline" onClick={() => onPick('', '')}>{clearLabel}</button>
        </div>
      )}
      <input
        className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
        placeholder={placeholder}
        value={search}
        onChange={(e) => onSearch(e.target.value)}
      />
      {search.trim().length > 0 && (
        <div className="max-h-48 overflow-y-auto rounded-md border bg-card text-sm shadow-sm">
          {loading && <div className="px-3 py-2 text-muted-foreground">…</div>}
          {!loading && results.length === 0 && <div className="px-3 py-2 text-muted-foreground">—</div>}
          {results.map((r) => (
            <button
              key={r.value}
              type="button"
              className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left hover:bg-accent"
              onClick={() => { onPick(r.value, r.label); onSearch(''); }}
            >
              <span className="truncate">{r.label}</span>
              {r.hint && <span className="shrink-0 text-xs text-muted-foreground">{r.hint}</span>}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

import { useState, type ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { StatusPill, type Tone } from '@/components/list';
import { notify, apiMessage } from '@/lib/notify';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { Field, OptionalSelect, RemotePicker } from '@/pages/fleet/fleet-ui';
import {
  BLAST_STATUS_LABEL, ISSUE_STATUS_LABEL, LOG_STATUS_LABEL, useEmployeeSearch, usePartnerSearch, useProductSearch,
  type BlastStatus, type IssueStatus, type JournalEntry, type LogStatus, type Variance,
} from '@/features/quarry/api';

/** Shared bits of the quarry screens (ADR-S012): status pills, stat tiles, pickers, a field-driven form dialog. */

/** "1,250.5 t" style quantities; "—" when absent. */
export function qty(v: string | number | null | undefined, digits = 2): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = Number(v);
  return Number.isFinite(n) ? n.toLocaleString(undefined, { maximumFractionDigits: digits }) : String(v);
}
export const tonnes = (v: string | number | null | undefined) => (v === null || v === undefined || v === '' ? '—' : `${qty(v)} t`);
export const toOptions = (m: Record<string, string>) => Object.entries(m).map(([value, label]) => ({ value, label }));

const LOG_TONE: Record<LogStatus, Tone> = { draft: 'warning', posted: 'success', reversed: 'neutral' };
export const LogStatusPill = ({ status }: { status: LogStatus }) => (
  <StatusPill tone={LOG_TONE[status] ?? 'neutral'}>{LOG_STATUS_LABEL[status] ?? status}</StatusPill>
);
const BLAST_TONE: Record<BlastStatus, Tone> = { planned: 'info', fired: 'warning', closed: 'success', cancelled: 'neutral' };
export const BlastStatusPill = ({ status }: { status: BlastStatus }) => (
  <StatusPill tone={BLAST_TONE[status] ?? 'neutral'}>{BLAST_STATUS_LABEL[status] ?? status}</StatusPill>
);
const ISSUE_TONE: Record<IssueStatus, Tone> = {
  pending_approval: 'warning', approved: 'info', rejected: 'danger', issued: 'accent', reconciled: 'success', cancelled: 'neutral',
};
export const IssueStatusPill = ({ status }: { status: IssueStatus }) => (
  <StatusPill tone={ISSUE_TONE[status] ?? 'neutral'}>{ISSUE_STATUS_LABEL[status] ?? status}</StatusPill>
);
const VARIANCE_LABEL: Record<Variance['status'], string> = {
  not_started: tx('Not started'), ahead: tx('Ahead'), on_track: tx('On track'), behind: tx('Behind'), achieved: tx('Achieved'), missed: tx('Missed'),
};
const VARIANCE_TONE: Record<Variance['status'], Tone> = {
  not_started: 'neutral', ahead: 'success', on_track: 'info', behind: 'danger', achieved: 'success', missed: 'danger',
};
export const VariancePill = ({ status }: { status: Variance['status'] }) => (
  <StatusPill tone={VARIANCE_TONE[status] ?? 'neutral'}>{VARIANCE_LABEL[status] ?? status}</StatusPill>
);

export function StatTile({ label, value, hint, tone }: { label: string; value: ReactNode; hint?: ReactNode; tone?: 'danger' | 'warning' }) {
  const color = tone === 'danger' ? 'text-rose-600' : tone === 'warning' ? 'text-amber-600' : '';
  return (
    <div className="rounded-xl border bg-card p-3 shadow-sm">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={`text-xl font-semibold tabular-nums ${color}`}>{value}</div>
      {hint && <div className="text-xs text-muted-foreground">{hint}</div>}
    </div>
  );
}

export function Section({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="space-y-3 rounded-xl border bg-card p-4 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">{title}</h2>
        {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
      </div>
      {children}
    </section>
  );
}

/** Label / value grid of a detail screen. */
export function KeyValues({ items }: { items: Array<[string, ReactNode]> }) {
  return (
    <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2 lg:grid-cols-3">
      {items.map(([k, v]) => (
        <div key={k} className="flex justify-between gap-3 border-b border-dashed py-1 sm:block sm:border-0">
          <dt className="text-muted-foreground">{k}</dt>
          <dd className="font-medium">{v ?? '—'}</dd>
        </div>
      ))}
    </dl>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <div className="rounded-xl border bg-card p-8 text-center text-sm text-muted-foreground">{children}</div>;
}

export function JournalTable({ entries }: { entries: JournalEntry[] }) {
  if (!entries.length) return <p className="text-sm text-muted-foreground">{tx('No GL entries yet.')}</p>;
  return (
    <div className="space-y-3">
      {entries.map((e) => (
        <div key={e.id} className="overflow-x-auto rounded-lg border">
          <div className="flex flex-wrap justify-between gap-2 bg-muted/40 px-3 py-2 text-sm">
            <span className="font-medium">{e.entryNumber} · {e.description}</span>
            <span className="text-muted-foreground">{e.reversalOfId ? tx('Reversal') : e.status}</span>
          </div>
          <table className="w-full text-sm">
            <tbody>
              {e.lines.map((l) => (
                <tr key={l.id} className="border-t">
                  <td className="px-3 py-1.5">{l.accountCode} · {l.accountName}</td>
                  <td className="px-3 py-1.5 text-muted-foreground">{l.description}</td>
                  <td className="px-3 py-1.5 text-right tabular-nums">{Number(l.debit) ? money(l.debit) : ''}</td>
                  <td className="px-3 py-1.5 text-right tabular-nums">{Number(l.credit) ? money(l.credit) : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </div>
  );
}

// ---- pickers over server searches ----

export function EmployeePicker({ value, label, onChange }: { value: string; label?: string | null; onChange: (id: string, label: string) => void }) {
  const [search, setSearch] = useState('');
  const q = useEmployeeSearch(search);
  return (
    <RemotePicker value={value} selectedLabel={label} search={search} onSearch={setSearch}
      results={(q.data ?? []).map((e) => ({ value: e.id, label: e.name || e.employeeCode, hint: e.employeeCode }))}
      loading={q.isFetching} onPick={onChange} placeholder={tx('Type a name or employee code…')} clearLabel={tx('Clear')} />
  );
}

export function ProductPicker({ value, label, onChange }: { value: string; label?: string | null; onChange: (id: string, label: string) => void }) {
  const [search, setSearch] = useState('');
  const q = useProductSearch(search);
  return (
    <RemotePicker value={value} selectedLabel={label} search={search} onSearch={setSearch}
      results={(q.data ?? []).map((p) => ({ value: p.id, label: p.name, hint: `${p.code} · ${p.costingMethod}` }))}
      loading={q.isFetching} onPick={onChange} placeholder={tx('Type the product name or code…')} clearLabel={tx('Clear')} />
  );
}

export function PartnerPicker({ value, label, onChange }: { value: string; label?: string | null; onChange: (id: string, label: string) => void }) {
  const [search, setSearch] = useState('');
  const q = usePartnerSearch(search);
  return (
    <RemotePicker value={value} selectedLabel={label} search={search} onSearch={setSearch}
      results={(q.data ?? []).map((p) => ({ value: p.id, label: p.name, hint: p.code }))}
      loading={q.isFetching} onPick={onChange} placeholder={tx('Type the partner name…')} clearLabel={tx('Clear')} />
  );
}

// ---- field-driven form dialog (master data) ----

export type FieldType = 'text' | 'number' | 'date' | 'datetime' | 'select' | 'textarea' | 'checkbox' | 'employee' | 'product' | 'partner';
export interface FieldSpec {
  name: string;
  label: string;
  type?: FieldType;
  required?: boolean;
  options?: Array<{ value: string; label: string }>;
  /** Shown next to employee / product / partner pickers before a new pick. */
  pickedLabel?: string | null;
  wide?: boolean;
  hidden?: boolean;
  disabled?: boolean;
  hint?: string;
  min?: number;
}

export type FormValues = Record<string, string | boolean>;

/** Values of a row for the form (numbers / dates as strings, booleans kept). */
export function initialValues(fields: FieldSpec[], row?: object | null, defaults: FormValues = {}): FormValues {
  const out: FormValues = {};
  const r = (row ?? {}) as Record<string, unknown>;
  for (const f of fields) {
    const v = r[f.name];
    if (f.type === 'checkbox') out[f.name] = v === undefined || v === null ? (defaults[f.name] ?? false) : Boolean(v);
    else if (v === undefined || v === null) out[f.name] = defaults[f.name] ?? '';
    else if (f.type === 'datetime') out[f.name] = String(v).slice(0, 16);
    else if (f.type === 'date') out[f.name] = String(v).slice(0, 10);
    else out[f.name] = String(v);
  }
  return out;
}

/** The JSON body of the form: numbers parsed, blanks as null, datetimes as ISO instants. */
export function bodyOf(fields: FieldSpec[], values: FormValues): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  for (const f of fields) {
    if (f.hidden && values[f.name] === '') continue;
    const v = values[f.name];
    if (f.type === 'checkbox') body[f.name] = Boolean(v);
    else if (v === '' || v === undefined) body[f.name] = null;
    else if (f.type === 'number') body[f.name] = Number(v);
    else if (f.type === 'datetime') body[f.name] = new Date(String(v)).toISOString();
    else body[f.name] = typeof v === 'string' ? v.trim() : v;
  }
  return body;
}

export function FormFields({ fields, values, setValue, errors }: {
  fields: FieldSpec[]; values: FormValues; setValue: (name: string, v: string | boolean) => void; errors: Record<string, string>;
}) {
  const [labels, setLabels] = useState<Record<string, string>>({});
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
      {fields.filter((f) => !f.hidden).map((f) => {
        const v = values[f.name];
        const s = typeof v === 'string' ? v : '';
        const label = `${f.label}${f.required ? ' *' : ''}`;
        const pick = (id: string, l: string) => { setValue(f.name, id); setLabels((x) => ({ ...x, [f.name]: l })); };
        let control: ReactNode;
        switch (f.type) {
          case 'select':
            control = <OptionalSelect value={s} onChange={(x) => setValue(f.name, x)} options={f.options ?? []} placeholder={tx('Choose…')}
              noneLabel={f.required ? undefined : tx('None')} disabled={f.disabled} />;
            break;
          case 'textarea':
            control = <Textarea rows={2} value={s} onChange={(e) => setValue(f.name, e.target.value)} disabled={f.disabled} />;
            break;
          case 'checkbox':
            control = (
              <label className="flex h-10 items-center gap-2 text-sm">
                <input type="checkbox" checked={Boolean(v)} onChange={(e) => setValue(f.name, e.target.checked)} disabled={f.disabled} /> {f.hint ?? f.label}
              </label>
            );
            break;
          case 'employee':
            control = <EmployeePicker value={s} label={labels[f.name] ?? f.pickedLabel} onChange={pick} />;
            break;
          case 'product':
            control = f.disabled ? <Input value={f.pickedLabel ?? s} disabled /> : <ProductPicker value={s} label={labels[f.name] ?? f.pickedLabel} onChange={pick} />;
            break;
          case 'partner':
            control = <PartnerPicker value={s} label={labels[f.name] ?? f.pickedLabel} onChange={pick} />;
            break;
          default:
            control = (
              <Input type={f.type === 'number' ? 'number' : f.type === 'date' ? 'date' : f.type === 'datetime' ? 'datetime-local' : 'text'}
                inputMode={f.type === 'number' ? 'decimal' : undefined} min={f.min} step="any" value={s} disabled={f.disabled}
                onChange={(e) => setValue(f.name, e.target.value)} />
            );
        }
        return (
          <Field key={f.name} label={label} error={errors[f.name]} className={f.wide || f.type === 'textarea' ? 'sm:col-span-2' : undefined}>
            {control}
            {f.hint && f.type !== 'checkbox' && <p className="text-xs text-muted-foreground">{f.hint}</p>}
          </Field>
        );
      })}
    </div>
  );
}

/** Required + number checks of a field-driven form. */
export function validate(fields: FieldSpec[], values: FormValues): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const f of fields) {
    if (f.hidden) continue;
    const v = values[f.name];
    if (f.required && f.type !== 'checkbox' && (v === '' || v === undefined)) errors[f.name] = tx('Required');
    else if (f.type === 'number' && v !== '' && v !== undefined && !Number.isFinite(Number(v))) errors[f.name] = tx('Enter a number');
    else if (f.type === 'number' && f.min !== undefined && v !== '' && Number(v) < f.min) errors[f.name] = tx('Too small');
  }
  return errors;
}

/** A create / edit dialog over `fields`; `onSave(body)` performs the request. */
export function FormDialog({ title, description, fields, row, defaults, onSave, onClose, saving }: {
  title: string; description?: string; fields: FieldSpec[]; row?: object | null; defaults?: FormValues;
  onSave: (body: Record<string, unknown>) => Promise<unknown>; onClose: () => void; saving?: boolean;
}) {
  const [values, setValues] = useState<FormValues>(() => initialValues(fields, row, defaults));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const submit = async () => {
    const e = validate(fields, values);
    setErrors(e);
    if (Object.keys(e).length) return;
    try {
      await onSave(bodyOf(fields, values));
      notify.success(tx('Saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <FormFields fields={fields} values={values} errors={errors} setValue={(n, v) => setValues((x) => ({ ...x, [n]: v }))} />
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={saving}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Ask for a reason (reverse, cancel) and confirm. */
export function ReasonDialog({ title, description, confirmLabel, onConfirm, onClose, busy }: {
  title: string; description?: string; confirmLabel: string; onConfirm: (reason: string) => Promise<unknown>; onClose: () => void; busy?: boolean;
}) {
  const [reason, setReason] = useState('');
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <Field label={tx('Reason *')}><Textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button variant="destructive" disabled={!reason.trim() || busy}
            onClick={async () => {
              try { await onConfirm(reason.trim()); onClose(); } catch (err) { notify.error(apiMessage(err, tx('The action failed'))); }
            }}>{confirmLabel}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

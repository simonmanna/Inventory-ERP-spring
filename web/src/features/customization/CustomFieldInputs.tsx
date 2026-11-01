import { useEffect, useState } from 'react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { type CustomFieldDefinition, useCustomValues, useSaveCustomValues } from '@/features/customization/api';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const SELECT = 'h-9 w-full rounded-md border border-input bg-background px-3 text-sm';

/** One input per custom field type (ADR-S029 §1). */
export function CustomFieldInput({
  field,
  value,
  onChange,
  disabled,
}: {
  field: CustomFieldDefinition;
  value: unknown;
  onChange: (v: unknown) => void;
  disabled?: boolean;
}) {
  const id = `cf-${field.key}`;
  const text = value === undefined || value === null ? '' : String(value);
  let input;
  switch (field.type) {
    case 'textarea':
      input = (
        <textarea id={id} className="min-h-20 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
          value={text} disabled={disabled} onChange={(e) => onChange(e.target.value)} />
      );
      break;
    case 'boolean':
      input = (
        <input id={id} type="checkbox" className="h-4 w-4" checked={value === true} disabled={disabled}
          onChange={(e) => onChange(e.target.checked)} />
      );
      break;
    case 'select':
      input = (
        <select id={id} className={SELECT} value={text} disabled={disabled} onChange={(e) => onChange(e.target.value || null)}>
          <option value="">—</option>
          {(field.options.values ?? []).map((o) => <option key={o} value={o}>{o}</option>)}
        </select>
      );
      break;
    case 'multiselect': {
      const selected = Array.isArray(value) ? (value as string[]) : [];
      input = (
        <div className="flex flex-wrap gap-3">
          {(field.options.values ?? []).map((o) => (
            <label key={o} className="flex items-center gap-1 text-sm">
              <input type="checkbox" disabled={disabled} checked={selected.includes(o)}
                onChange={(e) => onChange(e.target.checked ? [...selected, o] : selected.filter((s) => s !== o))} />
              {o}
            </label>
          ))}
        </div>
      );
      break;
    }
    default:
      input = (
        <Input id={id} disabled={disabled} value={text}
          type={field.type === 'date' ? 'date' : field.type === 'number' || field.type === 'decimal' ? 'number' : 'text'}
          step={field.type === 'decimal' ? 'any' : undefined}
          placeholder={field.type === 'reference' ? tx('Record id') : undefined}
          onChange={(e) => onChange(e.target.value === '' ? null : e.target.value)} />
      );
  }
  return (
    <div className="space-y-1">
      <Label htmlFor={id}>
        {field.label}
        {field.required && <span className="text-destructive"> *</span>}
      </Label>
      {input}
      {field.helpText && <p className="text-xs text-muted-foreground">{field.helpText}</p>}
    </div>
  );
}

/** Custom fields of one record, editable in place; hidden when the entity has no fields. */
export function CustomFieldsPanel({ entity, recordId }: { entity: string; recordId: string }) {
  const has = useAuthStore((s) => s.hasPermission);
  const canRead = has(PERMISSIONS.customRecord.read);
  const canWrite = has(PERMISSIONS.customRecord.write);
  const { data } = useCustomValues(entity, recordId, canRead);
  const save = useSaveCustomValues(entity, recordId);
  const [draft, setDraft] = useState<Record<string, unknown>>({});

  useEffect(() => {
    if (data) setDraft(data.values ?? {});
  }, [data]);

  if (!canRead || !data || data.fields.length === 0) return null;
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{tx('Custom fields')}</CardTitle>
      </CardHeader>
      <CardContent>
        <form
          className="grid gap-3 md:grid-cols-2"
          onSubmit={(e) => {
            e.preventDefault();
            const patch: Record<string, unknown> = {};
            data.fields.forEach((f) => {
              patch[f.key] = draft[f.key] ?? null;
            });
            save.mutate(patch);
          }}
        >
          {data.fields.map((f) => (
            <CustomFieldInput key={f.key} field={f} value={draft[f.key]} disabled={!canWrite}
              onChange={(v) => setDraft((d) => ({ ...d, [f.key]: v }))} />
          ))}
          {canWrite && (
            <div className="md:col-span-2">
              <Button type="submit" disabled={save.isPending}>{tx('Save')}</Button>
            </div>
          )}
        </form>
      </CardContent>
    </Card>
  );
}

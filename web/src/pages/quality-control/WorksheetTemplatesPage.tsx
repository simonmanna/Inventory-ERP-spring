import { useState } from 'react';
import { ArrowDown, ArrowUp, FileSpreadsheet, Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  FIELD_TYPE_LABEL, useSaveTemplate, useWorksheetTemplate, useWorksheetTemplates, type WorksheetField, type WorksheetFieldType,
  type WorksheetTemplate,
} from '@/features/quality-control/api';
import { ActivePill, Field, OptionalSelect, Toggle } from './qc-ui';
import { optNum, toOptions } from './qc-format';

/** Worksheet templates (ADR-S087 §10): versioned custom forms; a used version is never changed. */
export function WorksheetTemplatesPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const { data, isLoading, isError } = useWorksheetTemplates();
  const [editing, setEditing] = useState<string | 'new' | null>(null);
  const columns: Column<WorksheetTemplate>[] = [
    { key: 'name', header: tx('Template'), render: (t) => <span className="font-medium">{t.name}</span> },
    { key: 'fields', header: tx('Fields'), className: 'text-right', render: (t) => t.fields.length },
    { key: 'v', header: tx('Version'), className: 'text-right', render: (t) => `v${t.version}` },
    { key: 'used', header: tx('Used by checks'), className: 'text-right', render: (t) => t.checkCount ?? 0 },
    { key: 'status', header: tx('Status'), render: (t) => <ActivePill active={t.isActive} /> },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={FileSpreadsheet} title={tx('Worksheet Templates')}
        description={tx('Custom check forms. Editing a template that checks already use creates a new version.')}
        actions={has(PERMISSIONS.qualityControl.configure) && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New template')}</Button>} />
      <ListCard>
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(t) => t.id} onRowClick={(t) => setEditing(t.id)}
          cellClassName="py-2.5 px-4" headerRowClassName="h-10"
          emptyMessage={isError ? tx('Could not load the templates.') : tx('No worksheet templates yet.')} />
      </ListCard>
      {editing && <TemplateDialog id={editing === 'new' ? undefined : editing} readOnly={!has(PERMISSIONS.qualityControl.configure)} onClose={() => setEditing(null)} />}
    </div>
  );
}

interface Draft {
  key: string; label: string; type: WorksheetFieldType; required: boolean; min: string; max: string; decimals: string;
  options: string; passValues: string; expected: '' | 'true' | 'false'; maxLength: string; showIfField: string; showIfEquals: string;
}

const toDraft = (f: WorksheetField): Draft => ({
  key: f.key, label: f.label, type: f.type, required: !!f.required, min: f.min != null ? String(f.min) : '',
  max: f.max != null ? String(f.max) : '', decimals: f.decimals != null ? String(f.decimals) : '', options: (f.options ?? []).join(', '),
  passValues: (f.passValues ?? []).join(', '), expected: f.expected == null ? '' : f.expected ? 'true' : 'false',
  maxLength: f.maxLength != null ? String(f.maxLength) : '', showIfField: f.showIf?.field ?? '',
  showIfEquals: f.showIf ? String(f.showIf.equals) : '',
});
const list = (v: string) => v.split(',').map((x) => x.trim()).filter(Boolean);
const slug = (label: string) => label.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').replace(/^(\d)/, 'f_$1').slice(0, 40);

function toField(d: Draft, all: Draft[]): Record<string, unknown> {
  const f: Record<string, unknown> = { key: d.key, label: d.label.trim(), type: d.type };
  if (d.required) f.required = true;
  if (d.type === 'number') {
    if (d.min.trim()) f.min = optNum(d.min);
    if (d.max.trim()) f.max = optNum(d.max);
    if (d.decimals.trim()) f.decimals = optNum(d.decimals);
  }
  if (d.type === 'select') {
    f.options = list(d.options);
    if (d.passValues.trim()) f.passValues = list(d.passValues);
  }
  if (d.type === 'checkbox' && d.expected) f.expected = d.expected === 'true';
  if (d.type === 'text' && d.maxLength.trim()) f.maxLength = optNum(d.maxLength);
  if (d.showIfField) {
    const ref = all.find((x) => x.key === d.showIfField);
    const eq = ref?.type === 'checkbox' ? d.showIfEquals === 'true' : ref?.type === 'number' ? Number(d.showIfEquals) : d.showIfEquals;
    f.showIf = { field: d.showIfField, equals: eq };
  }
  return f;
}

function TemplateDialog({ id, readOnly, onClose }: { id?: string; readOnly: boolean; onClose: () => void }) {
  const { data: t, isLoading } = useWorksheetTemplate(id);
  if (id && (isLoading || !t)) return null;
  return <TemplateEditor key={t?.id ?? 'new'} template={t} readOnly={readOnly || (!!t && !t.isLatest)} onClose={onClose} />;
}

function TemplateEditor({ template: t, readOnly, onClose }: { template?: WorksheetTemplate; readOnly: boolean; onClose: () => void }) {
  const [name, setName] = useState(t?.name ?? '');
  const [description, setDescription] = useState(t?.description ?? '');
  const [isActive, setIsActive] = useState(t?.isActive ?? true);
  const [fields, setFields] = useState<Draft[]>((t?.fields ?? []).map(toDraft));
  const save = useSaveTemplate();
  const set = (i: number, patch: Partial<Draft>) => setFields(fields.map((f, j) => (j === i ? { ...f, ...patch } : f)));
  const move = (i: number, d: -1 | 1) => {
    const next = [...fields];
    [next[i], next[i + d]] = [next[i + d], next[i]];
    setFields(next);
  };
  const add = () => setFields([...fields, { key: '', label: '', type: 'text', required: false, min: '', max: '', decimals: '', options: '', passValues: '', expected: '', maxLength: '', showIfField: '', showIfEquals: '' }]);
  const submit = async () => {
    const withKeys = fields.map((f, i) => ({ ...f, key: f.key || slug(f.label) || `field_${i + 1}` }));
    try {
      const out = await save.mutateAsync({
        id: t?.id,
        body: { name: name.trim(), description: description.trim() || undefined, fields: withKeys.map((f) => toField(f, withKeys)), ...(t ? { isActive } : {}) },
      });
      notify.success(t && out.version !== t.version ? tx('Saved as version {{v}}: checks already created keep version {{old}}', { v: out.version, old: t.version }) : tx('Template saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the template')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>{t ? `${t.name} · v${t.version}` : tx('New worksheet template')}</DialogTitle>
          <DialogDescription>
            {t && !t.isLatest ? tx('An older version, kept for the checks that use it. Open the latest version to edit.')
              : t?.checkCount ? tx('{{n}} checks use this version, so saving creates version {{v}}.', { n: t.checkCount, v: t.version + 1 })
                : tx('The server checks every answer against these rules.')}
          </DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Name *')}><Input value={name} disabled={readOnly} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Description')}><Input value={description} disabled={readOnly} onChange={(e) => setDescription(e.target.value)} /></Field>
        </div>
        <div className="space-y-2">
          {fields.map((f, i) => (
            <div key={i} className="space-y-2 rounded-lg border p-3">
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-[1fr_10rem_8rem_auto]">
                <Input placeholder={tx('Label')} value={f.label} disabled={readOnly} onChange={(e) => set(i, { label: e.target.value })} />
                <OptionalSelect value={f.type} disabled={readOnly} onChange={(v) => set(i, { type: (v || 'text') as WorksheetFieldType })} placeholder={tx('Type')} options={toOptions(FIELD_TYPE_LABEL)} />
                <Input placeholder={tx('key')} className="font-mono text-xs" value={f.key} disabled={readOnly || !!t?.fields.some((x) => x.key === f.key && f.key)} onChange={(e) => set(i, { key: e.target.value })} />
                {!readOnly && (
                  <div className="flex gap-1">
                    <Button variant="ghost" size="icon" aria-label={tx('Up')} disabled={i === 0} onClick={() => move(i, -1)}><ArrowUp className="h-4 w-4" /></Button>
                    <Button variant="ghost" size="icon" aria-label={tx('Down')} disabled={i === fields.length - 1} onClick={() => move(i, 1)}><ArrowDown className="h-4 w-4" /></Button>
                    <Button variant="ghost" size="icon" aria-label={tx('Remove')} onClick={() => setFields(fields.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
                  </div>
                )}
              </div>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                {f.type === 'number' && (
                  <>
                    <Input placeholder={tx('Min (pass)')} inputMode="decimal" value={f.min} disabled={readOnly} onChange={(e) => set(i, { min: e.target.value })} />
                    <Input placeholder={tx('Max (pass)')} inputMode="decimal" value={f.max} disabled={readOnly} onChange={(e) => set(i, { max: e.target.value })} />
                    <Input placeholder={tx('Decimals')} inputMode="numeric" value={f.decimals} disabled={readOnly} onChange={(e) => set(i, { decimals: e.target.value })} />
                  </>
                )}
                {f.type === 'select' && (
                  <>
                    <Input className="sm:col-span-2" placeholder={tx('Options, comma separated')} value={f.options} disabled={readOnly} onChange={(e) => set(i, { options: e.target.value })} />
                    <Input className="sm:col-span-2" placeholder={tx('Passing options (empty = any)')} value={f.passValues} disabled={readOnly} onChange={(e) => set(i, { passValues: e.target.value })} />
                  </>
                )}
                {f.type === 'checkbox' && (
                  <OptionalSelect value={f.expected} disabled={readOnly} onChange={(v) => set(i, { expected: v as Draft['expected'] })} placeholder={tx('Passes when…')} noneLabel={tx('Any answer passes')}
                    options={[{ value: 'true', label: tx('Passes when ticked') }, { value: 'false', label: tx('Passes when not ticked') }]} />
                )}
                {f.type === 'text' && <Input placeholder={tx('Max length')} inputMode="numeric" value={f.maxLength} disabled={readOnly} onChange={(e) => set(i, { maxLength: e.target.value })} />}
                <OptionalSelect value={f.showIfField} disabled={readOnly || i === 0} onChange={(v) => set(i, { showIfField: v })} placeholder={tx('Always shown')} noneLabel={tx('Always shown')}
                  options={fields.slice(0, i).filter((x) => x.key || x.label).map((x, j) => ({ value: x.key || slug(x.label) || `field_${j + 1}`, label: tx('Show if {{f}} =', { f: x.label || x.key }) }))} />
                {f.showIfField && <Input placeholder={tx('Value')} value={f.showIfEquals} disabled={readOnly} onChange={(e) => set(i, { showIfEquals: e.target.value })} />}
                <div className="flex items-center"><Toggle checked={f.required} onChange={(v) => !readOnly && set(i, { required: v })} label={tx('Required')} /></div>
              </div>
            </div>
          ))}
          {!readOnly && <Button variant="outline" size="sm" onClick={add}><Plus className="mr-1 h-4 w-4" /> {tx('Add field')}</Button>}
        </div>
        {t && !readOnly && <Toggle checked={isActive} onChange={setIsActive} label={tx('Active')} />}
        {!!t?.versions?.length && (
          <div className="text-xs text-muted-foreground">
            {tx('Versions')}: {t.versions.map((v) => `v${v.version} (${dateTime(v.createdAt)}, ${v.checkCount} ${tx('checks')})`).join(' · ')}
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{readOnly ? tx('Close') : tx('Cancel')}</Button>
          {!readOnly && <Button onClick={submit} disabled={!name.trim() || !fields.length || fields.some((f) => !f.label.trim()) || save.isPending}>{tx('Save')}</Button>}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

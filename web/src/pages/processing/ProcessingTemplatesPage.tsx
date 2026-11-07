import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, CopyPlus, Layers, Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { useLocationOptions } from '@/features/manufacturing/options';
import { apiMessage, notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import {
  ALLOCATION_LABEL, GENEALOGY_LABEL, OUTPUT_ROLE_LABEL, VALUATION_LABEL, useSaveTemplate, useTemplate, useTemplateAction, useTemplates, type OutputRole, type Template,
} from '@/features/processing/api';
import { Field, NumberInput, ProductPicker, Section, TemplateStatusPill, numberOf, selectClass } from './processing-ui';

/** The steps a business runs: each a versioned recipe of one operation. */
export function ProcessingTemplatesPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data, isLoading, isError } = useTemplates();
  const columns: Column<Template>[] = [
    { key: 'name', header: tx('Step'), render: (t) => <div><div className="font-medium text-primary">{t.name}</div><div className="text-xs text-muted-foreground">{t.code} · v{t.version}</div></div> },
    { key: 'in', header: tx('Takes'), render: (t) => t.inputs.filter((i) => i.role === 'traceable').map((i) => i.productName).join(', ') },
    { key: 'out', header: tx('Makes'), render: (t) => t.outputs.map((o) => o.productName).join(', ') },
    { key: 'yield', header: tx('Expected yield'), className: 'text-right', render: (t) => `${Number(t.expectedYieldPct)}%` },
    { key: 'status', header: tx('Status'), render: (t) => <TemplateStatusPill status={t.status} /> },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Layers} title={tx('Processing Steps')} description={tx('Set up once, then every job follows it. Changing an active step makes a new version, so past jobs keep the recipe they ran on.')}
        actions={has(PERMISSIONS.processing.create) && <Button onClick={() => navigate('/manufacturing/processing/templates/new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New step')}</Button>} />
      <ListCard>
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(t) => t.id} onRowClick={(t) => navigate(`/manufacturing/processing/templates/${t.id}`)} cellClassName="py-2.5 px-4"
          emptyMessage={isError ? tx('Could not load the steps. Try again.') : tx('No step yet. Create the first one, such as cutting or polishing.')} />
      </ListCard>
    </div>
  );
}

interface InLine { key: number; productId: string; productLabel: string; role: string; genealogyBasis: string; basisFactor: string }
interface OutLine { key: number; productId: string; productLabel: string; role: string; expectedPct: string; valuation: string; nrvUnitValue: string; marketValue: string; basisFactor: string; stocked: boolean }
let seq = 0;
const nextKey = () => { seq += 1; return seq; };
const newIn = (): InLine => ({ key: nextKey(), productId: '', productLabel: '', role: 'traceable', genealogyBasis: 'mass', basisFactor: '' });
const newOut = (role = 'main'): OutLine => ({ key: nextKey(), productId: '', productLabel: '', role, expectedPct: '', valuation: role === 'waste' ? 'zero' : 'allocated', nrvUnitValue: '', marketValue: '', basisFactor: '', stocked: true });

/** Create or edit a draft; view an active or archived version, and start its next version. */
export function ProcessingTemplateEditorPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const existing = useTemplate(id);
  const locations = useLocationOptions();
  const save = useSaveTemplate();
  const action = useTemplateAction();
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [allocationMethod, setAllocationMethod] = useState('market_value');
  const [yieldBasisUnit, setYieldBasisUnit] = useState('t');
  const [expectedYieldPct, setExpectedYieldPct] = useState('100');
  const [qcGate, setQcGate] = useState(false);
  const [inputLocation, setInputLocation] = useState('');
  const [outputLocation, setOutputLocation] = useState('');
  const [inputs, setInputs] = useState<InLine[]>([newIn()]);
  const [outputs, setOutputs] = useState<OutLine[]>([newOut()]);
  const t = existing.data;
  const editable = !t || t.status === 'draft';
  const canEdit = has(PERMISSIONS.processing.create);

  useEffect(() => {
    if (!t) return;
    setCode(t.code); setName(t.name); setDescription(t.description ?? ''); setAllocationMethod(t.allocationMethod); setYieldBasisUnit(t.yieldBasisUnit);
    setExpectedYieldPct(String(Number(t.expectedYieldPct))); setQcGate(t.qcGate); setInputLocation(t.defaultInputLocationId ?? ''); setOutputLocation(t.defaultOutputLocationId ?? '');
    setInputs(t.inputs.map((i) => ({ key: nextKey(), productId: i.productId, productLabel: i.productName, role: i.role, genealogyBasis: i.genealogyBasis, basisFactor: i.basisFactor ?? '' })));
    setOutputs(t.outputs.map((o) => ({ key: nextKey(), productId: o.productId, productLabel: o.productName, role: o.role, expectedPct: String(Number(o.expectedPct)), valuation: o.valuation, nrvUnitValue: o.nrvUnitValue ?? '', marketValue: o.marketValue ?? '', basisFactor: o.basisFactor ?? '', stocked: o.stocked })));
  }, [t?.id, t?.status]); // eslint-disable-line react-hooks/exhaustive-deps

  const mixed = [...inputs, ...outputs].some((l) => l.basisFactor && Number(l.basisFactor) !== 1);
  const body = () => ({
    code: code.trim(), name: name.trim(), description: description.trim() || undefined, allocationMethod, yieldBasisUnit: yieldBasisUnit.trim() || undefined, expectedYieldPct: Number(expectedYieldPct), qcGate,
    defaultInputLocationId: inputLocation || undefined, defaultOutputLocationId: outputLocation || undefined,
    inputs: inputs.filter((i) => i.productId).map((i) => ({ productId: i.productId, role: i.role, genealogyBasis: i.genealogyBasis, basisFactor: numberOf(i.basisFactor) })),
    outputs: outputs.filter((o) => o.productId).map((o) => ({ productId: o.productId, role: o.role, expectedPct: Number(o.expectedPct) || 0, valuation: o.valuation, nrvUnitValue: numberOf(o.nrvUnitValue), marketValue: numberOf(o.marketValue), basisFactor: numberOf(o.basisFactor), stocked: o.stocked })),
  });
  const saveDraft = async () => {
    try {
      const saved = await save.mutateAsync({ id, body: body() });
      notify.success(tx('Saved'));
      if (!id) navigate(`/manufacturing/processing/templates/${saved.id}`, { replace: true });
      return saved;
    } catch (err) { notify.error(apiMessage(err, tx('Could not save the step'))); return null; }
  };
  const activate = async () => {
    const saved = await saveDraft();
    if (!saved) return;
    try { await action.mutateAsync({ id: saved.id, action: 'activate' }); notify.success(tx('Active. New jobs can use this step.')); } catch (err) { notify.error(apiMessage(err, tx('Could not activate'))); }
  };
  const newVersion = async () => {
    try {
      const copy = await action.mutateAsync({ id: id as string, action: 'new-version' });
      navigate(`/manufacturing/processing/templates/${copy.id}`);
    } catch (err) { notify.error(apiMessage(err, tx('Could not create the version'))); }
  };
  const archive = async () => {
    try { await action.mutateAsync({ id: id as string, action: 'archive' }); notify.success(tx('Archived')); } catch (err) { notify.error(apiMessage(err, tx('Could not archive'))); }
  };
  const ready = code.trim() && name.trim() && inputs.some((i) => i.productId) && outputs.some((o) => o.productId);
  const updateIn = (key: number, patch: Partial<InLine>) => setInputs((rows) => rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  const updateOut = (key: number, patch: Partial<OutLine>) => setOutputs((rows) => rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));

  return (
    <div className="mx-auto max-w-4xl space-y-4">
      <Link to="/manufacturing/processing/templates" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"><ArrowLeft className="h-4 w-4" /> {tx('Processing Steps')}</Link>
      <ListPageHeader icon={Layers} title={t ? `${t.name} v${t.version}` : tx('New processing step')} description={editable ? tx('Lot-tracked outputs must block the issue when stock runs out. Set this on the product.') : tx('This version is locked. Past jobs keep it. Create a new version to change it.')}
        actions={(
          <div className="flex flex-wrap items-center gap-2">
            {t && <TemplateStatusPill status={t.status} />}
            {t && !editable && canEdit && <Button variant="outline" onClick={newVersion}><CopyPlus className="mr-1.5 h-4 w-4" /> {tx('New version')}</Button>}
            {t?.status === 'active' && canEdit && <Button variant="outline" onClick={archive}>{tx('Archive')}</Button>}
          </div>
        )} />
      <fieldset disabled={!editable || !canEdit} className="space-y-4">
        <Section title={tx('Step')}>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label={tx('Code')}><Input value={code} disabled={!!t} onChange={(e) => setCode(e.target.value)} placeholder="POLISH" /></Field>
            <Field label={tx('Name')}><Input value={name} onChange={(e) => setName(e.target.value)} placeholder={tx('Polishing')} /></Field>
            <Field label={tx('Expected yield (%)')} hint={tx('Loss up to this raises unit cost. Loss above it is written off.')}><NumberInput value={expectedYieldPct} onChange={setExpectedYieldPct} /></Field>
            <Field label={tx('Yield measured in')} hint={tx('The common unit for input, output and loss, such as t')}><Input value={yieldBasisUnit} onChange={(e) => setYieldBasisUnit(e.target.value)} /></Field>
            <Field label={tx('Share cost between outputs')} hint={mixed ? tx('Different units cannot be added, so choose weight or market value.') : undefined}>
              <select className={selectClass} value={allocationMethod} onChange={(e) => setAllocationMethod(e.target.value)}>{Object.entries(ALLOCATION_LABEL).map(([k, v]) => <option key={k} value={k} disabled={mixed && k === 'quantity'}>{v}</option>)}</select>
            </Field>
            <Field label={tx('Default input location')}><select className={selectClass} value={inputLocation} onChange={(e) => setInputLocation(e.target.value)}><option value="">—</option>{(locations.data ?? []).map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}</select></Field>
            <Field label={tx('Default output location')}><select className={selectClass} value={outputLocation} onChange={(e) => setOutputLocation(e.target.value)}><option value="">—</option>{(locations.data ?? []).map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}</select></Field>
            <label className="flex items-center gap-2 self-end pb-2 text-sm"><input type="checkbox" className="rounded" checked={qcGate} onChange={(e) => setQcGate(e.target.checked)} />{tx('Hold output for a quality check')}</label>
            <Field label={tx('Description')} className="sm:col-span-2"><Input value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
          </div>
        </Section>
        <Section title={tx('Takes')} actions={editable && <Button type="button" size="sm" variant="outline" onClick={() => setInputs((r) => [...r, newIn()])}><Plus className="mr-1 h-3.5 w-3.5" /> {tx('Add input')}</Button>}>
          <div className="space-y-3">{inputs.map((i) => (
            <div key={i.key} className="grid items-end gap-3 rounded-lg border p-3 md:grid-cols-[minmax(0,1.6fr)_9rem_9rem_7rem_auto]">
              <Field label={tx('Product')}><ProductPicker value={i.productId} label={i.productLabel} onChange={(pid, label) => updateIn(i.key, { productId: pid, productLabel: label })} /></Field>
              <Field label={tx('Role')}><select className={selectClass} value={i.role} onChange={(e) => updateIn(i.key, { role: e.target.value })}><option value="traceable">{tx('Traced to its lot')}</option><option value="consumable">{tx('Consumable')}</option></select></Field>
              <Field label={tx('Lot trace')}><select className={selectClass} value={i.genealogyBasis} onChange={(e) => updateIn(i.key, { genealogyBasis: e.target.value })}>{Object.entries(GENEALOGY_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
              <Field label={tx('Factor to {{unit}}', { unit: yieldBasisUnit })}><NumberInput value={i.basisFactor} onChange={(v) => updateIn(i.key, { basisFactor: v })} placeholder="1" /></Field>
              <Button type="button" variant="ghost" size="icon" className="h-9 w-9" aria-label={tx('Remove input')} disabled={inputs.length <= 1} onClick={() => setInputs((r) => r.filter((x) => x.key !== i.key))}><Trash2 className="h-4 w-4" /></Button>
            </div>
          ))}</div>
        </Section>
        <Section title={tx('Makes')} actions={editable && <Button type="button" size="sm" variant="outline" onClick={() => setOutputs((r) => [...r, newOut('co_product')])}><Plus className="mr-1 h-3.5 w-3.5" /> {tx('Add output')}</Button>}>
          <div className="space-y-3">{outputs.map((o) => (
            <div key={o.key} className="grid items-end gap-3 rounded-lg border p-3 md:grid-cols-3 lg:grid-cols-[minmax(0,1.6fr)_9rem_6rem_9rem_7rem_auto]">
              <Field label={tx('Product')}><ProductPicker value={o.productId} label={o.productLabel} onChange={(pid, label) => updateOut(o.key, { productId: pid, productLabel: label })} /></Field>
              <Field label={tx('Role')}><select className={selectClass} value={o.role} onChange={(e) => updateOut(o.key, { role: e.target.value as OutputRole })}>{Object.entries(OUTPUT_ROLE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
              <Field label={tx('Expected %')}><NumberInput value={o.expectedPct} onChange={(v) => updateOut(o.key, { expectedPct: v })} /></Field>
              <Field label={tx('Value')}><select className={selectClass} value={o.valuation} disabled={o.role === 'main'} onChange={(e) => updateOut(o.key, { valuation: e.target.value })}>{Object.entries(VALUATION_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
              <Field label={tx('Factor to {{unit}}', { unit: yieldBasisUnit })}><NumberInput value={o.basisFactor} onChange={(v) => updateOut(o.key, { basisFactor: v })} placeholder="1" /></Field>
              <Button type="button" variant="ghost" size="icon" className="h-9 w-9" aria-label={tx('Remove output')} disabled={outputs.length <= 1} onClick={() => setOutputs((r) => r.filter((x) => x.key !== o.key))}><Trash2 className="h-4 w-4" /></Button>
            </div>
          ))}</div>
        </Section>
      </fieldset>
      {editable && canEdit && (
        <div className="sticky bottom-0 z-10 -mx-1 flex justify-end gap-2 border-t bg-background/95 p-3 backdrop-blur">
          <Button variant="outline" disabled={!ready || save.isPending} onClick={saveDraft}>{tx('Save draft')}</Button>
          <Button disabled={!ready || save.isPending || action.isPending} onClick={activate}>{tx('Save and activate')}</Button>
        </div>
      )}
    </div>
  );
}

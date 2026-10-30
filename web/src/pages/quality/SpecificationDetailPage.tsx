import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Archive, CheckCircle2, GitBranch, Plus, RefreshCw, Save, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  KIND_LABEL, useSpecAction, useSpecification, useUpdateSpecification, type ParamKind, type SpecParameter,
} from '@/features/quality/api';
import { Field, Item, OptionalSelect, SpecStatusPill } from './quality-ui';
import { num, optNum, toOptions } from './quality-format';

interface EnvelopeRow { sieveMm: string; minPassingPct: string; maxPassingPct: string }
interface ParamRow {
  key: string;
  code: string;
  name: string;
  kind: ParamKind;
  unit: string;
  testMethod: string;
  minValue: string;
  maxValue: string;
  targetValue: string;
  expectedText: string;
  isCritical: boolean;
  envelope: EnvelopeRow[];
}

const s = (v: unknown) => (v === null || v === undefined ? '' : String(v));

function toRow(p: SpecParameter): ParamRow {
  return {
    key: p.id, code: p.code, name: p.name, kind: p.kind, unit: s(p.unit), testMethod: s(p.testMethod),
    minValue: s(p.minValue), maxValue: s(p.maxValue), targetValue: s(p.targetValue), expectedText: s(p.expectedText),
    isCritical: p.isCritical,
    envelope: (p.sieveEnvelope ?? []).map((e) => ({ sieveMm: s(e.sieveMm), minPassingPct: s(e.minPassingPct), maxPassingPct: s(e.maxPassingPct) })),
  };
}

const blank = (): ParamRow => ({
  key: Math.random().toString(36).slice(2), code: '', name: '', kind: 'numeric', unit: '', testMethod: '', minValue: '',
  maxValue: '', targetValue: '', expectedText: '', isCritical: true, envelope: [],
});

/** Specification version detail: parameters (editable on drafts), activate / revise / archive, version history. */
export function SpecificationDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: spec, isLoading, isError, refetch } = useSpecification(id);
  const has = useAuthStore((st) => st.hasPermission);
  const update = useUpdateSpecification();
  const action = useSpecAction();
  const [rows, setRows] = useState<ParamRow[]>([]);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    if (spec?.parameters) {
      setRows(spec.parameters.map(toRow));
      setDirty(false);
    }
  }, [spec]);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !spec) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('Specification not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const canEdit = has(PERMISSIONS.quality.configure);
  const draft = spec.status === 'draft';
  const editable = canEdit && draft;
  const change = (key: string, patch: Partial<ParamRow>) => {
    setRows((rs) => rs.map((r) => (r.key === key ? { ...r, ...patch } : r)));
    setDirty(true);
  };
  const save = async () => {
    try {
      await update.mutateAsync({
        id: spec.id,
        body: {
          parameters: rows.map((r, i) => ({
            code: r.code.trim(), name: r.name.trim(), kind: r.kind, unit: r.unit.trim() || undefined,
            testMethod: r.testMethod.trim() || undefined,
            minValue: r.kind === 'numeric' ? optNum(r.minValue) : undefined,
            maxValue: r.kind === 'numeric' ? optNum(r.maxValue) : undefined,
            targetValue: r.kind === 'numeric' ? optNum(r.targetValue) : undefined,
            expectedText: r.expectedText.trim() || undefined, isCritical: r.isCritical, sortOrder: i,
            sieveEnvelope: r.kind === 'sieve'
              ? r.envelope.filter((e) => e.sieveMm.trim() !== '').map((e) => ({
                sieveMm: Number(e.sieveMm), minPassingPct: optNum(e.minPassingPct), maxPassingPct: optNum(e.maxPassingPct),
              }))
              : undefined,
          })),
        },
      });
      notify.success(tx('Parameters saved'));
      setDirty(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the parameters')));
    }
  };
  const run = async (a: 'activate' | 'revise' | 'archive') => {
    try {
      const out = await action.mutateAsync({ id: spec.id, action: a });
      notify.success(a === 'activate' ? tx('Specification activated') : a === 'revise' ? tx('New draft version created') : tx('Specification archived'));
      if (a === 'revise') navigate(`/quality/specifications/${out.id}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not update the specification')));
    }
  };
  const validRows = rows.every((r) => r.code.trim() && r.name.trim());

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/quality/specifications')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold">{spec.code} · v{spec.version}</h1>
            <SpecStatusPill status={spec.status} />
          </div>
          <p className="text-sm text-muted-foreground">{spec.name}</p>
        </div>
        {canEdit && (
          <div className="flex flex-wrap gap-2">
            {draft && <Button onClick={() => run('activate')} disabled={dirty || action.isPending}><CheckCircle2 className="mr-1.5 h-4 w-4" /> {tx('Activate')}</Button>}
            {!draft && spec.status !== 'archived' && <Button variant="outline" onClick={() => run('revise')} disabled={action.isPending}><GitBranch className="mr-1.5 h-4 w-4" /> {tx('New version')}</Button>}
            {spec.status !== 'archived' && <Button variant="ghost" onClick={() => run('archive')} disabled={action.isPending}><Archive className="mr-1.5 h-4 w-4" /> {tx('Archive')}</Button>}
          </div>
        )}
      </div>

      <Card>
        <CardContent className="pt-6">
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Item label={tx('Product')}>{spec.productName}</Item>
            <Item label={tx('Grade')}>{spec.grade}</Item>
            <Item label={tx('Standard')}>{spec.standard}</Item>
            <Item label={tx('Effective from')}>{spec.effectiveFrom ? date(spec.effectiveFrom) : null}</Item>
            <Item label={tx('Activated')}>{spec.activatedAt ? dateTime(spec.activatedAt) : null}</Item>
          </dl>
          {spec.notes && <p className="mt-3 whitespace-pre-wrap text-sm">{spec.notes}</p>}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle className="text-base">{tx('Parameters')}</CardTitle>
          {editable && (
            <div className="flex gap-2">
              <Button size="sm" variant="outline" onClick={() => { setRows((rs) => [...rs, blank()]); setDirty(true); }}><Plus className="mr-1 h-4 w-4" /> {tx('Add parameter')}</Button>
              <Button size="sm" onClick={save} disabled={!dirty || !validRows || update.isPending}><Save className="mr-1 h-4 w-4" /> {tx('Save')}</Button>
            </div>
          )}
        </CardHeader>
        <CardContent className="space-y-3">
          {rows.length === 0 && <p className="text-sm text-muted-foreground">{tx('No parameters yet.')}</p>}
          {!editable && rows.length > 0 && <ReadOnlyParameters rows={rows} />}
          {editable && rows.map((r) => (
            <div key={r.key} className="space-y-3 rounded-lg border p-3">
              <div className="grid grid-cols-2 gap-3 md:grid-cols-6">
                <Field label={tx('Code *')}><Input value={r.code} onChange={(e) => change(r.key, { code: e.target.value })} /></Field>
                <Field label={tx('Name *')} className="md:col-span-2"><Input value={r.name} onChange={(e) => change(r.key, { name: e.target.value })} /></Field>
                <Field label={tx('Kind')}>
                  <OptionalSelect value={r.kind} onChange={(v) => change(r.key, { kind: (v || 'numeric') as ParamKind })} placeholder={tx('Kind')} options={toOptions(KIND_LABEL)} />
                </Field>
                <Field label={tx('Unit')}><Input value={r.unit} onChange={(e) => change(r.key, { unit: e.target.value })} /></Field>
                <Field label={tx('Test method')}><Input value={r.testMethod} onChange={(e) => change(r.key, { testMethod: e.target.value })} /></Field>
                {r.kind === 'numeric' && (
                  <>
                    <Field label={tx('Minimum')}><Input inputMode="decimal" value={r.minValue} onChange={(e) => change(r.key, { minValue: e.target.value })} /></Field>
                    <Field label={tx('Maximum')}><Input inputMode="decimal" value={r.maxValue} onChange={(e) => change(r.key, { maxValue: e.target.value })} /></Field>
                    <Field label={tx('Target')}><Input inputMode="decimal" value={r.targetValue} onChange={(e) => change(r.key, { targetValue: e.target.value })} /></Field>
                  </>
                )}
                {(r.kind === 'boolean' || r.kind === 'text') && (
                  <Field label={tx('Expected value')}><Input value={r.expectedText} placeholder={r.kind === 'boolean' ? 'true' : ''} onChange={(e) => change(r.key, { expectedText: e.target.value })} /></Field>
                )}
                <label className="flex items-end gap-2 pb-2 text-sm">
                  <input type="checkbox" checked={r.isCritical} onChange={(e) => change(r.key, { isCritical: e.target.checked })} />
                  {tx('Critical')}
                </label>
                <div className="flex items-end justify-end">
                  <Button variant="ghost" size="icon" aria-label={tx('Remove')} onClick={() => { setRows((rs) => rs.filter((x) => x.key !== r.key)); setDirty(true); }}><Trash2 className="h-4 w-4" /></Button>
                </div>
              </div>
              {r.kind === 'sieve' && <EnvelopeEditor rows={r.envelope} onChange={(envelope) => change(r.key, { envelope })} />}
            </div>
          ))}
        </CardContent>
      </Card>

      {(spec.versions ?? []).length > 1 && (
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('Versions')}</CardTitle></CardHeader>
          <CardContent>
            <ul className="divide-y text-sm">
              {(spec.versions ?? []).map((v) => (
                <li key={v.id} className="flex items-center justify-between gap-2 py-2">
                  <Link className={v.id === spec.id ? 'font-semibold' : 'text-primary hover:underline'} to={`/quality/specifications/${v.id}`}>v{v.version}</Link>
                  <span className="text-muted-foreground">{v.activatedAt ? dateTime(v.activatedAt) : dateTime(v.createdAt)}</span>
                  <SpecStatusPill status={v.status} />
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function ReadOnlyParameters({ rows }: { rows: ParamRow[] }) {
  return (
    <div className="space-y-3">
      {rows.map((r) => (
        <div key={r.key} className="rounded-lg border p-3 text-sm">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-semibold">{r.code}</span><span>{r.name}</span>
            <span className="text-xs text-muted-foreground">{KIND_LABEL[r.kind]}{r.unit ? ` · ${r.unit}` : ''}{r.testMethod ? ` · ${r.testMethod}` : ''}</span>
            {!r.isCritical && <span className="rounded bg-muted px-1.5 text-xs">{tx('Non-critical')}</span>}
          </div>
          {r.kind === 'numeric' && (
            <p className="mt-1 text-muted-foreground">{tx('Limits')}: {r.minValue ? num(r.minValue, 4) : '—'} – {r.maxValue ? num(r.maxValue, 4) : '—'}{r.targetValue ? ` · ${tx('Target')} ${num(r.targetValue, 4)}` : ''}</p>
          )}
          {(r.kind === 'boolean' || r.kind === 'text') && r.expectedText && <p className="mt-1 text-muted-foreground">{tx('Expected value')}: {r.expectedText}</p>}
          {r.kind === 'sieve' && (
            <table className="mt-2 w-full max-w-md text-xs">
              <thead><tr className="text-left text-muted-foreground"><th>{tx('Sieve (mm)')}</th><th className="text-right">{tx('Min % passing')}</th><th className="text-right">{tx('Max % passing')}</th></tr></thead>
              <tbody>{r.envelope.map((e) => <tr key={e.sieveMm}><td>{num(e.sieveMm, 3)}</td><td className="text-right">{e.minPassingPct || '—'}</td><td className="text-right">{e.maxPassingPct || '—'}</td></tr>)}</tbody>
            </table>
          )}
        </div>
      ))}
    </div>
  );
}

function EnvelopeEditor({ rows, onChange }: { rows: EnvelopeRow[]; onChange: (rows: EnvelopeRow[]) => void }) {
  const set = (i: number, patch: Partial<EnvelopeRow>) => onChange(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  return (
    <div className="space-y-2 rounded-md bg-muted/40 p-2">
      <p className="text-xs font-medium text-muted-foreground">{tx('Grading envelope (largest sieve first)')}</p>
      {rows.map((r, i) => (
        <div key={i} className="grid grid-cols-[1fr_1fr_1fr_auto] gap-2">
          <Input inputMode="decimal" aria-label={tx('Sieve (mm)')} placeholder={tx('Sieve (mm)')} value={r.sieveMm} onChange={(e) => set(i, { sieveMm: e.target.value })} />
          <Input inputMode="decimal" aria-label={tx('Min % passing')} placeholder={tx('Min % passing')} value={r.minPassingPct} onChange={(e) => set(i, { minPassingPct: e.target.value })} />
          <Input inputMode="decimal" aria-label={tx('Max % passing')} placeholder={tx('Max % passing')} value={r.maxPassingPct} onChange={(e) => set(i, { maxPassingPct: e.target.value })} />
          <Button variant="ghost" size="icon" aria-label={tx('Remove')} onClick={() => onChange(rows.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
        </div>
      ))}
      <Button size="sm" variant="outline" onClick={() => onChange([...rows, { sieveMm: '', minPassingPct: '', maxPassingPct: '' }])}><Plus className="mr-1 h-4 w-4" /> {tx('Add sieve')}</Button>
    </div>
  );
}

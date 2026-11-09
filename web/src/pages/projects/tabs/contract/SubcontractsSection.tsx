import { useState } from 'react';
import { Calculator, CheckCircle2, Plus, XCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { useSupplierOptions } from '@/features/procurement/api';
import type { Workspace } from '@/features/projects/api';
import { selectClass, shortDate, todayIso } from '@/features/projects/ui';
import {
  RETENTION_CONDITIONS, conditionLabel, contractApi, opt, useContractMutation, useSubcontracts,
  type RetentionCondition, type Subcontract, type Valuation,
} from '@/features/projects/contract-api';
import { Empty, Field, FormDialog, Money, Section, StatusPill, Table, isAmount, num, pct, td, tdRight, type ContractPerms } from './shared';

/**
 * Subcontracts on a WBS element and their cumulative valuations (gross to project WIP, retention payable held, net
 * billed to the subcontractor). Held subcontractor retentions are in the Retentions section.
 */
type SubcontractEntry = { sub: Subcontract; valuations: Valuation[] };

export function SubcontractsSection({ ws, perms }: { ws: Workspace; perms: ContractPerms }) {
  const { data: subcontracts = [] } = useSubcontracts(ws.id);
  const entries: SubcontractEntry[] = subcontracts.map((s) => ({ sub: s, valuations: s.valuations ?? [] }));
  const empty = { supplierId: '', wbsId: '', scope: '', value: '', retentionPct: '', retentionCondition: 'completion_certificate' as RetentionCondition };
  const [open, setOpen] = useState(false);
  const [f, setF] = useState(empty);
  const [search, setSearch] = useState('');
  const { data: suppliers = [] } = useSupplierOptions(search);
  const [valuing, setValuing] = useState<SubcontractEntry | null>(null);
  const [v, setV] = useState({ cumulativeValue: '', date: todayIso() });

  const create = useContractMutation<void, Subcontract>(() => contractApi.createSubcontract(ws.id, {
    supplierId: f.supplierId, wbsId: f.wbsId, scope: opt(f.scope), value: f.value.trim(),
    retentionPct: opt(f.retentionPct), retentionCondition: f.retentionCondition,
  }), { success: tx('Subcontract created') });
  const value = useContractMutation<void, Valuation>(() => contractApi.valuation(valuing!.sub.id, {
    cumulativeValue: v.cumulativeValue.trim(), date: opt(v.date),
  }), { success: tx('Valuation posted') });
  const complete = useContractMutation<string>((id) => contractApi.completeSubcontract(id), { success: tx('Subcontract completed') });
  const cancel = useContractMutation<string>((id) => contractApi.cancelSubcontract(id), { success: tx('Subcontract cancelled') });

  const valid = !!f.supplierId && !!f.wbsId && isAmount(f.value) && num(f.value) > 0
    && (f.retentionPct === '' || (num(f.retentionPct) >= 0 && num(f.retentionPct) < 100));
  const valued = (e: SubcontractEntry) => e.valuations.reduce((m, x) => Math.max(m, num(x.cumulativeValue)), 0);
  const supplierName = (s: Subcontract) => s.supplierName ?? suppliers.find((x) => x.id === s.supplierId)?.name ?? s.supplierId.slice(0, 8);
  const wbsCode = (id: string) => ws.wbs.find((w) => w.id === id)?.code ?? '';
  const vValid = !!valuing && isAmount(v.cumulativeValue) && num(v.cumulativeValue) > valued(valuing)
    && num(v.cumulativeValue) <= num(valuing.sub.value);

  return (
    <Section
      title={tx('Subcontracts')}
      hint={tx('Valuations are cumulative: each posts the increase to project cost, holds retention and bills the net.')}
      action={perms.manage && (
        <Button size="sm" disabled={ws.wbs.length === 0} title={ws.wbs.length === 0 ? tx('Add a WBS element first') : undefined}
          onClick={() => { setF(empty); setOpen(true); }}>
          <Plus className="mr-1.5 h-4 w-4" />{tx('New subcontract')}
        </Button>
      )}
    >
      {entries.length === 0 ? (
        <Empty>{tx('No subcontracts yet; their held retentions are listed under Retentions.')}</Empty>
      ) : (
        <div className="space-y-4">
          {entries.map((e) => (
            <div key={e.sub.id} className="rounded-lg border p-3">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div>
                  <p className="font-medium"><span className="font-mono text-xs text-muted-foreground">{e.sub.number}</span> {supplierName(e.sub)}</p>
                  <p className="text-xs text-muted-foreground">
                    {wbsCode(e.sub.wbsId)}{e.sub.scope && ` · ${e.sub.scope}`} · {tx('Retention')} {pct(e.sub.retentionPct)} · {conditionLabel(e.sub.retentionCondition)}
                  </p>
                </div>
                <div className="flex items-center gap-3 text-sm">
                  <span>{tx('Valued')} <Money value={valued(e)} /> / <Money value={e.sub.value} /></span>
                  <StatusPill status={e.sub.status} />
                  {perms.post && e.sub.status === 'active' && (
                    <Button size="sm" variant="outline" onClick={() => { setV({ cumulativeValue: '', date: todayIso() }); setValuing(e); }}>
                      <Calculator className="mr-1 h-3.5 w-3.5" />{tx('Value')}
                    </Button>
                  )}
                  {perms.manage && e.sub.status === 'active' && (
                    <Button size="sm" variant="outline" disabled={complete.isPending}
                      onClick={() => window.confirm(tx('Complete subcontract {{number}}? No further valuations can be posted; held retention stays until released.', { number: e.sub.number })) && complete.mutate(e.sub.id)}>
                      <CheckCircle2 className="mr-1 h-3.5 w-3.5" />{tx('Complete')}
                    </Button>
                  )}
                  {perms.manage && e.sub.status === 'active' && e.valuations.length === 0 && (
                    <Button size="sm" variant="ghost" className="text-rose-600 hover:text-rose-700" disabled={cancel.isPending}
                      onClick={() => window.confirm(tx('Cancel subcontract {{number}}?', { number: e.sub.number })) && cancel.mutate(e.sub.id)}>
                      <XCircle className="mr-1 h-3.5 w-3.5" />{tx('Cancel')}
                    </Button>
                  )}
                </div>
              </div>
              {e.valuations.length > 0 && (
                <div className="mt-3">
                  <Table head={[tx('Number'), tx('Date'), { label: tx('Cumulative'), right: true }, { label: tx('This valuation'), right: true }, { label: tx('Retention'), right: true }, { label: tx('Net billed'), right: true }]}>
                    {e.valuations.map((x) => (
                      <tr key={x.id}>
                        <td className={cn(td, 'font-mono text-xs')}>{x.number}</td>
                        <td className={td}>{shortDate(x.valuationDate)}</td>
                        <td className={tdRight}><Money value={x.cumulativeValue} /></td>
                        <td className={tdRight}><Money value={x.thisValuation} /></td>
                        <td className={tdRight}><Money value={x.retention} /></td>
                        <td className={tdRight}><Money value={x.netAmount} /></td>
                      </tr>
                    ))}
                  </Table>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      <FormDialog
        open={open}
        onClose={() => setOpen(false)}
        title={tx('New subcontract')}
        submitLabel={tx('Create subcontract')}
        valid={valid}
        pending={create.isPending}
        onSubmit={() => create.mutate(undefined, { onSuccess: () => setOpen(false) })}
      >
        <Field label={tx('Subcontractor')} className="sm:col-span-2">
          <SearchableSelect
            value={f.supplierId}
            onValueChange={(id) => setF({ ...f, supplierId: id })}
            onQueryChange={setSearch}
            options={suppliers.map((s) => ({ value: s.id, label: s.name, code: s.code ?? undefined }))}
            placeholder={tx('Select a supplier…')}
          />
        </Field>
        <Field label={tx('WBS element')}>
          <select className={selectClass} value={f.wbsId} onChange={(e) => setF({ ...f, wbsId: e.target.value })}>
            <option value="">{tx('Select…')}</option>
            {ws.wbs.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
          </select>
        </Field>
        <Field label={tx('Subcontract value')}>
          <Input type="number" step="0.01" min={0} value={f.value} onChange={(e) => setF({ ...f, value: e.target.value })} />
        </Field>
        <Field label={tx('Scope')} className="sm:col-span-2">
          <Input value={f.scope} onChange={(e) => setF({ ...f, scope: e.target.value })} placeholder={tx('e.g. Electrical installation, block B')} />
        </Field>
        <Field label={tx('Retention %')}>
          <Input type="number" step="0.01" min={0} max={99.99} value={f.retentionPct} onChange={(e) => setF({ ...f, retentionPct: e.target.value })} />
        </Field>
        <Field label={tx('Retention release condition')}>
          <select className={selectClass} value={f.retentionCondition} onChange={(e) => setF({ ...f, retentionCondition: e.target.value as RetentionCondition })}>
            {RETENTION_CONDITIONS.map((c) => <option key={c} value={c}>{conditionLabel(c)}</option>)}
          </select>
        </Field>
      </FormDialog>

      <FormDialog
        open={!!valuing}
        onClose={() => setValuing(null)}
        title={valuing ? tx('Value subcontract {{number}}', { number: valuing.sub.number }) : ''}
        description={valuing ? tx('Already valued: {{done}} of {{total}}. The project must be active.', {
          done: valued(valuing).toFixed(2), total: num(valuing.sub.value).toFixed(2),
        }) : undefined}
        submitLabel={tx('Post valuation')}
        valid={vValid}
        pending={value.isPending}
        onSubmit={() => value.mutate(undefined, {
          onSuccess: () => setValuing(null),
        })}
      >
        <Field label={tx('Cumulative value to date')}>
          <Input type="number" step="0.01" min={0} value={v.cumulativeValue} onChange={(e) => setV({ ...v, cumulativeValue: e.target.value })} />
        </Field>
        <Field label={tx('Valuation date')}>
          <Input type="date" value={v.date} onChange={(e) => setV({ ...v, date: e.target.value })} />
        </Field>
      </FormDialog>
    </Section>
  );
}

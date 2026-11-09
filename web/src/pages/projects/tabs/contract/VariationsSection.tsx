import { useState } from 'react';
import { Check, Plus, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import type { Workspace } from '@/features/projects/api';
import { selectClass } from '@/features/projects/ui';
import {
  COST_ELEMENTS, MODIFICATIONS, contractApi, modificationLabel, opt, useContractMutation, useVariations,
  type Modification,
} from '@/features/projects/contract-api';
import { Empty, Field, FormDialog, Money, Section, StatusPill, Table, isAmount, num, td, tdRight, type ContractPerms } from './shared';

const EMPTY = { description: '', priceDelta: '', costDelta: '', wbsId: '', costElement: '', modification: 'cumulative_catch_up' as Modification };

/** Change orders: price (and optional cost) deltas; approval (by someone else) revises the budget. */
export function VariationsSection({ ws, perms }: { ws: Workspace; perms: ContractPerms }) {
  const { data = [], isLoading } = useVariations(ws.id);
  const [open, setOpen] = useState(false);
  const [f, setF] = useState(EMPTY);
  const hasCost = isAmount(f.costDelta) && num(f.costDelta) !== 0;
  const create = useContractMutation(() => contractApi.createVariation(ws.id, {
    description: f.description.trim(),
    priceDelta: f.priceDelta.trim(),
    costDelta: opt(f.costDelta),
    wbsId: hasCost ? opt(f.wbsId) : undefined,
    costElement: hasCost ? opt(f.costElement) : undefined,
    modification: f.modification,
  }), { success: tx('Variation drafted') });
  const approve = useContractMutation((id: string) => contractApi.approveVariation(id), { success: tx('Variation approved') });
  const reject = useContractMutation((id: string) => contractApi.rejectVariation(id), { success: tx('Variation rejected') });
  const valid = !!f.description.trim() && isAmount(f.priceDelta) && (!hasCost || (!!f.wbsId && !!f.costElement));
  const wbsName = (id?: string | null) => ws.wbs.find((w) => w.id === id)?.code ?? '';

  return (
    <Section
      title={tx('Variations')}
      hint={tx('Approved variations change the transaction price; a cost delta revises the approved budget.')}
      action={perms.manage && <Button size="sm" onClick={() => { setF(EMPTY); setOpen(true); }}><Plus className="mr-1.5 h-4 w-4" />{tx('New variation')}</Button>}
    >
      {isLoading ? <Skeleton className="h-24 w-full" /> : data.length === 0 ? <Empty>{tx('No variations yet.')}</Empty> : (
        <Table head={[tx('Number'), tx('Description'), tx('Treatment'), { label: tx('Price change'), right: true }, { label: tx('Cost change'), right: true }, tx('Status'), '']}>
          {data.map((v) => (
            <tr key={v.id}>
              <td className={cn(td, 'font-mono text-xs')}>{v.number}</td>
              <td className={td}>
                {v.description}
                {num(v.costDelta) !== 0 && <p className="text-xs text-muted-foreground">{wbsName(v.wbsId)} · {v.costElement}</p>}
              </td>
              <td className={td}>{modificationLabel(v.modification)}</td>
              <td className={tdRight}><Money value={v.priceDelta} /></td>
              <td className={tdRight}><Money value={v.costDelta} /></td>
              <td className={td}><StatusPill status={v.status} /></td>
              <td className={cn(td, 'whitespace-nowrap text-right')}>
                {perms.approve && v.status === 'pending' && (
                  <>
                    <Button size="sm" variant="outline" className="mr-1" disabled={approve.isPending} onClick={() => approve.mutate(v.id)}><Check className="mr-1 h-3.5 w-3.5" />{tx('Approve')}</Button>
                    <Button size="sm" variant="ghost" disabled={reject.isPending} onClick={() => reject.mutate(v.id)}><X className="mr-1 h-3.5 w-3.5" />{tx('Reject')}</Button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </Table>
      )}

      <FormDialog
        open={open}
        onClose={() => setOpen(false)}
        title={tx('New variation')}
        description={tx('A variation must be approved by someone other than its author.')}
        submitLabel={tx('Draft variation')}
        valid={valid}
        pending={create.isPending}
        onSubmit={() => create.mutate(undefined, { onSuccess: () => setOpen(false) })}
      >
        <Field label={tx('Description')} className="sm:col-span-2">
          <Input value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} placeholder={tx('e.g. Additional retaining wall')} />
        </Field>
        <Field label={tx('Price change')} hint={tx('Negative for an omission.')}>
          <Input type="number" step="0.01" value={f.priceDelta} onChange={(e) => setF({ ...f, priceDelta: e.target.value })} />
        </Field>
        <Field label={tx('Contract modification (IFRS 15.18–21)')}>
          <select className={selectClass} value={f.modification} onChange={(e) => setF({ ...f, modification: e.target.value as Modification })}>
            {MODIFICATIONS.map((m) => <option key={m} value={m}>{modificationLabel(m)}</option>)}
          </select>
        </Field>
        <Field label={tx('Cost change (optional)')}>
          <Input type="number" step="0.01" value={f.costDelta} onChange={(e) => setF({ ...f, costDelta: e.target.value })} />
        </Field>
        <div />
        {hasCost && (
          <>
            <Field label={tx('WBS element')}>
              <select className={selectClass} value={f.wbsId} onChange={(e) => setF({ ...f, wbsId: e.target.value })}>
                <option value="">{tx('Select…')}</option>
                {ws.wbs.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
              </select>
            </Field>
            <Field label={tx('Cost element')}>
              <select className={selectClass} value={f.costElement} onChange={(e) => setF({ ...f, costElement: e.target.value })}>
                <option value="">{tx('Select…')}</option>
                {COST_ELEMENTS.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            </Field>
          </>
        )}
      </FormDialog>
    </Section>
  );
}

import { useState } from 'react';
import { Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import type { Workspace } from '@/features/projects/api';
import { shortDate, todayIso } from '@/features/projects/ui';
import { contractApi, opt, useAdvances, useContractMutation, type Advance, type ContractPosition } from '@/features/projects/contract-api';
import { Empty, Field, FormDialog, Money, Section, StatusPill, Table, isAmount, num, pct, td, tdRight, type ContractPerms } from './shared';

/**
 * Customer advances (mobilisation payments): invoiced to the customer on creation and recovered from each certified
 * claim at the recovery %.
 */
export function AdvancesSection({ ws, perms, position }: { ws: Workspace; perms: ContractPerms; position?: ContractPosition }) {
  const { data: advances = [] } = useAdvances(ws.id);
  const empty = { amount: '', recoveryPct: '', date: todayIso() };
  const [open, setOpen] = useState(false);
  const [f, setF] = useState(empty);
  const create = useContractMutation<void, Advance>(() => contractApi.createAdvance(ws.id, {
    amount: f.amount.trim(), recoveryPct: f.recoveryPct.trim(), date: opt(f.date),
  }), { success: tx('Advance invoiced') });
  const valid = isAmount(f.amount) && num(f.amount) > 0 && isAmount(f.recoveryPct) && num(f.recoveryPct) > 0 && num(f.recoveryPct) <= 100;

  return (
    <Section
      title={tx('Customer advances')}
      hint={tx('An advance is invoiced now and recovered from each certified claim at its recovery percentage.')}
      action={perms.post && (
        <Button size="sm" disabled={!ws.customerId} onClick={() => { setF(empty); setOpen(true); }}><Plus className="mr-1.5 h-4 w-4" />{tx('New advance')}</Button>
      )}
    >
      {!ws.customerId && <p className="mb-3 text-xs text-amber-600">{tx('The project needs a customer for contract billing.')}</p>}
      <div className="mb-4 rounded-lg bg-muted/40 p-3">
        <p className="text-xs text-muted-foreground">{tx('Advances outstanding (not yet recovered)')}</p>
        <p className="text-xl font-semibold"><Money value={position?.advancesOutstanding} /></p>
      </div>
      {advances.length === 0 ? <Empty>{tx('No advances yet.')}</Empty> : (
        <>
          <Table head={[tx('Date'), { label: tx('Amount'), right: true }, { label: tx('Recovery %'), right: true }, { label: tx('Recovered'), right: true }, { label: tx('Outstanding'), right: true }, tx('Status')]}>
            {advances.map((a) => (
              <tr key={a.id}>
                <td className={td}>{shortDate(a.createdAt)}</td>
                <td className={tdRight}><Money value={a.amount} /></td>
                <td className={tdRight}>{pct(a.recoveryPct)}</td>
                <td className={tdRight}><Money value={a.recovered} /></td>
                <td className={tdRight}><Money value={a.outstanding ?? num(a.amount) - num(a.recovered)} /></td>
                <td className={cn(td)}><StatusPill status={a.status} /></td>
              </tr>
            ))}
          </Table>
        </>
      )}

      <FormDialog
        open={open}
        onClose={() => setOpen(false)}
        title={tx('New customer advance')}
        description={tx('Posts an invoice to the customer against the advances account.')}
        submitLabel={tx('Invoice advance')}
        valid={valid}
        pending={create.isPending}
        onSubmit={() => create.mutate(undefined, { onSuccess: () => setOpen(false) })}
      >
        <Field label={tx('Amount')}>
          <Input type="number" step="0.01" min={0} value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} />
        </Field>
        <Field label={tx('Recovery %')} hint={tx('Share of each claim withheld to recover the advance.')}>
          <Input type="number" step="0.01" min={0} max={100} value={f.recoveryPct} onChange={(e) => setF({ ...f, recoveryPct: e.target.value })} />
        </Field>
        <Field label={tx('Invoice date')}>
          <Input type="date" value={f.date} onChange={(e) => setF({ ...f, date: e.target.value })} />
        </Field>
      </FormDialog>
    </Section>
  );
}

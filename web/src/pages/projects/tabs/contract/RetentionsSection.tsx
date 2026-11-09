import { useState } from 'react';
import { MinusCircle, Unlock } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { useSupplierOptions } from '@/features/procurement/api';
import type { Workspace } from '@/features/projects/api';
import { shortDate } from '@/features/projects/ui';
import { conditionLabel, contractApi, opt, useContractMutation, useRetentions, type Retention } from '@/features/projects/contract-api';
import { Empty, Field, FormDialog, Money, Section, StatusPill, Table, isAmount, num, td, tdRight, type ContractPerms } from './shared';

const CLASSIFICATION = (): Record<string, string> => ({
  contract_asset: tx('Contract asset'), receivable: tx('Receivable'), payable: tx('Payable'),
});

/** Retention ledger: customer retentions (asset / receivable) and subcontractor retentions (payable). */
export function RetentionsSection({ ws, perms }: { ws: Workspace; perms: ContractPerms }) {
  const { data = [], isLoading } = useRetentions(ws.id);
  const { data: suppliers = [] } = useSupplierOptions();
  const [releasing, setReleasing] = useState<Retention | null>(null);
  const [evidence, setEvidence] = useState('');
  const [charging, setCharging] = useState<Retention | null>(null);
  const [bc, setBc] = useState({ amount: '', reason: '' });

  const release = useContractMutation(() => contractApi.release(releasing!.id, opt(evidence)), { success: tx('Retention released') });
  const backCharge = useContractMutation(() => contractApi.backCharge(charging!.id, { amount: bc.amount.trim(), reason: opt(bc.reason) }), { success: tx('Back-charge posted') });

  const open = (r: Retention) => num(r.amount) - num(r.backCharged);
  const partner = (r: Retention) => (r.side === 'customer' ? ws.customerName : suppliers.find((s) => s.id === r.partnerId)?.name) ?? r.partnerId.slice(0, 8);
  const needsEvidence = (r: Retention) => r.releaseCondition !== 'date_only';
  const notYet = (r: Retention) => r.releaseCondition === 'date_only' && !!r.releaseDate && r.releaseDate > new Date().toISOString().slice(0, 10);
  const bcValid = !!charging && isAmount(bc.amount) && num(bc.amount) > 0 && num(bc.amount) <= open(charging);

  return (
    <Section title={tx('Retentions')} hint={tx('Release a retention once its condition is met; back-charges reduce what is still held from a subcontractor.')}>
      {isLoading ? <Skeleton className="h-24 w-full" /> : data.length === 0 ? <Empty>{tx('No retentions held yet.')}</Empty> : (
        <Table head={[tx('Side'), tx('Partner'), tx('Release condition'), tx('Classification'), { label: tx('Amount'), right: true }, { label: tx('Back-charged'), right: true }, { label: tx('Still held'), right: true }, tx('Status'), '']}>
          {data.map((r) => (
            <tr key={r.id}>
              <td className={td}>{r.side === 'customer' ? tx('Customer') : tx('Subcontractor')}</td>
              <td className={td}>{partner(r)}</td>
              <td className={td}>
                {conditionLabel(r.releaseCondition)}
                {r.releaseDate && <p className="text-xs text-muted-foreground">{shortDate(r.releaseDate)}</p>}
              </td>
              <td className={td}>{CLASSIFICATION()[r.classification] ?? r.classification}</td>
              <td className={tdRight}><Money value={r.amount} /></td>
              <td className={tdRight}><Money value={r.backCharged} /></td>
              <td className={cn(tdRight, 'font-medium')}>{r.status === 'held' ? <Money value={open(r)} /> : '—'}</td>
              <td className={td}><StatusPill status={r.status} /></td>
              <td className={cn(td, 'whitespace-nowrap text-right')}>
                {perms.post && r.status === 'held' && (
                  <>
                    {r.side === 'subcontractor' && open(r) > 0 && (
                      <Button size="sm" variant="ghost" className="mr-1" onClick={() => { setBc({ amount: '', reason: '' }); setCharging(r); }}>
                        <MinusCircle className="mr-1 h-3.5 w-3.5" />{tx('Back-charge')}
                      </Button>
                    )}
                    <Button size="sm" variant="outline" disabled={notYet(r) || release.isPending}
                      title={notYet(r) ? tx('This retention is released on {{date}}', { date: r.releaseDate }) : undefined}
                      onClick={() => { setEvidence(''); setReleasing(r); }}>
                      <Unlock className="mr-1 h-3.5 w-3.5" />{tx('Release')}
                    </Button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </Table>
      )}

      <FormDialog
        open={!!releasing}
        onClose={() => setReleasing(null)}
        title={tx('Release retention')}
        description={releasing ? (releasing.side === 'customer'
          ? tx('Invoices the customer for {{amount}} still held.', { amount: open(releasing).toFixed(2) })
          : tx('Bills the subcontractor release of {{amount}} still held.', { amount: open(releasing).toFixed(2) })) : undefined}
        submitLabel={tx('Release')}
        valid={!!releasing && (!needsEvidence(releasing) || !!evidence.trim())}
        pending={release.isPending}
        onSubmit={() => release.mutate(undefined, { onSuccess: () => setReleasing(null) })}
      >
        {releasing && needsEvidence(releasing) ? (
          <Field
            className="sm:col-span-2"
            label={releasing.releaseCondition === 'defects_period' ? tx('End of defects period certificate') : tx('Practical completion certificate')}
            hint={tx('The certificate reference that meets the release condition.')}
          >
            <Input value={evidence} onChange={(e) => setEvidence(e.target.value)} />
          </Field>
        ) : (
          <p className="text-sm text-muted-foreground sm:col-span-2">{tx('A time-only retention needs no evidence once its release date has passed.')}</p>
        )}
      </FormDialog>

      <FormDialog
        open={!!charging}
        onClose={() => setCharging(null)}
        title={tx('Back-charge subcontractor')}
        description={charging ? tx('Up to {{amount}} still held. Reduces the retention payable and the project cost.', { amount: open(charging).toFixed(2) }) : undefined}
        submitLabel={tx('Post back-charge')}
        valid={bcValid}
        pending={backCharge.isPending}
        onSubmit={() => backCharge.mutate(undefined, { onSuccess: () => setCharging(null) })}
      >
        <Field label={tx('Amount')}>
          <Input type="number" step="0.01" min={0} value={bc.amount} onChange={(e) => setBc({ ...bc, amount: e.target.value })} />
        </Field>
        <Field label={tx('Reason')}>
          <Input value={bc.reason} onChange={(e) => setBc({ ...bc, reason: e.target.value })} placeholder={tx('e.g. Defect rectified by others')} />
        </Field>
      </FormDialog>
    </Section>
  );
}

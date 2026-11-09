import { useState } from 'react';
import { Check, Plus, Send, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import type { Workspace } from '@/features/projects/api';
import { shortDate, todayIso } from '@/features/projects/ui';
import {
  ONEROUS_INDICATORS, contractApi, indicatorLabel, opt, useContractMutation, useOnerousAssessments,
  type OnerousAssessment, type OnerousIndicator,
} from '@/features/projects/contract-api';
import { Empty, Field, FormDialog, Money, Section, StatusPill, Table, isAmount, num, td, tdRight, type ContractPerms } from './shared';

const EMPTY = {
  asOf: todayIso(), indicators: [] as OnerousIndicator[], probableClaims: '', costOfExiting: '', exitEvidence: '',
  impairmentRecognised: '', impairmentReference: '',
};

/** IAS 37 onerous-contract assessments: draft (computed from the forecast) → approve (another user) → post the movement. */
export function OnerousSection({ ws, perms }: { ws: Workspace; perms: ContractPerms }) {
  const { data = [], isLoading } = useOnerousAssessments(ws.id);
  const [open, setOpen] = useState(false);
  const [f, setF] = useState(EMPTY);
  const [detail, setDetail] = useState<OnerousAssessment | null>(null);

  const create = useContractMutation(() => contractApi.assessOnerous(ws.id, {
    asOf: opt(f.asOf), indicators: f.indicators, probableClaims: opt(f.probableClaims), costOfExiting: opt(f.costOfExiting),
    exitEvidence: opt(f.exitEvidence), impairmentRecognised: opt(f.impairmentRecognised), impairmentReference: opt(f.impairmentReference),
  }), { success: tx('Assessment drafted') });
  const approve = useContractMutation((id: string) => contractApi.approveOnerous(id), { success: tx('Assessment approved') });
  const reject = useContractMutation((id: string) => contractApi.rejectOnerous(id), { success: tx('Assessment rejected') });
  const post = useContractMutation((id: string) => contractApi.postOnerous(id), { success: tx('Provision movement posted') });

  const toggle = (i: OnerousIndicator) => setF({ ...f, indicators: f.indicators.includes(i) ? f.indicators.filter((x) => x !== i) : [...f.indicators, i] });
  const numOk = (v: string) => v === '' || isAmount(v);
  const valid = f.indicators.length > 0 && numOk(f.probableClaims) && numOk(f.costOfExiting) && numOk(f.impairmentRecognised)
    && (f.costOfExiting === '' || !!f.exitEvidence.trim())
    && (num(f.impairmentRecognised) <= 0 || !!f.impairmentReference.trim());

  return (
    <Section
      title={tx('Onerous-contract assessments (IAS 37)')}
      hint={tx('The provision is the unavoidable cost (lower of fulfilling and exiting) in excess of the expected benefits.')}
      action={perms.manage && <Button size="sm" onClick={() => { setF({ ...EMPTY, asOf: todayIso() }); setOpen(true); }}><Plus className="mr-1.5 h-4 w-4" />{tx('New assessment')}</Button>}
    >
      {isLoading ? <Skeleton className="h-24 w-full" /> : data.length === 0 ? <Empty>{tx('No onerous-contract assessments yet.')}</Empty> : (
        <Table head={[tx('As of'), tx('Indicators'), { label: tx('Expected benefits'), right: true }, { label: tx('Unavoidable cost'), right: true }, { label: tx('Provision'), right: true }, { label: tx('Movement'), right: true }, tx('Status'), '']}>
          {data.map((a) => (
            <tr key={a.id} className="cursor-pointer hover:bg-muted/40" onClick={() => setDetail(a)}>
              <td className={td}>{shortDate(a.asOf)}</td>
              <td className={td}>{a.indicators.split(',').filter(Boolean).map(indicatorLabel).join(', ')}</td>
              <td className={tdRight}><Money value={a.expectedBenefits} /></td>
              <td className={tdRight}><Money value={a.unavoidableCost} /></td>
              <td className={cn(tdRight, 'font-medium')}><Money value={a.provision} /></td>
              <td className={tdRight}><Money value={a.movement} /></td>
              <td className={td}><StatusPill status={a.status} /></td>
              <td className={cn(td, 'whitespace-nowrap text-right')} onClick={(e) => e.stopPropagation()}>
                {perms.approve && a.status === 'draft' && (
                  <Button size="sm" variant="outline" className="mr-1" disabled={approve.isPending} onClick={() => approve.mutate(a.id)}><Check className="mr-1 h-3.5 w-3.5" />{tx('Approve')}</Button>
                )}
                {perms.post && a.status === 'approved' && (
                  <Button size="sm" variant="outline" className="mr-1" disabled={post.isPending} onClick={() => post.mutate(a.id)}><Send className="mr-1 h-3.5 w-3.5" />{tx('Post')}</Button>
                )}
                {perms.approve && (a.status === 'draft' || a.status === 'approved') && (
                  <Button size="sm" variant="ghost" disabled={reject.isPending} onClick={() => reject.mutate(a.id)}><X className="mr-1 h-3.5 w-3.5" />{tx('Reject')}</Button>
                )}
              </td>
            </tr>
          ))}
        </Table>
      )}

      <FormDialog
        open={open}
        onClose={() => setOpen(false)}
        title={tx('New onerous-contract assessment')}
        description={tx('Costs to fulfil come from the cost-to-complete forecast; the remaining price from the transaction price less revenue recognised.')}
        submitLabel={tx('Draft assessment')}
        valid={valid}
        pending={create.isPending}
        onSubmit={() => create.mutate(undefined, { onSuccess: () => setOpen(false) })}
      >
        <Field label={tx('As of')}>
          <Input type="date" value={f.asOf} onChange={(e) => setF({ ...f, asOf: e.target.value })} />
        </Field>
        <Field label={tx('Probable claims (expected extra benefits)')}>
          <Input type="number" step="0.01" min={0} value={f.probableClaims} onChange={(e) => setF({ ...f, probableClaims: e.target.value })} />
        </Field>
        <Field label={tx('Indicators')} className="sm:col-span-2">
          <div className="flex flex-wrap gap-2">
            {ONEROUS_INDICATORS.map((i) => (
              <label key={i} className={cn('flex cursor-pointer items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs', f.indicators.includes(i) && 'border-primary bg-primary/10')}>
                <input type="checkbox" className="h-3.5 w-3.5 accent-primary" checked={f.indicators.includes(i)} onChange={() => toggle(i)} />
                {indicatorLabel(i)}
              </label>
            ))}
          </div>
        </Field>
        <Field label={tx('Cost of exiting (optional)')} hint={tx('Compensation or penalties from failing to fulfil.')}>
          <Input type="number" step="0.01" min={0} value={f.costOfExiting} onChange={(e) => setF({ ...f, costOfExiting: e.target.value })} />
        </Field>
        <Field label={tx('Exit evidence')}>
          <Input value={f.exitEvidence} disabled={f.costOfExiting === ''} onChange={(e) => setF({ ...f, exitEvidence: e.target.value })} placeholder={tx('Contract clause, legal advice')} />
        </Field>
        <Field label={tx('Impairment of dedicated assets (IAS 37.69)')}>
          <Input type="number" step="0.01" min={0} value={f.impairmentRecognised} onChange={(e) => setF({ ...f, impairmentRecognised: e.target.value })} />
        </Field>
        <Field label={tx('Impairment reference')}>
          <Input value={f.impairmentReference} disabled={num(f.impairmentRecognised) <= 0} onChange={(e) => setF({ ...f, impairmentReference: e.target.value })} />
        </Field>
      </FormDialog>

      <FormDialog open={!!detail} onClose={() => setDetail(null)} title={tx('Onerous-contract assessment')} submitLabel={tx('Close')} onSubmit={() => setDetail(null)}>
        {detail && (
          <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:col-span-2">
            {([
              [tx('Remaining price'), detail.remainingPrice], [tx('Probable claims'), detail.probableClaims],
              [tx('Expected benefits'), detail.expectedBenefits], [tx('Incremental cost'), detail.incrementalCost],
              [tx('Directly related cost'), detail.directlyRelatedCost], [tx('Excluded cost'), detail.excludedCost],
              [tx('Cost of fulfilling'), detail.costOfFulfilling], [tx('Cost of exiting'), detail.costOfExiting],
              [tx('Unavoidable cost'), detail.unavoidableCost], [tx('Impairment recognised'), detail.impairmentRecognised],
              [tx('Provision'), detail.provision], [tx('Previous provision'), detail.previousProvision], [tx('Movement'), detail.movement],
            ] as const).map(([label, value]) => (
              <div key={label} className="contents">
                <dt className="text-muted-foreground">{label}</dt><dd><Money value={value} /></dd>
              </div>
            ))}
            {detail.exitEvidence && <><dt className="text-muted-foreground">{tx('Exit evidence')}</dt><dd>{detail.exitEvidence}</dd></>}
            {detail.impairmentReference && <><dt className="text-muted-foreground">{tx('Impairment reference')}</dt><dd>{detail.impairmentReference}</dd></>}
          </dl>
        )}
      </FormDialog>
    </Section>
  );
}

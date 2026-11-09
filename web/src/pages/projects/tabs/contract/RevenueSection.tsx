import { useState } from 'react';
import { BadgeCheck, ClipboardCheck, Play, Undo2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import type { Workspace } from '@/features/projects/api';
import { shortDate, todayIso } from '@/features/projects/ui';
import {
  contractApi, methodLabel, useContractMutation, useCriteriaAssessments, useRevenuePreview, useRevenueRuns,
  type CriteriaAssessment, type RevenueFigures,
} from '@/features/projects/contract-api';
import { Empty, Field, FormDialog, Money, Section, StatusPill, Table, num, td, tdRight, type ContractPerms } from './shared';

const progressPct = (v: RevenueFigures['progress']) => `${(num(v) * 100).toFixed(1)}%`;

/**
 * Revenue recognition: preview a run for a period end, post it, reverse the latest run. Above it, the IFRS 15.35
 * over-time criteria assessment (drafted by one user, approved by another) that the recognition method election needs.
 */
export function RevenueSection({ ws, perms }: { ws: Workspace; perms: ContractPerms }) {
  const { data: criteria = [] } = useCriteriaAssessments(ws.id);
  const { data: runs = [], isLoading } = useRevenueRuns(ws.id);
  const [periodEnd, setPeriodEnd] = useState(todayIso());
  const [previewOn, setPreviewOn] = useState(false);
  const preview = useRevenuePreview(ws.id, periodEnd, previewOn);
  const post = useContractMutation(() => contractApi.runRevenue(ws.id, periodEnd), { success: tx('Revenue recognised') });
  const reverse = useContractMutation((id: string) => contractApi.reverseRun(id), { success: tx('Run reversed') });
  const lastPosted = [...runs].reverse().find((r) => r.status === 'posted');

  const [assessing, setAssessing] = useState(false);
  const [c, setC] = useState({ criterionA: false, criterionB: false, criterionC: false, evidence: '' });
  const assess = useContractMutation<void, CriteriaAssessment>(() => contractApi.assessCriteria(ws.id, c), { success: tx('Assessment recorded') });
  const approve = useContractMutation<string, CriteriaAssessment>((id) => contractApi.approveCriteria(id), { success: tx('Assessment approved') });

  const figures = preview.data;
  const future = periodEnd > todayIso();

  return (
    <div className="space-y-4">
      <Section
        title={tx('Revenue recognition')}
        hint={tx('Over time (cost-to-cost) recognises revenue by progress; point in time recognises it when the project is completed.')}
      >
        {perms.post && (
          <div className="mb-4 flex flex-wrap items-end gap-2">
            <Field label={tx('Period end')}>
              <Input type="date" className="w-44" max={todayIso()} value={periodEnd} onChange={(e) => { setPeriodEnd(e.target.value); setPreviewOn(false); }} />
            </Field>
            <Button variant="outline" disabled={!periodEnd || future} onClick={() => (previewOn ? void preview.refetch() : setPreviewOn(true))}>{tx('Preview')}</Button>
            <Button disabled={!figures || !previewOn || post.isPending} onClick={() => post.mutate(undefined, { onSuccess: () => setPreviewOn(false) })}>
              <Play className="mr-1.5 h-4 w-4" />{tx('Post run')}
            </Button>
            {lastPosted && <span className="text-xs text-muted-foreground">{tx('Recognised up to {{date}}', { date: shortDate(lastPosted.periodEnd) })}</span>}
          </div>
        )}
        {!perms.post && (
          <Field label={tx('Preview for period end')} className="mb-4 max-w-xs">
            <div className="flex gap-2">
              <Input type="date" max={todayIso()} value={periodEnd} onChange={(e) => { setPeriodEnd(e.target.value); setPreviewOn(false); }} />
              <Button variant="outline" disabled={!periodEnd || future} onClick={() => setPreviewOn(true)}>{tx('Preview')}</Button>
            </div>
          </Field>
        )}
        {previewOn && (preview.isLoading ? <Skeleton className="mb-4 h-20 w-full" /> : preview.isError ? (
          <p className="mb-4 text-sm text-rose-600">{tx('The preview could not be computed for this period end.')}</p>
        ) : figures && (
          <div className="mb-4 grid gap-3 rounded-lg border border-dashed p-3 text-sm sm:grid-cols-3 lg:grid-cols-5">
            <div><p className="text-xs text-muted-foreground">{tx('Method')}</p><p className="font-medium">{methodLabel(figures.method)}</p></div>
            <div><p className="text-xs text-muted-foreground">{tx('Progress')}</p><p className="font-medium tabular-nums">{progressPct(figures.progress)}</p></div>
            <div><p className="text-xs text-muted-foreground">{tx('Transaction price')}</p><Money value={figures.transactionPrice} className="font-medium" /></div>
            <div><p className="text-xs text-muted-foreground">{tx('Cost to date / estimated total')}</p><p className="font-medium"><Money value={figures.costToDate} /> / <Money value={figures.estimatedTotalCost} /></p></div>
            <div><p className="text-xs text-muted-foreground">{tx('Cumulative revenue')}</p><Money value={figures.cumulativeRevenue} className="font-medium" /></div>
            <div className="sm:col-span-3 lg:col-span-5 flex flex-wrap gap-6 border-t pt-2">
              <span>{tx('Revenue this period')}: <Money value={figures.revenueThisPeriod} className="font-semibold" /></span>
              <span>{tx('Cost of revenue this period')}: <Money value={figures.costThisPeriod} className="font-semibold" /></span>
            </div>
          </div>
        ))}

        {isLoading ? <Skeleton className="h-24 w-full" /> : runs.length === 0 ? <Empty>{tx('No revenue recognition runs yet.')}</Empty> : (
          <Table head={[tx('Period end'), tx('Method'), { label: tx('Progress'), right: true }, { label: tx('Cumulative revenue'), right: true }, { label: tx('Revenue this period'), right: true }, { label: tx('Cost this period'), right: true }, tx('Status'), '']}>
            {runs.map((r) => (
              <tr key={r.id}>
                <td className={td}>{shortDate(r.periodEnd)}</td>
                <td className={td}>{methodLabel(r.method)}</td>
                <td className={tdRight}>{progressPct(r.progress)}</td>
                <td className={tdRight}><Money value={r.cumulativeRevenue} /></td>
                <td className={tdRight}><Money value={r.revenueThisPeriod} /></td>
                <td className={tdRight}><Money value={r.costThisPeriod} /></td>
                <td className={td}><StatusPill status={r.status} /></td>
                <td className={cn(td, 'text-right')}>
                  {perms.post && r.id === lastPosted?.id && (
                    <Button size="sm" variant="ghost" disabled={reverse.isPending} onClick={() => reverse.mutate(r.id)}><Undo2 className="mr-1 h-3.5 w-3.5" />{tx('Reverse')}</Button>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Section>

      <Section
        title={tx('Over-time criteria (IFRS 15.35)')}
        hint={tx('Revenue is recognised over time only when one criterion is met and the assessment is approved by someone other than its assessor.')}
        action={perms.manage && (
          <Button size="sm" variant="outline" onClick={() => { setC({ criterionA: false, criterionB: false, criterionC: false, evidence: '' }); setAssessing(true); }}>
            <ClipboardCheck className="mr-1.5 h-4 w-4" />{tx('New assessment')}
          </Button>
        )}
      >
        {criteria.length === 0 ? <Empty>{tx('No assessments yet. Each one is approved by a second person.')}</Empty> : (
          <Table head={[tx('Recorded'), tx('Criteria met'), tx('Evidence'), tx('Status'), '']}>
            {criteria.map((a) => (
              <tr key={a.id}>
                <td className={td}>{shortDate(a.createdAt)}</td>
                <td className={td}>{[a.criterionA && '(a)', a.criterionB && '(b)', a.criterionC && '(c)'].filter(Boolean).join(' ') || tx('None')}</td>
                <td className={cn(td, 'max-w-sm truncate')} title={a.evidence}>{a.evidence}</td>
                <td className={td}><StatusPill status={a.status} /></td>
                <td className={cn(td, 'text-right')}>
                  {perms.approve && a.status === 'draft' && (
                    <Button size="sm" variant="outline" disabled={approve.isPending} onClick={() => approve.mutate(a.id)}>
                      <BadgeCheck className="mr-1 h-3.5 w-3.5" />{tx('Approve')}
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Section>

      <FormDialog
        open={assessing}
        onClose={() => setAssessing(false)}
        title={tx('IFRS 15.35 assessment')}
        description={tx('Tick each criterion the contract meets and record the evidence (at least 10 characters).')}
        submitLabel={tx('Record assessment')}
        valid={c.evidence.trim().length >= 10}
        pending={assess.isPending}
        onSubmit={() => assess.mutate(undefined, { onSuccess: () => setAssessing(false) })}
      >
        {([
          ['criterionA', tx('(a) The customer simultaneously receives and consumes the benefits as we perform')],
          ['criterionB', tx('(b) Our performance creates or enhances an asset the customer controls')],
          ['criterionC', tx('(c) No alternative use, and an enforceable right to payment for work to date')],
        ] as const).map(([k, label]) => (
          <label key={k} className="flex items-start gap-2 text-sm sm:col-span-2">
            <input type="checkbox" className="mt-0.5 h-4 w-4 accent-primary" checked={c[k]} onChange={(e) => setC({ ...c, [k]: e.target.checked })} />
            {label}
          </label>
        ))}
        <Field label={tx('Evidence')} className="sm:col-span-2">
          <Textarea rows={3} value={c.evidence} onChange={(e) => setC({ ...c, evidence: e.target.value })} placeholder={tx('Contract clauses, legal opinion…')} />
        </Field>
      </FormDialog>
    </div>
  );
}

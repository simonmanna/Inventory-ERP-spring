import { useMemo, useState } from 'react';
import { BadgeCheck, Plus, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { useTaxes } from '@/features/accounting/api';
import type { Workspace } from '@/features/projects/api';
import { selectClass, shortDate, todayIso } from '@/features/projects/ui';
import {
  RETENTION_CONDITIONS, conditionLabel, contractApi, opt, useClaim, useClaims, useContractMutation,
  type Claim, type ContractPosition, type RetentionCondition,
} from '@/features/projects/contract-api';
import { Empty, Field, FormDialog, Money, Section, StatusPill, Table, isAmount, num, pct, td, tdRight, type ContractPerms } from './shared';

/** Progress claims (interim payment applications): draft → certify (invoice + retention + advance recovery). */
export function ClaimsSection({ ws, perms, position }: { ws: Workspace; perms: ContractPerms; position?: ContractPosition }) {
  const { data = [], isLoading } = useClaims(ws.id);
  const [open, setOpen] = useState(false);
  const [picked, setDetail] = useState<Claim | null>(null);
  // The detail re-reads the claim (GET /projects/claims/:id) so it shows the invoice and certification once posted.
  const { data: fresh } = useClaim(picked?.id);
  const detail = picked ? (fresh?.id === picked.id ? fresh : picked) : null;
  const previous = useMemo(
    () => data.filter((c) => c.status === 'certified').reduce((m, c) => Math.max(m, num(c.cumulativeCertified)), 0),
    [data],
  );
  const last = [...data].reverse().find((c) => c.status === 'certified');
  const empty = {
    claimDate: todayIso(), cumulativeCertified: '', retentionPct: last ? String(num(last.retentionPct)) : '',
    retentionCondition: (last?.retentionCondition ?? 'completion_certificate') as RetentionCondition,
    retentionReleaseDate: '', taxId: last?.taxId ?? '',
  };
  const [f, setF] = useState(empty);
  const { data: taxes } = useTaxes({ pageSize: 200 });
  const hasDraft = data.some((c) => c.status === 'draft');

  const create = useContractMutation(() => contractApi.createClaim(ws.id, {
    claimDate: opt(f.claimDate),
    cumulativeCertified: f.cumulativeCertified.trim(),
    retentionPct: opt(f.retentionPct),
    retentionCondition: f.retentionCondition,
    retentionReleaseDate: f.retentionCondition === 'date_only' ? opt(f.retentionReleaseDate) : undefined,
    taxId: opt(f.taxId),
  }), { success: tx('Claim drafted') });
  const certify = useContractMutation((id: string) => contractApi.certifyClaim(id), { success: tx('Claim certified and invoiced') });
  const cancel = useContractMutation((id: string) => contractApi.cancelClaim(id), { success: tx('Claim cancelled') });

  const valid = isAmount(f.cumulativeCertified) && num(f.cumulativeCertified) > previous
    && (f.retentionCondition !== 'date_only' || !!f.retentionReleaseDate);
  const thisClaim = Math.max(0, num(f.cumulativeCertified) - previous);
  const retention = (thisClaim * num(f.retentionPct)) / 100;

  return (
    <Section
      title={tx('Progress claims')}
      hint={tx('Each claim states the cumulative value certified; certifying invoices the net of retention and advance recovery.')}
      action={perms.manage && (
        <Button size="sm" disabled={hasDraft || !ws.customerId} title={hasDraft ? tx('Certify or cancel the draft claim first') : undefined}
          onClick={() => { setF(empty); setOpen(true); }}>
          <Plus className="mr-1.5 h-4 w-4" />{tx('New claim')}
        </Button>
      )}
    >
      {!ws.customerId && <p className="mb-3 text-xs text-amber-600">{tx('The project needs a customer for contract billing.')}</p>}
      {isLoading ? <Skeleton className="h-24 w-full" /> : data.length === 0 ? <Empty>{tx('No progress claims yet.')}</Empty> : (
        <Table head={[tx('Number'), tx('Date'), { label: tx('Cumulative'), right: true }, { label: tx('This claim'), right: true }, { label: tx('Retention'), right: true }, { label: tx('Advance recovery'), right: true }, { label: tx('Net'), right: true }, tx('Status'), '']}>
          {data.map((c) => (
            <tr key={c.id} className="cursor-pointer hover:bg-muted/40" onClick={() => setDetail(c)}>
              <td className={cn(td, 'font-mono text-xs')}>{c.number}</td>
              <td className={td}>{shortDate(c.claimDate)}</td>
              <td className={tdRight}><Money value={c.cumulativeCertified} /></td>
              <td className={tdRight}><Money value={c.thisClaim} /></td>
              <td className={tdRight}><Money value={c.retention} /></td>
              <td className={tdRight}><Money value={c.advanceRecovery} /></td>
              <td className={cn(tdRight, 'font-medium')}><Money value={c.netAmount} /></td>
              <td className={td}><StatusPill status={c.status} /></td>
              <td className={cn(td, 'whitespace-nowrap text-right')} onClick={(e) => e.stopPropagation()}>
                {c.status === 'draft' && perms.post && (
                  <Button size="sm" variant="outline" className="mr-1" disabled={certify.isPending} onClick={() => certify.mutate(c.id)}>
                    <BadgeCheck className="mr-1 h-3.5 w-3.5" />{tx('Certify')}
                  </Button>
                )}
                {c.status === 'draft' && perms.manage && (
                  <Button size="sm" variant="ghost" disabled={cancel.isPending} onClick={() => cancel.mutate(c.id)}><X className="mr-1 h-3.5 w-3.5" />{tx('Cancel')}</Button>
                )}
              </td>
            </tr>
          ))}
        </Table>
      )}

      <FormDialog
        open={open}
        onClose={() => setOpen(false)}
        title={tx('New progress claim')}
        description={tx('Already certified: {{prev}} of a transaction price of {{price}}.', {
          prev: previous.toFixed(2), price: position ? num(position.transactionPrice).toFixed(2) : '—',
        })}
        submitLabel={tx('Draft claim')}
        valid={valid}
        pending={create.isPending}
        onSubmit={() => create.mutate(undefined, { onSuccess: () => setOpen(false) })}
      >
        <Field label={tx('Claim date')}>
          <Input type="date" value={f.claimDate} onChange={(e) => setF({ ...f, claimDate: e.target.value })} />
        </Field>
        <Field label={tx('Cumulative value certified')} hint={tx('Must exceed what is already certified.')}>
          <Input type="number" step="0.01" min={0} value={f.cumulativeCertified} onChange={(e) => setF({ ...f, cumulativeCertified: e.target.value })} />
        </Field>
        <Field label={tx('Retention %')}>
          <Input type="number" step="0.01" min={0} max={99.99} value={f.retentionPct} onChange={(e) => setF({ ...f, retentionPct: e.target.value })} />
        </Field>
        <Field label={tx('Retention release condition')}>
          <select className={selectClass} value={f.retentionCondition} onChange={(e) => setF({ ...f, retentionCondition: e.target.value as RetentionCondition })}>
            {RETENTION_CONDITIONS.map((c) => <option key={c} value={c}>{conditionLabel(c)}</option>)}
          </select>
        </Field>
        {f.retentionCondition === 'date_only' && (
          <Field label={tx('Retention release date')}>
            <Input type="date" value={f.retentionReleaseDate} onChange={(e) => setF({ ...f, retentionReleaseDate: e.target.value })} />
          </Field>
        )}
        <Field label={tx('Tax (optional)')}>
          <select className={selectClass} value={f.taxId} onChange={(e) => setF({ ...f, taxId: e.target.value })}>
            <option value="">{tx('No tax')}</option>
            {(taxes?.data ?? []).filter((t) => t.isActive).map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
        </Field>
        <div className="rounded-md bg-muted/50 p-3 text-xs text-muted-foreground sm:col-span-2">
          {tx('This claim')}: <b className="tabular-nums text-foreground">{thisClaim.toFixed(2)}</b>
          {' · '}{tx('Retention')}: <b className="tabular-nums text-foreground">{retention.toFixed(2)}</b>
          {position && num(position.advancesOutstanding) > 0 && <> · {tx('Advance recovery is deducted on top.')}</>}
        </div>
      </FormDialog>

      <FormDialog
        open={!!detail}
        onClose={() => setDetail(null)}
        title={detail ? tx('Claim {{number}}', { number: detail.number }) : ''}
        submitLabel={tx('Close')}
        onSubmit={() => setDetail(null)}
      >
        {detail && (
          <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:col-span-2">
            <dt className="text-muted-foreground">{tx('Status')}</dt><dd><StatusPill status={detail.status} /></dd>
            <dt className="text-muted-foreground">{tx('Claim date')}</dt><dd>{shortDate(detail.claimDate)}</dd>
            <dt className="text-muted-foreground">{tx('Cumulative value certified')}</dt><dd><Money value={detail.cumulativeCertified} /></dd>
            <dt className="text-muted-foreground">{tx('Previously certified')}</dt><dd><Money value={detail.previousCertified} /></dd>
            <dt className="text-muted-foreground">{tx('This claim')}</dt><dd><Money value={detail.thisClaim} /></dd>
            <dt className="text-muted-foreground">{tx('Retention')} ({pct(detail.retentionPct)})</dt><dd><Money value={detail.retention} /></dd>
            <dt className="text-muted-foreground">{tx('Retention release condition')}</dt>
            <dd>{conditionLabel(detail.retentionCondition)}{detail.retentionReleaseDate && ` · ${shortDate(detail.retentionReleaseDate)}`}</dd>
            <dt className="text-muted-foreground">{tx('Advance recovery')}</dt><dd><Money value={detail.advanceRecovery} /></dd>
            <dt className="text-muted-foreground">{tx('Net amount')}</dt><dd className="font-semibold"><Money value={detail.netAmount} /></dd>
            {detail.invoiceId && <><dt className="text-muted-foreground">{tx('Invoice')}</dt><dd className="font-mono text-xs">{detail.invoiceId.slice(0, 8)}</dd></>}
            {detail.certifiedAt && <><dt className="text-muted-foreground">{tx('Certified')}</dt><dd>{shortDate(detail.certifiedAt)}</dd></>}
          </dl>
        )}
      </FormDialog>
    </Section>
  );
}

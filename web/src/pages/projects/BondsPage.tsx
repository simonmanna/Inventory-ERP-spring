import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Plus, ShieldCheck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { selectClass, shortDate } from '@/features/projects/ui';
import { BOND_TYPES, bondTypeLabel, directionLabel, useBonds, useExposure, type ExposureRow } from '@/features/projects/construction-api';
import { Money, Section, Table, td, tdRight } from './tabs/contract/shared';
import { BondDetailDialog, BondFormDialog, BondTable, useBondPerms } from './bonds/BondsUi';

/**
 * Bonds & guarantees register (ADR-S074): every performance bond, bank guarantee, advance-payment guarantee, retention
 * bond and bid bond we issued or received, with the lifecycle (activate, extend, reduce, release, call) and the live
 * exposure by issuing bank / insurer and by project. `?bond=<id>` opens one (links from alerts and DMS).
 */
export function BondsPage() {
  const perms = useBondPerms();
  const [params, setParams] = useSearchParams();
  const [status, setStatus] = useState('');
  const [direction, setDirection] = useState('');
  const [type, setType] = useState('');
  const [view, setView] = useState<'register' | 'exposure'>('register');
  const [creating, setCreating] = useState(false);
  const { data: bonds = [], isLoading } = useBonds({ status: status || undefined, direction: direction || undefined });
  const { data: exposure } = useExposure(view === 'exposure');
  const open = params.get('bond');
  const shown = type ? bonds.filter((b) => b.bondType === type) : bonds;

  return (
    <div className="mx-auto space-y-4 px-2 py-3 md:px-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="flex items-center gap-2 text-xl font-semibold"><ShieldCheck className="h-5 w-5" />{tx('Bonds & guarantees')}</h1>
          <p className="text-sm text-muted-foreground">{tx('Issued to clients and received from subcontractors; expiry alerts at 30, 14 and 7 days.')}</p>
        </div>
        {perms.manage && <Button onClick={() => setCreating(true)}><Plus className="mr-1.5 h-4 w-4" />{tx('New bond')}</Button>}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <div className="inline-flex rounded-lg border bg-muted/40 p-0.5">
          {(['register', 'exposure'] as const).map((v) => (
            <button key={v} type="button" onClick={() => setView(v)}
              className={cn('rounded-md px-3 py-1.5 text-sm', view === v ? 'bg-background font-medium shadow-sm' : 'text-muted-foreground hover:text-foreground')}>
              {v === 'register' ? tx('Register') : tx('Exposure')}
            </button>
          ))}
        </div>
        {view === 'register' && (
          <>
            <select className={cn(selectClass, 'w-44')} value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="">{tx('All statuses')}</option>
              {['draft', 'active', 'extended', 'reduced', 'released', 'called', 'expired'].map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
            <select className={cn(selectClass, 'w-44')} value={direction} onChange={(e) => setDirection(e.target.value)}>
              <option value="">{tx('Both directions')}</option>
              <option value="issued">{directionLabel('issued')}</option>
              <option value="received">{directionLabel('received')}</option>
            </select>
            <select className={cn(selectClass, 'w-52')} value={type} onChange={(e) => setType(e.target.value)}>
              <option value="">{tx('All types')}</option>
              {BOND_TYPES.map((t) => <option key={t} value={t}>{bondTypeLabel(t)}</option>)}
            </select>
          </>
        )}
      </div>

      {view === 'register' && (
        <Section title={tx('Register')}>
          {isLoading ? <Skeleton className="h-40 w-full" /> : <BondTable bonds={shown} onOpen={(b) => setParams({ bond: b.id })} />}
        </Section>
      )}

      {view === 'exposure' && exposure && (
        <div className="space-y-4">
          <ExposureSection title={tx('Totals')} rows={exposure.totals} first={(r) => directionLabel(r.direction)} />
          <ExposureSection title={tx('By issuing bank / insurer')} rows={exposure.byBank} first={(r) => `${r.issuer} · ${directionLabel(r.direction)}`} />
          <ExposureSection title={tx('By project')} rows={exposure.byProject}
            first={(r) => `${r.projectCode ? `${r.projectCode} · ${r.projectName}` : tx('No project')} · ${directionLabel(r.direction)}`} />
        </div>
      )}

      {creating && <BondFormDialog open onClose={() => setCreating(false)} />}
      <BondDetailDialog bondId={open} onClose={() => setParams({})} />
    </div>
  );
}

function ExposureSection({ title, rows, first }: { title: string; rows: ExposureRow[]; first: (r: ExposureRow) => string }) {
  return (
    <Section title={title}>
      <Table head={['', tx('Currency'), { label: tx('Bonds'), right: true }, { label: tx('Live amount'), right: true },
        { label: tx('Expiring in 30 days'), right: true }, { label: tx('Margin held'), right: true }, tx('Next expiry')]}>
        {rows.map((r, i) => (
          <tr key={i}>
            <td className={td}>{first(r)}</td>
            <td className={td}>{r.currencyCode}</td>
            <td className={tdRight}>{r.bonds}</td>
            <td className={tdRight}><Money value={r.amount} /></td>
            <td className={tdRight}><Money value={r.expiring30} /></td>
            <td className={tdRight}><Money value={r.marginHeld} /></td>
            <td className={td}>{r.nextExpiry ? shortDate(r.nextExpiry) : '—'}</td>
          </tr>
        ))}
      </Table>
    </Section>
  );
}

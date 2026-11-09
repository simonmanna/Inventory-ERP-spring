import { useState } from 'react';
import { AlertTriangle, Lock } from 'lucide-react';
import { Skeleton } from '@/components/ui/skeleton';
import { useAuthStore } from '@/stores/auth.store';
import { PERMISSIONS } from '@erp/shared';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import type { Num, Workspace } from '@/features/projects/api';
import { methodLabel, useContractPosition, type ContractPosition } from '@/features/projects/contract-api';
import { Money, num, type ContractPerms } from './contract/shared';
import { VariationsSection } from './contract/VariationsSection';
import { ClaimsSection } from './contract/ClaimsSection';
import { AdvancesSection } from './contract/AdvancesSection';
import { SubcontractsSection } from './contract/SubcontractsSection';
import { RetentionsSection } from './contract/RetentionsSection';
import { RevenueSection } from './contract/RevenueSection';
import { OnerousSection } from './contract/OnerousSection';

type Sub = 'variations' | 'claims' | 'advances' | 'subcontracts' | 'retentions' | 'revenue' | 'onerous';

/**
 * Contract commercials of a project (CM-8b, ADR-S039): the contract position, then variations, progress claims,
 * customer advances, subcontracts, retentions, revenue recognition (with the IFRS 15.35 assessment) and onerous
 * contracts. Viewing needs `contract:read`; drafting `contract:manage`; posting `contract:post`; approving
 * `contract:approve` (the server refuses approving your own item and its message is shown verbatim).
 */
export function ContractTab({ ws }: { ws: Workspace }) {
  const has = useAuthStore((s) => s.hasPermission);
  const perms: ContractPerms = {
    read: has(PERMISSIONS.contract.read),
    manage: has(PERMISSIONS.contract.manage),
    post: has(PERMISSIONS.contract.post),
    approve: has(PERMISSIONS.contract.approve),
  };
  const [sub, setSub] = useState<Sub>('variations');
  const { data: position, isLoading } = useContractPosition(ws.id, perms.read);

  if (!perms.read) {
    return (
      <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed px-6 py-14 text-center text-sm text-muted-foreground">
        <Lock className="h-6 w-6" />
        {tx('You need the contract read permission to see the contract commercials of this project.')}
      </div>
    );
  }

  const subs: Array<{ id: Sub; label: string }> = [
    { id: 'variations', label: tx('Variations') },
    { id: 'claims', label: tx('Claims') },
    { id: 'advances', label: tx('Advances') },
    { id: 'subcontracts', label: tx('Subcontracts') },
    { id: 'retentions', label: tx('Retentions') },
    { id: 'revenue', label: tx('Revenue') },
    { id: 'onerous', label: tx('Onerous') },
  ];

  return (
    <div className="space-y-4">
      {isLoading ? <Skeleton className="h-36 w-full" /> : position && <PositionSummary position={position} />}

      <div className="flex flex-wrap gap-1.5">
        {subs.map((s) => (
          <button
            key={s.id}
            type="button"
            onClick={() => setSub(s.id)}
            className={cn('rounded-full border px-3 py-1 text-sm',
              sub === s.id ? 'border-primary bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground')}
          >
            {s.label}
          </button>
        ))}
      </div>

      {sub === 'variations' && <VariationsSection ws={ws} perms={perms} />}
      {sub === 'claims' && <ClaimsSection ws={ws} perms={perms} position={position} />}
      {sub === 'advances' && <AdvancesSection ws={ws} perms={perms} position={position} />}
      {sub === 'subcontracts' && <SubcontractsSection ws={ws} perms={perms} />}
      {sub === 'retentions' && <RetentionsSection ws={ws} perms={perms} />}
      {sub === 'revenue' && <RevenueSection ws={ws} perms={perms} />}
      {sub === 'onerous' && <OnerousSection ws={ws} perms={perms} />}
    </div>
  );
}

function PositionSummary({ position: p }: { position: ContractPosition }) {
  const tiles: Array<[string, Num, string?]> = [
    [tx('Transaction price'), p.transactionPrice, tx('Contract value + approved variations')],
    [tx('Revenue recognised'), p.revenueRecognised],
    [tx('Gross billed'), p.grossBilled, tx('Certified claims')],
    [num(p.contractLiability) > 0 ? tx('Contract liability') : tx('Contract asset'),
      num(p.contractLiability) > 0 ? p.contractLiability : p.contractAsset,
      num(p.contractLiability) > 0 ? tx('Billed ahead of revenue') : tx('Revenue not yet billed')],
    [tx('Retention receivable'), num(p.retentionContractAsset) + num(p.retentionReceivable), tx('Held by the customer')],
    [tx('Retention payable'), p.retentionPayable, tx('Held from subcontractors')],
    [tx('Advances outstanding'), p.advancesOutstanding],
    [tx('Onerous provision'), p.onerousProvision],
  ];
  const ties = Object.entries(p.glTieOut ?? {}) as Array<[string, { difference: string | number }]>;
  const offTie = ties.filter(([, t]) => Math.abs(num(t.difference)) >= 0.005);
  const tieLabel: Record<string, string> = {
    retentionContractAsset: tx('Retention contract asset'), retentionPayable: tx('Retention payable'),
    customerAdvances: tx('Customer advances'), onerousProvision: tx('Onerous provision'),
  };

  return (
    <section className="rounded-xl border bg-card p-4 shadow-sm">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold">{tx('Contract position')}</h3>
        <span className="rounded bg-muted px-2 py-0.5 text-xs text-muted-foreground">{tx('Recognition')}: {methodLabel(p.method)}</span>
      </div>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {tiles.map(([label, value, hint]) => (
          <div key={label}>
            <p className="text-xs text-muted-foreground">{label}</p>
            <p className="text-lg font-semibold"><Money value={value} /></p>
            {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
          </div>
        ))}
      </div>
      {offTie.length > 0 && (
        <p className="mt-3 flex items-start gap-1.5 rounded-md bg-amber-50 p-2 text-xs text-amber-800 dark:bg-amber-500/10 dark:text-amber-300">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          {tx('Sub-ledger and general ledger differ (organisation-wide) for: {{accounts}}.', { accounts: offTie.map(([k]) => tieLabel[k] ?? k).join(', ') })}
        </p>
      )}
    </section>
  );
}

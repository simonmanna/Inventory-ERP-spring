import { Link } from 'react-router-dom';
import { GitBranch, RefreshCw } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { useAuthStore } from '@/stores/auth.store';
import { useOrderBomStatus, useUpdateOrderBom } from '@/features/manufacturing/api-plm-app';
import { useFeatureAvailable } from '@/lib/use-feature-available';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

/**
 * PLM on a production order (ADR-S086): when the order's BOM version was superseded by an applied change, say so and
 * let a PLM user switch a not-yet-started order to the current version; open changes on its BOM are listed.
 */
export function OrderBomBanner({ orderId }: { orderId: string }) {
  const has = useAuthStore((s) => s.hasPermission);
  const plmOn = useFeatureAvailable('plm');
  const canRead = has(PERMISSIONS.plm.read);
  const canApply = has(PERMISSIONS.plm.apply);
  const { data } = useOrderBomStatus(orderId, plmOn && canRead);
  const update = useUpdateOrderBom();
  if (!data || (!data.superseded && !data.openChanges.length)) return null;
  const finished = ['completed', 'cancelled', 'qc_hold'].includes(data.status);
  if (finished && !data.superseded) return null;
  const run = () => {
    if (!confirm(tx('Switch {{o}} to {{c}} v{{v}}? Its material lines are rebuilt from the current version.',
      { o: data.orderCode, c: data.current?.code ?? '', v: data.current?.version ?? '' }))) return;
    update.mutate(orderId, {
      onSuccess: () => notify.success(tx('{{o}} now uses {{c}} v{{v}}', { o: data.orderCode, c: data.current?.code ?? '', v: data.current?.version ?? '' })),
    });
  };
  return (
    <div className="space-y-1.5 rounded-lg border border-amber-300 bg-amber-50 px-4 py-2.5 text-sm text-amber-900 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200">
      {data.superseded && data.bom && data.current && (
        <div className="flex flex-wrap items-center gap-3">
          <GitBranch className="h-4 w-4 shrink-0" />
          <span className="min-w-0 flex-1">
            {finished
              ? tx('Made on {{b}} v{{v}}; {{c}} v{{cv}} is the current version.', { b: data.bom.code, v: data.bom.version, c: data.current.code, cv: data.current.version })
              : tx('This order uses {{b}} v{{v}}, which was superseded by {{c}} v{{cv}}.', { b: data.bom.code, v: data.bom.version, c: data.current.code, cv: data.current.version })}
            {!finished && data.blocker && <> {data.blocker}</>}
          </span>
          {!finished && canApply && data.canUpdate && (
            <Button size="sm" variant="outline" onClick={run} disabled={update.isPending}><RefreshCw className="mr-1.5 h-4 w-4" />{tx('Use the current version')}</Button>
          )}
        </div>
      )}
      {!finished && data.openChanges.length > 0 && (
        <p className="text-xs">
          {tx('Open engineering changes on this BOM')}:{' '}
          {data.openChanges.map((c, i) => (
            <span key={c.id}>{i > 0 && ', '}<Link to={`/manufacturing/plm/changes/${c.id}`} className="font-mono underline">{c.number}</Link> {c.title}</span>
          ))}
        </p>
      )}
    </div>
  );
}

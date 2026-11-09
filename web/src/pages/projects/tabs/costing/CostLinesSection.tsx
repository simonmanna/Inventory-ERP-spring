import { useMemo, useState } from 'react';
import { Undo2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter,
  AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { money } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { selectClass, shortDate } from '@/features/projects/ui';
import { useCostLines, useCostingMutation, type CostLine, type JobProject } from '@/features/projects/costing-api';
import { SOURCE_LABEL, Section, StatusChip, costElementLabel, numCell, tdClass, thClass } from './shared';

/** The project cost sub-ledger: every captured cost with its journal entry; posted lines can be reversed. */
export function CostLinesSection({ project, canPost }: { project: JobProject; canPost: boolean }) {
  const { data: lines = [], isLoading } = useCostLines(project.id);
  const [element, setElement] = useState('');
  const [status, setStatus] = useState('');
  const [reversing, setReversing] = useState<CostLine | null>(null);
  const reverse = useCostingMutation((id: string) => ({ method: 'post', money: true, url: `/projects/costs/${id}/reverse` }), { success: tx('Cost reversed') });

  const shown = useMemo(
    () => [...lines].reverse().filter((l) => (!element || l.costElement === element) && (!status || l.status === status)),
    [lines, element, status],
  );
  const site = (id?: string | null) => project.sites.find((s) => s.id === id)?.code;

  return (
    <Section
      title={tx('Cost lines')}
      actions={(
        <>
          <select className={cn(selectClass, 'h-8 w-36')} value={element} onChange={(e) => setElement(e.target.value)}>
            <option value="">{tx('All elements')}</option>
            {['material', 'labour', 'equipment', 'subcontract', 'other', 'overhead'].map((e) => <option key={e} value={e}>{costElementLabel(e)}</option>)}
          </select>
          <select className={cn(selectClass, 'h-8 w-32')} value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">{tx('All statuses')}</option>
            <option value="posted">{tx('Posted')}</option>
            <option value="reversed">{tx('Reversed')}</option>
          </select>
        </>
      )}
    >
      {isLoading ? <Skeleton className="h-32 w-full" /> : shown.length === 0 ? (
        <p className="text-sm text-muted-foreground">{tx('No cost captured yet.')}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="border-b">
              <tr>
                <th className={thClass}>{tx('Date')}</th>
                <th className={thClass}>{tx('Source')}</th>
                <th className={thClass}>{tx('WBS')}</th>
                <th className={thClass}>{tx('Element')}</th>
                <th className={thClass}>{tx('Description')}</th>
                <th className={cn(thClass, 'text-right')}>{tx('Qty')}</th>
                <th className={cn(thClass, 'text-right')}>{tx('Rate')}</th>
                <th className={cn(thClass, 'text-right')}>{tx('Amount')}</th>
                <th className={thClass}>{tx('Status')}</th>
                <th />
              </tr>
            </thead>
            <tbody className="divide-y">
              {shown.map((l) => (
                <tr key={l.id} className={cn(l.status === 'reversed' && 'text-muted-foreground')}>
                  <td className={cn(tdClass, 'whitespace-nowrap')}>{shortDate(l.date)}</td>
                  <td className={tdClass}>{SOURCE_LABEL[l.sourceType] ?? l.sourceType}</td>
                  <td className={tdClass}>{l.wbsCode}{site(l.siteId) && <span className="text-muted-foreground"> · {site(l.siteId)}</span>}</td>
                  <td className={tdClass}>{costElementLabel(l.costElement)}{l.excludeFromProgress && <span className="ml-1 text-xs text-muted-foreground">({tx('excl. progress')})</span>}</td>
                  <td className={cn(tdClass, 'max-w-xs truncate')} title={l.description ?? undefined}>{l.description}</td>
                  <td className={numCell}>{l.quantity == null ? '' : Number(l.quantity).toLocaleString()}</td>
                  <td className={numCell}>{l.rate == null ? '' : money(l.rate)}</td>
                  <td className={cn(numCell, l.status === 'reversed' && 'line-through')}>{money(l.amount)}</td>
                  <td className={tdClass}><StatusChip status={l.status} /></td>
                  <td className="px-2 py-1 text-right">
                    {canPost && l.status === 'posted' && (
                      <Button variant="ghost" size="sm" onClick={() => setReversing(l)}><Undo2 className="mr-1 h-4 w-4" />{tx('Reverse')}</Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <AlertDialog open={!!reversing} onOpenChange={(o) => !o && setReversing(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{tx('Reverse this cost?')}</AlertDialogTitle>
            <AlertDialogDescription>
              {tx('Posts a reversing journal entry dated today; a stock issue also returns the stock. Every cost line of the same journal entry is reversed with it.')}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{tx('Cancel')}</AlertDialogCancel>
            <AlertDialogAction disabled={reverse.isPending} onClick={() => reversing && reverse.mutate(reversing.id, { onSettled: () => setReversing(null) })}>
              {tx('Reverse')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Section>
  );
}

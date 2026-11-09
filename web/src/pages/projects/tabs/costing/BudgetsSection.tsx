import { useState } from 'react';
import { CheckCircle2, Pencil, Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { money } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { shortDate } from '@/features/projects/ui';
import { useBudget, useBudgets, useCostingMutation, type Budget, type JobProject } from '@/features/projects/costing-api';
import { CostElementSelect, Field, Section, StatusChip, WbsSelect, costElementLabel, numCell, tdClass, thClass, wbsLabel } from './shared';

interface DraftLine { wbsId: string; costElement: string; amount: string; revenue: string }
const emptyLine = (): DraftLine => ({ wbsId: '', costElement: 'material', amount: '', revenue: '' });

/** Budget versions (original, then revisions); draft → approved supersedes the previous approved version. */
export function BudgetsSection({ project, canManage }: { project: JobProject; canManage: boolean }) {
  const { data: budgets = [], isLoading } = useBudgets(project.id);
  const [open, setOpen] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Budget | null>(null);

  return (
    <Section
      title={tx('Budgets')}
      actions={canManage && (
        <Button size="sm" onClick={() => setCreating(true)} disabled={project.wbs.length === 0} title={project.wbs.length === 0 ? tx('Add a WBS element first') : undefined}>
          <Plus className="mr-1.5 h-4 w-4" />{budgets.length ? tx('New revision') : tx('New budget')}
        </Button>
      )}
    >
      {isLoading ? <Skeleton className="h-24 w-full" /> : budgets.length === 0 ? (
        <p className="text-sm text-muted-foreground">{tx('No budget yet. A budget is drafted per WBS element and cost element, then approved.')}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="border-b">
              <tr>
                <th className={thClass}>{tx('Version')}</th>
                <th className={thClass}>{tx('Kind')}</th>
                <th className={thClass}>{tx('Status')}</th>
                <th className={cn(thClass, 'text-right')}>{tx('Total')}</th>
                <th className={thClass}>{tx('Created')}</th>
                <th className={thClass}>{tx('Approved')}</th>
                <th className={thClass}>{tx('Notes')}</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {[...budgets].reverse().map((b) => (
                <tr key={b.id} className="cursor-pointer hover:bg-muted/40" onClick={() => setOpen(b.id)}>
                  <td className={cn(tdClass, 'font-medium')}>v{b.version}</td>
                  <td className={tdClass}>{b.kind === 'original' ? tx('Original') : tx('Revised')}</td>
                  <td className={tdClass}><StatusChip status={b.status} /></td>
                  <td className={numCell}>{money(b.total)}</td>
                  <td className={tdClass}>{shortDate(b.createdAt)}</td>
                  <td className={tdClass}>{b.approvedAt ? shortDate(b.approvedAt) : '—'}</td>
                  <td className={cn(tdClass, 'max-w-xs truncate text-muted-foreground')}>{b.notes}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <BudgetDialog budgetId={open} project={project} canManage={canManage} onClose={() => setOpen(null)} onEdit={(b) => { setOpen(null); setEditing(b); }} />
      {creating && <NewBudgetDialog project={project} latest={budgets[budgets.length - 1]} onClose={() => setCreating(false)} />}
      {editing && <NewBudgetDialog project={project} latest={editing} editing onClose={() => setEditing(null)} />}
    </Section>
  );
}

function BudgetDialog({ budgetId, project, canManage, onClose, onEdit }: {
  budgetId: string | null; project: JobProject; canManage: boolean; onClose: () => void; onEdit: (b: Budget) => void;
}) {
  const { data: b, isLoading } = useBudget(budgetId);
  const approve = useCostingMutation(() => ({ method: 'post', url: `/projects/budgets/${budgetId}/approve` }), { success: tx('Budget approved') });
  const remove = useCostingMutation(() => ({ method: 'delete', url: `/projects/budgets/${budgetId}` }), { success: tx('Draft budget deleted') });
  if (!budgetId) return null;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{b ? tx('Budget v{{version}}', { version: b.version }) : tx('Budget')}</DialogTitle>
          {b && <DialogDescription className="flex items-center gap-2"><StatusChip status={b.status} />{b.notes}</DialogDescription>}
        </DialogHeader>
        {isLoading || !b ? <Skeleton className="h-32 w-full" /> : (
          <div className="max-h-[60vh] overflow-auto">
            <table className="w-full text-sm">
              <thead className="border-b">
                <tr>
                  <th className={thClass}>{tx('WBS')}</th>
                  <th className={thClass}>{tx('Cost element')}</th>
                  <th className={cn(thClass, 'text-right')}>{tx('Cost')}</th>
                  <th className={cn(thClass, 'text-right')}>{tx('Revenue')}</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {(b.lines ?? []).map((l) => (
                  <tr key={l.id}>
                    <td className={tdClass}>{wbsLabel(project.wbs, l.wbsId)}</td>
                    <td className={tdClass}>{costElementLabel(l.costElement)}</td>
                    <td className={numCell}>{money(l.amount)}</td>
                    <td className={numCell}>{money(l.revenue)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot className="border-t font-semibold">
                <tr><td className={tdClass} colSpan={2}>{tx('Total')}</td><td className={numCell}>{money(b.total)}</td><td /></tr>
              </tfoot>
            </table>
          </div>
        )}
        <DialogFooter>
          {canManage && b?.status === 'draft' && (
            <Button
              variant="ghost"
              className="mr-auto text-rose-600 hover:text-rose-700"
              disabled={remove.isPending}
              onClick={() => window.confirm(tx('Delete draft budget v{{version}}?', { version: b.version })) && remove.mutate(undefined, { onSuccess: onClose })}
            >
              <Trash2 className="mr-1.5 h-4 w-4" />{tx('Delete draft')}
            </Button>
          )}
          <Button variant="outline" onClick={onClose}>{tx('Close')}</Button>
          {canManage && b?.status === 'draft' && (
            <Button variant="outline" onClick={() => onEdit(b)}><Pencil className="mr-1.5 h-4 w-4" />{tx('Edit draft')}</Button>
          )}
          {canManage && b?.status === 'draft' && (
            <Button onClick={() => approve.mutate(undefined, { onSuccess: onClose })} disabled={approve.isPending}>
              <CheckCircle2 className="mr-1.5 h-4 w-4" />{tx('Approve budget')}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** New draft (seeded from `latest`), or — with `editing` — changes to the draft `latest` itself. */
function NewBudgetDialog({ project, latest, editing, onClose }: { project: JobProject; latest?: Budget; editing?: boolean; onClose: () => void }) {
  const { data: base } = useBudget(latest?.id);
  const [lines, setLines] = useState<DraftLine[]>([emptyLine()]);
  const [notes, setNotes] = useState(editing ? latest?.notes ?? '' : '');
  const [seeded, setSeeded] = useState(false);
  if (!seeded && base?.lines?.length) {
    setSeeded(true);
    setLines(base.lines.map((l) => ({ wbsId: l.wbsId, costElement: l.costElement, amount: String(l.amount), revenue: String(l.revenue ?? '') })));
  }
  const setLine = (i: number, patch: Partial<DraftLine>) => setLines((cur) => cur.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const create = useCostingMutation(() => ({
    method: editing ? 'patch' : 'post',
    url: editing ? `/projects/budgets/${latest!.id}` : `/projects/${project.id}/budgets`,
    body: {
      // an emptied notes field clears the draft's notes
      notes: editing ? notes.trim() : notes.trim() || undefined,
      lines: lines.map((l) => ({ wbsId: l.wbsId, costElement: l.costElement, amount: l.amount.trim() || '0', revenue: l.revenue.trim() || undefined })),
    },
  }), { success: editing ? tx('Draft budget saved') : tx('Draft budget created') });
  const valid = lines.length > 0 && lines.every((l) => l.wbsId && l.amount.trim() !== '' && Number(l.amount) >= 0);
  const total = lines.reduce((s, l) => s + (Number(l.amount) || 0), 0);

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <DialogTitle>{editing ? tx('Edit draft budget v{{version}}', { version: latest?.version }) : latest ? tx('New budget revision') : tx('New budget')}</DialogTitle>
          <DialogDescription>
            {editing ? tx('Saving replaces every line of this draft.') : latest ? tx('Starts from the latest version. The draft replaces the approved budget once approved.') : tx('One line per WBS element and cost element.')}
          </DialogDescription>
        </DialogHeader>
        <div className="max-h-[55vh] space-y-2 overflow-auto">
          <div className="grid grid-cols-[1fr_9rem_8rem_8rem_2rem] gap-2 text-xs font-medium text-muted-foreground">
            <span>{tx('WBS element')}</span><span>{tx('Cost element')}</span><span className="text-right">{tx('Cost')}</span><span className="text-right">{tx('Revenue')}</span><span />
          </div>
          {lines.map((l, i) => (
            <div key={i} className="grid grid-cols-[1fr_9rem_8rem_8rem_2rem] items-center gap-2">
              <WbsSelect wbs={project.wbs} value={l.wbsId} onChange={(v) => setLine(i, { wbsId: v })} />
              <CostElementSelect value={l.costElement} onChange={(v) => setLine(i, { costElement: v })} />
              <Input type="number" step="0.01" min="0" className="text-right" value={l.amount} onChange={(e) => setLine(i, { amount: e.target.value })} />
              <Input type="number" step="0.01" min="0" className="text-right" value={l.revenue} onChange={(e) => setLine(i, { revenue: e.target.value })} />
              <button type="button" className="text-muted-foreground hover:text-rose-600 disabled:opacity-30" disabled={lines.length === 1} onClick={() => setLines(lines.filter((_, j) => j !== i))} aria-label={tx('Remove line')}>
                <Trash2 className="h-4 w-4" />
              </button>
            </div>
          ))}
          <Button type="button" variant="ghost" size="sm" onClick={() => setLines([...lines, emptyLine()])}><Plus className="mr-1.5 h-4 w-4" />{tx('Add line')}</Button>
        </div>
        <Field label={tx('Notes')}><Input value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <DialogFooter className="items-center">
          <span className="mr-auto text-sm text-muted-foreground">{tx('Total')}: <span className="font-semibold tabular-nums text-foreground">{money(total)}</span></span>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!valid || create.isPending} onClick={() => create.mutate(undefined, { onSuccess: onClose })}>{tx('Save draft')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import { useState } from 'react';
import { toast } from 'sonner';
import { Archive, ArrowRightLeft, UserRoundCheck, X, XCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { OptionalSelect } from '@/pages/fleet/fleet-ui';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { crmApi, useCrmLookups, useCrmMutation, type BulkResult, type DealKind } from '@/features/crm/suite-api';
import { Field } from './crm-ui';

type Action = 'assign' | 'move' | 'lost' | 'archive';

/**
 * Sticky bar for the rows ticked in a lead or opportunity list: assign, move stage (opportunities), mark lost or
 * archive. Each record is processed on its own; refused ones are reported, the rest go through.
 */
export function BulkBar({ kind, ids, onClear }: { kind: DealKind; ids: string[]; onClear: () => void }) {
  const { data: lk } = useCrmLookups();
  const [open, setOpen] = useState<Action | null>(null);
  const [owner, setOwner] = useState('');
  const [stageId, setStageId] = useState('');
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');
  const m = useCrmMutation(crmApi.bulk);
  if (ids.length === 0) return null;

  const run = (action: Action, extra: Record<string, unknown> = {}) =>
    m.mutate({ kind, ids, action, ...extra }, {
      onSuccess: (r: BulkResult) => {
        if (r.failed.length === 0) toast.success(`${r.done} ${tx('updated')}`);
        else toast.warning(`${r.done} ${tx('updated')}, ${r.failed.length} ${tx('refused')}: ${r.failed[0].error}`);
        setOpen(null);
        onClear();
      },
      onError: (e) => toast.error(apiErrorMessage(e, tx('Could not update'))),
    });

  const label = kind === 'lead' ? tx('leads') : tx('opportunities');
  return (
    <>
      <div role="toolbar" aria-label={tx('Bulk actions')} className="sticky bottom-3 z-20 mx-auto flex w-fit max-w-full flex-wrap items-center gap-2 rounded-xl border bg-card px-3 py-2 shadow-lg">
        <span className="text-sm font-medium">{ids.length} {label} {tx('selected')}</span>
        <Button size="sm" variant="outline" onClick={() => setOpen('assign')}><UserRoundCheck className="mr-1.5 h-4 w-4" />{tx('Assign')}</Button>
        {kind === 'opportunity' && <Button size="sm" variant="outline" onClick={() => setOpen('move')}><ArrowRightLeft className="mr-1.5 h-4 w-4" />{tx('Move stage')}</Button>}
        <Button size="sm" variant="outline" onClick={() => setOpen('lost')}><XCircle className="mr-1.5 h-4 w-4" />{tx('Mark lost')}</Button>
        <Button size="sm" variant="outline" onClick={() => setOpen('archive')}><Archive className="mr-1.5 h-4 w-4" />{tx('Archive')}</Button>
        <Button size="sm" variant="ghost" onClick={onClear} title={tx('Clear selection')} aria-label={tx('Clear selection')}><X className="h-4 w-4" /></Button>
      </div>
      <Dialog open={open != null} onOpenChange={(v) => !v && setOpen(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>
              {open === 'assign' ? tx('Assign') : open === 'move' ? tx('Move stage') : open === 'lost' ? tx('Mark lost') : tx('Archive')} — {ids.length} {label}
            </DialogTitle>
          </DialogHeader>
          {open === 'assign' && (
            <Field label={tx('Salesperson')} hint={tx('Leave empty to assign by the automatic rule.')}>
              <OptionalSelect value={owner} onChange={setOwner} options={(lk?.users ?? []).map((u) => ({ value: u.id, label: u.name }))} placeholder={tx('Automatic')} noneLabel={tx('Automatic')} />
            </Field>
          )}
          {open === 'move' && (
            <Field label={tx('Stage')}>
              <OptionalSelect value={stageId} onChange={setStageId} options={(lk?.stages ?? []).filter((s) => s.isActive).map((s) => ({ value: s.id, label: s.name }))} placeholder={tx('Choose a stage')} />
            </Field>
          )}
          {open === 'lost' && (
            <div className="space-y-3">
              <Field label={tx('Lost reason')}>
                <OptionalSelect value={reason} onChange={setReason} options={(lk?.lostReasons ?? []).map((r) => ({ value: r.id, label: r.name }))} placeholder={tx('No reason')} noneLabel={tx('No reason')} />
              </Field>
              <Field label={tx('Note')}><Input value={note} onChange={(e) => setNote(e.target.value)} /></Field>
            </div>
          )}
          {open === 'archive' && <p className="text-sm text-muted-foreground">{tx('Archived records leave the lists. Won opportunities are kept.')}</p>}
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(null)}>{tx('Cancel')}</Button>
            <Button
              variant={open === 'archive' ? 'destructive' : 'default'}
              disabled={m.isPending || (open === 'move' && !stageId)}
              onClick={() => run(open!, open === 'assign' ? { ownerId: owner || null } : open === 'move' ? { stageId } : open === 'lost' ? { lostReasonId: reason || null, lostNote: note || null } : {})}
            >
              {m.isPending ? tx('Working…') : tx('Apply')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

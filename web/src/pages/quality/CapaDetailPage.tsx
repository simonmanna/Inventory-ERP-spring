import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Check, RefreshCw, Save, X } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  CAPA_STATUS_LABEL, ROOT_CAUSE_LABEL, useAddCapaAction, useCapa, useCapaStatus, useCompleteCapaAction, useUpdateCapa,
  type Capa, type CapaStatus,
} from '@/features/quality/api';
import { CapaStatusPill, Field, Item } from './quality-ui';
import { ActionRow } from './CapasPage';

const MOVES: Record<CapaStatus, CapaStatus[]> = {
  open: ['in_progress', 'cancelled'],
  in_progress: ['verification', 'cancelled'],
  verification: ['closed', 'in_progress', 'cancelled'],
  closed: [],
  cancelled: [],
};

/** CAPA detail (ADR-S013): actions (add / done / cancel), status moves, effectiveness check (not by the owner). */
export function CapaDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: c, isLoading, isError, refetch } = useCapa(id);
  const has = useAuthStore((s) => s.hasPermission);
  const update = useUpdateCapa();
  const addAction = useAddCapaAction();
  const complete = useCompleteCapaAction();
  const [moveTo, setMoveTo] = useState<CapaStatus | null>(null);
  const [draft, setDraft] = useState({ kind: 'corrective' as 'corrective' | 'preventive', description: '', ownerUserId: '', dueDate: '' });
  const [effectiveness, setEffectiveness] = useState<string | null>(null);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !c) {
    return (
      <div className="p-8 text-center text-muted-foreground">
        <p>{tx('CAPA not found')}</p>
        <Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
      </div>
    );
  }
  const canEdit = has(PERMISSIONS.quality.capa);
  const open = c.status !== 'closed' && c.status !== 'cancelled';
  const effNotes = effectiveness ?? c.effectivenessNotes ?? '';
  const add = async () => {
    try {
      await addAction.mutateAsync({
        id: c.id, body: { kind: draft.kind, description: draft.description.trim(), ownerUserId: draft.ownerUserId || undefined, dueDate: draft.dueDate || undefined },
      });
      setDraft({ kind: 'corrective', description: '', ownerUserId: '', dueDate: '' });
      notify.success(tx('Action added'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not add the action')));
    }
  };
  const finish = async (actionId: string, status: 'done' | 'cancelled') => {
    try {
      await complete.mutateAsync({ actionId, status });
      notify.success(status === 'done' ? tx('Action done') : tx('Action cancelled'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not update the action')));
    }
  };
  const saveEffectiveness = async () => {
    try {
      await update.mutateAsync({ id: c.id, body: { effectivenessNotes: effNotes } });
      setEffectiveness(null);
      notify.success(tx('Saved'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the CAPA')));
    }
  };
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/quality/capas')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold">{c.capaNumber}</h1>
            <CapaStatusPill status={c.status} />
            {c.overdue && <span className="rounded-full bg-rose-100 px-2 py-0.5 text-xs font-medium text-rose-700">{tx('Overdue')}</span>}
          </div>
          <p className="text-sm text-muted-foreground">{c.title}</p>
        </div>
        {canEdit && (
          <div className="flex flex-wrap gap-2">
            {MOVES[c.status].map((to) => (
              <Button key={to} variant={to === 'cancelled' ? 'ghost' : to === 'closed' ? 'default' : 'outline'} onClick={() => setMoveTo(to)}>{CAPA_STATUS_LABEL[to]}</Button>
            ))}
          </div>
        )}
      </div>

      <Card>
        <CardContent className="pt-6">
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Item label={tx('NCR')}>{c.ncrId ? <Link className="text-primary hover:underline" to={`/quality/ncrs/${c.ncrId}`}>{c.ncrNumber}</Link> : null}</Item>
            <Item label={tx('Owner')}>{c.ownerName || null}</Item>
            <Item label={tx('Due date')}>{c.dueDate ? date(c.dueDate) : null}</Item>
            <Item label={tx('Root cause method')}>{c.rootCauseMethod ? ROOT_CAUSE_LABEL[c.rootCauseMethod] ?? c.rootCauseMethod : null}</Item>
            <Item label={tx('Verified')}>{c.verifiedAt ? dateTime(c.verifiedAt) : null}</Item>
            <Item label={tx('Closed')}>{c.closedAt ? dateTime(c.closedAt) : null}</Item>
          </dl>
          <div className="mt-4 rounded-md bg-muted/40 p-3 text-sm">
            <p className="text-xs font-medium text-muted-foreground">{tx('Root cause')}</p>
            <p className="whitespace-pre-wrap">{c.rootCause ?? '—'}</p>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Actions')}</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          {(c.actions ?? []).length === 0 && <p className="text-sm text-muted-foreground">{tx('No actions yet.')}</p>}
          <ul className="divide-y text-sm">
            {(c.actions ?? []).map((a) => (
              <li key={a.id} className="flex flex-wrap items-center gap-2 py-2">
                <span className="rounded bg-muted px-1.5 text-xs">{a.kind === 'preventive' ? tx('Preventive') : tx('Corrective')}</span>
                <span className="min-w-0 flex-1">{a.description}</span>
                <span className="text-muted-foreground">{a.ownerName || '—'}</span>
                <span className={a.overdue ? 'font-medium text-rose-600' : 'text-muted-foreground'}>{a.dueDate ? date(a.dueDate) : '—'}</span>
                <span className="text-xs">{a.status === 'done' ? tx('Done') : a.status === 'cancelled' ? tx('Cancelled') : tx('Open')}</span>
                {canEdit && open && a.status === 'open' && (
                  <>
                    <Button size="sm" variant="outline" onClick={() => finish(a.id, 'done')}><Check className="mr-1 h-4 w-4" /> {tx('Done')}</Button>
                    <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Cancel')} onClick={() => finish(a.id, 'cancelled')}><X className="h-4 w-4" /></Button>
                  </>
                )}
              </li>
            ))}
          </ul>
          {canEdit && open && (
            <div className="space-y-2">
              <ActionRow a={draft} onChange={(p) => setDraft((d) => ({ ...d, ...p }))} />
              <Button size="sm" onClick={add} disabled={!draft.description.trim() || addAction.isPending}>{tx('Add action')}</Button>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle className="text-base">{tx('Effectiveness check')}</CardTitle>
          {canEdit && open && <Button size="sm" variant="outline" onClick={saveEffectiveness} disabled={effectiveness === null || update.isPending}><Save className="mr-1 h-4 w-4" /> {tx('Save')}</Button>}
        </CardHeader>
        <CardContent>
          {canEdit && open
            ? <Textarea rows={3} value={effNotes} onChange={(e) => setEffectiveness(e.target.value)} placeholder={tx('How was the effectiveness of the actions verified?')} />
            : <p className="whitespace-pre-wrap text-sm">{c.effectivenessNotes ?? '—'}</p>}
          <p className="mt-2 text-xs text-muted-foreground">{tx('Closing the CAPA records the effectiveness check; the CAPA owner cannot close it.')}</p>
        </CardContent>
      </Card>

      {moveTo && <CapaMoveDialog capa={c} to={moveTo} notes={effNotes} onClose={() => setMoveTo(null)} />}
    </div>
  );
}

function CapaMoveDialog({ capa, to, notes: initial, onClose }: { capa: Capa; to: CapaStatus; notes: string; onClose: () => void }) {
  const [notes, setNotes] = useState(to === 'closed' ? initial : '');
  const move = useCapaStatus();
  const submit = async () => {
    try {
      await move.mutateAsync({ id: capa.id, status: to, notes });
      notify.success(CAPA_STATUS_LABEL[to]);
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not update the CAPA')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader><DialogTitle>{capa.capaNumber} → {CAPA_STATUS_LABEL[to]}</DialogTitle></DialogHeader>
        <Field label={to === 'closed' ? tx('Effectiveness check *') : tx('Notes')}><Textarea rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={move.isPending || (to === 'closed' && !notes.trim())}>{tx('Confirm')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import { useState } from 'react';
import { ArrowRight, Plus, Route as RouteIcon, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { apiMessage, notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useDeleteRoute, useRoutes, useSaveRoute, useTemplates, type ProcessingRoute } from '@/features/processing/api';
import { EmptyState, Field, Section, selectClass } from './processing-ui';

function RouteDialog({ route, onClose }: { route: ProcessingRoute | null; onClose: () => void }) {
  const save = useSaveRoute();
  const templates = useTemplates({ status: 'active' });
  const [code, setCode] = useState(route?.code ?? '');
  const [name, setName] = useState(route?.name ?? '');
  const [steps, setSteps] = useState<string[]>(route?.steps.map((s) => s.templateCode) ?? ['']);
  const submit = async () => {
    try {
      await save.mutateAsync({ id: route?.id, body: { code: code.trim(), name: name.trim(), steps: steps.filter(Boolean).map((templateCode) => ({ templateCode })) } });
      notify.success(tx('Saved'));
      onClose();
    } catch (err) { notify.error(apiMessage(err, tx('Could not save the route'))); }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{route ? tx('Edit route') : tx('New route')}</DialogTitle></DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={tx('Code')}><Input value={code} disabled={!!route} onChange={(e) => setCode(e.target.value)} /></Field>
          <Field label={tx('Name')}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
        </div>
        <div className="space-y-2">
          {steps.map((s, i) => (
            <div key={`${i}-${s}`} className="flex items-center gap-2">
              <span className="w-5 text-right text-xs text-muted-foreground">{i + 1}</span>
              <select className={selectClass} aria-label={tx('Step {{n}}', { n: i + 1 })} value={s} onChange={(e) => setSteps((r) => r.map((x, j) => (j === i ? e.target.value : x)))}>
                <option value="">{tx('Choose a step')}</option>
                {(templates.data ?? []).map((t) => <option key={t.id} value={t.code}>{t.name}</option>)}
              </select>
              <Button type="button" variant="ghost" size="icon" className="h-9 w-9" aria-label={tx('Remove step')} disabled={steps.length <= 1} onClick={() => setSteps((r) => r.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
            </div>
          ))}
          <Button type="button" variant="outline" size="sm" onClick={() => setSteps((r) => [...r, ''])}><Plus className="mr-1 h-3.5 w-3.5" /> {tx('Add step')}</Button>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Close')}</Button>
          <Button disabled={!code.trim() || !name.trim() || !steps.some(Boolean) || save.isPending} onClick={submit}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Usual chains of steps. A route is guidance: it never blocks a sale, a stop, or a different path. */
export function ProcessingRoutesPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const { data, isLoading } = useRoutes();
  const remove = useDeleteRoute();
  const [editing, setEditing] = useState<ProcessingRoute | null | 'new'>(null);
  const canEdit = has(PERMISSIONS.processing.create);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={RouteIcon} title={tx('Processing Routes')} description={tx('The usual order of steps. A route only guides: you can sell, stop or take a different path at any point.')}
        actions={canEdit && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New route')}</Button>} />
      {!isLoading && (data ?? []).length === 0 && <EmptyState>{tx('No route yet.')}</EmptyState>}
      {(data ?? []).map((r) => (
        <Section key={r.id} title={`${r.name} (${r.code})`} actions={canEdit && (
          <>
            <Button variant="outline" size="sm" onClick={() => setEditing(r)}>{tx('Edit')}</Button>
            <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Delete route')} onClick={async () => { try { await remove.mutateAsync(r.id); } catch (err) { notify.error(apiMessage(err, tx('Could not delete'))); } }}><Trash2 className="h-4 w-4" /></Button>
          </>
        )}>
          <ol className="flex flex-wrap items-center gap-2 text-sm">
            {r.steps.map((s, i) => (
              <li key={s.id} className="flex items-center gap-2">
                {i > 0 && <ArrowRight className="h-4 w-4 text-muted-foreground" aria-hidden />}
                <span className={`rounded-lg border px-3 py-1.5 ${s.templateId ? '' : 'border-dashed text-muted-foreground'}`}>{s.templateName ?? tx('{{code}} (no active version)', { code: s.templateCode })}</span>
              </li>
            ))}
          </ol>
        </Section>
      ))}
      {editing && <RouteDialog route={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

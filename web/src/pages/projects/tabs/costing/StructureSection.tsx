import { useMemo, useState } from 'react';
import { Pencil, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { selectClass } from '@/features/projects/ui';
import { useCostingMutation, useLocationOptions, type JobProject, type LocationOption, type ProjectSite, type WbsElement } from '@/features/projects/costing-api';
import { Field, Section, WbsSelect, tdClass, thClass } from './shared';

/** WBS elements in tree order (parent before children) with their depth. */
function tree(wbs: WbsElement[]): Array<{ w: WbsElement; depth: number }> {
  const byParent = new Map<string, WbsElement[]>();
  for (const w of wbs) {
    const k = w.parentId && wbs.some((p) => p.id === w.parentId) ? w.parentId : '';
    byParent.set(k, [...(byParent.get(k) ?? []), w]);
  }
  const out: Array<{ w: WbsElement; depth: number }> = [];
  const walk = (parent: string, depth: number) => {
    for (const w of (byParent.get(parent) ?? []).sort((a, b) => a.code.localeCompare(b.code))) {
      out.push({ w, depth });
      if (depth < 20) walk(w.id, depth + 1);
    }
  };
  walk('', 0);
  return out;
}

/** The project's work breakdown structure (what cost is booked against) and its sites (where work happens). */
export function StructureSection({ project, canManage }: { project: JobProject; canManage: boolean }) {
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <WbsCard project={project} canManage={canManage} />
      <SitesCard project={project} canManage={canManage} />
    </div>
  );
}

function WbsCard({ project, canManage }: { project: JobProject; canManage: boolean }) {
  const blank = { code: '', name: '', parentId: '', isBillable: true, isCostable: true };
  const [f, setF] = useState(blank);
  const add = useCostingMutation(() => ({
    method: 'post',
    url: `/projects/${project.id}/wbs`,
    body: { code: f.code.trim(), name: f.name.trim(), parentId: f.parentId || undefined, isBillable: f.isBillable, isCostable: f.isCostable },
  }), { success: tx('WBS element added') });
  const rows = useMemo(() => tree(project.wbs), [project.wbs]);
  const [editing, setEditing] = useState<WbsElement | null>(null);
  const remove = useCostingMutation<WbsElement>((w) => ({ method: 'delete', url: `/projects/wbs/${w.id}` }), { success: tx('WBS element deleted') });

  return (
    <Section title={tx('Work breakdown structure')}>
      {rows.length === 0 ? (
        <p className="mb-3 text-sm text-muted-foreground">{tx('No WBS elements yet. Cost, budgets and forecasts are booked against a WBS element.')}</p>
      ) : (
        <table className="mb-3 w-full text-sm">
          <thead className="border-b">
            <tr><th className={thClass}>{tx('Code')}</th><th className={thClass}>{tx('Name')}</th><th className={thClass}>{tx('Costable')}</th><th className={thClass}>{tx('Billable')}</th>{canManage && <th className={thClass} />}</tr>
          </thead>
          <tbody className="divide-y">
            {rows.map(({ w, depth }) => (
              <tr key={w.id}>
                <td className={cn(tdClass, 'font-mono')} style={{ paddingLeft: `${0.75 + depth * 1.25}rem` }}>{w.code}</td>
                <td className={tdClass}>{w.name}</td>
                <td className={tdClass}>{w.isCostable ? tx('Yes') : tx('No')}</td>
                <td className={tdClass}>{w.isBillable ? tx('Yes') : tx('No')}</td>
                {canManage && (
                  <RowActions
                    onEdit={() => setEditing(w)}
                    onDelete={() => window.confirm(tx('Delete WBS element {{code}}? Tasks linked to it lose the link.', { code: w.code })) && remove.mutate(w)}
                  />
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {canManage && (
        <form
          className="space-y-2 border-t pt-3"
          onSubmit={(e) => { e.preventDefault(); if (f.code.trim() && f.name.trim()) add.mutate(undefined, { onSuccess: () => setF(blank) }); }}
        >
          <div className="grid gap-2 sm:grid-cols-[8rem_1fr]">
            <Input placeholder={tx('Code, e.g. 1.2')} value={f.code} onChange={(e) => setF({ ...f, code: e.target.value })} />
            <Input placeholder={tx('Name, e.g. Foundations')} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <WbsSelect wbs={project.wbs} value={f.parentId} onChange={(v) => setF({ ...f, parentId: v })} allowEmpty={tx('No parent (top level)')} className="w-56" />
            <label className="flex items-center gap-1.5 text-sm"><input type="checkbox" className="h-4 w-4 accent-primary" checked={f.isCostable} onChange={(e) => setF({ ...f, isCostable: e.target.checked })} />{tx('Costable')}</label>
            <label className="flex items-center gap-1.5 text-sm"><input type="checkbox" className="h-4 w-4 accent-primary" checked={f.isBillable} onChange={(e) => setF({ ...f, isBillable: e.target.checked })} />{tx('Billable')}</label>
            <Button type="submit" size="sm" className="ml-auto" disabled={!f.code.trim() || !f.name.trim() || add.isPending}>{tx('Add WBS element')}</Button>
          </div>
        </form>
      )}
      {editing && <EditWbsDialog project={project} wbs={editing} onClose={() => setEditing(null)} />}
    </Section>
  );
}

function SitesCard({ project, canManage }: { project: JobProject; canManage: boolean }) {
  const blank = { code: '', name: '', address: '', deliveryLocationId: '' };
  const [f, setF] = useState(blank);
  const { data: locations = [] } = useLocationOptions(true);
  const add = useCostingMutation(() => ({
    method: 'post',
    url: `/projects/${project.id}/sites`,
    body: { code: f.code.trim(), name: f.name.trim(), address: f.address.trim() || undefined, deliveryLocationId: f.deliveryLocationId || undefined },
  }), { success: tx('Site added') });
  const loc = (id?: string | null) => locations.find((l) => l.id === id)?.name;
  const [editing, setEditing] = useState<ProjectSite | null>(null);
  const remove = useCostingMutation<ProjectSite>((s) => ({ method: 'delete', url: `/projects/sites/${s.id}` }), { success: tx('Site deleted') });

  return (
    <Section title={tx('Sites')}>
      {project.sites.length === 0 ? (
        <p className="mb-3 text-sm text-muted-foreground">{tx('No sites yet. A site can carry a delivery location that stock issues default to.')}</p>
      ) : (
        <table className="mb-3 w-full text-sm">
          <thead className="border-b">
            <tr><th className={thClass}>{tx('Code')}</th><th className={thClass}>{tx('Name')}</th><th className={thClass}>{tx('Address')}</th><th className={thClass}>{tx('Delivery location')}</th>{canManage && <th className={thClass} />}</tr>
          </thead>
          <tbody className="divide-y">
            {project.sites.map((s) => (
              <tr key={s.id}>
                <td className={cn(tdClass, 'font-mono')}>{s.code}</td>
                <td className={tdClass}>{s.name}</td>
                <td className={cn(tdClass, 'text-muted-foreground')}>{s.address}</td>
                <td className={tdClass}>{s.deliveryLocationId ? loc(s.deliveryLocationId) ?? '…' : '—'}</td>
                {canManage && (
                  <RowActions
                    onEdit={() => setEditing(s)}
                    onDelete={() => window.confirm(tx('Delete site {{code}}? Tasks linked to it lose the link.', { code: s.code })) && remove.mutate(s)}
                  />
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {canManage && (
        <form
          className="space-y-2 border-t pt-3"
          onSubmit={(e) => { e.preventDefault(); if (f.code.trim() && f.name.trim()) add.mutate(undefined, { onSuccess: () => setF(blank) }); }}
        >
          <div className="grid gap-2 sm:grid-cols-[8rem_1fr]">
            <Input placeholder={tx('Code')} value={f.code} onChange={(e) => setF({ ...f, code: e.target.value })} />
            <Input placeholder={tx('Name, e.g. North block')} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
          </div>
          <Input placeholder={tx('Address (optional)')} value={f.address} onChange={(e) => setF({ ...f, address: e.target.value })} />
          <div className="flex flex-wrap items-center gap-2">
            <select className={cn(selectClass, 'w-56')} value={f.deliveryLocationId} onChange={(e) => setF({ ...f, deliveryLocationId: e.target.value })}>
              <option value="">{tx('No delivery location')}</option>
              {locations.map((l) => <option key={l.id} value={l.id}>{l.code ? `${l.code} · ` : ''}{l.name}</option>)}
            </select>
            <Button type="submit" size="sm" className="ml-auto" disabled={!f.code.trim() || !f.name.trim() || add.isPending}>{tx('Add site')}</Button>
          </div>
        </form>
      )}
      {editing && <EditSiteDialog site={editing} locations={locations} onClose={() => setEditing(null)} />}
    </Section>
  );
}

function RowActions({ onEdit, onDelete }: { onEdit: () => void; onDelete: () => void }) {
  return (
    <td className={cn(tdClass, 'w-16 whitespace-nowrap text-right')}>
      <button type="button" className="mr-2 text-muted-foreground hover:text-foreground" onClick={onEdit} aria-label={tx('Edit')}>
        <Pencil className="h-3.5 w-3.5" />
      </button>
      <button type="button" className="text-muted-foreground hover:text-rose-600" onClick={onDelete} aria-label={tx('Delete')}>
        <Trash2 className="h-3.5 w-3.5" />
      </button>
    </td>
  );
}

/** Ids of `id` and everything below it: a WBS element cannot move under itself or one of its children. */
function selfAndDescendants(wbs: WbsElement[], id: string): Set<string> {
  const out = new Set([id]);
  for (let grew = true; grew;) {
    grew = false;
    for (const w of wbs) {
      if (w.parentId && out.has(w.parentId) && !out.has(w.id)) { out.add(w.id); grew = true; }
    }
  }
  return out;
}

function EditWbsDialog({ project, wbs, onClose }: { project: JobProject; wbs: WbsElement; onClose: () => void }) {
  const [f, setF] = useState({ code: wbs.code, name: wbs.name, parentId: wbs.parentId ?? '', isBillable: wbs.isBillable, isCostable: wbs.isCostable });
  const blocked = useMemo(() => selfAndDescendants(project.wbs, wbs.id), [project.wbs, wbs.id]);
  const save = useCostingMutation(() => ({
    method: 'patch',
    url: `/projects/wbs/${wbs.id}`,
    body: { code: f.code.trim(), name: f.name.trim(), parentId: f.parentId, isBillable: f.isBillable, isCostable: f.isCostable },
  }), { success: tx('WBS element saved') });
  const valid = !!f.code.trim() && !!f.name.trim();
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Edit WBS element')}</DialogTitle></DialogHeader>
        <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); if (valid) save.mutate(undefined, { onSuccess: onClose }); }}>
          <div className="grid gap-3 sm:grid-cols-[8rem_1fr]">
            <Field label={tx('Code')}><Input value={f.code} onChange={(e) => setF({ ...f, code: e.target.value })} /></Field>
            <Field label={tx('Name')}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
          </div>
          <Field label={tx('Parent')}>
            <WbsSelect wbs={project.wbs.filter((w) => !blocked.has(w.id))} value={f.parentId} onChange={(v) => setF({ ...f, parentId: v })} allowEmpty={tx('No parent (top level)')} />
          </Field>
          <div className="flex gap-4">
            <label className="flex items-center gap-1.5 text-sm"><input type="checkbox" className="h-4 w-4 accent-primary" checked={f.isCostable} onChange={(e) => setF({ ...f, isCostable: e.target.checked })} />{tx('Costable')}</label>
            <label className="flex items-center gap-1.5 text-sm"><input type="checkbox" className="h-4 w-4 accent-primary" checked={f.isBillable} onChange={(e) => setF({ ...f, isBillable: e.target.checked })} />{tx('Billable')}</label>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
            <Button type="submit" disabled={!valid || save.isPending}>{tx('Save')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function EditSiteDialog({ site, locations, onClose }: { site: ProjectSite; locations: LocationOption[]; onClose: () => void }) {
  const [f, setF] = useState({ code: site.code, name: site.name, address: site.address ?? '', deliveryLocationId: site.deliveryLocationId ?? '' });
  // "" clears the optional address / delivery location on the server
  const save = useCostingMutation(() => ({
    method: 'patch',
    url: `/projects/sites/${site.id}`,
    body: { code: f.code.trim(), name: f.name.trim(), address: f.address.trim(), deliveryLocationId: f.deliveryLocationId },
  }), { success: tx('Site saved') });
  const valid = !!f.code.trim() && !!f.name.trim();
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Edit site')}</DialogTitle></DialogHeader>
        <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); if (valid) save.mutate(undefined, { onSuccess: onClose }); }}>
          <div className="grid gap-3 sm:grid-cols-[8rem_1fr]">
            <Field label={tx('Code')}><Input value={f.code} onChange={(e) => setF({ ...f, code: e.target.value })} /></Field>
            <Field label={tx('Name')}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
          </div>
          <Field label={tx('Address')}><Input value={f.address} onChange={(e) => setF({ ...f, address: e.target.value })} /></Field>
          <Field label={tx('Delivery location')}>
            <select className={selectClass} value={f.deliveryLocationId} onChange={(e) => setF({ ...f, deliveryLocationId: e.target.value })}>
              <option value="">{tx('No delivery location')}</option>
              {locations.map((l) => <option key={l.id} value={l.id}>{l.code ? `${l.code} · ` : ''}{l.name}</option>)}
            </select>
          </Field>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
            <Button type="submit" disabled={!valid || save.isPending}>{tx('Save')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

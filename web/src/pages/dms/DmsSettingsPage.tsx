import { useEffect, useState } from 'react';
import { Plus, Save, Shield, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useRoles } from '@/features/staff/api';
import {
  useAccessRules, useArchiveWorkspace, useCreateTag, useCreateTagCategory, useCreateWorkspace, useDeleteTag,
  useDeleteTagCategory, useReplaceAccessRules, useRevokeShareLink, useShareLinks, useTagCategories, useTags,
  useUpdateWorkspace, useWorkspaces, type DmsAccessRule, type DmsWorkspace,
} from '@/features/dms/api';

/** DMS configuration: workspaces + access rules, tags, share links. */
export function DmsSettingsPage() {
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.dms.workspaceManage));
  const canTags = useAuthStore((s) => s.hasPermission(PERMISSIONS.dms.tagManage));
  const canShare = useAuthStore((s) => s.hasPermission(PERMISSIONS.dms.documentShare));
  const first = canManage ? 'workspaces' : canTags ? 'tags' : 'shares';
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">{tx('Document settings')}</h1>
      <Tabs defaultValue={first}>
        <TabsList>
          {canManage && <TabsTrigger value="workspaces">{tx('Workspaces')}</TabsTrigger>}
          {canTags && <TabsTrigger value="tags">{tx('Tags')}</TabsTrigger>}
          {canShare && <TabsTrigger value="shares">{tx('Share links')}</TabsTrigger>}
        </TabsList>
        {canManage && <TabsContent value="workspaces"><WorkspacesTab /></TabsContent>}
        {canTags && <TabsContent value="tags"><TagsTab /></TabsContent>}
        {canShare && <TabsContent value="shares"><SharesTab /></TabsContent>}
      </Tabs>
    </div>
  );
}

function WorkspacesTab() {
  const { data } = useWorkspaces();
  const create = useCreateWorkspace();
  const [name, setName] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const ws = data?.find((w) => w.id === selected) ?? null;
  const add = async () => {
    try {
      const w = await create.mutateAsync({ name: name.trim() });
      setName('');
      setSelected(w.id);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not create the workspace')));
    }
  };
  return (
    <div className="grid gap-4 lg:grid-cols-[320px_1fr]">
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Workspaces')}</CardTitle></CardHeader>
        <CardContent className="space-y-2">
          <div className="flex gap-2">
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={tx('New workspace name')} />
            <Button size="icon" onClick={add} disabled={!name.trim()}><Plus className="h-4 w-4" /></Button>
          </div>
          <ul className="divide-y rounded-md border text-sm">
            {(data ?? []).map((w) => (
              <li key={w.id}>
                <button type="button" onClick={() => setSelected(w.id)}
                  className={`flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-accent ${selected === w.id ? 'bg-accent' : ''}`}>
                  <span className="h-2.5 w-2.5 rounded-full" style={{ background: w.color ?? '#94a3b8' }} />
                  <span className="flex-1">{w.name}</span>
                  {w.isSystem && <Badge variant="outline">{tx('System')}</Badge>}
                  {w.requiresApproval && <Badge variant="secondary">{tx('Approval')}</Badge>}
                </button>
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>
      {ws ? <WorkspaceEditor key={ws.id} ws={ws} /> : (
        <p className="p-4 text-sm text-muted-foreground">{tx('Select a workspace to edit it and its access rules.')}</p>
      )}
    </div>
  );
}

function WorkspaceEditor({ ws }: { ws: DmsWorkspace }) {
  const update = useUpdateWorkspace();
  const archive = useArchiveWorkspace();
  const { data: rules } = useAccessRules(ws.id);
  const replace = useReplaceAccessRules();
  const { data: roles } = useRoles();
  const [name, setName] = useState(ws.name);
  const [color, setColor] = useState(ws.color ?? '#64748b');
  const [perm, setPerm] = useState(ws.requiredPermission ?? '');
  const [approval, setApproval] = useState(ws.requiresApproval);
  const [draft, setDraft] = useState<Array<Pick<DmsAccessRule, 'roleId' | 'userId' | 'level'>>>([]);
  useEffect(() => {
    setDraft((rules ?? []).map((r) => ({ roleId: r.roleId, userId: r.userId, level: r.level })));
  }, [rules]);

  const save = async () => {
    try {
      await update.mutateAsync({ id: ws.id, body: { name, color, requiredPermission: perm, requiresApproval: approval } });
      notify.success(tx('Saved'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save')));
    }
  };
  const saveRules = async () => {
    try {
      await replace.mutateAsync({ id: ws.id, rules: draft.filter((r) => r.roleId || r.userId) });
      notify.success(tx('Access rules saved'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the access rules')));
    }
  };

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader><CardTitle className="text-base">{ws.name}</CardTitle></CardHeader>
        <CardContent className="grid gap-3 text-sm sm:grid-cols-2">
          <label className="grid gap-1">{tx('Name')}<Input value={name} onChange={(e) => setName(e.target.value)} /></label>
          <label className="grid gap-1">{tx('Colour')}<Input type="color" value={color} onChange={(e) => setColor(e.target.value)} /></label>
          <label className="grid gap-1 sm:col-span-2">{tx('Required permission (optional)')}
            <Input value={perm} onChange={(e) => setPerm(e.target.value)} placeholder="sales:order:read" />
          </label>
          <label className="flex items-center gap-2 sm:col-span-2">
            <input type="checkbox" checked={approval} onChange={(e) => setApproval(e.target.checked)} />
            {tx('New uploads need approval')}
          </label>
          <div className="flex gap-2 sm:col-span-2">
            <Button onClick={save}><Save className="mr-1 h-4 w-4" />{tx('Save')}</Button>
            {!ws.isSystem && (
              <Button variant="outline" className="text-destructive" onClick={() => archive.mutateAsync(ws.id)
                .then(() => notify.success(tx('Workspace archived')), (err) => notify.error(apiMessage(err, tx('Could not archive'))))}>
                <Trash2 className="mr-1 h-4 w-4" />{tx('Archive')}
              </Button>
            )}
          </div>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2 text-base"><Shield className="h-4 w-4" />{tx('Access rules')}</CardTitle></CardHeader>
        <CardContent className="space-y-2 text-sm">
          <p className="text-xs text-muted-foreground">
            {tx('No rules: every user with document access (and the required permission) can use the workspace. With rules: only the listed roles.')}
          </p>
          {draft.map((r, i) => (
            <div key={i} className="flex gap-2">
              <Select value={r.roleId ?? ''} onValueChange={(v) => setDraft(draft.map((x, j) => (j === i ? { ...x, roleId: v, userId: null } : x)))}>
                <SelectTrigger className="flex-1"><SelectValue placeholder={tx('Role')} /></SelectTrigger>
                <SelectContent>{(roles ?? []).map((ro) => <SelectItem key={ro.id} value={ro.id}>{ro.name}</SelectItem>)}</SelectContent>
              </Select>
              <Select value={r.level} onValueChange={(v) => setDraft(draft.map((x, j) => (j === i ? { ...x, level: v as DmsAccessRule['level'] } : x)))}>
                <SelectTrigger className="w-32"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="read">{tx('Read')}</SelectItem>
                  <SelectItem value="write">{tx('Write')}</SelectItem>
                  <SelectItem value="manage">{tx('Manage')}</SelectItem>
                </SelectContent>
              </Select>
              <Button variant="ghost" size="icon" onClick={() => setDraft(draft.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
            </div>
          ))}
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => setDraft([...draft, { roleId: null, userId: null, level: 'read' }])}>
              <Plus className="mr-1 h-4 w-4" />{tx('Add rule')}
            </Button>
            <Button size="sm" onClick={saveRules}>{tx('Save rules')}</Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

function TagsTab() {
  const { data: tags } = useTags();
  const { data: categories } = useTagCategories();
  const createTag = useCreateTag();
  const deleteTag = useDeleteTag();
  const createCat = useCreateTagCategory();
  const deleteCat = useDeleteTagCategory();
  const [name, setName] = useState('');
  const [cat, setCat] = useState('');
  const [catName, setCatName] = useState('');
  const err = (e: unknown) => notify.error(apiMessage(e, tx('Action failed')));
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Tags')}</CardTitle></CardHeader>
        <CardContent className="space-y-2 text-sm">
          <div className="flex gap-2">
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={tx('Tag name')} />
            <Select value={cat} onValueChange={setCat}>
              <SelectTrigger className="w-40"><SelectValue placeholder={tx('Category')} /></SelectTrigger>
              <SelectContent>{(categories ?? []).map((c) => <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>)}</SelectContent>
            </Select>
            <Button size="icon" disabled={!name.trim()} onClick={() => createTag.mutateAsync({ name: name.trim(), categoryId: cat || null })
              .then(() => setName(''), err)}><Plus className="h-4 w-4" /></Button>
          </div>
          <ul className="divide-y rounded-md border">
            {(tags ?? []).map((t) => (
              <li key={t.id} className="flex items-center gap-2 px-3 py-1.5">
                <span className="flex-1">{t.name}</span>
                {t.categoryName && <Badge variant="outline">{t.categoryName}</Badge>}
                <span className="text-xs text-muted-foreground">{t.documentCount ?? 0}</span>
                <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => deleteTag.mutateAsync(t.id).catch(err)}>
                  <Trash2 className="h-4 w-4" />
                </Button>
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Tag categories')}</CardTitle></CardHeader>
        <CardContent className="space-y-2 text-sm">
          <div className="flex gap-2">
            <Input value={catName} onChange={(e) => setCatName(e.target.value)} placeholder={tx('Category name')} />
            <Button size="icon" disabled={!catName.trim()} onClick={() => createCat.mutateAsync({ name: catName.trim() })
              .then(() => setCatName(''), err)}><Plus className="h-4 w-4" /></Button>
          </div>
          <ul className="divide-y rounded-md border">
            {(categories ?? []).map((c) => (
              <li key={c.id} className="flex items-center gap-2 px-3 py-1.5">
                <span className="flex-1">{c.name}</span>
                <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => deleteCat.mutateAsync(c.id).catch(err)}>
                  <Trash2 className="h-4 w-4" />
                </Button>
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>
    </div>
  );
}

function SharesTab() {
  const { data } = useShareLinks();
  const revoke = useRevokeShareLink();
  return (
    <Card>
      <CardContent className="p-0">
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-muted-foreground">
            <tr><th className="p-2">{tx('Link')}</th><th className="p-2">{tx('Scope')}</th><th className="p-2">{tx('Expires')}</th>
              <th className="p-2">{tx('Downloads')}</th><th className="p-2" /></tr>
          </thead>
          <tbody>
            {(data ?? []).map((l) => (
              <tr key={l.id} className="border-t">
                <td className="p-2">{l.name ?? '—'} {l.hasPassword && <Badge variant="outline">{tx('password')}</Badge>}</td>
                <td className="p-2">{l.scope === 'folder' ? tx('Folder') : tx('{{n}} document(s)', { n: l.documentIds.length })}
                  {l.allowUpload && <Badge variant="secondary" className="ml-1">{tx('upload')}</Badge>}</td>
                <td className="p-2">{dateTime(l.expiresAt)}</td>
                <td className="p-2">{l.downloadCount}{l.maxDownloads ? ` / ${l.maxDownloads}` : ''}</td>
                <td className="p-2 text-right">
                  {l.active ? (
                    <Button size="sm" variant="ghost" className="text-destructive"
                      onClick={() => revoke.mutateAsync(l.id).then(() => notify.success(tx('Link revoked')))}>{tx('Revoke')}</Button>
                  ) : <Badge variant="outline">{l.revokedAt ? tx('Revoked') : tx('Expired')}</Badge>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </CardContent>
    </Card>
  );
}

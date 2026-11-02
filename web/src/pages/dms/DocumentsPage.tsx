import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  Archive, ArchiveRestore, CalendarClock, Check, ChevronRight, Clock, Download, Eye, Folder, FolderPlus, Grid2x2,
  Hourglass, Inbox, Link2, List, Loader2, Lock, Search, Share2, Tag as TagIcon, Trash2, Undo2, Unlock, Upload, User,
  X,
} from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, dateTime, formatBytes, relativeTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  useAddVersion, useBulk, useCreateFolder, useDeleteDocument, useDocument, useDocumentAction, useDocuments,
  useFolders, useRestoreDocument, useRestoreVersion, useTags, useTimeline, useUpdateDocument, useUploadDocuments,
  useUnlinkDocument, useWorkspaces, type DmsDocument, type DmsFolder, type DmsListFilter,
} from '@/features/dms/api';
import { FilePreviewDialog } from '@/components/dms/FilePreviewDialog';
import { downloadDocument, fileIcon, filesOf } from '@/components/dms/file-utils';
import { ShareDialog } from './ShareDialog';

type QuickFilter = 'all' | 'mine' | 'expiring' | 'pending' | 'archived' | 'trash';

const STATUS_LABEL: Record<string, string> = {
  active: 'Active', pending_approval: 'Pending approval', approved: 'Approved', rejected: 'Rejected', archived: 'Archived',
};

/** The Documents app (DMS, ADR-S044): workspaces, folders, tags, search, preview, versions, sharing. */
export function DocumentsPage() {
  const [params, setParams] = useSearchParams();
  const wsId = params.get('ws') ?? undefined;
  const folderId = params.get('folder') ?? undefined;
  const tagId = params.get('tag') ?? undefined;
  const selectedId = params.get('document');
  const quick = (params.get('filter') as QuickFilter) ?? 'all';
  const view = params.get('view') === 'list' ? 'list' : 'grid';
  const [q, setQ] = useState(params.get('q') ?? '');
  const [debouncedQ, setDebouncedQ] = useState(q);
  const [page, setPage] = useState(1);
  const [selection, setSelection] = useState<string[]>([]);
  const [dragOver, setDragOver] = useState(false);
  const [progress, setProgress] = useState<number | null>(null);
  const [preview, setPreview] = useState<DmsDocument | null>(null);
  const uploadInput = useRef<HTMLInputElement>(null);

  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.dms.documentWrite));
  const canDelete = useAuthStore((s) => s.hasPermission(PERMISSIONS.dms.documentDelete));

  const set = (patch: Record<string, string | undefined | null>) => {
    const next = new URLSearchParams(params);
    Object.entries(patch).forEach(([k, v]) => (v == null || v === '' ? next.delete(k) : next.set(k, v)));
    setParams(next, { replace: true });
    setPage(1);
    setSelection([]);
  };

  useEffect(() => {
    const t = setTimeout(() => setDebouncedQ(q.trim()), 300);
    return () => clearTimeout(t);
  }, [q]);

  const { data: workspaces, isLoading: wsLoading } = useWorkspaces();
  const { data: folders } = useFolders(wsId);
  const { data: tags } = useTags();
  const workspace = workspaces?.find((w) => w.id === wsId);
  const folder = folders?.find((f) => f.id === folderId);

  const in30 = useMemo(() => {
    const d = new Date();
    d.setDate(d.getDate() + 30);
    return d.toISOString().slice(0, 10);
  }, []);

  const filter: DmsListFilter = {
    workspaceId: wsId,
    folderId,
    root: wsId && !folderId && !debouncedQ && quick === 'all' ? true : undefined,
    tagId,
    q: debouncedQ || undefined,
    mine: quick === 'mine' || undefined,
    expiringBefore: quick === 'expiring' ? in30 : undefined,
    status: quick === 'pending' ? 'pending_approval' : quick === 'archived' ? 'archived' : undefined,
    deleted: quick === 'trash' || undefined,
    sort: quick === 'expiring' ? 'expiry' : undefined,
    page,
    pageSize: 60,
  };
  const { data, isLoading, isFetching } = useDocuments(filter);
  const docs = data?.data ?? [];

  const upload = useUploadDocuments();
  const createFolder = useCreateFolder();
  const bulk = useBulk();
  const update = useUpdateDocument();

  const uploadTarget = workspace ?? workspaces?.find((w) => w.code === 'general');
  const canUploadHere = canWrite && quick !== 'trash' && (!workspace || workspace.access !== 'read');

  const send = async (files: File[]) => {
    if (!files.length || !canUploadHere) return;
    setProgress(0);
    try {
      await upload.mutateAsync({
        files, options: { workspaceId: uploadTarget?.id, folderId: folderId ?? null }, onProgress: setProgress,
      });
      notify.success(tx('{{n}} file(s) uploaded', { n: files.length }));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not upload the file')));
    } finally {
      setProgress(null);
      if (uploadInput.current) uploadInput.current.value = '';
    }
  };

  const newFolder = async () => {
    if (!wsId) return;
    const name = window.prompt(tx('Folder name'));
    if (!name?.trim()) return;
    try {
      const f = await createFolder.mutateAsync({ workspaceId: wsId, parentId: folderId ?? null, name: name.trim() });
      set({ folder: f.id, document: null });
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not create the folder')));
    }
  };

  const moveTo = async (ids: string[], target: { workspaceId?: string; folderId: string | null }) => {
    try {
      await bulk.mutateAsync({ ids, action: 'move', workspaceId: target.workspaceId, folderId: target.folderId });
      notify.success(tx('Moved'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not move')));
    }
  };

  const runBulk = async (action: string, extra: Record<string, unknown> = {}) => {
    if (!selection.length) return;
    if (action === 'delete' && !window.confirm(tx('Delete {{n}} document(s)?', { n: selection.length }))) return;
    try {
      const r = await bulk.mutateAsync({ ids: selection, action, ...extra });
      notify.success(tx('{{n}} updated', { n: r.updated }));
      setSelection([]);
    } catch (err) {
      notify.error(apiMessage(err, tx('Bulk action failed')));
    }
  };

  const toggle = (id: string) =>
    setSelection((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  const tree = useMemo(() => buildTree(folders ?? []), [folders]);
  const crumbs = useMemo(() => {
    const out: DmsFolder[] = [];
    let cur = folder;
    while (cur) {
      out.unshift(cur);
      cur = folders?.find((f) => f.id === cur?.parentId);
    }
    return out;
  }, [folder, folders]);

  const quickFilters: Array<{ id: QuickFilter; label: string; icon: typeof Inbox; hidden?: boolean }> = [
    { id: 'all', label: tx('All documents'), icon: Inbox },
    { id: 'mine', label: tx('My documents'), icon: User },
    { id: 'expiring', label: tx('Expiring (30 days)'), icon: CalendarClock },
    { id: 'pending', label: tx('Pending approval'), icon: Hourglass },
    { id: 'archived', label: tx('Archived'), icon: Archive },
    { id: 'trash', label: tx('Trash'), icon: Trash2, hidden: !canDelete },
  ];

  return (
    <div className="flex h-[calc(100vh-7rem)] min-h-[520px] gap-3">
      {/* ── left: workspaces / folders / tags ── */}
      <aside className="hidden w-60 shrink-0 flex-col gap-4 overflow-y-auto rounded-lg border bg-card p-3 md:flex">
        <div className="space-y-0.5">
          {quickFilters.filter((f) => !f.hidden).map((f) => (
            <NavRow key={f.id} active={quick === f.id && !wsId && !tagId} icon={f.icon} label={f.label}
              onClick={() => set({ filter: f.id === 'all' ? null : f.id, ws: null, folder: null, tag: null })} />
          ))}
        </div>
        <div>
          <div className="mb-1 px-2 text-xs font-semibold uppercase text-muted-foreground">{tx('Workspaces')}</div>
          {wsLoading ? <Skeleton className="h-24 w-full" /> : (workspaces ?? []).map((w) => (
            <div key={w.id}>
              <NavRow active={wsId === w.id && !folderId} label={w.name} count={w.documentCount}
                dot={w.color ?? undefined}
                onClick={() => set({ ws: w.id, folder: null, filter: null, tag: null })}
                onDropIds={(ids) => moveTo(ids, { workspaceId: w.id, folderId: null })} />
              {wsId === w.id && tree.map((n) => (
                <FolderRow key={n.folder.id} node={n} depth={1} activeId={folderId}
                  onSelect={(id) => set({ folder: id })}
                  onDropIds={(ids, fid) => moveTo(ids, { workspaceId: w.id, folderId: fid })} />
              ))}
            </div>
          ))}
        </div>
        {(tags ?? []).length > 0 && (
          <div>
            <div className="mb-1 px-2 text-xs font-semibold uppercase text-muted-foreground">{tx('Tags')}</div>
            {(tags ?? []).map((t) => (
              <NavRow key={t.id} active={tagId === t.id} icon={TagIcon} label={t.name} count={t.documentCount}
                onClick={() => set({ tag: tagId === t.id ? null : t.id })} />
            ))}
          </div>
        )}
      </aside>

      {/* ── center: documents ── */}
      <section
        className={cn('flex min-w-0 flex-1 flex-col rounded-lg border bg-card', dragOver && 'ring-2 ring-primary/50')}
        onDragOver={(e) => {
          if (e.dataTransfer.types.includes('Files') && canUploadHere) {
            e.preventDefault();
            setDragOver(true);
          }
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => {
          if (!e.dataTransfer.files.length) return;
          e.preventDefault();
          setDragOver(false);
          void send(filesOf(e.dataTransfer.files));
        }}
      >
        <div className="flex flex-wrap items-center gap-2 border-b p-3">
          <div className="flex min-w-0 flex-1 items-center gap-1 text-sm">
            <button type="button" className="font-semibold hover:underline" onClick={() => set({ folder: null })}>
              {workspace?.name ?? quickFilters.find((f) => f.id === quick)?.label ?? tx('Documents')}
            </button>
            {crumbs.map((c) => (
              <span key={c.id} className="flex items-center gap-1 truncate">
                <ChevronRight className="h-3 w-3 text-muted-foreground" />
                <button type="button" className="truncate hover:underline" onClick={() => set({ folder: c.id })}>
                  {c.name}
                </button>
              </span>
            ))}
            {isFetching && <Loader2 className="ml-1 h-3 w-3 animate-spin text-muted-foreground" />}
          </div>
          <div className="relative w-full sm:w-64">
            <Search className="absolute left-2 top-2.5 h-4 w-4 text-muted-foreground" />
            <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder={tx('Search names, tags and text…')}
              className="pl-8" />
          </div>
          <div className="flex rounded-md border">
            <Button variant={view === 'grid' ? 'secondary' : 'ghost'} size="icon" className="h-9 w-9"
              onClick={() => set({ view: null })} title={tx('Grid')}><Grid2x2 className="h-4 w-4" /></Button>
            <Button variant={view === 'list' ? 'secondary' : 'ghost'} size="icon" className="h-9 w-9"
              onClick={() => set({ view: 'list' })} title={tx('List')}><List className="h-4 w-4" /></Button>
          </div>
          {wsId && canWrite && workspace?.access !== 'read' && (
            <Button variant="outline" size="sm" onClick={newFolder}>
              <FolderPlus className="mr-1 h-4 w-4" />{tx('Folder')}
            </Button>
          )}
          {canUploadHere && (
            <Button size="sm" onClick={() => uploadInput.current?.click()} disabled={upload.isPending}>
              <Upload className="mr-1 h-4 w-4" />{tx('Upload')}
            </Button>
          )}
          <input ref={uploadInput} type="file" multiple hidden onChange={(e) => void send(filesOf(e.target.files))} />
        </div>

        {selection.length > 0 && (
          <div className="flex flex-wrap items-center gap-2 border-b bg-muted/40 px-3 py-2 text-sm">
            <span className="font-medium">{tx('{{n}} selected', { n: selection.length })}</span>
            {quick === 'archived' ? (
              <Button size="sm" variant="outline" onClick={() => runBulk('unarchive')}>
                <ArchiveRestore className="mr-1 h-4 w-4" />{tx('Unarchive')}
              </Button>
            ) : (
              <Button size="sm" variant="outline" onClick={() => runBulk('archive')}>
                <Archive className="mr-1 h-4 w-4" />{tx('Archive')}
              </Button>
            )}
            {(tags ?? []).length > 0 && (
              <Select onValueChange={(v) => runBulk('tag', { tagIds: [v] })}>
                <SelectTrigger className="h-9 w-40"><SelectValue placeholder={tx('Add tag…')} /></SelectTrigger>
                <SelectContent>
                  {(tags ?? []).map((t) => <SelectItem key={t.id} value={t.id}>{t.name}</SelectItem>)}
                </SelectContent>
              </Select>
            )}
            {canDelete && (
              <Button size="sm" variant="outline" className="text-destructive" onClick={() => runBulk('delete')}>
                <Trash2 className="mr-1 h-4 w-4" />{tx('Delete')}
              </Button>
            )}
            <Button size="sm" variant="ghost" onClick={() => setSelection([])}><X className="h-4 w-4" /></Button>
            <span className="text-xs text-muted-foreground">{tx('Tip: drag documents onto a workspace or folder to move them.')}</span>
          </div>
        )}

        <div className="min-h-0 flex-1 overflow-y-auto p-3">
          {progress != null && (
            <div className="mb-3 flex items-center gap-2 rounded-md border bg-muted/40 px-3 py-2 text-sm">
              <Loader2 className="h-4 w-4 animate-spin" />{tx('Uploading… {{pct}}%', { pct: progress })}
            </div>
          )}
          {wsId && !folderId && !debouncedQ && tree.length > 0 && (
            <div className="mb-3 grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-6">
              {tree.map((n) => (
                <button key={n.folder.id} type="button" onClick={() => set({ folder: n.folder.id })}
                  onDragOver={(e) => e.preventDefault()}
                  onDrop={(e) => {
                    const ids = e.dataTransfer.getData('application/x-dms-ids');
                    if (ids) {
                      e.preventDefault();
                      e.stopPropagation();
                      void moveTo(JSON.parse(ids), { workspaceId: wsId, folderId: n.folder.id });
                    }
                  }}
                  className="flex items-center gap-2 rounded-md border p-2 text-left text-sm hover:bg-accent">
                  <Folder className="h-5 w-5 shrink-0 text-amber-500" />
                  <span className="truncate">{n.folder.name}</span>
                  <span className="ml-auto text-xs text-muted-foreground">{n.folder.documentCount}</span>
                </button>
              ))}
            </div>
          )}
          {isLoading ? (
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
              {Array.from({ length: 10 }).map((_, i) => <Skeleton key={i} className="h-36" />)}
            </div>
          ) : docs.length === 0 ? (
            <div className="flex h-60 flex-col items-center justify-center gap-2 text-center text-sm text-muted-foreground">
              <Inbox className="h-10 w-10" />
              <p>{debouncedQ ? tx('No documents match your search.') : tx('No documents here yet.')}</p>
              {canUploadHere && <p>{tx('Drop files anywhere in this panel to upload.')}</p>}
            </div>
          ) : view === 'grid' ? (
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
              {docs.map((d) => (
                <DocCard key={d.id} doc={d} selected={selection.includes(d.id)} active={selectedId === d.id}
                  dragIds={selection.includes(d.id) ? selection : [d.id]}
                  onToggle={() => toggle(d.id)} onOpen={() => set({ document: d.id })} onPreview={() => setPreview(d)} />
              ))}
            </div>
          ) : (
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-muted-foreground">
                <tr>
                  <th className="w-8 p-2" />
                  <th className="p-2">{tx('Name')}</th>
                  <th className="hidden p-2 md:table-cell">{tx('Tags')}</th>
                  <th className="hidden p-2 lg:table-cell">{tx('Owner')}</th>
                  <th className="p-2">{tx('Size')}</th>
                  <th className="hidden p-2 sm:table-cell">{tx('Updated')}</th>
                </tr>
              </thead>
              <tbody>
                {docs.map((d) => {
                  const Icon = fileIcon(d.contentType, d.name);
                  return (
                    <tr key={d.id} draggable={!d.readOnly}
                      onDragStart={(e) => e.dataTransfer.setData('application/x-dms-ids',
                        JSON.stringify(selection.includes(d.id) ? selection : [d.id]))}
                      className={cn('cursor-pointer border-t hover:bg-accent/50', selectedId === d.id && 'bg-accent')}
                      onClick={() => set({ document: d.id })}>
                      <td className="p-2" onClick={(e) => e.stopPropagation()}>
                        <input type="checkbox" checked={selection.includes(d.id)} onChange={() => toggle(d.id)} />
                      </td>
                      <td className="max-w-0 p-2">
                        <div className="flex items-center gap-2">
                          <Icon className="h-4 w-4 shrink-0 text-muted-foreground" />
                          <span className="truncate">{d.name}</span>
                          <StatusBadge doc={d} />
                        </div>
                        {d.snippet && <Snippet text={d.snippet} />}
                      </td>
                      <td className="hidden p-2 md:table-cell">
                        <div className="flex flex-wrap gap-1">{d.tags.map((t) => <TagChip key={t.id} name={t.name} />)}</div>
                      </td>
                      <td className="hidden p-2 lg:table-cell">{d.ownerName ?? '—'}</td>
                      <td className="whitespace-nowrap p-2">{formatBytes(d.byteSize)}</td>
                      <td className="hidden whitespace-nowrap p-2 sm:table-cell">{relativeTime(d.updatedAt)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
          {data && data.meta.totalPages > 1 && (
            <div className="mt-4 flex items-center justify-center gap-2 text-sm">
              <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>{tx('Previous')}</Button>
              <span>{tx('Page {{p}} of {{n}}', { p: data.meta.page, n: data.meta.totalPages })}</span>
              <Button variant="outline" size="sm" disabled={page >= data.meta.totalPages} onClick={() => setPage((p) => p + 1)}>
                {tx('Next')}
              </Button>
            </div>
          )}
        </div>
      </section>

      {/* ── right: inspector ── */}
      {selectedId && (
        <Inspector id={selectedId} onClose={() => set({ document: null })} onPreview={setPreview}
          onUpdate={(body) => update.mutateAsync({ id: selectedId, body })} />
      )}

      <FilePreviewDialog documentId={preview?.id ?? null} name={preview?.name} contentType={preview?.contentType}
        open={!!preview} onOpenChange={(o) => !o && setPreview(null)} />
    </div>
  );
}

// ---- tree ----

interface TreeNode {
  folder: DmsFolder;
  children: TreeNode[];
}

function buildTree(folders: DmsFolder[]): TreeNode[] {
  const byParent = new Map<string, DmsFolder[]>();
  folders.forEach((f) => {
    const k = f.parentId ?? '';
    byParent.set(k, [...(byParent.get(k) ?? []), f]);
  });
  const walk = (parent: string): TreeNode[] =>
    (byParent.get(parent) ?? []).map((f) => ({ folder: f, children: walk(f.id) }));
  return walk('');
}

function FolderRow({ node, depth, activeId, onSelect, onDropIds }: {
  node: TreeNode; depth: number; activeId?: string; onSelect: (id: string) => void;
  onDropIds: (ids: string[], folderId: string) => void;
}) {
  return (
    <>
      <NavRow active={activeId === node.folder.id} icon={Folder} label={node.folder.name} count={node.folder.documentCount}
        indent={depth} onClick={() => onSelect(node.folder.id)} onDropIds={(ids) => onDropIds(ids, node.folder.id)} />
      {node.children.map((c) => (
        <FolderRow key={c.folder.id} node={c} depth={depth + 1} activeId={activeId} onSelect={onSelect}
          onDropIds={onDropIds} />
      ))}
    </>
  );
}

function NavRow({ active, icon: Icon, label, count, dot, indent = 0, onClick, onDropIds }: {
  active?: boolean; icon?: typeof Folder; label: string; count?: number; dot?: string; indent?: number;
  onClick: () => void; onDropIds?: (ids: string[]) => void;
}) {
  const [over, setOver] = useState(false);
  return (
    <button type="button" onClick={onClick}
      onDragOver={(e) => {
        if (onDropIds && e.dataTransfer.types.includes('application/x-dms-ids')) {
          e.preventDefault();
          setOver(true);
        }
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        setOver(false);
        const ids = e.dataTransfer.getData('application/x-dms-ids');
        if (ids && onDropIds) {
          e.preventDefault();
          onDropIds(JSON.parse(ids));
        }
      }}
      style={{ paddingLeft: 8 + indent * 12 }}
      className={cn('flex w-full items-center gap-2 rounded-md py-1.5 pr-2 text-left text-sm hover:bg-accent',
        active && 'bg-accent font-medium', over && 'ring-2 ring-primary')}>
      {dot ? <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: dot }} />
        : Icon ? <Icon className="h-4 w-4 shrink-0 text-muted-foreground" /> : null}
      <span className="truncate">{label}</span>
      {count != null && count > 0 && <span className="ml-auto text-xs text-muted-foreground">{count}</span>}
    </button>
  );
}

// ---- cards ----

function TagChip({ name }: { name: string }) {
  return <span className="rounded bg-secondary px-1.5 py-0.5 text-[11px] text-secondary-foreground">{name}</span>;
}

function StatusBadge({ doc }: { doc: DmsDocument }) {
  const expired = doc.expiresOn && doc.expiresOn < new Date().toISOString().slice(0, 10);
  return (
    <>
      {doc.status !== 'active' && <Badge variant={doc.status === 'rejected' ? 'destructive' : 'secondary'}
        className="shrink-0 text-[10px]">{tx(STATUS_LABEL[doc.status] ?? doc.status)}</Badge>}
      {expired && <Badge variant="destructive" className="shrink-0 text-[10px]">{tx('Expired')}</Badge>}
      {doc.lockedById && <Lock className="h-3 w-3 shrink-0 text-muted-foreground" />}
    </>
  );
}

function Snippet({ text }: { text: string }) {
  const parts = text.split(/(«[^»]*»)/g);
  return (
    <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">
      {parts.map((p, i) => (p.startsWith('«')
        ? <mark key={i} className="rounded bg-yellow-200/70 px-0.5 dark:bg-yellow-700/50">{p.slice(1, -1)}</mark>
        : <span key={i}>{p}</span>))}
    </p>
  );
}

function DocCard({ doc, selected, active, dragIds, onToggle, onOpen, onPreview }: {
  doc: DmsDocument; selected: boolean; active: boolean; dragIds: string[];
  onToggle: () => void; onOpen: () => void; onPreview: () => void;
}) {
  const Icon = fileIcon(doc.contentType, doc.name);
  return (
    <div draggable={!doc.readOnly}
      onDragStart={(e) => e.dataTransfer.setData('application/x-dms-ids', JSON.stringify(dragIds))}
      onClick={onOpen}
      className={cn('group relative flex cursor-pointer flex-col rounded-lg border p-3 transition-colors hover:border-primary/50',
        active && 'border-primary bg-primary/5', selected && 'ring-2 ring-primary/50')}>
      <input type="checkbox" checked={selected} onChange={onToggle} onClick={(e) => e.stopPropagation()}
        className={cn('absolute left-2 top-2', !selected && 'opacity-0 group-hover:opacity-100')} />
      <button type="button" onClick={(e) => { e.stopPropagation(); onPreview(); }}
        className="mb-2 flex h-20 items-center justify-center rounded-md bg-muted/50" title={tx('Preview')}>
        <Icon className="h-10 w-10 text-muted-foreground" />
      </button>
      <div className="flex items-center gap-1">
        <span className="truncate text-sm font-medium" title={doc.name}>{doc.name}</span>
      </div>
      <div className="mt-1 flex flex-wrap items-center gap-1">
        <StatusBadge doc={doc} />
        {doc.tags.slice(0, 2).map((t) => <TagChip key={t.id} name={t.name} />)}
      </div>
      {doc.snippet && <Snippet text={doc.snippet} />}
      <div className="mt-auto pt-1 text-[11px] text-muted-foreground">
        {formatBytes(doc.byteSize)} · {relativeTime(doc.updatedAt)}
      </div>
    </div>
  );
}

// ---- inspector ----

function Inspector({ id, onClose, onPreview, onUpdate }: {
  id: string; onClose: () => void; onPreview: (d: DmsDocument) => void;
  onUpdate: (body: Record<string, unknown>) => Promise<unknown>;
}) {
  const { data: doc, isLoading, isError } = useDocument(id);
  const { data: timeline } = useTimeline(id);
  const { data: tags } = useTags();
  const { data: workspaces } = useWorkspaces();
  const action = useDocumentAction();
  const addVersion = useAddVersion();
  const restoreVersion = useRestoreVersion();
  const del = useDeleteDocument();
  const restore = useRestoreDocument();
  const unlink = useUnlinkDocument();
  const userId = useAuthStore((s) => s.user?.id);
  const canDelete = useAuthStore((s) => s.hasPermission(PERMISSIONS.dms.documentDelete));
  const canApprove = useAuthStore((s) => s.hasPermission(PERMISSIONS.dms.documentApprove));
  const canShare = useAuthStore((s) => s.hasPermission(PERMISSIONS.dms.documentShare));
  const canWritePerm = useAuthStore((s) => s.hasPermission(PERMISSIONS.dms.documentWrite));
  const versionInput = useRef<HTMLInputElement>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [expiresOn, setExpiresOn] = useState('');
  const [shareOpen, setShareOpen] = useState(false);

  useEffect(() => {
    if (doc) {
      setName(doc.name);
      setDescription(doc.description ?? '');
      setExpiresOn(doc.expiresOn ?? '');
    }
  }, [doc]);

  const run = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn();
      notify.success(ok);
    } catch (err) {
      notify.error(apiMessage(err, tx('Action failed')));
    }
  };

  if (isLoading) return <aside className="w-80 shrink-0 rounded-lg border bg-card p-4"><Skeleton className="h-60" /></aside>;
  if (isError || !doc) {
    return (
      <aside className="w-80 shrink-0 rounded-lg border bg-card p-4 text-sm text-muted-foreground">
        {tx('Document not found.')} <Button variant="link" onClick={onClose}>{tx('Close')}</Button>
      </aside>
    );
  }

  const editable = canWritePerm && !doc.readOnly && !doc.deletedAt && doc.access !== 'read'
    && (!doc.lockedById || doc.lockedById === userId);
  const Icon = fileIcon(doc.contentType, doc.name);
  const workspace = workspaces?.find((w) => w.id === doc.workspaceId);
  const dirty = name !== doc.name || description !== (doc.description ?? '') || expiresOn !== (doc.expiresOn ?? '');

  const save = () => run(() => onUpdate({
    name: name.trim() !== doc.name ? name.trim() : undefined,
    description: description !== (doc.description ?? '') ? description : undefined,
    expiresOn: expiresOn && expiresOn !== doc.expiresOn ? expiresOn : undefined,
    clearExpiry: !expiresOn && doc.expiresOn ? true : undefined,
    version: doc.version,
  }), tx('Saved'));

  const setTags = (tagIds: string[]) => run(() => onUpdate({ tagIds }), tx('Tags updated'));

  return (
    <aside className="flex w-80 shrink-0 flex-col overflow-hidden rounded-lg border bg-card">
      <div className="flex items-center gap-2 border-b p-3">
        <Icon className="h-5 w-5 shrink-0 text-muted-foreground" />
        <span className="min-w-0 flex-1 truncate font-medium" title={doc.name}>{doc.name}</span>
        <Button variant="ghost" size="icon" className="h-7 w-7" onClick={onClose}><X className="h-4 w-4" /></Button>
      </div>
      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-3 text-sm">
        <div className="flex flex-wrap gap-1.5">
          <Button size="sm" variant="outline" onClick={() => onPreview(doc)}><Eye className="mr-1 h-4 w-4" />{tx('Preview')}</Button>
          <Button size="sm" variant="outline" onClick={() => downloadDocument(doc.id)}><Download className="mr-1 h-4 w-4" />{tx('Download')}</Button>
          {canShare && !doc.deletedAt && (
            <Button size="sm" variant="outline" onClick={() => setShareOpen(true)}><Share2 className="mr-1 h-4 w-4" />{tx('Share')}</Button>
          )}
        </div>

        {doc.readOnly && (
          <p className="rounded-md bg-muted p-2 text-xs text-muted-foreground">
            {tx('This file is attached by its module ({{m}}) and is read-only here.', { m: doc.legacyOwnerType ?? '—' })}
          </p>
        )}
        {doc.lockedById && (
          <p className="flex items-center gap-1 rounded-md bg-muted p-2 text-xs">
            <Lock className="h-3 w-3" />{tx('Locked by {{n}}', { n: doc.lockedByName ?? '—' })}
          </p>
        )}

        <div className="space-y-2">
          <label className="text-xs font-medium text-muted-foreground">{tx('Name')}</label>
          <Input value={name} onChange={(e) => setName(e.target.value)} disabled={!editable} />
          <label className="text-xs font-medium text-muted-foreground">{tx('Description')}</label>
          <Textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} disabled={!editable} />
          <label className="flex items-center gap-1 text-xs font-medium text-muted-foreground">
            <Clock className="h-3 w-3" />{tx('Expires on')}
          </label>
          <Input type="date" value={expiresOn} onChange={(e) => setExpiresOn(e.target.value)} disabled={!editable} />
          {editable && dirty && <Button size="sm" onClick={save}><Check className="mr-1 h-4 w-4" />{tx('Save')}</Button>}
        </div>

        <div>
          <div className="mb-1 text-xs font-medium text-muted-foreground">{tx('Tags')}</div>
          <div className="flex flex-wrap gap-1">
            {doc.tags.map((t) => (
              <span key={t.id} className="flex items-center gap-1 rounded bg-secondary px-1.5 py-0.5 text-xs">
                {t.name}
                {editable && (
                  <button type="button" onClick={() => setTags(doc.tags.filter((x) => x.id !== t.id).map((x) => x.id))}>
                    <X className="h-3 w-3" />
                  </button>
                )}
              </span>
            ))}
            {editable && (tags ?? []).filter((t) => !doc.tags.some((x) => x.id === t.id)).length > 0 && (
              <Select onValueChange={(v) => setTags([...doc.tags.map((x) => x.id), v])}>
                <SelectTrigger className="h-7 w-28 text-xs"><SelectValue placeholder={tx('+ Tag')} /></SelectTrigger>
                <SelectContent>
                  {(tags ?? []).filter((t) => !doc.tags.some((x) => x.id === t.id)).map((t) => (
                    <SelectItem key={t.id} value={t.id}>{t.name}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          </div>
        </div>

        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
          <dt className="text-muted-foreground">{tx('Workspace')}</dt><dd>{workspace?.name ?? '—'}</dd>
          <dt className="text-muted-foreground">{tx('Status')}</dt><dd>{tx(STATUS_LABEL[doc.status] ?? doc.status)}</dd>
          <dt className="text-muted-foreground">{tx('Size')}</dt><dd>{formatBytes(doc.byteSize)}</dd>
          <dt className="text-muted-foreground">{tx('Owner')}</dt><dd>{doc.ownerName ?? '—'}</dd>
          <dt className="text-muted-foreground">{tx('Uploaded')}</dt><dd>{dateTime(doc.createdAt)}</dd>
          {doc.expiresOn && (<><dt className="text-muted-foreground">{tx('Expires')}</dt><dd>{date(doc.expiresOn)}</dd></>)}
        </dl>

        {doc.links.length > 0 && (
          <div>
            <div className="mb-1 flex items-center gap-1 text-xs font-medium text-muted-foreground">
              <Link2 className="h-3 w-3" />{tx('Linked records')}
            </div>
            <ul className="space-y-1">
              {doc.links.map((l) => (
                <li key={l.id} className="flex items-center gap-2 text-xs">
                  <span className="text-muted-foreground">{tx(l.typeLabel ?? l.resType)}</span>
                  {l.webPath ? <Link to={l.webPath} className="truncate text-primary hover:underline">{l.label ?? l.resId}</Link>
                    : <span className="truncate">{l.label ?? tx('(no access)')}</span>}
                  {editable && (
                    <button type="button" className="ml-auto text-muted-foreground hover:text-destructive"
                      onClick={() => run(() => unlink.mutateAsync({ id: doc.id, linkId: l.id }), tx('Unlinked'))}>
                      <X className="h-3 w-3" />
                    </button>
                  )}
                </li>
              ))}
            </ul>
          </div>
        )}

        <div>
          <div className="mb-1 flex items-center justify-between text-xs font-medium text-muted-foreground">
            <span>{tx('Versions')}</span>
            {editable && (
              <button type="button" className="text-primary hover:underline" onClick={() => versionInput.current?.click()}>
                {tx('Upload new version')}
              </button>
            )}
            <input ref={versionInput} type="file" hidden onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) {
                const note = window.prompt(tx('Note for this version (optional)')) ?? undefined;
                void run(() => addVersion.mutateAsync({ id: doc.id, file: f, note }), tx('New version uploaded'));
              }
              e.target.value = '';
            }} />
          </div>
          <ul className="space-y-1">
            {(doc.versions ?? []).map((v) => (
              <li key={v.id} className="flex items-center gap-2 text-xs">
                <Badge variant={v.versionNo === doc.currentVersion ? 'default' : 'outline'}>v{v.versionNo}</Badge>
                <span className="min-w-0 flex-1 truncate" title={v.note ?? v.filename}>
                  {v.note ?? v.filename}
                  <span className="block text-muted-foreground">{v.uploadedByName} · {relativeTime(v.createdAt)}</span>
                </span>
                <button type="button" title={tx('Download')} onClick={() => downloadDocument(doc.id, v.versionNo)}>
                  <Download className="h-3 w-3" />
                </button>
                {editable && v.versionNo !== doc.currentVersion && (
                  <button type="button" title={tx('Restore this version')}
                    onClick={() => run(() => restoreVersion.mutateAsync({ id: doc.id, versionNo: v.versionNo }),
                      tx('Version restored'))}>
                    <Undo2 className="h-3 w-3" />
                  </button>
                )}
              </li>
            ))}
          </ul>
        </div>

        <div className="flex flex-wrap gap-1.5 border-t pt-3">
          {doc.deletedAt ? (
            canDelete && (
              <Button size="sm" variant="outline" onClick={() => run(() => restore.mutateAsync(doc.id), tx('Restored'))}>
                <Undo2 className="mr-1 h-4 w-4" />{tx('Restore')}
              </Button>
            )
          ) : (
            <>
              {editable && (doc.lockedById
                ? <Button size="sm" variant="outline" onClick={() => run(() => action.mutateAsync({ id: doc.id, action: 'unlock' }), tx('Unlocked'))}>
                  <Unlock className="mr-1 h-4 w-4" />{tx('Unlock')}</Button>
                : <Button size="sm" variant="outline" onClick={() => run(() => action.mutateAsync({ id: doc.id, action: 'lock' }), tx('Locked'))}>
                  <Lock className="mr-1 h-4 w-4" />{tx('Lock')}</Button>)}
              {editable && (doc.status === 'archived'
                ? <Button size="sm" variant="outline" onClick={() => run(() => action.mutateAsync({ id: doc.id, action: 'unarchive' }), tx('Unarchived'))}>
                  <ArchiveRestore className="mr-1 h-4 w-4" />{tx('Unarchive')}</Button>
                : <Button size="sm" variant="outline" onClick={() => run(() => action.mutateAsync({ id: doc.id, action: 'archive' }), tx('Archived'))}>
                  <Archive className="mr-1 h-4 w-4" />{tx('Archive')}</Button>)}
              {editable && (doc.status === 'active' || doc.status === 'rejected') && (
                <Button size="sm" variant="outline" onClick={() => run(() => action.mutateAsync({ id: doc.id, action: 'submit' }), tx('Submitted for approval'))}>
                  <Hourglass className="mr-1 h-4 w-4" />{tx('Request approval')}</Button>
              )}
              {canApprove && doc.status === 'pending_approval' && (
                <>
                  <Button size="sm" onClick={() => run(() => action.mutateAsync({ id: doc.id, action: 'approve' }), tx('Approved'))}>
                    <Check className="mr-1 h-4 w-4" />{tx('Approve')}</Button>
                  <Button size="sm" variant="outline" onClick={() => {
                    const comment = window.prompt(tx('Reason for rejection')) ?? undefined;
                    void run(() => action.mutateAsync({ id: doc.id, action: 'reject', comment }), tx('Rejected'));
                  }}>{tx('Reject')}</Button>
                </>
              )}
              {canDelete && !doc.readOnly && (
                <Button size="sm" variant="outline" className="text-destructive" onClick={() => {
                  if (window.confirm(tx('Delete "{{name}}"?', { name: doc.name }))) {
                    void run(async () => { await del.mutateAsync(doc.id); onClose(); }, tx('Deleted'));
                  }
                }}><Trash2 className="mr-1 h-4 w-4" />{tx('Delete')}</Button>
              )}
            </>
          )}
        </div>

        {(timeline ?? []).length > 0 && (
          <div>
            <div className="mb-1 text-xs font-medium text-muted-foreground">{tx('Activity')}</div>
            <ol className="space-y-1.5 border-l pl-3">
              {(timeline ?? []).slice(0, 30).map((a) => (
                <li key={a.id} className="text-xs">
                  <span className="font-medium">{tx(a.action.replace(/_/g, ' '))}</span>
                  <span className="text-muted-foreground"> · {a.actorName || tx('System')} · {relativeTime(a.createdAt)}</span>
                </li>
              ))}
            </ol>
          </div>
        )}
      </div>
      {shareOpen && <ShareDialog open={shareOpen} onOpenChange={setShareOpen} documentIds={[doc.id]} title={doc.name} />}
    </aside>
  );
}

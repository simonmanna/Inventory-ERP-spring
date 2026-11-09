import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Archive, CheckCircle2, CopyPlus, FileText, History, MoreHorizontal, Pencil, Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { ListCard, ListPageHeader } from '@/components/list/list-page';
import { FilterSelect, ListToolbar, SearchInput } from '@/components/list/list-toolbar';
import { useAuthStore } from '@/stores/auth.store';
import {
  useDeleteDocument, useDocumentAction, usePlmDocuments, type DocStatus, type DocType, type PlmDocument,
} from '@/features/manufacturing/api-plm';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { DocStatusPill, EmptyState, FileLink, PlmSubNav, ProductLabel } from './plm-ui';
import { DocumentDialog, VersionsDialog } from './document-dialogs';
import { DOC_STATUS, DOC_TYPES, day } from './vocab';

/** Engineering document register: drawings, specifications, CAD, instructions… with versions and release. */
export function PlmDocumentsPage() {
  const [params] = useSearchParams();
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.plm.manage);
  const [status, setStatus] = useState(params.get('q') ? '' : 'current');
  const [docType, setDocType] = useState('');
  const [search, setSearch] = useState(params.get('q') ?? '');
  const q = useDebouncedValue(search, 250);
  const { data = [], isLoading } = usePlmDocuments({ status, docType, q });
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<PlmDocument | null>(null);
  const [versionsOf, setVersionsOf] = useState<string | null>(null);
  const action = useDocumentAction();
  const remove = useDeleteDocument();

  const run = (d: PlmDocument, a: 'release' | 'revise' | 'obsolete') => {
    const ask = a === 'release' ? tx('Release {{n}} v{{v}}? The previously released version is superseded.', { n: d.number, v: d.version })
      : a === 'obsolete' ? tx('Make {{n}} v{{v}} obsolete?', { n: d.number, v: d.version }) : null;
    if (ask && !confirm(ask)) return;
    action.mutate({ id: d.id, action: a }, {
      onSuccess: (r) => notify.success(a === 'release' ? tx('{{n}} released', { n: d.number })
        : a === 'revise' ? tx('Draft v{{v}} created', { v: r.version }) : tx('{{n}} is obsolete', { n: d.number })),
    });
  };

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('Engineering documents')}
        description={tx('Controlled drawings, specifications and instructions: one released version at a time.')}
        icon={FileText}
        actions={canManage && <Button onClick={() => setCreating(true)}><Plus className="mr-1 h-4 w-4" />{tx('New document')}</Button>}
      />
      <PlmSubNav />
      <ListToolbar>
        <SearchInput value={search} onChange={setSearch} placeholder={tx('Search number or title…')} />
        <FilterSelect value={status} onChange={setStatus} allLabel={tx('Any status')}
          options={[{ value: 'current', label: tx('Current (draft + released)') },
            ...(Object.keys(DOC_STATUS) as DocStatus[]).map((s) => ({ value: s, label: DOC_STATUS[s].label }))]} />
        <FilterSelect value={docType} onChange={setDocType} allLabel={tx('Any type')}
          options={(Object.keys(DOC_TYPES) as DocType[]).map((t) => ({ value: t, label: DOC_TYPES[t] }))} />
        <span className="ml-auto text-xs text-muted-foreground">{tx('{{n}} documents', { n: data.length })}</span>
      </ListToolbar>

      {isLoading ? <Skeleton className="h-72 w-full rounded-xl" /> : data.length === 0 ? (
        <EmptyState icon={FileText} title={tx('No documents')}
          hint={tx('Upload drawings and specifications, link them to products, and release them through change orders.')}
          action={canManage && <Button onClick={() => setCreating(true)}><Plus className="mr-1 h-4 w-4" />{tx('New document')}</Button>} />
      ) : (
        <ListCard>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Number')}</TableHead>
                <TableHead>{tx('Title')}</TableHead>
                <TableHead>{tx('Type')}</TableHead>
                <TableHead>{tx('Status')}</TableHead>
                <TableHead>{tx('Product')}</TableHead>
                <TableHead>{tx('Change')}</TableHead>
                <TableHead>{tx('File')}</TableHead>
                <TableHead className="w-10" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.map((d) => (
                <TableRow key={d.id}>
                  <TableCell className="whitespace-nowrap">
                    <div className="font-mono text-xs">{d.number}</div>
                    <div className="text-xs text-muted-foreground">v{d.version} · {tx('Rev')} {d.revision}</div>
                  </TableCell>
                  <TableCell className="max-w-[260px]">
                    <div className="font-medium">{d.title}</div>
                    {d.description && <div className="line-clamp-1 text-xs text-muted-foreground">{d.description}</div>}
                  </TableCell>
                  <TableCell className="text-sm text-muted-foreground">{DOC_TYPES[d.docType] ?? d.docType}</TableCell>
                  <TableCell>
                    <DocStatusPill status={d.status} />
                    {d.releasedAt && <div className="mt-0.5 text-[11px] text-muted-foreground">{day(d.releasedAt)}</div>}
                  </TableCell>
                  <TableCell className="max-w-[200px] text-sm"><ProductLabel product={d.product} /></TableCell>
                  <TableCell>{d.changeId ? <Link to={`/manufacturing/plm/changes/${d.changeId}`} className="font-mono text-xs text-primary hover:underline">{d.changeNumber ?? tx('Open')}</Link> : '—'}</TableCell>
                  <TableCell><FileLink fileId={d.fileId} url={d.url} fileName={d.fileName} /></TableCell>
                  <TableCell>
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Actions')}><MoreHorizontal className="h-4 w-4" /></Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end">
                        <DropdownMenuItem onClick={() => setVersionsOf(d.id)}><History className="mr-2 h-4 w-4" />{tx('Versions')}</DropdownMenuItem>
                        {canManage && d.status === 'draft' && (
                          <>
                            <DropdownMenuItem onClick={() => setEditing(d)}><Pencil className="mr-2 h-4 w-4" />{tx('Edit')}</DropdownMenuItem>
                            <DropdownMenuItem onClick={() => run(d, 'release')}><CheckCircle2 className="mr-2 h-4 w-4" />{tx('Release')}</DropdownMenuItem>
                          </>
                        )}
                        {canManage && d.status !== 'draft' && (
                          <DropdownMenuItem onClick={() => run(d, 'revise')}><CopyPlus className="mr-2 h-4 w-4" />{tx('New version')}</DropdownMenuItem>
                        )}
                        {canManage && d.status === 'released' && (
                          <DropdownMenuItem onClick={() => run(d, 'obsolete')}><Archive className="mr-2 h-4 w-4" />{tx('Make obsolete')}</DropdownMenuItem>
                        )}
                        {canManage && d.status === 'draft' && (
                          <>
                            <DropdownMenuSeparator />
                            <DropdownMenuItem
                              className="text-rose-600 focus:text-rose-600"
                              onClick={() => confirm(tx('Delete draft {{n}} v{{v}}?', { n: d.number, v: d.version }))
                                && remove.mutate(d.id, { onSuccess: () => notify.success(tx('Draft deleted')) })}
                            >
                              <Trash2 className="mr-2 h-4 w-4" />{tx('Delete')}
                            </DropdownMenuItem>
                          </>
                        )}
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </ListCard>
      )}

      <DocumentDialog open={creating || !!editing} doc={editing} onClose={() => { setCreating(false); setEditing(null); }} />
      <VersionsDialog docId={versionsOf} onClose={() => setVersionsOf(null)} />
    </div>
  );
}

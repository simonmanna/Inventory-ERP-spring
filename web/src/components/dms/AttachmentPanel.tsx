import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Download, Eye, FolderOpen, Loader2, Paperclip, Trash2, Upload } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Badge } from '@/components/ui/badge';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { formatBytes, relativeTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  useDeleteDocument, useRecordAttachments, useUploadRecordAttachments, type DmsDocument,
} from '@/features/dms/api';
import { FilePreviewDialog } from './FilePreviewDialog';
import { downloadDocument, fileIcon, filesOf } from './file-utils';

interface Props {
  /** DMS record type, e.g. `sales_order`, `purchase_order`, `expense`, `invoice`, `crm_deal`. */
  resType: string;
  resId?: string | null;
  title?: string;
  /** Tags added to every upload (e.g. `['Receipt']`). */
  tags?: string[];
  /** Render without the card chrome (inside tabs / dialogs). */
  bare?: boolean;
  /** Extra permission needed to upload (the record's own write permission). */
  writePermission?: string;
  className?: string;
}

/**
 * Attachments of any ERP record, stored centrally in the DMS (ADR-S044): drag-and-drop or pick files, preview,
 * download, delete, and jump to the document in the Documents app. Hidden for users without DMS read access.
 */
export function AttachmentPanel({ resType, resId, title, tags, bare, writePermission, className }: Props) {
  const canRead = useAuthStore((s) => s.hasPermission(PERMISSIONS.dms.documentRead));
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.dms.documentWrite)
    && (!writePermission || s.hasPermission(writePermission)));
  const canDelete = useAuthStore((s) => s.hasPermission(PERMISSIONS.dms.documentDelete));
  const { data, isLoading, isError } = useRecordAttachments(resType, resId, canRead);
  const upload = useUploadRecordAttachments();
  const remove = useDeleteDocument();
  const input = useRef<HTMLInputElement>(null);
  const [drag, setDrag] = useState(false);
  const [progress, setProgress] = useState<number | null>(null);
  const [preview, setPreview] = useState<DmsDocument | null>(null);

  if (!canRead || !resId) return null;

  const send = async (files: File[]) => {
    if (!files.length || !canWrite) return;
    setProgress(0);
    try {
      await upload.mutateAsync({ resType, resId, files, tags, onProgress: setProgress });
      notify.success(files.length === 1 ? tx('File attached') : tx('{{n}} files attached', { n: files.length }));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not upload the file')));
    } finally {
      setProgress(null);
      if (input.current) input.current.value = '';
    }
  };

  const del = async (d: DmsDocument) => {
    if (!window.confirm(tx('Delete "{{name}}"?', { name: d.name }))) return;
    try {
      await remove.mutateAsync(d.id);
      notify.success(tx('Deleted'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not delete the file')));
    }
  };

  const docs = data ?? [];
  const body = (
    <div
      className={cn('space-y-2 rounded-md transition-colors', drag && 'bg-primary/5 ring-2 ring-primary/40')}
      onDragOver={(e) => {
        if (!canWrite) return;
        e.preventDefault();
        setDrag(true);
      }}
      onDragLeave={() => setDrag(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDrag(false);
        void send(filesOf(e.dataTransfer.files));
      }}
    >
      {isLoading ? (
        <div className="space-y-2">
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
        </div>
      ) : isError ? (
        <p className="text-sm text-destructive">{tx('Could not load attachments.')}</p>
      ) : docs.length === 0 ? (
        <p className="py-2 text-sm text-muted-foreground">
          {canWrite ? tx('No files yet. Drop files here or use Attach.') : tx('No files attached.')}
        </p>
      ) : (
        <ul className="divide-y rounded-md border">
          {docs.map((d) => {
            const Icon = fileIcon(d.contentType, d.name);
            return (
              <li key={d.id} className="flex items-center gap-2 px-2 py-1.5 text-sm">
                <Icon className="h-4 w-4 shrink-0 text-muted-foreground" />
                <button type="button" className="min-w-0 flex-1 truncate text-left hover:underline"
                  onClick={() => setPreview(d)} title={d.name}>
                  {d.name}
                </button>
                {d.status === 'pending_approval' && <Badge variant="secondary">{tx('Pending')}</Badge>}
                {d.currentVersion > 1 && <Badge variant="outline">v{d.currentVersion}</Badge>}
                <span className="hidden shrink-0 text-xs text-muted-foreground sm:inline">
                  {formatBytes(d.byteSize)} · {relativeTime(d.createdAt)}
                </span>
                <Button variant="ghost" size="icon" className="h-7 w-7" title={tx('Preview')} onClick={() => setPreview(d)}>
                  <Eye className="h-4 w-4" />
                </Button>
                <Button variant="ghost" size="icon" className="h-7 w-7" title={tx('Download')}
                  onClick={() => downloadDocument(d.id)}>
                  <Download className="h-4 w-4" />
                </Button>
                <Button variant="ghost" size="icon" className="h-7 w-7" title={tx('Open in Documents')} asChild>
                  <Link to={`/dms?document=${d.id}`}><FolderOpen className="h-4 w-4" /></Link>
                </Button>
                {canDelete && !d.readOnly && (
                  <Button variant="ghost" size="icon" className="h-7 w-7 text-destructive" title={tx('Delete')}
                    onClick={() => del(d)}>
                    <Trash2 className="h-4 w-4" />
                  </Button>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {progress != null && (
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Loader2 className="h-3 w-3 animate-spin" />
          {tx('Uploading… {{pct}}%', { pct: progress })}
        </div>
      )}
      <input ref={input} type="file" multiple hidden onChange={(e) => void send(filesOf(e.target.files))} />
      <FilePreviewDialog documentId={preview?.id ?? null} name={preview?.name} contentType={preview?.contentType}
        open={!!preview} onOpenChange={(o) => !o && setPreview(null)} />
    </div>
  );

  const attachButton = canWrite && (
    <Button size="sm" variant="outline" onClick={() => input.current?.click()} disabled={upload.isPending}>
      <Upload className="mr-1 h-4 w-4" />
      {tx('Attach')}
    </Button>
  );

  if (bare) {
    return (
      <div className={cn('space-y-2', className)}>
        <div className="flex items-center justify-between">
          <span className="flex items-center gap-1.5 text-sm font-medium">
            <Paperclip className="h-4 w-4" />
            {title ?? tx('Attachments')} {docs.length > 0 && <span className="text-muted-foreground">({docs.length})</span>}
          </span>
          {attachButton}
        </div>
        {body}
      </div>
    );
  }
  return (
    <Card className={className}>
      <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle className="flex items-center gap-1.5 text-base">
          <Paperclip className="h-4 w-4" />
          {title ?? tx('Attachments')} {docs.length > 0 && <span className="text-muted-foreground">({docs.length})</span>}
        </CardTitle>
        {attachButton}
      </CardHeader>
      <CardContent>{body}</CardContent>
    </Card>
  );
}

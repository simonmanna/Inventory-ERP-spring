import { useEffect, useRef, useState } from 'react';
import { Loader2, Paperclip, Upload, X } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import {
  plmError, uploadPlmFile, useCreateDocument, usePlmDocument, useUpdateDocument, type DocType, type PlmDocument,
} from '@/features/manufacturing/api-plm';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { DocStatusPill, Field, FileLink, ProductPicker } from './plm-ui';
import { DOC_TYPES, day, selectClass } from './vocab';

/** New or edit (draft) engineering document; the file is uploaded to /files first, then referenced by id. */
export function DocumentDialog({ open, onClose, doc, productId: presetProduct, changeId, onCreated }: {
  open: boolean; onClose: () => void; doc?: PlmDocument | null; productId?: string | null; changeId?: string;
  onCreated?: (d: PlmDocument) => void;
}) {
  const init = () => ({
    title: doc?.title ?? '',
    docType: (doc?.docType ?? 'drawing') as DocType,
    revision: doc?.revision ?? '',
    productId: doc?.productId ?? presetProduct ?? '',
    url: doc?.url ?? '',
    description: doc?.description ?? '',
    fileId: doc?.fileId ?? '',
    fileName: doc?.fileName ?? '',
  });
  const [f, setF] = useState(init);
  const [uploading, setUploading] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (open) setF(init());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, doc?.id, presetProduct]);
  const set = <K extends keyof typeof f>(k: K, v: (typeof f)[K]) => setF((s) => ({ ...s, [k]: v }));
  const create = useCreateDocument();
  const update = useUpdateDocument();

  const onFile = async (file?: File | null) => {
    if (!file) return;
    setUploading(true);
    try {
      const up = await uploadPlmFile(file);
      setF((s) => ({ ...s, fileId: up.id, fileName: up.filename, title: s.title || file.name.replace(/\.[^.]+$/, '') }));
    } catch (e) {
      notify.error(tx('Upload failed'), plmError(e));
    } finally {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const submit = () => {
    if (!f.title.trim()) return notify.error(tx('Give the document a title.'));
    if (f.url && !/^https?:\/\//.test(f.url.trim())) return notify.error(tx('The link must start with http:// or https://'));
    if (doc) {
      update.mutate({
        id: doc.id,
        body: {
          title: f.title.trim(), docType: f.docType, revision: f.revision.trim() || undefined, productId: f.productId,
          url: f.url.trim(), description: f.description, fileId: f.fileId, fileName: f.fileName,
        },
      }, { onSuccess: () => { notify.success(tx('Document saved')); onClose(); } });
    } else {
      create.mutate({
        title: f.title.trim(), docType: f.docType, revision: f.revision.trim() || undefined, productId: f.productId || undefined,
        url: f.url.trim() || undefined, description: f.description.trim() || undefined, fileId: f.fileId || undefined,
        fileName: f.fileName || undefined, changeId: changeId || undefined,
      }, {
        onSuccess: (d) => { notify.success(tx('{{n}} created', { n: d.number })); onClose(); onCreated?.(d); },
      });
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{doc ? tx('Edit {{n}} v{{v}}', { n: doc.number, v: doc.version }) : tx('New document')}</DialogTitle>
          <DialogDescription>
            {changeId ? tx('The draft is attached to this change and released when the change is applied.')
              : tx('Documents start as drafts; release them directly or through a change order.')}
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={tx('Title')} required className="sm:col-span-2">
            <Input value={f.title} maxLength={200} onChange={(e) => set('title', e.target.value)} />
          </Field>
          <Field label={tx('Type')}>
            <select className={selectClass} value={f.docType} onChange={(e) => set('docType', e.target.value as DocType)}>
              {(Object.keys(DOC_TYPES) as DocType[]).map((t) => <option key={t} value={t}>{DOC_TYPES[t]}</option>)}
            </select>
          </Field>
          <Field label={tx('Revision')} hint={doc ? undefined : tx('Default A.')}>
            <Input value={f.revision} maxLength={10} onChange={(e) => set('revision', e.target.value)} placeholder="A" />
          </Field>
          <Field label={tx('Product')} className="sm:col-span-2">
            <ProductPicker value={f.productId} onChange={(v) => set('productId', v)} clearable placeholder={tx('Optional')} />
          </Field>
          <Field label={tx('File')} className="sm:col-span-2">
            <input ref={fileInput} type="file" className="hidden" onChange={(e) => void onFile(e.target.files?.[0])} />
            {f.fileId ? (
              <div className="flex items-center gap-2 rounded-md border bg-muted/30 px-3 py-2 text-sm">
                <Paperclip className="h-4 w-4 text-muted-foreground" />
                <span className="flex-1 truncate">{f.fileName || tx('Uploaded file')}</span>
                <Button type="button" size="sm" variant="ghost" onClick={() => fileInput.current?.click()} disabled={uploading}>{tx('Replace')}</Button>
                <Button type="button" size="sm" variant="ghost" onClick={() => setF((s) => ({ ...s, fileId: '', fileName: '' }))} aria-label={tx('Remove file')}>
                  <X className="h-4 w-4" />
                </Button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => fileInput.current?.click()}
                disabled={uploading}
                className={cn('flex w-full items-center justify-center gap-2 rounded-md border border-dashed px-3 py-4 text-sm text-muted-foreground hover:bg-muted/40',
                  uploading && 'opacity-60')}
              >
                {uploading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
                {uploading ? tx('Uploading…') : tx('Upload a file')}
              </button>
            )}
          </Field>
          <Field label={tx('Or a link')} className="sm:col-span-2" hint={tx('e.g. the CAD vault or a shared drive (http/https).')}>
            <Input value={f.url} maxLength={2000} onChange={(e) => set('url', e.target.value)} placeholder="https://" />
          </Field>
          <Field label={tx('Description')} className="sm:col-span-2">
            <Textarea rows={3} maxLength={4000} value={f.description} onChange={(e) => set('description', e.target.value)} />
          </Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={create.isPending || update.isPending || uploading}>{doc ? tx('Save') : tx('Create document')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Every version of a document number. */
export function VersionsDialog({ docId, onClose }: { docId: string | null; onClose: () => void }) {
  const { data, isLoading } = usePlmDocument(docId);
  return (
    <Dialog open={!!docId} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{data ? `${data.number} · ${data.title}` : tx('Versions')}</DialogTitle>
          <DialogDescription>{tx('One version is released at a time; releasing a new one supersedes the previous.')}</DialogDescription>
        </DialogHeader>
        {isLoading || !data ? <Skeleton className="h-40 w-full" /> : (
          <ol className="relative space-y-3 border-l pl-5">
            {data.versions.map((v) => (
              <li key={v.id} className="relative">
                <span className={cn('absolute -left-[26px] top-1 h-3 w-3 rounded-full border-2 border-background',
                  v.status === 'released' ? 'bg-emerald-500' : v.status === 'draft' ? 'bg-sky-500' : 'bg-slate-300')} />
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">v{v.version}</span>
                  <span className="rounded border px-1.5 font-mono text-[11px]">{tx('Rev')} {v.revision}</span>
                  <DocStatusPill status={v.status} />
                  {v.id === data.id && <span className="text-xs text-primary">{tx('(this one)')}</span>}
                </div>
                <p className="mt-0.5 text-xs text-muted-foreground">
                  {tx('Created')} {day(v.createdAt)}{v.releasedAt && <> · {tx('released')} {day(v.releasedAt)}</>}
                </p>
                <div className="mt-1"><FileLink fileId={v.fileId} url={v.url} fileName={v.fileName} /></div>
              </li>
            ))}
          </ol>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Close')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

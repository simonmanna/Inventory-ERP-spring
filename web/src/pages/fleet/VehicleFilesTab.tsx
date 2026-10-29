import { useRef, useState } from 'react';
import { Camera, FileText, Trash2, Upload } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { formatBytes, dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  useDeleteAttachment, useUploadAttachment, useVehicleAttachments, type Vehicle, type VehicleAttachment,
} from '@/features/fleet/api';

/** Vehicle photos and documents, stored in the platform file store (fleet 1.1). Uses the camera on phones. */
export function VehicleFilesTab({ vehicle }: { vehicle: Vehicle }) {
  const canUpload = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetVehicle.update)) && vehicle.status !== 'disposed';
  const { data, isLoading, isError } = useVehicleAttachments(vehicle.id);
  const upload = useUploadAttachment();
  const remove = useDeleteAttachment();
  const photoInput = useRef<HTMLInputElement>(null);
  const docInput = useRef<HTMLInputElement>(null);
  const [title, setTitle] = useState('');

  const onFile = async (file: File | undefined, kind: 'photo' | 'document', input: HTMLInputElement | null) => {
    if (!file) return;
    try {
      await upload.mutateAsync({ vehicleId: vehicle.id, file, kind, title: title.trim() || undefined });
      notify.success(tx('Uploaded'));
      setTitle('');
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not upload the file')));
    } finally {
      if (input) input.value = '';
    }
  };

  const del = async (a: VehicleAttachment) => {
    if (!window.confirm(tx('Remove this file?'))) return;
    try {
      await remove.mutateAsync({ vehicleId: vehicle.id, attachmentId: a.id });
      notify.success(tx('Removed'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not remove the file')));
    }
  };

  const photos = (data ?? []).filter((a) => a.kind === 'photo');
  const documents = (data ?? []).filter((a) => a.kind === 'document');

  return (
    <div className="space-y-4">
      {canUpload && (
        <Card>
          <CardContent className="flex flex-wrap items-end gap-2 pt-4">
            <div className="min-w-[12rem] flex-1 space-y-1.5">
              <label className="text-sm font-medium">{tx('Title (optional)')}</label>
              <Input value={title} onChange={(e) => setTitle(e.target.value)} />
            </div>
            <input ref={photoInput} type="file" accept="image/*" capture="environment" className="hidden"
              onChange={(e) => onFile(e.target.files?.[0], 'photo', e.target)} />
            <input ref={docInput} type="file" className="hidden" onChange={(e) => onFile(e.target.files?.[0], 'document', e.target)} />
            <Button variant="outline" onClick={() => photoInput.current?.click()} disabled={upload.isPending}><Camera className="mr-1.5 h-4 w-4" /> {tx('Add photo')}</Button>
            <Button variant="outline" onClick={() => docInput.current?.click()} disabled={upload.isPending}><Upload className="mr-1.5 h-4 w-4" /> {tx('Add document')}</Button>
          </CardContent>
        </Card>
      )}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <p className="text-sm text-muted-foreground">{tx('Could not load the files.')}</p>}

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Photos')}</CardTitle></CardHeader>
        <CardContent>
          {photos.length === 0 ? <p className="text-sm text-muted-foreground">{tx('No photos yet.')}</p> : (
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
              {photos.map((p) => (
                <figure key={p.id} className="group relative overflow-hidden rounded-lg border">
                  <a href={p.url} target="_blank" rel="noreferrer"><img src={p.url} alt={p.title ?? p.filename} className="aspect-video w-full object-cover" /></a>
                  <figcaption className="flex items-center justify-between gap-1 px-2 py-1 text-xs">
                    <span className="truncate">{p.title ?? p.filename}</span>
                    {canUpload && <button type="button" aria-label={tx('Remove')} onClick={() => del(p)}><Trash2 className="h-3.5 w-3.5 text-muted-foreground" /></button>}
                  </figcaption>
                </figure>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Documents')}</CardTitle></CardHeader>
        <CardContent className="divide-y">
          {documents.length === 0 && <p className="text-sm text-muted-foreground">{tx('No documents yet.')}</p>}
          {documents.map((d) => (
            <div key={d.id} className="flex items-center gap-3 py-2 text-sm">
              <FileText className="h-4 w-4 shrink-0 text-muted-foreground" />
              <a href={d.url} target="_blank" rel="noreferrer" className="min-w-0 flex-1 truncate text-primary hover:underline">{d.title ?? d.filename}</a>
              {d.complianceDocumentId && <span className="text-xs text-muted-foreground">{tx('Compliance')}</span>}
              <span className="hidden text-xs text-muted-foreground sm:inline">{formatBytes(d.byteSize)} · {dateTime(d.createdAt)}</span>
              {canUpload && <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Remove')} onClick={() => del(d)}><Trash2 className="h-4 w-4" /></Button>}
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}

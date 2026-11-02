import { useEffect, useState } from 'react';
import { Download, ExternalLink, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { resolveAssetUrl } from '@/lib/api';
import { apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { signedUrl, type DmsSigned } from '@/features/dms/api';
import { downloadDocument, fileIcon, isImage } from './file-utils';

interface Props {
  documentId: string | null;
  name?: string;
  contentType?: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** In-app preview of a DMS document: PDF / text in a sandboxed frame, images inline, anything else as download. */
export function FilePreviewDialog({ documentId, name, contentType, open, onOpenChange }: Props) {
  const [signed, setSigned] = useState<DmsSigned | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open || !documentId) return;
    let alive = true;
    setSigned(null);
    setError(null);
    signedUrl(documentId)
      .then((s) => alive && setSigned(s))
      .catch((err) => alive && setError(apiMessage(err, tx('Could not open the file'))));
    return () => {
      alive = false;
    };
  }, [open, documentId]);

  const type = signed?.contentType ?? contentType;
  const preview = signed?.previewUrl ? resolveAssetUrl(signed.previewUrl) : undefined;
  const Icon = fileIcon(type, name);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex h-[88vh] max-w-5xl flex-col gap-3">
        <DialogHeader className="flex-row items-center justify-between gap-3 space-y-0 pr-8">
          <DialogTitle className="truncate">{name ?? signed?.filename ?? tx('Preview')}</DialogTitle>
          <div className="flex shrink-0 gap-2">
            {preview && (
              <Button variant="outline" size="sm" asChild>
                <a href={preview} target="_blank" rel="noopener noreferrer">
                  <ExternalLink className="mr-1 h-4 w-4" />
                  {tx('Open')}
                </a>
              </Button>
            )}
            {documentId && (
              <Button size="sm" onClick={() => downloadDocument(documentId)}>
                <Download className="mr-1 h-4 w-4" />
                {tx('Download')}
              </Button>
            )}
          </div>
        </DialogHeader>
        <div className="min-h-0 flex-1 overflow-hidden rounded-md border bg-muted/30">
          {error ? (
            <div className="flex h-full items-center justify-center p-6 text-sm text-destructive">{error}</div>
          ) : !signed ? (
            <div className="flex h-full items-center justify-center">
              <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
            </div>
          ) : preview && isImage(type) ? (
            <div className="flex h-full items-center justify-center overflow-auto p-4">
              <img src={preview} alt={name ?? ''} className="max-h-full max-w-full object-contain" />
            </div>
          ) : preview ? (
            <iframe title={name ?? 'preview'} src={preview}
              className="h-full w-full bg-white" />
          ) : (
            <div className="flex h-full flex-col items-center justify-center gap-3 p-6 text-center text-sm text-muted-foreground">
              <Icon className="h-12 w-12" />
              <p>{tx('No preview for this file type.')}</p>
              {documentId && (
                <Button onClick={() => downloadDocument(documentId)}>
                  <Download className="mr-1 h-4 w-4" />
                  {tx('Download')}
                </Button>
              )}
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

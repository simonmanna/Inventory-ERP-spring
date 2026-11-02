import { useState } from 'react';
import { Copy, Link2, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useCreateShareLink, useRevokeShareLink, useShareLinks, type DmsShareLink } from '@/features/dms/api';

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Share these documents, or ... */
  documentIds?: string[];
  /** ... a folder (or workspace root) — optionally accepting uploads. */
  workspaceId?: string;
  folderId?: string | null;
  title?: string;
}

/** Create and manage external share links (expiry, password, download limit, optional upload drop box). */
export function ShareDialog({ open, onOpenChange, documentIds, workspaceId, folderId, title }: Props) {
  const docId = documentIds?.length === 1 ? documentIds[0] : undefined;
  const { data: links } = useShareLinks(docId, open && !!docId);
  const create = useCreateShareLink();
  const revoke = useRevokeShareLink();
  const [days, setDays] = useState('7');
  const [password, setPassword] = useState('');
  const [maxDownloads, setMaxDownloads] = useState('');
  const [allowUpload, setAllowUpload] = useState(false);
  const [created, setCreated] = useState<DmsShareLink | null>(null);
  const folderShare = !documentIds?.length;

  const submit = async () => {
    try {
      const link = await create.mutateAsync({
        name: title,
        documentIds: folderShare ? undefined : documentIds,
        workspaceId: folderShare ? workspaceId : undefined,
        folderId: folderShare ? folderId ?? undefined : undefined,
        expiresInDays: Number(days) || 7,
        password: password || undefined,
        maxDownloads: maxDownloads ? Number(maxDownloads) : undefined,
        allowUpload: folderShare ? allowUpload : undefined,
      });
      setCreated(link);
      setPassword('');
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not create the link')));
    }
  };

  const copy = async (url: string) => {
    try {
      await navigator.clipboard.writeText(url);
      notify.success(tx('Link copied'));
    } catch {
      window.prompt(tx('Copy the link'), url);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><Link2 className="h-4 w-4" />{tx('Share')} {title ? `"${title}"` : ''}</DialogTitle>
        </DialogHeader>
        {created?.url ? (
          <div className="space-y-3 text-sm">
            <p>{tx('Anyone with this link can open the shared files until it expires. It is shown only once.')}</p>
            <div className="flex gap-2">
              <Input readOnly value={created.url} onFocus={(e) => e.target.select()} />
              <Button size="icon" onClick={() => copy(created.url!)}><Copy className="h-4 w-4" /></Button>
            </div>
            <p className="text-xs text-muted-foreground">{tx('Expires {{d}}', { d: dateTime(created.expiresAt) })}</p>
            <Button variant="outline" onClick={() => setCreated(null)}>{tx('Create another')}</Button>
          </div>
        ) : (
          <div className="grid gap-3 text-sm">
            <label className="grid gap-1">{tx('Valid for (days)')}
              <Input type="number" min={1} max={365} value={days} onChange={(e) => setDays(e.target.value)} />
            </label>
            <label className="grid gap-1">{tx('Password (optional)')}
              <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
            </label>
            <label className="grid gap-1">{tx('Maximum downloads (optional)')}
              <Input type="number" min={1} value={maxDownloads} onChange={(e) => setMaxDownloads(e.target.value)} />
            </label>
            {folderShare && (
              <label className="flex items-center gap-2">
                <input type="checkbox" checked={allowUpload} onChange={(e) => setAllowUpload(e.target.checked)} />
                {tx('Allow the recipient to upload files (they wait for approval)')}
              </label>
            )}
            <Button onClick={submit} disabled={create.isPending}>
              {create.isPending && <Loader2 className="mr-1 h-4 w-4 animate-spin" />}{tx('Create link')}
            </Button>
          </div>
        )}
        {docId && (links ?? []).length > 0 && (
          <div className="mt-2 space-y-1 border-t pt-3 text-xs">
            <div className="font-medium text-muted-foreground">{tx('Existing links')}</div>
            {(links ?? []).map((l) => (
              <div key={l.id} className="flex items-center gap-2">
                <Badge variant={l.active ? 'default' : 'outline'}>{l.active ? tx('Active') : tx('Inactive')}</Badge>
                <span className="flex-1">
                  {tx('Expires {{d}}', { d: dateTime(l.expiresAt) })} · {tx('{{n}} downloads', { n: l.downloadCount })}
                  {l.hasPassword ? ` · ${tx('password')}` : ''}
                </span>
                {l.active && (
                  <Button size="sm" variant="ghost" className="h-6 text-destructive"
                    onClick={() => revoke.mutateAsync(l.id).then(() => notify.success(tx('Link revoked')))}>
                    {tx('Revoke')}
                  </Button>
                )}
              </div>
            ))}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

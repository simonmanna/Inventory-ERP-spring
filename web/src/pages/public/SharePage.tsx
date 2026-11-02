import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Download, FileText, Loader2, Lock, Upload } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { getApiBaseUrl } from '@/lib/api';
import { formatBytes, dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

interface ShareView {
  name: string | null;
  organizationName: string | null;
  expiresAt: string;
  allowDownload: boolean;
  allowUpload: boolean;
  downloadsRemaining: number | null;
  documents: Array<{ id: string; name: string; contentType: string | null; byteSize: number; updatedAt: string }>;
}

/** Public page of a DMS share link (no login): list / download shared files, optionally upload. */
export function SharePage() {
  const { token = '' } = useParams();
  const base = `${getApiBaseUrl()}/public/dms/share/${encodeURIComponent(token)}`;
  const [password, setPassword] = useState('');
  const [needPassword, setNeedPassword] = useState(false);
  const [view, setView] = useState<ShareView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [uploader, setUploader] = useState('');
  const [uploaded, setUploaded] = useState<string[]>([]);
  const input = useRef<HTMLInputElement>(null);

  const headers = useCallback((): Record<string, string> => (password ? { 'X-Share-Password': password } : {}), [password]);

  const load = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await fetch(base, { headers: headers() });
      const body = await r.json().catch(() => ({}));
      if (r.status === 403 && /password/i.test(String(body.message))) {
        setNeedPassword(true);
        if (password) setError(tx('Wrong password.'));
        return;
      }
      if (!r.ok) {
        setError(String(body.message ?? tx('This link is not valid.')));
        return;
      }
      setNeedPassword(false);
      setView(body as ShareView);
    } catch {
      setError(tx('Could not reach the server.'));
    } finally {
      setBusy(false);
    }
  }, [base, headers, password]);

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const download = async (id: string, name: string) => {
    const r = await fetch(`${base}/documents/${id}/download`, { headers: headers() });
    if (!r.ok) {
      const body = await r.json().catch(() => ({}));
      setError(String(body.message ?? tx('Download failed.')));
      return;
    }
    const blob = await r.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    a.click();
    URL.revokeObjectURL(url);
    void load();
  };

  const upload = async (files: FileList | null) => {
    if (!files?.length) return;
    const fd = new FormData();
    Array.from(files).forEach((f) => fd.append('file', f));
    if (uploader.trim()) fd.append('uploaderName', uploader.trim());
    setBusy(true);
    try {
      const r = await fetch(`${base}/upload`, { method: 'POST', headers: headers(), body: fd });
      const body = await r.json().catch(() => ({}));
      if (!r.ok) setError(String(body.message ?? tx('Upload failed.')));
      else setUploaded((u) => [...u, ...(body as Array<{ name: string }>).map((d) => d.name)]);
    } finally {
      setBusy(false);
      if (input.current) input.current.value = '';
    }
  };

  return (
    <div className="flex min-h-screen items-start justify-center bg-muted/40 p-4 sm:p-10">
      <Card className="w-full max-w-2xl">
        <CardHeader>
          <CardTitle>{view?.name ?? tx('Shared files')}</CardTitle>
          {view?.organizationName && <p className="text-sm text-muted-foreground">{tx('Shared by {{o}}', { o: view.organizationName })}</p>}
        </CardHeader>
        <CardContent className="space-y-4 text-sm">
          {error && <p className="rounded-md bg-destructive/10 p-2 text-destructive">{error}</p>}
          {needPassword && (
            <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); void load(); }}>
              <div className="relative flex-1">
                <Lock className="absolute left-2 top-2.5 h-4 w-4 text-muted-foreground" />
                <Input type="password" className="pl-8" value={password} onChange={(e) => setPassword(e.target.value)}
                  placeholder={tx('Password')} autoFocus />
              </div>
              <Button type="submit" disabled={busy || !password}>{tx('Open')}</Button>
            </form>
          )}
          {busy && !view && <Loader2 className="h-5 w-5 animate-spin" />}
          {view && (
            <>
              <p className="text-xs text-muted-foreground">
                {tx('Available until {{d}}', { d: dateTime(view.expiresAt) })}
                {view.downloadsRemaining != null && ` · ${tx('{{n}} download(s) left', { n: view.downloadsRemaining })}`}
              </p>
              {view.documents.length > 0 ? (
                <ul className="divide-y rounded-md border">
                  {view.documents.map((d) => (
                    <li key={d.id} className="flex items-center gap-2 p-2">
                      <FileText className="h-4 w-4 text-muted-foreground" />
                      <span className="flex-1 truncate">{d.name}</span>
                      <span className="text-xs text-muted-foreground">{formatBytes(d.byteSize)}</span>
                      {view.allowDownload && (
                        <Button size="sm" variant="outline" onClick={() => download(d.id, d.name)}>
                          <Download className="mr-1 h-4 w-4" />{tx('Download')}
                        </Button>
                      )}
                    </li>
                  ))}
                </ul>
              ) : !view.allowUpload && <p className="text-muted-foreground">{tx('No files are shared.')}</p>}
              {view.allowUpload && (
                <div className="space-y-2 rounded-md border border-dashed p-4">
                  <p className="font-medium">{tx('Send files')}</p>
                  <Input value={uploader} onChange={(e) => setUploader(e.target.value)} placeholder={tx('Your name or company')} />
                  <Button onClick={() => input.current?.click()} disabled={busy}><Upload className="mr-1 h-4 w-4" />{tx('Choose files')}</Button>
                  <input ref={input} type="file" multiple hidden onChange={(e) => void upload(e.target.files)} />
                  {uploaded.length > 0 && (
                    <p className="text-xs text-green-700">{tx('Received: {{f}}', { f: uploaded.join(', ') })}</p>
                  )}
                </div>
              )}
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

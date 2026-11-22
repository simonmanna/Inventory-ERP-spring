import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Copy, Link2 } from 'lucide-react';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

interface LinkStatus {
  active: boolean;
  expiresAt?: string;
  viewCount?: number;
  firstViewedAt?: string | null;
  lastViewedAt?: string | null;
  decision?: 'accepted' | 'declined' | null;
  decidedAt?: string | null;
  signerName?: string | null;
  signerNote?: string | null;
}

/** Share one quotation online: the customer views, downloads, accepts or declines it (CRM enhancements T3a). */
export function QuoteShareDialog({ quotationId, status, open, onOpenChange }: {
  quotationId: string; status: string; open: boolean; onOpenChange: (o: boolean) => void;
}) {
  const qc = useQueryClient();
  const [url, setUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const key = ['crm-suite', 'quote-link', quotationId];
  const { data: link, error } = useQuery({
    queryKey: key,
    queryFn: async () => (await api.get<LinkStatus>(`/sales/quotations/${quotationId}/share-link`)).data,
    enabled: open,
    retry: false,
  });
  const shareable = ['approved', 'sent'].includes(status);
  const act = async (create: boolean) => {
    setBusy(true);
    try {
      if (create) {
        const r = (await api.post<{ path: string }>(`/sales/quotations/${quotationId}/share-link`)).data;
        setUrl(window.location.origin + r.path);
      } else {
        await api.delete(`/sales/quotations/${quotationId}/share-link`);
        setUrl(null);
        toast.success(tx('Link turned off'));
      }
      await qc.invalidateQueries({ queryKey: key });
    } catch (e) {
      toast.error(apiErrorMessage(e, tx('Could not update the link')));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) setUrl(null); onOpenChange(v); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{tx('Online quotation link')}</DialogTitle></DialogHeader>
        <div className="space-y-3 text-sm">
          <p className="text-muted-foreground">{tx('Send the customer a private link to view this quotation, download the PDF and accept it by typing their name, or decline it. You are notified when they open or answer it.')}</p>
          {error && <p className="rounded-lg bg-rose-50 p-2 text-rose-700 dark:bg-rose-950/40 dark:text-rose-200">{apiErrorMessage(error, tx('Online links are not available'))}</p>}
          {url && (
            <>
              <div className="flex gap-2">
                <Input readOnly value={url} onFocus={(e) => e.currentTarget.select()} aria-label={tx('Quotation link')} />
                <Button variant="outline" onClick={() => { void navigator.clipboard?.writeText(url); toast.success(tx('Copied')); }}><Copy className="h-4 w-4" /></Button>
              </div>
              <p className="text-xs text-muted-foreground">{tx('Shown only now. Valid for 60 days; creating a new link replaces it.')}</p>
            </>
          )}
          {!url && link?.active && (
            <dl className="grid grid-cols-[auto,1fr] gap-x-3 gap-y-1 rounded-lg border p-3">
              <dt className="text-muted-foreground">{tx('Views')}</dt>
              <dd>{link.viewCount ?? 0}{link.lastViewedAt ? ` · ${tx('last')} ${dateTime(link.lastViewedAt)}` : ` · ${tx('not opened yet')}`}</dd>
              <dt className="text-muted-foreground">{tx('Answer')}</dt>
              <dd>
                {link.decision === 'accepted' ? <span className="font-medium text-emerald-700 dark:text-emerald-400">{tx('Accepted by')} {link.signerName}</span>
                  : link.decision === 'declined' ? <span className="font-medium text-rose-700 dark:text-rose-400">{tx('Declined')}{link.signerName ? ` · ${link.signerName}` : ''}</span>
                    : tx('Waiting')}
                {link.decidedAt ? ` · ${dateTime(link.decidedAt)}` : ''}
                {link.signerNote ? <p className="text-xs text-muted-foreground">“{link.signerNote}”</p> : null}
              </dd>
              <dt className="text-muted-foreground">{tx('Expires')}</dt>
              <dd>{link.expiresAt ? dateTime(link.expiresAt) : '—'}</dd>
            </dl>
          )}
          {!url && link && !link.active && !error && <p>{tx('No live link for this quotation.')}</p>}
          {!shareable && <p className="text-xs text-muted-foreground">{tx('Only approved or sent quotations can be shared.')}</p>}
        </div>
        <DialogFooter>
          {link?.active && <Button variant="outline" disabled={busy} onClick={() => act(false)}>{tx('Turn off')}</Button>}
          {shareable && !error && <Button disabled={busy} onClick={() => act(true)}><Link2 className="mr-1.5 h-4 w-4" />{link?.active ? tx('Create a new link') : tx('Create link')}</Button>}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

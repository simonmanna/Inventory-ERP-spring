import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Merge } from 'lucide-react';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { tx } from '@/lib/i18n/tx';
import { useCrmMutation } from '@/features/crm/suite-api';
import { PartnerPicker } from './crm-dialogs';

const LABEL: Record<string, string> = { Deal: tx('leads and opportunities'), Activity: tx('activities'), CrmCase: tx('cases'), Contact: tx('contacts'), SalesQuotation: tx('quotations') };

/** Merge a CRM-only duplicate customer into this one (CRM enhancements T3b). */
export function CustomerMergeDialog({ partnerId, partnerName, open, onOpenChange }: {
  partnerId: string; partnerName: string; open: boolean; onOpenChange: (v: boolean) => void;
}) {
  const [sourceId, setSourceId] = useState('');
  const [sourceName, setSourceName] = useState<string | null>(null);
  useEffect(() => { if (!open) { setSourceId(''); setSourceName(null); } }, [open]);
  const preview = useQuery({
    queryKey: ['crm-suite', 'customer-merge', partnerId, sourceId],
    queryFn: async () => (await api.get<{ moves: Record<string, number>; blockedBy: string[] }>(`/crm/customers/${partnerId}/merge-preview`, { params: { sourceId } })).data,
    enabled: open && !!sourceId && sourceId !== partnerId,
    retry: false,
  });
  const merge = useCrmMutation(() => api.post(`/crm/customers/${partnerId}/merge`, { sourceId }));
  const blocked = (preview.data?.blockedBy ?? []).length > 0;
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{tx('Merge a duplicate customer into')} {partnerName}</DialogTitle></DialogHeader>
        <div className="space-y-3 text-sm">
          <p className="text-muted-foreground">{tx('Its leads, opportunities, activities, cases, contacts and open quotations move here and the duplicate is archived. Customers with orders, invoices, payments or other records outside CRM cannot be merged.')}</p>
          <PartnerPicker value={sourceId} label={sourceName} onPick={(id, name) => { setSourceId(id); setSourceName(name || null); }} placeholder={tx('Find the duplicate customer…')} />
          {sourceId === partnerId && <p className="text-rose-600">{tx('Choose a different customer.')}</p>}
          {preview.error && <p className="text-rose-600">{apiErrorMessage(preview.error, tx('Could not check this customer'))}</p>}
          {preview.data && !blocked && (
            <ul className="list-disc rounded-lg border p-3 pl-7">
              {Object.entries(preview.data.moves).map(([k, n]) => <li key={k}>{n} {LABEL[k] ?? k}</li>)}
            </ul>
          )}
          {blocked && <p className="rounded-lg bg-amber-50 p-2 text-amber-900 dark:bg-amber-950/40 dark:text-amber-200">{tx('Cannot merge: this customer also has records in')} {preview.data!.blockedBy.join(', ')}.</p>}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button disabled={!preview.data || blocked || merge.isPending} onClick={() => merge.mutate(undefined, { onSuccess: () => { toast.success(tx('Customers merged')); onOpenChange(false); }, onError: (e) => toast.error(apiErrorMessage(e, tx('Could not merge'))) })}><Merge className="mr-1.5 h-4 w-4" />{tx('Merge')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

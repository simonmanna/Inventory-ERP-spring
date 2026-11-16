import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { notify } from '@/lib/notify';
import { formatCurrency } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { useAssetImpairments, useRecordImpairment } from '@/features/accounting/gl-automation-api';

/** IAS 36 impairment and reversal of one asset (ADR-S073). */
export function ImpairmentTab({ assetId, canImpair, disposed }: { assetId: string; canImpair: boolean; disposed: boolean }) {
  const { data, isLoading } = useAssetImpairments(assetId);
  const record = useRecordImpairment();
  const [mode, setMode] = useState<'impairment' | 'reversal' | null>(null);
  const [recoverable, setRecoverable] = useState('');
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [reason, setReason] = useState('');

  if (isLoading || !data) return <div className="text-muted-foreground">{tx('Loading...')}</div>;

  const submit = async () => {
    try {
      await record.mutateAsync({ assetId, reversal: mode === 'reversal', recoverableAmount: Number(recoverable), date, reason });
      notify.success(mode === 'reversal' ? tx('Impairment reversed') : tx('Impairment recorded'));
      setMode(null);
      setRecoverable('');
      setReason('');
    } catch (e: any) {
      const m = e?.response?.data?.message;
      notify.error(Array.isArray(m) ? m.join(', ') : m ?? tx('Error'));
    }
  };

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        <Card><CardHeader><CardTitle className="text-sm font-medium">{tx('Carrying amount')}</CardTitle></CardHeader>
          <CardContent><p className="text-2xl font-bold">{formatCurrency(data.carryingAmount)}</p></CardContent></Card>
        <Card><CardHeader><CardTitle className="text-sm font-medium">{tx('Accumulated impairment')}</CardTitle></CardHeader>
          <CardContent><p className="text-2xl font-bold">{formatCurrency(data.accumulatedImpairment)}</p></CardContent></Card>
        <Card><CardHeader><CardTitle className="text-sm font-medium">{tx('Reversible up to')}</CardTitle></CardHeader>
          <CardContent><p className="text-2xl font-bold">{formatCurrency(data.maxReversal)}</p></CardContent></Card>
      </div>
      {canImpair && !disposed && (
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => setMode('impairment')}>{tx('Record impairment')}</Button>
          <Button variant="outline" onClick={() => setMode('reversal')} disabled={Number(data.maxReversal) <= 0}>{tx('Reverse impairment')}</Button>
        </div>
      )}
      {data.impairments.length === 0 ? (
        <p className="text-muted-foreground">{tx('No impairment recorded.')}</p>
      ) : (
        <div className="space-y-2 text-sm">
          {data.impairments.map((i) => (
            <div key={i.id} className="flex items-center justify-between border-b pb-2">
              <div>
                <Badge variant={i.kind === 'impairment' ? 'destructive' : 'secondary'}>{i.kind === 'impairment' ? tx('Impairment') : tx('Reversal')}</Badge>{' '}
                <span>{new Date(i.impairmentDate).toLocaleDateString()}</span>
                {i.reason && <span className="text-muted-foreground"> · {i.reason}</span>}
                <p className="text-xs text-muted-foreground">
                  {tx('{{before}} → {{after}} (recoverable {{r}}), {{m}} months left', {
                    before: formatCurrency(i.carryingBefore), after: formatCurrency(i.carryingAfter), r: formatCurrency(i.recoverableAmount), m: i.remainingLifeMonths,
                  })}
                  {i.entryNumber ? ` · ${i.entryNumber}` : ''}
                </p>
              </div>
              <span className="font-medium">{formatCurrency(i.amount)}</span>
            </div>
          ))}
        </div>
      )}
      <Dialog open={!!mode} onOpenChange={(o) => !o && setMode(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>{mode === 'reversal' ? tx('Reverse impairment') : tx('Record impairment')}</DialogTitle></DialogHeader>
          <div className="space-y-3">
            <p className="text-sm text-muted-foreground">
              {mode === 'reversal'
                ? tx('The carrying amount rises to the recoverable amount, capped at what it would be without impairment.')
                : tx('The carrying amount is written down to the recoverable amount (Dr impairment loss / Cr accumulated impairment).')}
            </p>
            <div><Label>{tx('Recoverable amount')}</Label><Input type="number" min="0" step="0.01" value={recoverable} onChange={(e) => setRecoverable(e.target.value)} /></div>
            <div><Label>{tx('Date')}</Label><Input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></div>
            <div><Label>{tx('Reason')}</Label><Input value={reason} onChange={(e) => setReason(e.target.value)} /></div>
          </div>
          <DialogFooter><Button onClick={() => void submit()} disabled={record.isPending || recoverable === ''}>{tx('Post')}</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

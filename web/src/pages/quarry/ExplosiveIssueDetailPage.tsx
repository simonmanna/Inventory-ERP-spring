import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, PackageCheck, RefreshCw, RotateCcw, ShieldAlert, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { EXPLOSIVE_KIND_LABEL, useIssue, useIssueAction, useIssueExplosives, useReconcileExplosives, type ExplosiveIssueDetail } from '@/features/quarry/api';
import { EmptyState, IssueStatusPill, JournalTable, KeyValues, ReasonDialog, Section, qty } from './quarry-ui';

function ReconcileDialog({ issue, onClose }: { issue: ExplosiveIssueDetail; onClose: () => void }) {
  const reconcile = useReconcileExplosives();
  const [returned, setReturned] = useState<Record<string, string>>({});
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{tx('Reconcile {{code}}', { code: issue.code })}</DialogTitle>
          <DialogDescription>{tx('Enter the unused quantity returned to the magazine (0 when all was used).')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-2">
          {issue.lines.map((l) => (
            <div key={l.id} className="grid grid-cols-[1fr_120px] items-center gap-2 text-sm">
              <span>{l.productName} · {tx('issued')} {qty(l.issuedQty, 3)}</span>
              <Input type="number" inputMode="decimal" min="0" max={l.issuedQty} placeholder="0" value={returned[l.id] ?? ''}
                onChange={(e) => setReturned((x) => ({ ...x, [l.id]: e.target.value }))} />
            </div>
          ))}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={reconcile.isPending} onClick={async () => {
            try {
              await reconcile.mutateAsync({ id: issue.id, body: { lines: issue.lines.map((l) => ({ lineId: l.id, returnedQty: Number(returned[l.id] || 0) })) } });
              notify.success(tx('Explosives reconciled'));
              onClose();
            } catch (e) { notify.error(apiMessage(e, tx('Could not reconcile'))); }
          }}>{tx('Reconcile')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** An explosive issue: lines with magazine stock, approval status, issue / reconcile / cancel, GL and the audit trail. */
export function ExplosiveIssueDetailPage() {
  const { id = '' } = useParams();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: i, isLoading, isError } = useIssue(id);
  const issue = useIssueExplosives();
  const act = useIssueAction();
  const [dialog, setDialog] = useState<'reconcile' | 'cancel' | null>(null);
  if (isLoading) return <Skeleton className="h-96" />;
  if (isError || !i) return <EmptyState>{tx('Could not load the explosive issue.')}</EmptyState>;
  const keeper = has(PERMISSIONS.quarryBlasting.issueExplosive);
  return (
    <div className="space-y-4">
      <Button variant="ghost" size="sm" asChild><Link to="/quarry/explosive-issues"><ArrowLeft className="mr-1 h-4 w-4" /> {tx('Explosive issues')}</Link></Button>
      <ListPageHeader icon={ShieldAlert} title={`${i.code} · ${i.magazineCode}`} description={`${tx('Blast')} ${i.blastCode} · ${i.siteCode}`}
        actions={
          <>
            <IssueStatusPill status={i.status} />
            {i.status === 'pending_approval' && (
              <>
                <Button variant="outline" asChild><Link to="/approvals">{tx('Open approvals')}</Link></Button>
                <Button variant="outline" onClick={async () => {
                  try { await act.mutateAsync({ id: i.id, action: 'sync' }); } catch (e) { notify.error(apiMessage(e, tx('Still waiting for approval'))); }
                }}><RefreshCw className="mr-1.5 h-4 w-4" /> {tx('Refresh approval')}</Button>
              </>
            )}
            {i.status === 'approved' && keeper && (
              <Button disabled={issue.isPending} onClick={async () => {
                try { await issue.mutateAsync(i.id); notify.success(tx('Explosives issued from the magazine')); } catch (e) { notify.error(apiMessage(e, tx('Could not issue'))); }
              }}><PackageCheck className="mr-1.5 h-4 w-4" /> {tx('Issue from magazine')}</Button>
            )}
            {i.status === 'issued' && keeper && <Button onClick={() => setDialog('reconcile')}><RotateCcw className="mr-1.5 h-4 w-4" /> {tx('Reconcile return')}</Button>}
            {(i.status === 'pending_approval' || i.status === 'approved') && has(PERMISSIONS.quarryBlasting.requestExplosive) && (
              <Button variant="outline" onClick={() => setDialog('cancel')}><XCircle className="mr-1.5 h-4 w-4" /> {tx('Cancel')}</Button>
            )}
          </>
        } />
      <Section title={tx('Request')}>
        <KeyValues items={[
          [tx('Blast'), <Link key="b" className="text-primary hover:underline" to={`/quarry/blasts/${i.blastId}`}>{i.blastCode}</Link>],
          [tx('Magazine'), `${i.magazineCode} · ${i.magazineName}`], [tx('Purpose'), i.purpose], [tx('Shotfirer'), i.shotfirerName],
          [tx('Shotfirer licence number'), i.shotfirerLicenceNo], [tx('Requested'), dateTime(i.requestedAt)],
          [tx('Approval'), i.approvalStatus], [tx('Issued'), i.issuedAt ? dateTime(i.issuedAt) : null], [tx('Reconciled'), i.reconciledAt ? dateTime(i.reconciledAt) : null],
          [tx('Issued value'), money(i.issuedValue)], [tx('Returned value'), money(i.returnedValue)], [tx('Cancel reason'), i.cancelReason],
        ]} />
      </Section>
      <Section title={tx('Lines')}>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead><tr className="text-left text-muted-foreground"><th className="py-1">{tx('Explosive')}</th><th>{tx('Kind')}</th><th className="text-right">{tx('Requested')}</th><th className="text-right">{tx('Issued')}</th><th className="text-right">{tx('Returned')}</th><th className="text-right">{tx('In magazine')}</th><th className="text-right">{tx('Value')}</th></tr></thead>
            <tbody>
              {i.lines.map((l) => (
                <tr key={l.id} className="border-t">
                  <td className="py-1.5">{l.productName}</td><td>{EXPLOSIVE_KIND_LABEL[l.kind] ?? l.kind}</td>
                  <td className="text-right tabular-nums">{qty(l.requestedQty, 3)}</td><td className="text-right tabular-nums">{qty(l.issuedQty, 3)}</td>
                  <td className="text-right tabular-nums">{qty(l.returnedQty, 3)}</td><td className="text-right tabular-nums">{qty(l.magazineStock, 3)}</td>
                  <td className="text-right tabular-nums">{money(Number(l.issuedValue) - Number(l.returnedValue))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>
      <Section title={tx('General ledger')}><JournalTable entries={i.journal} /></Section>
      <Section title={tx('Audit trail')}>
        <ul className="divide-y text-sm">
          {i.audit.map((a, n) => (
            <li key={n} className="flex justify-between gap-2 py-1.5">
              <span>{a.entity === 'ApprovalRequest' ? tx('Approval') : tx('Issue')} · {a.action}</span>
              <span className="text-muted-foreground">{a.actorName || a.actorEmail || '—'} · {dateTime(a.createdAt)}</span>
            </li>
          ))}
        </ul>
      </Section>
      {dialog === 'reconcile' && <ReconcileDialog issue={i} onClose={() => setDialog(null)} />}
      {dialog === 'cancel' && (
        <ReasonDialog title={tx('Cancel {{code}}', { code: i.code })} confirmLabel={tx('Cancel request')} busy={act.isPending}
          onConfirm={(reason) => act.mutateAsync({ id: i.id, action: 'cancel', body: { reason } })} onClose={() => setDialog(null)} />
      )}
    </div>
  );
}

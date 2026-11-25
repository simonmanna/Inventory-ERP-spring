import { useState } from 'react';
import { Ban, CheckCircle2, Plus, ShieldBan, ShieldCheck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { StatusPill } from '@/components/list/list-page';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { useAuthStore } from '@/stores/auth.store';
import type { PlmItemDetail } from '@/features/manufacturing/api-plm';
import {
  useGrantLifecycleException, useRevokeLifecycleException, type LifecycleException, type PolicyActivity,
} from '@/features/manufacturing/api-plm-app';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { EmptyState, Field, Panel } from './plm-ui';
import { ACTIVITY_LABEL, LIFECYCLE, day, selectClass } from './vocab';

/**
 * The product's lifecycle rules (ADR-S086 §6): what purchasing, production, sales, the till and replenishment may do
 * with it today, and the authorized exceptions that lift a block for a limited time.
 */
export function RulesTab({ item }: { item: PlmItemDetail }) {
  const canGrant = useAuthStore((s) => s.hasPermission)(PERMISSIONS.plm.exception);
  const [granting, setGranting] = useState<PolicyActivity | null>(null);
  const revoke = useRevokeLifecycleException();
  const policy = item.policy;
  if (!policy) return <EmptyState icon={ShieldBan} title={tx('This product is not under lifecycle management.')} compact />;
  const activities = Object.keys(policy) as PolicyActivity[];
  const firstBlocked = activities.find((a) => !policy[a].allowed) ?? activities[0];
  const exceptions = item.exceptions ?? [];

  const onRevoke = (x: LifecycleException) => {
    const reason = prompt(tx('Revoke the exception for {{a}}? Reason (optional):', { a: tx(ACTIVITY_LABEL[x.activity].label) }));
    if (reason === null) return;
    revoke.mutate({ id: x.id, reason: reason.trim() || undefined }, { onSuccess: () => notify.success(tx('Exception revoked')) });
  };

  return (
    <div className="space-y-4">
      <Panel
        title={tx('What is allowed today')}
        icon={ShieldCheck}
        actions={canGrant && (
          <Button size="sm" variant="outline" onClick={() => setGranting(firstBlocked)}><Plus className="mr-1 h-4 w-4" />{tx('Grant exception')}</Button>
        )}
      >
        <p className="mb-3 text-sm text-muted-foreground">
          {tx('From the organization’s lifecycle rules for a {{s}} product (PLM settings). Products already on a document are never re-checked; receipts against placed purchase orders, deliveries and sales replayed from an offline till are never refused.', { s: LIFECYCLE[item.lifecycleState]?.label ?? item.lifecycleState })}
        </p>
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Activity')}</TableHead>
                <TableHead>{tx('Rule')}</TableHead>
                <TableHead>{tx('Exception')}</TableHead>
                <TableHead>{tx('Today')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {activities.map((a) => {
                const p = policy[a];
                return (
                  <TableRow key={a}>
                    <TableCell>
                      <p className="font-medium">{tx(ACTIVITY_LABEL[a].label)}</p>
                      <p className="text-[11px] text-muted-foreground">{tx(ACTIVITY_LABEL[a].hint)}</p>
                    </TableCell>
                    <TableCell className="text-sm">{p.policy === 'allow' ? tx('Allowed') : tx('Blocked')}</TableCell>
                    <TableCell className="text-sm text-muted-foreground">
                      {p.exception ? tx('until {{d}}', { d: day(p.exception.validUntil) }) : '—'}
                    </TableCell>
                    <TableCell>
                      <span className={cn('inline-flex items-center gap-1 text-sm font-medium',
                        p.allowed ? 'text-emerald-700 dark:text-emerald-300' : 'text-rose-700 dark:text-rose-300')}>
                        {p.allowed ? <CheckCircle2 className="h-4 w-4" /> : <Ban className="h-4 w-4" />}
                        {p.allowed ? tx('Allowed') : tx('Blocked')}
                      </span>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      </Panel>

      <Panel title={tx('Exceptions')} icon={ShieldBan}>
        {exceptions.length === 0 ? (
          <p className="text-sm text-muted-foreground">{tx('No exception was ever granted for this product.')}</p>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Activity')}</TableHead>
                  <TableHead>{tx('Reason')}</TableHead>
                  <TableHead>{tx('Valid until')}</TableHead>
                  <TableHead>{tx('Status')}</TableHead>
                  <TableHead>{tx('Granted by')}</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {exceptions.map((x) => (
                  <TableRow key={x.id}>
                    <TableCell className="font-medium">{tx(ACTIVITY_LABEL[x.activity]?.label ?? x.activity)}</TableCell>
                    <TableCell className="max-w-[320px] text-sm">
                      <p className="whitespace-pre-wrap">{x.reason}</p>
                      {x.revokedAt && (
                        <p className="text-[11px] text-muted-foreground">
                          {tx('Revoked {{d}} by {{u}}', { d: day(x.revokedAt), u: x.revokedByName ?? '—' })}{x.revokeReason ? `: ${x.revokeReason}` : ''}
                        </p>
                      )}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-sm">{day(x.validUntil)}</TableCell>
                    <TableCell>
                      <StatusPill tone={x.status === 'active' ? 'success' : 'neutral'}>
                        {x.status === 'active' ? tx('in force') : x.status === 'expired' ? tx('expired') : tx('revoked')}
                      </StatusPill>
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-sm text-muted-foreground">{x.createdByName ?? '—'} · {day(x.createdAt)}</TableCell>
                    <TableCell className="text-right">
                      {canGrant && x.status === 'active' && (
                        <Button size="sm" variant="ghost" disabled={revoke.isPending} onClick={() => onRevoke(x)}>{tx('Revoke')}</Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Panel>

      {granting && <GrantDialog item={item} activity={granting} onClose={() => setGranting(null)} />}
    </div>
  );
}

function inDays(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() + n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function GrantDialog({ item, activity, onClose }: { item: PlmItemDetail; activity: PolicyActivity; onClose: () => void }) {
  const grant = useGrantLifecycleException();
  const [f, setF] = useState({ activity, validUntil: inDays(30), reason: '' });
  const activities = Object.keys(item.policy ?? {}) as PolicyActivity[];
  const submit = () => grant.mutate({ productId: item.productId, ...f, reason: f.reason.trim() }, {
    onSuccess: () => { notify.success(tx('Exception granted')); onClose(); },
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Grant a lifecycle exception')}</DialogTitle>
          <DialogDescription>
            {tx('Allows one activity for {{p}} despite its lifecycle state, until the day you choose (at most a year). It is recorded with your name and reason, and can be revoked at any time.', { p: item.product.code })}
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={tx('Activity')} required>
            <select className={selectClass} value={f.activity} onChange={(e) => setF({ ...f, activity: e.target.value as PolicyActivity })}>
              {activities.map((a) => (
                <option key={a} value={a}>{tx(ACTIVITY_LABEL[a].label)}{item.policy?.[a].allowed ? ` (${tx('already allowed')})` : ''}</option>
              ))}
            </select>
          </Field>
          <Field label={tx('Valid until (last day)')} required>
            <Input type="date" min={inDays(0)} max={inDays(366)} value={f.validUntil} onChange={(e) => setF({ ...f, validUntil: e.target.value })} />
          </Field>
          <Field label={tx('Reason')} required className="sm:col-span-2">
            <Textarea rows={3} maxLength={2000} value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })}
              placeholder={tx('e.g. Sell the remaining 40 units to the distributor before the write-off')} />
          </Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={grant.isPending || !f.reason.trim() || !f.validUntil}>{tx('Grant exception')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

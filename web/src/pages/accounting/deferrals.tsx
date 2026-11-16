import { useState } from 'react';
import { CalendarClock, Plus, Play } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader } from '@/components/list';
import { useAccounts } from '@/features/accounting/api';
import {
  useCancelDeferral,
  useCreateDeferral,
  useDeferral,
  useDeferralReport,
  useDeferrals,
  useReleaseDeferral,
  type DeferralSchedule,
} from '@/features/accounting/gl-automation-api';
import { money } from '@/lib/format';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const errorText = (e: any) => {
  const m = e?.response?.data?.message;
  return Array.isArray(m) ? m.join(', ') : m ?? tx('Error');
};
const today = () => new Date().toISOString().slice(0, 10);

/** Deferred revenue and prepaid expense schedules (ADR-S073), with the remaining-balance reconciliation. */
export default function DeferralsPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.deferral.manage);
  const schedules = useDeferrals();
  const { data: accounts } = useAccounts();
  const create = useCreateDeferral();
  const release = useReleaseDeferral();
  const cancel = useCancelDeferral();
  const [asOf, setAsOf] = useState(today());
  const report = useDeferralReport(asOf);
  const [selectedId, setSelectedId] = useState<string>();
  const selected = useDeferral(selectedId);

  const [open, setOpen] = useState(false);
  const [fromLine, setFromLine] = useState(false);
  const [form, setForm] = useState({ name: '', kind: 'revenue', sourceAccountId: '', targetAccountId: '', totalAmount: '', startDate: today(), months: '12', method: 'monthly', journalLineId: '' });
  const set = (k: keyof typeof form, v: string) => setForm((f) => ({ ...f, [k]: v }));

  const submit = async () => {
    try {
      const body: Record<string, unknown> = fromLine
        ? { journalLineId: form.journalLineId, name: form.name || undefined, sourceAccountId: form.sourceAccountId || undefined,
            targetAccountId: form.targetAccountId || undefined, amount: form.totalAmount ? Number(form.totalAmount) : undefined,
            startDate: form.startDate, months: Number(form.months), method: form.method }
        : { name: form.name, kind: form.kind, sourceAccountId: form.sourceAccountId || undefined, targetAccountId: form.targetAccountId,
            totalAmount: Number(form.totalAmount), startDate: form.startDate, months: Number(form.months), method: form.method };
      const s = await create.mutateAsync(body);
      notify.success(tx('Schedule created'));
      setOpen(false);
      setSelectedId(s.id);
    } catch (e) {
      notify.error(errorText(e));
    }
  };

  const act = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn();
      notify.success(ok);
    } catch (e) {
      notify.error(errorText(e));
    }
  };

  const accountSelect = (key: 'sourceAccountId' | 'targetAccountId', placeholder: string) => (
    <Select value={form[key]} onValueChange={(v) => set(key, v)}>
      <SelectTrigger><SelectValue placeholder={placeholder} /></SelectTrigger>
      <SelectContent>
        {(accounts?.data ?? []).map((a) => <SelectItem key={a.id} value={a.id}>{a.code} {a.name}</SelectItem>)}
      </SelectContent>
    </Select>
  );

  const columns: Column<DeferralSchedule>[] = [
    { key: 'name', header: tx('Name'), render: (s) => <button type="button" className="text-left font-medium hover:underline" onClick={() => setSelectedId(s.id)}>{s.name}</button> },
    { key: 'kind', header: tx('Type'), render: (s) => (s.kind === 'revenue' ? tx('Deferred revenue') : tx('Prepaid expense')) },
    { key: 'accounts', header: tx('Accounts'), render: (s) => `${s.sourceAccountCode ?? ''} → ${s.targetAccountCode ?? ''}` },
    { key: 'total', header: tx('Total'), className: 'text-right', render: (s) => money(s.totalAmount) },
    { key: 'remaining', header: tx('Remaining'), className: 'text-right', render: (s) => money(s.remainingAmount) },
    { key: 'period', header: tx('Period'), render: (s) => tx('{{m}} months from {{d}} ({{method}})', { m: s.months, d: s.startDate, method: s.method }) },
    { key: 'status', header: tx('Status'), render: (s) => <Badge variant={s.status === 'active' ? 'default' : 'secondary'}>{s.status}</Badge> },
    {
      key: 'actions',
      header: '',
      render: (s) => canManage && s.status === 'active' && (
        <div className="flex gap-1">
          <Button variant="ghost" size="sm" onClick={() => void act(() => release.mutateAsync({ id: s.id }), tx('Due slices released'))}>{tx('Release due')}</Button>
          <Button variant="ghost" size="sm" onClick={() => void act(() => cancel.mutateAsync({ id: s.id }), tx('Schedule cancelled'))}>{tx('Cancel')}</Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('Deferrals')}
        description={tx('Release deferred revenue and prepaid expenses to profit and loss month by month.')}
        icon={CalendarClock}
        actions={canManage ? (
          <>
            <Button variant="outline" onClick={() => void act(() => release.mutateAsync({}), tx('Due slices released'))}><Play className="mr-2 h-4 w-4" />{tx('Release all due')}</Button>
            <Button onClick={() => { setFromLine(false); setOpen(true); }}><Plus className="mr-2 h-4 w-4" />{tx('New schedule')}</Button>
            <Button variant="outline" onClick={() => { setFromLine(true); setOpen(true); }}>{tx('From a journal line')}</Button>
          </>
        ) : undefined}
      />
      <ListCard>
        <DataTable columns={columns} data={schedules.data ?? []} loading={schedules.isLoading} getRowId={(s) => s.id} emptyMessage={tx('No deferral schedules yet.')} />
      </ListCard>

      {selected.data && (
        <Card>
          <CardHeader><CardTitle className="text-base">{selected.data.name}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            {selected.data.releases?.map((r) => (
              <div key={r.id} className="flex justify-between gap-2">
                <span>{r.periodIndex}. {r.periodStart} – {r.periodEnd}</span>
                <span className="text-muted-foreground">{money(r.amount)} · {r.entryNumber ?? r.status}</span>
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle className="text-base">{tx('Remaining balance vs ledger')}</CardTitle>
          <Input type="date" className="h-9 w-44" aria-label={tx('As of')} value={asOf} onChange={(e) => setAsOf(e.target.value)} />
        </CardHeader>
        <CardContent className="space-y-1 text-sm">
          {(report.data?.accounts ?? []).length === 0 && <p className="text-muted-foreground">{tx('Nothing deferred as of this date.')}</p>}
          {report.data?.accounts.map((a) => (
            <div key={a.accountId} className="flex justify-between gap-2">
              <span>{a.accountCode} {a.accountName}</span>
              <span className={Number(a.difference) === 0 ? 'text-muted-foreground' : 'text-destructive'}>
                {tx('Schedules {{s}} · Ledger {{g}} · Difference {{d}}', { s: money(a.scheduleRemaining), g: money(a.glBalance), d: money(a.difference) })}
              </span>
            </div>
          ))}
        </CardContent>
      </Card>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-lg">
          <DialogHeader><DialogTitle>{fromLine ? tx('Defer a posted journal line') : tx('New deferral schedule')}</DialogTitle></DialogHeader>
          <div className="grid grid-cols-2 gap-3">
            {fromLine && <div className="col-span-2"><Label>{tx('Journal line ID')}</Label><Input value={form.journalLineId} onChange={(e) => set('journalLineId', e.target.value)} /></div>}
            <div className="col-span-2"><Label>{tx('Name')}</Label><Input value={form.name} onChange={(e) => set('name', e.target.value)} /></div>
            {!fromLine && (
              <div>
                <Label>{tx('Type')}</Label>
                <Select value={form.kind} onValueChange={(v) => set('kind', v)}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="revenue">{tx('Deferred revenue')}</SelectItem>
                    <SelectItem value="expense">{tx('Prepaid expense')}</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            )}
            <div><Label>{fromLine ? tx('Amount (default: whole line)') : tx('Total')}</Label><Input type="number" min="0" value={form.totalAmount} onChange={(e) => set('totalAmount', e.target.value)} /></div>
            <div className="col-span-2"><Label>{tx('Deferral account (default from mappings)')}</Label>{accountSelect('sourceAccountId', tx('Deferred revenue / prepaid'))}</div>
            <div className="col-span-2"><Label>{fromLine ? tx('Revenue / expense account (balance-sheet lines only)') : tx('Revenue / expense account')}</Label>{accountSelect('targetAccountId', tx('Profit and loss account'))}</div>
            <div><Label>{tx('Start')}</Label><Input type="date" value={form.startDate} onChange={(e) => set('startDate', e.target.value)} /></div>
            <div><Label>{tx('Months')}</Label><Input type="number" min="1" value={form.months} onChange={(e) => set('months', e.target.value)} /></div>
            <div className="col-span-2">
              <Label>{tx('Method')}</Label>
              <Select value={form.method} onValueChange={(v) => set('method', v)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="monthly">{tx('Straight line, equal monthly slices')}</SelectItem>
                  <SelectItem value="daily">{tx('Straight line by days in each month')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <DialogFooter><Button onClick={() => void submit()} disabled={create.isPending}>{tx('Create')}</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

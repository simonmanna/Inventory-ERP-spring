import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';
import { money, date } from '@/lib/format';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { useUsers } from '@/features/staff/api';
import {
  useFollowupLevels, usePartnerFollowup, useSavePartnerFollowup, type FollowupInput, type FollowupStatus,
  type PartnerFollowup,
} from '@/features/partners/api';
import { FieldRow, FormSection, ReadValue, selectCls } from './profile-forms';

const STATUS: Record<FollowupStatus, { label: string; cls: string }> = {
  in_need_of_action: { label: tx('In need of action'), cls: 'border-amber-200 bg-amber-50 text-amber-800' },
  with_overdue_invoices: { label: tx('With overdue invoices'), cls: 'border-rose-200 bg-rose-50 text-rose-700' },
  no_action_needed: { label: tx('No action needed'), cls: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
};

const CHANNEL: Record<string, string> = {
  email: tx('Send an e-mail reminder'),
  letter: tx('Send a letter'),
  none: tx('Call the customer on the phone'),
};

interface FormState {
  followupLevelId: string;
  followupNextReminder: string;
  followupResponsibleId: string;
  followupExcluded: boolean;
  followupNote: string;
  promisedDate: string;
  promiseNote: string;
}

const day = (v?: string | null) => (v ? v.slice(0, 10) : '');

function toForm(f: PartnerFollowup): FormState {
  return {
    followupLevelId: f.followupLevelId ?? '',
    followupNextReminder: day(f.followupNextReminder),
    followupResponsibleId: f.followupResponsibleId ?? '',
    followupExcluded: f.followupExcluded,
    followupNote: f.followupNote ?? '',
    promisedDate: day(f.promise?.promisedDate),
    promiseNote: f.promise?.notes ?? '',
  };
}

/** Follow-up card + open balance for the "Payments and Follow-ups" tab. */
export function FollowupPanel({ partnerId, canManage }: { partnerId: string; canManage: boolean }) {
  const navigate = useNavigate();
  const { data, isLoading, isError } = usePartnerFollowup(partnerId);
  const { data: levels = [] } = useFollowupLevels();
  const { data: users } = useUsers({ page: 1, pageSize: 100 });
  const save = useSavePartnerFollowup(partnerId);
  const [form, setForm] = useState<FormState | null>(null);

  useEffect(() => {
    if (data) setForm(toForm(data));
  }, [data]);

  const original = useMemo(() => (data ? toForm(data) : null), [data]);
  const dirty = !!form && !!original && JSON.stringify(form) !== JSON.stringify(original);

  if (isLoading || (!form && !isError)) return <Skeleton className="h-72 w-full rounded-xl" />;
  if (isError || !data || !form) {
    return (
      <div className="rounded-xl border bg-card p-6 text-sm text-muted-foreground">
        {tx('Follow-up data is not available (requires the collections permission).')}
      </div>
    );
  }

  const set = (patch: Partial<FormState>) => setForm({ ...form, ...patch });
  const status = STATUS[data.status];
  const level = form.followupLevelId ? levels.find((l) => l.id === form.followupLevelId) ?? data.level : data.suggestedLevel;
  const disabled = !canManage;

  const onSave = async () => {
    if (!original) return;
    const body: FollowupInput = {};
    (Object.keys(form) as (keyof FormState)[]).forEach((k) => {
      if (form[k] !== original[k]) {
        const v = form[k];
        (body as Record<string, unknown>)[k] = typeof v === 'string' ? (v.trim() ? v.trim() : null) : v;
      }
    });
    try {
      await save.mutateAsync(body);
      notify.success(tx('Follow-up saved'));
    } catch (e: any) {
      notify.error(tx('Could not save follow-up'), e?.response?.data?.message ?? e?.message);
    }
  };

  const total = data.lines.reduce((s, l) => s + Number(l.balance), 0);
  const userList = users?.data ?? [];
  const today = new Date().toISOString().slice(0, 10);

  return (
    <div className="space-y-5">
      <div className="rounded-xl border bg-card p-5">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <h2 className="text-base font-semibold">{tx('Follow-up')}</h2>
            <span className={cn('inline-flex rounded-full border px-2.5 py-0.5 text-xs font-medium', status.cls)}>{status.label}</span>
          </div>
          {canManage && (
            <div className={cn('flex gap-2 transition-opacity', dirty ? 'opacity-100' : 'pointer-events-none opacity-0')}>
              <Button size="sm" variant="ghost" onClick={() => original && setForm(original)}>{tx('Discard')}</Button>
              <Button size="sm" onClick={onSave} disabled={save.isPending}>{tx('Save')}</Button>
            </div>
          )}
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-x-10 gap-y-5">
          <FormSection title={tx('Follow-up')}>
            <FieldRow label={tx('Follow-up Plan')} htmlFor="fu-plan" hint={tx('Reminder level applied to this customer; automatic picks it by days overdue')}>
              <select id="fu-plan" disabled={disabled} className={selectCls} value={form.followupLevelId} onChange={(e) => set({ followupLevelId: e.target.value })}>
                <option value="">{tx('Default plan')}</option>
                {levels.filter((l) => l.isActive || l.id === form.followupLevelId).map((l) => (
                  <option key={l.id} value={l.id}>{`${l.sequence}. ${l.name} (${l.daysOverdue} ${tx('days')})`}</option>
                ))}
              </select>
            </FieldRow>
            <FieldRow label={tx('Reminder Due')} hint={tx('Action of the current follow-up level')}>
              <ReadValue>{level ? `${level.name} — ${CHANNEL[level.channel] ?? level.channel}` : null}</ReadValue>
            </FieldRow>
            <FieldRow label={tx('Next Reminder')} htmlFor="fu-next">
              <Input id="fu-next" type="date" disabled={disabled} value={form.followupNextReminder} onChange={(e) => set({ followupNextReminder: e.target.value })} />
            </FieldRow>
            <FieldRow label={tx('Follow-up Responsible')} htmlFor="fu-resp" hint={tx('In charge of the collection')}>
              <select id="fu-resp" disabled={disabled} className={selectCls} value={form.followupResponsibleId} onChange={(e) => set({ followupResponsibleId: e.target.value })}>
                <option value="">{tx('In charge of the collection')}</option>
                {userList.map((u) => <option key={u.id} value={u.id}>{[u.firstName, u.lastName].filter(Boolean).join(' ') || u.email}</option>)}
              </select>
            </FieldRow>
            <FieldRow label={tx('Note')} htmlFor="fu-note">
              <Input id="fu-note" disabled={disabled} value={form.followupNote} onChange={(e) => set({ followupNote: e.target.value })} placeholder={tx('Internal follow-up note')} />
            </FieldRow>
          </FormSection>

          <div className="space-y-5">
            <FormSection title={tx('Amounts')}>
              <FieldRow label={tx('Amount Due')}><ReadValue><span className="font-semibold tabular-nums">{money(data.amountDue)}</span></ReadValue></FieldRow>
              <FieldRow label={tx('Amount Overdue')}>
                <ReadValue><span className={cn('font-semibold tabular-nums', Number(data.amountOverdue) > 0 && 'text-rose-600')}>{money(data.amountOverdue)}</span></ReadValue>
              </FieldRow>
              <FieldRow label={tx('Oldest Due Date')}>
                <ReadValue>{data.oldestDueDate ? <span>{date(data.oldestDueDate)} <span className="text-muted-foreground">· {data.daysOverdue} {tx('days overdue')}</span></span> : null}</ReadValue>
              </FieldRow>
            </FormSection>

            <FormSection title={tx('Promise to Pay')}>
              <FieldRow label={tx('Promised Payment Date')} htmlFor="fu-pdate" hint={tx('Reminders are paused until this date')}>
                <Input id="fu-pdate" type="date" min={today} disabled={disabled} value={form.promisedDate} onChange={(e) => set({ promisedDate: e.target.value })} />
              </FieldRow>
              <FieldRow label={tx('Note')} htmlFor="fu-pnote">
                <Textarea id="fu-pnote" rows={2} disabled={disabled} value={form.promiseNote} onChange={(e) => set({ promiseNote: e.target.value })} placeholder={tx('e.g. Will pay half before the 15th, the rest next month')} />
              </FieldRow>
            </FormSection>

            <FormSection title={tx('Exclusion')}>
              <FieldRow label={tx('Excluded from Follow-ups')} htmlFor="fu-excl" hint={tx('Dunning runs skip this customer')}>
                <label className="flex h-9 items-center">
                  <input id="fu-excl" type="checkbox" disabled={disabled} className="h-4 w-4 rounded border-input accent-primary" checked={form.followupExcluded} onChange={(e) => set({ followupExcluded: e.target.checked })} />
                </label>
              </FieldRow>
            </FormSection>
          </div>
        </div>
      </div>

      <div className="rounded-xl border bg-card">
        <div className="px-5 py-3 border-b">
          <h3 className="text-sm font-semibold">{tx('Balance Amount')}</h3>
        </div>
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Date')}</TableHead>
                <TableHead>{tx('Company')}</TableHead>
                <TableHead>{tx('Number')}</TableHead>
                <TableHead>{tx('Due Date')}</TableHead>
                <TableHead className="text-right">{tx('Balance')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.lines.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">{tx('Nothing open — this customer is fully paid.')}</TableCell>
                </TableRow>
              ) : data.lines.map((l) => {
                const overdue = l.dueDate && day(l.dueDate) < today && Number(l.balance) > 0;
                return (
                  <TableRow key={l.id} className="cursor-pointer hover:bg-muted/50" onClick={() => navigate(`/invoices/${l.id}`)}>
                    <TableCell className="text-sm">{date(l.issueDate)}</TableCell>
                    <TableCell className="text-sm text-muted-foreground">{l.company ?? '—'}</TableCell>
                    <TableCell className="font-mono text-xs font-medium">{l.documentNumber}</TableCell>
                    <TableCell className={cn('text-sm', overdue && 'text-rose-600 font-medium')}>{l.dueDate ? date(l.dueDate) : '—'}</TableCell>
                    <TableCell className={cn('text-right tabular-nums', Number(l.balance) < 0 && 'text-muted-foreground')}>{money(l.balance)}</TableCell>
                  </TableRow>
                );
              })}
              {data.lines.length > 0 && (
                <TableRow className="bg-muted/40 hover:bg-muted/40">
                  <TableCell colSpan={4} className="text-right text-sm font-medium">{tx('Total')}</TableCell>
                  <TableCell className="text-right font-semibold tabular-nums">{money(total)}</TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </div>
      </div>
    </div>
  );
}

import { useState } from 'react';
import { Pause, Play, Plus, Repeat, Trash2, Zap } from 'lucide-react';
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
  useDeleteRecurringTemplate,
  useRecurringAction,
  useRecurringRuns,
  useRecurringTemplates,
  useRunRecurringNow,
  useSaveRecurringTemplate,
  type Frequency,
  type RecurringTemplate,
} from '@/features/accounting/gl-automation-api';
import { money } from '@/lib/format';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const errorText = (e: any) => {
  const m = e?.response?.data?.message;
  return Array.isArray(m) ? m.join(', ') : m ?? tx('Error');
};

interface LineForm { accountId: string; debit: string; credit: string; description: string }
const blankLine = (): LineForm => ({ accountId: '', debit: '', credit: '', description: '' });

/** Recurring journal templates (ADR-S073): post or stage a draft on a schedule, with history. */
export default function RecurringEntriesPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.recurringEntry.manage);
  const templates = useRecurringTemplates();
  const { data: accounts } = useAccounts();
  const save = useSaveRecurringTemplate();
  const action = useRecurringAction();
  const runNow = useRunRecurringNow();
  const remove = useDeleteRecurringTemplate();
  const [selected, setSelected] = useState<RecurringTemplate | null>(null);
  const runs = useRecurringRuns(selected?.id);

  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<RecurringTemplate | null>(null);
  const [name, setName] = useState('');
  const [frequency, setFrequency] = useState<Frequency>('monthly');
  const [startDate, setStartDate] = useState(new Date().toISOString().slice(0, 10));
  const [endDate, setEndDate] = useState('');
  const [mode, setMode] = useState<'post' | 'draft'>('post');
  const [lines, setLines] = useState<LineForm[]>([blankLine(), blankLine()]);

  const debit = lines.reduce((s, l) => s + (Number(l.debit) || 0), 0);
  const credit = lines.reduce((s, l) => s + (Number(l.credit) || 0), 0);

  const startEdit = (t: RecurringTemplate | null) => {
    setEditing(t);
    setName(t?.name ?? '');
    setFrequency(t?.frequency ?? 'monthly');
    setStartDate(t?.startDate ?? new Date().toISOString().slice(0, 10));
    setEndDate(t?.endDate ?? '');
    setMode(t?.mode ?? 'post');
    setLines(t ? t.lines.map((l) => ({ accountId: l.accountId, debit: l.debit ? String(l.debit) : '', credit: l.credit ? String(l.credit) : '', description: l.description ?? '' })) : [blankLine(), blankLine()]);
    setOpen(true);
  };

  const submit = async () => {
    try {
      await save.mutateAsync({
        id: editing?.id,
        data: {
          name,
          frequency,
          startDate,
          endDate: endDate || undefined,
          mode,
          lines: lines.filter((l) => l.accountId).map((l) => ({
            accountId: l.accountId,
            debit: l.debit ? Number(l.debit) : null,
            credit: l.credit ? Number(l.credit) : null,
            description: l.description || null,
          })),
        },
      });
      notify.success(tx('Recurring entry saved'));
      setOpen(false);
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

  const columns: Column<RecurringTemplate>[] = [
    { key: 'name', header: tx('Name'), render: (t) => <button type="button" className="text-left font-medium hover:underline" onClick={() => setSelected(t)}>{t.name}</button> },
    { key: 'frequency', header: tx('Frequency'), render: (t) => `${t.intervalCount > 1 ? `${t.intervalCount} × ` : ''}${t.frequency}` },
    { key: 'mode', header: tx('Mode'), render: (t) => (t.mode === 'draft' ? tx('Draft for approval') : tx('Post')) },
    { key: 'amount', header: tx('Amount'), className: 'text-right', render: (t) => money(t.totalAmount) },
    { key: 'next', header: tx('Next run'), render: (t) => t.nextRunDate ?? '—' },
    { key: 'status', header: tx('Status'), render: (t) => <Badge variant={t.status === 'active' ? 'default' : 'secondary'}>{t.status}</Badge> },
    {
      key: 'actions',
      header: '',
      render: (t) => canManage && (
        <div className="flex gap-1">
          <Button variant="ghost" size="icon" title={tx('Run now')} aria-label={tx('Run now')}
            onClick={() => void act(() => runNow.mutateAsync({ id: t.id }), tx('Occurrence generated'))}><Zap className="h-4 w-4" /></Button>
          {t.status === 'active' && <Button variant="ghost" size="icon" title={tx('Pause')} aria-label={tx('Pause')}
            onClick={() => void act(() => action.mutateAsync({ id: t.id, action: 'pause' }), tx('Paused'))}><Pause className="h-4 w-4" /></Button>}
          {t.status === 'paused' && <Button variant="ghost" size="icon" title={tx('Resume')} aria-label={tx('Resume')}
            onClick={() => void act(() => action.mutateAsync({ id: t.id, action: 'resume' }), tx('Resumed'))}><Play className="h-4 w-4" /></Button>}
          {t.status !== 'ended' && <Button variant="ghost" size="sm" onClick={() => startEdit(t)}>{tx('Edit')}</Button>}
          <Button variant="ghost" size="icon" title={tx('Delete')} aria-label={tx('Delete')}
            onClick={() => void act(() => remove.mutateAsync(t.id), tx('Deleted'))}><Trash2 className="h-4 w-4" /></Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('Recurring Entries')}
        description={tx('Journal entries generated on a schedule: posted directly or staged as drafts for a second approver.')}
        icon={Repeat}
        actions={canManage ? <Button onClick={() => startEdit(null)}><Plus className="mr-2 h-4 w-4" />{tx('New recurring entry')}</Button> : undefined}
      />
      <ListCard>
        <DataTable columns={columns} data={templates.data ?? []} loading={templates.isLoading} getRowId={(t) => t.id}
          emptyMessage={tx('No recurring entries yet.')} />
      </ListCard>

      {selected && (
        <Card>
          <CardHeader><CardTitle className="text-base">{tx('History: {{n}}', { n: selected.name })}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            {(runs.data ?? []).length === 0 && <p className="text-muted-foreground">{tx('No occurrences yet.')}</p>}
            {runs.data?.map((r) => (
              <div key={r.id} className="flex justify-between gap-2">
                <span>{r.runDate} · {r.trigger}</span>
                <span className={r.status === 'failed' ? 'text-destructive' : 'text-muted-foreground'}>
                  {r.status === 'failed' ? r.error : `${r.entryNumber ?? tx('draft')} (${r.entryStatus ?? r.status})`}
                </span>
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-3xl">
          <DialogHeader><DialogTitle>{editing ? tx('Edit recurring entry') : tx('New recurring entry')}</DialogTitle></DialogHeader>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
            <div className="col-span-2"><Label>{tx('Name')}</Label><Input value={name} onChange={(e) => setName(e.target.value)} /></div>
            <div>
              <Label>{tx('Frequency')}</Label>
              <Select value={frequency} onValueChange={(v) => setFrequency(v as Frequency)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  {(['daily', 'weekly', 'monthly', 'quarterly', 'yearly'] as Frequency[]).map((f) => <SelectItem key={f} value={f}>{tx(f)}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div><Label>{tx('Start')}</Label><Input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} /></div>
            <div><Label>{tx('End (optional)')}</Label><Input type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} /></div>
            <div className="col-span-2">
              <Label>{tx('Mode')}</Label>
              <Select value={mode} onValueChange={(v) => setMode(v as 'post' | 'draft')}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="post">{tx('Post automatically')}</SelectItem>
                  <SelectItem value="draft">{tx('Create a draft for approval (maker-checker)')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <div className="space-y-2">
            {lines.map((l, i) => (
              <div key={i} className="grid grid-cols-12 gap-2">
                <div className="col-span-5">
                  <Select value={l.accountId} onValueChange={(v) => setLines((ls) => ls.map((x, j) => (j === i ? { ...x, accountId: v } : x)))}>
                    <SelectTrigger><SelectValue placeholder={tx('Account')} /></SelectTrigger>
                    <SelectContent>
                      {(accounts?.data ?? []).map((a) => <SelectItem key={a.id} value={a.id}>{a.code} {a.name}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </div>
                <Input className="col-span-3" placeholder={tx('Description')} value={l.description}
                  onChange={(e) => setLines((ls) => ls.map((x, j) => (j === i ? { ...x, description: e.target.value } : x)))} />
                <Input className="col-span-2" type="number" min="0" placeholder={tx('Debit')} value={l.debit}
                  onChange={(e) => setLines((ls) => ls.map((x, j) => (j === i ? { ...x, debit: e.target.value, credit: '' } : x)))} />
                <Input className="col-span-2" type="number" min="0" placeholder={tx('Credit')} value={l.credit}
                  onChange={(e) => setLines((ls) => ls.map((x, j) => (j === i ? { ...x, credit: e.target.value, debit: '' } : x)))} />
              </div>
            ))}
            <div className="flex items-center justify-between">
              <Button variant="outline" size="sm" onClick={() => setLines((ls) => [...ls, blankLine()])}><Plus className="mr-2 h-4 w-4" />{tx('Add line')}</Button>
              <span className={debit === credit ? 'text-sm text-muted-foreground' : 'text-sm text-destructive'}>
                {tx('Debit {{d}} / Credit {{c}}', { d: debit.toFixed(2), c: credit.toFixed(2) })}
              </span>
            </div>
          </div>
          <DialogFooter>
            <Button onClick={() => void submit()} disabled={save.isPending || !name || debit === 0 || debit !== credit}>{tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

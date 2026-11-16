import { useState } from 'react';
import { Plus, Trash2, Wand2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useAccounts } from '@/features/accounting/api';
import {
  useAllocateLine,
  useApplyBankRules,
  useBankRules,
  useDeleteBankRule,
  useOpenPayments,
  useSaveBankRule,
  type BankRule,
} from '@/features/accounting/gl-automation-api';

const errorText = (e: any) => {
  const m = e?.response?.data?.message;
  return Array.isArray(m) ? m.join(', ') : m ?? tx('Error');
};

interface RuleForm {
  name: string;
  priority: string;
  matchField: BankRule['matchField'];
  matchType: BankRule['matchType'];
  pattern: string;
  minAmount: string;
  maxAmount: string;
  direction: BankRule['direction'];
  action: BankRule['action'];
  counterAccountId: string;
  dateToleranceDays: string;
}

const emptyRule: RuleForm = {
  name: '',
  priority: '100',
  matchField: 'any',
  matchType: 'contains',
  pattern: '',
  minAmount: '',
  maxAmount: '',
  direction: 'any',
  action: 'post_entry',
  counterAccountId: '',
  dateToleranceDays: '3',
};

function Choice<T extends string>({ label, value, options, onChange }: { label: string; value: T; options: [T, string][]; onChange: (v: T) => void }) {
  return (
    <div>
      <Label>{label}</Label>
      <Select value={value} onValueChange={(v) => onChange(v as T)}>
        <SelectTrigger><SelectValue /></SelectTrigger>
        <SelectContent>
          {options.map(([v, l]) => <SelectItem key={v} value={v}>{l}</SelectItem>)}
        </SelectContent>
      </Select>
    </div>
  );
}

/** Matching rules of one bank account (ADR-S073): run after every import and on demand. */
export function BankRulesPanel({ bankAccountId, canUpdate }: { bankAccountId: string; canUpdate: boolean }) {
  const rules = useBankRules(bankAccountId);
  const { data: accounts } = useAccounts();
  const save = useSaveBankRule();
  const remove = useDeleteBankRule();
  const apply = useApplyBankRules();
  const [editing, setEditing] = useState<BankRule | null>(null);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<RuleForm>(emptyRule);
  const set = <K extends keyof RuleForm>(k: K, v: RuleForm[K]) => setForm((f) => ({ ...f, [k]: v }));

  const edit = (rule: BankRule | null) => {
    setEditing(rule);
    setForm(rule ? {
      name: rule.name,
      priority: String(rule.priority),
      matchField: rule.matchField,
      matchType: rule.matchType,
      pattern: rule.pattern ?? '',
      minAmount: rule.minAmount ?? '',
      maxAmount: rule.maxAmount ?? '',
      direction: rule.direction,
      action: rule.action,
      counterAccountId: rule.counterAccountId ?? '',
      dateToleranceDays: String(rule.dateToleranceDays),
    } : emptyRule);
    setOpen(true);
  };

  const submit = async () => {
    try {
      await save.mutateAsync({
        id: editing?.id,
        data: {
          bankAccountId,
          name: form.name,
          priority: Number(form.priority) as any,
          matchField: form.matchField,
          matchType: form.matchType,
          pattern: form.pattern || undefined,
          minAmount: (form.minAmount ? Number(form.minAmount) : undefined) as any,
          maxAmount: (form.maxAmount ? Number(form.maxAmount) : undefined) as any,
          direction: form.direction,
          action: form.action,
          counterAccountId: form.action === 'post_entry' ? form.counterAccountId || undefined : undefined,
          dateToleranceDays: Number(form.dateToleranceDays) as any,
        },
      });
      notify.success(tx('Rule saved'));
      setOpen(false);
    } catch (e) {
      notify.error(errorText(e));
    }
  };

  const run = async () => {
    try {
      const r = await apply.mutateAsync(bankAccountId);
      notify.success(tx('{{matched}} matched, {{posted}} posted, {{failed}} failed', {
        matched: r.matched, posted: r.posted, failed: r.failures.length,
      }));
    } catch (e) {
      notify.error(errorText(e));
    }
  };

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between gap-2">
        <CardTitle className="text-base">{tx('Matching rules')}</CardTitle>
        {canUpdate && (
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => void run()} disabled={apply.isPending || !rules.data?.length}>
              <Wand2 className="mr-2 h-4 w-4" />{tx('Apply rules')}
            </Button>
            <Button size="sm" onClick={() => edit(null)}><Plus className="mr-2 h-4 w-4" />{tx('New rule')}</Button>
          </div>
        )}
      </CardHeader>
      <CardContent className="space-y-2 text-sm">
        {(rules.data ?? []).length === 0 && (
          <p className="text-muted-foreground">{tx('No rules. Rules match bank charges, interest and recurring receipts automatically.')}</p>
        )}
        {rules.data?.map((r) => (
          <div key={r.id} className="flex items-center justify-between gap-2 border-b pb-2 last:border-0">
            <button type="button" className="text-left" onClick={() => canUpdate && edit(r)}>
              <span className="font-medium">{r.name}</span>{' '}
              {!r.isActive && <Badge variant="outline">{tx('inactive')}</Badge>}
              <p className="text-xs text-muted-foreground">
                {r.matchType} “{r.pattern ?? '*'}” · {r.direction}
                {r.minAmount || r.maxAmount ? ` · ${r.minAmount ?? '0'}–${r.maxAmount ?? '∞'}` : ''} ·{' '}
                {r.action === 'post_entry' ? tx('post to {{a}}', { a: r.counterAccountCode ?? '' }) : tx('match a payment')}
              </p>
            </button>
            {canUpdate && (
              <Button variant="ghost" size="icon" aria-label={tx('Delete rule')} onClick={() => void remove.mutateAsync(r.id).catch((e) => notify.error(errorText(e)))}>
                <Trash2 className="h-4 w-4" />
              </Button>
            )}
          </div>
        ))}
      </CardContent>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-lg">
          <DialogHeader><DialogTitle>{editing ? tx('Edit rule') : tx('New rule')}</DialogTitle></DialogHeader>
          <div className="grid grid-cols-2 gap-3">
            <div className="col-span-2"><Label>{tx('Name')}</Label><Input value={form.name} onChange={(e) => set('name', e.target.value)} /></div>
            <Choice label={tx('Match on')} value={form.matchField} onChange={(v) => set('matchField', v)}
              options={[['any', tx('Description or reference')], ['description', tx('Description')], ['reference', tx('Reference')]]} />
            <Choice label={tx('Match type')} value={form.matchType} onChange={(v) => set('matchType', v)}
              options={[['contains', tx('Contains')], ['regex', tx('Regular expression')]]} />
            <div className="col-span-2"><Label>{tx('Text or pattern')}</Label><Input value={form.pattern} onChange={(e) => set('pattern', e.target.value)} /></div>
            <div><Label>{tx('Min amount')}</Label><Input type="number" min="0" value={form.minAmount} onChange={(e) => set('minAmount', e.target.value)} /></div>
            <div><Label>{tx('Max amount')}</Label><Input type="number" min="0" value={form.maxAmount} onChange={(e) => set('maxAmount', e.target.value)} /></div>
            <Choice label={tx('Direction')} value={form.direction} onChange={(v) => set('direction', v)}
              options={[['any', tx('Any')], ['inbound', tx('Money in')], ['outbound', tx('Money out')]]} />
            <div><Label>{tx('Priority')}</Label><Input type="number" min="0" value={form.priority} onChange={(e) => set('priority', e.target.value)} /></div>
            <Choice label={tx('Action')} value={form.action} onChange={(v) => set('action', v)}
              options={[['post_entry', tx('Post to an account (charges, interest)')], ['match_payment', tx('Match a payment')]]} />
            {form.action === 'post_entry' ? (
              <div>
                <Label>{tx('Account')}</Label>
                <Select value={form.counterAccountId} onValueChange={(v) => set('counterAccountId', v)}>
                  <SelectTrigger><SelectValue placeholder={tx('Select account')} /></SelectTrigger>
                  <SelectContent>
                    {(accounts?.data ?? []).map((a) => <SelectItem key={a.id} value={a.id}>{a.code} {a.name}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
            ) : (
              <div><Label>{tx('Date tolerance (days)')}</Label><Input type="number" min="0" value={form.dateToleranceDays} onChange={(e) => set('dateToleranceDays', e.target.value)} /></div>
            )}
          </div>
          <DialogFooter><Button onClick={() => void submit()} disabled={save.isPending || !form.name}>{tx('Save')}</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  );
}

/** Allocate one statement line to one or several payments, partially if needed (ADR-S073). */
export function AllocateLineDialog({ line, onClose }: { line: { id: string; amount: string; description: string } | null; onClose: () => void }) {
  const payments = useOpenPayments(line?.id);
  const allocate = useAllocateLine();
  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const chosen = Object.entries(amounts).filter(([, v]) => v !== '' && Number(v) > 0);
  const total = chosen.reduce((s, [, v]) => s + Number(v), 0);

  const submit = async () => {
    if (!line) return;
    try {
      const r = await allocate.mutateAsync({ lineId: line.id, allocations: chosen.map(([paymentId, v]) => ({ paymentId, amount: Number(v) })) });
      notify.success(r.status === 'matched' ? tx('Line matched') : tx('Line partly matched'));
      setAmounts({});
      onClose();
    } catch (e) {
      notify.error(errorText(e));
    }
  };

  return (
    <Dialog open={!!line} onOpenChange={(o) => { if (!o) { setAmounts({}); onClose(); } }}>
      <DialogContent className="max-w-xl">
        <DialogHeader><DialogTitle>{tx('Match to several payments')}</DialogTitle></DialogHeader>
        <p className="text-sm text-muted-foreground">{line?.description} · {line?.amount}</p>
        <div className="max-h-80 space-y-2 overflow-y-auto text-sm">
          {(payments.data ?? []).length === 0 && <p className="text-muted-foreground">{tx('No open payments in this direction.')}</p>}
          {payments.data?.map((p) => (
            <div key={p.id} className="flex items-center justify-between gap-2">
              <div>
                <span className="font-medium">{p.paymentNumber}</span>{' '}
                <span className="text-muted-foreground">{p.partnerName ?? ''} · {tx('open {{a}} of {{b}}', { a: p.openAmount, b: p.amount })}</span>
              </div>
              <Input type="number" min="0" className="h-8 w-28" aria-label={tx('Amount')} placeholder="0"
                value={amounts[p.id] ?? ''} onChange={(e) => setAmounts((m) => ({ ...m, [p.id]: e.target.value }))}
                onFocus={() => { if (!amounts[p.id]) setAmounts((m) => ({ ...m, [p.id]: p.openAmount })); }} />
            </div>
          ))}
        </div>
        <DialogFooter className="items-center gap-2">
          <span className="text-sm text-muted-foreground">{tx('Allocated {{t}}', { t: total.toFixed(2) })}</span>
          <Button onClick={() => void submit()} disabled={allocate.isPending || chosen.length === 0}>{tx('Allocate')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

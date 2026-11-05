import { useEffect, useState } from 'react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { OptionalSelect } from '@/pages/fleet/fleet-ui';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import {
  crmApi, useCrmLookups, useCrmMutation, usePartnerOptions, type DealKind, type Opportunity, type PlannedType,
} from '@/features/crm/suite-api';
import { Field, Stars, TYPE_ICON, typeLabel } from './crm-ui';
import { cn } from '@/lib/utils';

/** Customer dropdown with type-ahead search (partners search), styled like the order-line product picker. */
export function PartnerPicker({ value, label, onPick, placeholder, className, pinned }: {
  value: string; label?: string | null; onPick: (id: string, name: string) => void; placeholder?: string; className?: string;
  /** Options always listed first (e.g. the branch walk-in customer), whatever the search. */
  pinned?: Array<{ id: string; name: string }>;
}) {
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState(false);
  const { data, isFetching } = usePartnerOptions(search, open);
  const pinnedIds = new Set((pinned ?? []).map((p) => p.id));
  const options = [
    ...(pinned ?? []).map((p) => ({ value: p.id, label: p.name, code: undefined as string | undefined, hint: undefined as string | undefined })),
    ...(data ?? []).filter((p) => !pinnedIds.has(p.id)).map((p) => ({ value: p.id, label: p.name, code: p.code ?? undefined, hint: p.email ?? undefined })),
  ];
  return (
    <SearchableSelect
      value={value}
      selectedLabel={label}
      onValueChange={(v) => onPick(v, options.find((o) => o.value === v)?.label ?? '')}
      options={options}
      onQueryChange={setSearch}
      onOpenChange={setOpen}
      loading={isFetching}
      placeholder={placeholder ?? tx('Select a customer…')}
      searchPlaceholder={tx('Search customers…')}
      clearLabel={tx('Clear')}
      className={className}
    />
  );
}

const toDate = (v?: string | null) => (v ? v.slice(0, 10) : '');

/** Create or edit a lead / opportunity. `defaults` pre-fills (e.g. stageId from a kanban column). */
export function OpportunityDialog({ open, onOpenChange, kind, initial, defaults, onSaved }: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  kind: DealKind;
  initial?: Opportunity | null;
  defaults?: Partial<Record<string, string>>;
  onSaved?: (o: Opportunity) => void;
}) {
  const { data: lk } = useCrmLookups();
  const [f, setF] = useState<Record<string, string>>({});
  const [priority, setPriority] = useState(0);
  const [tagIds, setTagIds] = useState<string[]>([]);
  const [partnerName, setPartnerName] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setF({
      name: initial?.name ?? '',
      partnerId: initial?.partnerId ?? defaults?.partnerId ?? '',
      contactName: initial?.contactName ?? '',
      companyName: initial?.companyName ?? '',
      email: initial?.email ?? '',
      phone: initial?.phone ?? '',
      amount: initial ? String(initial.amount ?? '') : '',
      expectedClose: toDate(initial?.expectedClose),
      ownerId: initial?.ownerId ?? '',
      teamId: initial?.teamId ?? '',
      sourceId: initial?.sourceId ?? '',
      stageId: initial?.stageId ?? defaults?.stageId ?? '',
      city: initial?.city ?? '',
      country: initial?.country ?? '',
      notes: initial?.notes ?? '',
      probability: initial?.probabilityOverride != null ? String(initial.probabilityOverride) : '',
    });
    setPriority(initial?.priority ?? 0);
    setTagIds(initial?.tagIds ?? []);
    setPartnerName(initial?.partnerName ?? defaults?.partnerName ?? null);
  }, [open, initial, defaults?.stageId, defaults?.partnerId, defaults?.partnerName]);

  const set = (k: string) => (e: { target: { value: string } }) => setF((s) => ({ ...s, [k]: e.target.value }));
  const save = useCrmMutation(async () => {
    const body: Record<string, unknown> = {
      name: f.name,
      partnerId: f.partnerId || null,
      contactName: f.contactName,
      companyName: f.companyName,
      email: f.email,
      phone: f.phone,
      city: f.city,
      country: f.country,
      notes: f.notes,
      amount: f.amount === '' ? 0 : Number(f.amount),
      expectedClose: f.expectedClose || null,
      teamId: f.teamId || null,
      sourceId: f.sourceId || null,
      priority,
      tagIds,
    };
    if (f.ownerId || initial) body.ownerId = f.ownerId || null;
    if (kind === 'opportunity' && f.stageId) body.stageId = f.stageId;
    if (initial) body.probability = f.probability === '' ? null : Number(f.probability);
    if (initial) return crmApi.update({ id: initial.id, ...body });
    return kind === 'lead' ? crmApi.createLead(body) : crmApi.createOpportunity(body);
  });

  const submit = () => {
    if (!f.name?.trim()) return toast.error(tx('Give it a name'));
    save.mutate(undefined, {
      onSuccess: (o) => { toast.success(initial ? tx('Saved') : tx('Created')); onOpenChange(false); onSaved?.(o); },
      onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
    });
  };

  const stages = (lk?.stages ?? []).filter((s) => s.isActive);
  const opt = (rows: Array<{ id: string; name: string }>) => rows.map((r) => ({ value: r.id, label: r.name }));

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[92vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {initial ? tx('Edit') + ' — ' + initial.name : kind === 'lead' ? tx('New lead') : tx('New opportunity')}
          </DialogTitle>
        </DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={kind === 'lead' ? tx('Lead') : tx('Opportunity')} className="sm:col-span-2">
            <Input autoFocus value={f.name ?? ''} onChange={set('name')} placeholder={tx('e.g. 150 trophies for sports day')} />
          </Field>
          <Field label={tx('Customer')} className="sm:col-span-2" hint={kind === 'lead' ? tx('Optional for a lead — fill company / contact instead.') : undefined}>
            <PartnerPicker value={f.partnerId ?? ''} label={partnerName} onPick={(id, name) => { setF((s) => ({ ...s, partnerId: id })); setPartnerName(name || null); }} />
          </Field>
          <Field label={tx('Company name')}><Input value={f.companyName ?? ''} onChange={set('companyName')} /></Field>
          <Field label={tx('Contact name')}><Input value={f.contactName ?? ''} onChange={set('contactName')} /></Field>
          <Field label={tx('Email')}><Input type="email" value={f.email ?? ''} onChange={set('email')} /></Field>
          <Field label={tx('Phone')}><Input value={f.phone ?? ''} onChange={set('phone')} /></Field>
          <Field label={tx('Expected revenue')}><Input type="number" min={0} value={f.amount ?? ''} onChange={set('amount')} /></Field>
          <Field label={tx('Expected closing')}><Input type="date" value={f.expectedClose ?? ''} onChange={set('expectedClose')} /></Field>
          {kind === 'opportunity' && !initial && (
            <Field label={tx('Stage')}>
              <OptionalSelect value={f.stageId ?? ''} onChange={(v) => setF((s) => ({ ...s, stageId: v }))} options={opt(stages)} placeholder={tx('First stage')} noneLabel={tx('First stage')} />
            </Field>
          )}
          {initial && (
            <Field label={tx('Probability (%)')} hint={tx('Leave empty to use the stage probability')}>
              <Input type="number" min={0} max={100} value={f.probability ?? ''} onChange={set('probability')} />
            </Field>
          )}
          <Field label={tx('Salesperson')}>
            <OptionalSelect value={f.ownerId ?? ''} onChange={(v) => setF((s) => ({ ...s, ownerId: v }))} options={(lk?.users ?? []).map((u) => ({ value: u.id, label: u.name }))} placeholder={initial ? tx('Unassigned') : tx('Automatic / me')} noneLabel={initial ? tx('Unassigned') : tx('Automatic / me')} />
          </Field>
          <Field label={tx('Sales team')}>
            <OptionalSelect value={f.teamId ?? ''} onChange={(v) => setF((s) => ({ ...s, teamId: v }))} options={opt(lk?.teams ?? [])} placeholder={tx('None')} noneLabel={tx('None')} />
          </Field>
          <Field label={tx('Source')}>
            <OptionalSelect value={f.sourceId ?? ''} onChange={(v) => setF((s) => ({ ...s, sourceId: v }))} options={opt((lk?.sources ?? []).filter((s) => s.isActive))} placeholder={tx('Unknown')} noneLabel={tx('Unknown')} />
          </Field>
          <Field label={tx('Priority')}><div className="flex h-10 items-center"><Stars value={priority} onChange={setPriority} size="md" /></div></Field>
          <Field label={tx('City')}><Input value={f.city ?? ''} onChange={set('city')} /></Field>
          <Field label={tx('Country')}><Input value={f.country ?? ''} onChange={set('country')} /></Field>
          <Field label={tx('Tags')} className="sm:col-span-2">
            <div className="flex flex-wrap gap-1.5">
              {(lk?.tags ?? []).map((t) => {
                const on = tagIds.includes(t.id);
                return (
                  <button key={t.id} type="button" onClick={() => setTagIds((s) => on ? s.filter((x) => x !== t.id) : [...s, t.id])}
                    className={cn('rounded-full border px-2.5 py-0.5 text-xs', on ? 'border-primary bg-primary/10 font-medium text-primary' : 'text-muted-foreground hover:bg-muted')}>
                    {t.name}
                  </button>
                );
              })}
              {(lk?.tags ?? []).length === 0 && <span className="text-xs text-muted-foreground">{tx('No tags yet — add them in CRM configuration.')}</span>}
            </div>
          </Field>
          <Field label={tx('Internal notes')} className="sm:col-span-2"><Textarea rows={3} value={f.notes ?? ''} onChange={set('notes')} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={save.isPending}>{initial ? tx('Save') : tx('Create')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Mark lost with a reason. */
export function LostDialog({ open, onOpenChange, id, onDone }: { open: boolean; onOpenChange: (o: boolean) => void; id: string; onDone?: () => void }) {
  const { data: lk } = useCrmLookups();
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');
  const m = useCrmMutation(crmApi.lost);
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Mark as lost')}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <Field label={tx('Lost reason')}>
            <OptionalSelect value={reason} onChange={setReason} options={(lk?.lostReasons ?? []).filter((r) => r.isActive).map((r) => ({ value: r.id, label: r.name }))} placeholder={tx('Choose a reason')} noneLabel={tx('Not recorded')} />
          </Field>
          <Field label={tx('Closing note')}><Textarea rows={3} value={note} onChange={(e) => setNote(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button variant="destructive" disabled={m.isPending} onClick={() => m.mutate({ id, lostReasonId: reason || undefined, lostNote: note || undefined }, {
            onSuccess: () => { toast.success(tx('Marked as lost')); onOpenChange(false); onDone?.(); },
            onError: (e) => toast.error(apiErrorMessage(e, tx('Could not mark as lost'))),
          })}>{tx('Mark lost')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

const PLAN_TYPES: PlannedType[] = ['call', 'email', 'meeting', 'task', 'note'];

/** Schedule a planned activity (or log a note) on an opportunity / customer. */
export function ScheduleDialog({ open, onOpenChange, dealId, partnerId, onDone }: {
  open: boolean; onOpenChange: (o: boolean) => void; dealId?: string; partnerId?: string; onDone?: () => void;
}) {
  const { data: lk } = useCrmLookups();
  const [type, setType] = useState<PlannedType>('call');
  const [title, setTitle] = useState('');
  const [due, setDue] = useState('');
  const [remind, setRemind] = useState('');
  const [assignee, setAssignee] = useState('');
  const [body, setBody] = useState('');
  useEffect(() => {
    if (open) { setType('call'); setTitle(''); setDue(new Date(Date.now() + 86_400_000).toISOString().slice(0, 16)); setRemind(''); setAssignee(''); setBody(''); }
  }, [open]);
  const m = useCrmMutation(crmApi.schedule);
  const submit = () => {
    if (!title.trim()) return toast.error(tx('Add a summary'));
    m.mutate({
      type, title, body: body || undefined, dealId, partnerId,
      dueAt: type === 'note' || !due ? undefined : new Date(due).toISOString(),
      remindAt: remind ? new Date(remind).toISOString() : undefined,
      assignedToId: assignee || undefined,
    }, {
      onSuccess: () => { toast.success(type === 'note' ? tx('Note logged') : tx('Activity scheduled')); onOpenChange(false); onDone?.(); },
      onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
    });
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{tx('Schedule activity')}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <div className="flex flex-wrap gap-1.5">
            {PLAN_TYPES.map((t) => {
              const Icon = TYPE_ICON[t];
              return (
                <button key={t} type="button" onClick={() => setType(t)}
                  className={cn('flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-sm', type === t ? 'border-primary bg-primary/10 font-medium text-primary' : 'hover:bg-muted')}>
                  <Icon className="h-4 w-4" />{typeLabel(t)}
                </button>
              );
            })}
          </div>
          <Field label={tx('Summary')}><Input autoFocus value={title} onChange={(e) => setTitle(e.target.value)} placeholder={tx('e.g. Discuss proposal')} /></Field>
          {type !== 'note' && (
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label={tx('Due')}><Input type="datetime-local" value={due} onChange={(e) => setDue(e.target.value)} /></Field>
              <Field label={tx('Remind me at')}><Input type="datetime-local" value={remind} onChange={(e) => setRemind(e.target.value)} /></Field>
              <Field label={tx('Assigned to')} className="sm:col-span-2">
                <OptionalSelect value={assignee} onChange={setAssignee} options={(lk?.users ?? []).map((u) => ({ value: u.id, label: u.name }))} placeholder={tx('Me')} noneLabel={tx('Me')} />
              </Field>
            </div>
          )}
          <Field label={tx('Details')}><Textarea rows={3} value={body} onChange={(e) => setBody(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={m.isPending}>{type === 'note' ? tx('Log note') : tx('Schedule')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Mark a planned activity done with an outcome and an optional follow-up. */
export function DoneDialog({ open, onOpenChange, activityId, onDone }: { open: boolean; onOpenChange: (o: boolean) => void; activityId: string; onDone?: () => void }) {
  const [outcome, setOutcome] = useState('');
  const [follow, setFollow] = useState(false);
  const [ftype, setFtype] = useState<PlannedType>('call');
  const [ftitle, setFtitle] = useState('');
  const [fdue, setFdue] = useState('');
  useEffect(() => { if (open) { setOutcome(''); setFollow(false); setFtitle(''); setFdue(new Date(Date.now() + 3 * 86_400_000).toISOString().slice(0, 16)); } }, [open]);
  const m = useCrmMutation(crmApi.doneActivity);
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Mark done')}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <Field label={tx('Outcome')}><Textarea rows={3} value={outcome} onChange={(e) => setOutcome(e.target.value)} placeholder={tx('What happened?')} /></Field>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={follow} onChange={(e) => setFollow(e.target.checked)} />{tx('Schedule a follow-up')}</label>
          {follow && (
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label={tx('Type')}>
                <OptionalSelect value={ftype} onChange={(v) => setFtype((v || 'call') as PlannedType)} options={PLAN_TYPES.filter((t) => t !== 'note').map((t) => ({ value: t, label: typeLabel(t) }))} placeholder={tx('Call')} />
              </Field>
              <Field label={tx('Due')}><Input type="datetime-local" value={fdue} onChange={(e) => setFdue(e.target.value)} /></Field>
              <Field label={tx('Summary')} className="sm:col-span-2"><Input value={ftitle} onChange={(e) => setFtitle(e.target.value)} /></Field>
            </div>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button disabled={m.isPending} onClick={() => m.mutate({
            id: activityId, outcome: outcome || undefined,
            followUp: follow ? { type: ftype, title: ftitle || typeLabel(ftype), dueAt: fdue ? new Date(fdue).toISOString() : undefined } : undefined,
          }, {
            onSuccess: () => { toast.success(tx('Done')); onOpenChange(false); onDone?.(); },
            onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
          })}>{tx('Done')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Lead → opportunity: link a customer, create one from the lead, or continue without. */
export function ConvertDialog({ open, onOpenChange, lead, onDone }: { open: boolean; onOpenChange: (o: boolean) => void; lead: Opportunity; onDone?: (o: Opportunity) => void }) {
  const { data: lk } = useCrmLookups();
  const [mode, setMode] = useState<'create' | 'link' | 'none'>('create');
  const [partnerId, setPartnerId] = useState('');
  const [partnerName, setPartnerName] = useState<string | null>(null);
  const [stageId, setStageId] = useState('');
  useEffect(() => {
    if (open) { setMode(lead.partnerId ? 'link' : 'create'); setPartnerId(lead.partnerId ?? ''); setPartnerName(lead.partnerName); setStageId(''); }
  }, [open, lead]);
  const m = useCrmMutation(crmApi.convert);
  const name = lead.companyName || lead.contactName || lead.name;
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Convert to opportunity')}</DialogTitle></DialogHeader>
        <div className="space-y-3 text-sm">
          <label className="flex items-start gap-2"><input type="radio" checked={mode === 'create'} onChange={() => setMode('create')} className="mt-1" />
            <span>{tx('Create a new customer')} <strong>{name}</strong></span></label>
          <label className="flex items-start gap-2"><input type="radio" checked={mode === 'link'} onChange={() => setMode('link')} className="mt-1" />{tx('Link to an existing customer')}</label>
          {mode === 'link' && <PartnerPicker value={partnerId} label={partnerName} onPick={(id, n) => { setPartnerId(id); setPartnerName(n || null); }} />}
          <label className="flex items-start gap-2"><input type="radio" checked={mode === 'none'} onChange={() => setMode('none')} className="mt-1" />{tx('Do not link a customer yet')}</label>
          <Field label={tx('Pipeline stage')}>
            <OptionalSelect value={stageId} onChange={setStageId} options={(lk?.stages ?? []).filter((s) => s.isActive && !s.isWon).map((s) => ({ value: s.id, label: s.name }))} placeholder={tx('Keep current / first stage')} noneLabel={tx('Keep current / first stage')} />
          </Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button disabled={m.isPending || (mode === 'link' && !partnerId)} onClick={() => m.mutate({
            id: lead.id, createCustomer: mode === 'create', partnerId: mode === 'link' ? partnerId : undefined, stageId: stageId || undefined,
          }, {
            onSuccess: (o) => { toast.success(tx('Converted to opportunity')); onOpenChange(false); onDone?.(o); },
            onError: (e) => toast.error(apiErrorMessage(e, tx('Could not convert'))),
          })}>{tx('Convert')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

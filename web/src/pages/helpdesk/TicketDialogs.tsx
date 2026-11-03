import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Loader2, Upload } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { usePartners } from '@/features/partners/api';
import {
  CHANNELS, DISPATCH_METHODS, useCreateOutgoingLetter, useCreateTicket, useDispatchLetter, useLetterTemplates,
  useRegisterIncomingLetter, useReplyLetter, useTeams, type HdTicket, type LetterBody,
} from '@/features/helpdesk/api';
import { filesOf } from '@/components/dms/file-utils';
import { channelLabel, dispatchLabel } from '@/features/helpdesk/labels';


function PartnerPicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const [search, setSearch] = useState('');
  const { data } = usePartners({ page: 1, pageSize: 50, search: search || undefined });
  const rows = data?.data ?? [];
  return (
    <div className="grid gap-1">
      <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={tx('Search customer / supplier…')} />
      <SearchableSelect value={value} onValueChange={onChange} placeholder={tx('Select a partner (optional)')}
        options={rows.map((p) => ({ value: p.id, label: p.name, hint: p.email ?? undefined }))} />
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <label className="grid gap-1 text-sm">{label}{children}</label>;
}

/** New ticket (any channel). */
export function NewTicketDialog({ open, onOpenChange, defaults }: {
  open: boolean; onOpenChange: (o: boolean) => void; defaults?: Record<string, string>;
}) {
  const navigate = useNavigate();
  const { data: teams } = useTeams();
  const create = useCreateTicket();
  const [f, setF] = useState<Record<string, string>>({ priority: 'normal', channel: 'phone', ...defaults });
  const set = (k: string, v: string) => setF((x) => ({ ...x, [k]: v }));
  const submit = async () => {
    try {
      const t = await create.mutateAsync(Object.fromEntries(Object.entries(f).filter(([, v]) => v)));
      notify.success(tx('Ticket {{n}} created', { n: t.number }));
      onOpenChange(false);
      navigate(`/helpdesk/tickets/${t.id}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not create the ticket')));
    }
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{tx('New ticket')}</DialogTitle></DialogHeader>
        <div className="grid gap-3">
          <Field label={tx('Subject')}><Input value={f.subject ?? ''} onChange={(e) => set('subject', e.target.value)} /></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={tx('Team')}>
              <Select value={f.teamId ?? ''} onValueChange={(v) => set('teamId', v)}>
                <SelectTrigger><SelectValue placeholder={tx('Default team')} /></SelectTrigger>
                <SelectContent>{(teams ?? []).filter((t) => t.isActive).map((t) => <SelectItem key={t.id} value={t.id}>{t.name}</SelectItem>)}</SelectContent>
              </Select>
            </Field>
            <Field label={tx('Priority')}>
              <Select value={f.priority} onValueChange={(v) => set('priority', v)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>{['low', 'normal', 'high', 'urgent'].map((p) => <SelectItem key={p} value={p}>{tx(p)}</SelectItem>)}</SelectContent>
              </Select>
            </Field>
            <Field label={tx('Channel')}>
              <Select value={f.channel} onValueChange={(v) => set('channel', v)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>{CHANNELS.filter((c) => c !== 'letter').map((c) => <SelectItem key={c} value={c}>{channelLabel(c)}</SelectItem>)}</SelectContent>
              </Select>
            </Field>
            <Field label={tx('Contact e-mail')}><Input value={f.contactEmail ?? ''} onChange={(e) => set('contactEmail', e.target.value)} /></Field>
          </div>
          <Field label={tx('Customer / supplier')}><PartnerPicker value={f.partnerId ?? ''} onChange={(v) => set('partnerId', v)} /></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={tx('Contact name')}><Input value={f.contactName ?? ''} onChange={(e) => set('contactName', e.target.value)} /></Field>
            <Field label={tx('Contact phone')}><Input value={f.contactPhone ?? ''} onChange={(e) => set('contactPhone', e.target.value)} /></Field>
          </div>
          <Field label={tx('Description')}><Textarea rows={4} value={f.description ?? ''} onChange={(e) => set('description', e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!f.subject?.trim() || create.isPending}>{tx('Create')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Register an incoming letter with its scan(s). */
export function IncomingLetterDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const navigate = useNavigate();
  const register = useRegisterIncomingLetter();
  const input = useRef<HTMLInputElement>(null);
  const [files, setFiles] = useState<File[]>([]);
  const today = new Date().toISOString().slice(0, 10);
  const [f, setF] = useState<Record<string, string>>({ letterDate: today, confidentiality: 'normal', priority: 'normal' });
  const set = (k: string, v: string) => setF((x) => ({ ...x, [k]: v }));
  const submit = async () => {
    try {
      const t = await register.mutateAsync({ fields: f, files });
      notify.success(tx('Letter {{n}} registered', { n: t.letterNumber ?? t.number }));
      onOpenChange(false);
      navigate(`/helpdesk/tickets/${t.id}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not register the letter')));
    }
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{tx('Register incoming letter')}</DialogTitle></DialogHeader>
        <div className="grid gap-3">
          <Field label={tx('Subject')}><Input value={f.subject ?? ''} onChange={(e) => set('subject', e.target.value)} /></Field>
          <Field label={tx('From (customer / supplier / authority)')}><PartnerPicker value={f.partnerId ?? ''} onChange={(v) => set('partnerId', v)} /></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={tx('Sender name')}><Input value={f.contactName ?? ''} onChange={(e) => set('contactName', e.target.value)} /></Field>
            <Field label={tx('Their reference')}><Input value={f.externalRef ?? ''} onChange={(e) => set('externalRef', e.target.value)} /></Field>
            <Field label={tx('Letter date')}><Input type="date" value={f.letterDate ?? ''} onChange={(e) => set('letterDate', e.target.value)} /></Field>
            <Field label={tx('Reply due by')}><Input type="date" value={f.replyDueOn ?? ''} onChange={(e) => set('replyDueOn', e.target.value)} /></Field>
            <Field label={tx('Confidentiality')}>
              <Select value={f.confidentiality} onValueChange={(v) => set('confidentiality', v)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>{['normal', 'confidential', 'secret'].map((c) => <SelectItem key={c} value={c}>{tx(c)}</SelectItem>)}</SelectContent>
              </Select>
            </Field>
            <Field label={tx('Priority')}>
              <Select value={f.priority} onValueChange={(v) => set('priority', v)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>{['low', 'normal', 'high', 'urgent'].map((p) => <SelectItem key={p} value={p}>{tx(p)}</SelectItem>)}</SelectContent>
              </Select>
            </Field>
          </div>
          <Field label={tx('Summary')}><Textarea rows={3} value={f.description ?? ''} onChange={(e) => set('description', e.target.value)} /></Field>
          <div className="flex items-center gap-2">
            <Button type="button" variant="outline" onClick={() => input.current?.click()}><Upload className="mr-1 h-4 w-4" />{tx('Attach scan')}</Button>
            <span className="truncate text-xs text-muted-foreground">{files.map((x) => x.name).join(', ') || tx('No file chosen')}</span>
            <input ref={input} type="file" multiple hidden accept="application/pdf,image/*"
              onChange={(e) => setFiles(filesOf(e.target.files))} />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!f.subject?.trim() || register.isPending}>
            {register.isPending && <Loader2 className="mr-1 h-4 w-4 animate-spin" />}{tx('Register')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function LetterFields({ value, onChange }: { value: LetterBody; onChange: (v: LetterBody) => void }) {
  const { data: templates } = useLetterTemplates();
  const set = (k: keyof LetterBody, v: string) => onChange({ ...value, [k]: v || undefined });
  return (
    <>
      <Field label={tx('Template')}>
        <Select value={value.templateId ?? 'none'} onValueChange={(v) => set('templateId', v === 'none' ? '' : v)}>
          <SelectTrigger><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="none">{tx('No template — write the letter')}</SelectItem>
            {(templates ?? []).filter((t) => t.isActive).map((t) => <SelectItem key={t.id} value={t.id}>{t.name}</SelectItem>)}
          </SelectContent>
        </Select>
      </Field>
      <Field label={tx('Letter body')}>
        <Textarea rows={8} value={value.body ?? ''} onChange={(e) => set('body', e.target.value)}
          placeholder={tx('Placeholders: {{contact.name}}, {{partner.name}}, {{letter.number}}, {{date}}, {{reply.externalRef}}')} />
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label={tx('Signed by')}><Input value={value.signatureName ?? ''} onChange={(e) => set('signatureName', e.target.value)} /></Field>
        <Field label={tx('Title')}><Input value={value.signatureTitle ?? ''} onChange={(e) => set('signatureTitle', e.target.value)} /></Field>
      </div>
    </>
  );
}

/** New outgoing letter (rendered to PDF into Correspondence). */
export function OutgoingLetterDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const navigate = useNavigate();
  const create = useCreateOutgoingLetter();
  const [ticket, setTicket] = useState<Record<string, string>>({ confidentiality: 'normal' });
  const [letter, setLetter] = useState<LetterBody>({});
  const set = (k: string, v: string) => setTicket((x) => ({ ...x, [k]: v }));
  const submit = async () => {
    try {
      const t = await create.mutateAsync({ ticket: Object.fromEntries(Object.entries(ticket).filter(([, v]) => v)), letter });
      notify.success(tx('Letter {{n}} created', { n: t.letterNumber ?? t.number }));
      onOpenChange(false);
      navigate(`/helpdesk/tickets/${t.id}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not create the letter')));
    }
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[92vh] max-w-xl overflow-y-auto">
        <DialogHeader><DialogTitle>{tx('New outgoing letter')}</DialogTitle></DialogHeader>
        <div className="grid gap-3">
          <Field label={tx('Subject')}><Input value={ticket.subject ?? ''} onChange={(e) => set('subject', e.target.value)} /></Field>
          <Field label={tx('To (customer / supplier / authority)')}><PartnerPicker value={ticket.partnerId ?? ''} onChange={(v) => set('partnerId', v)} /></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={tx('Addressee name')}><Input value={ticket.contactName ?? ''} onChange={(e) => set('contactName', e.target.value)} /></Field>
            <Field label={tx('Their reference')}><Input value={ticket.externalRef ?? ''} onChange={(e) => set('externalRef', e.target.value)} /></Field>
          </div>
          <LetterFields value={letter} onChange={setLetter} />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!ticket.subject?.trim() || (!letter.body && !letter.templateId) || create.isPending}>
            {create.isPending && <Loader2 className="mr-1 h-4 w-4 animate-spin" />}{tx('Create letter')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Reply to an incoming letter with an outgoing one. */
export function ReplyLetterDialog({ ticket, open, onOpenChange }: { ticket: HdTicket; open: boolean; onOpenChange: (o: boolean) => void }) {
  const navigate = useNavigate();
  const reply = useReplyLetter();
  const [letter, setLetter] = useState<LetterBody>({ subject: `Re: ${ticket.subject}` });
  const submit = async () => {
    try {
      const t = await reply.mutateAsync({ id: ticket.id, letter });
      notify.success(tx('Reply letter {{n}} created', { n: t.letterNumber ?? t.number }));
      onOpenChange(false);
      navigate(`/helpdesk/tickets/${t.id}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not create the reply')));
    }
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[92vh] max-w-xl overflow-y-auto">
        <DialogHeader><DialogTitle>{tx('Reply with a letter')}</DialogTitle></DialogHeader>
        <div className="grid gap-3">
          <Field label={tx('Subject')}><Input value={letter.subject ?? ''} onChange={(e) => setLetter({ ...letter, subject: e.target.value })} /></Field>
          <LetterFields value={letter} onChange={setLetter} />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={(!letter.body && !letter.templateId) || reply.isPending}>
            {reply.isPending && <Loader2 className="mr-1 h-4 w-4 animate-spin" />}{tx('Create reply')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Record dispatch of an outgoing letter. */
export function DispatchDialog({ ticket, open, onOpenChange }: { ticket: HdTicket; open: boolean; onOpenChange: (o: boolean) => void }) {
  const dispatch = useDispatchLetter();
  const [method, setMethod] = useState<string>(ticket.dispatchMethod ?? 'post');
  const [tracking, setTracking] = useState(ticket.trackingNumber ?? '');
  const submit = async () => {
    try {
      await dispatch.mutateAsync({ id: ticket.id, method, trackingNumber: tracking || undefined });
      notify.success(tx('Dispatch recorded'));
      onOpenChange(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not record the dispatch')));
    }
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-sm">
        <DialogHeader><DialogTitle>{tx('Dispatch letter')}</DialogTitle></DialogHeader>
        <div className="grid gap-3">
          <Field label={tx('Method')}>
            <Select value={method} onValueChange={setMethod}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>{DISPATCH_METHODS.map((m) => <SelectItem key={m} value={m}>{dispatchLabel(m)}</SelectItem>)}</SelectContent>
            </Select>
          </Field>
          <Field label={tx('Tracking number')}><Input value={tracking} onChange={(e) => setTracking(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={dispatch.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

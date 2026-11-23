import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Code2, Copy, ExternalLink, KeyRound, Pencil, Plus, Trash2 } from 'lucide-react';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { OptionalSelect } from '@/pages/fleet/fleet-ui';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useCrmMutation, type CrmLookups } from '@/features/crm/suite-api';
import { Field } from './crm-ui';

export interface LeadForm {
  id: string;
  name: string;
  publicKey: string;
  sourceId: string | null;
  sourceName: string | null;
  teamId: string | null;
  teamName: string | null;
  ownerId: string | null;
  ownerName: string | null;
  tagIds: string[];
  allowedOrigins: string[];
  thankYouMessage: string | null;
  isActive: boolean;
  dailyLimit: number;
  submissions: number;
  lastSubmittedAt: string | null;
}

const err = (fallback: string) => (e: unknown) => toast.error(apiErrorMessage(e, fallback));
const apiBase = () => `${window.location.origin}/api/v1`;
const hostedUrl = (key: string) => `${window.location.origin}/forms/lead/${key}`;

/** Copy-paste HTML for a customer website: a plain form posting JSON to the public endpoint. */
function embedSnippet(f: LeadForm) {
  const formUrl = `${apiBase()}/public/crm/forms/${f.publicKey}`;
  const url = `${formUrl}/submit`;
  return `<form id="erp-lead-form">
  <input name="name" placeholder="Your name" required>
  <input name="email" type="email" placeholder="Email">
  <input name="phone" placeholder="Phone">
  <input name="company" placeholder="Company">
  <textarea name="message" placeholder="How can we help?"></textarea>
  <input name="_hp" tabindex="-1" autocomplete="off" style="position:absolute;left:-9999px" aria-hidden="true">
  <button type="submit">Send</button>
  <p id="erp-lead-form-result" role="status"></p>
</form>
<script>
var erpLeadToken = null;
fetch('${formUrl}').then(function (r) { return r.json(); }).then(function (b) { erpLeadToken = b.submitToken; });
document.getElementById('erp-lead-form').addEventListener('submit', async function (e) {
  e.preventDefault();
  var data = Object.fromEntries(new FormData(this));
  data._t = erpLeadToken;
  data.pageUrl = location.href;
  var r = await fetch('${url}', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
  var body = await r.json().catch(function () { return {}; });
  document.getElementById('erp-lead-form-result').textContent = body.message || (r.ok ? 'Thank you.' : 'Something went wrong.');
  if (r.ok) this.reset();
});
</script>`;
}

const copy = (text: string) => { void navigator.clipboard?.writeText(text); toast.success(tx('Copied')); };

/** CRM Settings → Website forms (CRM enhancements T3a). */
export function LeadFormsTab({ lk }: { lk: CrmLookups }) {
  const { data, isLoading } = useQuery({
    queryKey: ['crm-suite', 'lead-forms'],
    queryFn: async () => (await api.get<LeadForm[]>('/crm/config/lead-forms')).data,
  });
  const [editing, setEditing] = useState<Partial<LeadForm> | null>(null);
  const [embed, setEmbed] = useState<LeadForm | null>(null);
  const rotate = useCrmMutation((id: string) => api.post(`/crm/config/lead-forms/${id}/rotate-key`));
  const remove = useCrmMutation((id: string) => api.delete(`/crm/config/lead-forms/${id}`));

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="max-w-2xl text-sm text-muted-foreground">{tx('Put a contact form on your website. Each enquiry becomes a lead with the form\'s source, team, tags and salesperson.')}</p>
        <Button onClick={() => setEditing({ isActive: true, tagIds: [], allowedOrigins: [] })}><Plus className="mr-1.5 h-4 w-4" />{tx('New form')}</Button>
      </div>
      {isLoading && <Skeleton className="h-32 w-full" />}
      <div className="divide-y rounded-xl border bg-card">
        {data?.map((f) => (
          <div key={f.id} className="flex flex-wrap items-center gap-3 p-3 text-sm">
            <div className="min-w-0 flex-1">
              <p className="font-medium">{f.name} {!f.isActive && <span className="ml-1 rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">{tx('Off')}</span>}</p>
              <p className="text-xs text-muted-foreground">
                {f.submissions} {tx('enquiries')}{f.lastSubmittedAt ? ` · ${tx('last')} ${dateTime(f.lastSubmittedAt)}` : ''}
                {f.sourceName ? ` · ${f.sourceName}` : ''}{f.ownerName ? ` · ${f.ownerName}` : ''}
                {f.allowedOrigins.length ? ` · ${f.allowedOrigins.join(', ')}` : ''}
              </p>
            </div>
            <Button size="sm" variant="outline" onClick={() => setEmbed(f)}><Code2 className="mr-1 h-3.5 w-3.5" />{tx('Embed')}</Button>
            <Button size="sm" variant="ghost" title={tx('Open hosted form')} asChild>
              <a href={hostedUrl(f.publicKey)} target="_blank" rel="noreferrer"><ExternalLink className="h-4 w-4" /></a>
            </Button>
            <Button size="sm" variant="ghost" title={tx('Edit')} onClick={() => setEditing(f)}><Pencil className="h-4 w-4" /></Button>
            <Button size="sm" variant="ghost" title={tx('New key')} onClick={() => {
              if (window.confirm(tx('Websites using the current key will stop sending enquiries until you update them. Continue?'))) {
                rotate.mutate(f.id, { onSuccess: () => toast.success(tx('New key created')), onError: err(tx('Could not change the key')) });
              }
            }}><KeyRound className="h-4 w-4" /></Button>
            <Button size="sm" variant="ghost" title={tx('Delete')} onClick={() => {
              if (window.confirm(tx('Delete this form? Leads already received are kept.'))) remove.mutate(f.id, { onError: err(tx('Could not delete')) });
            }}><Trash2 className="h-4 w-4" /></Button>
          </div>
        ))}
        {data && data.length === 0 && <p className="p-6 text-center text-sm text-muted-foreground">{tx('No website forms yet.')}</p>}
      </div>
      {editing && <LeadFormDialog form={editing} lk={lk} onClose={() => setEditing(null)} />}
      {embed && (
        <Dialog open onOpenChange={(o) => !o && setEmbed(null)}>
          <DialogContent className="max-h-[92vh] max-w-2xl overflow-y-auto">
            <DialogHeader><DialogTitle>{tx('Add the form to your website')}</DialogTitle></DialogHeader>
            <div className="space-y-3 text-sm">
              <div>
                <p className="mb-1 font-medium">{tx('Option 1: link to the hosted form')}</p>
                <div className="flex gap-2"><Input readOnly value={hostedUrl(embed.publicKey)} onFocus={(e) => e.currentTarget.select()} /><Button variant="outline" onClick={() => copy(hostedUrl(embed.publicKey))}><Copy className="h-4 w-4" /></Button></div>
              </div>
              <div>
                <p className="mb-1 font-medium">{tx('Option 2: paste this HTML into your website')}</p>
                <Textarea readOnly rows={14} className="font-mono text-xs" value={embedSnippet(embed)} onFocus={(e) => e.currentTarget.select()} />
                <Button className="mt-2" variant="outline" size="sm" onClick={() => copy(embedSnippet(embed))}><Copy className="mr-1 h-3.5 w-3.5" />{tx('Copy HTML')}</Button>
              </div>
              <p className="text-xs text-muted-foreground">{tx('Fields: name (required), email or phone (one required), company, subject, message. Keep the hidden _hp field: it catches spam bots. Each address can send 10 enquiries a minute.')}</p>
            </div>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}

function LeadFormDialog({ form, lk, onClose }: { form: Partial<LeadForm>; lk: CrmLookups; onClose: () => void }) {
  const [f, setF] = useState(form);
  const [origins, setOrigins] = useState((form.allowedOrigins ?? []).join('\n'));
  const set = (p: Partial<LeadForm>) => setF((x) => ({ ...x, ...p }));
  const save = useCrmMutation(async () => {
    const body = {
      name: f.name, sourceId: f.sourceId || null, teamId: f.teamId || null, ownerId: f.ownerId || null, tagIds: f.tagIds ?? [],
      allowedOrigins: origins.split(/[\n,]/).map((s) => s.trim()).filter(Boolean), thankYouMessage: f.thankYouMessage || null, isActive: f.isActive ?? true, dailyLimit: f.dailyLimit ?? 200,
    };
    return f.id ? api.patch(`/crm/config/lead-forms/${f.id}`, body) : api.post('/crm/config/lead-forms', body);
  });
  const toggleTag = (id: string) => set({ tagIds: (f.tagIds ?? []).includes(id) ? (f.tagIds ?? []).filter((t) => t !== id) : [...(f.tagIds ?? []), id] });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-lg overflow-y-auto">
        <DialogHeader><DialogTitle>{f.id ? tx('Edit website form') : tx('New website form')}</DialogTitle></DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={tx('Name')} className="sm:col-span-2"><Input autoFocus value={f.name ?? ''} placeholder={tx('Contact us page')} onChange={(e) => set({ name: e.target.value })} /></Field>
          <Field label={tx('Source')}>
            <OptionalSelect value={f.sourceId ?? ''} onChange={(v) => set({ sourceId: v || null })} options={lk.sources.filter((s) => s.isActive).map((s) => ({ value: s.id, label: s.name }))} placeholder={tx('None')} noneLabel={tx('None')} />
          </Field>
          <Field label={tx('Sales team')}>
            <OptionalSelect value={f.teamId ?? ''} onChange={(v) => set({ teamId: v || null })} options={lk.teams.map((t) => ({ value: t.id, label: t.name }))} placeholder={tx('None')} noneLabel={tx('None')} />
          </Field>
          <Field label={tx('Salesperson')} className="sm:col-span-2" hint={tx('Empty = the normal lead assignment rules')}>
            <OptionalSelect value={f.ownerId ?? ''} onChange={(v) => set({ ownerId: v || null })} options={lk.users.map((u) => ({ value: u.id, label: u.name }))} placeholder={tx('Automatic')} noneLabel={tx('Automatic')} />
          </Field>
          {lk.tags.length > 0 && (
            <Field label={tx('Tags')} className="sm:col-span-2">
              <div className="flex flex-wrap gap-1.5">
                {lk.tags.map((t) => (
                  <button key={t.id} type="button" onClick={() => toggleTag(t.id)} aria-pressed={(f.tagIds ?? []).includes(t.id)}
                    className={`rounded-full border px-2.5 py-0.5 text-xs ${(f.tagIds ?? []).includes(t.id) ? 'border-primary bg-primary text-primary-foreground' : 'hover:bg-muted'}`}>{t.name}</button>
                ))}
              </div>
            </Field>
          )}
          <Field label={tx('Allowed websites')} className="sm:col-span-2" hint={tx('One per line, e.g. https://www.example.com. Empty = any website. The hosted form always works.')}>
            <Textarea rows={2} value={origins} onChange={(e) => setOrigins(e.target.value)} />
          </Field>
          <Field label={tx('Thank-you message')} className="sm:col-span-2"><Input value={f.thankYouMessage ?? ''} placeholder={tx('Thank you, we will be in touch soon.')} onChange={(e) => set({ thankYouMessage: e.target.value })} /></Field>
          <Field label={tx('Enquiries per day (limit)')}><Input type="number" min={1} max={100000} value={f.dailyLimit ?? 200} onChange={(e) => set({ dailyLimit: Number(e.target.value) })} /></Field>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={f.isActive ?? true} onChange={(e) => set({ isActive: e.target.checked })} />{tx('Accepting enquiries')}</label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!f.name || save.isPending} onClick={() => save.mutate(undefined, { onSuccess: () => { toast.success(tx('Saved')); onClose(); }, onError: err(tx('Could not save')) })}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

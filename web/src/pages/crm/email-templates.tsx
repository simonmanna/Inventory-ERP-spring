import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { tx } from '@/lib/i18n/tx';
import { useCrmMutation, type EmailTemplate } from '@/features/crm/suite-api';
import { Field } from './crm-ui';

const err = (fallback: string) => (e: unknown) => toast.error(apiErrorMessage(e, fallback));
const PLACEHOLDER_HELP: Record<string, string> = {
  contact_name: tx('contact name'), first_name: tx('contact first name'), company: tx('customer or company'), deal_name: tx('lead / opportunity name'),
  amount: tx('expected revenue'), salesperson: tx('assigned salesperson'), my_name: tx('your name'), organization: tx('your company'),
};

/** CRM Settings → Email templates (CRM enhancements T3b). */
export function EmailTemplatesTab() {
  const { data, isLoading } = useQuery({
    queryKey: ['crm-suite', 'email-templates', 'settings'],
    queryFn: async () => (await api.get<{ data: EmailTemplate[]; placeholders: string[] }>('/crm/config/email-templates')).data,
  });
  const [editing, setEditing] = useState<Partial<EmailTemplate> | null>(null);
  const remove = useCrmMutation((id: string) => api.delete(`/crm/config/email-templates/${id}`));
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="max-w-2xl text-sm text-muted-foreground">{tx('Reusable emails for leads and opportunities. Salespeople pick one, adjust it and send; every email is logged in the history.')}</p>
        <Button onClick={() => setEditing({ isActive: true })}><Plus className="mr-1.5 h-4 w-4" />{tx('New template')}</Button>
      </div>
      {isLoading && <Skeleton className="h-24 w-full" />}
      <div className="divide-y rounded-xl border bg-card">
        {data?.data.map((t) => (
          <div key={t.id} className="flex items-center gap-3 p-3 text-sm">
            <div className="min-w-0 flex-1">
              <p className={t.isActive ? 'font-medium' : 'font-medium text-muted-foreground line-through'}>{t.name}</p>
              <p className="truncate text-xs text-muted-foreground">{t.subject}</p>
            </div>
            <Button size="sm" variant="ghost" title={tx('Edit')} onClick={() => setEditing(t)}><Pencil className="h-4 w-4" /></Button>
            <Button size="sm" variant="ghost" className="text-destructive" title={tx('Delete')} onClick={() => confirm(tx('Delete this template?')) && remove.mutate(t.id, { onError: err(tx('Could not delete')) })}><Trash2 className="h-4 w-4" /></Button>
          </div>
        ))}
        {data && data.data.length === 0 && <p className="p-6 text-center text-sm text-muted-foreground">{tx('No templates yet.')}</p>}
      </div>
      {editing && <TemplateDialog t={editing} placeholders={data?.placeholders ?? []} onClose={() => setEditing(null)} />}
    </div>
  );
}

function TemplateDialog({ t, placeholders, onClose }: { t: Partial<EmailTemplate>; placeholders: string[]; onClose: () => void }) {
  const [f, setF] = useState(t);
  const save = useCrmMutation(() => {
    const body = { name: f.name, subject: f.subject, body: f.body, isActive: f.isActive ?? true };
    return f.id ? api.patch(`/crm/config/email-templates/${f.id}`, body) : api.post('/crm/config/email-templates', body);
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-2xl overflow-y-auto">
        <DialogHeader><DialogTitle>{f.id ? tx('Edit template') : tx('New template')}</DialogTitle></DialogHeader>
        <div className="grid gap-3">
          <Field label={tx('Name')}><Input autoFocus maxLength={120} value={f.name ?? ''} placeholder={tx('First follow-up')} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
          <Field label={tx('Subject')}><Input maxLength={200} value={f.subject ?? ''} placeholder="{{deal_name}}" onChange={(e) => setF({ ...f, subject: e.target.value })} /></Field>
          <Field label={tx('Message')}><Textarea rows={10} value={f.body ?? ''} placeholder={tx('Dear {{first_name}},')} onChange={(e) => setF({ ...f, body: e.target.value })} /></Field>
          <div className="rounded-lg bg-muted/40 p-2 text-xs text-muted-foreground">
            <p className="mb-1 font-medium text-foreground">{tx('Placeholders')}</p>
            <div className="flex flex-wrap gap-x-4 gap-y-1">
              {placeholders.map((p) => (
                <button key={p} type="button" className="hover:text-foreground" title={tx('Add to the message')} onClick={() => setF({ ...f, body: `${f.body ?? ''}{{${p}}}` })}>
                  <code>{`{{${p}}}`}</code> {PLACEHOLDER_HELP[p] ?? ''}
                </button>
              ))}
            </div>
          </div>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={f.isActive ?? true} onChange={(e) => setF({ ...f, isActive: e.target.checked })} />{tx('Active')}</label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={!f.name || !f.subject || !f.body || save.isPending} onClick={() => save.mutate(undefined, { onSuccess: () => { toast.success(tx('Saved')); onClose(); }, onError: err(tx('Could not save')) })}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

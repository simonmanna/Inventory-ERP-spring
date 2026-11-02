import { useEffect, useState } from 'react';
import { Plus, Save, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useUsers } from '@/features/staff/api';
import {
  useCreateStage, useCreateTeam, useCreateTemplate, useCreateType, useDeleteStage, useLetterTemplates, useTeams,
  useTicketTypes, useUpdateStage, useUpdateTeam, useUpdateTemplate, type HdTeam, type HdTemplate,
} from '@/features/helpdesk/api';

const fail = (e: unknown) => notify.error(apiMessage(e, tx('Action failed')));

/** Helpdesk configuration: teams (members, assignment, stages), ticket types, letter templates. */
export function HelpdeskSettingsPage() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">{tx('Helpdesk settings')}</h1>
      <Tabs defaultValue="teams">
        <TabsList>
          <TabsTrigger value="teams">{tx('Teams & stages')}</TabsTrigger>
          <TabsTrigger value="types">{tx('Ticket types')}</TabsTrigger>
          <TabsTrigger value="templates">{tx('Letter templates')}</TabsTrigger>
        </TabsList>
        <TabsContent value="teams"><TeamsTab /></TabsContent>
        <TabsContent value="types"><TypesTab /></TabsContent>
        <TabsContent value="templates"><TemplatesTab /></TabsContent>
      </Tabs>
    </div>
  );
}

function TeamsTab() {
  const { data: teams } = useTeams();
  const create = useCreateTeam();
  const [name, setName] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const team = teams?.find((t) => t.id === selected) ?? teams?.[0];
  return (
    <div className="grid gap-4 lg:grid-cols-[280px_1fr]">
      <Card>
        <CardContent className="space-y-2 pt-4">
          <div className="flex gap-2">
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={tx('New team')} />
            <Button size="icon" disabled={!name.trim()} onClick={() => create.mutateAsync({ name: name.trim() })
              .then((t) => { setName(''); setSelected(t.id); }, fail)}><Plus className="h-4 w-4" /></Button>
          </div>
          <ul className="divide-y rounded-md border text-sm">
            {(teams ?? []).map((t) => (
              <li key={t.id}>
                <button type="button" onClick={() => setSelected(t.id)}
                  className={`flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-accent ${team?.id === t.id ? 'bg-accent' : ''}`}>
                  <span className="flex-1">{t.name}</span>
                  {!t.isActive && <Badge variant="outline">{tx('Inactive')}</Badge>}
                  <span className="text-xs text-muted-foreground">{t.openCount ?? 0}</span>
                </button>
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>
      {team && <TeamEditor key={team.id} team={team} />}
    </div>
  );
}

function TeamEditor({ team }: { team: HdTeam }) {
  const update = useUpdateTeam();
  const createStage = useCreateStage();
  const updateStage = useUpdateStage();
  const deleteStage = useDeleteStage();
  const { data: users } = useUsers({ page: 1, pageSize: 200 });
  const [name, setName] = useState(team.name);
  const [email, setEmail] = useState(team.email ?? '');
  const [assignment, setAssignment] = useState(team.assignment);
  const [members, setMembers] = useState<string[]>(team.memberIds);
  const [stageName, setStageName] = useState('');
  useEffect(() => setMembers(team.memberIds), [team.memberIds]);

  const save = () => update.mutateAsync({ id: team.id, body: { name, email: email || undefined, assignment, memberIds: members } })
    .then(() => notify.success(tx('Saved')), fail);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader><CardTitle className="text-base">{team.name}</CardTitle></CardHeader>
        <CardContent className="grid gap-3 text-sm sm:grid-cols-2">
          <label className="grid gap-1">{tx('Name')}<Input value={name} onChange={(e) => setName(e.target.value)} /></label>
          <label className="grid gap-1">{tx('Team e-mail')}<Input value={email} onChange={(e) => setEmail(e.target.value)} /></label>
          <label className="grid gap-1">{tx('Assignment')}
            <Select value={assignment} onValueChange={(v) => setAssignment(v as HdTeam['assignment'])}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="manual">{tx('Manual')}</SelectItem>
                <SelectItem value="round_robin">{tx('Round robin')}</SelectItem>
                <SelectItem value="balanced">{tx('Fewest open tickets')}</SelectItem>
              </SelectContent>
            </Select>
          </label>
          <div className="grid gap-1 sm:col-span-2">
            {tx('Members')}
            <div className="flex max-h-40 flex-wrap gap-2 overflow-y-auto rounded-md border p-2">
              {(users?.data ?? []).map((u) => (
                <label key={u.id} className="flex items-center gap-1 text-xs">
                  <input type="checkbox" checked={members.includes(u.id)}
                    onChange={(e) => setMembers(e.target.checked ? [...members, u.id] : members.filter((m) => m !== u.id))} />
                  {`${u.firstName} ${u.lastName ?? ''}`.trim()}
                </label>
              ))}
            </div>
          </div>
          <div className="flex gap-2 sm:col-span-2">
            <Button onClick={save}><Save className="mr-1 h-4 w-4" />{tx('Save')}</Button>
            <Button variant="outline" onClick={() => update.mutateAsync({ id: team.id, body: { isActive: !team.isActive } }).catch(fail)}>
              {team.isActive ? tx('Deactivate') : tx('Activate')}
            </Button>
          </div>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Stages')}</CardTitle></CardHeader>
        <CardContent className="space-y-2 text-sm">
          {team.stages.map((s) => (
            <div key={s.id} className="flex items-center gap-2">
              <Input className="w-20" type="number" defaultValue={s.sequence}
                onBlur={(e) => Number(e.target.value) !== s.sequence && updateStage.mutateAsync({ id: s.id, body: { sequence: Number(e.target.value) } }).catch(fail)} />
              <Input className="flex-1" defaultValue={s.name}
                onBlur={(e) => e.target.value.trim() && e.target.value !== s.name && updateStage.mutateAsync({ id: s.id, body: { name: e.target.value.trim() } }).catch(fail)} />
              <label className="flex items-center gap-1 text-xs">
                <input type="checkbox" checked={s.isClosed}
                  onChange={(e) => updateStage.mutateAsync({ id: s.id, body: { isClosed: e.target.checked } }).catch(fail)} />{tx('Closed')}
              </label>
              <label className="flex items-center gap-1 text-xs">
                <input type="checkbox" checked={s.folded}
                  onChange={(e) => updateStage.mutateAsync({ id: s.id, body: { folded: e.target.checked } }).catch(fail)} />{tx('Folded')}
              </label>
              <Button variant="ghost" size="icon" onClick={() => deleteStage.mutateAsync(s.id).catch(fail)}><Trash2 className="h-4 w-4" /></Button>
            </div>
          ))}
          <div className="flex gap-2">
            <Input value={stageName} onChange={(e) => setStageName(e.target.value)} placeholder={tx('New stage')} />
            <Button disabled={!stageName.trim()} onClick={() => createStage.mutateAsync({ teamId: team.id, body: { name: stageName.trim() } })
              .then(() => setStageName(''), fail)}><Plus className="mr-1 h-4 w-4" />{tx('Add')}</Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

function TypesTab() {
  const { data } = useTicketTypes();
  const create = useCreateType();
  const [name, setName] = useState('');
  return (
    <Card className="max-w-lg">
      <CardContent className="space-y-2 pt-4 text-sm">
        <div className="flex gap-2">
          <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={tx('e.g. Complaint, Warranty claim, Enquiry')} />
          <Button size="icon" disabled={!name.trim()} onClick={() => create.mutateAsync(name.trim()).then(() => setName(''), fail)}>
            <Plus className="h-4 w-4" />
          </Button>
        </div>
        <ul className="divide-y rounded-md border">
          {(data ?? []).map((t) => <li key={t.id} className="px-3 py-1.5">{t.name}</li>)}
        </ul>
      </CardContent>
    </Card>
  );
}

function TemplatesTab() {
  const { data } = useLetterTemplates();
  const [selected, setSelected] = useState<string | 'new' | null>(null);
  const tpl = data?.find((t) => t.id === selected) ?? null;
  return (
    <div className="grid gap-4 lg:grid-cols-[280px_1fr]">
      <Card>
        <CardContent className="space-y-2 pt-4">
          <Button variant="outline" size="sm" onClick={() => setSelected('new')}><Plus className="mr-1 h-4 w-4" />{tx('New template')}</Button>
          <ul className="divide-y rounded-md border text-sm">
            {(data ?? []).map((t) => (
              <li key={t.id}>
                <button type="button" onClick={() => setSelected(t.id)}
                  className={`w-full px-3 py-2 text-left hover:bg-accent ${selected === t.id ? 'bg-accent' : ''}`}>{t.name}</button>
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>
      {(selected === 'new' || tpl) && <TemplateEditor key={selected ?? 'x'} tpl={tpl} onSaved={(id) => setSelected(id)} />}
    </div>
  );
}

function TemplateEditor({ tpl, onSaved }: { tpl: HdTemplate | null; onSaved: (id: string) => void }) {
  const create = useCreateTemplate();
  const update = useUpdateTemplate();
  const [f, setF] = useState<Record<string, string>>({
    name: tpl?.name ?? '', subject: tpl?.subject ?? '', body: tpl?.body ?? '', closingLine: tpl?.closingLine ?? '',
    signatureName: tpl?.signatureName ?? '', signatureTitle: tpl?.signatureTitle ?? '',
  });
  const set = (k: string, v: string) => setF((x) => ({ ...x, [k]: v }));
  const save = () => (tpl ? update.mutateAsync({ id: tpl.id, body: f }) : create.mutateAsync(f))
    .then((t) => { notify.success(tx('Saved')); onSaved(t.id); }, fail);
  return (
    <Card>
      <CardContent className="grid gap-3 pt-4 text-sm">
        <label className="grid gap-1">{tx('Name')}<Input value={f.name} onChange={(e) => set('name', e.target.value)} /></label>
        <label className="grid gap-1">{tx('Subject')}<Input value={f.subject} onChange={(e) => set('subject', e.target.value)} /></label>
        <label className="grid gap-1">{tx('Body')}
          <Textarea rows={10} value={f.body} onChange={(e) => set('body', e.target.value)} />
          <span className="text-xs text-muted-foreground">
            {tx('Placeholders: {{contact.name}}, {{partner.name}}, {{subject}}, {{body}}, {{letter.number}}, {{ticket.number}}, {{date}}, {{user.name}}, {{organization.name}}, {{reply.letterNumber}}, {{reply.externalRef}}, {{reply.letterDate}}')}
          </span>
        </label>
        <div className="grid gap-3 sm:grid-cols-3">
          <label className="grid gap-1">{tx('Closing line')}<Input value={f.closingLine} onChange={(e) => set('closingLine', e.target.value)} /></label>
          <label className="grid gap-1">{tx('Signed by')}<Input value={f.signatureName} onChange={(e) => set('signatureName', e.target.value)} /></label>
          <label className="grid gap-1">{tx('Title')}<Input value={f.signatureTitle} onChange={(e) => set('signatureTitle', e.target.value)} /></label>
        </div>
        <div><Button onClick={save} disabled={!f.name.trim() || !f.body.trim()}><Save className="mr-1 h-4 w-4" />{tx('Save')}</Button></div>
      </CardContent>
    </Card>
  );
}

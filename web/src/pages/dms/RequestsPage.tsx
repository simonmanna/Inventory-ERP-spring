import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { FileQuestion, Plus, Upload } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useUsers } from '@/features/staff/api';
import {
  useCancelRequest, useCreateRequest, useFulfilRequest, useRequests, useWorkspaces, type DmsRequest,
} from '@/features/dms/api';
import { filesOf } from '@/components/dms/file-utils';

/** Document requests (DM-6): ask colleagues for files; fulfil yours by uploading. */
export function RequestsPage() {
  const [scope, setScope] = useState<'assigned' | 'sent'>('assigned');
  const { data, isLoading } = useRequests(scope);
  const canCreate = useAuthStore((s) => s.hasPermission(PERMISSIONS.dms.requestCreate));
  const [open, setOpen] = useState(false);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Document requests')}</h1>
          <p className="text-sm text-muted-foreground">{tx('Ask a colleague for a file, with a due date. They fulfil it by uploading.')}</p>
        </div>
        {canCreate && <Button onClick={() => setOpen(true)}><Plus className="mr-1 h-4 w-4" />{tx('Request a document')}</Button>}
      </div>
      <Tabs value={scope} onValueChange={(v) => setScope(v as 'assigned' | 'sent')}>
        <TabsList>
          <TabsTrigger value="assigned">{tx('Requested from me')}</TabsTrigger>
          <TabsTrigger value="sent">{tx('Requested by me')}</TabsTrigger>
        </TabsList>
      </Tabs>
      <Card>
        <CardContent className="p-0">
          {isLoading ? <p className="p-4 text-sm text-muted-foreground">{tx('Loading…')}</p>
            : (data ?? []).length === 0 ? (
              <div className="flex flex-col items-center gap-2 p-10 text-sm text-muted-foreground">
                <FileQuestion className="h-8 w-8" />{tx('No document requests.')}
              </div>
            ) : (
              <ul className="divide-y">
                {(data ?? []).map((r) => <RequestRow key={r.id} r={r} scope={scope} />)}
              </ul>
            )}
        </CardContent>
      </Card>
      {open && <CreateRequestDialog open={open} onOpenChange={setOpen} />}
    </div>
  );
}

function RequestRow({ r, scope }: { r: DmsRequest; scope: 'assigned' | 'sent' }) {
  const fulfil = useFulfilRequest();
  const cancel = useCancelRequest();
  const input = useRef<HTMLInputElement>(null);
  const onFiles = async (files: File[]) => {
    if (!files.length) return;
    try {
      await fulfil.mutateAsync({ id: r.id, files });
      notify.success(tx('Request fulfilled'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not upload the file')));
    }
  };
  return (
    <li className="flex flex-wrap items-center gap-3 p-3 text-sm">
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2 font-medium">
          {r.title}
          <Badge variant={r.status === 'open' ? (r.overdue ? 'destructive' : 'secondary') : 'outline'}>
            {r.status === 'open' && r.overdue ? tx('Overdue') : tx(r.status)}
          </Badge>
        </div>
        <div className="text-xs text-muted-foreground">
          {scope === 'assigned' ? tx('From {{n}}', { n: r.requestedByName || '—' }) : tx('To {{n}}', { n: r.requestedFromName || '—' })}
          {r.dueOn && ` · ${tx('Due {{d}}', { d: date(r.dueOn) })}`}
          {r.workspaceName && ` · ${r.workspaceName}`}
        </div>
        {r.note && <p className="mt-1 text-xs">{r.note}</p>}
      </div>
      {r.status === 'fulfilled' && r.fulfilledDocumentId && (
        <Button size="sm" variant="outline" asChild><Link to={`/dms?document=${r.fulfilledDocumentId}`}>{tx('Open document')}</Link></Button>
      )}
      {r.status === 'open' && scope === 'assigned' && (
        <>
          <Button size="sm" onClick={() => input.current?.click()} disabled={fulfil.isPending}>
            <Upload className="mr-1 h-4 w-4" />{tx('Upload')}
          </Button>
          <input ref={input} type="file" multiple hidden onChange={(e) => void onFiles(filesOf(e.target.files))} />
        </>
      )}
      {r.status === 'open' && scope === 'sent' && (
        <Button size="sm" variant="ghost" className="text-destructive"
          onClick={() => cancel.mutateAsync(r.id).then(() => notify.success(tx('Request cancelled')),
            (err) => notify.error(apiMessage(err, tx('Could not cancel'))))}>
          {tx('Cancel')}
        </Button>
      )}
    </li>
  );
}

function CreateRequestDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const { data: users } = useUsers({ page: 1, pageSize: 200 });
  const { data: workspaces } = useWorkspaces();
  const create = useCreateRequest();
  const [title, setTitle] = useState('');
  const [userId, setUserId] = useState('');
  const [workspaceId, setWorkspaceId] = useState('');
  const [dueOn, setDueOn] = useState('');
  const [note, setNote] = useState('');

  const submit = async () => {
    try {
      await create.mutateAsync({ title, requestedFromUserId: userId, workspaceId: workspaceId || undefined,
        dueOn: dueOn || undefined, note: note || undefined });
      notify.success(tx('Request sent'));
      onOpenChange(false);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not send the request')));
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Request a document')}</DialogTitle></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label className="grid gap-1">{tx('What do you need?')}<Input value={title} onChange={(e) => setTitle(e.target.value)} /></label>
          <label className="grid gap-1">{tx('From')}
            <SearchableSelect value={userId} onValueChange={setUserId} placeholder={tx('Select a user')}
              options={(users?.data ?? []).map((u) => ({ value: u.id, label: `${u.firstName} ${u.lastName ?? ''}`.trim(), hint: u.email }))} />
          </label>
          <label className="grid gap-1">{tx('Workspace')}
            <SearchableSelect value={workspaceId} onValueChange={setWorkspaceId} placeholder={tx('General')}
              options={(workspaces ?? []).filter((w) => w.access !== 'read').map((w) => ({ value: w.id, label: w.name }))} />
          </label>
          <label className="grid gap-1">{tx('Due date')}<Input type="date" value={dueOn} onChange={(e) => setDueOn(e.target.value)} /></label>
          <label className="grid gap-1">{tx('Note')}<Textarea rows={3} value={note} onChange={(e) => setNote(e.target.value)} /></label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!title.trim() || !userId || create.isPending}>{tx('Send request')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}


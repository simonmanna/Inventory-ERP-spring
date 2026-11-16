import { useMemo, useState } from 'react';
import { Archive, Building2, Check, Hash, Loader2, Lock, RefreshCw, Search } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import {
  useArchiveConversation,
  useBrowseChannels,
  useCreateChannel,
  useDirectory,
  useJoinConversation,
  useStartChat,
  useSyncDepartments,
  useUpdateConversation,
} from '@/features/communication/discuss-api';
import type { ConversationDetail, DirectoryUser, DiscussVisibility } from '@/features/communication/discuss-types';
import { Avatar } from '@/features/communication/discuss-ui';
import { relativeTime } from '@/features/communication/discuss-format';
import { useDiscussLive } from '@/features/communication/sse';
import { tx } from '@/lib/i18n/tx';

const inputCls =
  'w-full rounded-lg border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-1 focus:ring-ring';

/** Searchable multi-select of staff. */
export function UserPicker({
  users,
  selected,
  onChange,
  max,
}: {
  users: DirectoryUser[];
  selected: string[];
  onChange: (ids: string[]) => void;
  max?: number;
}) {
  const [q, setQ] = useState('');
  const online = useDiscussLive((s) => s.online);
  const shown = useMemo(() => {
    const term = q.trim().toLowerCase();
    return users
      .filter(
        (u) =>
          !term ||
          u.name.toLowerCase().includes(term) ||
          (u.email ?? '').toLowerCase().includes(term) ||
          (u.departmentName ?? '').toLowerCase().includes(term),
      )
      .slice(0, 100);
  }, [q, users]);
  const toggle = (id: string) => {
    if (selected.includes(id)) onChange(selected.filter((s) => s !== id));
    else if (!max || selected.length < max) onChange([...selected, id]);
  };
  return (
    <div>
      <div className="relative mb-1.5">
        <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-muted-foreground" />
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={tx('Search people or departments')} className={`${inputCls} pl-8`} />
      </div>
      <div className="max-h-56 overflow-y-auto rounded-lg border border-border">
        {shown.length === 0 && <div className="p-3 text-xs text-muted-foreground">{tx('No one found.')}</div>}
        {shown.map((u) => {
          const on = selected.includes(u.id);
          return (
            <button
              key={u.id}
              type="button"
              onClick={() => toggle(u.id)}
              className={`flex w-full items-center gap-2 border-b border-border px-3 py-1.5 text-left text-sm last:border-0 hover:bg-accent ${on ? 'bg-primary/5' : ''}`}
            >
              <Avatar id={u.id} name={u.name} initials={u.initials} size="sm" online={!!online[u.id]} />
              <div className="min-w-0 flex-1">
                <div className="truncate">{u.name}</div>
                <div className="truncate text-[11px] text-muted-foreground">{u.departmentName ?? u.email}</div>
              </div>
              {on && <Check className="h-4 w-4 text-primary" />}
            </button>
          );
        })}
      </div>
    </div>
  );
}

export function NewChannelDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const { data: dir } = useDirectory();
  const create = useCreateChannel();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [visibility, setVisibility] = useState<DiscussVisibility>('public');
  const [members, setMembers] = useState<string[]>([]);

  const submit = () =>
    create.mutate(
      {
        name: name.trim().replace(/^#/, ''),
        description: description.trim() || undefined,
        visibility,
        memberIds: members.length ? members : undefined,
      },
      {
        onSuccess: (c) => {
          onCreated(c.id);
          onClose();
        },
      },
    );

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{tx('Create a channel')}</DialogTitle>
          <DialogDescription>{tx('Channels are where a team talks about a topic, a project or a department.')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div>
            <label className="mb-1 block text-xs font-medium">{tx('Name')}</label>
            <div className="relative">
              <Hash className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
              <input value={name} onChange={(e) => setName(e.target.value)} maxLength={80} placeholder={tx('e.g. warehouse-team')} className={`${inputCls} pl-8`} autoFocus />
            </div>
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium">{tx('Description (optional)')}</label>
            <textarea value={description} onChange={(e) => setDescription(e.target.value)} maxLength={500} rows={2} className={`${inputCls} resize-none`} />
          </div>
          <div className="grid grid-cols-2 gap-2">
            {(['public', 'private'] as const).map((v) => (
              <button
                key={v}
                type="button"
                onClick={() => setVisibility(v)}
                className={`rounded-lg border p-2 text-left text-xs ${visibility === v ? 'border-primary bg-primary/5' : 'border-border hover:bg-accent'}`}
              >
                <div className="flex items-center gap-1.5 font-medium">
                  {v === 'public' ? <Hash className="h-3.5 w-3.5" /> : <Lock className="h-3.5 w-3.5" />}
                  {v === 'public' ? tx('Public') : tx('Private')}
                </div>
                <div className="mt-0.5 text-muted-foreground">
                  {v === 'public' ? tx('Anyone can find and join') : tx('Only invited members')}
                </div>
              </button>
            ))}
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium">{tx('Members')}</label>
            <UserPicker users={dir?.users ?? []} selected={members} onChange={setMembers} />
          </div>
          <button
            type="button"
            onClick={submit}
            disabled={!name.trim() || create.isPending}
            className="w-full rounded-lg bg-primary py-2 text-sm font-medium text-primary-foreground disabled:opacity-40"
          >
            {create.isPending ? tx('Creating…') : tx('Create channel')}
          </button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function NewChatDialog({
  meId,
  onClose,
  onCreated,
}: {
  meId?: string;
  onClose: () => void;
  onCreated: (id: string) => void;
}) {
  const { data: dir } = useDirectory();
  const start = useStartChat();
  const [selected, setSelected] = useState<string[]>([]);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{tx('New chat')}</DialogTitle>
          <DialogDescription>{tx('Pick one person for a direct message, or several for a group chat.')}</DialogDescription>
        </DialogHeader>
        <UserPicker users={(dir?.users ?? []).filter((u) => u.id !== meId)} selected={selected} onChange={setSelected} max={20} />
        <button
          type="button"
          disabled={selected.length === 0 || start.isPending}
          onClick={() =>
            start.mutate(selected, {
              onSuccess: (c) => {
                onCreated(c.id);
                onClose();
              },
            })
          }
          className="w-full rounded-lg bg-primary py-2 text-sm font-medium text-primary-foreground disabled:opacity-40"
        >
          {selected.length > 1 ? tx('Start group chat ({{count}})', { count: selected.length }) : tx('Start chat')}
        </button>
      </DialogContent>
    </Dialog>
  );
}

export function BrowseChannelsDialog({
  canModerate,
  onClose,
  onOpen,
}: {
  canModerate: boolean;
  onClose: () => void;
  onOpen: (id: string) => void;
}) {
  const [archived, setArchived] = useState(false);
  const [q, setQ] = useState('');
  const { data, isLoading } = useBrowseChannels(archived);
  const join = useJoinConversation();
  const unarchive = useArchiveConversation();
  const list = (data ?? []).filter(
    (c) => !q.trim() || c.name.toLowerCase().includes(q.trim().toLowerCase()) || (c.description ?? '').toLowerCase().includes(q.trim().toLowerCase()),
  );
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Browse channels')}</DialogTitle>
        </DialogHeader>
        <div className="flex items-center gap-2">
          <div className="relative flex-1">
            <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-muted-foreground" />
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={tx('Search channels')} className={`${inputCls} pl-8`} />
          </div>
          {canModerate && (
            <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <input type="checkbox" checked={archived} onChange={(e) => setArchived(e.target.checked)} />
              {tx('Archived')}
            </label>
          )}
        </div>
        <div className="max-h-[50vh] space-y-1 overflow-y-auto">
          {isLoading && <Loader2 className="mx-auto h-5 w-5 animate-spin text-muted-foreground" />}
          {!isLoading && list.length === 0 && <div className="p-4 text-center text-sm text-muted-foreground">{tx('No channels found.')}</div>}
          {list.map((c) => (
            <div key={c.id} className="flex items-center gap-3 rounded-lg border border-border p-2.5">
              <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-muted">
                {c.archived ? <Archive className="h-4 w-4" /> : c.departmentId ? <Building2 className="h-4 w-4" /> : c.visibility === 'private' ? <Lock className="h-4 w-4" /> : <Hash className="h-4 w-4" />}
              </div>
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium">{c.name}</div>
                <div className="truncate text-xs text-muted-foreground">
                  {tx('{{count}} members', { count: c.memberCount })}
                  {c.lastActivityAt ? ` · ${relativeTime(c.lastActivityAt)}` : ''}
                  {c.description ? ` · ${c.description}` : ''}
                </div>
              </div>
              {c.archived ? (
                <button
                  type="button"
                  onClick={() => unarchive.mutate({ id: c.id, archived: false })}
                  className="rounded-md border border-border px-2.5 py-1 text-xs hover:bg-accent"
                >
                  {tx('Unarchive')}
                </button>
              ) : c.joined ? (
                <button
                  type="button"
                  onClick={() => {
                    onOpen(c.id);
                    onClose();
                  }}
                  className="rounded-md border border-border px-2.5 py-1 text-xs hover:bg-accent"
                >
                  {tx('Open')}
                </button>
              ) : (
                <button
                  type="button"
                  disabled={join.isPending}
                  onClick={() =>
                    join.mutate(c.id, {
                      onSuccess: () => {
                        onOpen(c.id);
                        onClose();
                      },
                    })
                  }
                  className="rounded-md bg-primary px-2.5 py-1 text-xs font-medium text-primary-foreground disabled:opacity-50"
                >
                  {tx('Join')}
                </button>
              )}
            </div>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function DepartmentsDialog({
  canSync,
  onClose,
  onOpen,
}: {
  canSync: boolean;
  onClose: () => void;
  onOpen: (id: string) => void;
}) {
  const { data, isLoading } = useDirectory();
  const join = useJoinConversation();
  const sync = useSyncDepartments();
  const departments = data?.departments ?? [];
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Departments')}</DialogTitle>
          <DialogDescription>{tx('Each department has a channel. Open it to talk to the whole team.')}</DialogDescription>
        </DialogHeader>
        {canSync && (
          <button
            type="button"
            onClick={() => sync.mutate()}
            disabled={sync.isPending}
            className="flex w-fit items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-xs hover:bg-accent disabled:opacity-50"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${sync.isPending ? 'animate-spin' : ''}`} /> {tx('Sync departments from HR')}
          </button>
        )}
        <div className="max-h-[50vh] space-y-1 overflow-y-auto">
          {isLoading && <Loader2 className="mx-auto h-5 w-5 animate-spin text-muted-foreground" />}
          {!isLoading && departments.length === 0 && (
            <div className="p-4 text-center text-sm text-muted-foreground">{tx('No departments yet. Departments come from HR.')}</div>
          )}
          {departments.map((d) => (
            <div key={d.id} className="flex items-center gap-3 rounded-lg border border-border p-2.5">
              <Building2 className="h-4 w-4 shrink-0 text-muted-foreground" />
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium">
                  {d.name} {d.code && <span className="text-xs font-normal text-muted-foreground">({d.code})</span>}
                </div>
                <div className="text-xs text-muted-foreground">{tx('{{count}} members', { count: d.memberCount })}</div>
              </div>
              {d.channelId ? (
                <button
                  type="button"
                  disabled={join.isPending}
                  onClick={() => {
                    if (d.joined) {
                      onOpen(d.channelId!);
                      onClose();
                    } else {
                      join.mutate(d.channelId!, {
                        onSuccess: () => {
                          onOpen(d.channelId!);
                          onClose();
                        },
                      });
                    }
                  }}
                  className={`rounded-md px-2.5 py-1 text-xs ${d.joined ? 'border border-border hover:bg-accent' : 'bg-primary font-medium text-primary-foreground'}`}
                >
                  {d.joined ? tx('Open') : tx('Join')}
                </button>
              ) : (
                <span className="text-xs text-muted-foreground">{tx('No channel yet')}</span>
              )}
            </div>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function EditConversationDialog({ detail, onClose }: { detail: ConversationDetail; onClose: () => void }) {
  const update = useUpdateConversation();
  const [name, setName] = useState(detail.kind === 'channel' || detail.kind === 'group' ? detail.name : '');
  const [description, setDescription] = useState(detail.description ?? '');
  const [visibility, setVisibility] = useState<DiscussVisibility>(detail.visibility);
  const isChannel = detail.kind === 'channel';
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{isChannel ? tx('Edit channel') : tx('Rename chat')}</DialogTitle>
        </DialogHeader>
        <div className="space-y-3">
          <div>
            <label className="mb-1 block text-xs font-medium">{tx('Name')}</label>
            <input value={name} onChange={(e) => setName(e.target.value)} maxLength={80} className={inputCls} autoFocus />
          </div>
          {isChannel && (
            <>
              <div>
                <label className="mb-1 block text-xs font-medium">{tx('Description')}</label>
                <textarea value={description} onChange={(e) => setDescription(e.target.value)} maxLength={500} rows={3} className={`${inputCls} resize-none`} />
              </div>
              {detail.canManage && !detail.autoJoin && (
                <div className="flex gap-2">
                  {(['public', 'private'] as const).map((v) => (
                    <button
                      key={v}
                      type="button"
                      onClick={() => setVisibility(v)}
                      className={`flex flex-1 items-center justify-center gap-1.5 rounded-lg border py-1.5 text-xs ${visibility === v ? 'border-primary bg-primary/5' : 'border-border'}`}
                    >
                      {v === 'public' ? <Hash className="h-3.5 w-3.5" /> : <Lock className="h-3.5 w-3.5" />}
                      {v === 'public' ? tx('Public') : tx('Private')}
                    </button>
                  ))}
                </div>
              )}
            </>
          )}
          <button
            type="button"
            disabled={!name.trim() || update.isPending}
            onClick={() =>
              update.mutate(
                {
                  id: detail.id,
                  name: name.trim(),
                  ...(isChannel ? { description: description.trim(), ...(detail.canManage ? { visibility } : {}) } : {}),
                },
                { onSuccess: onClose },
              )
            }
            className="w-full rounded-lg bg-primary py-2 text-sm font-medium text-primary-foreground disabled:opacity-40"
          >
            {tx('Save')}
          </button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

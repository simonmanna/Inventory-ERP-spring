import { useMemo, useState } from 'react';
import { Crown, Shield, UserMinus, UserPlus, X } from 'lucide-react';
import { useAddMembers, useRemoveMember } from '@/features/communication/discuss-api';
import type { ConversationDetail, DirectoryUser } from '@/features/communication/discuss-types';
import { Avatar } from '@/features/communication/discuss-ui';
import { useDiscussLive } from '@/features/communication/sse';
import { tx } from '@/lib/i18n/tx';
import { UserPicker } from './dialogs';

/** Right-hand member list: presence, roles, add (any member of a channel/group), remove (moderators). */
export function MemberPanel({
  detail,
  users,
  meId,
  onClose,
}: {
  detail: ConversationDetail;
  users: DirectoryUser[];
  meId?: string;
  onClose: () => void;
}) {
  const online = useDiscussLive((s) => s.online);
  const add = useAddMembers();
  const remove = useRemoveMember();
  const [adding, setAdding] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);

  const memberIds = useMemo(() => new Set(detail.members.map((m) => m.userId)), [detail.members]);
  const canAdd = !!detail.role && detail.kind !== 'direct' && !detail.archived;
  const sorted = useMemo(
    () =>
      [...detail.members].sort((a, b) => {
        const oa = online[a.userId] ? 0 : 1;
        const ob = online[b.userId] ? 0 : 1;
        return oa - ob || a.name.localeCompare(b.name);
      }),
    [detail.members, online],
  );

  return (
    <aside className="absolute inset-y-0 right-0 z-20 flex w-72 shrink-0 flex-col border-l border-border bg-background shadow-xl lg:static lg:shadow-none">
      <div className="flex h-14 items-center justify-between border-b border-border px-4">
        <div className="font-semibold">
          {tx('Members')} <span className="text-sm font-normal text-muted-foreground">({detail.memberCount})</span>
        </div>
        <button type="button" onClick={onClose} className="rounded p-1 hover:bg-accent" aria-label={tx('Close')}>
          <X className="h-4 w-4" />
        </button>
      </div>

      {canAdd && (
        <div className="border-b border-border p-3">
          {adding ? (
            <div className="space-y-2">
              <UserPicker users={users.filter((u) => !memberIds.has(u.id))} selected={selected} onChange={setSelected} />
              <div className="flex gap-2">
                <button
                  type="button"
                  disabled={selected.length === 0 || add.isPending}
                  onClick={() =>
                    add.mutate(
                      { id: detail.id, userIds: selected },
                      {
                        onSuccess: () => {
                          setSelected([]);
                          setAdding(false);
                        },
                      },
                    )
                  }
                  className="flex-1 rounded-md bg-primary py-1.5 text-xs font-medium text-primary-foreground disabled:opacity-40"
                >
                  {tx('Add {{count}}', { count: selected.length })}
                </button>
                <button type="button" onClick={() => setAdding(false)} className="rounded-md border border-border px-3 text-xs hover:bg-accent">
                  {tx('Cancel')}
                </button>
              </div>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => setAdding(true)}
              className="flex w-full items-center justify-center gap-1.5 rounded-md border border-dashed border-border py-1.5 text-xs hover:bg-accent"
            >
              <UserPlus className="h-3.5 w-3.5" /> {tx('Add members')}
            </button>
          )}
        </div>
      )}

      <div className="flex-1 overflow-y-auto py-1">
        {sorted.map((m) => (
          <div key={m.userId} className="group flex items-center gap-2 px-4 py-1.5">
            <Avatar id={m.userId} name={m.name} initials={m.initials} size="sm" online={!!online[m.userId]} />
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-1 truncate text-sm">
                {m.name}
                {m.userId === meId && <span className="text-xs text-muted-foreground">({tx('you')})</span>}
                {m.role === 'owner' && <Crown className="h-3 w-3 text-amber-500" aria-label={tx('Owner')} />}
                {m.role === 'admin' && <Shield className="h-3 w-3 text-sky-500" aria-label={tx('Admin')} />}
              </div>
              {m.addedVia === 'department' && <div className="text-[10px] text-muted-foreground">{tx('Via department')}</div>}
            </div>
            {detail.canManage && m.userId !== meId && detail.kind !== 'direct' && (
              <button
                type="button"
                onClick={() => {
                  if (window.confirm(tx('Remove {{name}} from this conversation?', { name: m.name }))) {
                    remove.mutate({ id: detail.id, userId: m.userId });
                  }
                }}
                className="invisible rounded p-1 text-muted-foreground hover:bg-accent hover:text-destructive group-hover:visible"
                aria-label={tx('Remove member')}
              >
                <UserMinus className="h-3.5 w-3.5" />
              </button>
            )}
          </div>
        ))}
      </div>
    </aside>
  );
}

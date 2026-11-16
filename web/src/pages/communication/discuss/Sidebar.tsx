import { useMemo, useState, type ReactNode } from 'react';
import { BellOff, BellRing, Building2, Compass, Hash, Inbox, Lock, Pin, Plus, Search, Star, Users } from 'lucide-react';
import type { ConversationItem, SidebarResponse } from '@/features/communication/discuss-types';
import { Avatar } from '@/features/communication/discuss-ui';
import {
  desktopNotificationsSupported,
  requestDesktopNotifications,
  useIsOnline,
} from '@/features/communication/sse';
import { tx } from '@/lib/i18n/tx';

export type DiscussView =
  | { type: 'conversation'; id: string }
  | { type: 'inbox' }
  | { type: 'starred' }
  | { type: 'search' };

interface Props {
  data?: SidebarResponse;
  loading: boolean;
  view: DiscussView | null;
  search: string;
  onSearch: (q: string) => void;
  onSelect: (v: DiscussView) => void;
  onNewChannel: () => void;
  onBrowse: () => void;
  onNewChat: () => void;
  onDepartments: () => void;
}

function sortItems(items: ConversationItem[]): ConversationItem[] {
  return [...items].sort((a, b) => {
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
    const ta = new Date(a.lastMessage?.occurredAt ?? a.updatedAt).getTime();
    const tb = new Date(b.lastMessage?.occurredAt ?? b.updatedAt).getTime();
    return tb - ta;
  });
}

export function DiscussSidebar({
  data,
  loading,
  view,
  search,
  onSearch,
  onSelect,
  onNewChannel,
  onBrowse,
  onNewChat,
  onDepartments,
}: Props) {
  const [draft, setDraft] = useState(search);
  const [notifPerm, setNotifPerm] = useState<string>(
    desktopNotificationsSupported() ? Notification.permission : 'unsupported',
  );
  const channels = useMemo(() => sortItems(data?.channels ?? []), [data]);
  const chats = useMemo(() => sortItems(data?.chats ?? []), [data]);
  const activeId = view?.type === 'conversation' ? view.id : undefined;

  return (
    <aside className="flex h-full w-full flex-col border-r border-border bg-muted/20 md:w-72 md:shrink-0">
      <div className="border-b border-border p-3">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            onSearch(draft);
          }}
          className="relative"
        >
          <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-muted-foreground" />
          <input
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder={tx('Search messages…')}
            className="w-full rounded-lg border border-input bg-background py-1.5 pl-8 pr-2 text-sm outline-none focus:ring-1 focus:ring-ring"
          />
        </form>
      </div>

      <div className="flex-1 overflow-y-auto py-2">
        <NavRow
          icon={<Inbox className="h-4 w-4" />}
          label={tx('Inbox')}
          count={data?.counters.inbox ?? 0}
          active={view?.type === 'inbox'}
          onClick={() => onSelect({ type: 'inbox' })}
        />
        <NavRow
          icon={<Star className="h-4 w-4" />}
          label={tx('Starred')}
          count={data?.counters.starred ?? 0}
          subtle
          active={view?.type === 'starred'}
          onClick={() => onSelect({ type: 'starred' })}
        />
        <NavRow icon={<Building2 className="h-4 w-4" />} label={tx('Departments')} onClick={onDepartments} />

        <SectionHeader
          label={tx('Channels')}
          actions={
            <>
              <IconButton label={tx('Browse channels')} onClick={onBrowse}>
                <Compass className="h-3.5 w-3.5" />
              </IconButton>
              <IconButton label={tx('Create a channel')} onClick={onNewChannel}>
                <Plus className="h-3.5 w-3.5" />
              </IconButton>
            </>
          }
        />
        {loading && <div className="px-4 py-1 text-xs text-muted-foreground">{tx('Loading…')}</div>}
        {!loading && channels.length === 0 && (
          <button type="button" onClick={onBrowse} className="px-4 py-1 text-left text-xs text-primary hover:underline">
            {tx('Browse channels to join')}
          </button>
        )}
        {channels.map((c) => (
          <ConversationRow key={c.id} item={c} active={c.id === activeId} onClick={() => onSelect({ type: 'conversation', id: c.id })} />
        ))}

        <SectionHeader
          label={tx('Direct messages')}
          actions={
            <IconButton label={tx('New chat')} onClick={onNewChat}>
              <Plus className="h-3.5 w-3.5" />
            </IconButton>
          }
        />
        {!loading && chats.length === 0 && (
          <button type="button" onClick={onNewChat} className="px-4 py-1 text-left text-xs text-primary hover:underline">
            {tx('Start a conversation')}
          </button>
        )}
        {chats.map((c) => (
          <ConversationRow key={c.id} item={c} active={c.id === activeId} onClick={() => onSelect({ type: 'conversation', id: c.id })} />
        ))}
      </div>

      {notifPerm === 'default' && (
        <button
          type="button"
          onClick={async () => setNotifPerm(await requestDesktopNotifications())}
          className="flex items-center gap-2 border-t border-border px-4 py-2.5 text-left text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
        >
          <BellRing className="h-3.5 w-3.5" /> {tx('Enable desktop notifications')}
        </button>
      )}
    </aside>
  );
}

function NavRow({
  icon,
  label,
  count = 0,
  active,
  subtle,
  onClick,
}: {
  icon: ReactNode;
  label: string;
  count?: number;
  active?: boolean;
  subtle?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex w-full items-center gap-2.5 px-4 py-1.5 text-sm hover:bg-accent ${active ? 'bg-accent font-medium' : ''}`}
    >
      <span className="text-muted-foreground">{icon}</span>
      <span className="flex-1 text-left">{label}</span>
      {count > 0 && (
        <span className={`rounded-full px-1.5 text-[10px] ${subtle ? 'bg-muted text-muted-foreground' : 'bg-primary text-primary-foreground'}`}>
          {count}
        </span>
      )}
    </button>
  );
}

function SectionHeader({ label, actions }: { label: string; actions: ReactNode }) {
  return (
    <div className="mt-4 flex items-center justify-between px-4 pb-1">
      <span className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{label}</span>
      <span className="flex items-center gap-0.5">{actions}</span>
    </div>
  );
}

function IconButton({ label, onClick, children }: { label: string; onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" onClick={onClick} title={label} aria-label={label} className="rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground">
      {children}
    </button>
  );
}

function ConversationRow({ item, active, onClick }: { item: ConversationItem; active: boolean; onClick: () => void }) {
  const online = useIsOnline(item.directUserId);
  const bold = item.badge > 0;
  let icon: ReactNode;
  if (item.kind === 'direct' && item.directUserId) {
    icon = <Avatar id={item.directUserId} name={item.name} size="sm" online={online} />;
  } else if (item.kind === 'group') {
    icon = (
      <span className="flex h-6 w-6 items-center justify-center rounded-full bg-muted">
        <Users className="h-3.5 w-3.5 text-muted-foreground" />
      </span>
    );
  } else if (item.departmentId) {
    icon = <Building2 className="mx-1 h-4 w-4 text-muted-foreground" />;
  } else if (item.visibility === 'private') {
    icon = <Lock className="mx-1 h-4 w-4 text-muted-foreground" />;
  } else {
    icon = <Hash className="mx-1 h-4 w-4 text-muted-foreground" />;
  }
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex w-full items-center gap-2 px-3 py-1.5 text-sm hover:bg-accent ${active ? 'bg-accent' : ''} ${item.muted ? 'opacity-60' : ''}`}
      title={item.lastMessage ? `${item.lastMessage.authorName ?? ''}: ${item.lastMessage.body}` : item.name}
    >
      {icon}
      <span className={`min-w-0 flex-1 truncate text-left ${bold ? 'font-semibold text-foreground' : 'text-foreground/80'}`}>
        {item.name}
      </span>
      {item.pinned && <Pin className="h-3 w-3 shrink-0 text-muted-foreground" />}
      {item.muted && <BellOff className="h-3 w-3 shrink-0 text-muted-foreground" />}
      {item.badge > 0 && (
        <span className={`shrink-0 rounded-full px-1.5 text-[10px] ${item.mentionUnread > 0 ? 'bg-amber-500 text-white' : 'bg-primary text-primary-foreground'}`}>
          {item.mentionUnread > 0 && item.badge === item.mentionUnread ? `@${item.badge}` : item.badge}
        </span>
      )}
    </button>
  );
}

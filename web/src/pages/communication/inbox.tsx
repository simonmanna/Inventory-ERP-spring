import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { MessagesSquare } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { useAuthStore } from '@/stores/auth.store';
import { useDirectory, usePresence, useSidebar } from '@/features/communication/discuss-api';
import { useDiscussLive, useDiscussStream } from '@/features/communication/sse';
import { tx } from '@/lib/i18n/tx';
import { DiscussSidebar, type DiscussView } from './discuss/Sidebar';
import { Thread } from './discuss/Thread';
import { InboxView, SearchView, StarredView } from './discuss/views';
import { BrowseChannelsDialog, DepartmentsDialog, NewChannelDialog, NewChatDialog } from './discuss/dialogs';

type Dialog = 'channel' | 'browse' | 'chat' | 'departments' | null;

/**
 * Discuss (ADR-S064): Odoo-style staff messaging — inbox of mentions and followed records, starred messages,
 * channels (public, private, department), direct and group chats, live over `/communication/stream`.
 * URL: `?c=<conversationId>&m=<messageId>`, `?view=inbox|starred`, `?q=<search>&in=<conversationId>`.
 */
export default function CommunicationInboxPage() {
  const me = useAuthStore((s) => s.user);
  const canModerate = useAuthStore((s) => s.permissions.includes(PERMISSIONS.communication.channelManage));
  const [params, setParams] = useSearchParams();
  const [dialog, setDialog] = useState<Dialog>(null);

  const conversationParam = params.get('c') ?? undefined;
  const highlight = params.get('m') ?? undefined;
  const viewParam = params.get('view');
  const query = params.get('q') ?? '';
  const searchIn = params.get('in') ?? undefined;

  const view: DiscussView | null = conversationParam
    ? { type: 'conversation', id: conversationParam }
    : viewParam === 'inbox'
      ? { type: 'inbox' }
      : viewParam === 'starred'
        ? { type: 'starred' }
        : params.has('q')
          ? { type: 'search' }
          : null;

  const { data: sidebar, isLoading } = useSidebar();
  const { data: directory } = useDirectory();
  const { data: presence } = usePresence();
  useDiscussStream(view?.type === 'conversation' ? view.id : undefined);

  useEffect(() => {
    if (presence) useDiscussLive.getState().seedOnline(presence.online);
  }, [presence]);

  const users = useMemo(() => directory?.users ?? [], [directory]);

  const go = useCallback(
    (next: Record<string, string | undefined>) => {
      const p = new URLSearchParams();
      for (const [k, v] of Object.entries(next)) if (v !== undefined) p.set(k, v);
      setParams(p);
    },
    [setParams],
  );

  const select = (v: DiscussView) => {
    if (v.type === 'conversation') go({ c: v.id });
    else if (v.type === 'search') go({ q: query });
    else go({ view: v.type });
  };
  const openConversation = (id: string, messageId?: string) => go({ c: id, m: messageId });

  // Desktop: open the inbox by default so the page is never empty.
  const hasView = view !== null;
  useEffect(() => {
    if (!hasView && window.matchMedia('(min-width: 768px)').matches) go({ view: 'inbox' });
  }, [hasView, go]);

  const allItems = [...(sidebar?.channels ?? []), ...(sidebar?.chats ?? [])];
  const scopeName = searchIn ? allItems.find((c) => c.id === searchIn)?.name ?? tx('this conversation') : undefined;
  const showMain = !!view;

  return (
    <div className="relative flex h-[calc(100vh-3rem)] overflow-hidden">
      <div className={`${showMain ? 'hidden md:flex' : 'flex'} w-full md:w-auto`}>
        <DiscussSidebar
          data={sidebar}
          loading={isLoading}
          view={view}
          search={query}
          onSearch={(q) => go({ q, in: searchIn })}
          onSelect={select}
          onNewChannel={() => setDialog('channel')}
          onBrowse={() => setDialog('browse')}
          onNewChat={() => setDialog('chat')}
          onDepartments={() => setDialog('departments')}
        />
      </div>

      <main className={`${showMain ? 'flex' : 'hidden md:flex'} relative min-w-0 flex-1`}>
        {view?.type === 'conversation' && (
          <Thread
            key={view.id}
            conversationId={view.id}
            meId={me?.id}
            users={users}
            highlightMessageId={highlight}
            onBack={() => go({})}
            onSearchIn={(id) => go({ q: '', in: id })}
            onLeft={() => go({ view: 'inbox' })}
          />
        )}
        {view?.type === 'inbox' && <InboxView meId={me?.id} onOpen={openConversation} onBack={() => go({})} />}
        {view?.type === 'starred' && <StarredView meId={me?.id} onOpen={openConversation} onBack={() => go({})} />}
        {view?.type === 'search' && (
          <div className="flex min-w-0 flex-1 flex-col">
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const q = new FormData(e.currentTarget).get('q');
                go({ q: String(q ?? ''), in: searchIn });
              }}
              className="border-b border-border p-3 md:hidden"
            >
              <input
                name="q"
                defaultValue={query}
                placeholder={tx('Search messages…')}
                className="w-full rounded-lg border border-input bg-background px-3 py-1.5 text-sm outline-none"
              />
            </form>
            <SearchView
              query={query}
              conversation={searchIn ? { id: searchIn, name: scopeName ?? '' } : undefined}
              meId={me?.id}
              onOpen={openConversation}
              onBack={() => go({})}
              onClearScope={() => go({ q: query })}
            />
          </div>
        )}
        {!view && (
          <div className="flex flex-1 flex-col items-center justify-center gap-2 text-sm text-muted-foreground">
            <MessagesSquare className="h-8 w-8 opacity-40" />
            {tx('Select a conversation')}
          </div>
        )}
      </main>

      {dialog === 'channel' && <NewChannelDialog onClose={() => setDialog(null)} onCreated={(id) => openConversation(id)} />}
      {dialog === 'chat' && <NewChatDialog meId={me?.id} onClose={() => setDialog(null)} onCreated={(id) => openConversation(id)} />}
      {dialog === 'browse' && (
        <BrowseChannelsDialog canModerate={canModerate} onClose={() => setDialog(null)} onOpen={(id) => openConversation(id)} />
      )}
      {dialog === 'departments' && (
        <DepartmentsDialog canSync={canModerate} onClose={() => setDialog(null)} onOpen={(id) => openConversation(id)} />
      )}
    </div>
  );
}

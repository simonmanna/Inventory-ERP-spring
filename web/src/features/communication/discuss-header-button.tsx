import { Link } from 'react-router-dom';
import { MessagesSquare } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import { useDiscussCounters } from './discuss-api';

/**
 * App-header shortcut to Discuss with the unread + inbox badge. Hidden without `communication:conversation:read`
 * and whenever the counters call fails (communication module off).
 */
export function DiscussHeaderButton() {
  const allowed = useAuthStore((s) => s.permissions.includes(PERMISSIONS.communication.conversationRead));
  const { data, isError } = useDiscussCounters(allowed);
  if (!allowed || isError || !data) return null;
  const count = data.unread + data.inbox;
  return (
    <Link
      to="/communication"
      className="relative inline-flex h-9 w-9 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground"
      aria-label={count > 0 ? tx('Discuss ({{count}} unread)', { count }) : tx('Discuss')}
      title={tx('Discuss')}
    >
      <MessagesSquare className="h-5 w-5" />
      {count > 0 && (
        <span className="absolute right-0.5 top-0.5 min-w-[1.1rem] rounded-full bg-primary px-1 text-center text-[10px] font-semibold leading-[1.1rem] text-primary-foreground">
          {count > 99 ? '99+' : count}
        </span>
      )}
    </Link>
  );
}

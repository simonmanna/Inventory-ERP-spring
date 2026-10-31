import { Suspense, type ReactNode } from 'react';
import { Loader2 } from 'lucide-react';
import { tx } from '@/lib/i18n/tx';

/** Shown while a lazily loaded screen's code arrives (pages are split into their own chunks). */
export function PageLoading() {
  return (
    <div className="flex h-40 items-center justify-center text-muted-foreground" role="status">
      <Loader2 className="mr-2 h-5 w-5 animate-spin" aria-hidden />
      {tx('Loading…')}
    </div>
  );
}

/** Suspense boundary for routed pages: keeps the surrounding shell on screen while a page loads. */
export function PageSuspense({ children }: { children: ReactNode }) {
  return <Suspense fallback={<PageLoading />}>{children}</Suspense>;
}

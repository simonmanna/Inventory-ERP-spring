/**
 * ThemePicker — header dropdown for the app's colour themes (Luxury Sky / Light Charcoal), styled like the user
 * menu: the trigger shows the active theme's swatch and name, the menu lists every theme with a tick on the active one.
 *
 * Switching reveals the new theme as a circle growing from the trigger (View Transitions API); browsers without
 * it — or users who prefer reduced motion — get a short colour crossfade.
 */
import { useRef } from 'react';
import { flushSync } from 'react-dom';
import { Check, ChevronDown, Palette } from 'lucide-react';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';
import { useSidebarTheme, SIDEBAR_THEMES, type SidebarThemeKey } from '@/lib/sidebar-theme';
import { tx } from '@/lib/i18n/tx';

const ORDER: SidebarThemeKey[] = ['luxurySky', 'lightCharcoal'];

type ViewTransitionDoc = Document & {
  startViewTransition?: (cb: () => void) => { ready: Promise<void> };
};

function Swatch({ color, className }: { color: string; className?: string }) {
  return (
    <span
      aria-hidden
      className={cn('h-4 w-4 shrink-0 rounded-full ring-1 ring-black/10', className)}
      style={{ background: color, boxShadow: 'inset 0 1px 1px rgba(255,255,255,0.45)' }}
    />
  );
}

export function ThemePicker({ compact = false }: { compact?: boolean }) {
  const { key, setKey } = useSidebarTheme();
  const busy = useRef(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const current = SIDEBAR_THEMES[key];

  const choose = (next: SidebarThemeKey) => {
    if (next === key || busy.current) return;
    const doc = document as ViewTransitionDoc;
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    if (!doc.startViewTransition || reduce || !trigger.current) {
      const root = document.documentElement;
      root.classList.add('theme-anim');
      setKey(next);
      window.setTimeout(() => root.classList.remove('theme-anim'), 380);
      return;
    }

    const rect = trigger.current.getBoundingClientRect();
    const x = rect.left + rect.width / 2;
    const y = rect.top + rect.height / 2;
    const r = Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y));

    busy.current = true;
    const vt = doc.startViewTransition(() => flushSync(() => setKey(next)));
    vt.ready
      .then(() =>
        document.documentElement.animate(
          { clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${r}px at ${x}px ${y}px)`] },
          { duration: 560, easing: 'cubic-bezier(0.77, 0, 0.175, 1)', pseudoElement: '::view-transition-new(root)' },
        ).finished,
      )
      .catch(() => undefined)
      .finally(() => { busy.current = false; });
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          ref={trigger}
          type="button"
          aria-label={tx('Color theme')}
          title={current.label}
          className="flex items-center gap-2 rounded-full px-1.5 py-1 outline-none transition-colors hover:bg-muted/60 focus-visible:ring-2 focus-visible:ring-ring"
        >
          <span className="flex h-8 w-8 items-center justify-center rounded-full bg-primary/10" aria-hidden="true">
            <Swatch color={current.swatch} />
          </span>
          <span className={cn('text-sm font-medium text-foreground', compact ? 'sr-only' : 'hidden lg:inline')}>
            {current.label}
          </span>
          <ChevronDown className="hidden h-4 w-4 text-muted-foreground sm:inline" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        <DropdownMenuLabel className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <Palette className="h-4 w-4 text-muted-foreground" />
          {tx('Theme')}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        {ORDER.map((k) => {
          const t = SIDEBAR_THEMES[k];
          const active = k === key;
          return (
            <DropdownMenuItem
              key={k}
              onSelect={() => choose(k)}
              className={cn('cursor-pointer gap-2', active && 'font-semibold')}
              aria-checked={active}
              role="menuitemradio"
            >
              <Swatch color={t.swatch} />
              <span className="flex-1">{t.label}</span>
              {active && <Check className="h-4 w-4 text-primary" />}
            </DropdownMenuItem>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

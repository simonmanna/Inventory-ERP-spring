import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { PageSuspense } from '@/components/page-loading';
import { LogOut } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useAuthStore } from '@/stores/auth.store';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';

/** Customer portal chrome: no staff sidebar, a simple top bar that also works on a phone (PORTAL-1). */
export function PortalShell() {
  const navigate = useNavigate();
  const clear = useAuthStore((s) => s.clear);
  const organization = useAuthStore((s) => s.organization);
  const links = [
    { to: '/portal', end: true, label: tx('Home') },
    { to: '/portal/orders', end: false, label: tx('Orders') },
    { to: '/portal/deliveries', end: false, label: tx('Deliveries') },
    { to: '/portal/invoices', end: false, label: tx('Invoices') },
    { to: '/portal/statement', end: false, label: tx('Statement') },
  ];
  const signOut = () => {
    clear();
    navigate('/login');
  };
  return (
    <div className="min-h-screen bg-background">
      <header className="border-b bg-card">
        <div className="mx-auto flex max-w-4xl items-center justify-between gap-2 px-3 py-2">
          <span className="truncate font-semibold">{organization?.name ?? tx('Customer portal')}</span>
          <Button variant="ghost" size="sm" onClick={signOut}><LogOut className="mr-1.5 h-4 w-4" /> {tx('Sign out')}</Button>
        </div>
        <nav className="mx-auto flex max-w-4xl gap-1 overflow-x-auto px-2" aria-label={tx('Customer portal')}>
          {links.map((l) => (
            <NavLink key={l.to} to={l.to} end={l.end}
              className={({ isActive }) => cn('whitespace-nowrap border-b-2 px-3 py-2 text-sm', isActive ? 'border-primary font-medium text-primary' : 'border-transparent text-muted-foreground')}>
              {l.label}
            </NavLink>
          ))}
        </nav>
      </header>
      <main className="mx-auto max-w-4xl p-3"><PageSuspense><Outlet /></PageSuspense></main>
    </div>
  );
}

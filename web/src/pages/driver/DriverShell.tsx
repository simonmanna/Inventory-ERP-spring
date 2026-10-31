import { useEffect } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { ClipboardCheck, Fuel, Home, Truck } from 'lucide-react';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';

/** Installs as its own app: swaps the POS manifest for the driver one while this shell is on screen. */
function useDriverManifest() {
  useEffect(() => {
    const created = !document.querySelector('link[rel="manifest"]');
    const link = document.querySelector<HTMLLinkElement>('link[rel="manifest"]') ?? document.createElement('link');
    const previous = link.getAttribute('href');
    link.rel = 'manifest';
    link.href = '/driver.webmanifest';
    if (created) document.head.appendChild(link);
    return () => {
      if (created) link.remove();
      else if (previous) link.setAttribute('href', previous);
    };
  }, []);
}

/** Phone chrome of the driver app (DRIVER-1): no sidebar, a thumb-reach bottom bar. */
export function DriverShell() {
  useDriverManifest();
  const tabs = [
    { to: '/driver', end: true, label: tx('Home'), icon: Home },
    { to: '/driver/trips', end: false, label: tx('Trips'), icon: Truck },
    { to: '/driver/pre-trip', end: false, label: tx('Pre-trip check'), icon: ClipboardCheck },
    { to: '/driver/fuel', end: false, label: tx('Fuel'), icon: Fuel },
  ];
  return (
    <div className="flex min-h-screen flex-col bg-background">
      <main className="mx-auto w-full max-w-xl flex-1 p-3 pb-36"><Outlet /></main>
      <nav className="fixed inset-x-0 bottom-0 z-20 border-t bg-background" aria-label={tx('Driver app')}>
        <div className="mx-auto grid max-w-xl grid-cols-4">
          {tabs.map((t) => (
            <NavLink key={t.to} to={t.to} end={t.end}
              className={({ isActive }) => cn('flex h-16 flex-col items-center justify-center gap-1 text-xs', isActive ? 'text-primary' : 'text-muted-foreground')}>
              <t.icon className="h-6 w-6" aria-hidden />
              {t.label}
            </NavLink>
          ))}
        </div>
      </nav>
    </div>
  );
}

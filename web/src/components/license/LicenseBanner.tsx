import { AlertTriangle, BadgeCheck, ShieldAlert } from 'lucide-react';
import { Link } from 'react-router-dom';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { licenseSeverity, useLicense, type LicenseStatus } from '@/features/license/api';

/**
 * The strip above the workspace when the licence needs attention.
 *
 * It is quiet by design: nothing at all while the licence is healthy, a single line when a renewal
 * is coming, and only when writes have actually stopped does it become loud and permanent. A nag
 * that is always there is a nag nobody reads, and this one has to be believed on the day it matters.
 */
export function LicenseBanner() {
  const { data } = useLicense();
  const severity = licenseSeverity(data);
  if (!data || severity === 'none') return null;
  // Trial countdown is not shown to users; only a stopped (read-only) trial surfaces.
  if (data.mode === 'TRIAL' && severity !== 'error' && !data.tamperSuspected) return null;

  const { icon: Icon, tone, text } = describe(data, severity);

  return (
    <div
      role={severity === 'error' ? 'alert' : 'status'}
      className={cn(
        'flex flex-wrap items-center gap-x-3 gap-y-1 border-b px-4 py-2 text-sm',
        tone,
      )}
    >
      <Icon className="h-4 w-4 shrink-0" aria-hidden />
      <span className="min-w-0 flex-1">{text}</span>
      <Link
        to="/settings/license"
        className="shrink-0 font-medium underline underline-offset-2 hover:no-underline"
      >
        {severity === 'error' ? tx('Fix this') : tx('View licence')}
      </Link>
    </div>
  );
}

function describe(status: LicenseStatus, severity: 'info' | 'warn' | 'error') {
  const days = status.daysRemaining;

  if (severity === 'error') {
    const text =
      status.mode === 'REVOKED'
        ? tx('This licence has been revoked. The system is read-only — contact your supplier.')
        : status.mode === 'UNLICENSED'
          ? tx('The trial period has ended. The system is read-only until a licence is activated.')
          : status.reason === 'suspended'
            ? tx('This licence is suspended. The system is read-only — contact your supplier.')
            : tx('Your licence has expired. The system is read-only: nothing new can be saved.');
    return {
      icon: ShieldAlert,
      tone: 'border-destructive/30 bg-destructive/10 text-destructive',
      text,
    };
  }

  if (status.tamperSuspected) {
    return {
      icon: AlertTriangle,
      tone: 'border-amber-500/30 bg-amber-500/10 text-amber-900 dark:text-amber-200',
      // Said plainly rather than as an accusation: on a real till this is almost always a flat
      // CMOS battery or a machine that booted without a time source.
      text: tx("This machine's clock is behind the licence server. Check the date and time."),
    };
  }

  if (status.mode === 'GRACE') {
    return {
      icon: AlertTriangle,
      tone: 'border-amber-500/30 bg-amber-500/10 text-amber-900 dark:text-amber-200',
      text: status.graceUntil
        ? tx('Your licence has expired. Everything still works until {date} — renew before then.')
            .replace('{date}', formatDate(status.graceUntil))
        : tx('Your licence has expired and is running on borrowed time. Please renew.'),
    };
  }

  return {
    icon: severity === 'warn' ? AlertTriangle : BadgeCheck,
    tone:
      severity === 'warn'
        ? 'border-amber-500/30 bg-amber-500/10 text-amber-900 dark:text-amber-200'
        : 'border-border bg-muted text-muted-foreground',
    text: tx('Your licence expires in {days} day(s).').replace('{days}', String(Math.max(0, days ?? 0))),
  };
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' });
}

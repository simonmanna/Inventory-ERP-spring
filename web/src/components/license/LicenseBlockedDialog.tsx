import { useNavigate } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { tx } from '@/lib/i18n/tx';
import { useLicenseStore } from '@/features/license/license.store';

/**
 * Shown when the API refuses a write because of the licence (HTTP 402).
 *
 * One dialog for every case, because the server already explains itself: the message in the body
 * is written for the person reading the screen, so it is shown as-is rather than re-worded here.
 *
 * It is dismissible on purpose. The work already on screen is not lost by a licence lapsing, and
 * trapping someone in a modal they cannot clear — on a till, mid-service — would be worse than the
 * expiry itself. The banner stays up regardless, so the state cannot be missed.
 */
export function LicenseBlockedDialog() {
  const refusal = useLicenseStore((s) => s.refusal);
  const dismiss = useLicenseStore((s) => s.dismiss);
  const navigate = useNavigate();

  if (!refusal) return null;

  const limit = refusal.limit ? Object.entries(refusal.limit)[0] : undefined;

  return (
    <Dialog open onOpenChange={(open) => !open && dismiss()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <ShieldAlert className="h-5 w-5 text-destructive" aria-hidden />
            {limit ? tx('Licence limit reached') : tx('Licence required')}
          </DialogTitle>
          <DialogDescription className="pt-1 text-foreground">{refusal.message}</DialogDescription>
        </DialogHeader>

        <dl className="space-y-1 rounded-md border bg-muted/40 p-3 text-sm">
          {refusal.plan && (
            <div className="flex justify-between gap-4">
              <dt className="text-muted-foreground">{tx('Plan')}</dt>
              <dd className="font-medium">{refusal.plan}</dd>
            </div>
          )}
          {refusal.expiresAt && (
            <div className="flex justify-between gap-4">
              <dt className="text-muted-foreground">{tx('Expired')}</dt>
              <dd className="font-medium">{formatDate(refusal.expiresAt)}</dd>
            </div>
          )}
          {limit && (
            <div className="flex justify-between gap-4">
              <dt className="text-muted-foreground">{tx('Included')}</dt>
              <dd className="font-medium">{`${limit[1]} ${limit[0]}`}</dd>
            </div>
          )}
        </dl>

        {/* Said explicitly, because it is the first thing anyone wants to know. */}
        <p className="text-sm text-muted-foreground">
          {tx('Your data is safe and everything can still be viewed and exported. Only new entries are blocked.')}
        </p>

        <DialogFooter className="gap-2 sm:gap-2">
          <Button variant="outline" onClick={dismiss}>
            {tx('Close')}
          </Button>
          <Button
            onClick={() => {
              dismiss();
              navigate('/settings/license');
            }}
          >
            {tx('Open licence settings')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' });
}

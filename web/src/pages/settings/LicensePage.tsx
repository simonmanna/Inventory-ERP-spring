import { useState } from 'react';
import {
  BadgeCheck,
  Check,
  Copy,
  Clock,
  RefreshCw,
  ShieldAlert,
  ShieldOff,
  Upload,
} from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { notify } from '@/lib/notify';
import { MODULE_FEATURES } from '@/lib/features';
import {
  useActivateLicense,
  useApplyOfflineLicense,
  useDeactivateLicense,
  useLicense,
  useRefreshLicense,
  type LicenseMode,
  type LicenseStatus,
} from '@/features/license/api';

/**
 * {@code /settings/license} — what this installation is licensed for, and how to change it.
 *
 * Laid out in the order someone actually needs it when something is wrong: the state first, then
 * what is included and how much of it is in use, then the two ways to fix it (a key, or a file),
 * and only at the bottom the destructive option of releasing the installation.
 */
export function LicensePage() {
  const { data: status, isLoading } = useLicense();
  const activate = useActivateLicense();
  const applyOffline = useApplyOfflineLicense();
  const refresh = useRefreshLicense();
  const deactivate = useDeactivateLicense();

  const [licenseKey, setLicenseKey] = useState('');
  const [offlineToken, setOfflineToken] = useState('');
  const [copied, setCopied] = useState(false);

  if (isLoading || !status) {
    return <div className="p-6 text-sm text-muted-foreground">{tx('Loading…')}</div>;
  }

  const copyFingerprint = async () => {
    try {
      await navigator.clipboard.writeText(status.fingerprint);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // A LAN till on plain http has no clipboard API; selecting the text still works.
      notify.error(tx('Could not copy — select the text and copy it manually'));
    }
  };

  const readFile = async (file: File) => {
    const text = (await file.text()).trim();
    // A .lic file is either the bare token or the JSON the server returns around it.
    const token = text.startsWith('{') ? (JSON.parse(text).token ?? '') : text;
    if (!token) {
      notify.error(tx('That file does not contain a licence'));
      return;
    }
    applyOffline.mutate(token);
  };

  return (
    <div className="mx-auto max-w-4xl space-y-6 p-4 sm:p-6">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">{tx('Licence')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('What this installation is licensed to do, and how to renew or move it.')}
        </p>
      </header>

      <StateCard status={status} onRefresh={() => refresh.mutate()} refreshing={refresh.isPending} />

      <div className="grid gap-6 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{tx('Included')}</CardTitle>
            <CardDescription>
              {tx('Modules your licence covers. Switch them on or off in Developer Settings.')}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-2">
            {MODULE_FEATURES.map((feature) => {
              const included = status.features.includes(feature.key);
              return (
                <div key={feature.key} className="flex items-center justify-between gap-3 text-sm">
                  <span className={included ? '' : 'text-muted-foreground'}>{feature.label}</span>
                  <Badge variant={included ? 'secondary' : 'outline'}>
                    {included ? tx('Included') : tx('Not included')}
                  </Badge>
                </div>
              );
            })}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">{tx('Usage')}</CardTitle>
            <CardDescription>
              {tx('Going over a limit only blocks adding more — nothing already set up stops working.')}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <Meter label={tx('POS devices')} used={status.usage.devices} limit={status.limits.devices} />
            <Meter label={tx('Users')} used={status.usage.users} limit={status.limits.users} />
            <Meter
              label={tx('Organizations')}
              used={status.usage.organizations}
              limit={status.limits.organizations}
            />
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tx('Activate a licence')}</CardTitle>
          <CardDescription>
            {tx('Enter the key your supplier gave you. This installation will contact the licence server.')}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-col gap-2 sm:flex-row">
            <Input
              value={licenseKey}
              onChange={(e) => setLicenseKey(e.target.value)}
              placeholder="ERP-XXXX-XXXX-XXXX-XXXX"
              className="font-mono"
              autoComplete="off"
              spellCheck={false}
            />
            <Button
              onClick={() => activate.mutate(licenseKey.trim())}
              disabled={!licenseKey.trim() || activate.isPending}
            >
              {activate.isPending ? tx('Activating…') : tx('Activate')}
            </Button>
          </div>
          {/* Said up front: dashes and case do not matter, so nobody retypes a key five times. */}
          <p className="text-xs text-muted-foreground">
            {tx('Dashes, spaces and capitals do not matter.')}
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tx('No internet on this machine?')}</CardTitle>
          <CardDescription>
            {tx('Send your supplier the installation code below. They will send back a licence file to upload here.')}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label>{tx('Installation code')}</Label>
            <div className="flex flex-col gap-2 sm:flex-row">
              <code className="min-w-0 flex-1 break-all rounded-md border bg-muted px-3 py-2 text-xs">
                {status.fingerprint}
              </code>
              <Button variant="outline" onClick={copyFingerprint} className="shrink-0">
                {copied ? <Check className="mr-2 h-4 w-4" /> : <Copy className="mr-2 h-4 w-4" />}
                {copied ? tx('Copied') : tx('Copy')}
              </Button>
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="licence-file">{tx('Licence file')}</Label>
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
              <Input
                id="licence-file"
                type="file"
                accept=".lic,.json,.txt"
                className="sm:max-w-xs"
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  if (file) void readFile(file);
                  e.target.value = '';
                }}
              />
              <span className="text-xs text-muted-foreground">{tx('or paste its contents below')}</span>
            </div>
            <Textarea
              value={offlineToken}
              onChange={(e) => setOfflineToken(e.target.value)}
              rows={3}
              placeholder="eyJhbGciOiJFZERTQSIs…"
              className="font-mono text-xs"
              spellCheck={false}
            />
            <Button
              variant="outline"
              onClick={() => applyOffline.mutate(offlineToken.trim())}
              disabled={!offlineToken.trim() || applyOffline.isPending}
            >
              <Upload className="mr-2 h-4 w-4" />
              {applyOffline.isPending ? tx('Applying…') : tx('Apply licence file')}
            </Button>
          </div>
        </CardContent>
      </Card>

      {status.licensed && (
        <Card className="border-destructive/30">
          <CardHeader>
            <CardTitle className="text-base">{tx('Move this licence')}</CardTitle>
            <CardDescription>
              {tx('Releases this installation so the same licence can be activated on other hardware. Do this before decommissioning this machine.')}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Button
              variant="outline"
              className="border-destructive/40 text-destructive hover:bg-destructive/10"
              disabled={deactivate.isPending}
              onClick={() => {
                if (window.confirm(tx('Release this licence from this installation? It will become read-only until a licence is activated again.'))) {
                  deactivate.mutate();
                }
              }}
            >
              <ShieldOff className="mr-2 h-4 w-4" />
              {deactivate.isPending ? tx('Releasing…') : tx('Release this installation')}
            </Button>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function StateCard({
  status,
  onRefresh,
  refreshing,
}: {
  status: LicenseStatus;
  onRefresh: () => void;
  refreshing: boolean;
}) {
  const look = MODE_LOOK[status.mode];

  return (
    <Card className={cn('border-l-4', look.border)}>
      <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <CardTitle className="flex items-center gap-2 text-lg">
            <look.icon className={cn('h-5 w-5', look.text)} aria-hidden />
            {look.label}
          </CardTitle>
          <CardDescription>
            {status.customer
              ? tx('Licensed to {customer}').replace('{customer}', status.customer)
              : tx('No licence has been activated on this installation.')}
          </CardDescription>
        </div>
        {/* An offline licence has no server to ask, so the button would only ever fail. */}
        {status.licensed && !status.offline && (
          <Button variant="outline" size="sm" onClick={onRefresh} disabled={refreshing}>
            <RefreshCw className={cn('mr-2 h-4 w-4', refreshing && 'animate-spin')} />
            {tx('Check now')}
          </Button>
        )}
      </CardHeader>
      <CardContent className="grid gap-x-8 gap-y-3 sm:grid-cols-2">
        <Field label={tx('Plan')} value={status.planName ?? status.plan ?? tx('—')} />
        <Field
          label={tx('Expires')}
          value={
            status.expiresAt
              ? `${formatDate(status.expiresAt)}${
                  status.daysRemaining != null
                    ? ` · ${tx('{days} day(s)').replace('{days}', String(status.daysRemaining))}`
                    : ''
                }`
              : status.licensed
                ? tx('Never (perpetual)')
                : tx('—')
          }
        />
        {status.mode === 'GRACE' && status.graceUntil && (
          <Field label={tx('Read-only from')} value={formatDate(status.graceUntil)} />
        )}
        <Field
          label={tx('Last checked')}
          value={status.lastCheckAt ? formatDateTime(status.lastCheckAt) : tx('Never')}
        />
        {status.offline && <Field label={tx('Type')} value={tx('Offline licence file')} />}

        {status.lastCheckError && (
          <p className="text-sm text-amber-700 dark:text-amber-300 sm:col-span-2">
            {tx('Last check failed: {error}').replace('{error}', status.lastCheckError)}
          </p>
        )}
        {status.tamperSuspected && (
          <p className="text-sm text-amber-700 dark:text-amber-300 sm:col-span-2">
            {tx("This machine's clock is behind the licence server. Check the date and time — the licence is being judged against the later of the two.")}
          </p>
        )}
      </CardContent>
    </Card>
  );
}

const MODE_LOOK: Record<LicenseMode, { label: string; icon: typeof BadgeCheck; text: string; border: string }> = {
  ACTIVE: { label: tx('Active'), icon: BadgeCheck, text: 'text-emerald-600', border: 'border-l-emerald-500' },
  TRIAL: { label: tx('Trial'), icon: Clock, text: 'text-sky-600', border: 'border-l-sky-500' },
  GRACE: { label: tx('Expired — grace period'), icon: ShieldAlert, text: 'text-amber-600', border: 'border-l-amber-500' },
  READ_ONLY: { label: tx('Read-only'), icon: ShieldAlert, text: 'text-destructive', border: 'border-l-destructive' },
  REVOKED: { label: tx('Revoked'), icon: ShieldOff, text: 'text-destructive', border: 'border-l-destructive' },
  UNLICENSED: { label: tx('Not licensed'), icon: ShieldOff, text: 'text-destructive', border: 'border-l-destructive' },
};

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div className="space-y-0.5">
      <div className="text-xs uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="text-sm font-medium">{value}</div>
    </div>
  );
}

/** A limit with no number is unlimited; say so rather than drawing an empty bar. */
function Meter({ label, used, limit }: { label: string; used: number; limit?: number }) {
  if (limit == null) {
    return (
      <div className="flex items-center justify-between gap-4 text-sm">
        <span>{label}</span>
        <span className="text-muted-foreground">
          {tx('{used} · unlimited').replace('{used}', String(used))}
        </span>
      </div>
    );
  }
  const ratio = limit === 0 ? 1 : Math.min(1, used / limit);
  const atLimit = used >= limit;

  return (
    <div className="space-y-1">
      <div className="flex items-center justify-between gap-4 text-sm">
        <span>{label}</span>
        <span className={cn('font-medium', atLimit && 'text-destructive')}>
          {used} / {limit}
        </span>
      </div>
      <div className="h-1.5 overflow-hidden rounded-full bg-muted">
        <div
          className={cn('h-full rounded-full transition-all', atLimit ? 'bg-destructive' : 'bg-primary')}
          style={{ width: `${Math.round(ratio * 100)}%` }}
        />
      </div>
    </div>
  );
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' });
}

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export default LicensePage;

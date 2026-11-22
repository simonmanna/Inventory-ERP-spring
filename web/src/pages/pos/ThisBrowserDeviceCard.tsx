/**
 * ADR-S083 O2 — register the browser you are on as an offline POS device. The token goes straight into this
 * browser's encrypted vault (never shown); the browser then keeps a synced copy of the POS data and can sell, open
 * and close shifts and add customers while the server is unreachable.
 */
import { useEffect, useState } from 'react';
import { Loader2, MonitorSmartphone } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import { registerThisBrowser, syncEngineFor } from '@/lib/offline/web';
import type { DeviceInfo, SyncStatus } from '@/lib/offline/types';

export function ThisBrowserDeviceCard({ onRegistered }: { onRegistered?: () => void }) {
  const org = useAuthStore((s) => s.organization?.id);
  const [device, setDevice] = useState<DeviceInfo | null | undefined>(undefined);
  const [status, setStatus] = useState<SyncStatus | null>(null);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!org) return;
    const engine = syncEngineFor(org);
    void engine.device().then((d) => setDevice(d ?? null)).catch(() => setDevice(null));
    return engine.subscribe(setStatus);
  }, [org]);

  const register = async () => {
    if (!org || !name.trim()) return;
    setBusy(true);
    try {
      await registerThisBrowser(org, name.trim());
      const engine = syncEngineFor(org);
      setDevice((await engine.device()) ?? null);
      const s = await engine.sync();
      notify.success(s?.state === 'idle' ? tx('This browser is ready to work offline') : tx('Registered — first sync will finish when the server answers'));
      onRegistered?.();
    } catch (e: any) {
      notify.error(e?.response?.data?.message ?? e?.message ?? tx('Registration failed'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2"><MonitorSmartphone className="h-5 w-5" /> {tx('This browser')}</CardTitle>
        <CardDescription>
          {tx('Register the browser you are using as an offline POS terminal. It keeps a synced copy of the POS data and records sales, shifts and customers while the server is unreachable.')}
        </CardDescription>
      </CardHeader>
      <CardContent>
        {device === undefined ? null : device ? (
          <div className="text-sm space-y-1">
            <p>{tx('Registered as')} <strong>{device.name}</strong></p>
            <p className="text-muted-foreground">
              {status?.lastSyncAt ? tx('Last synced {{t}}', { t: new Date(status.lastSyncAt).toLocaleString() }) : tx('Not synced yet')}
              {status && status.pending + status.inFlight > 0 ? ` · ${tx('{{n}} waiting to sync', { n: status.pending + status.inFlight })}` : ''}
            </p>
          </div>
        ) : (
          <div className="flex flex-wrap items-end gap-3">
            <div className="grid gap-1.5">
              <Label htmlFor="browser-device-name">{tx('Terminal name')}</Label>
              <Input id="browser-device-name" className="w-64" placeholder={tx('e.g. Front counter PC')} value={name}
                onChange={(e) => setName(e.target.value)} />
            </div>
            <Button onClick={register} disabled={!name.trim() || busy}>
              {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
              {tx('Register this browser')}
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

import { useEffect, useState } from 'react';
import { Settings2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { usePutFuelBand, useFuelSettings, useUpdateFuelSettings, type FuelBandRow } from '@/features/fleet/fuel-api';
import { Field } from '../fleet-ui';

/** Fuel thresholds (fleet 1.5): overfill tolerance, dip approval thresholds, consumption band per vehicle class. */
export function FuelSettingsPage() {
  const canEdit = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleet.settings));
  const { data, isLoading, isError } = useFuelSettings();
  const save = useUpdateFuelSettings();
  const [overfill, setOverfill] = useState('');
  const [dipLitres, setDipLitres] = useState('');
  const [dipPct, setDipPct] = useState('');

  useEffect(() => {
    if (!data) return;
    setOverfill(data.overfillTolerancePct);
    setDipLitres(data.dipApprovalLitres);
    setDipPct(data.dipApprovalPct);
  }, [data]);

  if (isLoading) return <Skeleton className="h-64 w-full" />;
  if (isError || !data) return <p className="p-8 text-center text-muted-foreground">{tx('Could not load the fuel settings.')}</p>;

  const submit = async () => {
    try {
      await save.mutateAsync({ overfillTolerancePct: Number(overfill), dipApprovalLitres: Number(dipLitres), dipApprovalPct: Number(dipPct) });
      notify.success(tx('Fuel settings saved'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the fuel settings')));
    }
  };

  return (
    <div className="space-y-4">
      <ListPageHeader icon={Settings2} title={tx('Fuel Settings')}
        description={tx('Thresholds used to flag fuel anomalies and to route tank dip variances for approval.')} />
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Thresholds')}</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <Field label={tx('Overfill tolerance (% above tank size)')}>
              <Input type="number" min="0" max="100" step="0.1" value={overfill} disabled={!canEdit} onChange={(e) => setOverfill(e.target.value)} />
            </Field>
            <Field label={tx('Dip variance needing approval (L)')}>
              <Input type="number" min="0" step="1" value={dipLitres} disabled={!canEdit} onChange={(e) => setDipLitres(e.target.value)} />
            </Field>
            <Field label={tx('… or above (% of tank capacity)')}>
              <Input type="number" min="0" max="100" step="0.1" value={dipPct} disabled={!canEdit} onChange={(e) => setDipPct(e.target.value)} />
            </Field>
          </div>
          {canEdit && <Button onClick={submit} disabled={save.isPending}>{tx('Save')}</Button>}
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Consumption bands per vehicle class')}</CardTitle></CardHeader>
        <CardContent className="space-y-2">
          <p className="text-sm text-muted-foreground">{tx('A full-to-full efficiency outside the band is flagged. Leave a bound empty to skip it.')}</p>
          <div className="hidden grid-cols-[1fr_repeat(4,6.5rem)_5rem] gap-2 text-xs font-medium text-muted-foreground sm:grid">
            <span>{tx('Class')}</span><span>{tx('Min L/100 km')}</span><span>{tx('Max L/100 km')}</span><span>{tx('Min L/h')}</span><span>{tx('Max L/h')}</span><span />
          </div>
          {data.bands.map((b) => <BandRow key={b.categoryId} band={b} canEdit={canEdit} />)}
        </CardContent>
      </Card>
    </div>
  );
}

function BandRow({ band, canEdit }: { band: FuelBandRow; canEdit: boolean }) {
  const put = usePutFuelBand();
  const [v, setV] = useState({
    minLPer100Km: band.minLPer100Km ?? '', maxLPer100Km: band.maxLPer100Km ?? '',
    minLPerHour: band.minLPerHour ?? '', maxLPerHour: band.maxLPerHour ?? '',
  });
  const n = (s: string) => (s === '' ? undefined : Number(s));
  const submit = async () => {
    try {
      await put.mutateAsync({ categoryId: band.categoryId, body: {
        minLPer100Km: n(v.minLPer100Km), maxLPer100Km: n(v.maxLPer100Km), minLPerHour: n(v.minLPerHour), maxLPerHour: n(v.maxLPerHour),
      } });
      notify.success(tx('Band saved for {{name}}', { name: band.name }));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the band')));
    }
  };
  const input = (k: keyof typeof v, label: string) => (
    <Input type="number" min="0" step="0.1" aria-label={`${band.name} ${label}`} placeholder={label} value={v[k]} disabled={!canEdit}
      onChange={(e) => setV((s) => ({ ...s, [k]: e.target.value }))} />
  );
  return (
    <div className="grid grid-cols-2 items-center gap-2 rounded-lg border p-2 sm:grid-cols-[1fr_repeat(4,6.5rem)_5rem] sm:border-0 sm:p-0">
      <span className="col-span-2 text-sm font-medium sm:col-span-1">{band.name} <span className="text-xs text-muted-foreground">{band.code}</span></span>
      {input('minLPer100Km', tx('Min L/100 km'))}
      {input('maxLPer100Km', tx('Max L/100 km'))}
      {input('minLPerHour', tx('Min L/h'))}
      {input('maxLPerHour', tx('Max L/h'))}
      {canEdit && <Button size="sm" variant="outline" onClick={submit} disabled={put.isPending}>{tx('Save')}</Button>}
    </div>
  );
}

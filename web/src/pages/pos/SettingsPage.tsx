import { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { usePosSettings, useUpdatePosSettings, type PosNavVisibility } from '@/features/pos/api';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

const POS_MODES = [
  { value: 'cafe', label: tx('Cafe / Restaurant'), desc: tx('Menu items with variants, accompaniments, modifiers, tables, KDS, and KOT printing.') },
  { value: 'retail', label: tx('Retail'), desc: tx('Direct product selling with barcode scanning, no tables, no kitchen.') },
  { value: 'rental', label: tx('Rental'), desc: tx('Hire-out of serialized assets: agreements, deposits, returns, inspection and settlement.') },
];

export function PosSettingsPage() {
  const { data: settings, isLoading } = usePosSettings();
  const update = useUpdatePosSettings();
  const [posMode, setPosMode] = useState('cafe');
  const [sharedDrawer, setSharedDrawer] = useState(false);
  const [showClosingAmounts, setShowClosingAmounts] = useState(true);
  const [nav, setNav] = useState<Required<PosNavVisibility>>({
    showDashboard: true,
    showKitchenDisplay: true,
    showKitchenReports: true,
  });

  useEffect(() => {
    if (settings?.posMode) setPosMode(settings.posMode);
    setSharedDrawer(settings?.sharedDrawer === true);
    setShowClosingAmounts(settings?.showClosingAmounts !== false);
    setNav({
      showDashboard: settings?.showDashboard !== false,
      showKitchenDisplay: settings?.showKitchenDisplay !== false,
      showKitchenReports: settings?.showKitchenReports !== false,
    });
  }, [settings]);

  const handleSave = async () => {
    try {
      await update.mutateAsync({ posMode, sharedDrawer, showClosingAmounts, ...nav });
      notify.success(tx('POS settings saved'));
    } catch {
      notify.error(tx('Failed to save POS settings'));
    }
  };

  const current = POS_MODES.find((m) => m.value === posMode);

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('POS Settings')}</h1>
        <p className="text-sm text-muted-foreground">{tx('Configure how your POS terminal behaves.')}</p>
      </div>

      {isLoading ? (
        <Skeleton className="h-32 w-full" />
      ) : (
        <>
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{tx('POS Mode')}</CardTitle>
            <CardDescription>
              {tx('Choose between Cafe/Restaurant mode (menu items, tables, kitchen) or Retail mode (direct product selling, no tables). This affects the POS terminal layout and available features.')}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <Select value={posMode} onValueChange={setPosMode}>
              <SelectTrigger className="w-64">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {POS_MODES.map((m) => (
                  <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            {current && (
              <p className="text-sm text-muted-foreground">{current.desc}</p>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{tx('Register & drawer')}</CardTitle>
            <CardDescription>
              {tx('One till shared by several cashiers. When off, a collection can only land in the drawer of the cashier who opened it — complete a shift handover before another cashier sells on it. When on, any cashier may settle into the open drawer; every sale still records who settled it.')}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <label className="flex items-center gap-2 text-sm cursor-pointer">
              <input
                type="checkbox"
                className="rounded"
                checked={sharedDrawer}
                onChange={(e) => setSharedDrawer(e.target.checked)}
              />
              {tx('Allow other cashiers on this register (shared drawer)')}
            </label>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{tx('Closing amounts visible to the cashier')}</CardTitle>
            <CardDescription>
              {tx('When on, the shift-close and handover dialogs show the expected drawer cash and every wallet/bank figure before the count — the count verifies a known target. When off the close is blind: the cashier counts first and the expected figures and variance appear only once the count is committed. Managers always see the figures on the Cash Register page.')}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <label className="flex items-center gap-2 text-sm cursor-pointer">
              <input
                type="checkbox"
                className="rounded"
                checked={showClosingAmounts}
                onChange={(e) => setShowClosingAmounts(e.target.checked)}
              />
              {tx('Show the expected closing amounts while closing a shift')}
            </label>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{tx('Point of Sale navigation')}</CardTitle>
            <CardDescription>
              {tx('Choose which optional pages appear under Point of Sale in the sidebar. Hidden pages stay reachable by URL for users with permission.')}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {([
              ['showDashboard', tx('POS Dashboard visible')],
              ['showKitchenDisplay', tx('Kitchen Display visible')],
              ['showKitchenReports', tx('Kitchen Reports visible')],
            ] as const).map(([key, label]) => (
              <label key={key} className="flex items-center gap-2 text-sm cursor-pointer">
                <input
                  type="checkbox"
                  className="rounded"
                  checked={nav[key]}
                  onChange={(e) => setNav({ ...nav, [key]: e.target.checked })}
                />
                {label}
              </label>
            ))}
          </CardContent>
        </Card>
        <Button onClick={handleSave} disabled={update.isPending}>
          {update.isPending ? tx('Saving...') : tx('Save')}
        </Button>
        </>
      )}
    </div>
  );
}

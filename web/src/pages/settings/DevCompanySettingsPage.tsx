import { useEffect, useState, useRef } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Save, Loader2, Upload, Building2, Puzzle, Blocks } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Badge } from '@/components/ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { usePosSettings, useUpdatePosSettings } from '@/features/pos/api';
import { api } from '@/lib/api';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { MODULE_FEATURES, featureEnabled, featureLicensed } from '@/lib/features';
import { useLicense } from '@/features/license/api';
import { ORG_FEATURES_QUERY_KEY } from '@/lib/use-org-features';
import { tx } from '@/lib/i18n/tx';

interface DeveloperSettings {
  logoUrl: string | null;
  name: string;
  features: Record<string, boolean>;
}

/**
 * Feature switches that do not gate a sidebar section. Modules that DO gate
 * navigation live in MODULE_FEATURES so the sidebar and this page cannot drift.
 */
const KNOWN_FEATURES: Array<{ key: string; label: string; description: string }> = [
  { key: 'inventory', label: tx('Inventory'), description: tx('Stock tracking, batches, serial numbers') },
  { key: 'multiCurrency', label: tx('Multi-Currency'), description: tx('Foreign currency support and FX revaluation') },
  { key: 'alcoholBeverageMonitor', label: tx('Alcohol Beverage Monitor'), description: tx('Alcohol stock, duty tracking, and compliance reporting') },
  { key: 'offlineDevices', label: tx('Offline Devices'), description: tx('Offline-capable POS terminals and device sync management') },
  { key: 'procurement', label: tx('Procurement'), description: tx('Purchase requests, orders, and receipts') },
];

const POS_MODES = [
  { value: 'cafe', label: tx('Cafe / Restaurant'), desc: tx('Menu items with variants, accompaniments, modifiers, tables, KDS, and KOT printing.') },
  { value: 'retail', label: tx('Retail'), desc: tx('Direct product selling with barcode scanning, no tables, no kitchen.') },
  { value: 'rental', label: tx('Rental'), desc: tx('Hire-out of serialized assets: agreements, deposits, returns, inspection and settlement.') },
];

export function DevCompanySettingsPage() {
  const qc = useQueryClient();
  const auth = useAuthStore();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [name, setName] = useState('');
  const [logoUrl, setLogoUrl] = useState<string | null>(null);
  const [features, setFeatures] = useState<Record<string, boolean>>({});
  const [uploading, setUploading] = useState(false);

  // POS Mode
  const { data: posSettings, isLoading: posLoading } = usePosSettings();
  const updatePos = useUpdatePosSettings();
  const [posMode, setPosMode] = useState('cafe');
  const [sharedDrawer, setSharedDrawer] = useState(false);

  const q = useQuery<DeveloperSettings>({
    queryKey: ['settings-developer'],
    queryFn: async () => (await api.get<DeveloperSettings>('/settings/developer')).data,
  });

  // What the licence covers. While it is loading nothing is treated as unlicensed, so the page
  // does not briefly grey out toggles that are in fact paid for.
  const { data: license } = useLicense();

  useEffect(() => {
    if (q.data) {
      setName(q.data.name);
      setLogoUrl(q.data.logoUrl);
      setFeatures(q.data.features);
    }
  }, [q.data]);

  useEffect(() => {
    if (posSettings?.posMode) setPosMode(posSettings.posMode);
    setSharedDrawer(posSettings?.sharedDrawer === true);
  }, [posSettings]);

  const save = useMutation({
    mutationFn: async () => {
      const payload: Record<string, unknown> = { name, features };
      if (logoUrl !== undefined) payload.logoUrl = logoUrl;
      return (await api.put('/settings/developer', payload)).data;
    },
    onSuccess: (data: DeveloperSettings) => {
      notify.success(tx('Developer settings saved'));
      const org = auth.organization;
      if (org) {
        auth.setOrganization({ ...org, name: data.name });
      }
      qc.invalidateQueries({ queryKey: ['settings-developer'] });
      qc.invalidateQueries({ queryKey: ORG_FEATURES_QUERY_KEY });
    },
    onError: (e: any) => notify.error(e?.response?.data?.message ?? tx('Failed')),
  });

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    // Validate image type
    if (!file.type.startsWith('image/')) {
      notify.error(tx('Please select an image file'));
      return;
    }

    setUploading(true);
    try {
      const formData = new FormData();
      formData.append('file', file);
      formData.append('ownerType', 'organization');
      formData.append('ownerId', 'logo');

      const uploadRes = await api.post('/files/upload', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });

      const signedRes = await api.post(`/files/${uploadRes.data.id}/signed-url`);
      setLogoUrl(signedRes.data.url);
      notify.success(tx('Logo uploaded'));
    } catch (err: any) {
      notify.error(err?.response?.data?.message ?? tx('Upload failed'));
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const toggleFeature = (key: string) => {
    setFeatures((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  // Module features default to enabled when the key was never written, so the
  // toggle flips against the resolved value rather than the raw map.
  const toggleModule = (key: string) => {
    setFeatures((prev) => ({ ...prev, [key]: !featureEnabled(prev, key) }));
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Developer Company Settings')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Configure the base company profile and which modules are available.')}
          </p>
        </div>
        <Button onClick={() => save.mutate()} disabled={save.isPending || q.isLoading}>
          {save.isPending ? (
            <Loader2 className="mr-2 h-4 w-4 animate-spin" />
          ) : (
            <Save className="mr-2 h-4 w-4" />
          )}
          {tx('Save Developer Settings')}
        </Button>
      </div>

      {/* Company Profile — full width, sections laid out horizontally */}
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <Building2 className="h-4 w-4 text-muted-foreground" />
            <div>
              <CardTitle>{tx('Company Profile')}</CardTitle>
              <CardDescription>{tx('Basic company identity used across the system.')}</CardDescription>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          {q.isLoading ? (
            <Skeleton className="h-32 w-full" />
          ) : (
            <div className="grid gap-6 md:grid-cols-3 md:divide-x">
              {/* Company Logo */}
              <div className="min-w-0">
                <label className="text-sm font-medium">{tx('Company Logo')}</label>
                <div className="mt-2 flex items-center gap-4">
                  {logoUrl ? (
                    <div className="relative h-20 w-20 overflow-hidden rounded-lg border bg-muted">
                      <img
                        src={logoUrl}
                        alt={tx('Company logo')}
                        className="h-full w-full object-contain"
                        onError={(e) => {
                          (e.target as HTMLImageElement).style.display = 'none';
                        }}
                      />
                    </div>
                  ) : (
                    <div className="flex h-20 w-20 items-center justify-center rounded-lg border border-dashed bg-muted/30">
                      <Building2 className="h-8 w-8 text-muted-foreground/50" />
                    </div>
                  )}
                  <div className="flex flex-col gap-1.5">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => fileInputRef.current?.click()}
                      disabled={uploading}
                    >
                      {uploading ? (
                        <Loader2 className="mr-2 h-3 w-3 animate-spin" />
                      ) : (
                        <Upload className="mr-2 h-3 w-3" />
                      )}
                      {logoUrl ? tx('Replace') : tx('Upload')}
                    </Button>
                    {logoUrl && (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => setLogoUrl(null)}
                      >
                        {tx('Remove')}
                      </Button>
                    )}
                    <input
                      ref={fileInputRef}
                      type="file"
                      accept="image/*"
                      className="hidden"
                      onChange={handleFileUpload}
                    />
                  </div>
                </div>
                {logoUrl && (
                  <p className="mt-1.5 text-xs text-muted-foreground truncate max-w-xs">
                    {logoUrl}
                  </p>
                )}
              </div>

              {/* Company Name */}
              <div className="min-w-0 md:pl-6">
                <label className="text-sm font-medium">{tx('Company Name')}</label>
                <Input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="mt-1"
                  placeholder={tx('Your company name')}
                />
              </div>

              {/* POS Mode */}
              <div className="min-w-0 md:pl-6">
                <label className="text-sm font-medium">{tx('POS Mode')}</label>
                <p className="text-xs text-muted-foreground mb-3">
                  {tx('Choose between Cafe/Restaurant or Retail mode. This affects the POS terminal layout and available features.')}
                </p>
                {posLoading ? (
                  <Skeleton className="h-10 w-64" />
                ) : (
                  <div className="space-y-3">
                    <Select value={posMode} onValueChange={setPosMode}>
                      <SelectTrigger className="w-full max-w-64">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {POS_MODES.map((m) => (
                          <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    {POS_MODES.find((m) => m.value === posMode) && (
                      <p className="text-sm text-muted-foreground">{POS_MODES.find((m) => m.value === posMode)!.desc}</p>
                    )}
                    <label className="flex items-center gap-2 text-sm cursor-pointer">
                      <input
                        type="checkbox"
                        className="rounded"
                        checked={sharedDrawer}
                        onChange={(e) => setSharedDrawer(e.target.checked)}
                      />
                      {tx('Allow other cashiers on the register (shared drawer)')}
                    </label>
                    <Button onClick={async () => {
                      try {
                        await updatePos.mutateAsync({ posMode, sharedDrawer });
                        notify.success(tx('POS settings saved'));
                      } catch {
                        notify.error(tx('Failed to save POS settings'));
                      }
                    }} disabled={updatePos.isPending} size="sm">
                      {updatePos.isPending ? tx('Saving...') : tx('Save POS Mode')}
                    </Button>
                  </div>
                )}
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Modules & Features side by side, each a vertical list */}
      <div className="grid gap-6 lg:grid-cols-2">
        {/* Modules — these gate the left sidebar navigation */}
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <Blocks className="h-4 w-4 text-muted-foreground" />
              <div>
                <CardTitle>{tx('Modules')}</CardTitle>
                <CardDescription>
                  {tx('Turn a module off to hide its group from the left sidebar navigation.')}
                </CardDescription>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            {q.isLoading ? (
              <Skeleton className="h-48 w-full" />
            ) : (
              <div className="space-y-3">
                {MODULE_FEATURES.map((feat) => {
                  // The licence is the ceiling: a module it does not include cannot be switched
                  // on here, so the toggle is disabled and says why rather than appearing to work
                  // and then being refused by the server.
                  const licensed = featureLicensed(license?.features, feat.key);
                  const on = licensed && featureEnabled(features, feat.key);
                  return (
                    <label
                      key={feat.key}
                      className={`flex items-start gap-3 rounded-lg border p-3 transition-colors ${
                        licensed ? 'cursor-pointer hover:bg-muted/50' : 'cursor-not-allowed opacity-60'
                      }`}
                    >
                      <input
                        type="checkbox"
                        className="mt-0.5 rounded"
                        checked={on}
                        disabled={!licensed}
                        onChange={() => toggleModule(feat.key)}
                      />
                      <div className="min-w-0">
                        <div className="flex items-center gap-2 text-sm font-medium">
                          {feat.label}
                          <Badge variant={on ? 'secondary' : 'outline'} className="text-[10px]">
                            {!licensed ? 'not in your plan' : on ? 'visible' : 'hidden'}
                          </Badge>
                        </div>
                        <p className="text-xs text-muted-foreground">
                          {licensed
                            ? feat.description
                            : tx('Not included in your licence. See Settings → Licence to upgrade.')}
                        </p>
                      </div>
                    </label>
                  );
                })}
              </div>
            )}
          </CardContent>
        </Card>

        {/* Feature Toggles */}
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <Puzzle className="h-4 w-4 text-muted-foreground" />
              <div>
                <CardTitle>{tx('Features')}</CardTitle>
                <CardDescription>{tx('Enable or disable modules for this organization.')}</CardDescription>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            {q.isLoading ? (
              <Skeleton className="h-48 w-full" />
            ) : (
              <div className="space-y-3">
                {KNOWN_FEATURES.map((feat) => (
                  <label
                    key={feat.key}
                    className="flex cursor-pointer items-start gap-3 rounded-lg border p-3 transition-colors hover:bg-muted/50"
                  >
                    <input
                      type="checkbox"
                      className="mt-0.5 rounded"
                      checked={!!features[feat.key]}
                      onChange={() => toggleFeature(feat.key)}
                    />
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 text-sm font-medium">
                        {feat.label}
                        {features[feat.key] && (
                          <Badge variant="secondary" className="text-[10px]">{tx('active')}</Badge>
                        )}
                      </div>
                      <p className="text-xs text-muted-foreground">{feat.description}</p>
                    </div>
                  </label>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

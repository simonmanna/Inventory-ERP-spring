import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { tx } from '@/lib/i18n/tx';
import {
  useProductUnitEquivalents, useProductUnitProfile, useSaveProductUnitProfile, type ProductUnitProfileInput,
} from './api';

type Field = keyof ProductUnitProfileInput;

const FIELDS: { key: Field; label: () => string }[] = [
  { key: 'densityTonnesPerM3', label: () => tx('Density (t per m³)') },
  { key: 'kgPerPiece', label: () => tx('Weight per piece (kg)') },
  { key: 'kgPerBag', label: () => tx('Weight per bag (kg)') },
  { key: 'tonnesPerTruckload', label: () => tx('Truckload size (t)') },
];

/** One product's density and weights (UOM-1). Sales, stock, weighbridge and invoices all read these numbers. */
export function ProductUnitProfileCard({ productId }: { productId: string }) {
  const { data: profile, isLoading, isError } = useProductUnitProfile(productId);
  const save = useSaveProductUnitProfile(productId);
  const [values, setValues] = useState<Record<Field, string>>({
    densityTonnesPerM3: '', kgPerPiece: '', kgPerBag: '', tonnesPerTruckload: '',
  });
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!profile) return;
    setValues({
      densityTonnesPerM3: profile.densityTonnesPerM3?.toString() ?? '',
      kgPerPiece: profile.kgPerPiece?.toString() ?? '',
      kgPerBag: profile.kgPerBag?.toString() ?? '',
      tonnesPerTruckload: profile.tonnesPerTruckload?.toString() ?? '',
    });
  }, [profile]);

  function submit() {
    setError(null);
    const input: ProductUnitProfileInput = {};
    for (const f of FIELDS) {
      const raw = values[f.key].trim();
      const was = profile?.[f.key] ?? null;
      if (raw === '' && was !== null) input[f.key] = 0;
      if (raw !== '') input[f.key] = Number(raw);
    }
    save.mutate(input, {
      onError: (e: unknown) => {
        const message = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
        setError(message ?? tx('Could not save'));
      },
    });
  }

  return (
    <Card>
      <CardHeader className="pb-2 pt-4 px-5 bg-muted/30 border-b rounded-t-lg">
        <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">
          {tx('Density & Weights')}
        </CardTitle>
      </CardHeader>
      <CardContent className="px-5 py-4 space-y-3">
        {isLoading && <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>}
        {isError && <p className="text-sm text-destructive">{tx('Could not load')}</p>}
        <p className="text-[11px] text-muted-foreground">
          {tx('Each material has its own density. Leave a field empty when it does not apply.')}
        </p>
        <div className="grid grid-cols-2 gap-4">
          {FIELDS.map((f) => (
            <div key={f.key} className="space-y-1.5">
              <Label className="text-sm font-medium">{f.label()}</Label>
              <Input
                type="number"
                min={0}
                step="0.0001"
                value={values[f.key]}
                onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
              />
            </div>
          ))}
        </div>
        {error && <p className="text-sm text-destructive">{error}</p>}
        <Button type="button" variant="outline" size="sm" disabled={save.isPending} onClick={submit}>
          {save.isPending ? tx('Saving…') : tx('Save density & weights')}
        </Button>
      </CardContent>
    </Card>
  );
}

/** A quantity of a product shown in every unit it converts to: 120 t = 75 m³ = 4 truck. */
export function ProductUnitEquivalents({ productId, quantity }: { productId: string; quantity: number }) {
  const { data: units = [] } = useProductUnitEquivalents(productId, quantity);
  const others = units.filter((u) => !u.isBase);
  if (others.length === 0) return null;
  return (
    <p className="text-xs text-muted-foreground">
      {tx('Equal to')}: {others.map((u) => `${u.quantity.toLocaleString()} ${u.symbol ?? u.code}`).join(' · ')}
    </p>
  );
}

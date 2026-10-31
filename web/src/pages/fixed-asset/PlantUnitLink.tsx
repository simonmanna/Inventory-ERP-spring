import { Link } from 'react-router-dom';
import { Wrench } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Card, CardContent } from '@/components/ui/card';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import { usePlantByAsset } from '@/features/fleet/plant-api';

/** Link from a fixed asset to the plant unit maintained for it (CMMS-1); nothing without a linked unit. */
export function PlantUnitLink({ assetId }: { assetId: string }) {
  const allowed = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetVehicle.read));
  const { data } = usePlantByAsset(assetId, allowed);
  const unit = data?.unit;
  if (!unit) return null;
  return (
    <Card className="md:col-span-3">
      <CardContent className="flex items-center gap-2 pt-4 text-sm">
        <Wrench className="h-4 w-4 text-muted-foreground" aria-hidden />
        <span>{tx('Maintained as fixed plant')}:</span>
        <Link className="font-medium text-primary underline-offset-2 hover:underline" to={`/fleet/vehicles/${unit.id}`}>
          {unit.code}{unit.name ? ` · ${unit.name}` : ''}
        </Link>
      </CardContent>
    </Card>
  );
}

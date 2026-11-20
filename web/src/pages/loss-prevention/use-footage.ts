import { PERMISSIONS } from '@erp/shared';
import { useLicense } from '@/features/license/api';
import { featureAvailable } from '@/lib/features';
import { useOrgFeatures } from '@/lib/use-org-features';
import { useAuthStore } from '@/stores/auth.store';

/**
 * Whether "View footage" can be offered: the loss-prevention module and its camera licence feature are on, and the
 * user may see evidence (`lp:evidence`). Anything else would answer 402/403, so the link is simply not shown.
 */
export function useFootageAvailable(): boolean {
  const can = useAuthStore((s) => s.hasPermission);
  const { data: license } = useLicense();
  const { data: orgFeatures } = useOrgFeatures();
  return can(PERMISSIONS.lossPrevention.evidence) && !!license
    && featureAvailable(orgFeatures, license.features, 'lossPrevention')
    && featureAvailable(orgFeatures, license.features, 'lpVision');
}

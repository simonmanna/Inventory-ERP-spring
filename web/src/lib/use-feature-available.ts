import { useLicense } from '@/features/license/api';
import { featureAvailable, serverModuleAvailable } from '@/lib/features';
import { useModuleCatalog } from '@/lib/use-module-catalog';
import { useOrgFeatures } from '@/lib/use-org-features';

/**
 * Can this module feature be used here: licensed, switched on in Developer Settings (with the modules it requires),
 * and booted by the server and not switched off on the Modules screen? Unknown answers (still loading) count as yes,
 * as in the sidebar.
 */
export function useFeatureAvailable(key: string): boolean {
  const { data: features } = useOrgFeatures();
  const { data: license } = useLicense();
  const { data: catalog } = useModuleCatalog();
  return featureAvailable(features, license?.features, key) && serverModuleAvailable(catalog, key);
}

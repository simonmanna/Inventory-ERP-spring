import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { CatalogModule } from '@/lib/features';
import { useAuthStore } from '@/stores/auth.store';

/** Shared with the Modules screen, so switching an add-on there refreshes the sidebar. */
export const MODULE_CATALOG_QUERY_KEY = ['org-modules-catalog'] as const;

/**
 * `GET /feature-flags/modules/catalog`: the modules this server booted, with each add-on's state for the
 * organization (ADR-S086). Needs a session only, so every role can resolve its sidebar. While loading or on failure
 * the data is undefined, which hides nothing.
 */
export function useModuleCatalog() {
  const token = useAuthStore((s) => s.accessToken);
  return useQuery<CatalogModule[]>({
    queryKey: MODULE_CATALOG_QUERY_KEY,
    enabled: !!token,
    staleTime: 5 * 60_000,
    retry: false,
    queryFn: async () => (await api.get<CatalogModule[]>('/feature-flags/modules/catalog')).data ?? [],
  });
}

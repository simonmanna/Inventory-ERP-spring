import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useAuthStore } from '@/stores/auth.store';

export const ORG_FEATURES_QUERY_KEY = ['org-features'] as const;

interface OrgFeaturesResponse {
  features?: Record<string, boolean>;
  /** Line under the organization name in the sidebar header; null = default label. */
  sidebarTagline?: string | null;
}

/**
 * Shared query for `/settings/features`. Read from an unprivileged endpoint so
 * cashiers and other non-admin roles can still resolve their sidebar. Both
 * hooks below use the same key, so they share one request.
 */
function useOrgFeaturesQuery<T>(select: (d: OrgFeaturesResponse) => T) {
  const token = useAuthStore((s) => s.accessToken);
  return useQuery<OrgFeaturesResponse, Error, T>({
    queryKey: ORG_FEATURES_QUERY_KEY,
    enabled: !!token,
    staleTime: 5 * 60_000,
    retry: false,
    queryFn: async () => (await api.get<OrgFeaturesResponse>('/settings/features')).data ?? {},
    select,
  });
}

/**
 * The organization's module feature switches, as saved in Developer Settings.
 *
 * On failure the map stays empty, which `featureEnabled` treats as "everything
 * on" — navigation never blanks out because a request failed.
 */
export function useOrgFeatures() {
  return useOrgFeaturesQuery((d) => d.features ?? {});
}

/** The configurable sidebar tagline (Developer Settings), or null when not set. */
export function useSidebarTagline() {
  return useOrgFeaturesQuery((d) => d.sidebarTagline ?? null);
}

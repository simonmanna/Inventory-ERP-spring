import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';

/** A module that has an embedded Superset dashboard for this user (GET /bi/dashboards, ADR-S078). */
export interface BiDashboard { module: string; label: string }

/** What the API hands back to embed one dashboard; the token is pinned to the user's organization. */
export interface GuestToken { module: string; dashboardId: string; supersetDomain: string; token: string; expiresAt: string }

export const guestToken = async (module: string) =>
  (await api.get<GuestToken>(`/bi/dashboards/${encodeURIComponent(module)}/guest-token`)).data;

/** The embedded dashboards this user may open; empty when Superset is not configured. */
export function useBiDashboards() {
  return useQuery({
    queryKey: ['bi', 'dashboards'],
    queryFn: async () => (await api.get<BiDashboard[]>('/bi/dashboards')).data,
    staleTime: 5 * 60_000,
    retry: false,
  });
}

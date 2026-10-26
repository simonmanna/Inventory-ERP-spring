import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

/** What this installation is allowed to do. Mirrors the backend's `LicenseMode`. */
export type LicenseMode = 'UNLICENSED' | 'TRIAL' | 'ACTIVE' | 'GRACE' | 'READ_ONLY' | 'REVOKED';

/** `GET /license/status`. */
export interface LicenseStatus {
  mode: LicenseMode;
  reason: string;
  /** The one thing most callers need: may this install save anything? */
  writable: boolean;
  licensed: boolean;
  plan: string | null;
  planName: string | null;
  customer: string | null;
  status: string | null;
  period: string | null;
  /** Issued as a file for an air-gapped install, so it can never be refreshed online. */
  offline: boolean;
  startsAt: string | null;
  expiresAt: string | null;
  graceUntil: string | null;
  daysRemaining: number | null;
  features: string[];
  /** Only the caps that exist; an absent one is unlimited. */
  limits: { devices?: number; users?: number; organizations?: number };
  usage: { devices: number; users: number; organizations: number };
  fingerprint: string;
  lastCheckAt: string | null;
  lastCheckError: string | null;
  refreshAfter: string | null;
  /** The clock is behind the newest time the licence server was ever seen at. */
  tamperSuspected: boolean;
}

const KEY = ['license', 'status'] as const;

/**
 * The licence, for the banner and the licence page.
 *
 * Polled rather than fetched once: a licence expires by the passage of time, and a shop that has
 * been left open overnight should see the banner appear without a reload. Five minutes is often
 * enough for that and rare enough to be invisible.
 */
export function useLicense() {
  return useQuery({
    queryKey: KEY,
    queryFn: async () => (await api.get<LicenseStatus>('/license/status')).data,
    staleTime: 60_000,
    refetchInterval: 5 * 60_000,
    refetchOnWindowFocus: true,
    // The licence must never be the reason a screen fails to render.
    retry: 1,
  });
}

export function useLicenseFingerprint() {
  return useQuery({
    queryKey: ['license', 'fingerprint'],
    queryFn: async () => (await api.get<{ fingerprint: string }>('/license/fingerprint')).data.fingerprint,
    staleTime: Infinity,
  });
}

function useLicenseMutation<TArgs>(
  call: (args: TArgs) => Promise<LicenseStatus>,
  success: string,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: call,
    onSuccess: (status) => {
      queryClient.setQueryData(KEY, status);
      // Entitlements may have changed, so anything gated by them has to be re-read.
      void queryClient.invalidateQueries({ queryKey: ['settings', 'features'] });
      void queryClient.invalidateQueries({ queryKey: ['modules'] });
      notify.success(success);
    },
    onError: (error: unknown) => {
      notify.error((error as { userMessage?: string }).userMessage ?? tx('That did not work'));
    },
  });
}

export function useActivateLicense() {
  return useLicenseMutation<string>(
    async (licenseKey) => (await api.post<LicenseStatus>('/license/activate', { licenseKey })).data,
    tx('Licence activated'),
  );
}

export function useApplyOfflineLicense() {
  return useLicenseMutation<string>(
    async (token) => (await api.post<LicenseStatus>('/license/offline', { token })).data,
    tx('Licence file applied'),
  );
}

export function useRefreshLicense() {
  return useLicenseMutation<void>(
    async () => (await api.post<LicenseStatus>('/license/refresh')).data,
    tx('Licence checked'),
  );
}

export function useDeactivateLicense() {
  return useLicenseMutation<void>(
    async () => (await api.post<LicenseStatus>('/license/deactivate')).data,
    tx('Licence released from this installation'),
  );
}

/** Whole days until expiry, or null for a perpetual licence. */
export function daysUntil(iso: string | null | undefined): number | null {
  if (!iso) return null;
  return Math.floor((new Date(iso).getTime() - Date.now()) / 86_400_000);
}

/**
 * Should the user be warned, and how loudly?
 *
 * `error` is reserved for "nothing can be saved right now" — the one state worth interrupting
 * someone over. A fortnight's notice of a renewal is a `warn`, not an alarm.
 */
export function licenseSeverity(status: LicenseStatus | undefined): 'none' | 'info' | 'warn' | 'error' {
  if (!status) return 'none';
  if (!status.writable) return 'error';
  if (status.mode === 'GRACE' || status.tamperSuspected) return 'warn';
  const days = status.daysRemaining;
  if (days != null && days <= 14) return days <= 3 ? 'warn' : 'info';
  if (status.mode === 'TRIAL') return 'info';
  return 'none';
}

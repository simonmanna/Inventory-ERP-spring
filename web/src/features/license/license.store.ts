import { create } from 'zustand';

/**
 * The licence refusal the API last handed back (HTTP 402), so one dialog can explain every case.
 *
 * Deliberately a plain store with no API import: `lib/api.ts` writes to it from its response
 * interceptor, and anything that imported the client back would be a cycle.
 */
export interface LicenseRefusal {
  /** The server's own wording — shown as-is, because it is written for the shopkeeper. */
  message: string;
  mode?: string;
  reason?: string;
  plan?: string | null;
  status?: string | null;
  expiresAt?: string | null;
  graceUntil?: string | null;
  /** Which cap was hit, when the refusal was a seat limit rather than an expiry. */
  limit?: Record<string, number>;
  at: number;
}

interface LicenseUiState {
  refusal: LicenseRefusal | null;
  /** The value of the last `X-License-Warning` header seen, for the banner. */
  warning: string | null;
  noteRefusal: (refusal: Omit<LicenseRefusal, 'at'>) => void;
  noteWarning: (warning: string | null) => void;
  dismiss: () => void;
}

export const useLicenseStore = create<LicenseUiState>((set) => ({
  refusal: null,
  warning: null,
  noteRefusal: (refusal) => set({ refusal: { ...refusal, at: Date.now() } }),
  // Only ever set from a response header; clearing on a header-less response would make the
  // banner flicker, since most responses carry no licence header at all.
  noteWarning: (warning) => set((state) => (warning === state.warning ? state : { warning })),
  dismiss: () => set({ refusal: null }),
}));

/** Read the licence block out of a 402 body, whatever shape the route used. */
export function refusalFromResponse(body: unknown, message: string): Omit<LicenseRefusal, 'at'> {
  const licence = (body as { license?: Record<string, unknown> } | undefined)?.license ?? {};
  return {
    message,
    mode: typeof licence.mode === 'string' ? licence.mode : undefined,
    reason: typeof licence.reason === 'string' ? licence.reason : undefined,
    plan: typeof licence.plan === 'string' ? licence.plan : null,
    status: typeof licence.status === 'string' ? licence.status : null,
    expiresAt: typeof licence.expiresAt === 'string' ? licence.expiresAt : null,
    graceUntil: typeof licence.graceUntil === 'string' ? licence.graceUntil : null,
    limit: (licence.limit as Record<string, number> | undefined) ?? undefined,
  };
}

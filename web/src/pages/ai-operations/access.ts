import { PERMISSIONS } from '@erp/shared';

const env = import.meta.env as Record<string, string | undefined>;

/**
 * AI Operations Intelligence joins two opt-in modules on one page: alerts & exceptions (ADR-S021, pair
 * VITE_ENABLE_ALERTS with ENABLE_ALERTS on the API) and loss prevention (ADR-S077, pair VITE_ENABLE_LOSS_PREVENTION
 * with ENABLE_LOSS_PREVENTION). Flags are build-time constants under Vite, so these resolve once.
 */
export const AI_OPS_ALERTS_ENABLED = env.VITE_ENABLE_ALERTS === 'true';
export const AI_OPS_LP_ENABLED = env.VITE_ENABLE_LOSS_PREVENTION === 'true';

export const AI_OPS_PATH = '/ai-operations';

/** Any one of these opens the page; each part of it then checks its own permission. */
export const AI_OPS_PERMISSIONS: string[] = [
  ...(AI_OPS_ALERTS_ENABLED ? [PERMISSIONS.alerts.read] : []),
  ...(AI_OPS_LP_ENABLED ? [PERMISSIONS.lossPrevention.view] : []),
];

/** Route guard list: the old pages stayed reachable by permission alone, so the guard does not depend on the flags. */
export const AI_OPS_ROUTE_PERMISSIONS: string[] = [PERMISSIONS.alerts.read, PERMISSIONS.lossPrevention.view];

/**
 * ADR-S083 O1 — web sync engine types. The wire shapes mirror `contract-tests/sync-protocol/contract.json`.
 */

/** Client-side life of one offline operation (ADR-S083 S1). */
export type OpStatus =
  /** Waiting to be pushed (first time, or after a transient failure). */
  | 'queued'
  /** Sent, no answer recorded yet. After a crash it is re-pushed under the SAME opId. */
  | 'in_flight'
  /** The server applied (or replayed) it. */
  | 'applied'
  /** A definitive business rejection: never retried automatically; a person corrects it with a new op. */
  | 'rejected'
  /** Quarantined by the server (revoked / expired device): only a supervisor can recover it. */
  | 'held';

/** One operation in the outbox. `opId` is minted once and never changes across retries. */
export interface SyncOp {
  opId: string;
  deviceSeq: number;
  type: string;
  actorUserId: string;
  occurredAt: string;
  payload: Record<string, unknown>;
  /** Set on a person's correction of a rejected op (S1/S5). */
  supersedesOpId?: string;
  status: OpStatus;
  attempts: number;
  /** Epoch ms before which a queued op is not pushed again (backoff). */
  nextAttemptAt: number;
  createdAt: number;
  updatedAt: number;
  lastError?: string;
  code?: string;
  httpStatus?: number;
  /** `mapping` / `finalNumbers` returned when applied. */
  result?: { mapping?: Record<string, unknown>; finalNumbers?: Record<string, unknown> };
}

/** Input to enqueue: the engine mints opId, deviceSeq, occurredAt and timestamps. */
export interface NewOp {
  type: string;
  actorUserId: string;
  payload: Record<string, unknown>;
  occurredAt?: string;
  supersedesOpId?: string;
}

/** The registered device. The token itself lives encrypted in the vault, never here. */
export interface DeviceInfo {
  id: string;
  name: string;
  organizationId: string;
  registeredAt: number;
}

/** Offline policy sent on every pull (S2/C4). */
export interface OfflinePolicy {
  offlineWindowHours: number;
  idleTimeoutMinutes: number;
  pinMaxAttempts: number;
  pinLockoutMinutes: number;
}

export const DEFAULT_POLICY: OfflinePolicy = {
  offlineWindowHours: 72,
  idleTimeoutMinutes: 30,
  pinMaxAttempts: 5,
  pinLockoutMinutes: 5,
};

/** One pushed op result (`pushApplied` / `pushFailed`). */
export interface PushResult {
  opId: string;
  status: 'applied' | 'replayed' | 'failed';
  httpStatus: number;
  error: string | null;
  code?: string;
  retryable?: boolean;
  mapping?: Record<string, unknown>;
  finalNumbers?: Record<string, unknown>;
}

export interface PushResponse {
  results: PushResult[];
  lastPushSeq: number;
  serverTime: string;
}

export interface PullResponse {
  data: Record<string, Array<Record<string, unknown>>>;
  cursor: string;
  serverTime: string;
  protocolVersion?: string;
  deviceToken?: string;
  deviceTokenExpiresAt?: string | null;
  offlinePolicy?: OfflinePolicy;
  serverId?: string;
  deviceBranchId?: string | null;
  [k: string]: unknown;
}

export interface SnapshotOpen {
  snapshotId: string;
  snapshotAt: string;
  expiresAt: string;
  scopes: string[];
  pageSize: number;
  pages: Record<string, number>;
  pageCount: number;
}

export interface SnapshotPage {
  snapshotId: string;
  scope: string;
  after: string;
  rows: Array<Record<string, unknown>>;
  nextAfter: string | null;
  hasMore: boolean;
  pageChecksum: string;
}

export interface SnapshotComplete {
  snapshotId: string;
  status: 'completed';
  snapshotAt: string;
  cursor: string;
}

/** Download progress of a snapshot, kept so a restart resumes at the last stored page (C5). */
export interface SnapshotProgress {
  snapshotId: string;
  expiresAt: string;
  scopes: string[];
  perScope: Record<string, { after: string; done: boolean; rowCount: number; pageChecksums: string[] }>;
}

/** Engine state shown to the UI. */
export type EngineState =
  | 'idle'
  | 'syncing'
  | 'offline'
  | 'unregistered'
  /** The device token expired: pull is refused; queued ops are still pushed (held server-side). */
  | 'token_expired'
  /** The device was revoked: local data wiped, unsynced ops exported for a supervisor. */
  | 'revoked'
  /** The server no longer serves this protocol on pull; push still works. */
  | 'upgrade_required'
  | 'error';

export interface SyncStatus {
  state: EngineState;
  lastSyncAt: number | null;
  pending: number;
  inFlight: number;
  rejected: number;
  held: number;
  message?: string;
  /** True once local storage was found evicted (data written before may be gone). */
  storageEvicted?: boolean;
}

/** Scopes the server always sends in full (no watermark): replace, never merge. */
export const FULL_SET_SCOPES = ['paymentMethods', 'ledgerAccounts', 'expenseCategories', 'stockLocations'] as const;

/** Every pull scope (`SyncPullService.SCOPES`). */
export const ALL_SCOPES = [
  'menuItems', 'menuCategories', 'modifierGroups', 'taxes', 'posTables', 'cashRegisters', 'staff', 'settings',
  'products', 'productCategories', 'productPackagings', 'partners', 'reservations', 'conversations', 'messages',
  'paymentMethods', 'ledgerAccounts', 'expenseCategories', 'stockLocations', 'stockLevels', 'suppliers',
  'productVariants',
] as const;

export const SYNC_PROTOCOL = '2.0';

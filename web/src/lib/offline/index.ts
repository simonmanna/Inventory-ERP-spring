/** ADR-S083 O1 — web offline sync engine. Wiring into the POS screens is O2. */
export * from './types';
export { SyncStore, dbName } from './store';
export { Vault } from './vault';
export { SyncEngine, stateOf, PUSH_BATCH, type EngineOptions, type RevokedExport } from './engine';
export { FetchTransport, TransportError, wireOp, type SyncTransport } from './transport';
export { OfflineSessions, offlineWindow, permissionsOf, type OfflineSession, type LoginResult } from './session';
export { runSnapshot, SnapshotVerificationError } from './snapshot';
export { applyResult, applyTransientFailure, backoffMs, dueOps, isRetryable } from './retry';
export { checkStorage, requestPersistence } from './storage';
export { InProcessLocks, defaultLocks } from './lock';

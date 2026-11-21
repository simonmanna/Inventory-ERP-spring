import { api } from '@/lib/api';
import { operationIdentity } from './offline-queue';
import { tx } from '@/lib/i18n/tx';
import { isRecordedOffline } from './pos-ops';
import { routePosWrite } from './pos-sync';

export function pendingCashOperations(): Array<{ slot: string; endpoint: string; key: string; payload: any }> {
  try {
    const identity = operationIdentity();
    const prefix = `pos-cash-operation:${identity.organizationId}:${identity.operatorId}:`;
    return Array.from({ length: localStorage.length }, (_, i) => localStorage.key(i)!).filter(k => k.startsWith(prefix)).map(slot => ({ slot, endpoint: slot.slice(prefix.length), ...JSON.parse(localStorage.getItem(slot)!) }));
  } catch { return []; }
}

export async function recoverCashOperation(slot: string) {
  const operation = pendingCashOperations().find(item => item.slot === slot);
  if (!operation) throw new Error(tx('No pending operation for this operator'));
  try {
    const result = await api.post(operation.endpoint, operation.payload, { headers: { 'Idempotency-Key': operation.key } });
    localStorage.removeItem(slot);
    return result.data;
  } catch (e: any) { if (e?.response?.data?.safeToRetry) localStorage.removeItem(slot); throw e; }
}

/** Retain an immutable financial request through a lost response. PINs are
 * entered again when needed; the server excludes credentials from this hash. */
export async function submitCashOperation(endpoint: string, body: any) {
  const identity = operationIdentity();
  const slot = `pos-cash-operation:${identity.organizationId}:${identity.operatorId}:${endpoint}`;
  const previous = localStorage.getItem(slot);
  // ADR-S083 O2: on a registered device, known offline → a sync op; the result stands in for the server's answer
  // (an offline shift, a movement) until it syncs. A write already sent over REST once is finished over REST.
  try {
    body = await routePosWrite(endpoint, body, undefined, !!previous);
  } catch (e) {
    if (isRecordedOffline(e)) return offlineCashResult(endpoint, body, e.op.clientId);
    throw e;
  }
  const { managerPin, incomingPin, ...financialPayload } = body;
  const saved = previous ? JSON.parse(previous) : { key: crypto.randomUUID(), payload: financialPayload };
  if (JSON.stringify(saved.payload) !== JSON.stringify(financialPayload)) throw new Error('A previous drawer operation is awaiting confirmation. Retry its original amount, register and reason before recording a different operation.');
  localStorage.setItem(slot, JSON.stringify(saved));
  try {
    const result = await api.post(endpoint, { ...saved.payload, ...(managerPin ? { managerPin } : {}), ...(incomingPin ? { incomingPin } : {}) }, { headers: { 'Idempotency-Key': saved.key } });
    localStorage.removeItem(slot);
    return result.data;
  } catch (e: any) {
    if (e?.response?.data?.safeToRetry) localStorage.removeItem(slot);
    throw e;
  }
}

/** What the UI needs back from a cash write recorded offline. */
function offlineCashResult(endpoint: string, body: any, clientId: string) {
  const base = { id: clientId, offline: true, recordedOffline: true };
  if (endpoint === '/cash-sessions/open') {
    return { ...base, cashRegisterId: body.cashRegisterId, status: 'open', openingFloat: String(body.openingFloat ?? 0),
      openedAt: new Date().toISOString(), userId: null };
  }
  if (endpoint === '/cash-sessions/close') return { ...base, id: body.sessionId, status: 'closed' };
  return base;
}

import { afterEach, describe, expect, it } from 'vitest';
import type { AxiosAdapter } from 'axios';
import { api, isNeedsConnection, serverUnreachable } from '@/lib/api';
import { markReachable, markUnreachable } from '../reachability';

const sent: string[] = [];
const adapter: AxiosAdapter = async (config) => {
  sent.push(`${config.method} ${config.url}`);
  return { data: { ok: true }, status: 200, statusText: 'OK', headers: {}, config };
};

describe('offline write guard (ADR-S083 O3)', () => {
  afterEach(() => {
    markReachable();
    sent.length = 0;
  });

  it('refuses back-office writes at once while unreachable; reads and sign-in still go out', async () => {
    markUnreachable();
    expect(serverUnreachable()).toBe(true);
    const err = await api.post('/invoices', { x: 1 }, { adapter, headers: { 'Idempotency-Key': 'k1' } }).catch((e) => e);
    expect(isNeedsConnection(err)).toBe(true);
    expect(err.response.status).toBe(503);
    expect(err.response.data.code).toBe('NEEDS_CONNECTION');
    expect(err.userMessage).toMatch(/Needs connection/);
    await expect(api.delete('/partners/p1', { adapter })).rejects.toMatchObject({ needsConnection: true });
    expect(sent).toEqual([]);
    expect(serverUnreachable()).toBe(true); // a local refusal says nothing about the server

    await api.get('/products', { adapter });
    expect(sent).toEqual(['get /products']);
  });

  it('sends writes normally when the server is reachable', async () => {
    await api.post('/invoices', {}, { adapter });
    expect(sent).toEqual(['post /invoices']);
  });

  it('sign-in is never blocked', async () => {
    markUnreachable();
    await api.post('/auth/login', {}, { adapter });
    expect(sent).toEqual(['post /auth/login']);
  });
});

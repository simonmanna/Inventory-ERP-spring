/**
 * ADR-S083 O6 — web/Android parity, web half. The shared stream (`contract-tests/sync-protocol/parity/stream.json`)
 * goes through the real SyncStore + SyncEngine push; the batch that would go on the wire is compared with the committed
 * golden `web.json` (written when missing or with UPDATE_GOLDEN=1). Backend `SyncClientParityIT` pushes it and the
 * Android golden from two devices and asserts identical server state.
 */
import 'fake-indexeddb/auto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { afterEach, describe, expect, it } from 'vitest';
import { SyncEngine } from '../engine';
import { InProcessLocks } from '../lock';
import { SyncStore } from '../store';
import { FakeServer } from './fake-server';

const DIR = '../contract-tests/sync-protocol/parity';

interface StreamOp { opId: string; type: string; occurredAt: string; payload: Record<string, unknown> }

let engine: SyncEngine | null = null;
afterEach(() => engine?.dispose());

describe('web/Android parity (ADR-S083 O6)', () => {
  it('the web wire batch for the parity stream matches its golden', async () => {
    const stream = JSON.parse(readFileSync(`${DIR}/stream.json`, 'utf8')).ops as StreamOp[];
    const server = new FakeServer();
    engine = new SyncEngine({ store: new SyncStore('parity-web'), transport: server, locks: new InProcessLocks(),
      channel: null, random: () => 1 });
    await engine.adopt({ id: '{{deviceId}}', name: 'Parity', organizationId: 'org' }, 'tok-1');
    for (const o of stream) {
      await engine.enqueue({ type: o.type, actorUserId: '{{actor}}', occurredAt: o.occurredAt, payload: o.payload },
        { opId: o.opId });
    }
    await engine.sync();
    expect(server.pushCalls).toHaveLength(1);
    const wire = `${JSON.stringify({ deviceId: '{{deviceId}}', ops: server.pushCalls[0].ops }, null, 2)}\n`;
    const golden = `${DIR}/web.json`;
    if (!existsSync(golden) || process.env.UPDATE_GOLDEN === '1') writeFileSync(golden, wire);
    expect(JSON.parse(wire)).toEqual(JSON.parse(readFileSync(golden, 'utf8')));
  });
});

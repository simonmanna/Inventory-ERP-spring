import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import { SyncStore } from '../store';
import { Vault } from '../vault';

describe('Vault', () => {
  it('round-trips a secret, stores only ciphertext, and the key cannot be exported', async () => {
    const store = new SyncStore('vault-test-1');
    const v = new Vault(store);
    await v.put('deviceToken', 'secret-token-value');
    expect(await new Vault(store).get('deviceToken')).toBe('secret-token-value');
    const sealed = await store.getMeta<{ data: ArrayBuffer }>('vault:deviceToken');
    expect(new TextDecoder().decode(sealed!.data)).not.toContain('secret-token-value');
    const key = await store.getMeta<CryptoKey>('vaultKey');
    expect(key?.extractable).toBe(false);
    await expect(crypto.subtle.exportKey('raw', key!)).rejects.toBeTruthy();
    await v.remove('deviceToken');
    expect(await v.get('deviceToken')).toBeUndefined();
  });

  it('a lost key makes the secret unreadable (treated as unregistered), never throws', async () => {
    const store = new SyncStore('vault-test-2');
    await new Vault(store).put('deviceToken', 't');
    await store.setMeta({ vaultKey: undefined });
    expect(await new Vault(store).get('deviceToken')).toBeUndefined();
  });
});

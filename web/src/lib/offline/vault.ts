/**
 * ADR-S083 S2 — encrypted storage for the device token.
 *
 * An AES-GCM key is generated once with `extractable: false` and kept in IndexedDB as a `CryptoKey` (structured
 * clone keeps it non-extractable). Secrets are stored only as `{iv, data}` ciphertext. This defends against reading
 * the disk / IndexedDB files and against copying the database to another browser profile; it does NOT defend against
 * script running on our own origin, which can still ask the browser to decrypt — the stated limit in ADR-S083 S2,
 * mitigated by the strict CSP, no third-party scripts on POS routes and the short offline window.
 */
import type { SyncStore } from './store';

interface Sealed {
  iv: Uint8Array<ArrayBuffer>;
  data: ArrayBuffer;
}

const KEY_META = 'vaultKey';

export class Vault {
  private key: CryptoKey | null = null;

  constructor(private readonly store: SyncStore, private readonly subtle: SubtleCrypto = crypto.subtle) {}

  private async getKey(): Promise<CryptoKey> {
    if (this.key) return this.key;
    let key = await this.store.getMeta<CryptoKey>(KEY_META);
    if (!key) {
      key = await this.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
      await this.store.setMeta({ [KEY_META]: key });
    }
    this.key = key;
    return key;
  }

  async put(name: string, secret: string): Promise<void> {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const data = await this.subtle.encrypt({ name: 'AES-GCM', iv }, await this.getKey(),
      new TextEncoder().encode(secret));
    await this.store.setMeta({ [`vault:${name}`]: { iv, data } satisfies Sealed });
  }

  /** The secret, or `undefined` when absent or no longer decryptable (key lost → treat as unregistered). */
  async get(name: string): Promise<string | undefined> {
    const sealed = await this.store.getMeta<Sealed>(`vault:${name}`);
    if (!sealed) return undefined;
    try {
      const plain = await this.subtle.decrypt({ name: 'AES-GCM', iv: sealed.iv }, await this.getKey(), sealed.data);
      return new TextDecoder().decode(plain);
    } catch {
      return undefined;
    }
  }

  async remove(name: string): Promise<void> {
    await this.store.setMeta({ [`vault:${name}`]: undefined });
  }
}

/**
 * ADR-S083 O1 — one tab syncs at a time.
 *
 * Uses the Web Locks API (`navigator.locks`, Chrome/Edge 69+, Safari 15.4+, Android WebView). A lock is released
 * automatically when its tab closes or crashes, so a dead tab never blocks sync. `ifAvailable` means a second tab does
 * not queue behind the first: it skips and is told the outcome over the BroadcastChannel.
 *
 * Where Web Locks are missing, an in-process lock is used (it serialises within the tab only).
 */

export interface LockLike {
  request<T>(name: string, options: { ifAvailable: boolean }, cb: (lock: unknown | null) => Promise<T>): Promise<T>;
}

/** Same contract as `navigator.locks` for one JS realm. Shared instances simulate several tabs in tests. */
export class InProcessLocks implements LockLike {
  private held = new Set<string>();

  async request<T>(name: string, options: { ifAvailable: boolean }, cb: (lock: unknown | null) => Promise<T>): Promise<T> {
    if (this.held.has(name)) {
      if (options.ifAvailable) return cb(null);
      while (this.held.has(name)) await new Promise((r) => setTimeout(r, 10));
    }
    this.held.add(name);
    try {
      return await cb({ name });
    } finally {
      this.held.delete(name);
    }
  }
}

const fallback = new InProcessLocks();

export function defaultLocks(): LockLike {
  const nav = typeof navigator !== 'undefined' ? (navigator as Navigator & { locks?: LockLike }) : undefined;
  return nav?.locks ?? fallback;
}

/** Minimal BroadcastChannel surface (tests pass a fake; absent → no-op). */
export interface ChannelLike {
  postMessage(msg: unknown): void;
  addEventListener(type: 'message', fn: (e: MessageEvent) => void): void;
  removeEventListener(type: 'message', fn: (e: MessageEvent) => void): void;
  close(): void;
}

export function openChannel(name: string): ChannelLike | null {
  return typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel(name) : null;
}

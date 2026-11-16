/**
 * Recover from a stale bundle after a deploy.
 *
 * Every page is a lazy chunk whose file name carries a content hash. A tab that
 * was opened before a deploy still runs the old entry chunk, so opening another
 * page asks for a chunk name the server no longer has (404) and the screen goes
 * blank. Over plain http there is no service worker to refresh such a tab (see
 * sw-update.ts), so Vite's `vite:preloadError` is the only signal: reload once
 * to pick up the current build.
 *
 * Guards:
 * - reload only when the server answers, so an offline till keeps the screen it
 *   has instead of reloading into a browser error page;
 * - at most once per minute, so a chunk that is genuinely missing cannot loop.
 */
const KEY = 'erp.chunkReloadAt';
const WINDOW_MS = 60_000;

function lastReload(): number {
  try {
    return Number(sessionStorage.getItem(KEY) ?? 0);
  } catch {
    return 0;
  }
}

export function installChunkReload() {
  if (typeof window === 'undefined') return;
  window.addEventListener('vite:preloadError', (event) => {
    if (Date.now() - lastReload() < WINDOW_MS) return; // just reloaded: let the error surface
    event.preventDefault();
    void fetch(import.meta.env.BASE_URL, { method: 'HEAD', cache: 'no-store' })
      .then((res) => {
        if (!res.ok) return;
        try {
          sessionStorage.setItem(KEY, String(Date.now()));
        } catch {
          /* storage blocked: the server check still prevents an offline loop */
        }
        window.location.reload();
      })
      .catch(() => { /* server unreachable: stay on the current screen */ });
  });
}

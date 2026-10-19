/**
 * Phrase translation — natural-language keys.
 *
 * `tx('Open Shift')` looks the English source string up in the active
 * locale's phrase map and falls back to the English string itself when no
 * translation exists. A missing phrase therefore degrades to today's UI
 * instead of showing a key, which is what makes a partially translated build
 * safe to ship.
 *
 * It is a plain function, not a hook, so it also works in toasts, helpers and
 * non-component modules. Re-render on language change is handled by remounting
 * the app tree (see `useAppLanguage` + the `key` on <App/>'s root).
 */
import i18n from './i18n';
import ti from './phrases/ti.json';
import lg from './phrases/lg.json';
import sw from './phrases/sw.json';

type PhraseMap = Record<string, string>;

const MAPS: Record<string, PhraseMap> = {
  ti: ti as PhraseMap,
  lg: lg as PhraseMap,
  sw: sw as PhraseMap,
};

export const currentLang = (): string =>
  (i18n.resolvedLanguage ?? i18n.language ?? 'en').slice(0, 2);

/** Interpolate `{{name}}` placeholders. */
const interpolate = (s: string, vars?: Record<string, unknown>): string =>
  vars ? s.replace(/\{\{(\w+)\}\}/g, (_, k) => String(vars[k] ?? `{{${k}}}`)) : s;

export function tx(phrase: string, vars?: Record<string, unknown>): string {
  const map = MAPS[currentLang()];
  const hit = map?.[phrase];
  // An empty value means "not translated yet" — fall back to the source text.
  return interpolate(hit && hit.trim() ? hit : phrase, vars);
}

/** Phrases present in the English source but missing from a locale map. */
export function missingPhrases(lang: string, phrases: string[]): string[] {
  const map = MAPS[lang];
  if (!map) return [];
  return phrases.filter((p) => !(p in map));
}

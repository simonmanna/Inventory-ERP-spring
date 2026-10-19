/**
 * Current UI language, as a React state value. The app root uses it as a
 * `key`, so changing language remounts the tree — that is what makes the
 * non-hook `tx()` helper re-evaluate everywhere, including inside memoised
 * subtrees and non-component helpers.
 */
import { useEffect, useState } from 'react';
import i18n from './i18n';
import { currentLang } from './tx';

export function useAppLanguage(): string {
  const [lang, setLang] = useState(currentLang);
  useEffect(() => {
    const onChange = () => setLang(currentLang());
    i18n.on('languageChanged', onChange);
    return () => {
      i18n.off('languageChanged', onChange);
    };
  }, []);
  return lang;
}

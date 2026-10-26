import { Check, Globe } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  LOCALE_LABELS,
  LOCALE_NAMES,
  PICKER_LOCALES,
  SUPPORTED_LOCALES,
  type SupportedLocale,
} from '@/lib/i18n/i18n';

export function LanguageSwitcher({ className }: { className?: string }) {
  const { t, i18n } = useTranslation();
  const raw = (i18n.resolvedLanguage ?? i18n.language ?? 'en').slice(0, 2);
  const current = (SUPPORTED_LOCALES as readonly string[]).includes(raw)
    ? (raw as SupportedLocale)
    : 'en';

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="sm"
          aria-label={t('language.switch')}
          title={t('language.switch')}
          className={className ?? 'gap-1'}
        >
          <Globe className="h-4 w-4" />
          <span className="text-xs font-medium">{LOCALE_LABELS[current]}</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-44">
        <DropdownMenuLabel>{t('language.label')}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {PICKER_LOCALES.map((lng) => (
          <DropdownMenuItem
            key={lng}
            className="cursor-pointer justify-between"
            onClick={() => {
              if (lng === current) return;
              // Reload so module-level label tables (sidebar, columns, status maps)
              // re-evaluate tx() in the new language; the choice is cached in localStorage.
              void i18n.changeLanguage(lng).then(() => window.location.reload());
            }}
          >
            <span>{LOCALE_NAMES[lng]}</span>
            {lng === current ? <Check className="h-4 w-4 text-primary" /> : null}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

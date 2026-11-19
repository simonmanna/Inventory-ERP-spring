import { useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { BookOpen, CheckCircle2, GraduationCap, PlayCircle, RotateCcw, Search } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { tx } from '@/lib/i18n/tx';
import { useGuideProgress } from './api';
import { keywordResolver, resumeStep, tourState, toursForPage } from './tour-logic';
import { useVisibleTours } from './use-visible-tours';
import { useTourStore } from './tour-store';
import type { TourDef, TourProgress } from './types';

/** Header button that opens the Learn panel. Hidden when the user has no tour to take. */
export function GuideHeaderButton() {
  const tours = useVisibleTours();
  const setOpen = useTourStore((s) => s.setPanelOpen);
  if (tours.length === 0) return null;
  return (
    <button
      type="button"
      onClick={() => setOpen(true)}
      className="relative inline-flex h-9 w-9 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground"
      aria-label={tx('Learn: guided tours')}
      title={tx('Learn: guided tours')}
    >
      <GraduationCap className="h-5 w-5" />
    </button>
  );
}

/**
 * The Learn panel (ADR-S079): tours for this page, every tour the user may take with their progress, and a search box
 * that goes through the assistant seam ({@link keywordResolver} for now).
 */
export function GuidePanel() {
  const open = useTourStore((s) => s.panelOpen);
  const setOpen = useTourStore((s) => s.setPanelOpen);
  const start = useTourStore((s) => s.start);
  const tours = useVisibleTours();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const progress = useGuideProgress(open);
  const [query, setQuery] = useState('');

  const byKey = useMemo(() => new Map((progress.data ?? []).map((p) => [p.tourKey, p])), [progress.data]);
  const here = toursForPage(tours, pathname);
  const found = query.trim() ? keywordResolver.resolve(query, tours) : null;
  const done = tours.filter((t) => tourState(t, byKey.get(t.key)) === 'completed').length;

  const begin = (t: TourDef, fresh = false) => {
    const step = fresh ? 0 : resumeStep(t, byKey.get(t.key));
    start(t.key, step);
    const route = t.steps[step]?.route;
    // Open the step's page when it is a fixed page; otherwise the tour's start page.
    navigate(route && !route.includes(':') ? route : t.startPath);
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="left-auto right-0 top-0 flex h-full max-h-screen w-full max-w-md translate-x-0 translate-y-0 flex-col gap-3 overflow-y-auto rounded-none sm:rounded-none">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><GraduationCap className="h-5 w-5 text-primary" />{tx('Learn')}</DialogTitle>
          <DialogDescription>
            {tx('Guided tours show you each step on the real screens. You do the clicking; the guide explains.')}
          </DialogDescription>
        </DialogHeader>

        <div className="relative">
          <Search className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" aria-hidden />
          <Input className="pl-8" value={query} onChange={(e) => setQuery(e.target.value)}
            placeholder={tx('What do you want to learn? e.g. "make a quotation"')} aria-label={tx('Search the guide')} />
        </div>

        {found ? (
          <Section title={tx('Suggested tours')}>
            {found.length === 0
              ? <p className="text-sm text-muted-foreground">{tx('No tour matches yet. Try other words, or browse the list below.')}</p>
              : found.map((t) => <TourRow key={t.key} tour={t} progress={byKey.get(t.key)} onStart={begin} />)}
          </Section>
        ) : here.length > 0 && (
          <Section title={tx('On this page')}>
            {here.map((t) => <TourRow key={t.key} tour={t} progress={byKey.get(t.key)} onStart={begin} />)}
          </Section>
        )}

        <Section title={`${tx('Sales')} · ${done}/${tours.length} ${tx('done')}`}>
          <div className="mb-1 h-1.5 overflow-hidden rounded-full bg-muted">
            <div className="h-full rounded-full bg-primary transition-all" style={{ width: `${tours.length ? (done / tours.length) * 100 : 0}%` }} />
          </div>
          {tours.map((t) => <TourRow key={t.key} tour={t} progress={byKey.get(t.key)} onStart={begin} />)}
        </Section>
      </DialogContent>
    </Dialog>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="space-y-2">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{title}</h3>
      {children}
    </section>
  );
}

function TourRow({ tour, progress, onStart }: {
  tour: TourDef; progress?: TourProgress; onStart: (t: TourDef, fresh?: boolean) => void;
}) {
  const state = tourState(tour, progress);
  const [reading, setReading] = useState(false);
  return (
    <div className="rounded-lg border p-3">
      <div className="flex items-start gap-3">
        {state === 'completed'
          ? <CheckCircle2 className="mt-0.5 h-5 w-5 shrink-0 text-emerald-600" aria-label={tx('Done')} />
          : <PlayCircle className="mt-0.5 h-5 w-5 shrink-0 text-primary" aria-hidden />}
        <div className="min-w-0 flex-1">
          <p className="font-medium">{tour.title}</p>
          <p className="text-sm text-muted-foreground">{tour.summary}</p>
          <p className="mt-1 text-xs text-muted-foreground">{tour.steps.length} {tx('steps')}</p>
        </div>
      </div>
      {reading && <Tutorial tour={tour} />}
      <div className="mt-2 flex justify-end gap-2">
        <Button size="sm" variant="ghost" onClick={() => setReading(!reading)} aria-expanded={reading}>
          <BookOpen className="mr-1 h-3.5 w-3.5" />{reading ? tx('Hide tutorial') : tx('Read as tutorial')}
        </Button>
        {state !== 'new' && (
          <Button size="sm" variant="ghost" onClick={() => onStart(tour, true)}>
            <RotateCcw className="mr-1 h-3.5 w-3.5" />{tx('Start over')}
          </Button>
        )}
        {state !== 'completed' && (
          <Button size="sm" onClick={() => onStart(tour)}>
            {state === 'in_progress' ? tx('Resume') : tx('Start')}
          </Button>
        )}
      </div>
    </div>
  );
}

/** The tour as a written, numbered tutorial: every step's text, grouped by chapter. */
function Tutorial({ tour }: { tour: TourDef }) {
  return (
    <ol className="mt-3 space-y-2 border-t pt-3 text-sm">
      {tour.steps.map((s, i) => (
        <li key={i}>
          {s.chapter && <p className="mb-1 mt-3 text-[11px] font-semibold uppercase tracking-wide text-primary first:mt-0">{s.chapter}</p>}
          <p className="font-medium">{i + 1}. {s.title}</p>
          <p className="text-muted-foreground">{s.body}</p>
        </li>
      ))}
    </ol>
  );
}

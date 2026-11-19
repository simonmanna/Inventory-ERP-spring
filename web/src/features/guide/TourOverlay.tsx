import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { ArrowLeft, ArrowRight, GraduationCap, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { tx } from '@/lib/i18n/tx';
import { useSaveTourProgress } from './api';
import { findTour } from './tours';
import { clickAnchor, routeMatches, routeReached, visibleAnchor } from './tour-logic';
import { useTourStore } from './tour-store';

const CARD_W = 340;
const GAP = 12;
/** How long a step's element may be missing before the card offers to skip it. */
const MISSING_AFTER_MS = 5000;

const selector = (anchor: string) => `[data-tour="${CSS.escape(anchor)}"]`;

/** First matching element that is actually on screen (a hidden twin, e.g. a collapsed menu, is skipped). */
function findAnchor(anchor: string): HTMLElement | null {
  for (const el of Array.from(document.querySelectorAll<HTMLElement>(selector(anchor)))) {
    const r = el.getBoundingClientRect();
    if (r.width > 0 || r.height > 0) return el;
  }
  return null;
}

type Box = { top: number; left: number; width: number; height: number };

/** Where the card goes: below the target when it fits, else above, else beside; always inside the window. */
function placeCard(target: Box | null, cardH: number): { top: number; left: number } {
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const w = Math.min(CARD_W, vw - 2 * GAP);
  if (!target) return { top: Math.max(GAP, (vh - cardH) / 2), left: (vw - w) / 2 };
  const clampX = (x: number) => Math.min(Math.max(GAP, x), vw - w - GAP);
  const clampY = (y: number) => Math.min(Math.max(GAP, y), vh - cardH - GAP);
  const below = target.top + target.height + GAP;
  if (below + cardH <= vh - GAP) return { top: below, left: clampX(target.left) };
  const above = target.top - GAP - cardH;
  if (above >= GAP) return { top: above, left: clampX(target.left) };
  const right = target.left + target.width + GAP;
  if (right + w <= vw - GAP) return { top: clampY(target.top), left: right };
  return { top: clampY(vh - cardH - GAP), left: clampX(target.left) };
}

/**
 * The running tour (ADR-S079): a spotlight on the step's element and a card that explains it. Mounted once in the app
 * shell. The page underneath stays fully usable, because the user is meant to do each step themselves.
 */
export function TourOverlay() {
  const { activeKey, stepIndex, go, exit } = useTourStore();
  const tour = useMemo(() => findTour(activeKey), [activeKey]);
  const step = tour?.steps[stepIndex];
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const save = useSaveTourProgress();
  const [box, setBox] = useState<Box | null>(null);
  const [missing, setMissing] = useState(false);
  const cardRef = useRef<HTMLDivElement>(null);
  const [cardH, setCardH] = useState(180);

  const onRoute = !!step && routeMatches(step.route, pathname);
  const last = !!tour && stepIndex >= tour.steps.length - 1;

  // A tour that no longer exists (renamed in a new release) or a step past its end is dropped quietly.
  useEffect(() => {
    if (activeKey && (!tour || !step)) exit();
  }, [activeKey, tour, step, exit]);

  // Remember the step on the server (best effort).
  useEffect(() => {
    if (tour && step) save.mutate({ tourKey: tour.key, tourVersion: tour.version, status: 'in_progress', currentStep: stepIndex });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tour?.key, stepIndex]);

  const finish = useCallback(() => {
    if (tour) save.mutate({ tourKey: tour.key, tourVersion: tour.version, status: 'completed', currentStep: stepIndex });
    exit();
  }, [tour, stepIndex, save, exit]);

  const next = useCallback(() => (last ? finish() : go(stepIndex + 1)), [last, finish, go, stepIndex]);

  // Advance when the browser reaches the step's target route.
  useEffect(() => {
    if (step && routeReached(step, pathname)) next();
  }, [step, pathname, next]);

  // Follow the step's element: position, appearance of a "visible" anchor, and the missing timer.
  useEffect(() => {
    setBox(null);
    setMissing(false);
    if (!step || !onRoute) return;
    let scrolled = false;
    const startedAt = Date.now();
    const tick = () => {
      const waitFor = visibleAnchor(step);
      if (waitFor && findAnchor(waitFor)) { next(); return; }
      const el = step.anchor ? findAnchor(step.anchor) : null;
      if (el) {
        if (!scrolled) { el.scrollIntoView({ block: 'center', inline: 'nearest' }); scrolled = true; }
        const r = el.getBoundingClientRect();
        setBox((b) => (b && b.top === r.top && b.left === r.left && b.width === r.width && b.height === r.height
          ? b : { top: r.top, left: r.left, width: r.width, height: r.height }));
        setMissing(false);
      } else {
        setBox(null);
        if (step.anchor && Date.now() - startedAt > MISSING_AFTER_MS) setMissing(true);
      }
    };
    tick();
    const id = window.setInterval(tick, 150);
    return () => window.clearInterval(id);
  }, [step, onRoute, next]);

  // Advance when the user clicks the element the step asks for. Capture phase, so it runs even if the page stops
  // the event; the page's own handler still runs.
  useEffect(() => {
    const anchor = step && onRoute ? clickAnchor(step) : null;
    if (!anchor) return;
    const onClick = (e: MouseEvent) => {
      if (e.target instanceof Element && e.target.closest(selector(anchor))) window.setTimeout(next, 0);
    };
    document.addEventListener('click', onClick, true);
    return () => document.removeEventListener('click', onClick, true);
  }, [step, onRoute, next]);

  // Keep a dialog that the step points into from closing when the card is clicked (Radix closes on an outside
  // pointer-down), and let Escape end the tour when no dialog is open.
  const hasCard = !!tour && !!step;
  useEffect(() => {
    const card = cardRef.current;
    if (!card) return;
    const stop = (e: Event) => e.stopPropagation();
    card.addEventListener('pointerdown', stop);
    return () => card.removeEventListener('pointerdown', stop);
  }, [hasCard]);
  // The card's height decides whether it fits below the element; measure it whenever its content changes.
  useLayoutEffect(() => {
    if (cardRef.current) setCardH(cardRef.current.offsetHeight);
  }, [hasCard, stepIndex, onRoute, missing]);
  useEffect(() => {
    if (!tour) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !document.querySelector('[role="dialog"]:not([data-guide-card])')) exit();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [tour, exit]);

  if (!tour || !step) return null;

  const pos = placeCard(box, cardH);
  const waitsForUser = step.advanceOn !== 'next';
  // Only static routes can be opened for the user; a record page (`/:id`) has to be reached through the steps.
  const canOpen = !!step.route && !step.route.includes(':');
  const pad = 6;

  return (
    <>
      {box && (
        <div
          aria-hidden
          className="pointer-events-none fixed z-[70] rounded-lg ring-2 ring-primary transition-all duration-200"
          style={{
            top: box.top - pad, left: box.left - pad, width: box.width + 2 * pad, height: box.height + 2 * pad,
            boxShadow: '0 0 0 9999px rgb(0 0 0 / 0.35)',
          }}
        />
      )}
      <div
        ref={cardRef}
        data-guide-card
        role="dialog"
        aria-modal="false"
        aria-label={tour.title}
        className="fixed z-[71] rounded-xl border bg-popover p-4 text-popover-foreground shadow-2xl"
        style={{ top: pos.top, left: pos.left, width: Math.min(CARD_W, window.innerWidth - 2 * GAP), pointerEvents: 'auto' }}
      >
        <div className="mb-2 flex items-start justify-between gap-2">
          <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
            <GraduationCap className="h-4 w-4 text-primary" aria-hidden />
            {tour.title} · {stepIndex + 1}/{tour.steps.length}
          </span>
          <button type="button" onClick={exit} aria-label={tx('End the tour')} title={tx('End the tour')}
            className="rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground">
            <X className="h-4 w-4" />
          </button>
        </div>

        {onRoute ? (
          <>
            <h2 className="text-base font-semibold">{step.title}</h2>
            <p className="mt-1 text-sm leading-relaxed text-muted-foreground">{step.body}</p>
            {missing && (
              <p className="mt-2 rounded-md bg-amber-50 p-2 text-xs text-amber-800 dark:bg-amber-950/40 dark:text-amber-200">
                {tx('I can\'t find this on the screen. It may be hidden for your role or in this state. Skip this step or end the tour.')}
              </p>
            )}
            {waitsForUser && !missing && (
              <p className="mt-2 text-xs font-medium text-primary">{tx('Your turn: do this on the page to continue.')}</p>
            )}
          </>
        ) : (
          <>
            <h2 className="text-base font-semibold">{tx('This step is on another page')}</h2>
            <p className="mt-1 text-sm text-muted-foreground">
              {canOpen ? tx('Open the page to carry on with the tour.') : tx('Go back to the record you were working on, or start the tour again.')}
            </p>
          </>
        )}

        <div className="mt-3 flex items-center justify-between gap-2">
          <Button variant="ghost" size="sm" disabled={stepIndex === 0} onClick={() => go(stepIndex - 1)}>
            <ArrowLeft className="mr-1 h-4 w-4" />{tx('Back')}
          </Button>
          <div className="flex gap-2">
            {!onRoute && canOpen && <Button size="sm" onClick={() => navigate(step.route!)}>{tx('Take me there')}</Button>}
            {!onRoute && !canOpen && <Button size="sm" variant="outline" onClick={() => { go(0); navigate(tour.startPath); }}>{tx('Start again')}</Button>}
            {onRoute && (!waitsForUser || missing) && (
              <Button size="sm" variant={missing && waitsForUser ? 'outline' : 'default'} onClick={next}>
                {last ? tx('Finish') : missing ? tx('Skip') : tx('Next')}
                {!last && <ArrowRight className="ml-1 h-4 w-4" />}
              </Button>
            )}
          </div>
        </div>
      </div>
    </>
  );
}

import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  AlertTriangle, ArrowRight, BarChart3, Bell, BrainCircuit, Calendar, Camera, CheckCircle2, Clock, Cpu,
  Eye, FileText, Film, Info, Maximize2, Minimize2, MapPin, Play, ShieldCheck, User, Video, Wallet,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { useLpCases, useLpDashboard, type LpCase, type Severity } from '@/features/loss-prevention/api';
import { useInsightsSummary } from '@/features/loss-prevention/insights-api';
import { fetchLpEvidenceContent, blobErrorMessage, useLpEvidenceList, type Evidence } from '@/features/loss-prevention/vision-api';
import { FootageDialog } from './FootageDialog';
import { LiveViewDialog } from './vision-shared';
import { useObjectUrl } from './vision-ui';
import { num, pct } from './lp-format';

/** How often the live overview reads the cases again. */
const LIVE_MS = 30_000;

const TONE: Record<Severity, {
  banner: string; border: string; tint: string; text: string; dot: string; pill: string; frame: string; tag: string;
  label: string; priority: string;
}> = {
  critical: {
    banner: 'bg-gradient-to-r from-red-600 via-red-600 to-rose-500', border: 'border-red-500/80',
    tint: 'bg-red-50 border-red-200 dark:bg-red-950/40 dark:border-red-900', text: 'text-red-600 dark:text-red-400',
    dot: 'bg-red-500', pill: 'bg-red-100 text-red-700 dark:bg-red-900/50 dark:text-red-200',
    frame: 'border-red-500', tag: 'bg-red-600', label: tx('Critical alert'), priority: tx('High priority'),
  },
  warning: {
    banner: 'bg-gradient-to-r from-amber-500 via-orange-500 to-orange-400', border: 'border-amber-500/80',
    tint: 'bg-amber-50 border-amber-200 dark:bg-amber-950/40 dark:border-amber-900', text: 'text-amber-600 dark:text-amber-400',
    dot: 'bg-amber-500', pill: 'bg-amber-100 text-amber-800 dark:bg-amber-900/50 dark:text-amber-200',
    frame: 'border-amber-400', tag: 'bg-amber-500', label: tx('Needs attention'), priority: tx('Medium priority'),
  },
  info: {
    banner: 'bg-gradient-to-r from-blue-600 via-blue-600 to-sky-500', border: 'border-blue-500/80',
    tint: 'bg-blue-50 border-blue-200 dark:bg-blue-950/40 dark:border-blue-900', text: 'text-blue-600 dark:text-blue-400',
    dot: 'bg-blue-500', pill: 'bg-blue-100 text-blue-700 dark:bg-blue-900/50 dark:text-blue-200',
    frame: 'border-sky-400', tag: 'bg-blue-600', label: tx('For review'), priority: tx('Low priority'),
  },
};
const PILL_LABEL: Record<Severity, string> = { critical: tx('High'), warning: tx('Medium'), info: tx('Info') };
const RANK: Record<Severity, number> = { critical: 0, warning: 1, info: 2 };
const STATE_LABELS: Record<string, string> = {
  open: tx('Not started'), reviewing: tx('In review'), escalated: tx('Escalated'), closed: tx('Closed'),
};

const clock = (d: Date) => d.toLocaleString(undefined, {
  day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit',
});
const timeOf = (iso: string) => new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
function ago(iso: string, now: number) {
  const m = Math.max(0, Math.round((now - new Date(iso).getTime()) / 60_000));
  if (m < 1) return tx('just now');
  if (m < 60) return `${m} ${tx('min ago')}`;
  const h = Math.round(m / 60);
  if (h < 48) return `${h} ${tx('h ago')}`;
  return `${Math.round(h / 24)} ${tx('days ago')}`;
}

function useNow(ms = 1000) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [ms]);
  return now;
}

/**
 * The live overview of loss prevention: the most pressing open case up front, the latest cases beside the figures
 * that matter now. Read-only: it never opens a case's detail (that is recorded as a view) or a clip by itself; a
 * still or clip opens only on a click, and that view is recorded with the user's name like everywhere else.
 */
export function IntelligenceOverview({ vision, canEvidence, insights, engineLabels, onOpenCase, onViewAll }: {
  vision: boolean;
  canEvidence: boolean;
  insights: boolean;
  engineLabels: Record<string, string>;
  onOpenCase: (id: string) => void;
  onViewAll: () => void;
}) {
  const now = useNow();
  const rootRef = useRef<HTMLDivElement>(null);
  const [full, setFull] = useState(false);
  const dash = useLpDashboard(LIVE_MS);
  const cases = useLpCases({ page: 1, pageSize: 25 }, LIVE_MS);
  // The critical ones may be older than the latest page: read them on their own so the alert card never misses one.
  const critical = useLpCases({ severity: 'critical', page: 1, pageSize: 25 }, LIVE_MS);
  const open = (cases.data?.data ?? []).filter((c) => c.state !== 'closed');
  const candidates = [...(critical.data?.data ?? []).filter((c) => c.state !== 'closed'), ...open];
  const featured = candidates.sort((a, b) => RANK[a.severity] - RANK[b.severity]
    || (a.state === 'escalated' ? -1 : 0) - (b.state === 'escalated' ? -1 : 0)
    || b.openedAt.localeCompare(a.openedAt))[0];
  const branches = Object.fromEntries((dash.data?.byBranch ?? []).map((b) => [b.branchId, b.branchName]));
  const live = !dash.isError && !cases.isError;

  useEffect(() => {
    const onChange = () => setFull(document.fullscreenElement === rootRef.current);
    document.addEventListener('fullscreenchange', onChange);
    return () => document.removeEventListener('fullscreenchange', onChange);
  }, []);
  const toggleFull = () => {
    try {
      if (document.fullscreenElement) void document.exitFullscreen?.();
      else void rootRef.current?.requestFullscreen?.();
    } catch { /* not supported */ }
  };

  return (
    <div ref={rootRef} className={cn('space-y-4', full && 'overflow-y-auto bg-background p-6')}>
      {/* Header strip */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-gradient-to-br from-blue-600 to-indigo-600 text-white shadow-md shadow-blue-600/30">
            <BrainCircuit className="h-6 w-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold tracking-tight">{tx('AI Operations Intelligence')}</h2>
            <p className="text-sm text-muted-foreground">{tx('Real-time monitoring & alerts')}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <span className={cn('inline-flex items-center gap-2 rounded-full border px-3 py-1.5 text-sm font-semibold',
            live ? 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-300'
              : 'border-red-200 bg-red-50 text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-300')}>
            <span className="relative flex h-2.5 w-2.5">
              {live && <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />}
              <span className={cn('relative inline-flex h-2.5 w-2.5 rounded-full', live ? 'bg-emerald-500' : 'bg-red-500')} />
            </span>
            {live ? tx('Live') : tx('Offline')}
          </span>
          <span className="hidden rounded-full border bg-muted/50 px-3 py-1.5 text-sm tabular-nums text-muted-foreground sm:inline">
            {clock(new Date(now))}
          </span>
          <button type="button" onClick={toggleFull} title={full ? tx('Exit full screen') : tx('Full screen')}
            className="rounded-full border p-2 text-muted-foreground transition hover:bg-muted hover:text-foreground">
            {full ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />}
          </button>
        </div>
      </div>

      {/* The alert up front */}
      {cases.isLoading || critical.isLoading ? (
        <div className="h-[26rem] animate-pulse rounded-2xl border bg-muted/40" />
      ) : cases.isError ? (
        <div className="rounded-2xl border border-destructive/40 p-6 text-destructive">{tx('Could not load the cases. Try again.')}</div>
      ) : featured ? (
        <FeaturedAlert c={featured} now={now} branchName={branches[featured.branchId] ?? null}
          engineLabel={engineLabels[featured.engine] ?? featured.engine} showEvidence={vision && canEvidence}
          onOpen={() => onOpenCase(featured.id)} />
      ) : (
        <AllClear />
      )}

      {/* Recent alerts + operational insights */}
      <div className="grid gap-4 xl:grid-cols-[1.6fr_1fr]">
        <Panel icon={<Bell className="h-5 w-5 text-red-500" />} title={tx('Recent alerts')}
          action={(
            <button type="button" onClick={onViewAll}
              className="inline-flex items-center gap-1 text-sm font-medium text-blue-600 hover:underline dark:text-blue-400">
              {tx('View all')}<ArrowRight className="h-4 w-4" />
            </button>
          )}>
          {open.length === 0 && !cases.isLoading && (
            <p className="px-4 py-6 text-center text-sm text-muted-foreground">{tx('No open alerts.')}</p>
          )}
          <ul className="divide-y">
            {open.slice(0, 6).map((c) => {
              const t = TONE[c.severity];
              const top = c.id === featured?.id;
              return (
                <li key={c.id}>
                  <button type="button" onClick={() => onOpenCase(c.id)}
                    className={cn('grid w-full grid-cols-[auto_4.5rem_1fr_auto] items-center gap-3 px-4 py-3 text-left text-sm transition hover:bg-muted/60 sm:grid-cols-[auto_4.5rem_1fr_auto_8rem]',
                      top && 'bg-red-50/70 dark:bg-red-950/20')}>
                    <span className={cn('h-2.5 w-2.5 rounded-full', t.dot)} />
                    <span className={cn('tabular-nums', top ? t.text : 'text-muted-foreground')}>{timeOf(c.openedAt)}</span>
                    <span className="min-w-0">
                      <span className="block truncate font-semibold">{c.title}</span>
                      <span className="block truncate text-xs text-muted-foreground">
                        {c.ruleCode} · {engineLabels[c.engine] ?? c.engine}{c.subjectName ? ` · ${c.subjectName}` : ''}
                      </span>
                    </span>
                    <span className={cn('rounded-full px-2.5 py-0.5 text-xs font-semibold', t.pill)}>{PILL_LABEL[c.severity]}</span>
                    <span className="hidden truncate text-right text-xs text-muted-foreground sm:block">
                      {STATE_LABELS[c.state]}<br />{ago(c.openedAt, now)}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </Panel>
        <OperationalInsights insights={insights} />
      </div>
      {dash.data && <p className="text-xs text-muted-foreground">{dash.data.disclaimer}</p>}
    </div>
  );
}

function Panel({ icon, title, action, children }: { icon: ReactNode; title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="overflow-hidden rounded-2xl border bg-card shadow-sm">
      <header className="flex items-center justify-between gap-2 border-b px-4 py-3">
        <h3 className="flex items-center gap-2 font-semibold">{icon}{title}</h3>
        {action}
      </header>
      {children}
    </section>
  );
}

function AllClear() {
  return (
    <section className="overflow-hidden rounded-2xl border-2 border-emerald-500/70 bg-card shadow-sm">
      <div className="flex items-center gap-3 bg-gradient-to-r from-emerald-600 to-teal-500 px-5 py-3 text-white">
        <ShieldCheck className="h-8 w-8" />
        <p className="text-2xl font-bold">{tx('All clear')}</p>
      </div>
      <div className="flex flex-col items-center gap-2 px-6 py-12 text-center">
        <CheckCircle2 className="h-14 w-14 text-emerald-500" />
        <p className="text-lg font-semibold">{tx('No open variances')}</p>
        <p className="max-w-md text-sm text-muted-foreground">
          {tx('Recorded sales, voids, cash and stock counts are reconciled on a schedule. New cases appear here as soon as a run finds one.')}
        </p>
      </div>
    </section>
  );
}

function FeaturedAlert({ c, now, branchName, engineLabel, showEvidence, onOpen }: {
  c: LpCase; now: number; branchName: string | null; engineLabel: string; showEvidence: boolean; onOpen: () => void;
}) {
  const t = TONE[c.severity];
  const evidence = useLpEvidenceList({ caseId: c.id, pageSize: 10 }, showEvidence);
  const items = evidence.data?.data ?? [];
  const best = items.find((e) => e.kind === 'snapshot' && e.state === 'uploaded')
    ?? items.find((e) => e.state === 'uploaded') ?? items[0];
  const camera = best ? { id: best.cameraId, name: best.cameraName ?? tx('Camera') } : null;
  const [live, setLive] = useState(false);
  const [footage, setFootage] = useState(false);
  const exposure = c.exposureAmount === null ? null : Number(c.exposureAmount);

  return (
    <section className={cn('overflow-hidden rounded-2xl border-2 bg-card shadow-lg', t.border)}>
      <div className={cn('flex flex-wrap items-center justify-between gap-3 px-5 py-3 text-white', t.banner)}>
        <div className="flex items-center gap-4">
          <AlertTriangle className="h-9 w-9 shrink-0 drop-shadow" />
          <p className="text-2xl font-extrabold tracking-tight sm:text-3xl">{t.label}!</p>
        </div>
        <span className="rounded-lg border-2 border-white/80 bg-white/10 px-3 py-1 text-sm font-bold backdrop-blur sm:text-base">
          {t.priority}
        </span>
      </div>

      <div className="grid lg:grid-cols-[1.25fr_1fr]">
        {/* The picture, or the figures when there is none */}
        <div className="relative min-h-[18rem] bg-slate-950 lg:min-h-[24rem]">
          <EvidenceMedia evidence={showEvidence ? best : undefined} loading={showEvidence && evidence.isLoading}
            c={c} tone={t} engineLabel={engineLabel} />
          <div className="pointer-events-none absolute left-3 top-3 rounded-md bg-black/70 px-2.5 py-1 text-sm font-semibold text-white tabular-nums">
            {new Date(best?.startedAt ?? c.openedAt).toLocaleString()}
          </div>
          <div className="pointer-events-none absolute right-3 top-3 rounded-md bg-black/70 px-2.5 py-1 text-sm font-semibold text-white">
            {camera?.name ?? c.caseNumber}
          </div>
        </div>

        {/* What was found */}
        <div className="flex flex-col gap-4 p-5">
          <div>
            <p className={cn('text-xs font-bold uppercase tracking-wider', t.text)}>{c.ruleCode} · {engineLabel}</p>
            <h3 className="mt-1 text-2xl font-bold leading-tight">{c.title}</h3>
            <p className="mt-1 text-muted-foreground">
              {tx('Recorded figures differ from what was counted or expected. It does not state a cause until a reviewer does.')}
            </p>
          </div>
          <dl className="space-y-2.5 text-sm">
            <Row icon={<Clock className={cn('h-5 w-5', t.text)} />} label={tx('Time')}
              value={`${new Date(c.openedAt).toLocaleString()} · ${ago(c.openedAt, now)}`} />
            <Row icon={<Calendar className="h-5 w-5 text-muted-foreground" />} label={tx('Business day')} value={c.businessDate} />
            <Row icon={<Eye className="h-5 w-5 text-muted-foreground" />} label={tx('Status')} value={STATE_LABELS[c.state]} />
            <Row icon={<MapPin className="h-5 w-5 text-rose-500" />} label={tx('Location')} value={branchName ?? '—'} />
            {camera && <Row icon={<Camera className="h-5 w-5 text-foreground" />} label={tx('Camera')} value={camera.name} />}
            {c.subjectName && <Row icon={<User className="h-5 w-5 text-indigo-500" />} label={tx('Person')} value={c.subjectName} />}
            <Row icon={<Wallet className="h-5 w-5 text-emerald-600" />} label={tx('Exposure')}
              value={exposure === null ? '—' : num(exposure)} hint={c.exposureMethod ?? undefined} />
          </dl>
          <div className={cn('flex items-center gap-3 rounded-xl border p-3', t.tint)}>
            <div className="flex h-14 w-14 shrink-0 items-center justify-center rounded-lg bg-slate-900 text-white">
              {best?.kind === 'clip' ? <Film className="h-6 w-6" /> : <FileText className="h-6 w-6" />}
            </div>
            <div className="text-sm">
              <p className={cn('font-bold', t.text)}>{tx('Evidence')}:</p>
              <p>
                {showEvidence
                  ? (items.length > 0
                    ? `${items.length} ${tx('clip(s) or still(s) kept for this case.')}`
                    : tx('No video kept for this case yet. Open the case to see the records behind the figures.'))
                  : tx('The records behind the figures are in the case.')}
                {c.assigneeName ? ` ${tx('Assigned to')} ${c.assigneeName}.` : ''}
              </p>
            </div>
          </div>
          <div className="mt-auto flex flex-wrap gap-2 [&>*]:min-w-[10rem] [&>*]:flex-1">
            <ActionButton className="bg-red-600 text-white hover:bg-red-700" icon={<Eye className="h-5 w-5" />}
              title={tx('Review case')} sub={c.caseNumber} onClick={onOpen} />
            {showEvidence && camera ? (
              <ActionButton className="bg-blue-600 text-white hover:bg-blue-700" icon={<Play className="h-5 w-5 fill-current" />}
                title={tx('View live')} sub={tx('Camera')} onClick={() => setLive(true)} />
            ) : null}
            {showEvidence && (
              <ActionButton className="border bg-muted text-foreground hover:bg-muted/70" icon={<Video className="h-5 w-5" />}
                title={tx('Footage')} sub={tx('Around this moment')} onClick={() => setFootage(true)} />
            )}
          </div>
        </div>
      </div>
      {live && camera && <LiveViewDialog camera={camera} onClose={() => setLive(false)} />}
      {footage && (
        <FootageDialog query={{ branchId: c.branchId, at: best?.startedAt ?? c.openedAt }} label={c.caseNumber}
          onClose={() => setFootage(false)} />
      )}
    </section>
  );
}

function Row({ icon, label, value, hint }: { icon: ReactNode; label: string; value: string; hint?: string }) {
  return (
    <div className="flex items-start gap-3">
      <span className="shrink-0">{icon}</span>
      <dt className="w-28 shrink-0 font-medium">{label}</dt>
      <dd className="min-w-0">
        <span className="tabular-nums">{value}</span>
        {hint && <span className="block text-xs text-muted-foreground">{hint}</span>}
      </dd>
    </div>
  );
}

function ActionButton({ icon, title, sub, onClick, className }: {
  icon: ReactNode; title: string; sub?: string; onClick: () => void; className: string;
}) {
  return (
    <button type="button" onClick={onClick}
      className={cn('flex items-center gap-3 rounded-xl px-4 py-3 text-left shadow-sm transition active:scale-[0.98]', className)}>
      {icon}
      <span className="leading-tight">
        <span className="block whitespace-nowrap font-semibold">{title}</span>
        {sub && <span className="block text-xs opacity-80">{sub}</span>}
      </span>
    </button>
  );
}

/**
 * The case's still or clip, opened on a click (opening it is recorded in the evidence trail), framed like a camera
 * view. With no video, the case's facts in a heads-up card instead.
 */
function EvidenceMedia({ evidence, loading, c, tone, engineLabel }: {
  evidence?: Evidence; loading: boolean; c: LpCase; tone: (typeof TONE)[Severity]; engineLabel: string;
}) {
  const [media, setMedia] = useState<Blob | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const url = useObjectUrl(media);
  useEffect(() => { setMedia(null); setProblem(null); }, [evidence?.id]);

  const show = async () => {
    if (!evidence) return;
    setBusy(true);
    setProblem(null);
    try {
      const r = await fetchLpEvidenceContent(evidence.id);
      setMedia(evidence.contentType && r.blob.type !== evidence.contentType
        ? new Blob([r.blob], { type: evidence.contentType }) : r.blob);
    } catch (e) {
      setProblem(await blobErrorMessage(e, tx('The clip could not be opened')));
    } finally {
      setBusy(false);
    }
  };

  if (url && evidence) {
    return (
      <div className="relative h-full w-full">
        {evidence.kind === 'snapshot'
          ? <img src={url} alt="" className="absolute inset-0 h-full w-full object-cover" />
          : <video src={url} controls autoPlay className="absolute inset-0 h-full w-full bg-black object-contain" />}
        <Brackets frame={tone.frame} />
        <span className={cn('absolute bottom-3 left-3 rounded-md px-2.5 py-1 text-sm font-semibold text-white shadow', tone.tag)}>
          {c.ruleCode}: {engineLabel}
        </span>
      </div>
    );
  }

  return (
    <div className="absolute inset-0 overflow-hidden">
      {/* camera-grid backdrop */}
      <div className="absolute inset-0 opacity-30"
        style={{ backgroundImage: 'linear-gradient(rgba(148,163,184,.15) 1px, transparent 1px), linear-gradient(90deg, rgba(148,163,184,.15) 1px, transparent 1px)', backgroundSize: '32px 32px' }} />
      <div className="absolute inset-0 bg-gradient-to-br from-blue-900/30 via-transparent to-slate-900/60" />
      <Brackets frame={tone.frame} />
      <div className="relative flex h-full flex-col items-center justify-center gap-5 p-6 pt-14">
        <HudCard c={c} engineLabel={engineLabel} />
        {loading && <p className="text-sm text-slate-300">{tx('Looking for video…')}</p>}
        {evidence && (evidence.state === 'uploaded' ? (
          <button type="button" disabled={busy} onClick={() => void show()}
            className="inline-flex items-center gap-2 rounded-full bg-white/95 px-5 py-2.5 text-sm font-semibold text-slate-900 shadow-lg transition hover:bg-white disabled:opacity-60">
            {evidence.kind === 'snapshot' ? <Camera className="h-4 w-4" /> : <Play className="h-4 w-4 fill-current" />}
            {busy ? tx('Opening…') : evidence.kind === 'snapshot' ? tx('Show still') : tx('Play clip')}
          </button>
        ) : (
          <p className="rounded-full bg-white/10 px-4 py-1.5 text-xs text-slate-200">
            {tx('Video for this case')}: {evidence.state} — {tx('open the case to ask for it')}
          </p>
        ))}
        {evidence?.state === 'uploaded' && !busy && (
          <p className="text-xs text-slate-400">{tx('Opening it is recorded with your name.')}</p>
        )}
        {problem && <p className="text-xs text-red-300">{problem}</p>}
      </div>
    </div>
  );
}

/** Corner brackets, like a detection frame over the camera view. */
function Brackets({ frame }: { frame: string }) {
  const corner = 'pointer-events-none absolute h-10 w-10 border-[3px]';
  return (
    <>
      <span className={cn(corner, frame, 'left-[12%] top-[18%] rounded-tl-lg border-b-0 border-r-0')} />
      <span className={cn(corner, frame, 'right-[12%] top-[18%] rounded-tr-lg border-b-0 border-l-0')} />
      <span className={cn(corner, frame, 'bottom-[10%] left-[12%] rounded-bl-lg border-r-0 border-t-0')} />
      <span className={cn(corner, frame, 'bottom-[10%] right-[12%] rounded-br-lg border-l-0 border-t-0')} />
    </>
  );
}

/** The "POS AI insight" card: the case's facts at a glance. */
function HudCard({ c, engineLabel }: { c: LpCase; engineLabel: string }) {
  const rows: [string, string][] = [
    [tx('Rule'), `${c.ruleCode}`],
    [tx('Area'), engineLabel],
    [tx('Status'), STATE_LABELS[c.state]],
    [tx('Case'), c.caseNumber],
  ];
  return (
    <div className="w-full max-w-xs overflow-hidden rounded-xl border border-white/10 bg-slate-900/85 shadow-2xl backdrop-blur">
      <div className="flex items-center gap-2 bg-gradient-to-r from-blue-700 to-blue-600 px-4 py-2.5 text-white">
        <Cpu className="h-5 w-5" />
        <span className="font-bold">{tx('POS AI Insight')}</span>
      </div>
      <ul className="space-y-2 px-4 py-3 text-sm text-white">
        {rows.map(([k, v]) => (
          <li key={k} className="flex items-center gap-2">
            <CheckCircle2 className="h-5 w-5 shrink-0 fill-emerald-500 text-slate-900" />
            <span className="text-slate-300">{k}:</span>
            <span className="truncate font-semibold">{v}</span>
          </li>
        ))}
        <li className="flex items-center gap-2">
          <Clock className="h-5 w-5 shrink-0 text-sky-400" />
          <span className="text-slate-300">{tx('Opened')}:</span>
          <span className="font-semibold">{timeOf(c.openedAt)}</span>
        </li>
      </ul>
    </div>
  );
}

function OperationalInsights({ insights }: { insights: boolean }) {
  const dash = useLpDashboard(LIVE_MS);
  // Only with the insights licence feature: otherwise the route answers 402 and opens the licence dialog.
  const summary = useInsightsSummary({}, insights);
  const t = dash.data?.totals;
  const m = summary.data?.metrics;
  const trend = dash.data?.trend ?? [];
  const peak = Math.max(1, ...trend.map((d) => Number(d.cases)));
  const rows: { icon: ReactNode; value: string; label: string; hint: string }[] = [];
  if (m) {
    rows.push({
      icon: <CheckCircle2 className="h-7 w-7 fill-emerald-500 text-white" />, value: pct(m.cash.balancedRate),
      label: tx('Cash sessions balanced'), hint: `${m.cash.sessions} ${tx('sessions')}`,
    });
  }
  rows.push(
    {
      icon: <AlertTriangle className="h-7 w-7 fill-red-500 text-white" />, value: String(t?.critical ?? '—'),
      label: tx('Critical open cases'), hint: `${t?.open ?? 0} ${tx('open in all')}`,
    },
    {
      icon: <Bell className="h-7 w-7 fill-amber-400 text-amber-500" />, value: String(t?.escalated ?? '—'),
      label: tx('Escalated'), hint: `${t?.reviewing ?? 0} ${tx('in review')} · ${t?.notStarted ?? 0} ${tx('not started')}`,
    },
  );
  if (m) {
    rows.push({
      icon: <Info className="h-7 w-7 fill-blue-500 text-white" />, value: pct(m.sales.voids.amountRate),
      label: tx('Void rate'), hint: `${m.sales.count} ${tx('sales')} · ${pct(m.sales.refunds.amountRate)} ${tx('refunds')}`,
    });
  } else {
    rows.push({
      icon: <Wallet className="h-7 w-7 text-emerald-600" />, value: num(t?.exposure),
      label: tx('Exposure of open cases'), hint: tx('An estimate; see each case for its method'),
    });
  }
  return (
    <Panel icon={<BarChart3 className="h-5 w-5 text-blue-600" />} title={tx('Operational insights')}>
      <ul className="divide-y">
        {rows.map((r) => (
          <li key={r.label} className="flex items-center gap-4 px-4 py-3">
            {r.icon}
            <span className="w-16 text-xl font-bold tabular-nums">{r.value}</span>
            <span className="min-w-0 text-sm">
              <span className="block font-semibold">{r.label}</span>
              <span className="block truncate text-xs text-muted-foreground">{r.hint}</span>
            </span>
          </li>
        ))}
      </ul>
      <div className="border-t px-4 py-3">
        <p className="mb-2 text-xs font-medium text-muted-foreground">{tx('Cases per day, last 30 days')}</p>
        <div className="flex h-14 items-end gap-0.5">
          {trend.length === 0 && <p className="text-xs text-muted-foreground">{tx('No cases in this period.')}</p>}
          {trend.map((d) => (
            <div key={d.day} title={`${d.day}: ${d.cases}`}
              className="flex-1 rounded-t bg-gradient-to-t from-blue-600 to-sky-400"
              style={{ height: `${Math.max(6, (Number(d.cases) / peak) * 100)}%` }} />
          ))}
        </div>
      </div>
    </Panel>
  );
}

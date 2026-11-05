import { useMemo, useState } from 'react';
import { Link, Navigate, useParams, useSearchParams } from 'react-router-dom';
import { ChevronLeft, Search } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { ListPageHeader } from '@/components/list';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useReportCatalog, type ReportParams } from '@/features/sales/report-center-api';
import { ReportViewer } from './ReportViewer';
import { REPORT_PAGES } from './report-pages';

/**
 * A report center page (Sales → Reporting → …): its reports listed as tabs (side rail on desktop, picker on phones),
 * the selected one rendered by the shared viewer. Report and filters live in the URL so links and drill-downs are
 * shareable.
 */
export function SalesReportPage() {
  const { page: slug } = useParams();
  const page = REPORT_PAGES.find((p) => p.slug === slug);
  const { data: catalog, isLoading, isError } = useReportCatalog();
  const [search, setSearch] = useSearchParams();
  const [q, setQ] = useState('');

  const groups = useMemo(() => (catalog?.categories ?? []).filter((c) => page?.categories.includes(c.key)), [catalog, page]);
  const all = groups.flatMap((g) => g.reports);
  const state: ReportParams = useMemo(() => Object.fromEntries(search.entries()), [search]);
  const current = all.find((r) => r.key === state.r) ?? all[0];

  if (!page) return <Navigate to="/sales/reports" replace />;

  const update = (patch: ReportParams) => {
    const next = new URLSearchParams(search);
    for (const [k, v] of Object.entries(patch)) {
      if (v) next.set(k, v); else next.delete(k);
    }
    if (patch.period && patch.period !== 'custom') {
      next.delete('from');
      next.delete('to');
    }
    setSearch(next, { replace: true });
  };
  const select = (key: string) => {
    const next = new URLSearchParams(search);
    next.set('r', key);
    setSearch(next);
  };
  const needle = q.trim().toLowerCase();

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2 text-sm text-muted-foreground print:hidden">
        <Link to="/sales/reports" className="inline-flex items-center gap-1 hover:text-foreground"><ChevronLeft className="h-4 w-4" />{tx('Sales reports')}</Link>
      </div>
      <ListPageHeader icon={page.icon} title={tx(page.title)} description={tx(page.description)} />

      {isLoading && <div className="h-40 animate-pulse rounded-xl border bg-muted/30" />}
      {isError && <p className="text-sm text-rose-600">{tx('Could not load the report list. Try again in a moment.')}</p>}
      {!isLoading && !isError && all.length === 0 && <p className="text-sm text-muted-foreground">{tx('You do not have access to any report on this page.')}</p>}

      {current && (
        <div className="grid gap-4 lg:grid-cols-[250px_minmax(0,1fr)]">
          {/* phones / tablets: report picker */}
          <select className="h-10 rounded-md border bg-background px-3 text-sm lg:hidden print:hidden" value={current.key} onChange={(e) => select(e.target.value)}>
            {groups.map((g) => (
              <optgroup key={g.key} label={g.title}>
                {g.reports.map((r) => <option key={r.key} value={r.key}>{r.title}</option>)}
              </optgroup>
            ))}
          </select>

          {/* desktop: report tabs as a side rail */}
          <aside className="hidden lg:block print:hidden">
            <div className="sticky top-4 space-y-3 rounded-xl border bg-card p-2 shadow-sm">
              <div className="relative">
                <Search className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
                <Input className="h-9 pl-8" placeholder={tx('Find a report…')} value={q} onChange={(e) => setQ(e.target.value)} />
              </div>
              <nav className="max-h-[calc(100vh-12rem)] space-y-3 overflow-y-auto pr-1" role="tablist" aria-orientation="vertical">
                {groups.map((g) => {
                  const items = g.reports.filter((r) => !needle || `${r.title} ${r.description}`.toLowerCase().includes(needle));
                  if (items.length === 0) return null;
                  return (
                    <div key={g.key}>
                      {groups.length > 1 && <p className="px-2 pb-1 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{g.title}</p>}
                      {items.map((r) => (
                        <button key={r.key} type="button" role="tab" aria-selected={r.key === current.key} onClick={() => select(r.key)} title={r.description}
                          className={cn('block w-full truncate rounded-md px-2.5 py-1.5 text-left text-sm transition-colors',
                            r.key === current.key ? 'bg-primary text-primary-foreground' : 'text-foreground/80 hover:bg-muted')}>
                          {r.title}
                        </button>
                      ))}
                    </div>
                  );
                })}
              </nav>
            </div>
          </aside>

          <ReportViewer key={current.key} def={current} state={state} onChange={update} />
        </div>
      )}
    </div>
  );
}

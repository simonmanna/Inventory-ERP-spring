/**
 * One Report Library category (FR-0, ADR-S043) as a card grid, opened from the Reports sidebar section. Engine
 * reports open in the generic viewer; reports with their own screen open that screen.
 */
import { useMemo, useState } from 'react';
import { Link, Navigate, NavLink, useParams } from 'react-router-dom';
import { ArrowLeft, ArrowRight, ExternalLink, Search, Sparkles } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { useReportLibrary, type ReportDefinition } from '@/features/reports/library-api';
import { LEVEL_STYLE, REPORT_CATEGORIES, reportCategoryPath } from '@/features/reports/report-categories';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';

function ReportCard({ r }: { r: ReportDefinition }) {
  const to = r.engine ? `/reports/library/${r.id}` : r.webRoute;
  const body = (
    <>
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 items-center gap-1.5 text-sm font-semibold text-gray-900">
          {r.engine && <Sparkles className="h-4 w-4 shrink-0 text-blue-600" aria-label={tx('Report engine')} />}
          <span className="line-clamp-2">{r.title}</span>
        </div>
        <Badge variant="outline" className={`shrink-0 text-[10px] ${LEVEL_STYLE[r.level] ?? ''}`}>{tx(r.level.toLowerCase())}</Badge>
      </div>
      <p className="mt-2 line-clamp-3 flex-1 text-xs text-gray-500">{r.description ?? ' '}</p>
      <div className="mt-3 flex items-center justify-between border-t pt-2 text-xs">
        <span className="text-gray-400">
          {r.engine ? tx('Engine report') : r.webRoute ? tx('Dedicated screen') : tx('Available through the API')}
        </span>
        {to && (
          <span className="flex items-center gap-1 font-medium text-blue-600">
            {tx('Open')} {r.engine ? <ArrowRight className="h-3 w-3" /> : <ExternalLink className="h-3 w-3" />}
          </span>
        )}
      </div>
    </>
  );
  const cls = 'flex h-full flex-col rounded-lg border bg-white p-4 shadow-sm';
  if (!to) return <div className={`${cls} opacity-60`} title={tx('Available through the API')}>{body}</div>;
  return <Link to={to} className={`${cls} transition hover:border-blue-300 hover:shadow-md`}>{body}</Link>;
}

export function ReportCategoryPage() {
  const { slug } = useParams<{ slug: string }>();
  const meta = REPORT_CATEGORIES.find((c) => c.slug === slug);
  const { data, isLoading } = useReportLibrary();
  const [q, setQ] = useState('');
  const [level, setLevel] = useState('');

  const category = (data ?? []).find((c) => c.code === meta?.code);
  const reports = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return (category?.reports ?? []).filter((r) =>
      (!level || r.level === level)
      && (!needle || r.title.toLowerCase().includes(needle) || (r.description ?? '').toLowerCase().includes(needle)));
  }, [category, q, level]);

  if (!meta) return <Navigate to="/reports/library" replace />;
  const Icon = meta.icon;

  return (
    <div className="space-y-4">
      <Link to="/reports/library" className="flex items-center gap-1 text-xs text-gray-500 hover:text-blue-700">
        <ArrowLeft className="h-3 w-3" /> {tx('Report Library')}
      </Link>
      <div className="border-l-4 border-[#3b82f6] pl-4 space-y-1">
        <h1 className="flex items-center gap-2 text-3xl font-bold tracking-tight text-gray-900">
          <Icon className="h-7 w-7 text-blue-600" /> {tx(category?.title ?? meta.title)}
        </h1>
        <p className="text-sm text-gray-500">
          <span className="rounded bg-blue-600 px-1.5 py-0.5 text-[10px] font-bold text-white">{meta.code}</span>
          {category && <> · {category.reports.length} {tx('reports')}</>}
        </p>
      </div>

      <div className="flex gap-1.5 overflow-x-auto pb-1">
        {REPORT_CATEGORIES.map((c) => (
          <NavLink
            key={c.slug}
            to={reportCategoryPath(c.slug)}
            className={({ isActive }) => cn(
              'shrink-0 rounded-full border px-3 py-1 text-xs',
              isActive ? 'border-blue-600 bg-blue-600 text-white' : 'bg-white text-gray-600 hover:border-blue-300',
            )}
          >
            {tx(c.title)}
          </NavLink>
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-3 rounded-lg border bg-white p-3 shadow-sm">
        <div className="relative min-w-[240px] flex-1">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-gray-400" />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder={tx('Search reports')} className="h-9 pl-8 text-sm" />
        </div>
        <select value={level} onChange={(e) => setLevel(e.target.value)} className="h-9 rounded-md border px-2 text-sm" aria-label={tx('Level')}>
          <option value="">{tx('All levels')}</option>
          <option value="FINANCIAL">{tx('Financial (posted GL)')}</option>
          <option value="MANAGEMENT">{tx('Management')}</option>
          <option value="OPERATIONAL">{tx('Operational')}</option>
        </select>
      </div>

      {isLoading ? (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
          {Array.from({ length: 8 }).map((_, i) => <Skeleton key={i} className="h-36" />)}
        </div>
      ) : reports.length === 0 ? (
        <p className="rounded-lg border bg-white p-8 text-center text-sm text-gray-500">{tx('No reports match.')}</p>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
          {reports.map((r) => <ReportCard key={r.id} r={r} />)}
        </div>
      )}
    </div>
  );
}

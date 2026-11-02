/**
 * Report Library (FR-0, ADR-S043): every accounting report in 15 categories. Engine reports open in the generic
 * viewer; reports with their own screen open that screen.
 */
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { BookOpenCheck, ExternalLink, Search, Sparkles } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { useReportLibrary, type ReportDefinition } from '@/features/reports/library-api';
import { tx } from '@/lib/i18n/tx';

const LEVEL_STYLE: Record<string, string> = {
  FINANCIAL: 'bg-blue-50 text-blue-700 border-blue-200',
  OPERATIONAL: 'bg-gray-50 text-gray-600 border-gray-200',
  MANAGEMENT: 'bg-amber-50 text-amber-700 border-amber-200',
};

function ReportLink({ r }: { r: ReportDefinition }) {
  const body = (
    <div className="flex items-start justify-between gap-2">
      <div className="min-w-0">
        <div className="flex items-center gap-1.5 text-sm font-medium text-gray-900">
          {r.engine && <Sparkles className="h-3.5 w-3.5 shrink-0 text-blue-600" aria-label={tx('Report engine')} />}
          <span className="truncate">{r.title}</span>
        </div>
        {r.description && <p className="mt-0.5 line-clamp-2 text-xs text-gray-500">{r.description}</p>}
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <Badge variant="outline" className={`text-[10px] ${LEVEL_STYLE[r.level] ?? ''}`}>{tx(r.level.toLowerCase())}</Badge>
        {!r.engine && r.webRoute && <ExternalLink className="h-3 w-3 text-gray-400" />}
      </div>
    </div>
  );
  const cls = 'block rounded-md border border-transparent px-3 py-2 hover:border-gray-200 hover:bg-gray-50';
  if (r.engine) return <Link to={`/reports/library/${r.id}`} className={cls}>{body}</Link>;
  if (r.webRoute) return <Link to={r.webRoute} className={cls}>{body}</Link>;
  return <div className={`${cls} opacity-60`} title={tx('Available through the API')}>{body}</div>;
}

export function ReportLibraryPage() {
  const { data, isLoading } = useReportLibrary();
  const [q, setQ] = useState('');
  const [level, setLevel] = useState('');

  const categories = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return (data ?? []).map((c) => ({
      ...c,
      reports: c.reports.filter((r) =>
        (!level || r.level === level)
        && (!needle || r.title.toLowerCase().includes(needle) || (r.description ?? '').toLowerCase().includes(needle))),
    })).filter((c) => c.reports.length > 0);
  }, [data, q, level]);
  const total = (data ?? []).reduce((n, c) => n + c.reports.length, 0);

  return (
    <div className="space-y-4">
      <div className="border-l-4 border-[#3b82f6] pl-4 space-y-1">
        <h1 className="flex items-center gap-2 text-3xl font-bold tracking-tight text-gray-900">
          <BookOpenCheck className="h-7 w-7 text-blue-600" /> {tx('Report Library')}
        </h1>
        <p className="text-sm text-gray-500">
          {tx('Financial statements, ledgers, receivables, payables, tax, costing and quarry reports in one place.')}
          {total > 0 && <> · {total} {tx('reports')}</>}
        </p>
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
        <span className="flex items-center gap-1 text-xs text-gray-500">
          <Sparkles className="h-3.5 w-3.5 text-blue-600" /> {tx('Engine report: filters, comparatives, drill-down, export, schedule')}
        </span>
      </div>

      {isLoading ? (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} className="h-48" />)}
        </div>
      ) : categories.length === 0 ? (
        <p className="rounded-lg border bg-white p-8 text-center text-sm text-gray-500">{tx('No reports match.')}</p>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {categories.map((c) => (
            <section key={c.key} className="rounded-lg border bg-white shadow-sm">
              <header className="flex items-center gap-2 border-b px-4 py-2.5">
                <span className="rounded bg-blue-600 px-1.5 py-0.5 text-[10px] font-bold text-white">{c.code}</span>
                <h2 className="text-sm font-semibold text-gray-900">{tx(c.title)}</h2>
                <span className="ml-auto text-xs text-gray-400">{c.reports.length}</span>
              </header>
              <div className="p-1.5">
                {c.reports.map((r) => <ReportLink key={r.id} r={r} />)}
              </div>
            </section>
          ))}
        </div>
      )}
    </div>
  );
}

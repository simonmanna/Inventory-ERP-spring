/**
 * Generic report viewer (FR-0, ADR-S043): filter bar built from the report definition, comparative columns,
 * collapsible account detail, drill-down to journal lines and drill-through to source documents, integrity checks,
 * export (CSV / XLSX / PDF), print, saved views, schedules and the reproducibility check of a run.
 */
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  ArrowLeft, CalendarClock, CheckCircle2, ChevronLeft, ChevronRight, Download, Play, Printer, Save, ShieldCheck,
  Trash2, XCircle,
} from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import {
  decodeRequest, downloadLibraryReport, encodeRequest, useAccountOptions, useBranchOptions, useBudgetOptions,
  useCostCenterOptions, useCreateSchedule, useCreateView, useDeleteView, useJournalOptions, usePartnerOptions,
  useReportDefinition, useRunReport, useSavedViews, useVerifyRun,
  type Drill, type ReportColumn, type ReportRequest, type ReportResult, type ReportRow,
} from '@/features/reports/library-api';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';

const PRESET_LABELS: Record<string, string> = {
  TODAY: 'Today', YESTERDAY: 'Yesterday', THIS_MONTH: 'This month', LAST_MONTH: 'Last month',
  THIS_QUARTER: 'This quarter', LAST_QUARTER: 'Last quarter', THIS_YEAR: 'This year', LAST_YEAR: 'Last year',
  FISCAL_YTD: 'Fiscal year to date', LAST_FISCAL_YEAR: 'Last fiscal year',
};
const COMPARE_LABELS: Record<string, string> = {
  PRIOR_PERIOD: 'Prior period', PRIOR_YEAR: 'Prior year', BUDGET: 'Budget', VARIANCE: 'Variance',
  VARIANCE_PCT: 'Variance %',
};

const nf = new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

function formatCell(v: unknown, col: ReportColumn, row: ReportRow): string {
  if (v === null || v === undefined || v === '') return '';
  if (col.type === 'percent' || (row.values._format === 'percent' && col.type === 'money')) {
    return `${nf.format(Number(v))}%`;
  }
  if (col.type === 'money') {
    const n = Number(v);
    return n < 0 ? `(${nf.format(-n)})` : nf.format(n);
  }
  if (col.type === 'number') {
    return Number(v).toLocaleString(undefined, { maximumFractionDigits: 4 });
  }
  return String(v);
}

const LABEL = 'text-[10px] font-bold text-muted-foreground uppercase tracking-wider';

function MultiSelect({ label, options, value, onChange }: {
  label: string; options: { id: string; name: string; code?: string }[]; value: string[]; onChange: (v: string[]) => void;
}) {
  return (
    <div className="space-y-1 min-w-[170px]">
      <Label className={LABEL}>{label}</Label>
      <select
        multiple
        value={value}
        onChange={(e) => onChange(Array.from(e.target.selectedOptions).map((o) => o.value))}
        className="h-20 w-full rounded-md border px-1 text-xs"
        aria-label={label}
      >
        {options.map((o) => <option key={o.id} value={o.id}>{o.code ? `${o.code} ${o.name}` : o.name}</option>)}
      </select>
    </div>
  );
}

function ScheduleForm({ reportId, request, onDone }: { reportId: string; request: ReportRequest; onDone: () => void }) {
  const create = useCreateSchedule();
  const [name, setName] = useState('');
  const [format, setFormat] = useState('pdf');
  const [frequency, setFrequency] = useState('MONTHLY');
  const [day, setDay] = useState(1);
  const [hour, setHour] = useState(7);
  const [recipients, setRecipients] = useState('');
  const submit = () => {
    create.mutate({
      reportId, name: name || reportId, params: request, format, frequency, hourOfDay: hour,
      dayOfWeek: frequency === 'WEEKLY' ? day : undefined, dayOfMonth: frequency === 'MONTHLY' ? day : undefined,
      recipients: recipients.split(/[,;\s]+/).filter(Boolean),
    }, {
      onSuccess: () => { toast.success(tx('Schedule created')); onDone(); },
      onError: (e) => toast.error(apiErrorMessage(e, tx('Could not create the schedule'))),
    });
  };
  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border border-blue-200 bg-blue-50/40 p-3 print:hidden">
      <div className="space-y-1"><Label className={LABEL}>{tx('Name')}</Label>
        <Input value={name} onChange={(e) => setName(e.target.value)} className="h-9 w-44 text-xs" /></div>
      <div className="space-y-1"><Label className={LABEL}>{tx('Format')}</Label>
        <select value={format} onChange={(e) => setFormat(e.target.value)} className="h-9 rounded-md border px-2 text-xs">
          <option value="pdf">PDF</option><option value="xlsx">Excel</option><option value="csv">CSV</option>
        </select></div>
      <div className="space-y-1"><Label className={LABEL}>{tx('Frequency')}</Label>
        <select value={frequency} onChange={(e) => { setFrequency(e.target.value); setDay(1); }} className="h-9 rounded-md border px-2 text-xs">
          <option value="DAILY">{tx('Daily')}</option><option value="WEEKLY">{tx('Weekly')}</option><option value="MONTHLY">{tx('Monthly')}</option>
        </select></div>
      {frequency !== 'DAILY' && (
        <div className="space-y-1"><Label className={LABEL}>{frequency === 'WEEKLY' ? tx('Weekday (1=Mon)') : tx('Day of month')}</Label>
          <Input type="number" min={1} max={frequency === 'WEEKLY' ? 7 : 28} value={day} onChange={(e) => setDay(Number(e.target.value))} className="h-9 w-20 text-xs" /></div>
      )}
      <div className="space-y-1"><Label className={LABEL}>{tx('Hour')}</Label>
        <Input type="number" min={0} max={23} value={hour} onChange={(e) => setHour(Number(e.target.value))} className="h-9 w-20 text-xs" /></div>
      <div className="space-y-1 min-w-[240px] flex-1"><Label className={LABEL}>{tx('E-mail recipients')}</Label>
        <Input value={recipients} onChange={(e) => setRecipients(e.target.value)} placeholder="cfo@company.com, ..." className="h-9 text-xs" /></div>
      <Button size="sm" onClick={submit} disabled={create.isPending}>{tx('Create schedule')}</Button>
      <Button size="sm" variant="ghost" onClick={onDone}>{tx('Cancel')}</Button>
    </div>
  );
}

function ResultTable({ result, showAccounts, onDrill }: {
  result: ReportResult; showAccounts: boolean; onDrill: (d: Drill) => void;
}) {
  const rows = showAccounts ? result.rows : result.rows.filter((r) => r.kind !== 'account');
  return (
    <div className="overflow-x-auto rounded-lg border bg-white shadow-sm">
      <table className="w-full text-sm">
        <thead className="sticky top-0 bg-gray-50">
          <tr>
            {result.columns.map((c) => (
              <th key={c.key} className={`whitespace-nowrap border-b px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-gray-600 ${c.type === 'text' || c.type === 'date' ? 'text-left' : 'text-right'}`}>
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const rowDrill = row.drill?._row;
            const tone = row.kind === 'total' ? 'border-t-2 border-gray-300 bg-gray-50 font-bold'
              : row.kind === 'subtotal' ? 'border-t font-semibold'
                : row.kind === 'header' ? 'bg-white font-semibold text-gray-800'
                  : row.kind === 'kpi' ? 'bg-amber-50/50 italic text-amber-900'
                    : row.kind === 'account' ? 'text-gray-500' : '';
            return (
              <tr key={row.id} className={`border-b border-gray-100 hover:bg-blue-50/30 ${tone}`}>
                {result.columns.map((c, i) => {
                  const d = row.drill?.[c.key];
                  const text = formatCell(row.values[c.key], c, row);
                  const numeric = c.type !== 'text' && c.type !== 'date';
                  const neg = numeric && Number(row.values[c.key]) < 0;
                  const indent = i === 0 ? { paddingLeft: `${0.75 + row.depth * 1.1}rem` } : undefined;
                  const content = d && text ? (
                    <button type="button" onClick={() => onDrill(d)} className="underline decoration-dotted underline-offset-2 hover:text-blue-700" title={tx('Drill down')}>
                      {text}
                    </button>
                  ) : i === 0 && rowDrill?.route ? (
                    <Link to={rowDrill.route} className="hover:text-blue-700 hover:underline">{text}</Link>
                  ) : text;
                  return (
                    <td key={c.key} style={indent} className={`whitespace-nowrap px-3 py-1.5 ${numeric ? 'text-right tabular-nums' : ''} ${neg ? 'text-red-700' : ''}`}>
                      {content}
                    </td>
                  );
                })}
              </tr>
            );
          })}
          {rows.length === 0 && (
            <tr><td colSpan={result.columns.length} className="p-8 text-center text-gray-500">{tx('No data for these filters.')}</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

export function ReportViewerPage() {
  const { id = '' } = useParams();
  const [search, setSearch] = useSearchParams();
  const navigate = useNavigate();
  const { data: info, isLoading: defLoading, error: defError } = useReportDefinition(id);
  const def = info?.definition;
  const has = (k: string) => !!def?.filters.includes(k as never);

  const [draft, setDraft] = useState<ReportRequest>({});
  const [request, setRequest] = useState<ReportRequest | null>(null);
  const [showAccounts, setShowAccounts] = useState(true);
  const [scheduling, setScheduling] = useState(false);
  const [exporting, setExporting] = useState('');

  useEffect(() => {
    const fromUrl = decodeRequest(search.get('q'));
    const initial = fromUrl ?? { period: 'THIS_MONTH' };
    setDraft(initial);
    setRequest(initial);
  }, [id, search]);

  const { data: result, isFetching, error } = useRunReport(def?.engine ? id : undefined, request);
  const branches = useBranchOptions(has('BRANCH'));
  const costCenters = useCostCenterOptions(has('COST_CENTER'));
  const accounts = useAccountOptions(has('ACCOUNT'));
  const partners = usePartnerOptions(has('PARTNER'));
  const journals = useJournalOptions(has('JOURNAL'));
  const budgetList = useBudgetOptions(!!def?.options.some((o) => o.type === 'budget'));
  const views = useSavedViews(def?.engine ? id : undefined);
  const createView = useCreateView();
  const deleteView = useDeleteView(id);
  const verify = useVerifyRun();

  const set = (patch: Partial<ReportRequest>) => setDraft((d) => ({ ...d, ...patch }));
  const setOption = (key: string, value: string) =>
    setDraft((d) => ({ ...d, options: { ...(d.options ?? {}), [key]: value } }));
  const run = (r: ReportRequest = draft) => setSearch({ q: encodeRequest(r) });
  const onDrill = (d: Drill) => {
    if (d.route) navigate(d.route);
    else if (d.reportId) navigate(`/reports/library/${d.reportId}?q=${encodeRequest(d.params ?? {})}`);
  };
  const toggleCompare = (c: string) => {
    const cur = draft.compare ?? [];
    set({ compare: cur.includes(c) ? cur.filter((x) => x !== c) : [...cur, c] });
  };
  const exportAs = async (format: string) => {
    if (!request) return;
    setExporting(format);
    try {
      await downloadLibraryReport(id, format, request);
    } catch (e) {
      toast.error(apiErrorMessage(e, tx('Export failed')));
    } finally {
      setExporting('');
    }
  };
  const saveView = () => {
    const name = window.prompt(tx('Name of this view'));
    if (!name) return;
    createView.mutate({ reportId: id, name, params: draft }, {
      onSuccess: () => toast.success(tx('View saved')),
      onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save the view'))),
    });
  };
  const failed = useMemo(() => (result?.checks ?? []).filter((c) => !c.ok), [result]);
  const meta = result?.meta as { page?: number; pages?: number; total?: number } | undefined;

  if (defLoading) return <Skeleton className="h-64" />;
  if (defError || !def) {
    return <div className="rounded-lg border bg-white p-8 text-center text-sm text-gray-500">{apiErrorMessage(defError, tx('Report not found'))}</div>;
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-start justify-between gap-3 print:hidden">
        <div className="border-l-4 border-[#3b82f6] pl-4 space-y-1">
          <Link to="/reports/library" className="flex items-center gap-1 text-xs text-gray-500 hover:text-blue-700">
            <ArrowLeft className="h-3 w-3" /> {tx('Report Library')}
          </Link>
          <h1 className="text-2xl font-bold tracking-tight text-gray-900">{def.title}</h1>
          {def.description && <p className="max-w-3xl text-sm text-gray-500">{def.description}</p>}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {(views.data ?? []).length > 0 && (
            <select
              className="h-8 rounded-md border px-2 text-xs"
              value=""
              onChange={(e) => { const v = views.data?.find((x) => x.id === e.target.value); if (v) run(v.params); }}
              aria-label={tx('Saved views')}
            >
              <option value="">{tx('Saved views…')}</option>
              {views.data?.map((v) => <option key={v.id} value={v.id}>{v.name}{v.shared ? ` (${tx('shared')})` : ''}</option>)}
            </select>
          )}
          <Button size="sm" variant="outline" onClick={saveView}><Save className="mr-1 h-3.5 w-3.5" />{tx('Save view')}</Button>
          {info?.canSchedule && (
            <Button size="sm" variant="outline" onClick={() => setScheduling((s) => !s)}><CalendarClock className="mr-1 h-3.5 w-3.5" />{tx('Schedule')}</Button>
          )}
          {info?.exportFormats.map((f) => (
            <Button key={f} size="sm" variant="outline" disabled={!result || !!exporting} onClick={() => exportAs(f)}>
              <Download className="mr-1 h-3.5 w-3.5" />{exporting === f ? '…' : f.toUpperCase()}
            </Button>
          ))}
          <Button size="sm" variant="outline" onClick={() => window.print()} disabled={!result}><Printer className="mr-1 h-3.5 w-3.5" />{tx('Print')}</Button>
        </div>
      </div>

      {scheduling && request && <ScheduleForm reportId={id} request={draft} onDone={() => setScheduling(false)} />}

      <div className="flex flex-wrap items-end gap-3 rounded-lg border bg-white p-3 shadow-sm print:hidden">
        {(has('PERIOD') || has('AS_OF')) && (
          <div className="space-y-1">
            <Label className={LABEL}>{tx('Period')}</Label>
            <select
              value={draft.period ?? ''}
              onChange={(e) => set({ period: e.target.value || undefined, from: undefined, to: undefined, asOf: undefined })}
              className="h-9 rounded-md border px-2 text-xs"
            >
              <option value="">{tx('Custom')}</option>
              {(info?.periodPresets ?? []).map((p) => <option key={p} value={p}>{tx(PRESET_LABELS[p] ?? p)}</option>)}
            </select>
          </div>
        )}
        {has('PERIOD') && (
          <>
            <div className="space-y-1"><Label className={LABEL}>{tx('From')}</Label>
              <Input type="date" value={draft.from ?? ''} onChange={(e) => set({ from: e.target.value, period: undefined })} className="h-9 text-xs" /></div>
            <div className="space-y-1"><Label className={LABEL}>{tx('To')}</Label>
              <Input type="date" value={draft.to ?? ''} onChange={(e) => set({ to: e.target.value, period: undefined })} className="h-9 text-xs" /></div>
          </>
        )}
        {has('AS_OF') && (
          <div className="space-y-1"><Label className={LABEL}>{tx('As of')}</Label>
            <Input type="date" value={draft.asOf ?? ''} onChange={(e) => set({ asOf: e.target.value, period: undefined })} className="h-9 text-xs" /></div>
        )}
        {has('LAYOUT') && (info?.layouts.length ?? 0) > 0 && (
          <div className="space-y-1"><Label className={LABEL}>{tx('Layout')}</Label>
            <select value={draft.layoutCode ?? ''} onChange={(e) => set({ layoutCode: e.target.value || undefined })} className="h-9 rounded-md border px-2 text-xs">
              <option value="">{tx('Default')}</option>
              {info?.layouts.map((l) => <option key={l.code} value={l.code}>{l.name}</option>)}
            </select></div>
        )}
        {has('POSTING_STATUS') && (
          <div className="space-y-1"><Label className={LABEL}>{tx('Entries')}</Label>
            <select value={draft.postingStatus ?? 'POSTED'} onChange={(e) => set({ postingStatus: e.target.value === 'POSTED' ? undefined : 'ALL' })} className="h-9 rounded-md border px-2 text-xs">
              <option value="POSTED">{tx('Posted')}</option><option value="ALL">{tx('Posted + draft')}</option>
            </select></div>
        )}
        {has('BRANCH') && <MultiSelect label={tx('Branches')} options={branches.data ?? []} value={draft.branchIds ?? []} onChange={(v) => set({ branchIds: v })} />}
        {has('COST_CENTER') && <MultiSelect label={tx('Cost centers')} options={costCenters.data ?? []} value={draft.costCenterIds ?? []} onChange={(v) => set({ costCenterIds: v })} />}
        {has('ACCOUNT') && <MultiSelect label={tx('Accounts')} options={accounts.data ?? []} value={draft.accountIds ?? []} onChange={(v) => set({ accountIds: v })} />}
        {has('PARTNER') && <MultiSelect label={tx('Partners')} options={partners.data ?? []} value={draft.partnerIds ?? []} onChange={(v) => set({ partnerIds: v })} />}
        {has('JOURNAL') && <MultiSelect label={tx('Journals')} options={journals.data ?? []} value={draft.journalIds ?? []} onChange={(v) => set({ journalIds: v })} />}
        {def.options.map((o) => {
          const value = draft.options?.[o.key] ?? o.defaultValue ?? '';
          return (
            <div key={o.key} className="space-y-1">
              <Label className={LABEL}>{tx(o.label)}</Label>
              {o.type === 'select' ? (
                <select value={value} onChange={(e) => setOption(o.key, e.target.value)} className="h-9 rounded-md border px-2 text-xs">
                  {o.choices.map((c) => <option key={c} value={c}>{c === '' ? tx('Any') : tx(c.replace(/_/g, ' ').toLowerCase())}</option>)}
                </select>
              ) : o.type === 'budget' ? (
                <select value={value} onChange={(e) => setOption(o.key, e.target.value)} className="h-9 rounded-md border px-2 text-xs">
                  <option value="">{tx('Budget covering the period')}</option>
                  {(budgetList.data ?? []).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
                </select>
              ) : (
                <Input type={o.type === 'number' ? 'number' : o.type === 'date' ? 'date' : 'text'} value={value}
                  onChange={(e) => setOption(o.key, e.target.value)} className="h-9 w-36 text-xs" />
              )}
            </div>
          );
        })}
        {has('COMPARE') && (
          <div className="space-y-1">
            <Label className={LABEL}>{tx('Compare')}</Label>
            <div className="flex h-9 items-center gap-3">
              {(info?.compareOptions ?? []).map((c) => (
                <label key={c} className="flex items-center gap-1 text-xs">
                  <input type="checkbox" checked={(draft.compare ?? []).includes(c)} onChange={() => toggleCompare(c)} />
                  {tx(COMPARE_LABELS[c] ?? c)}
                </label>
              ))}
            </div>
          </div>
        )}
        <Button size="sm" className="h-9" onClick={() => run({ ...draft, page: undefined })}><Play className="mr-1 h-3.5 w-3.5" />{tx('Run')}</Button>
        {result?.rows.some((r) => r.kind === 'account') && (
          <label className="flex h-9 items-center gap-1 text-xs">
            <input type="checkbox" checked={showAccounts} onChange={(e) => setShowAccounts(e.target.checked)} /> {tx('Show accounts')}
          </label>
        )}
      </div>

      {error && <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">{apiErrorMessage(error, tx('The report could not run'))}</div>}

      {isFetching && !result ? <Skeleton className="h-96" /> : result && (
        <div className="space-y-3">
          <div className="rounded-lg border bg-white px-4 py-3 shadow-sm">
            <div className="text-xs font-semibold uppercase tracking-wider text-gray-500">{result.header.company}</div>
            <div className="text-lg font-bold text-gray-900">{result.header.title}</div>
            <div className="text-xs text-gray-600">
              {result.header.asOf ? `${tx('As of')} ${result.header.asOf}` : result.header.period}
              {' · '}{tx('Amounts in')} {result.header.currency}
              {' · '}{result.header.basis} {tx('basis')}
              {' · '}{result.header.branches.length ? result.header.branches.join(', ') : tx('All branches')}
              {result.header.postingStatus !== 'POSTED' && <> · {tx('includes drafts')}</>}
            </div>
            <div className="mt-1 flex flex-wrap items-center gap-3 text-[11px] text-gray-400">
              <span>{tx('Generated')} {new Date(result.header.generatedAt).toLocaleString()}{result.header.generatedBy ? ` · ${result.header.generatedBy}` : ''}</span>
              <span title={result.header.resultHash}>{tx('Run')} {result.header.runId.slice(0, 8)}</span>
              <button
                type="button"
                className="flex items-center gap-1 text-blue-600 hover:underline print:hidden"
                onClick={() => verify.mutate(result.header.runId, {
                  onSuccess: (v) => (v.reproducible ? toast.success(tx('Reproducible: same result as the original run'))
                    : toast.warning(tx('Data changed since this run'))),
                })}
              >
                <ShieldCheck className="h-3 w-3" /> {tx('Verify')}
              </button>
              {isFetching && <span>{tx('Refreshing…')}</span>}
            </div>
          </div>

          {result.checks.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {result.checks.map((c) => (
                <span key={c.code} title={c.detail ?? ''} className={`flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] ${c.ok ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-red-200 bg-red-50 text-red-700'}`}>
                  {c.ok ? <CheckCircle2 className="h-3 w-3" /> : <XCircle className="h-3 w-3" />}
                  {c.label}{!c.ok && c.detail ? `: ${c.detail}` : ''}
                </span>
              ))}
            </div>
          )}
          {failed.length > 0 && def.filters.includes('LAYOUT') && (
            <p className="text-xs text-red-700 print:hidden">{tx('Fix the statement layout under Report Library → layouts, or map the listed accounts to a line.')}</p>
          )}

          <ResultTable result={result} showAccounts={showAccounts} onDrill={onDrill} />

          {meta?.pages && meta.pages > 1 && (
            <div className="flex items-center justify-end gap-2 text-xs print:hidden">
              <span>{tx('Page')} {meta.page} / {meta.pages} · {meta.total} {tx('lines')}</span>
              <Button size="sm" variant="outline" disabled={(meta.page ?? 1) <= 1} onClick={() => run({ ...(request ?? {}), page: (meta.page ?? 1) - 1 })}><ChevronLeft className="h-3.5 w-3.5" /></Button>
              <Button size="sm" variant="outline" disabled={(meta.page ?? 1) >= meta.pages} onClick={() => run({ ...(request ?? {}), page: (meta.page ?? 1) + 1 })}><ChevronRight className="h-3.5 w-3.5" /></Button>
            </div>
          )}

          {(views.data ?? []).some((v) => !v.shared) && (
            <div className="flex flex-wrap gap-2 text-xs text-gray-500 print:hidden">
              {tx('My views')}:
              {views.data?.filter((v) => !v.shared).map((v) => (
                <span key={v.id} className="flex items-center gap-1 rounded border px-2 py-0.5">
                  <button type="button" className="hover:text-blue-700" onClick={() => run(v.params)}>{v.name}</button>
                  <button type="button" aria-label={tx('Delete view')} onClick={() => deleteView.mutate(v.id)}><Trash2 className="h-3 w-3" /></button>
                </span>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

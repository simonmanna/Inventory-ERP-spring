/**
 * IPR T33 — inventory integrity and reconciliation: every integrity check with its failing rows (and the report-only
 * checks), the GL tie-out, the organisation's feature-usage map with the P2s it promotes to go-live blockers (R6), and
 * links to the findings the daily monitors raise in the Posting Monitor. Read-only. Each section needs the permission
 * of its route (`inventory_valuation:read`, `report:accounting`, `pos:reports`); the route guard is the first.
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, CheckCircle2, ChevronDown, ChevronRight, CircleSlash, ExternalLink, RefreshCw } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';
import { useInventoryGlTieOut } from '@/features/accounting/api';
import { useStockLocations } from '@/features/inventory/locations';
import {
  cellText,
  checkState,
  failuresText,
  type FeatureUsage,
  findingsLink,
  GL_TIEOUT_KIND,
  goLiveBlockers,
  type IntegrityCheck,
  type IntegrityReport,
  orderedFeatures,
  postingMonitorLink,
  promotingFeatures,
  rowColumns,
  summarise,
  visibleSections,
} from '@/features/inventory/integrity';

function StateIcon({ check }: { check: IntegrityCheck }) {
  const s = checkState(check);
  if (s === 'ok') return <CheckCircle2 className="h-4 w-4 text-green-600" aria-label={tx('Passing')} />;
  if (s === 'skipped') return <CircleSlash className="h-4 w-4 text-muted-foreground" aria-label={tx('Skipped')} />;
  return <AlertTriangle className="h-4 w-4 text-destructive" aria-label={tx('Failing')} />;
}

function CheckRow({ check, showFindings }: { check: IntegrityCheck; showFindings: boolean }) {
  const [open, setOpen] = useState(false);
  const failing = checkState(check) === 'failing';
  const cols = rowColumns(check.rows);
  return (
    <div className="border-t first:border-t-0">
      <button
        type="button"
        className="flex w-full items-start gap-2 p-3 text-left hover:bg-muted/40"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
      >
        {check.rows.length > 0 ? (open ? <ChevronDown className="mt-0.5 h-4 w-4" /> : <ChevronRight className="mt-0.5 h-4 w-4" />)
          : <span className="w-4" />}
        <StateIcon check={check} />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <code className="font-mono text-sm">{check.key}</code>
            {failing && <span className="text-sm text-destructive">{tx(failuresText(check))}</span>}
            {check.tolerance !== null && check.tolerance !== undefined && (
              <span className="text-xs text-muted-foreground">{tx('tolerance')} {String(check.tolerance)}</span>
            )}
          </div>
          <p className="text-sm text-muted-foreground">{check.invariant}</p>
          {check.note && <p className="text-xs text-muted-foreground">{check.note}</p>}
        </div>
        {showFindings && failing && (
          <Link
            to={findingsLink(check)}
            className="inline-flex shrink-0 items-center gap-1 text-sm text-primary hover:underline"
            onClick={(e) => e.stopPropagation()}
          >
            {tx('Findings')} <ExternalLink className="h-3 w-3" />
          </Link>
        )}
      </button>
      {open && check.rows.length > 0 && (
        <div className="overflow-x-auto px-3 pb-3">
          <table className="w-full text-xs">
            <thead className="bg-muted/50 text-left">
              <tr>{cols.map((c) => <th key={c} className="p-1.5 font-medium">{c}</th>)}</tr>
            </thead>
            <tbody>
              {check.rows.map((r, i) => (
                <tr key={i} className="border-t align-top">
                  {cols.map((c) => <td key={c} className="p-1.5 font-mono">{cellText(r[c])}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function ChecksCard({ title, description, checks, showFindings }: {
  title: string; description: string; checks: IntegrityCheck[]; showFindings: boolean;
}) {
  const s = summarise(checks);
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-3">
          {title}
          <span className={`text-sm font-normal ${s.failing > 0 ? 'text-destructive' : 'text-green-700'}`}>
            {s.failing > 0 ? tx('{{n}} failing', { n: s.failing }) : tx('All passing')}
          </span>
          <span className="text-sm font-normal text-muted-foreground">
            {tx('{{ok}} of {{total}} passing, {{skipped}} skipped', { ok: s.ok, total: s.total, skipped: s.skipped })}
          </span>
        </CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent className="p-0">
        {s.ordered.length === 0
          ? <p className="p-4 text-sm text-muted-foreground">{tx('No checks.')}</p>
          : s.ordered.map((c) => <CheckRow key={c.key} check={c} showFindings={showFindings} />)}
      </CardContent>
    </Card>
  );
}

export function InventoryIntegrityPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const sections = visibleSections(has);
  const [locationId, setLocationId] = useState('');
  const { locations } = useStockLocations({ enabled: sections.integrity });

  const report = useQuery({
    queryKey: ['inventory-integrity', locationId || null],
    enabled: sections.integrity,
    queryFn: async () => (await api.get<IntegrityReport>('/inventory/reports/integrity', {
      params: { locationId: locationId || undefined } })).data,
  });
  const usage = useQuery({
    queryKey: ['inventory-feature-usage'],
    enabled: sections.featureUsage,
    queryFn: async () => (await api.get<FeatureUsage>('/inventory/reports/feature-usage')).data,
  });
  const tieOut = useInventoryGlTieOut(undefined, { enabled: sections.glTieOut });

  const blockers = goLiveBlockers(report.data, usage.data);
  const promoting = promotingFeatures(usage.data?.promotedP2s);
  const refresh = () => {
    void report.refetch();
    void usage.refetch();
    if (sections.glTieOut) void tieOut.refetch();
  };

  return (
    <div className="space-y-4 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Inventory integrity and reconciliation')}</h1>
          <p className="text-muted-foreground">
            {tx('Quantity, lot, serial and value checks, the GL tie-out, and the features this organisation uses. Run it before go-live and before every release.')}
          </p>
        </div>
        <div className="flex flex-wrap items-end gap-3">
          {sections.integrity && (
            <div className="grid gap-1">
              <Label htmlFor="integrity-location">{tx('Location')}</Label>
              <select
                id="integrity-location"
                className="h-10 rounded-md border bg-background px-3"
                value={locationId}
                onChange={(e) => setLocationId(e.target.value)}
              >
                <option value="">{tx('All locations')}</option>
                {(locations ?? []).map((l) => <option key={l.id} value={l.id}>{l.code} · {l.name}</option>)}
              </select>
            </div>
          )}
          <Button variant="outline" onClick={refresh} disabled={report.isFetching || usage.isFetching}>
            <RefreshCw className="mr-2 h-4 w-4" /> {tx('Run again')}
          </Button>
        </div>
      </div>

      {!sections.integrity && !sections.glTieOut && (
        <p className="text-sm text-muted-foreground">{tx('You do not have access to any of these reports.')}</p>
      )}

      {sections.integrity && (report.data || usage.data) && (
        <Card className={blockers.length ? 'border-destructive' : 'border-green-600'}>
          <CardContent className="p-4">
            {blockers.length === 0 ? (
              <p className="flex items-center gap-2 text-sm text-green-700">
                <CheckCircle2 className="h-4 w-4" /> {tx('No go-live blockers found by these checks.')}
              </p>
            ) : (
              <div className="space-y-1 text-sm">
                <p className="flex items-center gap-2 font-medium text-destructive">
                  <AlertTriangle className="h-4 w-4" /> {tx('{{n}} go-live blockers for this organisation', { n: blockers.length })}
                </p>
                <ul className="list-disc pl-6">{blockers.map((b) => <li key={b}>{tx(b)}</li>)}</ul>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {sections.integrity && (
        report.isLoading ? <Skeleton className="h-40 w-full" />
          : report.isError ? (
            <p role="alert" className="text-sm text-destructive">{apiErrorMessage(report.error, tx('Could not load the integrity report.'))}</p>
          ) : report.data ? (
            <>
              <p className="text-xs text-muted-foreground">
                {tx('Checked {{at}}; at most {{n}} rows per check.', {
                  at: new Date(report.data.checkedAt).toLocaleString(), n: report.data.rowLimit.toLocaleString() })}
              </p>
              <ChecksCard
                title={tx('Integrity checks')}
                description={tx('A failing check holds the go-live. Value checks also hold the period close.')}
                checks={report.data.checks}
                showFindings={sections.findings}
              />
              <ChecksCard
                title={tx('Report-only checks')}
                description={tx('Reported, never blocking a close: transaction-matrix gaps, feature risk and similar.')}
                checks={report.data.reportOnlyChecks ?? []}
                showFindings={sections.findings}
              />
            </>
          ) : null
      )}

      {sections.glTieOut && (
        <Card>
          <CardHeader>
            <CardTitle className="flex flex-wrap items-center gap-3">
              {tx('GL tie-out')}
              {tieOut.data && (
                <span className={`text-sm font-normal ${tieOut.data.withinTolerance ? 'text-green-700' : 'text-destructive'}`}>
                  {tieOut.data.withinTolerance ? tx('Within tolerance') : tx('Outside tolerance')}
                </span>
              )}
            </CardTitle>
            <CardDescription>
              {tx('Stock sub-ledger value against the inventory control accounts.')}{' '}
              <Link to="/inventory-gl-tieout" className="text-primary hover:underline">{tx('Open the full tie-out')}</Link>
            </CardDescription>
          </CardHeader>
          <CardContent>
            {tieOut.isLoading ? <Skeleton className="h-16 w-full" />
              : tieOut.isError ? <p role="alert" className="text-sm text-destructive">{apiErrorMessage(tieOut.error, tx('Could not load the GL tie-out.'))}</p>
                : tieOut.data ? (
                  <div className="space-y-2 text-sm">
                    <div className="grid gap-2 sm:grid-cols-4">
                      <div><div className="text-muted-foreground">{tx('Sub-ledger')}</div><div className="font-mono">{String(tieOut.data.subledgerValue)}</div></div>
                      <div><div className="text-muted-foreground">{tx('GL balance')}</div><div className="font-mono">{String(tieOut.data.glBalance)}</div></div>
                      <div><div className="text-muted-foreground">{tx('Variance')}</div><div className="font-mono">{String(tieOut.data.variance)}</div></div>
                      <div><div className="text-muted-foreground">{tx('Tolerance')}</div><div className="font-mono">{String(tieOut.data.tolerance)}</div></div>
                    </div>
                    {tieOut.data.warning && <p className="text-xs text-muted-foreground">{tieOut.data.warning}</p>}
                    {sections.findings && !tieOut.data.withinTolerance && (
                      <Link to={postingMonitorLink(GL_TIEOUT_KIND)} className="inline-flex items-center gap-1 text-primary hover:underline">
                        {tx('Tie-out findings')} <ExternalLink className="h-3 w-3" />
                      </Link>
                    )}
                  </div>
                ) : null}
          </CardContent>
        </Card>
      )}

      {sections.featureUsage && (
        <Card>
          <CardHeader>
            <CardTitle>{tx('Feature usage and promoted P2s')}</CardTitle>
            <CardDescription>
              {tx('A deferred P2 becomes a go-live blocker for this organisation when it uses the feature behind it. Re-check before every release.')}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {usage.isLoading ? <Skeleton className="h-24 w-full" />
              : usage.isError ? <p role="alert" className="text-sm text-destructive">{apiErrorMessage(usage.error, tx('Could not load the feature usage.'))}</p>
                : usage.data ? (
                  <>
                    <div>
                      <h3 className="mb-1 text-sm font-medium">{tx('Open P2s on features in use')}</h3>
                      {usage.data.promotedP2s.length === 0 ? (
                        <p className="text-sm text-green-700">{tx('None.')}</p>
                      ) : (
                        <table className="w-full text-sm">
                          <thead className="bg-muted/50 text-left"><tr>
                            <th className="p-2">{tx('P2')}</th><th className="p-2">{tx('Finding')}</th><th className="p-2">{tx('Because of')}</th>
                          </tr></thead>
                          <tbody>
                            {usage.data.promotedP2s.map((p) => (
                              <tr key={p.id} className="border-t">
                                <td className="p-2 font-mono">{p.id}</td>
                                <td className="p-2">{p.title}</td>
                                <td className="p-2 font-mono text-xs">{p.matchedFeatures.join(' + ')}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      )}
                    </div>
                    {(usage.data.fixedP2s?.length ?? 0) > 0 && (
                      <div>
                        <h3 className="mb-1 text-sm font-medium">{tx('P2s fixed in the readiness programme')}</h3>
                        <p className="text-sm text-muted-foreground">
                          {usage.data.fixedP2s!.map((p) => p.id).join(', ')}
                        </p>
                      </div>
                    )}
                    <div className="overflow-x-auto">
                      <table className="w-full text-sm">
                        <thead className="bg-muted/50 text-left"><tr>
                          <th className="p-2">{tx('Feature')}</th><th className="p-2">{tx('In use')}</th><th className="p-2 text-right">{tx('Rows')}</th>
                        </tr></thead>
                        <tbody>
                          {orderedFeatures(usage.data.features).map((f) => (
                            <tr key={f.key} className={`border-t ${promoting.has(f.key) ? 'bg-destructive/5' : ''}`}>
                              <td className="p-2">
                                {f.label} <code className="ml-1 text-xs text-muted-foreground">{f.key}</code>
                                {f.evidence && <div className="text-xs text-muted-foreground">{f.evidence}</div>}
                              </td>
                              <td className="p-2">{f.inUse ? tx('Yes') : tx('No')}</td>
                              <td className="p-2 text-right font-mono">{f.count.toLocaleString()}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </>
                ) : null}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

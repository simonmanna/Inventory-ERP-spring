import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const QuarryDashboardPage = lazy(() => import('./QuarryDashboardPage').then((m) => ({ default: m.QuarryDashboardPage })));
const SitesPage = lazy(() => import('./SitesPage').then((m) => ({ default: m.SitesPage })));
const SiteDetailPage = lazy(() => import('./SiteDetailPage').then((m) => ({ default: m.SiteDetailPage })));
const MaterialsPage = lazy(() => import('./MaterialsPage').then((m) => ({ default: m.MaterialsPage })));
const PlansPage = lazy(() => import('./PlansPage').then((m) => ({ default: m.PlansPage })));
const ExtractionLogsPage = lazy(() => import('./ExtractionLogsPage').then((m) => ({ default: m.ExtractionLogsPage })));
const ExtractionEntryPage = lazy(() => import('./ExtractionEntryPage').then((m) => ({ default: m.ExtractionEntryPage })));
const ExtractionLogDetailPage = lazy(() => import('./ExtractionLogDetailPage').then((m) => ({ default: m.ExtractionLogDetailPage })));
const HaulagePage = lazy(() => import('./HaulagePage').then((m) => ({ default: m.HaulagePage })));
const LossesPage = lazy(() => import('./LossesPage').then((m) => ({ default: m.LossesPage })));
const StockpilesPage = lazy(() => import('./StockpilesPage').then((m) => ({ default: m.StockpilesPage })));
const StockpileDetailPage = lazy(() => import('./StockpileDetailPage').then((m) => ({ default: m.StockpileDetailPage })));
const BlastsPage = lazy(() => import('./BlastsPage').then((m) => ({ default: m.BlastsPage })));
const BlastDetailPage = lazy(() => import('./BlastDetailPage').then((m) => ({ default: m.BlastDetailPage })));
const ExplosiveIssuesPage = lazy(() => import('./ExplosiveIssuesPage').then((m) => ({ default: m.ExplosiveIssuesPage })));
const ExplosiveIssueDetailPage = lazy(() => import('./ExplosiveIssueDetailPage').then((m) => ({ default: m.ExplosiveIssueDetailPage })));
const MagazineDetailPage = lazy(() => import('./MagazinesPage').then((m) => ({ default: m.MagazineDetailPage })));
const MagazinesPage = lazy(() => import('./MagazinesPage').then((m) => ({ default: m.MagazinesPage })));
const QuarryReportsPage = lazy(() => import('./QuarryReportsPage').then((m) => ({ default: m.QuarryReportsPage })));
const QuarrySettingsPage = lazy(() => import('./QuarrySettingsPage').then((m) => ({ default: m.QuarrySettingsPage })));

const P = PERMISSIONS;
// Every quarry screen reads the form lookups, so each also needs quarry_site:read.
const guard = (permission: string, el: JSX.Element) => <RequirePermission permission={[P.quarrySite.read, permission]}>{el}</RequirePermission>;

/** Quarry & extraction (ADR-S012): static segments before the `:id` routes. */
export const quarryRoutes = (
  <>
    <Route path="/quarry" element={guard(P.quarryExtraction.read, <QuarryDashboardPage />)} />
    <Route path="/quarry/sites" element={guard(P.quarrySite.read, <SitesPage />)} />
    <Route path="/quarry/sites/:id" element={guard(P.quarrySite.read, <SiteDetailPage />)} />
    <Route path="/quarry/materials" element={guard(P.quarrySite.read, <MaterialsPage />)} />
    <Route path="/quarry/plans" element={guard(P.quarryExtraction.read, <PlansPage />)} />
    <Route path="/quarry/extraction" element={guard(P.quarryExtraction.read, <ExtractionLogsPage />)} />
    <Route path="/quarry/extraction/new" element={guard(P.quarryExtraction.create, <ExtractionEntryPage />)} />
    <Route path="/quarry/extraction/:id" element={guard(P.quarryExtraction.read, <ExtractionLogDetailPage />)} />
    <Route path="/quarry/extraction/:id/edit" element={guard(P.quarryExtraction.update, <ExtractionEntryPage />)} />
    <Route path="/quarry/haulage" element={guard(P.quarryExtraction.read, <HaulagePage />)} />
    <Route path="/quarry/losses" element={guard(P.quarryExtraction.read, <LossesPage />)} />
    <Route path="/quarry/stockpiles" element={guard(P.quarrySite.read, <StockpilesPage />)} />
    <Route path="/quarry/stockpiles/:id" element={guard(P.quarryExtraction.read, <StockpileDetailPage />)} />
    <Route path="/quarry/blasts" element={guard(P.quarryBlasting.read, <BlastsPage />)} />
    <Route path="/quarry/blasts/:id" element={guard(P.quarryBlasting.read, <BlastDetailPage />)} />
    <Route path="/quarry/explosive-issues" element={guard(P.quarryBlasting.read, <ExplosiveIssuesPage />)} />
    <Route path="/quarry/explosive-issues/:id" element={guard(P.quarryBlasting.read, <ExplosiveIssueDetailPage />)} />
    <Route path="/quarry/magazines" element={guard(P.quarryBlasting.read, <MagazinesPage />)} />
    <Route path="/quarry/magazines/:id" element={guard(P.quarryBlasting.read, <MagazineDetailPage />)} />
    <Route path="/quarry/reports" element={guard(P.quarry.report, <QuarryReportsPage />)} />
    <Route path="/quarry/settings" element={guard(P.quarry.settings, <QuarrySettingsPage />)} />
  </>
);

import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { QuarryDashboardPage } from './QuarryDashboardPage';
import { SitesPage } from './SitesPage';
import { SiteDetailPage } from './SiteDetailPage';
import { MaterialsPage } from './MaterialsPage';
import { PlansPage } from './PlansPage';
import { ExtractionLogsPage } from './ExtractionLogsPage';
import { ExtractionEntryPage } from './ExtractionEntryPage';
import { ExtractionLogDetailPage } from './ExtractionLogDetailPage';
import { HaulagePage } from './HaulagePage';
import { LossesPage } from './LossesPage';
import { StockpilesPage } from './StockpilesPage';
import { StockpileDetailPage } from './StockpileDetailPage';
import { BlastsPage } from './BlastsPage';
import { BlastDetailPage } from './BlastDetailPage';
import { ExplosiveIssuesPage } from './ExplosiveIssuesPage';
import { ExplosiveIssueDetailPage } from './ExplosiveIssueDetailPage';
import { MagazineDetailPage, MagazinesPage } from './MagazinesPage';
import { QuarryReportsPage } from './QuarryReportsPage';
import { QuarrySettingsPage } from './QuarrySettingsPage';

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

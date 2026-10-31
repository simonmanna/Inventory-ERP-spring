import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const QualityOverviewPage = lazy(() => import('./QualityOverviewPage').then((m) => ({ default: m.QualityOverviewPage })));
const SpecificationsPage = lazy(() => import('./SpecificationsPage').then((m) => ({ default: m.SpecificationsPage })));
const SpecificationDetailPage = lazy(() => import('./SpecificationDetailPage').then((m) => ({ default: m.SpecificationDetailPage })));
const InspectionPlansPage = lazy(() => import('./InspectionPlansPage').then((m) => ({ default: m.InspectionPlansPage })));
const InspectionLotsPage = lazy(() => import('./InspectionLotsPage').then((m) => ({ default: m.InspectionLotsPage })));
const InspectionLotDetailPage = lazy(() => import('./InspectionLotDetailPage').then((m) => ({ default: m.InspectionLotDetailPage })));
const NcrsPage = lazy(() => import('./NcrsPage').then((m) => ({ default: m.NcrsPage })));
const NcrDetailPage = lazy(() => import('./NcrDetailPage').then((m) => ({ default: m.NcrDetailPage })));
const CapasPage = lazy(() => import('./CapasPage').then((m) => ({ default: m.CapasPage })));
const CapaDetailPage = lazy(() => import('./CapaDetailPage').then((m) => ({ default: m.CapaDetailPage })));
const LabImportsPage = lazy(() => import('./LabImportsPage').then((m) => ({ default: m.LabImportsPage })));
const TraceabilityPage = lazy(() => import('./TraceabilityPage').then((m) => ({ default: m.TraceabilityPage })));
const QualityReportsPage = lazy(() => import('./QualityReportsPage').then((m) => ({ default: m.QualityReportsPage })));

/** Quality management screens (ADR-S013); core-available, gated by quality:* permissions. Static segments before :id. */
const read = PERMISSIONS.quality.read;
export const qualityRoutes = (
  <>
    <Route path="/quality" element={<RequirePermission permission={read}><QualityOverviewPage /></RequirePermission>} />
    <Route path="/quality/specifications" element={<RequirePermission permission={read}><SpecificationsPage /></RequirePermission>} />
    <Route path="/quality/specifications/:id" element={<RequirePermission permission={read}><SpecificationDetailPage /></RequirePermission>} />
    <Route path="/quality/plans" element={<RequirePermission permission={read}><InspectionPlansPage /></RequirePermission>} />
    <Route path="/quality/lots" element={<RequirePermission permission={read}><InspectionLotsPage /></RequirePermission>} />
    <Route path="/quality/lots/:id" element={<RequirePermission permission={read}><InspectionLotDetailPage /></RequirePermission>} />
    <Route path="/quality/ncrs" element={<RequirePermission permission={read}><NcrsPage /></RequirePermission>} />
    <Route path="/quality/ncrs/:id" element={<RequirePermission permission={read}><NcrDetailPage /></RequirePermission>} />
    <Route path="/quality/capas" element={<RequirePermission permission={read}><CapasPage /></RequirePermission>} />
    <Route path="/quality/capas/:id" element={<RequirePermission permission={read}><CapaDetailPage /></RequirePermission>} />
    <Route path="/quality/lab" element={<RequirePermission permission={read}><LabImportsPage /></RequirePermission>} />
    <Route path="/quality/traceability" element={<RequirePermission permission={read}><TraceabilityPage /></RequirePermission>} />
    <Route path="/quality/reports" element={<RequirePermission permission={read}><QualityReportsPage /></RequirePermission>} />
  </>
);

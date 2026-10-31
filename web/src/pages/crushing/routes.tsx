import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { CrushingRunsPage } from './CrushingRunsPage';
import { CrushingRunEntryPage } from './CrushingRunEntryPage';
import { CrushingRunDetailPage } from './CrushingRunDetailPage';
import { CrushingPlantsPage } from './CrushingPlantsPage';
import { CrushingPlantEditorPage } from './CrushingPlantEditorPage';
import { GradingProfilePage } from './GradingProfilePage';
import { CrushingStockpilesPage } from './CrushingStockpilesPage';
import { CrushingReportsPage } from './CrushingReportsPage';

const P = PERMISSIONS;
// Every crushing screen reads the form lookups, so each also needs crushing:read.
const guard = (permission: string, el: JSX.Element) => <RequirePermission permission={[P.crushing.read, permission]}>{el}</RequirePermission>;

/** Crushing, screening & aggregate processing (manufacturing 2.3, ADR-S016): static segments before the `:id` routes. */
export const crushingRoutes = (
  <>
    <Route path="/manufacturing/crushing" element={guard(P.crushing.read, <CrushingRunsPage />)} />
    <Route path="/manufacturing/crushing/runs/new" element={guard(P.crushingRun.record, <CrushingRunEntryPage />)} />
    <Route path="/manufacturing/crushing/runs/:id" element={guard(P.crushing.read, <CrushingRunDetailPage />)} />
    <Route path="/manufacturing/crushing/runs/:id/edit" element={guard(P.crushingRun.record, <CrushingRunEntryPage />)} />
    <Route path="/manufacturing/crushing/plants" element={guard(P.crushing.read, <CrushingPlantsPage />)} />
    <Route path="/manufacturing/crushing/plants/new" element={guard(P.crushing.setup, <CrushingPlantEditorPage />)} />
    <Route path="/manufacturing/crushing/plants/:id" element={guard(P.crushing.read, <CrushingPlantEditorPage />)} />
    <Route path="/manufacturing/crushing/profiles/new" element={guard(P.crushing.setup, <GradingProfilePage />)} />
    <Route path="/manufacturing/crushing/profiles/:id" element={guard(P.crushing.read, <GradingProfilePage />)} />
    <Route path="/manufacturing/crushing/stockpiles" element={guard(P.crushing.read, <CrushingStockpilesPage />)} />
    <Route path="/manufacturing/crushing/reports" element={guard(P.production.report, <CrushingReportsPage />)} />
  </>
);

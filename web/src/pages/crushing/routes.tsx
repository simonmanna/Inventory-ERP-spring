import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const CrushingRunsPage = lazy(() => import('./CrushingRunsPage').then((m) => ({ default: m.CrushingRunsPage })));
const CrushingRunEntryPage = lazy(() => import('./CrushingRunEntryPage').then((m) => ({ default: m.CrushingRunEntryPage })));
const CrushingRunDetailPage = lazy(() => import('./CrushingRunDetailPage').then((m) => ({ default: m.CrushingRunDetailPage })));
const CrushingPlantsPage = lazy(() => import('./CrushingPlantsPage').then((m) => ({ default: m.CrushingPlantsPage })));
const CrushingPlantEditorPage = lazy(() => import('./CrushingPlantEditorPage').then((m) => ({ default: m.CrushingPlantEditorPage })));
const GradingProfilePage = lazy(() => import('./GradingProfilePage').then((m) => ({ default: m.GradingProfilePage })));
const CrushingStockpilesPage = lazy(() => import('./CrushingStockpilesPage').then((m) => ({ default: m.CrushingStockpilesPage })));
const CrushingReportsPage = lazy(() => import('./CrushingReportsPage').then((m) => ({ default: m.CrushingReportsPage })));

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

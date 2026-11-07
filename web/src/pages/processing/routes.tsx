import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const ProcessingDashboardPage = lazy(() => import('./ProcessingDashboardPage').then((m) => ({ default: m.ProcessingDashboardPage })));
const ProcessingJobsPage = lazy(() => import('./ProcessingJobsPage').then((m) => ({ default: m.ProcessingJobsPage })));
const ProcessingJobEntryPage = lazy(() => import('./ProcessingJobEntryPage').then((m) => ({ default: m.ProcessingJobEntryPage })));
const ProcessingJobDetailPage = lazy(() => import('./ProcessingJobDetailPage').then((m) => ({ default: m.ProcessingJobDetailPage })));
const ProcessingStockPage = lazy(() => import('./ProcessingStockPage').then((m) => ({ default: m.ProcessingStockPage })));
const ProcessingTemplatesPage = lazy(() => import('./ProcessingTemplatesPage').then((m) => ({ default: m.ProcessingTemplatesPage })));
const ProcessingTemplateEditorPage = lazy(() => import('./ProcessingTemplatesPage').then((m) => ({ default: m.ProcessingTemplateEditorPage })));
const ProcessingRoutesPage = lazy(() => import('./ProcessingRoutesPage').then((m) => ({ default: m.ProcessingRoutesPage })));

const P = PERMISSIONS.processing;
const guard = (permission: string, el: JSX.Element) => <RequirePermission permission={[P.read, permission]}>{el}</RequirePermission>;

/** Multi-stage processing (ADR-S060): static segments before the `:id` routes. */
export const processingRoutes = (
  <>
    <Route path="/manufacturing/processing" element={guard(P.read, <ProcessingDashboardPage />)} />
    <Route path="/manufacturing/processing/jobs" element={guard(P.read, <ProcessingJobsPage />)} />
    <Route path="/manufacturing/processing/jobs/new" element={guard(P.post, <ProcessingJobEntryPage />)} />
    <Route path="/manufacturing/processing/jobs/:id" element={guard(P.read, <ProcessingJobDetailPage />)} />
    <Route path="/manufacturing/processing/stock" element={guard(P.read, <ProcessingStockPage />)} />
    <Route path="/manufacturing/processing/templates" element={guard(P.read, <ProcessingTemplatesPage />)} />
    <Route path="/manufacturing/processing/templates/new" element={guard(P.create, <ProcessingTemplateEditorPage />)} />
    <Route path="/manufacturing/processing/templates/:id" element={guard(P.read, <ProcessingTemplateEditorPage />)} />
    <Route path="/manufacturing/processing/routes" element={guard(P.read, <ProcessingRoutesPage />)} />
  </>
);

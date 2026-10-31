import { lazy } from 'react';
import { Navigate, Route, useParams } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const CrmDashboardPage = lazy(() => import('./CrmDashboardPage').then((m) => ({ default: m.CrmDashboardPage })));
const PipelinePage = lazy(() => import('./PipelinePage').then((m) => ({ default: m.PipelinePage })));
const OpportunityDetailPage = lazy(() => import('./OpportunityDetailPage').then((m) => ({ default: m.OpportunityDetailPage })));
const LeadsPage = lazy(() => import('./LeadsPage').then((m) => ({ default: m.LeadsPage })));
const ActivitiesPage = lazy(() => import('./ActivitiesPage').then((m) => ({ default: m.ActivitiesPage })));
const QuotationsPage = lazy(() => import('./QuotationsPage').then((m) => ({ default: m.QuotationsPage })));
const QuotationEditorPage = lazy(() => import('./QuotationEditorPage').then((m) => ({ default: m.QuotationEditorPage })));
const CustomerDetailPage = lazy(() => import('./CustomersPage').then((m) => ({ default: m.CustomerDetailPage })));
const CustomersPage = lazy(() => import('./CustomersPage').then((m) => ({ default: m.CustomersPage })));
const PriceRulesPage = lazy(() => import('./PriceRulesPage').then((m) => ({ default: m.PriceRulesPage })));
const CrmReportsPage = lazy(() => import('./CrmReportsPage').then((m) => ({ default: m.CrmReportsPage })));
const CrmSettingsPage = lazy(() => import('./CrmSettingsPage').then((m) => ({ default: m.CrmSettingsPage })));
const CasesPage = lazy(() => import('./CasesPage').then((m) => ({ default: m.CasesPage })));
const CaseDetailPage = lazy(() => import('./CaseDetailPage').then((m) => ({ default: m.CaseDetailPage })));

/** Old deal links keep working: /crm/deals/:id → /crm/opportunities/:id. */
function LegacyDealRedirect() {
  const { id } = useParams();
  return <Navigate to={`/crm/opportunities/${id}`} replace />;
}

const P = PERMISSIONS.crm;

/** CRM sales suite routes (ADR-S010); static segments before the `:id` routes. */
export const crmRoutes = (
  <>
    <Route path="/crm" element={<RequirePermission permission={P.dashboardRead}><CrmDashboardPage /></RequirePermission>} />
    <Route path="/crm/pipeline" element={<RequirePermission permission={P.dealRead}><PipelinePage /></RequirePermission>} />
    <Route path="/crm/leads" element={<RequirePermission permission={P.leadRead}><LeadsPage /></RequirePermission>} />
    <Route path="/crm/opportunities/:id" element={<RequirePermission permission={P.dealRead}><OpportunityDetailPage /></RequirePermission>} />
    <Route path="/crm/activities" element={<RequirePermission permission={P.activityRead}><ActivitiesPage /></RequirePermission>} />
    <Route path="/crm/quotations" element={<RequirePermission permission={P.quotationRead}><QuotationsPage /></RequirePermission>} />
    <Route path="/crm/quotations/new" element={<RequirePermission permission={P.quotationWrite}><QuotationEditorPage /></RequirePermission>} />
    <Route path="/crm/quotations/:id" element={<RequirePermission permission={P.quotationRead}><QuotationEditorPage /></RequirePermission>} />
    <Route path="/crm/customers" element={<RequirePermission permission={P.dealRead}><CustomersPage /></RequirePermission>} />
    <Route path="/crm/customers/:id" element={<RequirePermission permission={P.dealRead}><CustomerDetailPage /></RequirePermission>} />
    <Route path="/crm/cases" element={<RequirePermission permission={P.caseRead}><CasesPage /></RequirePermission>} />
    <Route path="/crm/cases/:id" element={<RequirePermission permission={P.caseRead}><CaseDetailPage /></RequirePermission>} />
    <Route path="/crm/pricing" element={<RequirePermission permission={P.pricingRead}><PriceRulesPage /></RequirePermission>} />
    <Route path="/crm/reports" element={<RequirePermission permission={P.reportRead}><CrmReportsPage /></RequirePermission>} />
    <Route path="/crm/settings" element={<RequirePermission permission={P.configure}><CrmSettingsPage /></RequirePermission>} />
    <Route path="/crm/deals" element={<Navigate to="/crm/pipeline" replace />} />
    <Route path="/crm/deals/:id" element={<LegacyDealRedirect />} />
  </>
);

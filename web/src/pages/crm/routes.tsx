import { Navigate, Route, useParams } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { CrmDashboardPage } from './CrmDashboardPage';
import { PipelinePage } from './PipelinePage';
import { OpportunityDetailPage } from './OpportunityDetailPage';
import { LeadsPage } from './LeadsPage';
import { ActivitiesPage } from './ActivitiesPage';
import { QuotationsPage } from './QuotationsPage';
import { QuotationEditorPage } from './QuotationEditorPage';
import { CustomerDetailPage, CustomersPage } from './CustomersPage';
import { PriceRulesPage } from './PriceRulesPage';
import { CrmReportsPage } from './CrmReportsPage';
import { CrmSettingsPage } from './CrmSettingsPage';
import { CasesPage } from './CasesPage';
import { CaseDetailPage } from './CaseDetailPage';

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

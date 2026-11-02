import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const HelpdeskDashboardPage = lazy(() => import('./HelpdeskDashboardPage').then((m) => ({ default: m.HelpdeskDashboardPage })));
const TicketsPage = lazy(() => import('./TicketsPage').then((m) => ({ default: m.TicketsPage })));
const TicketDetailPage = lazy(() => import('./TicketDetailPage').then((m) => ({ default: m.TicketDetailPage })));
const LettersPage = lazy(() => import('./LettersPage').then((m) => ({ default: m.LettersPage })));
const HelpdeskSettingsPage = lazy(() => import('./HelpdeskSettingsPage').then((m) => ({ default: m.HelpdeskSettingsPage })));

const P = PERMISSIONS.helpdesk;

/** Helpdesk & correspondence routes (ADR-S045); static segments before `:id`. */
export const helpdeskRoutes = (
  <>
    <Route path="/helpdesk" element={<RequirePermission permission={P.reportRead}><HelpdeskDashboardPage /></RequirePermission>} />
    <Route path="/helpdesk/tickets" element={<RequirePermission permission={P.ticketRead}><TicketsPage /></RequirePermission>} />
    <Route path="/helpdesk/tickets/:id" element={<RequirePermission permission={P.ticketRead}><TicketDetailPage /></RequirePermission>} />
    <Route path="/helpdesk/letters" element={<RequirePermission permission={P.ticketRead}><LettersPage /></RequirePermission>} />
    <Route path="/helpdesk/settings" element={<RequirePermission permission={P.teamManage}><HelpdeskSettingsPage /></RequirePermission>} />
  </>
);

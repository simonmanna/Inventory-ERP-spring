import { lazy } from 'react';
import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';

const LandedCostControlPage = lazy(() => import('@/pages/costing/LandedCostControlPage').then((m) => ({ default: m.LandedCostControlPage })));
const LandedCostReportsPage = lazy(() => import('@/pages/costing/LandedCostReportsPage').then((m) => ({ default: m.LandedCostReportsPage })));
const LandedCostVoucherDetailPage = lazy(() => import('@/pages/costing/LandedCostVouchersPage').then((m) => ({ default: m.LandedCostVoucherDetailPage })));
const LandedCostVoucherEditPage = lazy(() => import('@/pages/costing/LandedCostVouchersPage').then((m) => ({ default: m.LandedCostVoucherEditPage })));
const LandedCostVouchersPage = lazy(() => import('@/pages/costing/LandedCostVouchersPage').then((m) => ({ default: m.LandedCostVouchersPage })));
const ShipmentsPage = lazy(() => import('@/pages/costing/ShipmentsPage').then((m) => ({ default: m.ShipmentsPage })));
const PurchaseRequestsPage = lazy(() => import('./purchase-requests').then((m) => ({ default: m.PurchaseRequestsPage })));
const PurchaseOrdersPage = lazy(() => import('./purchase-orders').then((m) => ({ default: m.PurchaseOrdersPage })));
const PurchaseOrderCreatePage = lazy(() => import('./purchase-order-create').then((m) => ({ default: m.PurchaseOrderCreatePage })));
const PurchaseOrderDetailPage = lazy(() => import('./PurchaseOrderDetailPage').then((m) => ({ default: m.PurchaseOrderDetailPage })));
const PurchaseOrderReceivePage = lazy(() => import('./PurchaseOrderReceivePage').then((m) => ({ default: m.PurchaseOrderReceivePage })));
const PurchaseOrderPayPage = lazy(() => import('./PurchaseOrderPayPage').then((m) => ({ default: m.PurchaseOrderPayPage })));
const GoodsReceiptsPage = lazy(() => import('./goods-receipts').then((m) => ({ default: m.GoodsReceiptsPage })));
const GoodsReceiptCreatePage = lazy(() => import('./goods-receipt-create').then((m) => ({ default: m.GoodsReceiptCreatePage })));
const GoodsReceiptDetailPage = lazy(() => import('./goods-receipt-detail'));
const ThreeWayMatchPage = lazy(() => import('./three-way-match').then((m) => ({ default: m.ThreeWayMatchPage })));
const DebitNotesPage = lazy(() => import('./debit-notes').then((m) => ({ default: m.DebitNotesPage })));
const DebitNoteCreatePage = lazy(() => import('./debit-note-create').then((m) => ({ default: m.DebitNoteCreatePage })));
const SuppliersPage = lazy(() => import('./s2p/SuppliersPage').then((m) => ({ default: m.SuppliersPage })));
const SupplierDetailPage = lazy(() => import('./s2p/SupplierDetailPage').then((m) => ({ default: m.SupplierDetailPage })));
const PurchasingPoliciesPage = lazy(() => import('./s2p/PurchasingPoliciesPage').then((m) => ({ default: m.PurchasingPoliciesPage })));
const PurchaseRequestCreatePage = lazy(() => import('./s2p/PurchaseRequestCreatePage').then((m) => ({ default: m.PurchaseRequestCreatePage })));
const SourcingWorkbenchPage = lazy(() => import('./s2p/SourcingWorkbenchPage').then((m) => ({ default: m.SourcingWorkbenchPage })));
const OpenOrdersPage = lazy(() => import('./s2p/OpenOrdersPage').then((m) => ({ default: m.OpenOrdersPage })));
const PurchaseOrderPrintPage = lazy(() => import('./s2p/PurchaseOrderPrintPage').then((m) => ({ default: m.PurchaseOrderPrintPage })));
const RfqDetailPage = lazy(() => import('./s2p/RfqPages').then((m) => ({ default: m.RfqDetailPage })));
const RfqListPage = lazy(() => import('./s2p/RfqPages').then((m) => ({ default: m.RfqListPage })));
const AgreementDetailPage = lazy(() => import('./s2p/AgreementPages').then((m) => ({ default: m.AgreementDetailPage })));
const AgreementsPage = lazy(() => import('./s2p/AgreementPages').then((m) => ({ default: m.AgreementsPage })));
const CommitmentsPage = lazy(() => import('./s2p/ControlsPages').then((m) => ({ default: m.CommitmentsPage })));
const PoChangeApprovalsPage = lazy(() => import('./s2p/ControlsPages').then((m) => ({ default: m.PoChangeApprovalsPage })));
const SupplierClaimsPage = lazy(() => import('./s2p/ControlsPages').then((m) => ({ default: m.SupplierClaimsPage })));
const EmergencyPurchasesPage = lazy(() => import('./s2p/QualityPages').then((m) => ({ default: m.EmergencyPurchasesPage })));
const InspectionsPage = lazy(() => import('./s2p/QualityPages').then((m) => ({ default: m.InspectionsPage })));
const ProcurementDashboardPage = lazy(() => import('./s2p/PlanningPages').then((m) => ({ default: m.ProcurementDashboardPage })));
const ReplenishmentPage = lazy(() => import('./s2p/PlanningPages').then((m) => ({ default: m.ReplenishmentPage })));
const SupplierPortalPage = lazy(() => import('./s2p/SupplierPortalPage').then((m) => ({ default: m.SupplierPortalPage })));

const P = PERMISSIONS.procurement;

/**
 * Purchasing (Source-to-Pay) routes, every page guarded by its read permission (ADR-PROCUREMENT-001). Static
 * segments come before `:id` routes.
 */
export const procurementRoutes = (
  <>
    <Route path="/procurement/purchase-requests" element={<RequirePermission permission={P.purchaseRequest.read}><PurchaseRequestsPage /></RequirePermission>} />
    <Route path="/procurement/purchase-requests/new" element={<RequirePermission permission={P.purchaseRequest.create}><PurchaseRequestCreatePage /></RequirePermission>} />
    <Route path="/procurement/sourcing" element={<RequirePermission permission={P.purchaseOrder.create}><SourcingWorkbenchPage /></RequirePermission>} />
    <Route path="/procurement/reports/open-orders" element={<RequirePermission permission={P.purchaseOrder.read}><OpenOrdersPage /></RequirePermission>} />
    <Route path="/procurement/purchase-orders" element={<RequirePermission permission={P.purchaseOrder.read}><PurchaseOrdersPage /></RequirePermission>} />
    <Route path="/procurement/purchase-orders/new" element={<RequirePermission permission={P.purchaseOrder.create}><PurchaseOrderCreatePage /></RequirePermission>} />
    <Route path="/procurement/purchase-orders/:id/receive" element={<RequirePermission permission={P.purchaseOrder.receive}><PurchaseOrderReceivePage /></RequirePermission>} />
    <Route path="/procurement/purchase-orders/:id/pay" element={<RequirePermission permission={P.purchaseOrder.pay}><PurchaseOrderPayPage /></RequirePermission>} />
    <Route path="/procurement/purchase-orders/:id" element={<RequirePermission permission={P.purchaseOrder.read}><PurchaseOrderDetailPage /></RequirePermission>} />
    <Route path="/procurement/goods-receipts" element={<RequirePermission permission={P.goodsReceipt.read}><GoodsReceiptsPage /></RequirePermission>} />
    <Route path="/procurement/goods-receipts/new" element={<RequirePermission permission={P.goodsReceipt.create}><GoodsReceiptCreatePage /></RequirePermission>} />
    <Route path="/procurement/goods-receipts/:id" element={<RequirePermission permission={P.goodsReceipt.read}><GoodsReceiptDetailPage /></RequirePermission>} />
    <Route path="/procurement/landed-cost-vouchers" element={<RequirePermission permission={PERMISSIONS.landedCost.read}><LandedCostVouchersPage /></RequirePermission>} />
    <Route path="/procurement/landed-cost-vouchers/new" element={<RequirePermission permission={PERMISSIONS.landedCost.create}><LandedCostVoucherEditPage /></RequirePermission>} />
    <Route path="/procurement/landed-cost-vouchers/:id" element={<RequirePermission permission={PERMISSIONS.landedCost.read}><LandedCostVoucherDetailPage /></RequirePermission>} />
    <Route path="/procurement/landed-cost-vouchers/:id/edit" element={<RequirePermission permission={PERMISSIONS.landedCost.create}><LandedCostVoucherEditPage /></RequirePermission>} />
    <Route path="/procurement/shipments" element={<RequirePermission permission={PERMISSIONS.landedCost.read}><ShipmentsPage /></RequirePermission>} />
    <Route path="/procurement/landed-cost-control" element={<RequirePermission permission={PERMISSIONS.landedCost.read}><LandedCostControlPage /></RequirePermission>} />
    <Route path="/procurement/landed-cost-reports" element={<RequirePermission permission={PERMISSIONS.landedCost.read}><LandedCostReportsPage /></RequirePermission>} />
    <Route path="/procurement/three-way-match" element={<RequirePermission permission={P.threeWayMatch.read}><ThreeWayMatchPage /></RequirePermission>} />
    <Route path="/procurement/debit-notes" element={<RequirePermission permission={PERMISSIONS.debitNote.read}><DebitNotesPage /></RequirePermission>} />
    <Route path="/procurement/debit-notes/new" element={<RequirePermission permission={PERMISSIONS.debitNote.create}><DebitNoteCreatePage /></RequirePermission>} />
    <Route path="/procurement/suppliers" element={<RequirePermission permission={PERMISSIONS.supplier.read}><SuppliersPage /></RequirePermission>} />
    <Route path="/procurement/suppliers/:partnerId" element={<RequirePermission permission={PERMISSIONS.supplier.read}><SupplierDetailPage /></RequirePermission>} />
    <Route path="/procurement/rfqs" element={<RequirePermission permission={PERMISSIONS.rfq.read}><RfqListPage /></RequirePermission>} />
    <Route path="/procurement/rfqs/:id" element={<RequirePermission permission={PERMISSIONS.rfq.read}><RfqDetailPage /></RequirePermission>} />
    <Route path="/procurement/agreements" element={<RequirePermission permission={PERMISSIONS.purchaseAgreement.read}><AgreementsPage /></RequirePermission>} />
    <Route path="/procurement/agreements/:id" element={<RequirePermission permission={PERMISSIONS.purchaseAgreement.read}><AgreementDetailPage /></RequirePermission>} />
    <Route path="/procurement/claims" element={<RequirePermission permission={PERMISSIONS.supplierClaim.read}><SupplierClaimsPage /></RequirePermission>} />
    <Route path="/procurement/po-changes" element={<RequirePermission permission={PERMISSIONS.poChange.approve}><PoChangeApprovalsPage /></RequirePermission>} />
    <Route path="/procurement/commitments" element={<RequirePermission permission={PERMISSIONS.budgetControl.commitments}><CommitmentsPage /></RequirePermission>} />
    <Route path="/procurement/inspections" element={<RequirePermission permission={PERMISSIONS.inspection.read}><InspectionsPage /></RequirePermission>} />
    <Route path="/procurement/reports/emergency" element={<RequirePermission permission={PERMISSIONS.emergencyPurchase.review}><EmergencyPurchasesPage /></RequirePermission>} />
    <Route path="/procurement/analytics" element={<RequirePermission permission={PERMISSIONS.procurementAnalytics.read}><ProcurementDashboardPage /></RequirePermission>} />
    <Route path="/procurement/replenishment" element={<RequirePermission permission={PERMISSIONS.replenishment.read}><ReplenishmentPage /></RequirePermission>} />
    <Route path="/procurement/policies" element={<RequirePermission permission={PERMISSIONS.procurementPolicy.read}><PurchasingPoliciesPage /></RequirePermission>} />
  </>
);

/** Full-page print views (outside the app shell so only the document prints). */
export const procurementPrintRoutes = (
  <Route path="/procurement/purchase-orders/:id/print" element={<RequirePermission permission={P.purchaseOrder.read}><PurchaseOrderPrintPage /></RequirePermission>} />
);

/** Public supplier portal (no login; the link token is the credential). Mounted outside ProtectedRoute. */
export const procurementPublicRoutes = <Route path="/supplier-portal/:token" element={<SupplierPortalPage />} />;

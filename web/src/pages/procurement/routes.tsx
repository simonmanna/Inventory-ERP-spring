import { Route } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { RequirePermission } from '@/components/require-permission';
import { LandedCostReportsPage } from '@/pages/costing/LandedCostReportsPage';
import { LandedCostVoucherDetailPage, LandedCostVoucherEditPage, LandedCostVouchersPage } from '@/pages/costing/LandedCostVouchersPage';
import { ShipmentsPage } from '@/pages/costing/ShipmentsPage';
import { PurchaseRequestsPage } from './purchase-requests';
import { PurchaseOrdersPage } from './purchase-orders';
import { PurchaseOrderCreatePage } from './purchase-order-create';
import { PurchaseOrderDetailPage } from './PurchaseOrderDetailPage';
import { PurchaseOrderReceivePage } from './PurchaseOrderReceivePage';
import { PurchaseOrderPayPage } from './PurchaseOrderPayPage';
import { GoodsReceiptsPage } from './goods-receipts';
import { GoodsReceiptCreatePage } from './goods-receipt-create';
import GoodsReceiptDetailPage from './goods-receipt-detail';
import { ThreeWayMatchPage } from './three-way-match';
import { DebitNotesPage } from './debit-notes';
import { DebitNoteCreatePage } from './debit-note-create';
import { SuppliersPage } from './s2p/SuppliersPage';
import { SupplierDetailPage } from './s2p/SupplierDetailPage';
import { PurchasingPoliciesPage } from './s2p/PurchasingPoliciesPage';
import { PurchaseRequestCreatePage } from './s2p/PurchaseRequestCreatePage';
import { SourcingWorkbenchPage } from './s2p/SourcingWorkbenchPage';
import { OpenOrdersPage } from './s2p/OpenOrdersPage';
import { PurchaseOrderPrintPage } from './s2p/PurchaseOrderPrintPage';
import { RfqDetailPage, RfqListPage } from './s2p/RfqPages';
import { AgreementDetailPage, AgreementsPage } from './s2p/AgreementPages';
import { CommitmentsPage, PoChangeApprovalsPage, SupplierClaimsPage } from './s2p/ControlsPages';
import { EmergencyPurchasesPage, InspectionsPage } from './s2p/QualityPages';

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
    <Route path="/procurement/policies" element={<RequirePermission permission={PERMISSIONS.procurementPolicy.read}><PurchasingPoliciesPage /></RequirePermission>} />
  </>
);

/** Full-page print views (outside the app shell so only the document prints). */
export const procurementPrintRoutes = (
  <Route path="/procurement/purchase-orders/:id/print" element={<RequirePermission permission={P.purchaseOrder.read}><PurchaseOrderPrintPage /></RequirePermission>} />
);

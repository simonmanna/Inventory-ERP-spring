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

const P = PERMISSIONS.procurement;

/**
 * Purchasing (Source-to-Pay) routes, every page guarded by its read permission (ADR-PROCUREMENT-001). Static
 * segments come before `:id` routes.
 */
export const procurementRoutes = (
  <>
    <Route path="/procurement/purchase-requests" element={<RequirePermission permission={P.purchaseRequest.read}><PurchaseRequestsPage /></RequirePermission>} />
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
    <Route path="/procurement/policies" element={<RequirePermission permission={PERMISSIONS.procurementPolicy.read}><PurchasingPoliciesPage /></RequirePermission>} />
  </>
);

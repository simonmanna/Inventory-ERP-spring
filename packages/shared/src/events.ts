/**
 * Typed domain-event contract shared by publishers and subscribers (ADR-003).
 * Add new events here so the bus stays type-safe across modules.
 */

export const EVENTS = {
  // master data
  PartnerCreated: 'partner.created',
  PartnerUpdated: 'partner.updated',
  PartnerDeleted: 'partner.deleted',
  ProductCreated: 'product.created',
  ProductUpdated: 'product.updated',
  ProductDeleted: 'product.deleted',
  UserRegistered: 'user.registered',
  UserLoggedIn: 'user.logged_in',
  // accounting (Phase 2)
  JournalPosted: 'journal.posted',
  JournalReversed: 'journal.reversed',
  CashReceived: 'cash.received',
  CashPaid: 'cash.paid',
  BankTransfer: 'bank.transfer',
  // invoicing / AR (Phase 3)
  InvoiceCreated: 'invoice.created',
  InvoicePosted: 'invoice.posted',
  InvoicePaid: 'invoice.paid',
  InvoiceCancelled: 'invoice.cancelled',
  CreditNoteIssued: 'creditnote.issued',
  PaymentReceived: 'payment.received',
  PaymentAllocated: 'payment.allocated',
  // purchasing / AP
  BillPosted: 'bill.posted',
  BillCancelled: 'bill.cancelled',
  PaymentVoided: 'payment.voided',
  // inventory (Phase 4)
  StockReceived: 'stock.received',
  StockIssued: 'stock.issued',
  StockAdjusted: 'stock.adjusted',
  StockTransferred: 'stock.transferred',
  // M5 — cash sessions
  CashSessionOpened: 'cash.session.opened',
  CashSessionClosed: 'cash.session.closed',
  CashSessionReconciled: 'cash.session.reconciled',
  CashMovementRecorded: 'cash.movement.recorded',
  CashBankingRecorded: 'cash.banking.recorded',
  // D2-2 — period close
  FiscalPeriodClosed: 'fiscal_period.closed',
  FiscalPeriodLocked: 'fiscal_period.locked',
  FiscalPeriodReopened: 'fiscal_period.reopened',
  // Phase C — FX revaluation
  FxRevaluationRan: 'fx_revaluation.ran',
  // Phase D — bank reconciliation
  BankStatementImported: 'bank_statement.imported',
  BankReconciliationRan: 'bank_reconciliation.ran',
  // Phase F.6 — Procurement chain
  PurchaseRequestCreated: 'purchase_request.created',
  PurchaseRequestSubmitted: 'purchase_request.submitted',
  PurchaseRequestApproved: 'purchase_request.approved',
  PurchaseRequestRejected: 'purchase_request.rejected',
  PurchaseRequestConverted: 'purchase_request.converted',
  PurchaseOrderCreated: 'purchase_order.created',
  PurchaseOrderApproved: 'purchase_order.approved',
  PurchaseOrderSent: 'purchase_order.sent',
  PurchaseOrderCancelled: 'purchase_order.cancelled',
  GoodsReceiptPosted: 'goods_receipt.posted',
  GoodsReceiptCancelled: 'goods_receipt.cancelled',
  ThreeWayMatchComputed: 'three_way_match.computed',
  DebitNoteCreated: 'debit_note.created',
  DebitNotePosted: 'debit_note.posted',
  // ADR-PROCUREMENT-001 — Source-to-Pay
  SupplierStatusChanged: 'supplier.status_changed',
  SupplierBankAccountAdded: 'supplier.bank_account_added',
  SupplierBankAccountVerified: 'supplier.bank_account_verified',
  SupplierCertificateExpiring: 'supplier.certificate_expiring',
  RfqSent: 'rfq.sent',
  RfqAwarded: 'rfq.awarded',
  AgreementActivated: 'agreement.activated',
  AgreementExhausted: 'agreement.exhausted',
  PurchaseOrderClosed: 'purchase_order.closed',
  // DMS — generic document lifecycle (Phase 2 engine facts; one event per engine
  // action so posting/reversal/notification hooks can subscribe generically).
  DocumentCreated: 'document.created',
  DocumentSubmitted: 'document.submitted',
  DocumentApproved: 'document.approved',
  DocumentRejected: 'document.rejected',
  DocumentConfirmed: 'document.confirmed',
  DocumentIssued: 'document.issued',
  DocumentActivated: 'document.activated',
  DocumentExpired: 'document.expired',
  DocumentRevoked: 'document.revoked',
  DocumentPosted: 'document.posted',
  DocumentPaid: 'document.paid',
  DocumentClosed: 'document.closed',
  DocumentCancelled: 'document.cancelled',
  DocumentReversed: 'document.reversed',
  DocumentReissued: 'document.reissued',
  DocumentTerminated: 'document.terminated',
  DocumentArchived: 'document.archived',
  DocumentAmended: 'document.amended',
  // Phase F.6 — Push notifications
  PushSubscribed: 'push.subscribed',
  // POS vertical (Phase 8) — Phase A additions (sell loop)
  PosSaleCompleted: 'pos.sale.completed',
  PosRefundCompleted: 'pos.refund.completed',
  PosHoldCreated: 'pos.hold.created',
  PosHoldRecalled: 'pos.hold.recalled',
  PosHoldDeleted: 'pos.hold.deleted',
  PosOverrideApproved: 'pos.override.approved',
  PosVoidCompleted: 'pos.void.completed',
  PosReportGenerated: 'pos.report.generated',
  /// A cashier signed in at a terminal with their PIN. HR listens for this to
  /// raise an attendance clock-in; POS itself neither knows nor cares that HR
  /// exists. See DomainEventMap for why this goes over the bus.
  PosPinLogin: 'pos.pin.login',
  /// The cashier logged off the terminal. HR turns it into a clock-out.
  PosPinLogoff: 'pos.pin.logoff',
  // POS Order → Invoice → Receipt domain (DDD split)
  PosOrderCreated: 'pos.order.created',
  PosOrderUpdated: 'pos.order.updated',
  PosOrderInvoiced: 'pos.order.invoiced',
  PosOrderClosed: 'pos.order.closed',
  PosOrderCancelled: 'pos.order.cancelled',
  /** A-016: a single line was voided off an open order (partial or whole). */
  PosOrderItemVoided: 'pos.order.item_voided',
  PosInvoiceSettled: 'pos.invoice.settled',
  PosInvoiceCredited: 'pos.invoice.credited',
  PosInvoiceWrittenOff: 'pos.invoice.written_off',
  CheckoutCompensationFailed: 'pos.checkout.compensation_failed',
  StoreCreditReversalFailed: 'pos.store_credit.reversal_failed',
  // POS Phase T1 — Tables Management (ADR-012)
  PosTableCreated: 'pos.table.created',
  PosTableUpdated: 'pos.table.updated',
  PosTableDeleted: 'pos.table.deleted',
  PosTableStatusChanged: 'pos.table.status_changed',
  PosTableMerged: 'pos.table.merged',
  PosTableUnmerged: 'pos.table.unmerged',
  PosTableTransferred: 'pos.table.transferred',
  PosTableSplit: 'pos.table.split',
  PosTableCleaned: 'pos.table.cleaned',
  PosTableReservationCreated: 'pos.table.reservation_created',
  PosTableReservationSeated: 'pos.table.reservation_seated',
  PosTableReservationCancelled: 'pos.table.reservation_cancelled',
  PosTableReservationNoShow: 'pos.table.reservation_no_show',
  // Staff / RBAC management
  RoleCreated: 'role.created',
  RoleUpdated: 'role.updated',
  RoleDeleted: 'role.deleted',
  UserCreated: 'user.created',
  UserUpdated: 'user.updated',
  UserDeleted: 'user.deleted',
  UserPasswordReset: 'user.password_reset',
  UserUnlocked: 'user.unlocked',
  // Fixed Assets
  AssetCreated: 'fixed_asset.created',
  AssetUpdated: 'fixed_asset.updated',
  AssetDeleted: 'fixed_asset.deleted',
  AssetAssigned: 'fixed_asset.assigned',
  AssetTransferred: 'fixed_asset.transferred',
  AssetDisposed: 'fixed_asset.disposed',
  DepreciationRun: 'fixed_asset.depreciation_run',
  MaintenanceDue: 'fixed_asset.maintenance_due',
  WarrantyExpiring: 'fixed_asset.warranty_expiring',

  // ─── Business events (Phase A) ────────────────────────────────────────────
  // Immutable facts about the domain-neutral `Order` lifecycle, emitted by the
  // workflow engine (ADR-007) and recorded in `DomainEventLog`. Past tense: an
  // event names something that HAPPENED, unlike the transition `action` that
  // caused it.
  //
  // Distinct from the `pos.order.*` family above, which is the POS-specific
  // notification stream (SSE fan-out to the floor map) with its own payloads.
  // These are the ledger facts; those are UI nudges.
  OrderConfirmed: 'order.confirmed',
  OrderFulfillmentStarted: 'order.fulfillment_started',
  OrderCompleted: 'order.completed',
  OrderClosed: 'order.closed',
  OrderCancelled: 'order.cancelled',
  OrderReopened: 'order.reopened',
  OrderSuperseded: 'order.superseded',

  // Per-fulfillment-document facts. The `strategy` discriminates kitchen /
  // delivery / rental / repair / production, so a subscriber can react to
  // "some fulfillment finished" without knowing which module produced it —
  // this is what the Phase C inventory posting policy binds to.
  FulfillmentStarted: 'fulfillment.started',
  FulfillmentCompleted: 'fulfillment.completed',

  // Communication platform. `message.created` fires when a human/system message
  // is persisted (drives dispatch); `message.received` on inbound from an
  // external provider; `message.delivered`/`.failed` are the transport outcome.
  // `channel.status_changed` tracks provider connection health for the UI.
  CommunicationMessageCreated: 'communication.message.created',
  CommunicationMessageReceived: 'communication.message.received',
  CommunicationMessageDelivered: 'communication.message.delivered',
  CommunicationMessageFailed: 'communication.message.failed',
  CommunicationChannelStatusChanged: 'communication.channel.status_changed',

  // Fleet & logistics (ADR-S009). Facts about the vehicle register that later sub-modules (dispatch, maintenance,
  // costing) and alerts react to; published after the change commits.
  FleetVehicleCreated: 'fleet.vehicle.created',
  FleetVehicleStatusChanged: 'fleet.vehicle.status_changed',
  FleetMeterCorrected: 'fleet.meter.corrected',
  CrmLeadConverted: 'crm.lead.converted',
  CrmDealWon: 'crm.deal.won',
  CrmDealLost: 'crm.deal.lost',
  CrmQuotationApproved: 'crm.quotation.approved',
  CrmQuotationConverted: 'crm.quotation.converted',
  // Fleet 1.2 — drivers & operators: vehicle check-out / check-in and settled driver allowance claims.
  FleetVehicleCheckedOut: 'fleet.vehicle.checked_out',
  FleetVehicleCheckedIn: 'fleet.vehicle.checked_in',
  FleetClaimSettled: 'fleet.claim.settled',
  // Fleet maintenance (1.6): work orders raised / completed, defects reported (a critical one blocks dispatch).
  FleetWorkOrderCreated: 'fleet.workorder.created',
  FleetWorkOrderCompleted: 'fleet.workorder.completed',
  FleetDefectReported: 'fleet.defect.reported',
  // Fleet 1.7 / 1.8: incidents, traffic fines (a fine charged to the driver raises a payroll deduction request that
  // hr payroll consumes), telematics alerts (speeding, idling, geofence) pulled from the provider.
  FleetIncidentReported: 'fleet.incident.reported',
  FleetFineCharged: 'fleet.fine.charged',
  FleetFineDeductionRequested: 'fleet.fine.deduction_requested',
  FleetFineDeductionCancelled: 'fleet.fine.deduction_cancelled',
  FleetTelematicsAlertRaised: 'fleet.telematics.alert_raised',
  // Fleet 1.3 — dispatch: a trip left the yard (stock issued once), was delivered (POD), closed or returned; a delivery
  // discrepancy was decided (an approved shortage on invoiced goods carries the draft credit note id).
  FleetTripDispatched: 'fleet.trip.dispatched',
  FleetTripDelivered: 'fleet.trip.delivered',
  FleetTripClosed: 'fleet.trip.closed',
  FleetTripReturned: 'fleet.trip.returned',
  FleetDeliveryDiscrepancyDecided: 'fleet.delivery.discrepancy_decided',
  // Fleet 1.9 — a transporter freight bill was matched to the supplier invoice (AP bill + accrual, or inbound landed
  // cost); an expiry / service-due reminder was raised (communication rules can route it to email / WhatsApp); hr
  // payroll included / re-opened inputs other modules queued (fleet settles fines deducted from drivers' pay).
  FleetFreightBillMatched: 'fleet.freight_bill.matched',
  FleetReminderDue: 'fleet.reminder.due',
  HrPayrollInputsIncluded: 'hr.payroll.inputs_included',
  HrPayrollInputsReopened: 'hr.payroll.inputs_reopened',
  // Fleet 1.4 weighbridge: a ticket completed (net weight final — drives the trip's loaded quantity / stock issue in
  // 1.4b and the GRN of inbound weighing), an out-of-tolerance exception raised for approval, a ticket voided.
  WeighbridgeTicketCompleted: 'weighbridge.ticket.completed',
  WeighbridgeExceptionRaised: 'weighbridge.exception.raised',
  WeighbridgeTicketVoided: 'weighbridge.ticket.voided',
} as const;

/** Payload emitted for a created/updated/deleted tenant entity. */
export interface EntityEventPayload {
  id: string;
  organizationId: string;
  actorId?: string;
}

export interface UserLoggedInPayload {
  userId: string;
  organizationId: string;
  at: string;
}

export interface JournalPostedPayload {
  organizationId: string;
  journalEntryId: string;
  entryNumber: string;
  sourceType?: string;
  sourceId?: string;
}

export interface JournalReversedPayload {
  organizationId: string;
  journalEntryId: string;
  reversalEntryId: string;
}

export interface TreasuryPayload {
  organizationId: string;
  journalEntryId: string;
  amount: string;
}

export interface DocumentEventPayload {
  organizationId: string;
  documentId: string;
  documentNumber: string;
}

export interface PaymentEventPayload {
  organizationId: string;
  paymentId: string;
  amount: string;
}

export interface PaymentAllocatedPayload {
  organizationId: string;
  paymentId: string;
  documentId: string;
  amount: string;
}

export interface StockEventPayload {
  organizationId: string;
  productId: string;
  locationId: string;
  ledgerCode: string;
  quantity: string;
  /** Unit cost at the time of the move (AVCO recompute, FIFO batch cost, or STANDARD). */
  unitCost?: string;
  /** Total monetary value (= unitCost × quantity). */
  totalValue?: string;
  /** New running-average cost after a receipt (AVCO only). */
  newRunningAverage?: string;
  /** Delta between counted and system quantity on adjustment. */
  delta?: string;
}

/** Maps every event name to its payload type. */
/**
 * Emitted when a cashier PIN-logs in at a terminal.
 *
 * This exists so HR can turn a POS sign-in into an attendance clock-in without
 * the two modules importing each other: `pos` and `hr` are both verticals and
 * the architecture rules forbid a direct dependency in either direction. It is
 * also why the coupling is safe — POS publishes and moves on, so HR being slow,
 * broken or switched off can never delay or fail a cashier's login.
 */
export interface PosPinLoginPayload {
  userId: string;
  organizationId: string;
  at: string;
  deviceId?: string | null;
}

export interface DomainEventMap {
  'partner.created': EntityEventPayload;
  'partner.updated': EntityEventPayload;
  'partner.deleted': EntityEventPayload;
  'product.created': EntityEventPayload;
  'product.updated': EntityEventPayload;
  'product.deleted': EntityEventPayload;
  'user.registered': EntityEventPayload;
  'user.logged_in': UserLoggedInPayload;
  'pos.pin.login': PosPinLoginPayload;
  'pos.pin.logoff': PosPinLoginPayload;
  'journal.posted': JournalPostedPayload;
  'journal.reversed': JournalReversedPayload;
  'cash.received': TreasuryPayload;
  'cash.paid': TreasuryPayload;
  'bank.transfer': TreasuryPayload;
  'invoice.created': DocumentEventPayload;
  'invoice.posted': DocumentEventPayload;
  'invoice.paid': DocumentEventPayload;
  'invoice.cancelled': DocumentEventPayload;
  'creditnote.issued': DocumentEventPayload;
  'payment.received': PaymentEventPayload;
  'payment.allocated': PaymentAllocatedPayload;
  'bill.posted': DocumentEventPayload;
  'bill.cancelled': DocumentEventPayload;
  'payment.voided': PaymentEventPayload;
  'stock.received': StockEventPayload;
  'stock.issued': StockEventPayload;
  'stock.adjusted': StockEventPayload;
  'stock.transferred': StockEventPayload;
  'cash.session.opened': { organizationId: string; sessionId: string; cashRegisterId: string };
  'cash.session.closed': { organizationId: string; sessionId: string; expected: string; counted: string; variance: string };
  'cash.session.reconciled': { organizationId: string; sessionId: string; cashRegisterId: string };
  'cash.movement.recorded': { organizationId: string; sessionId: string; movementId: string; movementType: string; amount: string };
  'cash.banking.recorded': { organizationId: string; sessionId: string; amount: string; bankName: string };
  'fiscal_period.closed': { organizationId: string; periodId: string; periodName: string; closingEntryId: string; netIncome: string };
  'fiscal_period.locked': { organizationId: string; periodId: string; periodName: string };
  'fiscal_period.reopened': { organizationId: string; periodId: string; periodName: string };
  'fx_revaluation.ran': { organizationId: string; asOf: string; revalued: number; totalGain: string };
  'bank_statement.imported': { organizationId: string; bankAccountId: string; imported: number; skipped: number };
  'bank_reconciliation.ran': { organizationId: string; bankAccountId: string; runId: string; matched: number; unmatched: number };
  'purchase_request.created': { organizationId: string; requestId: string; requestNumber: string };
  'purchase_request.submitted': { organizationId: string; requestId: string };
  'purchase_request.approved': { organizationId: string; requestId: string; approverId: string };
  'purchase_request.rejected': { organizationId: string; requestId: string; reason: string };
  'purchase_request.converted': { organizationId: string; requestId: string; purchaseOrderIds: string[] };
  'purchase_order.created': { organizationId: string; orderId: string; orderNumber: string; partnerId: string };
  'purchase_order.approved': { organizationId: string; orderId: string; approverId: string };
  'purchase_order.sent': { organizationId: string; orderId: string; sentAt: string };
  'purchase_order.cancelled': { organizationId: string; orderId: string; reason: string };
  'goods_receipt.posted': { organizationId: string; receiptId: string; receiptNumber: string; orderId: string };
  'goods_receipt.cancelled': { organizationId: string; receiptId: string; reason: string };
  'three_way_match.computed': { organizationId: string; purchaseOrderId: string; matched: number; mismatched: number; blocked: number };
  'debit_note.created': { organizationId: string; noteId: string; noteNumber: string; direction: 'outbound' | 'inbound' };
  'debit_note.posted': { organizationId: string; noteId: string; direction: 'outbound' | 'inbound'; amount: string };
  'supplier.status_changed': { organizationId: string; partnerId: string; from: string; to: string; reason: string };
  'supplier.bank_account_added': { organizationId: string; partnerId: string; bankAccountId: string };
  'supplier.bank_account_verified': { organizationId: string; partnerId: string; bankAccountId: string };
  'supplier.certificate_expiring': { organizationId: string; partnerId: string; certificateId: string; type: string; validTo: string };
  'rfq.sent': { organizationId: string; rfqId: string };
  'rfq.awarded': { organizationId: string; rfqId: string };
  'agreement.activated': { organizationId: string; agreementId: string };
  'agreement.exhausted': { organizationId: string; agreementId: string };
  'purchase_order.closed': { organizationId: string; orderId: string; reason: string };
  // DMS — generic document lifecycle facts (Phase 2 engine). Every engine action
  // emits `document.<action>`; subscribers (posting/reversal/notification hooks)
  // filter on `action`/`documentTypeCode` as needed.
  'document.created': DocumentLifecyclePayload;
  'document.submitted': DocumentLifecyclePayload;
  'document.approved': DocumentLifecyclePayload;
  'document.rejected': DocumentLifecyclePayload;
  'document.confirmed': DocumentLifecyclePayload;
  'document.issued': DocumentLifecyclePayload;
  'document.activated': DocumentLifecyclePayload;
  'document.expired': DocumentLifecyclePayload;
  'document.revoked': DocumentLifecyclePayload;
  'document.posted': DocumentLifecyclePayload;
  'document.paid': DocumentLifecyclePayload;
  'document.closed': DocumentLifecyclePayload;
  'document.cancelled': DocumentLifecyclePayload;
  'document.reversed': DocumentLifecyclePayload;
  'document.reissued': DocumentLifecyclePayload;
  'document.terminated': DocumentLifecyclePayload;
  'document.archived': DocumentLifecyclePayload;
  'document.amended': DocumentLifecyclePayload;
  'push.subscribed': { organizationId: string; userId: string; subscriptionId: string };
  'pos.sale.completed': { organizationId: string; invoiceId: string; invoiceNumber: string; cashSessionId?: string; total: string };
  'pos.refund.completed': { organizationId: string; invoiceId: string; creditNoteId: string; total: string };
  'pos.hold.created': { organizationId: string; holdId: string; name: string; total: string; heldById: string };
  'pos.hold.recalled': { organizationId: string; holdId: string; recalledById: string };
  'pos.hold.deleted': { organizationId: string; holdId: string };
  'pos.override.approved': { organizationId: string; approverId: string; overrideKind: 'discount' | 'price_change' | 'void' | 'manual_refund' | 'write_off' | 'shift_handover'; referenceId?: string; amount?: string };
  'pos.void.completed': { organizationId: string; invoiceId?: string; documentLineId?: string; voidedById: string; reason?: string };
  'pos.report.generated': { organizationId: string; reportKind: 'x' | 'z' | 'hourly' | 'top_items' | 'variance'; cashSessionId?: string; asOf: string };
  'pos.order.created': { organizationId: string; orderId: string; orderNumber: string; tableId?: string };
  'pos.order.updated': { organizationId: string; orderId: string; version: number };
  'pos.order.invoiced': { organizationId: string; orderId: string; invoiceId: string; invoiceNumber: string };
  'pos.order.closed': { organizationId: string; orderId: string; invoiceId?: string };
  'pos.order.cancelled': { organizationId: string; orderId: string; reason?: string };
  /** A-016: one line was voided off an open order (partial or whole). */
  'pos.order.item_voided': {
    organizationId: string; orderId: string; orderItemId: string;
    description: string; quantity: number; whole: boolean;
    reason: string; approvedById?: string | null;
  };
  'pos.invoice.settled': { organizationId: string; invoiceId: string; invoiceNumber: string; paymentMode: string };
  'pos.invoice.credited': { organizationId: string; invoiceId: string; invoiceNumber: string; partnerId: string; amount: string };
  'pos.invoice.written_off': { organizationId: string; invoiceId: string; invoiceNumber: string; amount: string };
  'pos.checkout.compensation_failed': { organizationId: string; invoiceId: string; paymentIds: string[] };
  'pos.store_credit.reversal_failed': { organizationId: string; invoiceId: string; amount: number };
  'pos.table.created': { organizationId: string; tableId: string; number: number; name: string };
  'pos.table.updated': { organizationId: string; tableId: string; changes: Record<string, unknown> };
  'pos.table.deleted': { organizationId: string; tableId: string };
  'pos.table.status_changed': { organizationId: string; tableId: string; from: string; to: string; reason?: string };
  'pos.table.merged': { organizationId: string; sourceId: string; targetId: string; orderIds: string[]; actorId: string };
  'pos.table.unmerged': { organizationId: string; tableId: string; actorId: string };
  'pos.table.transferred': { organizationId: string; sourceId: string; targetId: string; orderIds: string[]; actorId: string };
  'pos.table.split': { organizationId: string; sourceOrderId: string; newOrderIds: string[]; actorId: string };
  'pos.table.cleaned': { organizationId: string; tableId: string; actorId: string };
  'pos.table.reservation_created': { organizationId: string; reservationId: string; tableId: string; startAt: string; endAt: string };
  'pos.table.reservation_seated': { organizationId: string; reservationId: string; tableId: string; orderId?: string };
  'pos.table.reservation_cancelled': { organizationId: string; reservationId: string; tableId: string };
  'pos.table.reservation_no_show': { organizationId: string; reservationId: string; tableId: string };
  'role.created': EntityEventPayload;
  'role.updated': EntityEventPayload;
  'role.deleted': EntityEventPayload;
  'user.created': EntityEventPayload;
  'user.updated': EntityEventPayload;
  'user.deleted': EntityEventPayload;
  'user.password_reset': EntityEventPayload;
  'user.unlocked': EntityEventPayload;
  'product.restored': EntityEventPayload;
  // Fixed Assets
  'fixed_asset.created': EntityEventPayload;
  'fixed_asset.updated': EntityEventPayload;
  'fixed_asset.deleted': EntityEventPayload;
  'fixed_asset.assigned': { organizationId: string; assetId: string; assignedToId: string; assignedToType: string };
  'fixed_asset.transferred': { organizationId: string; assetId: string; fromLocation: string; toLocation: string };
  'fixed_asset.disposed': { organizationId: string; assetId: string; method: string; value: string };
  'fixed_asset.depreciation_run': { organizationId: string; period: string; entriesCount: number };
  'fixed_asset.maintenance_due': { organizationId: string; assetId: string; assetName: string; maintenanceId: string };
  'fixed_asset.warranty_expiring': { organizationId: string; assetId: string; assetName: string; daysLeft: number };

  // ─── Business events (Phase A) ────────────────────────────────────────────
  /** Every order-lifecycle fact carries the same shape; `action` is the
   *  workflow action that produced it (`confirm`, `complete`, `close`, …). */
  'order.confirmed': OrderLifecycleEventPayload;
  'order.fulfillment_started': OrderLifecycleEventPayload;
  'order.completed': OrderLifecycleEventPayload;
  'order.closed': OrderLifecycleEventPayload;
  'order.cancelled': OrderLifecycleEventPayload;
  'order.reopened': OrderLifecycleEventPayload;
  'order.superseded': OrderLifecycleEventPayload;

  'fulfillment.started': FulfillmentEventPayload;
  'fulfillment.completed': FulfillmentEventPayload;

  // Communication platform.
  'communication.message.created': CommunicationMessageEventPayload;
  'communication.message.received': CommunicationMessageEventPayload;
  'communication.message.delivered': CommunicationDeliveryEventPayload;
  'communication.message.failed': CommunicationDeliveryEventPayload;
  'communication.channel.status_changed': CommunicationChannelEventPayload;

  // Fleet & logistics.
  'fleet.vehicle.created': { organizationId: string; vehicleId: string; code: string; categoryId: string };
  'fleet.vehicle.status_changed': FleetVehicleStatusChangedPayload;
  'fleet.meter.corrected': FleetMeterCorrectedPayload;
  'crm.lead.converted': { organizationId: string; dealId: string; partnerId: string };
  'crm.deal.won': { organizationId: string; dealId: string; amount: number };
  'crm.deal.lost': { organizationId: string; dealId: string; lostReasonId: string | null };
  'crm.quotation.approved': { organizationId: string; quotationId: string; number: string };
  'crm.quotation.converted': { organizationId: string; quotationId: string; convertedType: 'order' | 'proforma'; convertedId: string };
  'fleet.vehicle.checked_out': FleetCheckoutEventPayload;
  'fleet.vehicle.checked_in': FleetCheckoutEventPayload;
  'fleet.claim.settled': FleetClaimSettledPayload;
  'fleet.workorder.created': {
    organizationId: string; workOrderId: string; code: string; vehicleId: string; type: string; source: string;
  };
  'fleet.workorder.completed': {
    organizationId: string; workOrderId: string; code: string; vehicleId: string; type: string; totalCost: string;
  };
  'fleet.defect.reported': {
    organizationId: string; defectId: string; code: string; vehicleId: string; severity: string;
    blocksDispatch: boolean;
  };
  'fleet.incident.reported': {
    organizationId: string; incidentId: string; incidentNumber: string; vehicleId: string; incidentType: string;
    severity: string; occurredAt: string;
  };
  'fleet.fine.charged': {
    organizationId: string; fineId: string; fineNumber: string; vehicleId: string; chargedTo: 'company' | 'driver';
    amount: string; expenseId: string | null; deductionId: string | null;
  };
  'fleet.fine.deduction_requested': FleetFineDeductionPayload;
  'fleet.fine.deduction_cancelled': FleetFineDeductionPayload & { reason: string | null };
  'fleet.telematics.alert_raised': {
    organizationId: string; vehicleId: string; eventId: string; eventType: string; severity: string; occurredAt: string;
  };
  'fleet.trip.dispatched': FleetTripEventPayload;
  'fleet.trip.delivered': FleetTripEventPayload;
  'fleet.trip.closed': FleetTripEventPayload & { distanceKm: string | null; freightAmount: string | null };
  'fleet.trip.returned': FleetTripEventPayload & { reason: string };
  'fleet.freight_bill.matched': {
    organizationId: string; freightBillId: string; code: string; direction: 'outbound' | 'inbound';
    transporterPartnerId: string; amount: string; varianceAmount: string; vendorBillId: string | null;
    journalEntryId: string | null; tripCount: number;
  };
  'fleet.reminder.due': {
    organizationId: string; itemType: 'compliance_document' | 'driver_document' | 'vehicle_tare' | 'service_due';
    itemId: string; state: string; dueOn: string | null; vehicleId: string | null; driverId: string | null;
    title: string; body: string;
  };
  'hr.payroll.inputs_included': HrPayrollInputsPayload;
  'hr.payroll.inputs_reopened': HrPayrollInputsPayload;
  'fleet.delivery.discrepancy_decided': {
    organizationId: string; discrepancyId: string; code: string; loadId: string; deliveryOrderId: string;
    status: 'approved' | 'rejected'; differenceQty: string; creditNoteId: string | null;
  };
}

/** A fleet trip (load) of a delivery order (fleet 1.3). */
/** Payroll inputs of a run (fleet 1.9 hr wiring): each names the source document that queued it. */
export interface HrPayrollInputsPayload {
  organizationId: string;
  runId: string;
  runNumber: string | null;
  inputs: Array<{ inputId: string; kind: 'earning' | 'deduction'; employeeId: string; amount: string; source: string;
    sourceId: string }>;
}

export interface FleetTripEventPayload {
  organizationId: string;
  loadId: string;
  code: string;
  deliveryOrderId: string;
  vehicleId: string | null;
  driverId: string | null;
  carrier: 'own' | 'transporter';
  'weighbridge.ticket.completed': WeighbridgeTicketCompletedPayload;
  'weighbridge.exception.raised': {
    organizationId: string; exceptionId: string; ticketId: string; ticketNumber: string;
    /** Decimal strings (kg / %). */
    varianceKg: string; variancePct: string | null; allowedVarianceKg: string;
  };
  'weighbridge.ticket.voided': {
    organizationId: string; ticketId: string; ticketNumber: string; reason: string; replacedById: string | null;
    deliveryOrderId: string | null; tripId: string | null;
  };
}

/** A completed weighbridge ticket (fleet 1.4): net weight in kg and in the document unit. */
export interface WeighbridgeTicketCompletedPayload {
  organizationId: string;
  ticketId: string;
  ticketNumber: string;
  subjectType: 'sale_delivery' | 'purchase_receipt' | 'internal_haulage' | 'other';
  direction: 'outbound' | 'inbound' | 'internal';
  stationId: string;
  vehicleId: string | null;
  registrationPlate: string;
  partnerId: string | null;
  productId: string | null;
  salesOrderId: string | null;
  deliveryOrderId: string | null;
  tripId: string | null;
  purchaseOrderId: string | null;
  /** Decimal strings. */
  grossKg: string;
  tareKg: string;
  netKg: string;
  netQty: string | null;
  uomId: string | null;
  withinTolerance: boolean;
  manual: boolean;
  reissuedFromId: string | null;
  goodsReceiptId: string | null;
}

/** A traffic fine charged to a driver: payroll deducts `amount` from employee `employeeId` (hr wiring). */
export interface FleetFineDeductionPayload {
  organizationId: string;
  deductionId: string;
  fineId: string;
  fineNumber: string;
  employeeId: string;
  /** Decimal string, e.g. "150000.00". */
  amount: string;
  vehicleId: string;
  offence: string;
  offenceAt: string;
}

/** A vehicle moved between active / in_workshop / breakdown / idle / disposed. */
export interface FleetVehicleStatusChangedPayload {
  organizationId: string;
  vehicleId: string;
  fromStatus: string | null;
  toStatus: string;
  reason: string | null;
  /** manual, registration, or the sub-module that moved it (maintenance, inspection, ...). */
  source: string;
  sourceId: string | null;
}

/** An approved meter correction (adjust a reading / meter replacement) was applied. */
export interface FleetMeterCorrectedPayload {
  organizationId: string;
  vehicleId: string;
  correctionId: string;
  meterType: 'odometer' | 'hours';
  kind: 'adjust' | 'replace';
  previousValue: string | null;
  correctedValue: string;
}

/** A driver checked a vehicle out (or back in: then with the distance / hours used since check-out). */
export interface FleetCheckoutEventPayload {
  organizationId: string;
  checkoutId: string;
  vehicleId: string;
  driverId: string;
  checkedOutAt?: string;
  checkedInAt?: string;
  distanceKm?: string | null;
  hoursUsed?: string | null;
}

/** A driver allowance / trip-expense claim was paid through expenses or exported to payroll. */
export interface FleetClaimSettledPayload {
  organizationId: string;
  claimId: string;
  code: string;
  driverId: string;
  vehicleId: string | null;
  settlement: 'expenses' | 'payroll';
  amount: string;
  currencyCode: string;
  journalEntryId: string | null;
}

/** A message was created (outbound) or received (inbound). */
export interface CommunicationMessageEventPayload {
  organizationId: string;
  messageId: string;
  conversationId: string;
  /** Inbound provider id, or 'internal' for staff-authored. */
  providerId: string;
  direction: 'inbound' | 'outbound';
  [key: string]: unknown;
}

/** A per-recipient delivery reached a terminal transport outcome. */
export interface CommunicationDeliveryEventPayload {
  organizationId: string;
  messageId: string;
  deliveryId: string;
  providerId: string;
  status: string;
  [key: string]: unknown;
}

/** A communication channel changed connection status. */
export interface CommunicationChannelEventPayload {
  organizationId: string;
  channelId: string;
  providerId: string;
  status: string;
  [key: string]: unknown;
}

/**
 * Emitted by `WorkflowService.transition` for a declared order transition.
 * `fromState`/`toState` make the fact self-describing without re-reading the row.
 */
export interface OrderLifecycleEventPayload {
  organizationId: string;
  orderId: string;
  fromState: string;
  toState: string;
  /** The workflow action that caused the transition. */
  action: string;
  [key: string]: unknown;
}

/**
 * Emitted by the DMS engine (Phase 2) for every document lifecycle action.
 * `fromStatus`/`toStatus` make the transition self-describing; `version` is the
 * document's post-action optimistic-lock value. `reason` is present on
 * cancel/reverse/terminate/revoke (guarded by requiresReason).
 */
export interface DocumentLifecyclePayload {
  organizationId: string;
  documentId: string;
  documentNumber?: string;
  documentTypeId: string;
  documentTypeCode: string;
  /** The engine action that produced this fact (submit, post, reverse, …). */
  action: string;
  fromStatus: string;
  toStatus?: string;
  actorId?: string;
  /** Post-action optimistic-lock version. */
  version: number;
  reason?: string;
  /** Present when the action created a reversal counterpart (credit/debit note). */
  counterpartId?: string;
  [key: string]: unknown;
}

/**
 * Emitted by a fulfillment document when it starts or finishes its work.
 * `strategy` is the module-declared code (`kitchen`, `delivery`, `rental`, …)
 * and `documentType`/`documentId` point at the concrete row, so a subscriber
 * can react generically or drill into the source.
 */
export interface FulfillmentEventPayload {
  organizationId: string;
  orderId: string;
  strategy: string;
  documentType: string;
  documentId: string;
  [key: string]: unknown;
}

export type DomainEventName = keyof DomainEventMap;

/**
 * Which entity a business event is ABOUT, so `DomainEventLog` can be indexed by
 * subject and queried as history (`@@index([organizationId, entityType, entityId])`).
 *
 * Only events listed here produce a ledger row. Everything else still reaches
 * the outbox for delivery — the outbox is transport, this map is what makes an
 * event part of the permanent record.
 */
export const EVENT_SUBJECT: Partial<Record<DomainEventName, { entityType: string; idField: string }>> = {
  'supplier.status_changed': { entityType: 'supplier', idField: 'partnerId' },
  'supplier.bank_account_added': { entityType: 'supplier', idField: 'partnerId' },
  'supplier.bank_account_verified': { entityType: 'supplier', idField: 'partnerId' },
  'order.confirmed': { entityType: 'order', idField: 'orderId' },
  'order.fulfillment_started': { entityType: 'order', idField: 'orderId' },
  'order.completed': { entityType: 'order', idField: 'orderId' },
  'order.closed': { entityType: 'order', idField: 'orderId' },
  'order.cancelled': { entityType: 'order', idField: 'orderId' },
  'order.reopened': { entityType: 'order', idField: 'orderId' },
  'order.superseded': { entityType: 'order', idField: 'orderId' },
  // Fulfillment facts are filed against the ORDER, not the fulfillment document:
  // the milestone question is always "what happened to this order?", and the
  // concrete document is still recoverable from the payload.
  'fulfillment.started': { entityType: 'order', idField: 'orderId' },
  'fulfillment.completed': { entityType: 'order', idField: 'orderId' },
  // DMS — lifecycle facts are filed against the document itself, so the log is
  // queryable as per-document history.
  'document.created': { entityType: 'document', idField: 'documentId' },
  'document.submitted': { entityType: 'document', idField: 'documentId' },
  'document.approved': { entityType: 'document', idField: 'documentId' },
  'document.rejected': { entityType: 'document', idField: 'documentId' },
  'document.confirmed': { entityType: 'document', idField: 'documentId' },
  'document.issued': { entityType: 'document', idField: 'documentId' },
  'document.activated': { entityType: 'document', idField: 'documentId' },
  'document.expired': { entityType: 'document', idField: 'documentId' },
  'document.revoked': { entityType: 'document', idField: 'documentId' },
  'document.posted': { entityType: 'document', idField: 'documentId' },
  'document.paid': { entityType: 'document', idField: 'documentId' },
  'document.closed': { entityType: 'document', idField: 'documentId' },
  'document.cancelled': { entityType: 'document', idField: 'documentId' },
  'document.reversed': { entityType: 'document', idField: 'documentId' },
  'document.reissued': { entityType: 'document', idField: 'documentId' },
  'document.terminated': { entityType: 'document', idField: 'documentId' },
  'document.archived': { entityType: 'document', idField: 'documentId' },
  'document.amended': { entityType: 'document', idField: 'documentId' },
  // Communication — message facts are filed against the message so a
  // conversation's ledger history is queryable per-message.
  'communication.message.created': { entityType: 'communication_message', idField: 'messageId' },
  'communication.message.received': { entityType: 'communication_message', idField: 'messageId' },
  'communication.message.delivered': { entityType: 'communication_message', idField: 'messageId' },
  'communication.message.failed': { entityType: 'communication_message', idField: 'messageId' },
  };
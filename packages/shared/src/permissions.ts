/**
 * RBAC permission catalog. Format: `resource:action` (ADR Phase 0 auth).
 * Shared so the API guards and the Web permission-aware menus agree.
 */

export const PERMISSIONS = {
  organization: {
    create: 'organization:create',
    read: 'organization:read',
    update: 'organization:update',
    delete: 'organization:delete',
  },
  user: {
    create: 'user:create',
    read: 'user:read',
    update: 'user:update',
    delete: 'user:delete',
  },
  role: {
    create: 'role:create',
    read: 'role:read',
    update: 'role:update',
    delete: 'role:delete',
  },
  partner: {
    create: 'partner:create',
    read: 'partner:read',
    update: 'partner:update',
    delete: 'partner:delete',
  },
  partnerCategory: {
    create: 'partner_category:create',
    read: 'partner_category:read',
    update: 'partner_category:update',
    delete: 'partner_category:delete',
  },
  product: {
    create: 'product:create',
    read: 'product:read',
    update: 'product:update',
    delete: 'product:delete',
  },
  productCategory: {
    create: 'product_category:create',
    read: 'product_category:read',
    update: 'product_category:update',
    delete: 'product_category:delete',
  },
  uom: {
    create: 'uom:create',
    read: 'uom:read',
    update: 'uom:update',
    delete: 'uom:delete',
  },
  uomCategory: {
    create: 'uom_category:create',
    read: 'uom_category:read',
    update: 'uom_category:update',
    delete: 'uom_category:delete',
  },
  tax: {
    create: 'tax:create',
    read: 'tax:read',
    update: 'tax:update',
    delete: 'tax:delete',
  },
  currency: {
    create: 'currency:create',
    read: 'currency:read',
    update: 'currency:update',
    delete: 'currency:delete',
  },
  fiscalPeriod: {
    create: 'fiscal_period:create',
    read: 'fiscal_period:read',
    update: 'fiscal_period:update',
    delete: 'fiscal_period:delete',
    // N-2: reopen is a distinct control from ordinary period updates — it
    // reverses a closing journal and reopens posted books.
    reopen: 'fiscal_period:reopen',
  },
  setting: {
    read: 'setting:read',
    update: 'setting:update',
  },
  auditLog: {
    read: 'audit_log:read',
  },
  costCenter: {
    create: 'cost_center:create',
    read: 'cost_center:read',
    update: 'cost_center:update',
    delete: 'cost_center:delete',
    allocate: 'cost_center:allocate',
  },
  // ---- ADR-S007 — landed costing, costing policy, cost centres ----
  costingPolicy: {
    read: 'costing_policy:read',
    manage: 'costing_policy:manage',
    approve: 'costing_policy:approve',
  },
  // ---- ADR-S008 — standard & process costing ----
  standardCost: {
    read: 'standard_cost:read',
    manage: 'standard_cost:manage',
    approve: 'standard_cost:approve',
  },
  processCosting: {
    read: 'process_costing:read',
    manage: 'process_costing:manage',
    close: 'process_costing:close',
  },
  // ---- ADR-PROCUREMENT-001 — Source-to-Pay ----
  supplier: {
    read: 'supplier:read',
    manage: 'supplier:manage',
    approve: 'supplier:approve',
    verifyBank: 'supplier:verify_bank',
  },
  procurementPolicy: {
    read: 'procurement_policy:read',
    manage: 'procurement_policy:manage',
    approve: 'procurement_policy:approve',
  },
  rfq: {
    read: 'rfq:read',
    manage: 'rfq:manage',
    award: 'rfq:award',
  },
  purchaseAgreement: {
    read: 'purchase_agreement:read',
    manage: 'purchase_agreement:manage',
    approve: 'purchase_agreement:approve',
  },
  budgetControl: {
    override: 'budget:override',
    commitments: 'commitment:read',
  },
  poChange: {
    request: 'po_change:request',
    approve: 'po_change:approve',
  },
  supplierClaim: {
    read: 'supplier_claim:read',
    manage: 'supplier_claim:manage',
  },
  inspection: {
    read: 'inspection:read',
    manage: 'inspection:manage',
  },
  emergencyPurchase: {
    review: 'emergency_purchase:review',
  },
  receiving: {
    overrideEvidence: 'receiving:override_evidence',
  },
  replenishment: {
    read: 'replenishment:read',
    manage: 'replenishment:manage',
  },
  procurementAnalytics: {
    read: 'procurement_analytics:read',
  },
  supplierPortal: {
    manage: 'supplier_portal:manage',
  },
  landedCost: {
    read: 'landed_cost:read',
    create: 'landed_cost:create',
    approve: 'landed_cost:approve',
    post: 'landed_cost:post',
    reverse: 'landed_cost:reverse',
    configure: 'landed_cost:configure',
  },
  // ---- ADR-S005 — accounting completion ----
  budget: {
    read: 'budget:read',
    create: 'budget:create',
    update: 'budget:update',
    delete: 'budget:delete',
    approve: 'budget:approve',
  },
  openingBalance: {
    read: 'opening_balance:read',
    post: 'opening_balance:post',
  },

  // ---- Accounting (Phase 2) ----
  account: {
    create: 'account:create',
    read: 'account:read',
    update: 'account:update',
    delete: 'account:delete',
  },
  /** Account categories define accounting *behavior* (normal balance, statement
   *  section, which module can auto-discover the account). They are global rows
   *  shared by every tenant and written only by the boot seeder, so there is no
   *  create / update / delete permission. */
  accountCategory: {
    create: 'account_category:create',
    read: 'account_category:read',
    update: 'account_category:update',
    delete: 'account_category:delete',
  },
  journal: {
    create: 'journal:create',
    read: 'journal:read',
    update: 'journal:update',
    delete: 'journal:delete',
  },
  journalEntry: {
    read: 'journal_entry:read',
    create: 'journal_entry:create',
    post: 'journal_entry:post',
    reverse: 'journal_entry:reverse',
  },
  accountMapping: {
    read: 'account_mapping:read',
    update: 'account_mapping:update',
  },
  bankAccount: {
    create: 'bank_account:create',
    read: 'bank_account:read',
    update: 'bank_account:update',
    delete: 'bank_account:delete',
  },
  treasury: {
    read: 'treasury:read',
    transfer: 'treasury:transfer',
  },

  // ---- Invoicing / AR (Phase 3) ----
  invoice: {
    read: 'invoice:read',
    create: 'invoice:create',
    update: 'invoice:update',
    post: 'invoice:post',
    cancel: 'invoice:cancel',
    delete: 'invoice:delete',
    send: 'invoice:send',
  },
  commission: {
    read: 'commission:read',
    manage: 'commission:manage',
    approve: 'commission:approve',
  },
  priceList: {
    read: 'price_list:read',
    manage: 'price_list:manage',
  },
  creditControl: {
    read: 'credit_control:read',
    manage: 'credit_control:manage',
    override: 'credit_control:override',
  },
  creditNote: {
    read: 'credit_note:read',
    create: 'credit_note:create',
    post: 'credit_note:post',
  },
  expense: {
    read: 'expense:read',
    create: 'expense:create',
    update: 'expense:update',
    post: 'expense:post',
    cancel: 'expense:cancel',
    approve: 'expense:approve',
  },
  payment: {
    read: 'payment:read',
    create: 'payment:create',
    allocate: 'payment:allocate',
    void: 'payment:void',
  },
  report: {
    accounting: 'report:accounting',
    ar: 'report:ar',
  },

  // ---- Inventory (Phase 4) ----
  inventoryLocation: {
    create: 'inventory_location:create',
    read: 'inventory_location:read',
    update: 'inventory_location:update',
    delete: 'inventory_location:delete',
  },
  /// Scheduled alerts and exception reports (Phase 5, ADR-S021). Holding `alerts:read` also puts a user on the default
  /// recipient list; `alerts:manage` sets thresholds, channels and recipients per alert type.
  alerts: {
    read: 'alerts:read',
    manage: 'alerts:manage',
  },
  /// Read-only reporting datasets as CSV (Phase 5 integrations).
  bi: {
    export: 'bi:export',
  },
  inventory: {
    read: 'inventory:read',
    move: 'inventory:move',
    /// Replenishment rules, alerts and raising requisitions from them (INV-3).
    replenish: 'inventory:replenish',
  },
  // ---- F.8 — Stock document wrappers + masters ----
  inventoryDoc: {
    create: 'inventory_doc:create',
    read: 'inventory_doc:read',
    update: 'inventory_doc:update',
    approve: 'inventory_doc:approve',
    delete: 'inventory_doc:delete',
  },
  // ---- Inventory count sessions (opening/closing physical counts) ----
  inventoryCount: {
    read: 'inventory_count:read',
    count: 'inventory_count:count',
    submit: 'inventory_count:submit',
  },
  // ---- Manufacturing — BOM / recipe master ----
  bom: {
    read: 'bom:read',
    create: 'bom:create',
    update: 'bom:update',
    activate: 'bom:activate',
    delete: 'bom:delete',
  },
  // ---- Manufacturing — production orders ----
  productionOrder: {
    read: 'production_order:read',
    create: 'production_order:create',
    start: 'production_order:start',
    complete: 'production_order:complete',
    cancel: 'production_order:cancel',
    approve: 'production_order:approve',
    qc: 'production_order:qc',
  },
  // ---- Manufacturing — demand requests (Phase 2) ----
  productionRequest: {
    read: 'production_request:read',
    create: 'production_request:create',
    submit: 'production_request:submit',
    approve: 'production_request:approve',
    reject: 'production_request:reject',
  },
  // ---- Manufacturing — planning (Phase 2) ----
  productionPlan: {
    read: 'production_plan:read',
    create: 'production_plan:create',
    update: 'production_plan:update',
    confirm: 'production_plan:confirm',
  },
  // ---- Manufacturing — reports/analytics ----
  production: {
    report: 'production:report',
  },
  // ---- Manufacturing Phase 4 — work centres, resources, routing, work orders ----
  workCenter: {
    read: 'work_center:read',
    create: 'work_center:create',
    update: 'work_center:update',
    delete: 'work_center:delete',
  },
  resource: {
    read: 'resource:read',
    create: 'resource:create',
    update: 'resource:update',
    delete: 'resource:delete',
  },
  routing: {
    read: 'routing:read',
    create: 'routing:create',
    delete: 'routing:delete',
  },
  // ---- Crushing, screening & aggregate processing (manufacturing 2.3, ADR-S016) ----
  // Route ↔ permission matrix: docs/spec/manufacturing.md §2.3. Reports use production:report.
  crushing: {
    /// Plants / circuits, grading profiles, runs, stockpiles and surveys (and the crushing form lookups).
    read: 'crushing:read',
    /// Plant & circuit set-up (crushers, screens, conveyors as work centres) and grading profiles.
    setup: 'crushing:setup',
  },
  crushingRun: {
    /// Record / edit / delete draft crushing runs (plant-operator entry).
    record: 'crushing_run:record',
    /// Post a run (feed out of the ROM stockpile, sized products into stockpiles at cost, GL) and reverse it.
    post: 'crushing_run:post',
  },
  crushingSurvey: {
    /// Record and submit stockpile surveys (volume × density = tonnes).
    record: 'crushing_survey:record',
    /// Approve (posts the stock adjustment + GL), reject or reverse a survey — never the person who submitted it.
    approve: 'crushing_survey:approve',
  },
  workOrder: {
    read: 'work_order:read',
    manage: 'work_order:manage',
  },
  // ---- Manufacturing 2.7 — shop-floor execution & data capture (ADR-S015) ----
  shopFloor: {
    /// Approve / reject submitted shift reports (supervisor).
    approve: 'shop_floor:approve',
    /// Feed machine data (poll adapters, push from a gateway / MQTT bridge, CSV import) — device accounts.
    machineData: 'shop_floor:machine_data',
  },
  // ---- Configurable Inventory Posting Rules (M3) ----
  inventoryPostingRule: {
    read: 'inventory_posting_rule:read',
    update: 'inventory_posting_rule:update',
    create: 'inventory_posting_rule:create',
    delete: 'inventory_posting_rule:delete',
  },

  // ---- Beverage Control — digital-weight alcohol measurement (bar) ----
  beverage: {
    read: 'beverage:read',
    setup: 'beverage:setup',
    count: 'beverage:count',
    approve: 'beverage:approve',
  },

  // ---- Rental Management — hire-out of serialized/pooled assets ----
  rental: {
    read: 'rental:read',
    reserve: 'rental:reserve',
    manage: 'rental:manage',
    sign: 'rental:sign',
    checkout: 'rental:checkout',
    return: 'rental:return',
    settle: 'rental:settle',
    inspect: 'rental:inspect',
    waive: 'rental:waive',
    extend: 'rental:extend',
    swap: 'rental:swap',
    refundDeposit: 'rental:refund_deposit',
    service: 'rental:service',
    report: 'rental:report',
    overrideScore: 'rental:override_score',
  },

  // ---- Repair & Maintenance Management (RMMS) — item repair, maintenance,
  // field service, contract maintenance, preventive maintenance ----
  repair: {
    read: 'repair:read',
    create: 'repair:create',
    manage: 'repair:manage',
    diagnose: 'repair:diagnose',
    quote: 'repair:quote',
    approve: 'repair:approve',
    assign: 'repair:assign',
    work: 'repair:work',
    test: 'repair:test',
    deliver: 'repair:deliver',
    close: 'repair:close',
    parts: 'repair:parts',
    warranty: 'repair:warranty',
    contract: 'repair:contract',
    schedule: 'repair:schedule',
    report: 'repair:report',
  },

  // ---- M5: Cash registers / sessions (foundation) ----
  cashRegister: {
    create: 'cash_register:create',
    read: 'cash_register:read',
    update: 'cash_register:update',
    delete: 'cash_register:delete',
  },
  cashSession: {
    open: 'cash_session:open',
    read: 'cash_session:read',
    close: 'cash_session:close',
    reconcile: 'cash_session:reconcile',
    // H3: taking cash OUT of the drawer (petty cash / bank drop) needs its own
    // grant — an opener should not be able to remove cash unsupervised.
    cashOut: 'cash_session:cash_out',
    // C3: approving a shift variance (segregation of duties — not the cashier).
    approveVariance: 'cash_session:approve_variance',
    // Closed shifts are never reopened. A manager (not the cashier) may close an
    // abandoned shift with a blind count, and post linked corrections to a
    // closed shift from a current one.
    forceClose: 'cash_session:force_close',
    correct: 'cash_session:correct',
  },
  // ---- Phase F: Branch permissions (also includes the F.5 vertical module perms)
  branch: {
    create: 'branch:create',
    read: 'branch:read',
    update: 'branch:update',
    delete: 'branch:delete',
    // Allows an admin to see / switch the user's home branch.
    assignUser: 'branch:assign_user',
  },
  // ---- POS Tables Management (ADR-012 / Phase T1) ----
  tables: {
    view: 'tables:view',
    create: 'tables:create',
    edit: 'tables:edit',
    delete: 'tables:delete',
    transfer: 'tables:transfer',
    merge: 'tables:merge',
    split: 'tables:split',
    clean: 'tables:clean',
    reserve: 'tables:reserve',
    // Manage the configurable zone (category) catalog — create/edit/archive/restore.
    zones: 'tables:zones',
  },
  // ---- Phase F.5: Vertical permissions (consumed by the verticals/* modules) ----
  pos: {
    read: 'pos:read',
    checkout: 'pos:checkout',
    refund: 'pos:refund',
    openSession: 'pos:open_session',
    closeSession: 'pos:close_session',
    hold: 'pos:hold',                 // park/recall a sale
    discount: 'pos:discount',         // apply > 0% discount without override
    priceOverride: 'pos:price_override', // set a line price away from the catalogue
    void: 'pos:void',                 // void a line or a sale
    // Audit#2 N-05: hand goods over on a house account. A different decision
    // from taking money, so it is a different right — it used to ride on
    // pos:checkout while credit control itself failed open at limit 0.
    credit: 'pos:credit',
    writeOff: 'pos:write_off',        // write off an uncollectable POS invoice
    override: 'pos:override',         // approve a manager override (PIN)
    reports: 'pos:reports',           // X/Z + sales analytics
    deleteItem: 'pos:delete_item',    // remove an order item from the cart
    kds: 'pos:kds',                   // Kitchen Display: view board + advance tickets
  },
  school: {
    read: 'school:read',
    enroll: 'school:enroll',
    issueTermFees: 'school:issue_term_fees',
    recordPayment: 'school:record_payment',
    manageSchedule: 'school:manage_schedule',
  },
  notifications: {
    read: 'notifications:read',
    write: 'notifications:write',
  },
  procurement: {
    purchaseRequest: {
      create: 'purchase_request:create',
      read: 'purchase_request:read',
      update: 'purchase_request:update',
      delete: 'purchase_request:delete',
      approve: 'purchase_request:approve',
      submit: 'purchase_request:submit',
    },
    /// Procurement budgets and what is committed against them (PROC-3).
    budget: {
      read: 'procurement_budget:read',
      manage: 'procurement_budget:manage',
    },
    /// Who may buy for a branch (PROC-3). `central` is the central buying team, which no branch rule restricts.
    delegation: {
      read: 'procurement_delegation:read',
      manage: 'procurement_delegation:manage',
      central: 'procurement_delegation:central',
    },
    purchaseOrder: {
      create: 'purchase_order:create',
      read: 'purchase_order:read',
      update: 'purchase_order:update',
      delete: 'purchase_order:delete',
      approve: 'purchase_order:approve',
      send: 'purchase_order:send',
      cancel: 'purchase_order:cancel',
      receive: 'purchase_order:receive',
      pay: 'purchase_order:pay',
    },
    goodsReceipt: {
      create: 'goods_receipt:create',
      read: 'goods_receipt:read',
      post: 'goods_receipt:post',
      cancel: 'goods_receipt:cancel',
    },
    threeWayMatch: {
      read: 'three_way_match:read',
      approve: 'three_way_match:approve',
      override: 'three_way_match:override',
    },
  },
  debitNote: {
    create: 'debit_note:create',
    read: 'debit_note:read',
    post: 'debit_note:post',
    cancel: 'debit_note:cancel',
    approve: 'debit_note:approve',
  },
  files: {
    read: 'files:read',
    write: 'files:write',
    delete: 'files:delete',
  },
  webhooks: {
    read: 'webhooks:read',
    write: 'webhooks:write',
    delete: 'webhooks:delete',
  },
  approvals: {
    read: 'approvals:read',
    decide: 'approvals:decide',
    manage: 'approvals:manage',
  },
  /// Document number formats (CTRL-2).
  documentNumbering: {
    read: 'document_numbering:read',
    manage: 'document_numbering:manage',
  },
  // ---- Generic Orders (back-office Order CRUD, POS-independent) ----
    orders: {
      read: 'orders:read',
      create: 'orders:create',
      update: 'orders:update',
      cancel: 'orders:cancel',
      invoice: 'orders:invoice',
    },
    // ---- Frontend-facing management permissions (dot-notation) ----
    partners: {
    view: 'partners.view',
    create: 'partners.create',
    edit: 'partners.edit',
    delete: 'partners.delete',
  },
  products: {
    view: 'products.view',
    create: 'products.create',
    edit: 'products.edit',
    delete: 'products.delete',
  },
  menu: {
    view: 'menu.view',
    create: 'menu.create',
    edit: 'menu.edit',
    delete: 'menu.delete',
  },
  menuCategories: {
    view: 'menu_categories.view',
    create: 'menu_categories.create',
    edit: 'menu_categories.edit',
    delete: 'menu_categories.delete',
  },
  backup: {
    read: 'backup:read',
    update: 'backup:update',
    run: 'backup:run',
  },
  fixedAsset: {
    create: 'fixed_asset:create',
    read: 'fixed_asset:read',
    update: 'fixed_asset:update',
    delete: 'fixed_asset:delete',
    dispose: 'fixed_asset:dispose',
    transfer: 'fixed_asset:transfer',
    approveTransfer: 'fixed_asset:approve_transfer',
  },
  assetCategory: {
    create: 'asset_category:create',
    read: 'asset_category:read',
    update: 'asset_category:update',
    delete: 'asset_category:delete',
  },
  assetDepreciation: {
    read: 'asset_depreciation:read',
    run: 'asset_depreciation:run',
    approve: 'asset_depreciation:approve',
  },
  assetReport: {
    read: 'asset_report:read',
  },
  // NB: a top-level `organization` block already exists above (organization:*)
  // for the kernel-level organization entity; we do NOT redeclare it here.
  task: {
    create: 'task:create',
    read: 'task:read',
    update: 'task:update',
    delete: 'task:delete',
    assign: 'task:assign',
    verify: 'task:verify',
    reorder: 'task:reorder',
    manageLabels: 'task:manage_labels',
    manageTemplates: 'task:manage_templates',
    manageAutoRules: 'task:manage_auto_rules',
  },
  // ---- CRM (Phase A) — sales pipeline, activity timeline, analytics ----
  crm: {
    dashboardRead: 'crm:dashboard:read',
    dealRead: 'crm:deal:read',
    dealWrite: 'crm:deal:write',
    activityRead: 'crm:activity:read',
    activityWrite: 'crm:activity:write',
    // CRM sales suite (ADR-S010)
    /// See every salesperson's leads / opportunities (without it: own + unassigned only).
    dealViewAll: 'crm:deal:view_all',
    leadRead: 'crm:lead:read',
    leadWrite: 'crm:lead:write',
    quotationRead: 'crm:quotation:read',
    quotationWrite: 'crm:quotation:write',
    quotationApprove: 'crm:quotation:approve',
    quotationConvert: 'crm:quotation:convert',
    pricingRead: 'crm:pricing:read',
    pricingManage: 'crm:pricing:manage',
    reportRead: 'crm:report:read',
    configure: 'crm:configure',
    /// Customer complaints and service requests (CRM-1).
    caseRead: 'crm:case:read',
    caseWrite: 'crm:case:write',
  },
  // ---- Sales / order-to-cash (ADR-S011) ----
  sales: {
    quotationRead: 'sales:quotation:read',
    quotationWrite: 'sales:quotation:write',
    quotationApprove: 'sales:quotation:approve',
    quotationSend: 'sales:quotation:send',
    quotationConvert: 'sales:quotation:convert',
    orderRead: 'sales:order:read',
    /// See every salesperson's quotations / orders (without it: own, unassigned and led team).
    orderReadAll: 'sales:order:read_all',
    orderWrite: 'sales:order:write',
    orderConfirm: 'sales:order:confirm',
    orderCancel: 'sales:order:cancel',
    orderCloseShort: 'sales:order:close_short',
    orderHoldRelease: 'sales:order:hold_release',
    /// Request a change to a confirmed order.
    orderAmend: 'sales:order:amend',
    /// Approve / apply a change request.
    orderAmendApprove: 'sales:order:amend_approve',
    pricingRead: 'sales:pricing:read',
    pricingManage: 'sales:pricing:manage',
    /// Override the billable quantity decided by the billing basis.
    billingOverride: 'sales:billing:override',
    /// Override fulfilment / recognition policies on an order.
    policyOverride: 'sales:policy:override',
    reportRead: 'sales:report:read',
    configure: 'sales:configure',
    /// Raise invoices / down-payment invoices from sales orders.
    invoiceCreate: 'sales:invoice:create',
    /// Record customer deposits (advances) and apply them to invoices.
    depositRecord: 'sales:deposit:record',
    depositRefund: 'sales:deposit:refund',
    /// Returns (RMA) and customer complaints (ADR-S011 S7).
    returnRead: 'sales:return:read',
    returnWrite: 'sales:return:write',
    /// Approve a return and settle it (credit note, refund, replacement).
    returnApprove: 'sales:return:approve',
    complaintRead: 'sales:complaint:read',
    complaintWrite: 'sales:complaint:write',
    /// Collections worklist, dunning runs, promises to pay (ADR-S011 S8).
    collectionsRead: 'sales:collections:read',
    collectionsManage: 'sales:collections:manage',
    /// Blanket / contract agreements (licence salesAgreements, ADR-S011 S9).
    agreementRead: 'sales:agreement:read',
    agreementManage: 'sales:agreement:manage',
    /// Rebate programmes, accrual runs and settlement credit notes.
    rebateManage: 'sales:rebate:manage',
    commissionRead: 'sales:commission:read',
    /// Commission rules, statements and their approval into payroll.
    commissionManage: 'sales:commission:manage',
    targetManage: 'sales:target:manage',
    /// Issue / revoke customer portal links (licence customerPortal, ADR-S011 S10).
    portalManage: 'sales:portal:manage',
  },
  // ---- Credit management (ADR-S011 §3) ----
  credit: {
    read: 'credit:read',
    /// Limits, policy, customer hard hold.
    manage: 'credit:manage',
    /// Release a document held by a credit check.
    override: 'credit:override',
  },
  document: {
    create: 'document:create',
    read: 'document:read',
    update: 'document:update',
    delete: 'document:delete',
    print: 'document:print',
    archive: 'document:archive',
    manage: 'document:manage',
  },
  documentType: {
    create: 'doc:type:create',
    read: 'doc:type:read',
    update: 'doc:type:update',
    delete: 'doc:type:delete',
    manage: 'doc:type:manage',
  },
  // ---- Workforce Management (HR) — employees, attendance, timesheets,
  //      leave, payroll, payslips, advances, loans, tax tables, reviews ----
  hr: {
    /// Employee directory + HR dashboard. Deliberately does NOT carry pay,
    /// bank or national-ID data — that needs `hr:compensation`.
    read: 'hr:read',
    employee: 'hr:employee',
    /// Read AND write of compensation-grade fields: baseSalary, hourlyRate,
    /// bank*, mobileMoney*, taxNumber, pensionNumber, socialSecurityNumber,
    /// dateOfBirth. Separated from `hr:read` so an HR directory user cannot
    /// see everyone's salary and bank account.
    compensation: 'hr:compensation',
    /// Control of the Employee<->User identity spine: link, unlink, provision
    /// an account, change roles/status, terminate, suspend, transfer.
    access: 'hr:access',
    attendance: 'hr:attendance',
    shift: 'hr:shift',
    timesheet: 'hr:timesheet',
    leave: 'hr:leave',
    holiday: 'hr:holiday',
    payroll: 'hr:payroll',
    payslip: 'hr:payslip',
    advance: 'hr:advance',
    loan: 'hr:loan',
    taxTable: 'hr:tax_table',
    performance: 'hr:performance',
    /// Employee paperwork — contracts, IDs, permits. Separate from `hr:read`
    /// because a directory user has no business reading someone's contract.
    document: 'hr:document',
    training: 'hr:training',
    report: 'hr:report',
    /// Job openings, applicants, interviews and onboarding checklists (HR-1).
    recruitment: 'hr:recruitment',
    /// Employment contract records and renewals (HR-2).
    contract: 'hr:contract',
  },
  // ---- Communication platform — messaging + channels ----
  // NB: read/write/send are CAPABILITIES. Per-conversation ACCESS is enforced
  // separately by ConversationAccessService (participant / visibility / role).
  // readAll grants cross-conversation read (managers/support) but never opens a
  // `private` direct conversation.
  communication: {
    conversationRead: 'communication:conversation:read',
    conversationWrite: 'communication:conversation:write',
    conversationReadAll: 'communication:conversation:read_all',
    messageSend: 'communication:message:send',
    channelRead: 'communication:channel:read',
    channelManage: 'communication:channel:manage',
  },
  // ---- Fleet & logistics (module fleet, ADR-S009, construction-materials Track C Phase 1) ----
  // Every Phase 1 key (1.1–1.9) is declared here at once; each sub-module wires its own routes to them.
  // Route ↔ permission matrix: docs/spec/fleet.md §Permissions.
  fleet: {
    /// Fleet dashboards, cost / utilisation / delivery reports and their exports (1.9).
    report: 'fleet:report',
    /// Vehicle classes, fleet thresholds, telematics provider and allowance rules.
    settings: 'fleet:settings',
  },
  fleetVehicle: {
    read: 'fleet_vehicle:read',
    create: 'fleet_vehicle:create',
    update: 'fleet_vehicle:update',
    delete: 'fleet_vehicle:delete',
    /// Move a vehicle between active / in workshop / breakdown / idle / disposed.
    status: 'fleet_vehicle:status',
    /// Record odometer / hour-meter readings and request meter corrections.
    meter: 'fleet_vehicle:meter',
    /// Approver permission of the default meter-correction approval workflow (rollbacks).
    approveMeter: 'fleet_vehicle:approve_meter',
    /// Registration, insurance, road licence, inspection and operator-permit documents.
    compliance: 'fleet_vehicle:compliance',
  },
  fleetDriver: {
    read: 'fleet_driver:read',
    create: 'fleet_driver:create',
    update: 'fleet_driver:update',
    delete: 'fleet_driver:delete',
    /// Vehicle ↔ driver assignments.
    assign: 'fleet_driver:assign',
    /// Vehicle check-out / check-in (odometer, fuel level, condition, photos).
    checkout: 'fleet_driver:checkout',
    /// Record driver allowances and trip expenses.
    allowance: 'fleet_driver:allowance',
    approveAllowance: 'fleet_driver:approve_allowance',
  },
  fleetDispatch: {
    /// Delivery orders, customer sites and the dispatch board.
    read: 'fleet_dispatch:read',
    create: 'fleet_dispatch:create',
    update: 'fleet_dispatch:update',
    /// Split orders into loads and assign truck / driver / trailer / transporter.
    plan: 'fleet_dispatch:plan',
    cancel: 'fleet_dispatch:cancel',
    /// Approve delivered-vs-ordered discrepancies (separation of duties with the recorder).
    approveDiscrepancy: 'fleet_dispatch:approve_discrepancy',
  },
  fleetTrip: {
    read: 'fleet_trip:read',
    create: 'fleet_trip:create',
    update: 'fleet_trip:update',
    /// Loading → dispatched → in transit (driver / dispatcher actions).
    dispatch: 'fleet_trip:dispatch',
    /// Proof of delivery (received quantity, receiver, signature, GPS).
    deliver: 'fleet_trip:deliver',
    close: 'fleet_trip:close',
    cancel: 'fleet_trip:cancel',
  },
  fleetFuel: {
    read: 'fleet_fuel:read',
    create: 'fleet_fuel:create',
    update: 'fleet_fuel:update',
    /// Post / reverse fuel cost to the GL.
    post: 'fleet_fuel:post',
    /// Site fuel tanks / bowsers: dips, issues, reconciliation.
    tank: 'fleet_fuel:tank',
  },
  fleetMaintenance: {
    read: 'fleet_maintenance:read',
    create: 'fleet_maintenance:create',
    update: 'fleet_maintenance:update',
    /// Service schedules and class templates.
    schedule: 'fleet_maintenance:schedule',
    /// Complete work orders: issue parts, book labour, post costs.
    complete: 'fleet_maintenance:complete',
    /// Pre-trip inspections (DVIR) and defects.
    inspect: 'fleet_maintenance:inspect',
    /// Tyres: fitment, rotation, removal, tread readings.
    tyre: 'fleet_maintenance:tyre',
  },
  fleetIncident: {
    /// Incidents / accidents, damage, insurance claims and traffic fines.
    read: 'fleet_incident:read',
    create: 'fleet_incident:create',
    update: 'fleet_incident:update',
    /// Charge a fine to a driver (payroll deduction) or to the company.
    charge: 'fleet_incident:charge',
  },
  fleetFreight: {
    /// Transporter freight bills (fleet 1.9): list and detail.
    read: 'fleet_freight:read',
    /// Generate bills from closed transporter trips, match the supplier invoice (AP bill + accrual), cancel,
    /// capitalise inbound freight as landed cost.
    bill: 'fleet_freight:bill',
    /// Accept a supplier invoice outside the match tolerance.
    approveVariance: 'fleet_freight:approve_variance',
  },
  weighbridge: {
    read: 'weighbridge:read',
    /// Operator: register trucks, capture first / second weights, print tickets.
    weigh: 'weighbridge:weigh',
    /// Key in a weight instead of reading the indicator (always flagged on the ticket).
    manualEntry: 'weighbridge:manual_entry',
    /// Approve an out-of-tolerance ticket (never the operator's own).
    approveException: 'weighbridge:approve_exception',
    /// Void a printed ticket (re-issue with audit).
    void: 'weighbridge:void',
    /// Stations, indicator devices, tolerances, tare validity.
    configure: 'weighbridge:configure',
  },
  // ---- Quality management (module quality, ADR-S013, construction-materials Track C Phase 2.6) ----
  // Core-available (no module flag). Route ↔ permission matrix: docs/spec/quality.md §Permissions.
  quality: {
    /// Specifications, plans, lots, results, NCR / CAPA, traceability, reports, CoA print.
    read: 'quality:read',
    /// Specifications (versions, parameters, sieve envelopes) and inspection plans (sampling, hold policy).
    configure: 'quality:configure',
    /// Open lots, record test results, import laboratory results.
    inspect: 'quality:inspect',
    /// Put a lot on hold or move it into QC quarantine.
    hold: 'quality:hold',
    /// Release a lot (back to stock / next stage); a failed lot needs a concession reason.
    release: 'quality:release',
    /// Reject (scrap / return to supplier), downgrade to a second-grade product, cancel a lot.
    dispose: 'quality:dispose',
    /// Nonconformance reports.
    ncr: 'quality:ncr',
    /// Corrective / preventive actions.
    capa: 'quality:capa',
  },
  // ---- Quarry & extraction (module quarry, ADR-S012, construction-materials Track C Phase 2.2) ----
  // Route ↔ permission matrix: docs/spec/quarry.md §Routes. Every quarry role needs quarry_site:read (form lookups).
  quarry: {
    /// Extraction vs target, cost per tonne, losses and royalties reports.
    report: 'quarry:report',
    /// Cost-allocation rule, fallback rates, equipment rates, royalty / levy rates.
    settings: 'quarry:settings',
  },
  quarrySite: {
    /// Sites, zones / benches / faces, materials, stockpiles (and the quarry form lookups).
    read: 'quarry_site:read',
    create: 'quarry_site:create',
    update: 'quarry_site:update',
    delete: 'quarry_site:delete',
  },
  quarryExtraction: {
    /// Daily extraction logs, losses, haulage, plans and the ROM stockpile view.
    read: 'quarry_extraction:read',
    create: 'quarry_extraction:create',
    update: 'quarry_extraction:update',
    /// Post / reverse an extraction log: raw stone into ROM stock with its cost, GL capitalisation.
    post: 'quarry_extraction:post',
    /// Extraction plans and production targets.
    plan: 'quarry_extraction:plan',
    /// Record haulage from the face to the ROM stockpile / crusher.
    haul: 'quarry_extraction:haul',
  },
  quarryBlasting: {
    /// Drilling & blasting records, magazines, explosive registry and issues.
    read: 'quarry_blasting:read',
    create: 'quarry_blasting:create',
    /// Edit, fire, close or cancel a blast.
    update: 'quarry_blasting:update',
    /// Request explosives for a blast (goes to approval).
    requestExplosive: 'quarry_blasting:request_explosive',
    /// Approver permission of the default explosive-issue approval workflow (never the requester).
    approveExplosive: 'quarry_blasting:approve_explosive',
    /// Explosive issue authorisation: issue an approved request from the magazine and reconcile the unused return.
    issueExplosive: 'quarry_blasting:issue_explosive',
    /// Magazines (licence, keeper) and the controlled-explosive product registry.
    magazine: 'quarry_blasting:magazine',
  },
  // ---- Customer portal (PORTAL-1, ADR-S050) ----
  portal: {
    /// The only permission of a portal login: its own orders, deliveries, invoices and statement.
    customer: 'portal:customer',
    /// Staff: open, disable and reset customer portal accounts.
    manage: 'portal:manage',
  },
  // ---- Management dashboards (Track C Phase 5, ADR-S020) ----
  // Route ↔ permission matrix: docs/spec/analytics-kpis.md §Routes. A viewer holds analytics:view plus one per dashboard.
  analytics: {
    /// Open the analytics screens and their filter lists; shows nothing without a dashboard permission below.
    view: 'analytics:view',
    quarry: 'analytics:quarry',
    aggregates: 'analytics:aggregates',
    blocks: 'analytics:blocks',
    inventory: 'analytics:inventory',
    procurement: 'analytics:procurement',
    sales: 'analytics:sales',
    fleet: 'analytics:fleet',
    maintenance: 'analytics:maintenance',
    finance: 'analytics:finance',
    branch: 'analytics:branch',
  },
  // ---- Label printing (INT-3) ----
  label: {
    /// Print product, stockpile and weighbridge ticket labels (PDF or ZPL).
    print: 'label:print',
  },
} as const;

type PermissionLeaf<T> = T extends string ? T : T extends object ? PermissionLeaf<T[keyof T]> : never;
export type Permission = PermissionLeaf<typeof PERMISSIONS>;

/** Flattened list of every permission string (used for seeding the admin role). */
function flattenPermissions(input: unknown): string[] {
  if (typeof input === 'string') return [input];
  if (input && typeof input === 'object') {
    return Object.values(input as Record<string, unknown>).flatMap(flattenPermissions);
  }
  return [];
}
export const ALL_PERMISSIONS: string[] = flattenPermissions(PERMISSIONS);

/**
 * Operational manager role for cash management. Kept in one place so the seed
 * and the migration that provisions existing organizations stay identical.
 * Deliberately excludes system administration (users, roles, settings, COA).
 */
export const MANAGER_PERMISSIONS: readonly string[] = [
  'pos:read', 'pos:checkout', 'pos:hold', 'pos:discount', 'pos:void', 'pos:refund', 'pos:override',
  'pos:reports', 'pos:open_session', 'pos:close_session', 'pos:credit', 'pos:kds',
  'cash_session:open', 'cash_session:read', 'cash_session:close', 'cash_session:reconcile',
  'cash_session:cash_out', 'cash_session:approve_variance', 'cash_session:force_close', 'cash_session:correct',
  'cash_register:read',
  'treasury:read', 'treasury:transfer', 'account:read', 'bank_account:read',
  'payment:read', 'payment:create', 'payment:allocate', 'payment:void',
  'expense:read', 'expense:create', 'expense:update', 'expense:post', 'expense:cancel', 'expense:approve',
  'partner:read', 'partners.view', 'products.view', 'product:read', 'menu.view', 'user:read',
  'tables:view', 'tables:transfer', 'tables:merge', 'tables:split', 'tables:edit',
  'branch:read',
];

package com.inventoryerp.invoicing.application;

import java.util.Set;

/**
 * ADR-S094 §8.2: who owns a customer document. Every sales invoice, credit note and proforma is created through
 * {@link DocumentBuilderService#createDocument(String, SourceRef, DocumentBuilderService.HeaderInput, java.util.List)}
 * with one of these. Its {@link #kind()} is stored in {@code Document.sourceKind}; a database check requires it on
 * every sales invoice and credit note. {@code sourceType} / {@code sourceId} keep their existing values (parity), so
 * no response changes.
 *
 * <p>Each kind declares its owner module and the document types it may create. Only {@link Manual} depends on the
 * organization's {@code invoicing.manualCustomerInvoices} setting (§8.3).
 */
public sealed interface SourceRef {

    /** Stored in {@code Document.sourceKind}. */
    String kind();

    /** Module that owns documents of this kind. */
    String owner();

    /** Document types this kind may create. */
    Set<String> documentTypes();

    /** True when the organization's manual-invoice setting decides whether the document may be created. */
    default boolean manual() {
        return false;
    }

    Set<String> INVOICE = Set.of("sales_invoice");
    Set<String> CREDIT_NOTE = Set.of("credit_note");
    Set<String> PROFORMA = Set.of("proforma_invoice");

    /** Regular invoice (or proforma) of a Sale. */
    record Sale(String saleId) implements SourceRef {
        public Sale {
            require(saleId, "saleId");
        }

        @Override
        public String kind() {
            return "sale";
        }

        @Override
        public String owner() {
            return "sales";
        }

        @Override
        public Set<String> documentTypes() {
            return Set.of("sales_invoice", "proforma_invoice");
        }
    }

    /** Down-payment invoice of a Sale. */
    record SaleDownPayment(String saleId) implements SourceRef {
        public SaleDownPayment {
            require(saleId, "saleId");
        }

        @Override
        public String kind() {
            return "sale_down_payment";
        }

        @Override
        public String owner() {
            return "sales";
        }

        @Override
        public Set<String> documentTypes() {
            return INVOICE;
        }
    }

    /** Invoice of one milestone of a Sale (a down payment). */
    record SaleMilestone(String saleId, String milestoneId) implements SourceRef {
        public SaleMilestone {
            require(saleId, "saleId");
            require(milestoneId, "milestoneId");
        }

        @Override
        public String kind() {
            return "sale_milestone";
        }

        @Override
        public String owner() {
            return "sales";
        }

        @Override
        public Set<String> documentTypes() {
            return INVOICE;
        }
    }

    /** Invoice of a legacy branch cash sale ({@code SalesSubmission}, ADR-S056) until that flow is removed. */
    record CashSale(String submissionId) implements SourceRef {
        public CashSale {
            require(submissionId, "submissionId");
        }

        @Override
        public String kind() {
            return "cash_sale";
        }

        @Override
        public String owner() {
            return "sales";
        }

        @Override
        public Set<String> documentTypes() {
            return INVOICE;
        }
    }

    /** Proforma made from a quotation before any Sale exists. */
    record Quotation(String quotationId) implements SourceRef {
        public Quotation {
            require(quotationId, "quotationId");
        }

        @Override
        public String kind() {
            return "quotation";
        }

        @Override
        public String owner() {
            return "sales";
        }

        @Override
        public Set<String> documentTypes() {
            return PROFORMA;
        }
    }

    /** Project billing: advance, certified claim, retention or retention release. */
    record ProjectContract(String projectId) implements SourceRef {
        public ProjectContract {
            require(projectId, "projectId");
        }

        @Override
        public String kind() {
            return "project_contract";
        }

        @Override
        public String owner() {
            return "projects";
        }

        @Override
        public Set<String> documentTypes() {
            return INVOICE;
        }
    }

    /** Late-payment fee of a dunning entry. */
    record CollectionsFee(String dunningEntryId) implements SourceRef {
        public CollectionsFee {
            require(dunningEntryId, "dunningEntryId");
        }

        @Override
        public String kind() {
            return "collections_fee";
        }

        @Override
        public String owner() {
            return "sales";
        }

        @Override
        public Set<String> documentTypes() {
            return INVOICE;
        }
    }

    /** Goods (and transport) invoice of a delivery order that no Sale owns. */
    record FleetDelivery(String deliveryOrderId) implements SourceRef {
        public FleetDelivery {
            require(deliveryOrderId, "deliveryOrderId");
        }

        @Override
        public String kind() {
            return "fleet_delivery";
        }

        @Override
        public String owner() {
            return "fleet";
        }

        @Override
        public Set<String> documentTypes() {
            return INVOICE;
        }
    }

    /** Transport-only invoice of a delivery order owned by a Sale (the goods are invoiced from the Sale). */
    record FleetCharges(String deliveryOrderId, String saleId) implements SourceRef {
        public FleetCharges {
            require(deliveryOrderId, "deliveryOrderId");
        }

        @Override
        public String kind() {
            return "fleet_charges";
        }

        @Override
        public String owner() {
            return "fleet";
        }

        @Override
        public Set<String> documentTypes() {
            return INVOICE;
        }
    }

    /** Document generated from a recurring template (any type the template names). */
    record Recurring(String templateId) implements SourceRef {
        public Recurring {
            require(templateId, "templateId");
        }

        @Override
        public String kind() {
            return "recurring";
        }

        @Override
        public String owner() {
            return "kernel";
        }

        @Override
        public Set<String> documentTypes() {
            return Set.of("sales_invoice", "credit_note", "vendor_bill", "debit_note");
        }
    }

    /** Opening-balance invoice of an import batch. */
    record OpeningBalance(String batchId) implements SourceRef {
        public OpeningBalance {
            require(batchId, "batchId");
        }

        @Override
        public String kind() {
            return "opening_balance";
        }

        @Override
        public String owner() {
            return "invoicing";
        }

        @Override
        public Set<String> documentTypes() {
            return INVOICE;
        }
    }

    /** Reversal or reissue counterpart made by the documents engine ({@code relation} CANCELS / REISSUES). */
    record Counterpart(String documentId, String relation) implements SourceRef {
        public Counterpart {
            require(documentId, "documentId");
            require(relation, "relation");
        }

        @Override
        public String kind() {
            return "counterpart";
        }

        @Override
        public String owner() {
            return "documents";
        }

        @Override
        public Set<String> documentTypes() {
            return Set.of("credit_note", "debit_note");
        }
    }

    /**
     * Credit note issued by a module or against a named invoice ({@code invoiceId} may be null for a module credit,
     * e.g. a rebate with no open invoice). The credit kind itself stays in {@code data.creditKind} (ADR-S094 C2).
     */
    record CreditFor(String invoiceId, String owner) implements SourceRef {
        public CreditFor {
            require(owner, "owner");
        }

        @Override
        public String kind() {
            return "credit";
        }

        @Override
        public Set<String> documentTypes() {
            return CREDIT_NOTE;
        }
    }

    /** Created by hand: {@code POST /invoices}, or a credit note that names no invoice. */
    record Manual() implements SourceRef {
        @Override
        public String kind() {
            return "manual";
        }

        @Override
        public String owner() {
            return "invoicing";
        }

        @Override
        public Set<String> documentTypes() {
            return Set.of("sales_invoice", "credit_note");
        }

        @Override
        public boolean manual() {
            return true;
        }
    }

    /** Every value {@code Document.sourceKind} may hold (the database check lists the same). */
    Set<String> KINDS = Set.of("sale", "sale_down_payment", "sale_milestone", "cash_sale", "quotation",
            "project_contract", "collections_fee", "fleet_delivery", "fleet_charges", "recurring", "opening_balance",
            "counterpart", "credit", "manual");

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SourceRef needs " + name);
        }
    }
}

package com.inventoryerp.invoicing.application;

import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.core.application.UomConversionService.UnitRole;
import com.inventoryerp.core.application.UomConversionService.LineUnit;
import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.core.application.ProductVariantService;
import com.inventoryerp.core.application.ProductSizingService;
import com.inventoryerp.core.application.ProductSizingService.Sizing;
import static com.inventoryerp.kernel.common.Money.ZERO;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.documents.application.DmsTypeResolver;
import com.inventoryerp.documents.domain.Document;
import com.inventoryerp.documents.domain.DocumentLine;
import com.inventoryerp.invoicing.application.TaxCalculationService.LineTaxResult;
import com.inventoryerp.invoicing.application.TaxCalculationService.TaxLike;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.ReadMemo;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Shared document logic for invoices, credit notes and vendor bills (port of {@code document-builder.service.ts},
 * ADR-010): the server computes line subtotals / tax / totals and resolves the accounts each line posts to.
 */
@Service
public class DocumentBuilderService {


    private com.inventoryerp.core.application.SaleableProducts saleable;

    private ProductSizingService sizing;

    /** Sell by size (ADR-S066). */
    @org.springframework.beans.factory.annotation.Autowired
    void setSizing(ProductSizingService sizing) {
        this.sizing = sizing;
    }

    /** Sell at any stage (ADR-S060): only the product's own switch stops a sale. */
    @org.springframework.beans.factory.annotation.Autowired
    void setSaleable(com.inventoryerp.core.application.SaleableProducts saleable) {
        this.saleable = saleable;
    }

    /** One requested line ({@code DocumentLineInput}). */
    public record LineInput(String productId, String menuItemId, String variantId, String variantName,
            String accountId, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPercent,
            String discountType, BigDecimal discountAmount, String discountReason, String discountSource, String taxId,
            String lineType, Boolean taxInclusive, String uomId, Sizing sizing) {

        /** Line without a size (ADR-S066: {@code sizing} null = plain quantity). */
        public LineInput(String productId, String menuItemId, String variantId, String variantName, String accountId,
                String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPercent,
                String discountType, BigDecimal discountAmount, String discountReason, String discountSource,
                String taxId, String lineType, Boolean taxInclusive, String uomId) {
            this(productId, menuItemId, variantId, variantName, accountId, description, quantity, unitPrice,
                    discountPercent, discountType, discountAmount, discountReason, discountSource, taxId, lineType,
                    taxInclusive, uomId, null);
        }

        /** The same line with another discount. */
        public LineInput withDiscountPercent(BigDecimal percent) {
            return new LineInput(productId, menuItemId, variantId, variantName, accountId, description, quantity, unitPrice, percent,
                    discountType, discountAmount, discountReason, discountSource, taxId, lineType, taxInclusive, uomId,
                    sizing);
        }

        /** The same line sold by size (ADR-S066): the size decides the quantity. */
        public LineInput withSizing(Sizing size) {
            return new LineInput(productId, menuItemId, variantId, variantName, accountId, description, quantity,
                    unitPrice, discountPercent, discountType, discountAmount, discountReason, discountSource, taxId,
                    lineType, taxInclusive, uomId, size);
        }

        /** Line in the role's default unit (ADR-S003: {@code uomId} picks another unit of the product). */
        public LineInput(String productId, String menuItemId, String variantId, String variantName, String accountId,
                String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPercent,
                String discountType, BigDecimal discountAmount, String discountReason, String discountSource,
                String taxId, String lineType, Boolean taxInclusive) {
            this(productId, menuItemId, variantId, variantName, accountId, description, quantity, unitPrice,
                    discountPercent, discountType, discountAmount, discountReason, discountSource, taxId, lineType,
                    taxInclusive, null);
        }

        public static LineInput of(String productId, String accountId, String description, BigDecimal quantity,
                BigDecimal unitPrice, BigDecimal discountPercent, String taxId, String lineType) {
            return of(productId, accountId, description, quantity, unitPrice, discountPercent, taxId, lineType, null);
        }

        public static LineInput of(String productId, String accountId, String description, BigDecimal quantity,
                BigDecimal unitPrice, BigDecimal discountPercent, String taxId, String lineType, String uomId) {
            return new LineInput(productId, null, null, null, accountId, description, quantity, unitPrice,
                    discountPercent, null, null, null, null, taxId, lineType, null, uomId);
        }
    }

    /** Header fields ({@code DocumentHeaderInput}). Dates are ISO strings like the Nest DTOs. */
    public record HeaderInput(String partnerId, String issueDate, String dueDate, String paymentTermId,
            String paymentTermName, String paymentMode, String fiscalPositionId, String fiscalPositionName,
            String invoicingJournalId, String invoicingJournalName, String salespersonId, String salespersonName,
            String deliveryDate, String deliveryAddress, String incoterm, String incotermLocation, String sourceDocument,
            String currencyId, BigDecimal exchangeRate, String reference, String notes, String reversedDocumentId,
            String sourceType, String branchId) {}

    /** A server-computed line. */
    public record PreparedLine(String productId, String menuItemId, String variantId, String variantName,
            String accountId, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPercent,
            String discountType, BigDecimal discountAmount, String discountReason, String discountSource, String taxId,
            String lineType, BigDecimal subtotal, BigDecimal taxAmount, BigDecimal total, int lineNumber,
            boolean taxInclusive, String uomId, BigDecimal uomFactor, Sizing sizing) {

        /** Shape consumed by {@link #groupForPosting}. */
        public Map<String, Object> asRow() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", productId);
            m.put("accountId", accountId);
            m.put("lineType", lineType);
            m.put("subtotal", subtotal);
            m.put("taxId", taxId);
            m.put("taxAmount", taxAmount);
            m.put("total", total);
            m.put("quantity", quantity);
            m.put("description", description);
            m.put("uomId", uomId);
            m.put("uomFactor", uomFactor);
            return m;
        }
    }

    /** {@code prepareLines} result. */
    public record Totals(List<PreparedLine> prepared, BigDecimal subtotal, BigDecimal taxAmount, BigDecimal total,
            BigDecimal discountTotal) {}

    /**
     * One revenue / expense posting line: account, the line's analytic dimensions (ADR-S025; {@code costCenter} is the
     * cost centre id, the rest go to the GL line's {@code dimensions}) and the amount.
     */
    public record ItemLine(String accountId, Map<String, Object> dimensions, BigDecimal amount) {

        /** Cost centre carried by the dimensions, or null. */
        public String costCenterId() {
            return dimensions == null ? null : (String) dimensions.get("costCenter");
        }

        /** Dimensions for the GL line (without the cost centre), or null. */
        public Map<String, Object> glDimensions() {
            if (dimensions == null) {
                return null;
            }
            Map<String, Object> out = new java.util.TreeMap<>(dimensions);
            out.remove("costCenter");
            return out.isEmpty() ? null : out;
        }
    }

    /**
     * Accounts a document posts to. {@code itemLines} splits {@code itemByAccount} by line dimensions (one entry per
     * account when no line carries dimensions).
     */
    public record Grouped(String counterAccount, Map<String, BigDecimal> itemByAccount,
            Map<String, BigDecimal> taxByAccount, List<ItemLine> itemLines) {

        public Grouped(String counterAccount, Map<String, BigDecimal> itemByAccount,
                Map<String, BigDecimal> taxByAccount) {
            this(counterAccount, itemByAccount, taxByAccount, itemByAccount.entrySet().stream()
                    .map(e -> new ItemLine(e.getKey(), null, e.getValue())).toList());
        }
    }

    private final JdbcClient jdbc;
    private final DmsTypeResolver dmsTypes;
    private final SequenceService sequence;
    private final TaxCalculationService tax;
    private final AccountDeterminationService determination;
    private final UomConversionService units;

    public DocumentBuilderService(JdbcClient jdbc, DmsTypeResolver dmsTypes, SequenceService sequence,
            TaxCalculationService tax, AccountDeterminationService determination, UomConversionService units) {
        this.jdbc = jdbc;
        this.units = units;
        this.dmsTypes = dmsTypes;
        this.sequence = sequence;
        this.tax = tax;
        this.determination = determination;
    }

    private com.inventoryerp.core.application.FiscalPositionTaxService fiscalPositions;

    /** ACC-7: optional (a hand-built builder, e.g. in landed cost, applies no fiscal position). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFiscalPositions(com.inventoryerp.core.application.FiscalPositionTaxService fiscalPositions) {
        this.fiscalPositions = fiscalPositions;
    }

    /** Tax mapping of the document's fiscal position (stated, else the partner's default); empty without one. */
    public Map<String, String> fiscalTaxMap(String fiscalPositionId, String partnerId) {
        if (fiscalPositions == null) {
            return Map.of();
        }
        return fiscalPositions.taxMap(fiscalPositions.resolve(fiscalPositionId, partnerId));
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Decimal places of the organization currency (VAT lines round to it); null when unknown. */
    private Integer currencyScale() {
        String orgId = TenantContext.optionalOrganizationId();
        if (orgId == null) {
            return null;
        }
        String code = ReadMemo.get(() -> jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?")
                .param(orgId).query(String.class).optional().orElse(null), "org.currencyCode", orgId);
        if (code == null) {
            return null;
        }
        return ReadMemo.get(() -> jdbc.sql("SELECT \"decimalPlaces\" FROM \"Currency\" WHERE code = ?").param(code)
                .query(Integer.class).optional().orElse(null), "currency.decimalPlaces", code);
    }

    /** Server-side line math for a sales-side document (lines default to the product's sales unit). */
    public Totals prepareLines(List<LineInput> lines) {
        return prepareLines(lines, UnitRole.SALES);
    }

    /**
     * Line unit for a document line (ADR-S003). An explicit {@code uomId} is validated strictly. The implicit default
     * unit never blocks a document: if it cannot be resolved the line keeps no snapshot and stock posting falls back
     * to the live conversion, exactly as before V5.
     */
    private LineUnit lineUnit(String productId, String uomId, UnitRole role) {
        if (uomId != null && !uomId.isBlank()) {
            return units.resolveLineUnit(productId, uomId, role);
        }
        try {
            return units.resolveLineUnit(productId, null, role);
        } catch (ApiException e) {
            return new LineUnit(null, null);
        }
    }

    /** Server-side line math: price, discount, tax engine, totals; {@code role} picks each line's default unit. */
    public Totals prepareLines(List<LineInput> lines, UnitRole role) {
        return prepareLines(lines, role, Map.of());
    }

    /**
     * As {@link #prepareLines(List, UnitRole)}, with a fiscal-position tax mapping (ACC-7): each line's tax (stated or
     * the product's) is replaced by its target; an exempt target ({@code ""}) leaves the line untaxed.
     */
    public Totals prepareLines(List<LineInput> lines, UnitRole role, Map<String, String> taxMap) {
        List<PreparedLine> prepared = new ArrayList<>();
        BigDecimal discountTotal = ZERO;
        Integer taxScale = currencyScale();
        int i = 0;
        for (LineInput l : lines) {
            i++;
            String lineType = l.lineType() != null ? l.lineType() : "product";
            boolean meta = !"product".equals(lineType);
            Map<String, Object> product = null;
            if (l.productId() != null && !meta) {
                String productId = l.productId();
                String productOrg = org();
                product = ReadMemo.get(() -> SqlRows.one(jdbc.sql("SELECT * FROM \"Product\" WHERE id = ? AND"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1").params(productId, productOrg)),
                        "Product", productOrg, productId);
            }
            // POS lines carry menu-item variants (menuItemId set); only product lines name a ProductVariant.
            String variantId = meta || l.menuItemId() != null || l.variantId() == null || l.variantId().isBlank() ? null
                    : l.variantId();
            String variantName = l.variantName();
            if (variantId != null && product == null) {
                variantId = null;
            }
            if (variantId != null) {
                // ADR-S058: the variant must belong to the line's product; its price is the default unit price.
                Map<String, Object> v = SqlRows.one(jdbc.sql("SELECT v.name, " + ProductVariantService.PRICE_SQL
                        + " AS price FROM \"ProductVariant\" v JOIN \"Product\" p ON p.id = v.\"productId\" WHERE v.id = ?"
                        + " AND v.\"productId\" = ? AND v.\"organizationId\" = ? AND v.\"deletedAt\" IS NULL").params(
                                variantId, l.productId(), org()));
                if (v == null && Boolean.TRUE.equals(product.get("hasVariants"))) {
                    throw ApiException.badRequest("Variant not found");
                }
                if (v == null) {
                    variantId = null; // not a product variant (e.g. a legacy menu variant id): keep the old behaviour
                } else {
                    variantName = variantName != null ? variantName : (String) v.get("name");
                    product = new LinkedHashMap<>(product);
                    product.put("salesPrice", v.get("price"));
                }
            }
            LineUnit unit = meta || product == null ? new LineUnit(null, null) : lineUnit(l.productId(), l.uomId(), role);
            BigDecimal defaultPrice = product != null && product.get("salesPrice") != null
                    ? (BigDecimal) product.get("salesPrice") : null;
            if (!meta && l.unitPrice() == null && product != null && unit.factor() != null) {
                defaultPrice = units.defaultUnitPrice(l.productId(), unit, role, defaultPrice);
            }
            BigDecimal unitPrice = meta ? ZERO : l.unitPrice() != null ? l.unitPrice()
                    : defaultPrice != null ? defaultPrice : ZERO;
            // ADR-S066: a line sold by size gets its quantity from pieces × length [× width [× height]].
            Sizing size = meta || product == null || sizing == null ? null : sizing.resolve(l.productId(), l.sizing());
            BigDecimal quantity = meta ? ZERO : size != null ? size.quantity()
                    : l.quantity() != null ? l.quantity() : BigDecimal.ONE;
            BigDecimal gross = quantity.multiply(unitPrice);
            String discType = l.discountType() != null ? l.discountType() : "percentage";
            BigDecimal effectiveDiscount = "fixed_amount".equals(discType) && l.discountAmount() != null
                    && l.discountAmount().signum() > 0
                    ? Money.min(l.discountAmount(), gross)
                    : gross.multiply(Money.div(l.discountPercent() != null ? l.discountPercent() : ZERO,
                            BigDecimal.valueOf(100)));
            BigDecimal afterDiscount = gross.subtract(effectiveDiscount);
            discountTotal = discountTotal.add(effectiveDiscount);
            BigDecimal effectivePct = gross.signum() > 0
                    ? Money.div(effectiveDiscount, gross).multiply(BigDecimal.valueOf(100)) : ZERO;
            String sourceTaxId = meta ? null : l.taxId() != null ? l.taxId() : product == null ? null
                    : (String) product.get("taxId");
            String mappedTaxId = sourceTaxId != null && taxMap.containsKey(sourceTaxId) ? taxMap.get(sourceTaxId)
                    : sourceTaxId;
            String taxId = mappedTaxId == null || mappedTaxId.isEmpty() ? null : mappedTaxId;
            String taxOrg = org();
            Map<String, Object> taxRow = taxId == null ? null : ReadMemo.get(() -> SqlRows.one(jdbc.sql("SELECT * FROM"
                    + " \"Tax\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1").params(taxId, taxOrg)),
                    "Tax", taxOrg, taxId);
            boolean taxInclusive = l.taxInclusive() != null ? l.taxInclusive()
                    : taxRow != null ? Boolean.TRUE.equals(taxRow.get("isInclusive"))
                    : product != null && Boolean.TRUE.equals(product.get("taxInclusive"));
            List<TaxLike> taxes = taxRow == null ? List.of() : List.of(new TaxLike((String) taxRow.get("id"),
                    (BigDecimal) taxRow.get("rate"), taxInclusive, Boolean.TRUE.equals(taxRow.get("isCompound")),
                    taxRow.get("type") == null ? null : taxRow.get("type").toString()));
            LineTaxResult result = tax.computeLine(afterDiscount, taxes, taxScale);
            String description = l.description() != null ? l.description()
                    : product != null ? (String) product.get("name") : "Item";
            prepared.add(new PreparedLine(l.productId(), l.menuItemId(), variantId == null ? l.variantId() : variantId,
                    variantId == null ? l.variantName() : variantName, l.accountId(),
                    description, quantity, unitPrice, effectivePct, discType, effectiveDiscount, l.discountReason(),
                    l.discountSource() != null ? l.discountSource() : "manual", taxId, lineType, result.net(),
                    result.taxTotal(), result.gross(), i, taxInclusive, unit.uomId(), unit.factor(), size));
        }
        BigDecimal subtotal = Money.sum(prepared.stream().map(PreparedLine::subtotal).toList());
        BigDecimal taxAmount = Money.sum(prepared.stream().map(PreparedLine::taxAmount).toList());
        return new Totals(prepared, subtotal, taxAmount, subtotal.add(taxAmount), discountTotal);
    }

    /** Create a draft document (numbered per type and issue year) with its lines; returns it with lines + partner. */
    /** Refuses a line whose product is switched off for sale (ADR-S060). */
    public void assertSaleable(List<LineInput> lines) {
        lines.forEach(l -> saleable.assertSaleable(l.productId()));
    }

    public Map<String, Object> createDocument(String documentType, HeaderInput h, List<LineInput> lines) {
        if ("sales_invoice".equals(documentType)) {
            assertSaleable(lines);
        }
        Totals totals = prepareLines(lines, "vendor_bill".equals(documentType) ? UnitRole.PURCHASE : UnitRole.SALES,
                fiscalTaxMap(h.fiscalPositionId(), h.partnerId()));
        int year = EntityPatch.toInstant(h.issueDate()).atOffset(ZoneOffset.UTC).getYear();
        String key;
        String prefix;
        switch (documentType) {
            case "credit_note" -> {
                key = "creditnote:" + year;
                prefix = "CN-" + year + "-";
            }
            case "vendor_bill" -> {
                key = "vendorbill:" + year;
                prefix = "BILL-" + year + "-";
            }
            case "proforma_invoice" -> {
                key = "proforma:" + year;
                prefix = "PRO-" + year + "-";
            }
            default -> {
                key = "invoice:" + year;
                prefix = "INV-" + year + "-";
            }
        }
        String number = sequence.next(key, prefix, 6);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Document\" (id, \"organizationId\", \"documentNumber\", \"documentType\", \"documentTypeId\","
                        + " \"partnerId\", \"currencyId\", \"exchangeRate\", \"issueDate\", \"dueDate\", \"paymentTermId\","
                        + " \"paymentTermName\", \"paymentMode\", \"fiscalPositionId\", \"fiscalPositionName\","
                        + " \"invoicingJournalId\", \"invoicingJournalName\", \"salespersonId\", \"salespersonName\","
                        + " \"deliveryDate\", \"deliveryAddress\", incoterm, \"incotermLocation\", \"sourceDocument\", status,"
                        + " reference, notes, \"reversedDocumentId\", \"sourceType\", \"branchId\", subtotal, \"discountTotal\","
                        + " \"taxAmount\", \"totalAmount\", \"amountResidual\", \"amountPaid\", \"paymentStatus\", \"createdAt\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?::\"DocumentType\", ?, ?, ?, ?, ?, ?, ?, ?, ?::\"InvoicePaymentMode\","
                        + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'not_paid', "
                        + NOW + ", " + NOW + ")")
                .params(id, org(), number, documentType, dmsTypes.resolveIdByCode(documentType), h.partnerId(),
                        h.currencyId(), h.exchangeRate() != null ? h.exchangeRate() : BigDecimal.ONE, ts(h.issueDate()),
                        ts(h.dueDate()), h.paymentTermId(), h.paymentTermName(), h.paymentMode(), h.fiscalPositionId(),
                        h.fiscalPositionName(), h.invoicingJournalId(), h.invoicingJournalName(), h.salespersonId(),
                        h.salespersonName(), ts(h.deliveryDate()), h.deliveryAddress(), h.incoterm(), h.incotermLocation(),
                        h.sourceDocument(), h.reference(), h.notes(), h.reversedDocumentId(), h.sourceType(), h.branchId(),
                        totals.subtotal(), totals.discountTotal(), totals.taxAmount(), totals.total(), totals.total())
                .update();
        insertLines(id, totals.prepared(), true);
        Map<String, Object> doc = document(id);
        doc.put("lines", lines(id, false));
        doc.put("partner", partner(doc.get("partnerId")));
        return doc;
    }

    /** Insert prepared lines ({@code withExtras}: menuItemId / lineType / taxInclusive like {@code create}). */
    public void insertLines(String documentId, List<PreparedLine> prepared, boolean withExtras) {
        for (PreparedLine p : prepared) {
            String variant = productVariant(p);
            jdbc.sql("INSERT INTO \"DocumentLine\" (id, \"organizationId\", \"documentId\", \"productId\", \"menuItemId\","
                            + " \"accountId\", description, quantity, \"unitPrice\", \"discountPercent\", \"taxId\", subtotal,"
                            + " \"taxAmount\", total, \"lineNumber\", \"lineType\", \"taxInclusive\", \"uomId\", \"uomFactor\","
                            + " \"variantId\", \"variantName\", pieces, length, width, height) VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                            + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), documentId, p.productId(),
                            withExtras ? p.menuItemId() : null, p.accountId(), p.description(), p.quantity(),
                            p.unitPrice(), p.discountPercent(), p.taxId(), p.subtotal(), p.taxAmount(), p.total(),
                            p.lineNumber(), withExtras ? p.lineType() : "product", withExtras && p.taxInclusive(),
                            p.uomId(), p.uomFactor(), variant, variant == null ? null : p.variantName(),
                            p.sizing() == null ? null : p.sizing().pieces(), p.sizing() == null ? null : p.sizing().length(),
                            p.sizing() == null ? null : p.sizing().width(), p.sizing() == null ? null : p.sizing().height())
                    .update();
        }
    }

    /** Resolve the counter account (AR / AP) plus income|expense and tax accounts grouped for a document. */
    public Grouped groupForPosting(String partnerId, List<Map<String, Object>> lines, String kind, String grniAccountId) {
        Map<String, Object> partner = SqlRows.one(jdbc.sql("SELECT * FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL LIMIT 1").params(partnerId, org()));
        boolean sales = "sales".equals(kind);
        String counter = sales
                ? determination.receivableAccount(partner == null ? null : (String) partner.get("receivableAccountId"))
                : determination.payableAccount(partner == null ? null : (String) partner.get("payableAccountId"));
        Map<String, BigDecimal> items = new LinkedHashMap<>();
        Map<String, BigDecimal> taxes = new LinkedHashMap<>();
        Map<String, Map<String, Object>> lineDims = lineDimensions(lines);
        Map<String, ItemLine> split = new LinkedHashMap<>();
        for (Map<String, Object> line : lines) {
            Object lt = line.get("lineType");
            if (lt != null && !"product".equals(lt.toString())) {
                continue;
            }
            Map<String, Object> category = null;
            boolean stockable = false;
            if (line.get("productId") != null) {
                Object productId = line.get("productId");
                Map<String, Object> p = com.inventoryerp.kernel.common.ReadMemo.get(() -> SqlRows.one(jdbc.sql(
                        "SELECT \"categoryId\", \"trackInventory\" FROM \"Product\""
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1")
                        .params(productId, org())), "doc.productPosting", org(), productId);
                if (p != null) {
                    stockable = Boolean.TRUE.equals(p.get("trackInventory"));
                    if (p.get("categoryId") != null) {
                        category = SqlRows.one(jdbc.sql("SELECT * FROM \"ProductCategory\" WHERE id = ?")
                                .param(p.get("categoryId")));
                    }
                }
            }
            String lineAccount = (String) line.get("accountId");
            String itemAcc = sales
                    ? determination.incomeAccount(lineAccount, category == null ? null : (String) category.get("incomeAccountId"))
                    : stockable && grniAccountId != null ? grniAccountId
                    : determination.expenseAccount(lineAccount,
                            category == null ? null : (String) category.get("expenseAccountId"));
            items.merge(itemAcc, Money.dec(line.get("subtotal")), BigDecimal::add);
            Map<String, Object> dims = lineDims.get((String) line.get("id"));
            String splitKey = itemAcc + " " + (dims == null ? "" : com.inventoryerp.shared.web.EntityJson.toJson(dims));
            split.merge(splitKey, new ItemLine(itemAcc, dims, Money.dec(line.get("subtotal"))),
                    (a, b) -> new ItemLine(a.accountId(), a.dimensions(), a.amount().add(b.amount())));
            BigDecimal taxAmount = Money.dec(line.get("taxAmount"));
            if (line.get("taxId") != null && taxAmount.signum() != 0) {
                Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT \"accountId\", type::text AS type FROM \"Tax\" WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1").params(line.get("taxId"), org()));
                if (t != null && !"withholding".equals(t.get("type"))) {
                    String taxAcc = determination.taxAccount((String) t.get("accountId"),
                            sales ? "tax_payable" : "tax_receivable");
                    taxes.merge(taxAcc, taxAmount, BigDecimal::add);
                }
            }
        }
        return new Grouped(counter, items, taxes, new ArrayList<>(split.values()));
    }

    /** Analytic dimensions of stored document lines by line id (ADR-S025); empty for unsaved (e.g. POS) lines. */
    private Map<String, Map<String, Object>> lineDimensions(List<Map<String, Object>> lines) {
        List<String> ids = lines.stream().map(l -> l.get("id")).filter(String.class::isInstance).map(String.class::cast)
                .toList();
        Map<String, Map<String, Object>> out = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT id, dimensions::text AS d FROM \"DocumentLine\" WHERE \"organizationId\" = ? AND"
                        + " id = ANY(?::text[]) AND dimensions IS NOT NULL").params(org(), ids.toArray(String[]::new))
                .query((rs, n) -> {
                    Object tree = com.inventoryerp.shared.web.EntityJson.parseJson(rs.getString("d"));
                    Map<String, Object> m = new java.util.TreeMap<>();
                    if (tree instanceof tools.jackson.databind.JsonNode node && node.isObject()) {
                        node.properties().forEach(e -> {
                            if (e.getValue().isString() && !e.getValue().asString().isBlank()) {
                                m.put(e.getKey(), e.getValue().asString());
                            }
                        });
                    }
                    if (!m.isEmpty()) {
                        out.put(rs.getString("id"), m);
                    }
                    return null;
                }).list();
        return out;
    }

    // ------------------------------------------------------------------ rows

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    static java.sql.Timestamp ts(String iso) {
        return iso == null ? null : SqlRows.ts(EntityPatch.toInstant(iso));
    }

    static java.sql.Timestamp ts(Instant i) {
        return SqlRows.ts(i);
    }

    /** Document row (tenant scoped) or null. */
    public Map<String, Object> document(String id) {
        return SqlRows.one(Document.class, jdbc.sql("SELECT * FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
    }

    /** Document row of a given type or null. */
    public Map<String, Object> document(String id, String type) {
        return SqlRows.one(Document.class, jdbc.sql("SELECT * FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"documentType\"::text = ?").params(id, org(), type));
    }

    /** Lines of a document ({@code ordered}: by lineNumber). */
    public List<Map<String, Object>> lines(String documentId, boolean ordered) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.sql("SELECT * FROM \"DocumentLine\" WHERE \"documentId\" = ?"
                + (ordered ? " ORDER BY \"lineNumber\" ASC" : "")).param(documentId).query().listOfRows()) {
            Map<String, Object> line = SqlRows.as(DocumentLine.class, row);
            // ADR-S058: variant keys only on variant lines, so documents without variants keep their exact shape.
            if (row.get("variantId") != null) {
                line.put("variantId", row.get("variantId"));
                line.put("variantName", row.get("variantName"));
            }
            // ADR-S066: size keys only on lines sold by size.
            if (row.get("pieces") != null) {
                for (String k : List.of("pieces", "length", "width", "height")) {
                    line.put(k, row.get(k) == null ? null : com.inventoryerp.kernel.common.JsNumbers.js((BigDecimal) row.get(k)));
                }
            }
            out.add(line);
        }
        return out;
    }

    /** The line's ProductVariant id (ADR-S058); menu-item variants of POS lines are not stored here. */
    private String productVariant(PreparedLine p) {
        if (p.menuItemId() != null || p.productId() == null || p.variantId() == null || p.variantId().isBlank()) {
            return null;
        }
        return jdbc.sql("SELECT id FROM \"ProductVariant\" WHERE id = ? AND \"productId\" = ? AND \"organizationId\" = ?")
                .params(p.variantId(), p.productId(), org()).query(String.class).optional().orElse(null);
    }

    /** Full partner row ({@code include: {partner: true}}). */
    public Map<String, Object> partner(Object partnerId) {
        if (partnerId == null) {
            return null;
        }
        return SqlRows.one(com.inventoryerp.core.domain.Partner.class,
                jdbc.sql("SELECT * FROM \"Partner\" WHERE id = ?").param(partnerId));
    }
}

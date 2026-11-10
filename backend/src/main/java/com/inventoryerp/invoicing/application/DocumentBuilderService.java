package com.inventoryerp.invoicing.application;

import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.core.application.UomConversionService.UnitRole;
import com.inventoryerp.core.application.UomConversionService.LineUnit;
import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.core.application.ProductVariantService;
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

    /** Sell at any stage (ADR-S060): only the product's own switch stops a sale. */
    @org.springframework.beans.factory.annotation.Autowired
    void setSaleable(com.inventoryerp.core.application.SaleableProducts saleable) {
        this.saleable = saleable;
    }

    /** One requested line ({@code DocumentLineInput}). */
    public record LineInput(String productId, String menuItemId, String variantId, String variantName,
            String accountId, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPercent,
            String discountType, BigDecimal discountAmount, String discountReason, String discountSource, String taxId,
            String lineType, Boolean taxInclusive, String uomId, List<String> taxIds) {

        /**
         * A line without a tax list ({@code taxIds}, ADR-S071): its single {@code taxId} (or the product's) applies.
         */
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
                    taxIds);
        }

        /** The same line with another tax list (ADR-S071). */
        public LineInput withTaxIds(List<String> ids) {
            return new LineInput(productId, menuItemId, variantId, variantName, accountId, description, quantity,
                    unitPrice, discountPercent, discountType, discountAmount, discountReason, discountSource, taxId,
                    lineType, taxInclusive, uomId, ids);
        }

        /** The same line at another unit price / discount (price lists), keeping everything else. */
        public LineInput withPrice(BigDecimal price, BigDecimal percent) {
            return new LineInput(productId, menuItemId, variantId, variantName, accountId, description, quantity, price,
                    percent, discountType, discountAmount, discountReason, discountSource, taxId, lineType, taxInclusive,
                    uomId, taxIds);
        }

        /** Line in the role's default unit (ADR-S003: {@code uomId} picks another unit of the product). */
        public LineInput(String productId, String menuItemId, String variantId, String variantName, String accountId,
                String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPercent,
                String discountType, BigDecimal discountAmount, String discountReason, String discountSource,
                String taxId, String lineType, Boolean taxInclusive) {
            this(productId, menuItemId, variantId, variantName, accountId, description, quantity, unitPrice,
                    discountPercent, discountType, discountAmount, discountReason, discountSource, taxId, lineType,
                    taxInclusive, null, null);
        }

        public static LineInput of(String productId, String accountId, String description, BigDecimal quantity,
                BigDecimal unitPrice, BigDecimal discountPercent, String taxId, String lineType) {
            return of(productId, accountId, description, quantity, unitPrice, discountPercent, taxId, lineType, null);
        }

        public static LineInput of(String productId, String accountId, String description, BigDecimal quantity,
                BigDecimal unitPrice, BigDecimal discountPercent, String taxId, String lineType, String uomId) {
            return new LineInput(productId, null, null, null, accountId, description, quantity, unitPrice,
                    discountPercent, null, null, null, null, taxId, lineType, null, uomId, null);
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
            boolean taxInclusive, String uomId, BigDecimal uomFactor, List<String> taxIds, List<LineTax> taxes,
            BigDecimal withholdingAmount) {

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
            if (taxes != null) {
                m.put("taxes", taxes.stream().map(LineTax::asMap).toList());
            }
            return m;
        }
    }

    /**
     * One tax of a line's breakdown (ADR-S071): {@code kind} {@code tax} (part of the line total), {@code withholding}
     * (deducted from the payable) or {@code reverse_charge} (self-assessed: input and output VAT, nothing payable).
     */
    public record LineTax(String taxId, String kind, BigDecimal rate, BigDecimal base, BigDecimal amount,
            int sequence) {

        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taxId", taxId);
            m.put("kind", kind);
            m.put("rate", rate);
            m.put("base", base);
            m.put("amount", amount);
            m.put("sequence", sequence);
            return m;
        }
    }

    /** {@code prepareLines} result ({@code withholdingAmount}: withholding of all lines, ADR-S071). */
    public record Totals(List<PreparedLine> prepared, BigDecimal subtotal, BigDecimal taxAmount, BigDecimal total,
            BigDecimal discountTotal, BigDecimal withholdingAmount) {

        public Totals(List<PreparedLine> prepared, BigDecimal subtotal, BigDecimal taxAmount, BigDecimal total,
                BigDecimal discountTotal) {
            this(prepared, subtotal, taxAmount, total, discountTotal, ZERO);
        }

        /** What the partner is owed / owes on the document: total less withholding. */
        public BigDecimal residual() {
            return total.subtract(withholdingAmount);
        }
    }

    /** Withholding and reverse-charge amounts of a document's lines, by tax (ADR-S071). */
    public record SpecialTaxes(BigDecimal withholding, Map<String, BigDecimal[]> withholdingByTax,
            Map<String, BigDecimal> reverseChargeByTax) {

        public boolean any() {
            return withholding.signum() != 0 || !reverseChargeByTax.isEmpty();
        }
    }

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
            BigDecimal quantity = meta ? ZERO : l.quantity() != null ? l.quantity() : BigDecimal.ONE;
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
            List<String> requested = meta || l.taxIds() == null ? null
                    : l.taxIds().stream().filter(x -> x != null && !x.isBlank()).distinct().toList();
            boolean explicit = requested != null && !requested.isEmpty();
            boolean advanced = explicit || taxRow != null && (Boolean.TRUE.equals(taxRow.get("isGroup"))
                    || Boolean.TRUE.equals(taxRow.get("reverseCharge")));
            String description = l.description() != null ? l.description()
                    : product != null ? (String) product.get("name") : "Item";
            if (advanced) {
                // ADR-S071: several taxes / a group / withholding / reverse charge — one engine, a stored breakdown.
                Multi m = multiTax(explicit ? requested : List.of(taxId), explicit, taxMap, role, afterDiscount,
                        l.taxInclusive(), product, taxScale);
                prepared.add(new PreparedLine(l.productId(), l.menuItemId(), variantId == null ? l.variantId()
                        : variantId, variantId == null ? l.variantName() : variantName, l.accountId(), description,
                        quantity, unitPrice, effectivePct, discType, effectiveDiscount, l.discountReason(),
                        l.discountSource() != null ? l.discountSource() : "manual", m.headTaxId(), lineType,
                        m.result().net(), m.result().taxTotal(), m.result().gross(), i, m.inclusive(), unit.uomId(),
                        unit.factor(), explicit ? requested : null, m.taxes(), m.result().withholdingTotal()));
                continue;
            }
            boolean taxInclusive = l.taxInclusive() != null ? l.taxInclusive()
                    : taxRow != null ? Boolean.TRUE.equals(taxRow.get("isInclusive"))
                    : product != null && Boolean.TRUE.equals(product.get("taxInclusive"));
            List<TaxLike> taxes = taxRow == null ? List.of() : List.of(new TaxLike((String) taxRow.get("id"),
                    (BigDecimal) taxRow.get("rate"), taxInclusive, Boolean.TRUE.equals(taxRow.get("isCompound")),
                    taxRow.get("type") == null ? null : taxRow.get("type").toString()));
            LineTaxResult result = tax.computeLine(afterDiscount, taxes, taxScale);
            prepared.add(new PreparedLine(l.productId(), l.menuItemId(), variantId == null ? l.variantId() : variantId,
                    variantId == null ? l.variantName() : variantName, l.accountId(),
                    description, quantity, unitPrice, effectivePct, discType, effectiveDiscount, l.discountReason(),
                    l.discountSource() != null ? l.discountSource() : "manual", taxId, lineType, result.net(),
                    result.taxTotal(), result.gross(), i, taxInclusive, unit.uomId(), unit.factor(), null, null,
                    ZERO));
        }
        BigDecimal subtotal = Money.sum(prepared.stream().map(PreparedLine::subtotal).toList());
        BigDecimal taxAmount = Money.sum(prepared.stream().map(PreparedLine::taxAmount).toList());
        BigDecimal withholding = Money.sum(prepared.stream().map(PreparedLine::withholdingAmount).toList());
        return new Totals(prepared, subtotal, taxAmount, subtotal.add(taxAmount), discountTotal, withholding);
    }

    /** Result of {@link #multiTax}. */
    private record Multi(LineTaxResult result, List<LineTax> taxes, String headTaxId, boolean inclusive) {}

    private Map<String, Object> taxRow(String id) {
        String taxOrg = org();
        return ReadMemo.get(() -> SqlRows.one(jdbc.sql("SELECT * FROM \"Tax\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL LIMIT 1").params(id, taxOrg)), "Tax", taxOrg, id);
    }

    private static String kindOf(Map<String, Object> t) {
        if ("withholding".equals(String.valueOf(t.get("type")))) {
            return "withholding";
        }
        return Boolean.TRUE.equals(t.get("reverseCharge")) ? "reverse_charge" : "tax";
    }

    /**
     * ADR-S071: the line's taxes ({@code ids}: the stated list, or the single group / reverse-charge tax), each mapped
     * by the fiscal position, groups expanded to their ordered children, computed by {@link TaxCalculationService}
     * (compound and inclusive included). Withholding and reverse charge apply to purchases only.
     */
    private Multi multiTax(List<String> ids, boolean explicit, Map<String, String> taxMap, UnitRole role,
            BigDecimal amount, Boolean inclusiveOverride, Map<String, Object> product, Integer scale) {
        List<Map<String, Object>> leaves = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String head = null;
        for (String raw : ids) {
            String id = explicit && taxMap.containsKey(raw) ? taxMap.get(raw) : raw;
            if (id == null || id.isEmpty()) {
                continue;
            }
            Map<String, Object> t = taxRow(id);
            if (t == null) {
                throw ApiException.badRequest("Tax " + id + " not found");
            }
            if (head == null && !"withholding".equals(String.valueOf(t.get("type")))) {
                head = id;
            }
            if (Boolean.TRUE.equals(t.get("isGroup"))) {
                for (String childId : jdbc.sql("SELECT m.\"childTaxId\" FROM \"TaxGroupMember\" m JOIN \"Tax\" c ON"
                        + " c.id = m.\"childTaxId\" WHERE m.\"groupTaxId\" = ? AND m.\"organizationId\" = ? AND"
                        + " c.\"deletedAt\" IS NULL ORDER BY m.sequence").params(id, org()).query(String.class).list()) {
                    String mapped = taxMap.containsKey(childId) ? taxMap.get(childId) : childId;
                    if (mapped == null || mapped.isEmpty() || !seen.add(mapped)) {
                        continue;
                    }
                    Map<String, Object> c = taxRow(mapped);
                    if (c != null && !Boolean.TRUE.equals(c.get("isGroup"))) {
                        leaves.add(c);
                    }
                }
            } else if (seen.add(id)) {
                leaves.add(t);
            }
        }
        boolean purchase = role == UnitRole.PURCHASE;
        List<TaxLike> output = new ArrayList<>();
        List<TaxLike> withholding = new ArrayList<>();
        List<Map<String, Object>> reverse = new ArrayList<>();
        for (Map<String, Object> t : leaves) {
            String kind = kindOf(t);
            if (!"tax".equals(kind) && !purchase) {
                throw ApiException.badRequest("Withholding and reverse-charge taxes apply to vendor bills only");
            }
            BigDecimal rate = Money.dec(t.get("rate"));
            switch (kind) {
                case "withholding" -> withholding.add(new TaxLike((String) t.get("id"), rate, false, false,
                        "withholding"));
                case "reverse_charge" -> reverse.add(t);
                default -> output.add(new TaxLike((String) t.get("id"), rate, inclusiveOverride != null
                        ? inclusiveOverride : Boolean.TRUE.equals(t.get("isInclusive")),
                        Boolean.TRUE.equals(t.get("isCompound")), String.valueOf(t.get("type"))));
            }
        }
        List<TaxLike> all = new ArrayList<>(output);
        all.addAll(withholding);
        LineTaxResult r = tax.computeLine(amount, all, scale);
        List<LineTax> breakdown = new ArrayList<>();
        int seq = 0;
        BigDecimal accumulated = ZERO;
        for (int k = 0; k < output.size(); k++) {
            TaxLike t = output.get(k);
            BigDecimal amt = r.breakdown().get(k).amount();
            BigDecimal base = t.isCompound() ? r.net().add(accumulated) : r.net();
            accumulated = accumulated.add(amt);
            breakdown.add(new LineTax(t.id(), "tax", t.rate(), base, amt, seq++));
        }
        for (Map<String, Object> t : reverse) {
            BigDecimal rate = Money.dec(t.get("rate"));
            BigDecimal amt = TaxCalculationService.round(r.net().multiply(Money.div(rate, BigDecimal.valueOf(100))),
                    scale != null ? scale : 6);
            breakdown.add(new LineTax((String) t.get("id"), "reverse_charge", rate, r.net(), amt, seq++));
        }
        for (int k = 0; k < withholding.size(); k++) {
            TaxLike t = withholding.get(k);
            breakdown.add(new LineTax(t.id(), "withholding", t.rate(), r.net(), r.withholdingBreakdown().get(k)
                    .amount(), seq++));
        }
        boolean inclusive = inclusiveOverride != null ? inclusiveOverride
                : output.isEmpty() ? product != null && Boolean.TRUE.equals(product.get("taxInclusive"))
                : output.stream().anyMatch(TaxLike::isInclusive);
        return new Multi(r, breakdown, head, inclusive);
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
                        + " \"updatedAt\", \"withholdingAmount\") VALUES (?, ?, ?, ?::\"DocumentType\", ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?::\"InvoicePaymentMode\", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0,"
                        + " 'not_paid', " + NOW + ", " + NOW + ", ?)")
                .params(id, org(), number, documentType, dmsTypes.resolveIdByCode(documentType), h.partnerId(),
                        h.currencyId(), h.exchangeRate() != null ? h.exchangeRate() : BigDecimal.ONE, ts(h.issueDate()),
                        ts(h.dueDate()), h.paymentTermId(), h.paymentTermName(), h.paymentMode(), h.fiscalPositionId(),
                        h.fiscalPositionName(), h.invoicingJournalId(), h.invoicingJournalName(), h.salespersonId(),
                        h.salespersonName(), ts(h.deliveryDate()), h.deliveryAddress(), h.incoterm(), h.incotermLocation(),
                        h.sourceDocument(), h.reference(), h.notes(), h.reversedDocumentId(), h.sourceType(), h.branchId(),
                        totals.subtotal(), totals.discountTotal(), totals.taxAmount(), totals.total(), totals.residual(),
                        totals.withholdingAmount())
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
            String lineId = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"DocumentLine\" (id, \"organizationId\", \"documentId\", \"productId\", \"menuItemId\","
                            + " \"accountId\", description, quantity, \"unitPrice\", \"discountPercent\", \"taxId\", subtotal,"
                            + " \"taxAmount\", total, \"lineNumber\", \"lineType\", \"taxInclusive\", \"uomId\", \"uomFactor\","
                            + " \"variantId\", \"variantName\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " ?, ?)")
                    .params(lineId, org(), documentId, p.productId(),
                            withExtras ? p.menuItemId() : null, p.accountId(), p.description(), p.quantity(),
                            p.unitPrice(), p.discountPercent(), p.taxId(), p.subtotal(), p.taxAmount(), p.total(),
                            p.lineNumber(), withExtras ? p.lineType() : "product", withExtras && p.taxInclusive(),
                            p.uomId(), p.uomFactor(), variant, variant == null ? null : p.variantName())
                    .update();
            insertTaxes(documentId, lineId, p);
        }
    }

    /** ADR-S071: the stated tax list and the per-tax breakdown of a multi-tax line (nothing for single-tax lines). */
    private void insertTaxes(String documentId, String lineId, PreparedLine p) {
        if (p.taxIds() != null) {
            jdbc.sql("UPDATE \"DocumentLine\" SET \"taxIds\" = ?::text[] WHERE id = ?")
                    .params(p.taxIds().toArray(String[]::new), lineId).update();
        }
        if (p.taxes() == null) {
            return;
        }
        for (LineTax t : p.taxes()) {
            jdbc.sql("INSERT INTO \"DocumentLineTax\" (id, \"organizationId\", \"documentId\", \"documentLineId\","
                            + " \"taxId\", sequence, kind, rate, base, amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), documentId, lineId, t.taxId(), t.sequence(),
                            t.kind(), t.rate(), t.base(), t.amount())
                    .update();
        }
    }

    /**
     * Withholding and reverse-charge amounts of document lines (stored lines or {@link PreparedLine#asRow}), by tax:
     * withholding as {@code [base, rate, amount]}, reverse charge as the self-assessed VAT.
     */
    @SuppressWarnings("unchecked")
    public SpecialTaxes specialTaxes(List<Map<String, Object>> lines) {
        BigDecimal withholding = ZERO;
        Map<String, BigDecimal[]> byTax = new LinkedHashMap<>();
        Map<String, BigDecimal> reverse = new LinkedHashMap<>();
        for (Map<String, Object> line : lines) {
            if (!(line.get("taxes") instanceof List<?> taxes)) {
                continue;
            }
            for (Object o : taxes) {
                Map<String, Object> t = (Map<String, Object>) o;
                BigDecimal amount = Money.dec(t.get("amount"));
                String taxId = (String) t.get("taxId");
                if ("withholding".equals(t.get("kind"))) {
                    withholding = withholding.add(amount);
                    BigDecimal[] acc = byTax.computeIfAbsent(taxId, k -> new BigDecimal[] {ZERO, Money.dec(t.get(
                            "rate")), ZERO});
                    acc[0] = acc[0].add(Money.dec(t.get("base")));
                    acc[2] = acc[2].add(amount);
                } else if ("reverse_charge".equals(t.get("kind")) && amount.signum() != 0) {
                    reverse.merge(taxId, amount, BigDecimal::add);
                }
            }
        }
        return new SpecialTaxes(withholding, byTax, reverse);
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
            if (line.get("taxes") instanceof List<?> breakdown) {
                // ADR-S071: one tax line per tax account from the breakdown; withholding / reverse charge post apart.
                for (Object o : breakdown) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> bt = (Map<String, Object>) o;
                    BigDecimal amt = Money.dec(bt.get("amount"));
                    if (!"tax".equals(bt.get("kind")) || amt.signum() == 0) {
                        continue;
                    }
                    Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT \"accountId\" FROM \"Tax\" WHERE id = ? AND"
                            + " \"organizationId\" = ? LIMIT 1").params(bt.get("taxId"), org()));
                    String taxAcc = determination.taxAccount(t == null ? null : (String) t.get("accountId"),
                            sales ? "tax_payable" : "tax_receivable");
                    taxes.merge(taxAcc, amt, BigDecimal::add);
                }
            } else if (line.get("taxId") != null && taxAmount.signum() != 0) {
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
        return withWithholding(jdbc.sql("SELECT * FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
    }

    /** Document row of a given type or null. */
    public Map<String, Object> document(String id, String type) {
        return withWithholding(jdbc.sql("SELECT * FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"documentType\"::text = ?").params(id, org(), type));
    }

    /** The row in Prisma shape, plus {@code withholdingAmount} only when the document withheld tax (ADR-S071). */
    private static Map<String, Object> withWithholding(JdbcClient.StatementSpec spec) {
        List<Map<String, Object>> rows = spec.query().listOfRows();
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> raw = rows.getFirst();
        Map<String, Object> out = SqlRows.as(Document.class, raw);
        BigDecimal wht = Money.dec(raw.get("withholdingAmount"));
        if (wht.signum() != 0) {
            out.put("withholdingAmount", SqlRows.value(wht));
        }
        return out;
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
            out.add(line);
        }
        attachTaxes(documentId, out);
        return out;
    }

    /** ADR-S071: {@code taxIds} and the {@code taxes} breakdown on multi-tax lines only. */
    private void attachTaxes(String documentId, List<Map<String, Object>> lines) {
        Map<String, List<Map<String, Object>>> byLine = new LinkedHashMap<>();
        for (Map<String, Object> t : SqlRows.list(jdbc.sql("SELECT x.\"documentLineId\", x.\"taxId\", t.name, t.code,"
                + " x.kind, x.rate, x.base, x.amount, x.sequence FROM \"DocumentLineTax\" x JOIN \"Tax\" t ON t.id ="
                + " x.\"taxId\" WHERE x.\"documentId\" = ? ORDER BY x.\"documentLineId\", x.sequence").param(documentId))) {
            byLine.computeIfAbsent((String) t.remove("documentLineId"), k -> new ArrayList<>()).add(t);
        }
        Map<String, List<String>> ids = new LinkedHashMap<>();
        jdbc.sql("SELECT id, \"taxIds\" FROM \"DocumentLine\" WHERE \"documentId\" = ? AND \"taxIds\" IS NOT NULL")
                .param(documentId).query((rs, n) -> {
                    java.sql.Array a = rs.getArray("taxIds");
                    ids.put(rs.getString("id"), List.of((String[]) a.getArray()));
                    return null;
                }).list();
        if (byLine.isEmpty() && ids.isEmpty()) {
            return;
        }
        for (Map<String, Object> line : lines) {
            Object id = line.get("id");
            if (ids.containsKey(id)) {
                line.put("taxIds", ids.get(id));
            }
            if (byLine.containsKey(id)) {
                line.put("taxes", byLine.get(id));
            }
        }
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

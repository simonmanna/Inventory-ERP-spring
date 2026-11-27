package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.InventoryValuationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.StockMove.Received;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * IPR R5 / ADR-S090: opening-stock import for locations with no stock history.
 *
 * <ol>
 *   <li>{@link #preview}: per-row validation, the batches (at most {@value #BATCH_ROWS} rows of one location each), the
 *       exact journal of each batch and a {@code previewToken} — a hash of the rows plus a snapshot of every input the
 *       posting depends on (product costing method, tracking flags, active standard, {@code updatedAt}, variant state,
 *       location history and open counts, posting accounts, lot and serial settings). No writes.</li>
 *   <li>{@link #importRun}: needs that token and an explicit approval (bound to the token: the approval workflow
 *       {@value #APPROVAL_TYPE}, entity id = the token, when the organisation configures one). The file is validated
 *       again; a changed input is 409 {@value #STALE}. One run, one batch row per batch, then each batch posts in its
 *       own transaction ({@link #postBatch}).</li>
 *   <li>{@link #retryBatch}: a failed batch posts again, idempotently (the batch row lock and its status).</li>
 *   <li>{@link #reconciliation}: per batch and run, Σ ledger value = journal amount = valuation delta.</li>
 * </ol>
 *
 * <p>Posting goes through the public engine API only: {@link StockService#receive} (bare receipt, no GL) with ledger
 * type {@code opening_balance}, reference {@code opening_stock_import} / batch id; then one journal per batch, Dr the
 * product's stock account ({@link StockPostingService#stockAccount}, stamped with the product and location dimensions)
 * / Cr opening equity (the {@code retained_earnings} mapping, accounting policy P1), source {@code opening_stock_import}
 * / batch id, at the ledger's own values.
 *
 * <p>Lock levels of a batch: L1 run row then batch row ({@code FOR UPDATE}); L3 costing keys shared (organisation, then
 * each product in id order); L3b the location freeze key exclusively (polled, never queued); the snapshot is then
 * recomputed; the engine takes L3/L3b again (re-entrant), L4 quant, L5 {@code StockItem}, L6 lot / serial rows; L7 the
 * journal.
 */
@Service
public class OpeningStockImportService {

    public static final String REFERENCE_TYPE = "opening_stock_import";
    public static final String APPROVAL_TYPE = "opening_stock_import";
    public static final String STALE = "Preview is stale — preview and approve again";
    public static final int BATCH_ROWS = 2000;
    public static final int MAX_ROWS = 20000;

    private static final Set<String> SYSTEM_LOCATION_TYPES = Set.of("virtual", "transit");
    private static final Set<String> NON_STOCK_TYPES = Set.of("service", "fee", "subscription");
    private static final java.time.Duration FREEZE_WAIT = java.time.Duration.ofSeconds(10);

    /** One CSV row as sent by the client (all text, so a bad number is a row error, not a 400 of the whole file). */
    public record Row(Integer rowNo, String productId, String productCode, String variantId, String variantSku,
            String locationId, String locationCode, String quantity, String unitCost, String batchNumber,
            String expiryDate, List<String> serialNumbers) {}

    /** A validated row (what a batch stores and posts). */
    public record Resolved(int rowNo, String productId, String productCode, String productName, String variantId,
            String variantName, String locationId, String locationCode, String locationName, BigDecimal quantity,
            BigDecimal unitCost, String costSource, BigDecimal value, String batchNumber, String expiryDate,
            List<String> serialNumbers, String rawUnitCost) {}

    private record Checked(Row in, Resolved row, List<String> errors) {}

    /** The inputs read from the database for a set of rows (the snapshot). */
    private record Facts(Map<String, Map<String, Object>> products, Map<String, Map<String, Object>> variants,
            Map<String, Map<String, Object>> locations, Map<String, String> standards,
            Map<String, BigDecimal> governing, Set<String> existingSerials, Map<String, String> stockAccounts,
            String equity, String equityError, boolean autoLot, Map<String, String> serialPolicies,
            String periodError) {}

    private record Batch(int batchNo, String locationId, List<Resolved> rows, String token, BigDecimal total) {}

    private record Validation(List<Checked> rows, List<Batch> batches, List<String> globalErrors, Facts facts) {

        boolean valid() {
            return globalErrors.isEmpty() && rows.stream().allMatch(r -> r.errors().isEmpty());
        }

        long errorCount() {
            return rows.stream().filter(r -> !r.errors().isEmpty()).count() + globalErrors.size();
        }
    }

    private final JdbcClient jdbc;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final InventoryValuationService valuation;
    private final ObjectProvider<StandardCostService> standards;
    private final SettingResolverService settings;
    private final LocationScope scope;
    private final SequenceService seq;
    private final AuditService audit;
    private final ApprovalsService approvals;
    private final JsonMapper mapper;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public OpeningStockImportService(JdbcClient jdbc, StockService stock, StockPostingService stockPosting,
            PostingService posting, AccountDeterminationService determination, InventoryValuationService valuation,
            ObjectProvider<StandardCostService> standards, SettingResolverService settings, LocationScope scope,
            SequenceService seq, AuditService audit, ApprovalsService approvals, JsonMapper mapper,
            PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.posting = posting;
        this.determination = determination;
        this.valuation = valuation;
        this.standards = standards;
        this.settings = settings;
        this.scope = scope;
        this.seq = seq;
        this.audit = audit;
        this.approvals = approvals;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx = new TransactionTemplate(txm);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /**
     * A read in its own read-only transaction (tenant scope set like a request). Marked rollback-only locally, so a
     * refusal caught inside (an account that cannot be resolved) never turns the commit into an unexpected rollback.
     */
    private <T> T read(java.util.function.Supplier<T> body) {
        return readTx.execute(s -> {
            T out = body.get();
            s.setRollbackOnly();
            return out;
        });
    }

    // ================================================================== preview

    /** Validate the rows and show the batches and their journals; no writes. */
    public Map<String, Object> preview(List<Row> rows) {
        Validation v = validateFile(rows);
        return previewView(v);
    }

    private Validation validateFile(List<Row> rows) {
        if (rows == null || rows.isEmpty()) {
            throw ApiException.badRequest("The file has no rows");
        }
        if (rows.size() > MAX_ROWS) {
            throw ApiException.badRequest("An opening-stock file holds at most " + MAX_ROWS + " rows; split it");
        }
        String periodError = periodError();
        return read(() -> validate(rows, List.of(), periodError));
    }

    /** The books / fiscal period refusal of a movement dated now, or null (checked in its own transaction). */
    private String periodError() {
        try {
            stock.assertStockPeriodOpen(Instant.now());
            return null;
        } catch (ApiException e) {
            return e.getMessage();
        }
    }

    private Map<String, Object> previewView(Validation v) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean valid = v.valid();
        out.put("valid", valid);
        out.put("previewToken", valid ? previewToken(v.batches()) : null);
        out.put("rowCount", v.rows().size());
        out.put("errorCount", v.errorCount());
        out.put("globalErrors", v.globalErrors());
        List<Map<String, Object>> rowViews = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Checked c : v.rows()) {
            rowViews.add(rowView(c.row(), c.in(), c.errors()));
            for (String e : c.errors()) {
                errors.add(Obj.of("rowNo", rowNo(c), "message", e));
            }
        }
        out.put("rows", rowViews);
        out.put("errors", errors);
        Map<String, String[]> accounts = accountNames(v.batches(), v.facts());
        List<Map<String, Object>> batches = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Batch b : v.batches()) {
            Map<String, Object> loc = v.facts().locations().get(b.locationId());
            Map<String, Object> bv = new LinkedHashMap<>();
            bv.put("batchNo", b.batchNo());
            bv.put("locationId", b.locationId());
            bv.put("locationName", loc == null ? null : loc.get("name"));
            bv.put("rowCount", b.rows().size());
            bv.put("totalValue", JsNumbers.dec(b.total()));
            bv.put("batchToken", valid ? b.token() : null);
            bv.put("journal", journalView(b.rows(), v.facts(), accounts));
            batches.add(bv);
            total = total.add(b.total());
        }
        out.put("batches", batches);
        out.put("totalValue", JsNumbers.dec(total));
        out.put("journal", summaryJournal(v.batches(), v.facts(), accounts));
        return out;
    }

    private static int rowNo(Checked c) {
        return c.row() != null ? c.row().rowNo() : c.in().rowNo() != null ? c.in().rowNo() : 0;
    }

    private Map<String, Object> rowView(Resolved r, Row in, List<String> errors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowNo", r.rowNo());
        m.put("productId", r.productId());
        m.put("productCode", r.productCode() != null ? r.productCode() : in == null ? null : in.productCode());
        m.put("productName", r.productName());
        m.put("variantId", r.variantId());
        m.put("variantName", r.variantName());
        m.put("locationId", r.locationId());
        m.put("locationCode", r.locationCode() != null ? r.locationCode() : in == null ? null : in.locationCode());
        m.put("locationName", r.locationName());
        m.put("quantity", r.quantity() == null ? null : JsNumbers.dec(r.quantity()));
        m.put("unitCost", r.unitCost() == null ? null : JsNumbers.dec(r.unitCost()));
        m.put("costSource", r.costSource());
        m.put("value", r.value() == null ? null : JsNumbers.dec(r.value()));
        m.put("batchNumber", r.batchNumber());
        m.put("expiryDate", r.expiryDate());
        m.put("serialNumbers", r.serialNumbers());
        if (errors != null) {
            m.put("errors", errors);
        }
        return m;
    }

    // ================================================================== validation

    /**
     * Validate {@code rows} against the database now. {@code ownBatchIds}: ledger rows of these batches (the run's own,
     * when a later batch of the same location posts) do not count as location history.
     */
    private Validation validate(List<Row> rows, List<String> ownBatchIds, String periodError) {
        Facts facts = facts(rows, ownBatchIds, periodError);
        List<String> global = new ArrayList<>();
        if (facts.periodError() != null) {
            global.add(facts.periodError());
        }
        if (facts.equityError() != null) {
            global.add(facts.equityError());
        }
        List<Checked> checked = new ArrayList<>();
        Map<String, Integer> seenKeys = new HashMap<>();
        Map<String, Integer> seenSerials = new HashMap<>();
        int i = 0;
        for (Row in : rows) {
            i++;
            checked.add(check(in, in.rowNo() != null ? in.rowNo() : i, facts, seenKeys, seenSerials));
        }
        List<Batch> batches = batches(checked, facts);
        return new Validation(checked, batches, global, facts);
    }

    private Facts facts(List<Row> rows, List<String> ownBatchIds, String periodError) {
        String org = org();
        Set<String> pIds = new HashSet<>();
        Set<String> pCodes = new HashSet<>();
        Set<String> lIds = new HashSet<>();
        Set<String> lCodes = new HashSet<>();
        for (Row r : rows) {
            addIf(pIds, r.productId());
            addIf(pCodes, r.productCode());
            addIf(lIds, r.locationId());
            addIf(lCodes, r.locationCode());
        }
        Map<String, Map<String, Object>> products = new LinkedHashMap<>();
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT id, code, sku, name, \"productType\"::text AS"
                        + " \"productType\", \"costingMethod\"::text AS \"costingMethod\", \"costPrice\", \"trackInventory\","
                        + " \"batchTracking\", \"expiryTracking\", \"serialTracking\", \"hasVariants\", \"isActive\","
                        + " \"categoryId\", \"updatedAt\" FROM \"Product\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                        + " AND (id = ANY(?) OR code = ANY(?) OR sku = ANY(?)) ORDER BY id")
                .params(org, arr(pIds), arr(pCodes), arr(pCodes)))) {
            products.put((String) p.get("id"), p);
        }
        Map<String, Map<String, Object>> variants = new LinkedHashMap<>();
        if (!products.isEmpty()) {
            for (Map<String, Object> v : SqlRows.list(jdbc.sql("SELECT id, \"productId\", sku, name, \"isActive\","
                            + " \"deletedAt\" IS NOT NULL AS deleted, \"costPrice\", \"updatedAt\" FROM \"ProductVariant\""
                            + " WHERE \"organizationId\" = ? AND \"productId\" = ANY(?) ORDER BY id")
                    .params(org, arr(products.keySet())))) {
                variants.put((String) v.get("id"), v);
            }
        }
        Map<String, Map<String, Object>> locations = new LinkedHashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT id, code, name, type::text AS type, \"isActive\""
                        + " FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                        + " AND (id = ANY(?) OR code = ANY(?)) ORDER BY id")
                .params(org, arr(lIds), arr(lCodes)))) {
            Map<String, Object> loc = new LinkedHashMap<>(l);
            String id = (String) l.get("id");
            loc.put("history", !jdbc.sql("SELECT 1 FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                            + " \"locationId\" = ? AND NOT (COALESCE(\"referenceType\", '') = ? AND \"referenceId\" = ANY(?))"
                            + " LIMIT 1")
                    .params(org, id, REFERENCE_TYPE, ownBatchIds.toArray(String[]::new)).query().listOfRows().isEmpty());
            loc.put("openCount", jdbc.sql("SELECT \"countCode\" FROM \"InventoryCountSession\" WHERE \"organizationId\" = ?"
                            + " AND \"locationId\" = ? AND status = 'draft' AND \"deletedAt\" IS NULL ORDER BY \"countCode\" LIMIT 1")
                    .params(org, id).query(String.class).optional().orElse(null));
            loc.put("allowed", scope.permits(id));
            locations.put(id, loc);
        }
        Map<String, String> standardIds = new HashMap<>();
        Map<String, BigDecimal> governing = new HashMap<>();
        StandardCostService std = standards.getIfAvailable();
        for (Map<String, Object> p : products.values()) {
            if ("STANDARD".equals(p.get("costingMethod"))) {
                String id = (String) p.get("id");
                standardIds.put(id, jdbc.sql("SELECT id FROM \"ProductStandardCost\" WHERE \"organizationId\" = ? AND"
                        + " \"productId\" = ? AND status = 'active'").params(org, id).query(String.class).optional()
                        .orElse(""));
                BigDecimal g = std == null ? null : std.governingStandard(id).orElse(null);
                if (g != null) {
                    governing.put(id, g);
                }
            }
        }
        Set<String> serials = new HashSet<>();
        Set<String> wanted = new HashSet<>();
        rows.forEach(r -> {
            if (r.serialNumbers() != null) {
                r.serialNumbers().forEach(s -> addIf(wanted, s));
            }
        });
        if (!wanted.isEmpty() && !products.isEmpty()) {
            jdbc.sql("SELECT \"productId\", \"serialNumber\" FROM \"InventorySerial\" WHERE \"organizationId\" = ? AND"
                            + " \"productId\" = ANY(?) AND \"serialNumber\" = ANY(?)")
                    .params(org, arr(products.keySet()), arr(wanted)).query().listOfRows()
                    .forEach(r -> serials.add(r.get("productId") + "|" + r.get("serialNumber")));
        }
        Map<String, String> stockAccounts = new LinkedHashMap<>();
        for (String id : products.keySet()) {
            try {
                stockAccounts.put(id, stockPosting.stockAccount(id, BigDecimal.ONE));
            } catch (ApiException e) {
                stockAccounts.put(id, "!" + e.getMessage());
            }
        }
        String equity = determination.mappedOptional("retained_earnings");
        String equityError = null;
        if (equity == null) {
            equityError = "Map the opening equity account (retained_earnings) in Accounting > Account mappings before"
                    + " importing opening stock";
        }
        boolean autoLot = settings.resolveBool("inventory.batchAutoNumber", SettingContext.EMPTY);
        Map<String, String> policies = new HashMap<>();
        for (Row r : rows) {
            Map<String, Object> p = findProduct(products, r);
            Map<String, Object> l = findLocation(locations, r);
            if (p != null && l != null && Boolean.TRUE.equals(p.get("serialTracking"))) {
                String key = p.get("id") + "|" + l.get("id");
                policies.computeIfAbsent(key, k -> settings.resolveString("inventory.serialPolicy",
                        new SettingContext((String) p.get("id"), (String) p.get("categoryId"), (String) l.get("id"))));
            }
        }
        return new Facts(products, variants, locations, standardIds, governing, serials, stockAccounts, equity,
                equityError, autoLot, policies, periodError);
    }

    private static void addIf(Set<String> set, String v) {
        if (v != null && !v.isBlank()) {
            set.add(v.trim());
        }
    }

    private static String[] arr(java.util.Collection<String> c) {
        return c.toArray(String[]::new);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Map<String, Object> findProduct(Map<String, Map<String, Object>> products, Row r) {
        String id = trim(r.productId());
        if (id != null) {
            return products.get(id);
        }
        String code = trim(r.productCode());
        if (code == null) {
            return null;
        }
        for (Map<String, Object> p : products.values()) {
            if (code.equals(p.get("code"))) {
                return p;
            }
        }
        for (Map<String, Object> p : products.values()) {
            if (code.equals(p.get("sku"))) {
                return p;
            }
        }
        return null;
    }

    private static Map<String, Object> findLocation(Map<String, Map<String, Object>> locations, Row r) {
        String id = trim(r.locationId());
        if (id != null) {
            return locations.get(id);
        }
        String code = trim(r.locationCode());
        if (code == null) {
            return null;
        }
        for (Map<String, Object> l : locations.values()) {
            if (code.equals(l.get("code"))) {
                return l;
            }
        }
        return null;
    }

    private static Map<String, Object> findVariant(Map<String, Map<String, Object>> variants, String productId, Row r) {
        String id = trim(r.variantId());
        if (id != null) {
            Map<String, Object> v = variants.get(id);
            return v != null && productId.equals(v.get("productId")) ? v : null;
        }
        String sku = trim(r.variantSku());
        for (Map<String, Object> v : variants.values()) {
            if (productId.equals(v.get("productId")) && sku != null && sku.equals(v.get("sku"))) {
                return v;
            }
        }
        return null;
    }

    private static BigDecimal number(String raw) {
        try {
            return new BigDecimal(raw.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Checked check(Row in, int rowNo, Facts f, Map<String, Integer> seenKeys, Map<String, Integer> seenSerials) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> p = findProduct(f.products(), in);
        Map<String, Object> loc = findLocation(f.locations(), in);
        String pLabel = trim(in.productCode()) != null ? trim(in.productCode()) : trim(in.productId());
        String lLabel = trim(in.locationCode()) != null ? trim(in.locationCode()) : trim(in.locationId());
        String name = p == null ? pLabel : (String) p.get("name");
        Map<String, Object> variant = null;
        if (pLabel == null) {
            errors.add("Enter a product code");
        } else if (p == null) {
            errors.add("Product " + pLabel + " not found");
        } else {
            if (!Boolean.TRUE.equals(p.get("trackInventory")) || NON_STOCK_TYPES.contains(p.get("productType"))) {
                errors.add("Product " + name + " does not keep stock (not a stock-tracked product)");
            }
            if (!Boolean.TRUE.equals(p.get("isActive"))) {
                errors.add("Product " + name + " is inactive");
            }
            boolean named = trim(in.variantId()) != null || trim(in.variantSku()) != null;
            if (Boolean.TRUE.equals(p.get("hasVariants"))) {
                if (!named) {
                    errors.add("Choose a variant for " + name + " (variant SKU)");
                } else {
                    variant = findVariant(f.variants(), (String) p.get("id"), in);
                    if (variant == null) {
                        errors.add("Variant " + Objects.requireNonNullElse(trim(in.variantSku()), trim(in.variantId()))
                                + " not found for " + name);
                    } else if (!Boolean.TRUE.equals(variant.get("isActive")) || Boolean.TRUE.equals(variant.get("deleted"))) {
                        errors.add("Variant " + variant.get("name") + " of " + name + " is inactive");
                    }
                }
            } else if (named) {
                errors.add("Product " + name + " has no variants; leave the variant empty");
            }
        }
        if (lLabel == null) {
            errors.add("Enter a location code");
        } else if (loc == null) {
            errors.add("Location " + lLabel + " not found");
        } else {
            String ln = (String) loc.get("name");
            if (!Boolean.TRUE.equals(loc.get("allowed"))) {
                errors.add("Location " + ln + " is outside your data access");
            } else if (!Boolean.TRUE.equals(loc.get("isActive"))) {
                errors.add("Location " + ln + " is inactive");
            } else if (SYSTEM_LOCATION_TYPES.contains(loc.get("type"))) {
                errors.add("Location " + ln + " is a system location; opening stock goes into a real stock location");
            } else if (Boolean.TRUE.equals(loc.get("history"))) {
                errors.add("Location " + ln + " already has stock movements; opening stock import is only for"
                        + " locations with no stock history");
            } else if (loc.get("openCount") != null) {
                errors.add("Location " + ln + " has an open count " + loc.get("openCount")
                        + "; submit or cancel it before importing opening stock");
            }
        }
        BigDecimal qty = null;
        if (trim(in.quantity()) == null) {
            errors.add("Enter a quantity");
        } else {
            qty = number(in.quantity());
            if (qty == null) {
                errors.add("Quantity must be a number");
            } else if (qty.signum() <= 0) {
                errors.add("Quantity must be greater than 0");
                qty = null;
            } else if (qty.stripTrailingZeros().scale() > 6) {
                errors.add("Quantity has more than 6 decimal places");
            }
        }
        BigDecimal cost = null;
        String costSource = null;
        String rawCost = trim(in.unitCost());
        if (p != null) {
            String method = (String) p.get("costingMethod");
            boolean lots = Boolean.TRUE.equals(p.get("batchTracking"));
            if ("STANDARD".equals(method)) {
                BigDecimal std = f.governing().get((String) p.get("id"));
                if (std == null || std.signum() <= 0) {
                    errors.add("Set a cost price or an active standard cost for STANDARD product " + name + " first");
                } else if (rawCost != null && (number(rawCost) == null || number(rawCost).compareTo(std) != 0)) {
                    errors.add(name + " is a STANDARD product carried at its standard " + JsNumbers.dec(std)
                            + "; leave the unit cost empty or enter " + JsNumbers.dec(std));
                } else {
                    cost = std;
                    costSource = "standard";
                }
            } else if (rawCost == null) {
                BigDecimal fallback = variant != null && variant.get("costPrice") != null
                        ? (BigDecimal) variant.get("costPrice") : (BigDecimal) p.get("costPrice");
                if (fallback == null || fallback.signum() <= 0) {
                    errors.add("Enter a unit cost (" + name + " has no cost price)");
                } else {
                    cost = fallback;
                    costSource = "product_cost";
                }
            } else {
                cost = number(rawCost);
                if (cost == null) {
                    errors.add("Unit cost must be a number");
                } else if (cost.signum() <= 0) {
                    errors.add("Unit cost must be greater than 0 (opening stock is valued)");
                    cost = null;
                } else if (cost.stripTrailingZeros().scale() > 6) {
                    errors.add("Unit cost has more than 6 decimal places");
                } else {
                    costSource = "file";
                }
            }
            if ("FIFO".equals(method) && !lots) {
                errors.add("FIFO costing requires lot tracking on " + name);
            }
            String lot = trim(in.batchNumber());
            if (lots && lot == null && !f.autoLot()) {
                errors.add(name + " is lot-tracked: enter a lot number");
            }
            if (!lots && lot != null) {
                errors.add(name + " is not lot-tracked: leave the lot empty");
            }
            String expiry = trim(in.expiryDate());
            if (expiry != null) {
                if (!lots) {
                    errors.add("Expiry date is only valid for lot-tracked products");
                }
                try {
                    LocalDate.parse(expiry);
                } catch (RuntimeException e) {
                    errors.add("Expiry date must be YYYY-MM-DD");
                }
            } else if (Boolean.TRUE.equals(p.get("expiryTracking"))) {
                errors.add(name + " is expiry-tracked: enter an expiry date");
            }
            List<String> serials = serials(in);
            if (Boolean.TRUE.equals(p.get("serialTracking"))) {
                if (qty != null && qty.stripTrailingZeros().scale() > 0) {
                    errors.add("Serial-tracked " + name + " needs a whole quantity");
                } else if (qty != null) {
                    String policy = loc == null ? null : f.serialPolicies().get(p.get("id") + "|" + loc.get("id"));
                    if ("required".equals(policy) && qty.compareTo(BigDecimal.valueOf(serials.size())) != 0) {
                        errors.add(name + " needs exactly " + JsNumbers.dec(qty) + " serial number(s), got "
                                + serials.size());
                    } else if (qty.compareTo(BigDecimal.valueOf(serials.size())) < 0) {
                        errors.add(serials.size() + " serial number(s) for a quantity of " + JsNumbers.dec(qty));
                    }
                }
                for (String s : serials) {
                    Integer first = seenSerials.putIfAbsent(p.get("id") + "|" + s, rowNo);
                    if (first != null) {
                        errors.add("Serial " + s + " of " + name + " is already on row " + first);
                    } else if (f.existingSerials().contains(p.get("id") + "|" + s)) {
                        errors.add("Serial " + s + " already exists for " + name);
                    }
                }
            } else if (!serials.isEmpty()) {
                errors.add(name + " is not serial-tracked: leave the serial numbers empty");
            }
            if (loc != null && errors.isEmpty()) {
                String key = p.get("id") + "|" + (variant == null ? "" : variant.get("id")) + "|" + loc.get("id") + "|"
                        + Objects.toString(lot, "");
                Integer first = seenKeys.putIfAbsent(key, rowNo);
                if (first != null) {
                    errors.add("Duplicate of row " + first + " (same product, variant, location and lot)");
                }
            }
            String account = f.stockAccounts().get((String) p.get("id"));
            if (account != null && account.startsWith("!")) {
                errors.add(account.substring(1));
            }
        }
        BigDecimal value = qty != null && cost != null ? qty.multiply(cost).setScale(6, RoundingMode.HALF_UP) : null;
        Resolved r = new Resolved(rowNo, p == null ? null : (String) p.get("id"), p == null ? null : (String) p.get("code"),
                p == null ? null : (String) p.get("name"), variant == null ? null : (String) variant.get("id"),
                variant == null ? null : (String) variant.get("name"), loc == null ? null : (String) loc.get("id"),
                loc == null ? null : (String) loc.get("code"), loc == null ? null : (String) loc.get("name"),
                qty == null ? null : qty.stripTrailingZeros(), cost, costSource, value, trim(in.batchNumber()),
                trim(in.expiryDate()), serials(in), rawCost);
        return new Checked(in, r, errors);
    }

    private static List<String> serials(Row in) {
        if (in.serialNumbers() == null) {
            return List.of();
        }
        return in.serialNumbers().stream().map(OpeningStockImportService::trim).filter(Objects::nonNull).toList();
    }

    /** Valid rows grouped by location (first-appearance order), chunks of {@value #BATCH_ROWS} in row order. */
    private List<Batch> batches(List<Checked> checked, Facts f) {
        Map<String, List<Resolved>> byLocation = new LinkedHashMap<>();
        for (Checked c : checked) {
            if (c.errors().isEmpty() && c.row().locationId() != null) {
                byLocation.computeIfAbsent(c.row().locationId(), k -> new ArrayList<>()).add(c.row());
            }
        }
        List<Batch> out = new ArrayList<>();
        int n = 0;
        for (Map.Entry<String, List<Resolved>> e : byLocation.entrySet()) {
            List<Resolved> all = e.getValue();
            for (int from = 0; from < all.size(); from += BATCH_ROWS) {
                List<Resolved> chunk = List.copyOf(all.subList(from, Math.min(all.size(), from + BATCH_ROWS)));
                BigDecimal total = chunk.stream().map(Resolved::value).reduce(BigDecimal.ZERO, BigDecimal::add);
                out.add(new Batch(++n, e.getKey(), chunk, batchToken(chunk, e.getKey(), f), total));
            }
        }
        return out;
    }

    // ================================================================== hashes

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    /** Canonical content of a row as entered (resolved ids, normalised numbers): the basis of the import hashes. */
    private static String canonical(Resolved r) {
        return String.join("|", r.productId(), Objects.toString(r.variantId(), ""), r.locationId(), plain(r.quantity()),
                r.rawUnitCost() == null ? "" : plain(number(r.rawUnitCost())), Objects.toString(r.batchNumber(), ""),
                Objects.toString(r.expiryDate(), ""), String.join(",", r.serialNumbers()));
    }

    private static String rowsHash(List<Resolved> rows) {
        StringBuilder sb = new StringBuilder();
        rows.forEach(r -> sb.append(canonical(r)).append('\n'));
        return sha256(sb.toString());
    }

    /** Hash of a batch's rows plus every input its posting reads (the R5 snapshot). */
    private static String batchToken(List<Resolved> rows, String locationId, Facts f) {
        StringBuilder sb = new StringBuilder("rows:").append(rowsHash(rows)).append('\n');
        rows.forEach(r -> sb.append(r.rowNo()).append('=').append(plain(r.unitCost())).append(';'));
        sb.append('\n');
        Map<String, Object> loc = f.locations().get(locationId);
        sb.append("location:").append(locationId).append('|').append(loc.get("type")).append('|')
                .append(loc.get("isActive")).append('|').append(loc.get("history")).append('|')
                .append(loc.get("openCount")).append('|').append(loc.get("allowed")).append('\n');
        TreeSet<String> productIds = new TreeSet<>();
        TreeSet<String> variantIds = new TreeSet<>();
        rows.forEach(r -> {
            productIds.add(r.productId());
            if (r.variantId() != null) {
                variantIds.add(r.variantId());
            }
        });
        for (String id : productIds) {
            Map<String, Object> p = f.products().get(id);
            sb.append("product:").append(id);
            for (String k : List.of("productType", "costingMethod", "costPrice", "trackInventory", "batchTracking",
                    "expiryTracking", "serialTracking", "hasVariants", "isActive", "categoryId", "updatedAt")) {
                Object v = p.get(k);
                sb.append('|').append(v instanceof BigDecimal d ? plain(d) : Objects.toString(v, ""));
            }
            sb.append("|std:").append(Objects.toString(f.standards().get(id), "")).append(':')
                    .append(plain(f.governing().get(id)));
            sb.append("|acct:").append(f.stockAccounts().get(id));
            sb.append("|serialPolicy:").append(Objects.toString(f.serialPolicies().get(id + "|" + locationId), ""));
            sb.append('\n');
        }
        for (String id : variantIds) {
            Map<String, Object> v = f.variants().get(id);
            sb.append("variant:").append(id).append('|').append(v.get("isActive")).append('|').append(v.get("deleted"))
                    .append('|').append(plain((BigDecimal) v.get("costPrice"))).append('|').append(v.get("updatedAt"))
                    .append('\n');
        }
        sb.append("equity:").append(f.equity()).append("|autoLot:").append(f.autoLot());
        return sha256(sb.toString());
    }

    private static String previewToken(List<Batch> batches) {
        StringBuilder sb = new StringBuilder("opening-stock-preview:v1\n");
        batches.forEach(b -> sb.append(b.batchNo()).append(':').append(b.token()).append('\n'));
        return sha256(sb.toString());
    }

    /** Hash of the file as entered (rows in order, resolved ids); null while a row's product or location is unknown. */
    private static String importHash(List<Checked> rows) {
        List<Resolved> all = new ArrayList<>();
        for (Checked c : rows) {
            if (c.row().productId() == null || c.row().locationId() == null) {
                return null;
            }
            all.add(c.row());
        }
        all.sort(Comparator.comparingInt(Resolved::rowNo));
        return rowsHash(all);
    }

    // ================================================================== journal

    /** Dr stock account per product (aggregated) / Cr opening equity, for a batch of rows valued at {@code value}. */
    private List<Line> journalLines(List<Resolved> rows, Map<String, BigDecimal> valueByProduct, String locationId,
            Facts f) {
        List<Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : valueByProduct.entrySet()) {
            if (e.getValue().signum() <= 0) {
                continue;
            }
            String name = rows.stream().filter(r -> r.productId().equals(e.getKey())).findFirst()
                    .map(Resolved::productName).orElse(e.getKey());
            lines.add(StockPostingService.stampLine(Line.debit(f.stockAccounts().get(e.getKey()),
                    JsNumbers.dec(e.getValue()), "Opening stock · " + name), e.getKey(), locationId));
            total = total.add(e.getValue());
        }
        if (total.signum() > 0) {
            lines.add(Line.credit(f.equity(), JsNumbers.dec(total), "Opening stock"));
        }
        return lines;
    }

    private static Map<String, BigDecimal> valueByProduct(List<Resolved> rows) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        rows.forEach(r -> m.merge(r.productId(), r.value() == null ? BigDecimal.ZERO : r.value(), BigDecimal::add));
        return m;
    }

    private Map<String, String[]> accountNames(List<Batch> batches, Facts f) {
        Set<String> ids = new HashSet<>();
        batches.forEach(b -> b.rows().forEach(r -> addIf(ids, f.stockAccounts().get(r.productId()))));
        addIf(ids, f.equity());
        Map<String, String[]> out = new HashMap<>();
        if (!ids.isEmpty()) {
            jdbc.sql("SELECT id, code, name FROM \"Account\" WHERE \"organizationId\" = ? AND id = ANY(?)")
                    .params(org(), arr(ids)).query().listOfRows()
                    .forEach(r -> out.put((String) r.get("id"), new String[] {(String) r.get("code"), (String) r.get("name")}));
        }
        return out;
    }

    private Map<String, Object> journalView(List<Resolved> rows, Facts f, Map<String, String[]> accounts) {
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        Map<String, BigDecimal> byProduct = valueByProduct(rows);
        for (Map.Entry<String, BigDecimal> e : byProduct.entrySet()) {
            String acct = f.stockAccounts().get(e.getKey());
            String name = rows.stream().filter(r -> r.productId().equals(e.getKey())).findFirst()
                    .map(Resolved::productName).orElse(null);
            lines.add(lineView(acct, accounts, e.getValue(), null, e.getKey(), name));
            dr = dr.add(e.getValue());
        }
        if (dr.signum() > 0) {
            lines.add(lineView(f.equity(), accounts, null, dr, null, null));
            cr = dr;
        }
        return Obj.of("lines", lines, "totalDebit", JsNumbers.dec(dr), "totalCredit", JsNumbers.dec(cr));
    }

    private Map<String, Object> summaryJournal(List<Batch> batches, Facts f, Map<String, String[]> accounts) {
        Map<String, BigDecimal> byAccount = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Batch b : batches) {
            for (Resolved r : b.rows()) {
                byAccount.merge(f.stockAccounts().get(r.productId()), r.value(), BigDecimal::add);
                total = total.add(r.value());
            }
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        byAccount.forEach((acct, v) -> lines.add(lineView(acct, accounts, v, null, null, null)));
        if (total.signum() > 0) {
            lines.add(lineView(f.equity(), accounts, null, total, null, null));
        }
        return Obj.of("journalCode", "INV", "sourceType", REFERENCE_TYPE, "description",
                "Opening stock (one entry per batch)", "lines", lines, "totalDebit", JsNumbers.dec(total),
                "totalCredit", JsNumbers.dec(total));
    }

    private static Map<String, Object> lineView(String accountId, Map<String, String[]> accounts, BigDecimal debit,
            BigDecimal credit, String productId, String productName) {
        String[] a = accounts.get(accountId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", accountId);
        m.put("accountCode", a == null ? null : a[0]);
        m.put("accountName", a == null ? null : a[1]);
        m.put("debit", debit == null ? "0" : JsNumbers.dec(debit));
        m.put("credit", credit == null ? "0" : JsNumbers.dec(credit));
        m.put("productId", productId);
        m.put("productName", productName);
        return m;
    }

    // ================================================================== import

    /**
     * Import the previewed file: the token must match the file as validated now (else 409 {@value #STALE}), the
     * approval is explicit, and the file must not have been imported before (unique import hash). Each batch then posts
     * in its own transaction; the answer is the run with every batch's status.
     */
    public Map<String, Object> importRun(List<Row> rows, String previewToken, Boolean approve, String fileName,
            String notes) {
        if (!Boolean.TRUE.equals(approve)) {
            throw ApiException.badRequest("Approve the previewed opening-stock journal (approve: true) to import it");
        }
        if (previewToken == null || previewToken.isBlank()) {
            throw ApiException.badRequest("Preview the file first: the import needs its previewToken");
        }
        Validation v = validateFile(rows);
        // The same file twice is refused by its import hash first (its locations now have history, so the rows would
        // also fail validation; the hash names the run that imported it).
        String hash = importHash(v.rows());
        if (hash != null) {
            read(() -> {
                assertNotImported(hash);
                return null;
            });
        }
        if (!v.valid()) {
            throw ApiException.badRequest("The file has " + v.errorCount() + " error(s); preview it again and fix them"
                    + " before importing");
        }
        if (!previewToken.equals(previewToken(v.batches()))) {
            throw ApiException.conflict(STALE);
        }
        BigDecimal total = v.batches().stream().map(Batch::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> approval = tx.execute(s -> approvals.checkOrRequestApproval(APPROVAL_TYPE, previewToken,
                Obj.of("amount", total.doubleValue(), "totalValue", JsNumbers.dec(total), "rowCount", rows.size(),
                        "batchCount", v.batches().size())));
        if (approval != null && Boolean.TRUE.equals(approval.get("needsApproval"))) {
            throw ApiException.forbidden("Opening stock import requires approval. Request ID: "
                    + approval.get("requestId") + ".");
        }
        String approvalRequestId = approval == null ? null : (String) approval.get("requestId");
        String runId = createRun(v, hash, previewToken, total, fileName, notes, approvalRequestId);
        List<String> batchIds = read(() -> jdbc.sql("SELECT id FROM \"OpeningStockImportBatch\" WHERE \"runId\" = ?"
                + " AND \"organizationId\" = ? ORDER BY \"batchNo\"").params(runId, org()).query(String.class).list());
        for (String batchId : batchIds) {
            postOrRecord(runId, batchId, null);
        }
        return run(runId);
    }

    private void assertNotImported(String hash) {
        String code = jdbc.sql("SELECT \"runCode\" FROM \"OpeningStockImportRun\" WHERE \"organizationId\" = ? AND"
                + " \"importHash\" = ?").params(org(), hash).query(String.class).optional().orElse(null);
        if (code != null) {
            throw ApiException.conflict("This file was already imported (run " + code + ")");
        }
    }

    private String createRun(Validation v, String hash, String previewToken, BigDecimal total, String fileName,
            String notes, String approvalRequestId) {
        try {
            return tx.execute(s -> {
                String org = org();
                String user = TenantContext.userId();
                String runId = UUID.randomUUID().toString();
                String code = seq.next("opening_stock_import", "OSI-", 5);
                jdbc.sql("INSERT INTO \"OpeningStockImportRun\" (id, \"organizationId\", \"runCode\", status, \"importHash\","
                                + " \"previewToken\", \"fileName\", notes, \"rowCount\", \"batchCount\", \"totalValue\","
                                + " \"approvalRequestId\", \"approvedById\", \"approvedAt\", \"createdBy\") VALUES (?, ?, ?,"
                                + " 'posting', ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?)")
                        .params(runId, org, code, hash, previewToken, trim(fileName), trim(notes), v.rows().size(),
                                v.batches().size(), total, approvalRequestId, user, user)
                        .update();
                for (Batch b : v.batches()) {
                    jdbc.sql("INSERT INTO \"OpeningStockImportBatch\" (id, \"organizationId\", \"runId\", \"batchNo\","
                                    + " \"locationId\", status, \"importHash\", \"batchToken\", \"rowCount\", \"totalValue\", rows)"
                                    + " VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?, ?::jsonb)")
                            .params(UUID.randomUUID().toString(), org, runId, b.batchNo(), b.locationId(),
                                    sha256(b.locationId() + "\n" + rowsHash(b.rows())), b.token(), b.rows().size(),
                                    b.total(), mapper.writeValueAsString(b.rows()))
                            .update();
                }
                audit.recordInTx(AuditInput.of("OpeningStockImportRun", runId, "create", null, Obj.of("runCode", code,
                        "rowCount", v.rows().size(), "batchCount", v.batches().size(), "totalValue",
                        JsNumbers.dec(total), "previewToken", previewToken)));
                return runId;
            });
        } catch (DuplicateKeyException e) {
            read(() -> {
                assertNotImported(hash);
                return null;
            });
            throw ApiException.conflict("These rows were already imported for one of the locations in an earlier run");
        }
    }

    /** Post a batch; a failure is recorded on the batch (its own transaction) and rethrown only when asked. */
    private void postOrRecord(String runId, String batchId, String newToken) {
        try {
            postBatch(runId, batchId, newToken);
        } catch (RuntimeException e) {
            if (e instanceof ApiException api && api.getMessage() != null && api.getMessage().startsWith("Batch ")
                    && api.getMessage().contains("is already posted")) {
                return;
            }
            recordFailure(runId, batchId, message(e));
        }
    }

    private static String message(Throwable e) {
        Throwable t = e;
        String msg = null;
        while (t != null) {
            if (t instanceof ApiException) {
                return t.getMessage();
            }
            msg = t.getMessage();
            t = t.getCause();
        }
        String out = msg == null ? e.toString() : msg;
        return out.length() > 500 ? out.substring(0, 500) : out;
    }

    /**
     * Post one batch atomically (see the class lock levels). {@code newToken} non-null: the batch was previewed again
     * after a stale refusal and is approved at that token.
     */
    private void postBatch(String runId, String batchId, String newToken) {
        tx.executeWithoutResult(s -> {
            String org = org();
            Map<String, Object> run = SqlRows.one(jdbc.sql("SELECT * FROM \"OpeningStockImportRun\" WHERE id = ? AND"
                    + " \"organizationId\" = ? FOR UPDATE").params(runId, org));
            if (run == null) {
                throw ApiException.notFound("Opening-stock import run not found");
            }
            Map<String, Object> batch = SqlRows.one(jdbc.sql("SELECT id, \"batchNo\", \"locationId\", status,"
                    + " \"batchToken\", rows::text AS rows FROM \"OpeningStockImportBatch\" WHERE id = ? AND \"runId\" = ?"
                    + " AND \"organizationId\" = ? FOR UPDATE").params(batchId, runId, org));
            if (batch == null) {
                throw ApiException.notFound("Opening-stock import batch not found");
            }
            if ("posted".equals(batch.get("status"))) {
                throw ApiException.conflict("Batch " + batch.get("batchNo") + " of run " + run.get("runCode")
                        + " is already posted");
            }
            String locationId = (String) batch.get("locationId");
            List<Resolved> stored = mapper.readValue((String) batch.get("rows"), new TypeReference<List<Resolved>>() {});
            List<String> ownBatches = jdbc.sql("SELECT id FROM \"OpeningStockImportBatch\" WHERE \"runId\" = ?")
                    .param(runId).query(String.class).list();
            // L3: the costing keys shared, organisation then each product in id order.
            new TreeSet<>(stored.stream().map(Resolved::productId).toList())
                    .forEach(pid -> CostingLocks.shareProduct(jdbc, org, pid));
            // L3b: the location freeze key exclusively (polled): no other move at the location while the batch posts.
            lockLocation(org, locationId);
            // The period is checked again by the engine on every receipt (assertStockPeriodOpen).
            Validation now = validate(stored.stream().map(OpeningStockImportService::asRow).toList(), ownBatches, null);
            String expected = newToken != null ? newToken : (String) batch.get("batchToken");
            if (!now.valid() || now.batches().size() != 1 || !expected.equals(now.batches().getFirst().token())) {
                throw ApiException.conflict(STALE);
            }
            Facts facts = now.facts();
            List<Resolved> rows = now.batches().getFirst().rows();
            List<String> productIds = rows.stream().map(Resolved::productId).distinct().toList();
            BigDecimal before = valueAt(locationId, productIds);
            Map<String, BigDecimal> postedByProduct = new LinkedHashMap<>();
            BigDecimal ledgerValue = BigDecimal.ZERO;
            List<Resolved> ordered = StockMove.inLockOrder(rows, Resolved::productId, Resolved::variantId);
            String user = TenantContext.userId();
            for (Resolved r : ordered) {
                StockMove m = StockMove.of(r.productId(), locationId, r.quantity()).variant(r.variantId())
                        .unitCost(r.unitCost()).batch(r.batchNumber()).expiry(r.expiryDate())
                        .serials(r.serialNumbers().isEmpty() ? null : r.serialNumbers()).moveType("opening_balance")
                        .source(REFERENCE_TYPE, batchId).reference((String) run.get("runCode"))
                        .notes("Opening stock " + run.get("runCode") + " batch " + batch.get("batchNo") + " row "
                                + r.rowNo())
                        .people(user, user);
                m.skipLateCountCheck = true;
                Received rec = stock.receive(m);
                // The ledger stores the value at 6 decimals (numeric(20,6), half up): the journal takes the same.
                BigDecimal value = rec.totalValue().setScale(6, RoundingMode.HALF_UP);
                postedByProduct.merge(r.productId(), value, BigDecimal::add);
                ledgerValue = ledgerValue.add(value);
            }
            List<Line> lines = journalLines(rows, postedByProduct, locationId, facts);
            Map<String, Object> entry = lines.isEmpty() ? null : posting.post(PostingRequest.of("INV", Instant.now(),
                    "Opening stock " + run.get("runCode") + " · batch " + batch.get("batchNo") + " · "
                            + facts.locations().get(locationId).get("name"),
                    REFERENCE_TYPE, batchId, "inventory:opening_stock_import:" + org + ":" + batchId, lines));
            BigDecimal after = valueAt(locationId, productIds);
            jdbc.sql("UPDATE \"OpeningStockImportBatch\" SET status = 'posted', \"ledgerValue\" = ?, \"journalEntryId\" = ?,"
                            + " \"valuationBefore\" = ?, \"valuationAfter\" = ?, \"batchToken\" = ?, attempts = attempts + 1,"
                            + " error = NULL, \"postedById\" = ?, \"postedAt\" = now(), \"updatedAt\" = now() WHERE id = ?")
                    .params(ledgerValue, entry == null ? null : entry.get("id"), before, after, expected, user, batchId)
                    .update();
            refreshRunStatus(runId);
            audit.recordInTx(AuditInput.of("OpeningStockImportBatch", batchId, "post", null, Obj.of("runCode",
                    run.get("runCode"), "batchNo", batch.get("batchNo"), "rows", rows.size(), "ledgerValue",
                    JsNumbers.dec(ledgerValue), "journalEntryId", entry == null ? null : entry.get("id"))));
        });
    }

    private static Row asRow(Resolved r) {
        return new Row(r.rowNo(), r.productId(), null, r.variantId(), null, r.locationId(), null,
                r.quantity().toPlainString(), r.rawUnitCost(), r.batchNumber(), r.expiryDate(), r.serialNumbers());
    }

    private void lockLocation(String org, String locationId) {
        String key = StockService.freezeLockKey(org, locationId);
        long deadline = System.nanoTime() + FREEZE_WAIT.toNanos();
        while (!Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext(?)::bigint)").param(key)
                .query(Boolean.class).single())) {
            if (System.nanoTime() > deadline) {
                throw ApiException.conflict("Stock is moving at this location right now; retry the batch in a moment");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.conflict("Stock is moving at this location right now; retry the batch in a moment");
            }
        }
    }

    private BigDecimal valueAt(String locationId, List<String> productIds) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> r : valuation.currentValueByQuant(locationId, productIds)) {
            sum = sum.add(SqlRows.dec(r, "value"));
        }
        return sum;
    }

    private void recordFailure(String runId, String batchId, String error) {
        tx.executeWithoutResult(s -> {
            jdbc.sql("SELECT id FROM \"OpeningStockImportRun\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(runId, org()).query().listOfRows();
            jdbc.sql("UPDATE \"OpeningStockImportBatch\" SET status = 'failed', error = ?, attempts = attempts + 1,"
                            + " \"updatedAt\" = now() WHERE id = ? AND \"organizationId\" = ? AND status <> 'posted'")
                    .params(error, batchId, org()).update();
            refreshRunStatus(runId);
        });
    }

    /** Run status from its batches (caller holds the run row lock). */
    private void refreshRunStatus(String runId) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT count(*) FILTER (WHERE status = 'posted') AS posted,"
                + " count(*) FILTER (WHERE status = 'failed') AS failed, count(*) AS total FROM \"OpeningStockImportBatch\""
                + " WHERE \"runId\" = ?").param(runId));
        long posted = ((Number) c.get("posted")).longValue();
        long failed = ((Number) c.get("failed")).longValue();
        long total = ((Number) c.get("total")).longValue();
        String status = posted == total ? "completed" : failed > 0 ? (posted > 0 ? "partial" : "failed") : "posting";
        jdbc.sql("UPDATE \"OpeningStockImportRun\" SET status = ?, \"completedAt\" = CASE WHEN ? = 'completed' THEN now()"
                + " ELSE NULL END, \"updatedAt\" = now() WHERE id = ?").params(status, status, runId).update();
    }

    // ================================================================== retry

    /**
     * Post a failed (or never posted) batch again. With {@code batchToken} the caller approves the batch as previewed
     * again ({@link #batch} shows the revalidation of a failed batch); without it the stored token must still match.
     */
    public Map<String, Object> retryBatch(String runId, String batchId, String batchToken, Boolean approve) {
        if (!Boolean.TRUE.equals(approve)) {
            throw ApiException.badRequest("Approve the batch journal (approve: true) to post it again");
        }
        Map<String, Object> b = read(() -> batchRow(runId, batchId));
        if ("posted".equals(b.get("status"))) {
            throw ApiException.conflict("Batch " + b.get("batchNo") + " is already posted");
        }
        try {
            postBatch(runId, batchId, trim(batchToken));
        } catch (ApiException e) {
            if (e.getMessage() != null && e.getMessage().contains("is already posted")) {
                throw e;
            }
            recordFailure(runId, batchId, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            recordFailure(runId, batchId, message(e));
            throw ApiException.conflict("Batch " + b.get("batchNo") + " failed again: " + message(e));
        }
        return run(runId);
    }

    // ================================================================== reads

    private Map<String, Object> batchRow(String runId, String batchId) {
        assertRunVisible(runId);
        Map<String, Object> b = SqlRows.one(jdbc.sql("SELECT id, \"runId\", \"batchNo\", \"locationId\", status,"
                        + " \"importHash\", \"batchToken\", \"rowCount\", \"totalValue\", \"ledgerValue\", \"journalEntryId\","
                        + " \"valuationBefore\", \"valuationAfter\", attempts, error, \"postedById\", \"postedAt\", \"createdAt\","
                        + " \"updatedAt\" FROM \"OpeningStockImportBatch\" WHERE id = ? AND \"runId\" = ? AND \"organizationId\" = ?")
                .params(batchId, runId, org()));
        if (b == null) {
            throw ApiException.notFound("Opening-stock import batch not found");
        }
        return b;
    }

    private Map<String, Object> runRow(String runId) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT id, \"runCode\", status, \"importHash\", \"previewToken\","
                + " \"fileName\", notes, \"rowCount\", \"batchCount\", \"totalValue\", \"approvalRequestId\", \"approvedById\","
                + " \"approvedAt\", \"completedAt\", \"createdBy\", \"createdAt\", \"updatedAt\" FROM \"OpeningStockImportRun\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(runId, org()));
        if (r == null) {
            throw ApiException.notFound("Opening-stock import run not found");
        }
        return r;
    }

    private void assertRunVisible(String runId) {
        runRow(runId);
        if (scope.restricted()) {
            jdbc.sql("SELECT DISTINCT \"locationId\" FROM \"OpeningStockImportBatch\" WHERE \"runId\" = ? AND"
                            + " \"organizationId\" = ?").params(runId, org()).query(String.class).list()
                    .forEach(scope::assertAllowed);
        }
    }

    /** The organisation's import runs, newest first (a restricted user sees runs within their locations). */
    public List<Map<String, Object>> runs() {
        return read(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, \"runCode\", status, \"fileName\", \"rowCount\","
                    + " \"batchCount\", \"totalValue\", \"approvedById\", \"approvedAt\", \"completedAt\", \"createdAt\""
                    + " FROM \"OpeningStockImportRun\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\" DESC LIMIT 200")
                    .param(org()))) {
                if (scope.restricted() && jdbc.sql("SELECT DISTINCT \"locationId\" FROM \"OpeningStockImportBatch\""
                                + " WHERE \"runId\" = ?").param(r.get("id")).query(String.class).list().stream()
                        .anyMatch(l -> !scope.permits(l))) {
                    continue;
                }
                out.add(runView(r));
            }
            return out;
        });
    }

    private static Map<String, Object> runView(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.put("totalValue", JsNumbers.dec(SqlRows.dec(r, "totalValue")));
        return m;
    }

    /** A run with its batches (rows left out: see {@link #batch}). */
    public Map<String, Object> run(String runId) {
        return read(() -> {
            assertRunVisible(runId);
            Map<String, Object> out = runView(runRow(runId));
            out.remove("importHash");
            List<Map<String, Object>> batches = new ArrayList<>();
            for (Map<String, Object> b : SqlRows.list(jdbc.sql("SELECT b.id, b.\"batchNo\", b.\"locationId\","
                    + " l.name AS \"locationName\", b.status, b.\"rowCount\", b.\"totalValue\", b.\"ledgerValue\","
                    + " b.\"journalEntryId\", b.attempts, b.error, b.\"postedAt\" FROM \"OpeningStockImportBatch\" b"
                    + " LEFT JOIN \"InventoryLocation\" l ON l.id = b.\"locationId\" WHERE b.\"runId\" = ? AND"
                    + " b.\"organizationId\" = ? ORDER BY b.\"batchNo\"").params(runId, org()))) {
                batches.add(moneyFields(b, "totalValue", "ledgerValue"));
            }
            out.put("batches", batches);
            return out;
        });
    }

    private static Map<String, Object> moneyFields(Map<String, Object> row, String... keys) {
        Map<String, Object> m = new LinkedHashMap<>(row);
        for (String k : keys) {
            m.put(k, row.get(k) == null ? null : JsNumbers.dec(SqlRows.dec(row, k)));
        }
        return m;
    }

    /**
     * A batch with its rows, ledger codes and, while it is not posted, its revalidation now ({@code revalidation}: the
     * batch previewed again — valid, errors, the new {@code batchToken} and journal — to approve after a stale refusal).
     */
    public Map<String, Object> batch(String runId, String batchId) {
        String periodError = periodError();
        return read(() -> {
            Map<String, Object> b = batchRow(runId, batchId);
            Map<String, Object> out = moneyFields(b, "totalValue", "ledgerValue", "valuationBefore", "valuationAfter");
            String rowsJson = jdbc.sql("SELECT rows::text FROM \"OpeningStockImportBatch\" WHERE id = ?").param(batchId)
                    .query(String.class).single();
            List<Resolved> rows = mapper.readValue(rowsJson, new TypeReference<List<Resolved>>() {});
            out.put("rows", rows.stream().map(r -> rowView(r, null, null)).toList());
            out.put("ledger", SqlRows.list(jdbc.sql("SELECT \"ledgerCode\", \"productId\", \"variantId\", \"quantityChange\","
                    + " \"unitCost\", \"totalValue\", \"batchId\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                    + " \"referenceType\" = ? AND \"referenceId\" = ? ORDER BY \"ledgerCode\"")
                    .params(org(), REFERENCE_TYPE, batchId)).stream()
                    .map(l -> moneyFields(l, "quantityChange", "unitCost", "totalValue")).toList());
            if (!"posted".equals(b.get("status"))) {
                List<String> own = jdbc.sql("SELECT id FROM \"OpeningStockImportBatch\" WHERE \"runId\" = ?").param(runId)
                        .query(String.class).list();
                Validation v = validate(rows.stream().map(OpeningStockImportService::asRow).toList(), own, periodError);
                Map<String, Object> view = previewView(v);
                boolean same = v.valid() && v.batches().size() == 1
                        && v.batches().getFirst().token().equals(b.get("batchToken"));
                Map<String, Object> reval = new LinkedHashMap<>();
                reval.put("valid", view.get("valid"));
                reval.put("stale", !same);
                reval.put("batchToken", v.valid() && v.batches().size() == 1 ? v.batches().getFirst().token() : null);
                reval.put("errors", view.get("errors"));
                reval.put("globalErrors", view.get("globalErrors"));
                reval.put("journal", ((List<?>) view.get("batches")).isEmpty() ? null
                        : ((Map<?, ?>) ((List<?>) view.get("batches")).getFirst()).get("journal"));
                out.put("revalidation", reval);
            }
            out.remove("importHash");
            return out;
        });
    }

    /**
     * R5 reconciliation of a run: per batch Σ ledger value (rows {@code opening_stock_import} / batch id) = journal
     * amount (stock-account debits of the batch's entry, equal to its equity credit) = valuation delta recorded at
     * posting; the run is reconciled when every batch is posted and every difference is within 0.01.
     */
    public Map<String, Object> reconciliation(String runId) {
        return read(() -> {
            assertRunVisible(runId);
            Map<String, Object> run = runRow(runId);
            String org = org();
            List<String> stockAccounts = valuation.inventoryAccountIds();
            BigDecimal tol = new BigDecimal("0.01");
            BigDecimal sumLedger = BigDecimal.ZERO;
            BigDecimal sumJournal = BigDecimal.ZERO;
            BigDecimal sumEquity = BigDecimal.ZERO;
            BigDecimal sumDelta = BigDecimal.ZERO;
            BigDecimal sumExpected = BigDecimal.ZERO;
            boolean allPosted = true;
            boolean allOk = true;
            List<Map<String, Object>> batches = new ArrayList<>();
            for (Map<String, Object> b : SqlRows.list(jdbc.sql("SELECT id, \"batchNo\", \"locationId\", status,"
                    + " \"totalValue\", \"valuationBefore\", \"valuationAfter\", \"journalEntryId\" FROM"
                    + " \"OpeningStockImportBatch\" WHERE \"runId\" = ? AND \"organizationId\" = ? ORDER BY \"batchNo\"")
                    .params(runId, org))) {
                String id = (String) b.get("id");
                Map<String, Object> led = SqlRows.one(jdbc.sql("SELECT COALESCE(SUM(\"totalValue\"), 0) AS value,"
                        + " COALESCE(SUM(\"quantityChange\"), 0) AS qty, count(*) AS rows FROM \"InventoryLedger\" WHERE"
                        + " \"organizationId\" = ? AND \"referenceType\" = ? AND \"referenceId\" = ?")
                        .params(org, REFERENCE_TYPE, id));
                Map<String, Object> gl = SqlRows.one(jdbc.sql("SELECT COALESCE(SUM(l.debit) FILTER (WHERE l.\"accountId\" ="
                        + " ANY(?)), 0) AS stock, COALESCE(SUM(l.credit) FILTER (WHERE NOT (l.\"accountId\" = ANY(?))), 0) AS"
                        + " equity, COALESCE(SUM(l.debit), 0) AS dr, COALESCE(SUM(l.credit), 0) AS cr, count(DISTINCT e.id)"
                        + " AS entries FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON l.\"journalEntryId\" = e.id WHERE"
                        + " e.\"organizationId\" = ? AND e.\"sourceType\" = ? AND e.\"sourceId\" = ?")
                        .params(arr(stockAccounts), arr(stockAccounts), org, REFERENCE_TYPE, id));
                BigDecimal ledger = SqlRows.dec(led, "value");
                BigDecimal journal = SqlRows.dec(gl, "stock");
                BigDecimal equity = SqlRows.dec(gl, "equity");
                BigDecimal delta = b.get("valuationAfter") == null ? BigDecimal.ZERO
                        : SqlRows.dec(b, "valuationAfter").subtract(SqlRows.dec(b, "valuationBefore"));
                boolean posted = "posted".equals(b.get("status"));
                boolean ok = posted && ledger.subtract(journal).abs().compareTo(tol) <= 0
                        && journal.subtract(equity).abs().compareTo(tol) <= 0
                        && ledger.subtract(delta).abs().compareTo(tol) <= 0
                        && SqlRows.dec(gl, "dr").subtract(SqlRows.dec(gl, "cr")).abs().compareTo(tol) <= 0
                        && ledger.subtract(SqlRows.dec(b, "totalValue")).abs().compareTo(tol) <= 0;
                allPosted &= posted;
                allOk &= ok;
                sumLedger = sumLedger.add(ledger);
                sumJournal = sumJournal.add(journal);
                sumEquity = sumEquity.add(equity);
                sumDelta = sumDelta.add(delta);
                sumExpected = sumExpected.add(SqlRows.dec(b, "totalValue"));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("batchId", id);
                m.put("batchNo", b.get("batchNo"));
                m.put("locationId", b.get("locationId"));
                m.put("status", b.get("status"));
                m.put("previewedValue", JsNumbers.dec(SqlRows.dec(b, "totalValue")));
                m.put("ledgerValue", JsNumbers.dec(ledger));
                m.put("ledgerQuantity", JsNumbers.dec(SqlRows.dec(led, "qty")));
                m.put("ledgerRows", ((Number) led.get("rows")).longValue());
                m.put("journalAmount", JsNumbers.dec(journal));
                m.put("equityCredit", JsNumbers.dec(equity));
                m.put("journalEntries", ((Number) gl.get("entries")).longValue());
                m.put("valuationDelta", JsNumbers.dec(delta));
                m.put("ok", ok);
                batches.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runId", runId);
            out.put("runCode", run.get("runCode"));
            out.put("status", run.get("status"));
            out.put("complete", allPosted);
            out.put("reconciled", allPosted && allOk);
            out.put("tolerance", "0.01");
            out.put("previewedValue", JsNumbers.dec(sumExpected));
            out.put("ledgerValue", JsNumbers.dec(sumLedger));
            out.put("journalAmount", JsNumbers.dec(sumJournal));
            out.put("equityCredit", JsNumbers.dec(sumEquity));
            out.put("valuationDelta", JsNumbers.dec(sumDelta));
            out.put("batches", batches);
            return out;
        });
    }
}

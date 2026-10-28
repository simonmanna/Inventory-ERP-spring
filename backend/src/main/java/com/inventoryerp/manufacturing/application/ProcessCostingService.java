package com.inventoryerp.manufacturing.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.StandardCostService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.ProcessCostCalculator.Input;
import com.inventoryerp.manufacturing.application.ProcessCostCalculator.Result;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Process costing (ADR-S008 §2) for continuous production (cement, blocks, beverages): costs accumulate per process
 * and period instead of per production order.
 * <ul>
 *   <li><b>Inputs</b>: materials issued from stock into the process (Dr WIP / Cr stock at the materials' costing
 *       method), labour and overhead applied (Dr WIP / Cr the given applied-cost account, or recorded only when the
 *       cost is already in WIP), and the transferred-in cost of the previous process.</li>
 *   <li><b>Close</b> ({@link ProcessCostCalculator}, weighted average or FIFO): the completed cost moves to the next
 *       process (transferred-in, no GL — one WIP account) or, for the last process, into stock of the output product
 *       (Dr stock / Cr WIP; at standard with a yield variance when the product is standard-governed); abnormal loss
 *       is written off (Dr abnormal process loss / Cr WIP); closing WIP opens the next period.</li>
 * </ul>
 */
@Service
public class ProcessCostingService {

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    /** Process body. */
    public record ProcessInput(String code, String name, Integer sequence, String method, String nextProcessId,
            String outputProductId, String outputLocationId, String costCenterId, Boolean isActive, String notes) {}

    /** Period units body (any field null = unchanged). */
    public record UnitsInput(BigDecimal unitsStarted, BigDecimal unitsCompleted, BigDecimal closingUnits,
            BigDecimal closingMaterialPct, BigDecimal closingConversionPct, BigDecimal normalLossUnits,
            BigDecimal abnormalLossUnits, BigDecimal abnormalMaterialPct, BigDecimal abnormalConversionPct,
            BigDecimal openingUnits, BigDecimal openingMaterialPct, BigDecimal openingConversionPct,
            BigDecimal openingMaterialCost, BigDecimal openingConversionCost, BigDecimal openingTransferredInCost) {}

    private final JdbcClient jdbc;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final StandardCostService standards;
    private final AuditService audit;

    public ProcessCostingService(JdbcClient jdbc, StockService stock, StockPostingService stockPosting,
            PostingService posting, AccountResolverService accounts, StandardCostService standards, AuditService audit) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.posting = posting;
        this.accounts = accounts;
        this.standards = standards;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    // ------------------------------------------------------------------ processes

    @Transactional(readOnly = true)
    public List<Map<String, Object>> processes() {
        return SqlRows.list(jdbc.sql("SELECT p.*, n.code AS \"nextProcessCode\", pr.name AS \"outputProductName\","
                + " (SELECT id FROM \"ProcessCostPeriod\" o WHERE o.\"processId\" = p.id AND o.status = 'open' LIMIT 1)"
                + " AS \"openPeriodId\" FROM \"CostProcess\" p LEFT JOIN \"CostProcess\" n ON n.id = p.\"nextProcessId\""
                + " LEFT JOIN \"Product\" pr ON pr.id = p.\"outputProductId\" WHERE p.\"organizationId\" = ?"
                + " ORDER BY p.sequence, p.code").param(org()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> process(String id) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT * FROM \"CostProcess\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (p == null) {
            throw ApiException.notFound("Process not found");
        }
        p.put("periods", SqlRows.list(jdbc.sql("SELECT * FROM \"ProcessCostPeriod\" WHERE \"organizationId\" = ? AND"
                + " \"processId\" = ? ORDER BY \"periodFrom\" DESC LIMIT 60").params(org(), id)));
        return p;
    }

    @Transactional
    public Map<String, Object> createProcess(ProcessInput in) {
        if (in.code() == null || in.code().isBlank() || in.name() == null || in.name().isBlank()) {
            throw ApiException.badRequest("code and name are required");
        }
        validateProcess(in, null);
        long dup = jdbc.sql("SELECT count(*) FROM \"CostProcess\" WHERE \"organizationId\" = ? AND code = ?")
                .params(org(), in.code()).query(Long.class).single();
        if (dup > 0) {
            throw ApiException.conflict("Process " + in.code() + " already exists");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostProcess\" (id, \"organizationId\", code, name, sequence, method, \"nextProcessId\","
                        + " \"outputProductId\", \"outputLocationId\", \"costCenterId\", \"isActive\", notes) VALUES (?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), in.code(), in.name(), in.sequence() == null ? 1 : in.sequence(),
                        in.method() == null ? "weighted_average" : in.method(), blank(in.nextProcessId()),
                        blank(in.outputProductId()), blank(in.outputLocationId()), blank(in.costCenterId()),
                        in.isActive() == null || in.isActive(), in.notes())
                .update();
        return process(id);
    }

    @Transactional
    public Map<String, Object> updateProcess(String id, ProcessInput in) {
        process(id);
        validateProcess(in, id);
        jdbc.sql("UPDATE \"CostProcess\" SET name = COALESCE(?, name), sequence = COALESCE(?, sequence), method ="
                        + " COALESCE(?, method), \"nextProcessId\" = COALESCE(?, \"nextProcessId\"), \"outputProductId\" ="
                        + " COALESCE(?, \"outputProductId\"), \"outputLocationId\" = COALESCE(?, \"outputLocationId\"),"
                        + " \"costCenterId\" = COALESCE(?, \"costCenterId\"), \"isActive\" = COALESCE(?, \"isActive\"),"
                        + " notes = COALESCE(?, notes), \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(in.name(), in.sequence(), in.method(), blank(in.nextProcessId()), blank(in.outputProductId()),
                        blank(in.outputLocationId()), blank(in.costCenterId()), in.isActive(), in.notes(), id, org())
                .update();
        return process(id);
    }

    private void validateProcess(ProcessInput in, String selfId) {
        if (in.method() != null && !"weighted_average".equals(in.method()) && !"fifo".equals(in.method())) {
            throw ApiException.badRequest("method must be one of the following values: weighted_average, fifo");
        }
        if (blank(in.nextProcessId()) != null) {
            if (in.nextProcessId().equals(selfId)) {
                throw ApiException.badRequest("A process cannot feed itself");
            }
            long n = jdbc.sql("SELECT count(*) FROM \"CostProcess\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(in.nextProcessId(), org()).query(Long.class).single();
            if (n == 0) {
                throw ApiException.badRequest("Next process not found");
            }
        }
        if (blank(in.outputProductId()) != null) {
            long n = jdbc.sql("SELECT count(*) FROM \"Product\" WHERE id = ? AND \"organizationId\" = ? AND"
                    + " \"deletedAt\" IS NULL").params(in.outputProductId(), org()).query(Long.class).single();
            if (n == 0) {
                throw ApiException.badRequest("Output product not found");
            }
        }
    }

    // ------------------------------------------------------------------ periods

    /** Open the process's first period (later periods open automatically when one closes). */
    @Transactional
    public Map<String, Object> openPeriod(String processId, LocalDate from, LocalDate to) {
        process(processId);
        if (to.isBefore(from)) {
            throw ApiException.badRequest("periodTo must not be before periodFrom");
        }
        long open = jdbc.sql("SELECT count(*) FROM \"ProcessCostPeriod\" WHERE \"processId\" = ? AND status = 'open'")
                .param(processId).query(Long.class).single();
        if (open > 0) {
            throw ApiException.conflict("The process already has an open period; close it first");
        }
        return period(insertPeriod(processId, from, to, null));
    }

    private String insertPeriod(String processId, LocalDate from, LocalDate to, Map<String, Object> opening) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProcessCostPeriod\" (id, \"organizationId\", \"processId\", \"periodFrom\", \"periodTo\","
                        + " \"openingUnits\", \"openingMaterialPct\", \"openingConversionPct\", \"openingMaterialCost\","
                        + " \"openingConversionCost\", \"openingTransferredInCost\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), processId, from, to, op(opening, "units"), op(opening, "materialPct"),
                        op(opening, "conversionPct"), op(opening, "material"), op(opening, "conversion"),
                        op(opening, "transferredIn"))
                .update();
        return id;
    }

    private static Object op(Map<String, Object> opening, String k) {
        return opening == null || opening.get(k) == null ? BigDecimal.ZERO : opening.get(k);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> period(String id) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT pp.*, c.code AS \"processCode\", c.name AS"
                + " \"processName\", c.method FROM \"ProcessCostPeriod\" pp JOIN \"CostProcess\" c ON c.id = pp.\"processId\""
                + " WHERE pp.id = ? AND pp.\"organizationId\" = ?").params(id, org()));
        if (p == null) {
            throw ApiException.notFound("Process period not found");
        }
        p.put("entries", SqlRows.list(jdbc.sql("SELECT e.*, pr.name AS \"productName\" FROM \"ProcessCostEntry\" e"
                + " LEFT JOIN \"Product\" pr ON pr.id = e.\"productId\" WHERE e.\"organizationId\" = ? AND"
                + " e.\"periodId\" = ? ORDER BY e.\"createdAt\"").params(org(), id)));
        Map<String, BigDecimal> totals = totals(id);
        Map<String, String> t = new LinkedHashMap<>();
        totals.forEach((k, v) -> t.put(k, JsNumbers.dec(v)));
        p.put("costsAdded", t);
        return p;
    }

    @Transactional
    public Map<String, Object> updateUnits(String id, UnitsInput in) {
        Map<String, Object> p = lockOpen(id);
        jdbc.sql("UPDATE \"ProcessCostPeriod\" SET \"unitsStarted\" = COALESCE(?, \"unitsStarted\"), \"unitsCompleted\" ="
                        + " COALESCE(?, \"unitsCompleted\"), \"closingUnits\" = COALESCE(?, \"closingUnits\"),"
                        + " \"closingMaterialPct\" = COALESCE(?, \"closingMaterialPct\"), \"closingConversionPct\" ="
                        + " COALESCE(?, \"closingConversionPct\"), \"normalLossUnits\" = COALESCE(?, \"normalLossUnits\"),"
                        + " \"abnormalLossUnits\" = COALESCE(?, \"abnormalLossUnits\"), \"abnormalMaterialPct\" ="
                        + " COALESCE(?, \"abnormalMaterialPct\"), \"abnormalConversionPct\" = COALESCE(?,"
                        + " \"abnormalConversionPct\"), \"openingUnits\" = COALESCE(?, \"openingUnits\"),"
                        + " \"openingMaterialPct\" = COALESCE(?, \"openingMaterialPct\"), \"openingConversionPct\" ="
                        + " COALESCE(?, \"openingConversionPct\"), \"openingMaterialCost\" = COALESCE(?,"
                        + " \"openingMaterialCost\"), \"openingConversionCost\" = COALESCE(?, \"openingConversionCost\"),"
                        + " \"openingTransferredInCost\" = COALESCE(?, \"openingTransferredInCost\"), \"updatedAt\" = "
                        + NOW + " WHERE id = ?")
                .params(in.unitsStarted(), in.unitsCompleted(), in.closingUnits(), in.closingMaterialPct(),
                        in.closingConversionPct(), in.normalLossUnits(), in.abnormalLossUnits(), in.abnormalMaterialPct(),
                        in.abnormalConversionPct(), in.openingUnits(), in.openingMaterialPct(), in.openingConversionPct(),
                        in.openingMaterialCost(), in.openingConversionCost(), in.openingTransferredInCost(), p.get("id"))
                .update();
        return period(id);
    }

    /** Issue a material from stock into the process: Dr WIP / Cr stock at the material's cost. */
    @Transactional
    public Map<String, Object> issueMaterial(String periodId, String productId, String locationId, BigDecimal quantity,
            String description) {
        Map<String, Object> p = lockOpen(periodId);
        if (quantity == null || quantity.signum() <= 0) {
            throw ApiException.badRequest("Quantity must be positive");
        }
        String source = (String) p.get("processCode") + ":" + periodId;
        StockMove.Issued issued = stock.issue(StockMove.of(productId, locationId, quantity).skipGl()
                .moveType("production_consume").source("process_costing", source));
        BigDecimal value = issued.totalValue().setScale(6, RoundingMode.HALF_UP);
        Instant date = postingDate(p);
        stockPosting.postProductionConsume(productId, value, date, "process_costing", source,
                "Process " + p.get("processCode") + " · material issue");
        insertEntry(periodId, "material", value, issued.baseQuantity(), productId, locationId, "stock_issue",
                issued.ledgerCode(), description, null);
        return period(periodId);
    }

    /**
     * Labour / overhead applied to the process. With {@code creditAccountId}: Dr WIP / Cr that account (e.g. wages
     * or overhead applied); without: recorded only (the cost is already in WIP).
     */
    @Transactional
    public Map<String, Object> addCost(String periodId, String element, BigDecimal amount, String creditAccountId,
            String description) {
        Map<String, Object> p = lockOpen(periodId);
        if (!List.of("labour", "overhead", "material").contains(element)) {
            throw ApiException.badRequest("element must be one of the following values: labour, overhead, material");
        }
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.badRequest("Amount must be positive");
        }
        String je = null;
        if (blank(creditAccountId) != null) {
            String label = "Process " + p.get("processCode") + " · " + element + (description != null ? " · "
                    + description : "");
            Map<String, Object> posted = posting.post(PostingRequest.of("INV", postingDate(p), label, "process_costing",
                    p.get("processCode") + ":" + periodId, null, List.of(
                            new Line(stockPosting.wipAccount(), JsNumbers.dec(amount), null, null, label, null,
                                    (String) p.get("costCenterId"), null),
                            Line.credit(creditAccountId, JsNumbers.dec(amount), label))));
            je = posted == null ? null : (String) posted.get("id");
        }
        insertEntry(periodId, element, amount.setScale(6, RoundingMode.HALF_UP), null, null, null,
                je == null ? "recorded" : "journal", je, description, je);
        return period(periodId);
    }

    /** What closing would compute now (no writes). */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String periodId) {
        Map<String, Object> p = period(periodId);
        return Obj.of("period", p, "result", compute(p).toMap());
    }

    /** Close: compute, move completed cost on (next process or stock), write off abnormal loss, open next period. */
    @Transactional
    public Map<String, Object> close(String periodId) {
        Map<String, Object> p = lockOpen(periodId);
        Map<String, Object> full = period(periodId);
        Map<String, Object> proc = process((String) p.get("processId"));
        Result r = compute(full);
        Instant date = postingDate(p);
        String code = (String) proc.get("code");
        String source = code + ":" + periodId;
        BigDecimal completed = r.completedCost();
        BigDecimal unitsCompleted = SqlRows.dec(full, "unitsCompleted");
        List<String> journals = new ArrayList<>();
        String wip = stockPosting.wipAccount();

        if (r.abnormalLossCost().signum() > 0) {
            String loss = accounts.ensureByCode("5970", "Abnormal Process Loss", "cost_of_goods_sold",
                    "abnormal_process_loss");
            String label = "Process " + code + " · abnormal loss";
            Map<String, Object> je = posting.post(PostingRequest.of("INV", date, label, "process_costing", source,
                    "manufacturing:process_abnormal:" + org() + ":" + periodId, List.of(
                            new Line(loss, JsNumbers.dec(r.abnormalLossCost()), null, null, label, null,
                                    (String) proc.get("costCenterId"), null),
                            Line.credit(wip, JsNumbers.dec(r.abnormalLossCost()), label))));
            journals.add((String) je.get("id"));
        }

        String nextId = (String) proc.get("nextProcessId");
        if (nextId != null && unitsCompleted.signum() > 0) {
            // Same WIP account: the transfer is a sub-ledger movement, no journal.
            String nextPeriod = jdbc.sql("SELECT id FROM \"ProcessCostPeriod\" WHERE \"processId\" = ? AND status = 'open'")
                    .param(nextId).query(String.class).optional().orElse(null);
            if (nextPeriod == null) {
                nextPeriod = insertPeriod(nextId, (LocalDate) p.get("periodFrom"), (LocalDate) p.get("periodTo"), null);
            }
            insertEntry(nextPeriod, "transferred_in", completed, unitsCompleted, null, null, "process_transfer",
                    periodId, "From " + code, null);
            jdbc.sql("UPDATE \"ProcessCostPeriod\" SET \"unitsStarted\" = \"unitsStarted\" + ?, \"updatedAt\" = " + NOW
                    + " WHERE id = ?").params(unitsCompleted, nextPeriod).update();
        } else if (unitsCompleted.signum() > 0) {
            String productId = (String) proc.get("outputProductId");
            String locationId = (String) proc.get("outputLocationId");
            if (productId == null || locationId == null) {
                throw ApiException.badRequest("Process " + code + " is the last process: set its output product and"
                        + " output location before closing");
            }
            BigDecimal std = standards.activeStandard(productId).orElse(null);
            BigDecimal unit = std != null ? std : r.unitCost();
            BigDecimal value = std != null ? std.multiply(unitsCompleted).setScale(6, RoundingMode.HALF_UP) : completed;
            stock.receive(StockMove.of(productId, locationId, unitsCompleted).unitCost(unit)
                    .moveType("production_output").source("process_costing", source));
            if (value.signum() > 0) {
                stockPosting.postProductionOutput(productId, value, date, "process_costing", source,
                        "Process " + code + " · output");
            }
            if (std != null && completed.subtract(value).signum() != 0) {
                String je = standards.postProductionVariances(periodId, code, productId, completed.subtract(value),
                        unitsCompleted, value, wip, date);
                if (je != null) {
                    journals.add(je);
                }
            }
        }

        jdbc.sql("UPDATE \"ProcessCostPeriod\" SET status = 'closed', result = ?::jsonb, \"completedCost\" = ?,"
                        + " \"closingWipCost\" = ?, \"abnormalLossCost\" = ?, \"unitCost\" = ?, \"journalEntryIds\" ="
                        + " ?::text[], \"closedAt\" = " + NOW + ", \"closedBy\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(EntityJson.toJson(r.toMap()), completed, r.closingWipCost(), r.abnormalLossCost(), r.unitCost(),
                        journals.toArray(String[]::new), TenantContext.userId(), periodId)
                .update();
        LocalDate from = (LocalDate) p.get("periodFrom");
        LocalDate to = (LocalDate) p.get("periodTo");
        long days = ChronoUnit.DAYS.between(from, to);
        Map<String, Object> opening = new LinkedHashMap<>();
        opening.put("units", SqlRows.dec(full, "closingUnits"));
        opening.put("materialPct", SqlRows.dec(full, "closingMaterialPct"));
        opening.put("conversionPct", SqlRows.dec(full, "closingConversionPct"));
        opening.put("material", r.closingWipByElement().get("material"));
        opening.put("conversion", r.closingWipByElement().get("conversion"));
        opening.put("transferredIn", r.closingWipByElement().get("transferredIn"));
        String next = insertPeriod((String) p.get("processId"), to.plusDays(1), to.plusDays(1 + days), opening);
        audit.record(AuditInput.of("ProcessCostPeriod", periodId, "update", null, Obj.of("kind",
                "process_period_closed", "completedCost", JsNumbers.dec(completed), "closingWip",
                JsNumbers.dec(r.closingWipCost()), "abnormalLoss", JsNumbers.dec(r.abnormalLossCost()))));
        Map<String, Object> out = period(periodId);
        out.put("nextPeriodId", next);
        return out;
    }

    /** Cost-of-production report: every closed period of every process in a window. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> report(LocalDate from, LocalDate to) {
        return SqlRows.list(jdbc.sql("SELECT pp.id, pp.\"periodFrom\", pp.\"periodTo\", c.code, c.name, c.method,"
                + " pp.\"openingUnits\", pp.\"unitsStarted\", pp.\"unitsCompleted\", pp.\"closingUnits\","
                + " pp.\"normalLossUnits\", pp.\"abnormalLossUnits\", pp.\"completedCost\", pp.\"closingWipCost\","
                + " pp.\"abnormalLossCost\", pp.\"unitCost\", pp.result FROM \"ProcessCostPeriod\" pp"
                + " JOIN \"CostProcess\" c ON c.id = pp.\"processId\" WHERE pp.\"organizationId\" = ? AND pp.status ="
                + " 'closed' AND pp.\"periodFrom\" >= ? AND pp.\"periodTo\" <= ? ORDER BY c.sequence, pp.\"periodFrom\"")
                .params(org(), from, to));
    }

    // ------------------------------------------------------------------ helpers

    private Result compute(Map<String, Object> p) {
        @SuppressWarnings("unchecked")
        Map<String, String> added = (Map<String, String>) p.get("costsAdded");
        return ProcessCostCalculator.compute(new Input((String) p.get("method"), SqlRows.dec(p, "openingUnits"),
                SqlRows.dec(p, "openingMaterialPct"), SqlRows.dec(p, "openingConversionPct"),
                SqlRows.dec(p, "openingTransferredInCost"), SqlRows.dec(p, "openingMaterialCost"),
                SqlRows.dec(p, "openingConversionCost"), SqlRows.dec(p, "unitsStarted"), SqlRows.dec(p, "unitsCompleted"),
                SqlRows.dec(p, "closingUnits"), SqlRows.dec(p, "closingMaterialPct"), SqlRows.dec(p, "closingConversionPct"),
                SqlRows.dec(p, "normalLossUnits"), SqlRows.dec(p, "abnormalLossUnits"),
                SqlRows.dec(p, "abnormalMaterialPct"), SqlRows.dec(p, "abnormalConversionPct"),
                new BigDecimal(added.get("transferredIn")), new BigDecimal(added.get("material")),
                new BigDecimal(added.get("conversion"))));
    }

    private Map<String, BigDecimal> totals(String periodId) {
        Map<String, BigDecimal> t = new LinkedHashMap<>();
        t.put("transferredIn", BigDecimal.ZERO);
        t.put("material", BigDecimal.ZERO);
        t.put("labour", BigDecimal.ZERO);
        t.put("overhead", BigDecimal.ZERO);
        for (Map<String, Object> r : jdbc.sql("SELECT element, SUM(amount) AS amount FROM \"ProcessCostEntry\" WHERE"
                + " \"organizationId\" = ? AND \"periodId\" = ? GROUP BY element").params(org(), periodId).query()
                .listOfRows()) {
            String k = "transferred_in".equals(r.get("element")) ? "transferredIn" : (String) r.get("element");
            t.put(k, Money.dec(r.get("amount")));
        }
        t.put("conversion", t.get("labour").add(t.get("overhead")));
        return t;
    }

    private void insertEntry(String periodId, String element, BigDecimal amount, BigDecimal qty, String productId,
            String locationId, String sourceType, String sourceId, String description, String je) {
        jdbc.sql("INSERT INTO \"ProcessCostEntry\" (id, \"organizationId\", \"periodId\", element, amount, quantity,"
                        + " \"productId\", \"locationId\", \"sourceType\", \"sourceId\", description, \"journalEntryId\","
                        + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), org(), periodId, element, amount, qty, productId, locationId,
                        sourceType, sourceId, description, je, TenantContext.userId())
                .update();
    }

    private Map<String, Object> lockOpen(String periodId) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT pp.*, c.code AS \"processCode\", c.\"costCenterId\" FROM"
                + " \"ProcessCostPeriod\" pp JOIN \"CostProcess\" c ON c.id = pp.\"processId\" WHERE pp.id = ? AND"
                + " pp.\"organizationId\" = ? FOR UPDATE OF pp").params(periodId, org()));
        if (p == null) {
            throw ApiException.notFound("Process period not found");
        }
        if (!"open".equals(p.get("status"))) {
            throw ApiException.badRequest("The process period is closed");
        }
        return p;
    }

    /** Postings land on the period's last day (or today while it is still running). */
    private static Instant postingDate(Map<String, Object> p) {
        LocalDate to = (LocalDate) p.get("periodTo");
        Instant end = to.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(43_200);
        Instant now = Instant.now();
        return end.isAfter(now) ? now : end;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}

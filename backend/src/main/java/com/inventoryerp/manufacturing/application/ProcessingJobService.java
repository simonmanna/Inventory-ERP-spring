package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.StockReservationService;
import com.inventoryerp.inventory.application.StockReservationService.Reserve;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateOrder;
import com.inventoryerp.manufacturing.application.ProcessingCommands.CreateJob;
import com.inventoryerp.manufacturing.application.ProcessingCommands.JobCostInput;
import com.inventoryerp.manufacturing.application.ProcessingCommands.JobInputLine;
import com.inventoryerp.manufacturing.application.ProcessingCommands.JobOutputLine;
import com.inventoryerp.manufacturing.application.ProcessingCommands.PostJob;
import com.inventoryerp.manufacturing.application.ProcessingCommands.RecordOutput;
import com.inventoryerp.manufacturing.application.StageCommands.FrozenOutput;
import com.inventoryerp.manufacturing.application.StageCommands.InitializeStages;
import com.inventoryerp.manufacturing.application.StageCommands.IssueLine;
import com.inventoryerp.manufacturing.application.StageCommands.OutputOverrides;
import com.inventoryerp.manufacturing.application.StageCommands.OutputQty;
import com.inventoryerp.manufacturing.application.StageCommands.StageConversion;
import com.inventoryerp.manufacturing.application.StageCommands.StageIssue;
import com.inventoryerp.manufacturing.application.StageCommands.StageOutput;
import com.inventoryerp.manufacturing.domain.LotGenealogyLink;
import com.inventoryerp.manufacturing.domain.ProcessingJob;
import com.inventoryerp.manufacturing.domain.ProcessingJobCost;
import com.inventoryerp.manufacturing.domain.ProcessingJobInput;
import com.inventoryerp.manufacturing.domain.ProcessingJobLoss;
import com.inventoryerp.manufacturing.domain.ProcessingJobOutput;
import com.inventoryerp.manufacturing.domain.ProcessingTemplateInput;
import com.inventoryerp.manufacturing.domain.ProcessingTemplateOutput;
import com.inventoryerp.manufacturing.domain.ProductionMaterial;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.ProductionOrderStage;
import com.inventoryerp.manufacturing.domain.ProductionOutput;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processing jobs (ADR-S060): one job is one processing step and one production order on the multi-stage engine, so
 * every output is ordinary stock that can be sold or used by the next job, and selling or stopping needs no special
 * handling. Stock, valuation and journals all go through the engine. Execution state lives on the order and its stage:
 * a job stores only its draft and cancel fields, and its status is derived on read.
 */
@Service
public class ProcessingJobService {

    static final String RESERVATION_SOURCE = "processing_job_input";
    static final BigDecimal TOLERANCE = new BigDecimal("0.001");
    private static final int SCALE = 6;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Pattern LOT_STEP = Pattern.compile("^(.*)-P(\\d+)$");

    /** The derived job status; the only definition of it (see the class comment). */
    static final String STATUS_SQL = """
            CASE WHEN j."cancelledAt" IS NOT NULL THEN 'cancelled'
                 WHEN j."orderId" IS NULL THEN 'draft'
                 WHEN o."reversedAt" IS NOT NULL THEN 'reversed'
                 WHEN o.status::text = 'completed' THEN 'completed'
                 WHEN o.status::text = 'qc_hold' THEN 'qc_hold'
                 WHEN s."qtyProcessed" > 0 OR s."outputCost" > 0 THEN 'partially_output'
                 ELSE 'in_progress' END""";

    private static final String SELECT = """
            SELECT j.*, %s AS status, t.code AS "templateCode", t.name AS "templateName", t."yieldBasisUnit",
                   t."expectedYieldPct", o."orderCode",
                   COALESCE(s."materialCost", 0) + COALESCE(s."transferredInCost", 0) + COALESCE(s."conversionCost", 0)
                       - COALESCE(s."outputCost", 0) - COALESCE(s."scrapCost", 0) AS "wipValue"
            FROM "ProcessingJob" j
            JOIN "ProcessingTemplate" t ON t.id = j."templateId"
            LEFT JOIN "ProductionOrder" o ON o.id = j."orderId"
            LEFT JOIN "ProductionOrderStage" s ON s.id = j."stageId"
            WHERE j."organizationId" = ? AND j."deletedAt" IS NULL""".formatted(STATUS_SQL);

    private final MfgDb db;
    private final SequenceService seq;
    private final AuditService audit;
    private final ProcessingTemplateService templates;
    private final ProductionService production;
    private final StageExecutionService stages;
    private final StockReservationService reservations;
    private final PostingService posting;

    public ProcessingJobService(MfgDb db, SequenceService seq, AuditService audit, ProcessingTemplateService templates,
            ProductionService production, StageExecutionService stages, StockReservationService reservations,
            PostingService posting) {
        this.db = db;
        this.seq = seq;
        this.audit = audit;
        this.templates = templates;
        this.production = production;
        this.stages = stages;
        this.reservations = reservations;
        this.posting = posting;
    }

    // ================================================================== read

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String status, String templateId, String search, Integer page,
            Integer pageSize) {
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        StringBuilder w = new StringBuilder();
        if (status != null && !status.isBlank()) {
            w.append(" AND ").append(STATUS_SQL).append(" = ?");
            params.add(status);
        }
        if (templateId != null && !templateId.isBlank()) {
            w.append(" AND j.\"templateId\" = ?");
            params.add(templateId);
        }
        if (search != null && !search.isBlank()) {
            w.append(" AND (j.code ILIKE ? OR t.name ILIKE ? OR EXISTS (SELECT 1 FROM \"ProcessingJobOutput\" x WHERE"
                    + " x.\"jobId\" = j.id AND x.\"batchNumber\" ILIKE ?) OR EXISTS (SELECT 1 FROM \"ProcessingJobInput\" x"
                    + " WHERE x.\"jobId\" = j.id AND x.\"batchNumber\" ILIKE ?))");
            String q = "%" + search.trim() + "%";
            params.addAll(List.of(q, q, q, q));
        }
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 25 : Math.min(pageSize, 200);
        String base = SELECT + w;
        long total = db.jdbc().sql("SELECT count(*) FROM (" + base + ") c").params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((p - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> j : SqlRows.list(db.jdbc().sql(base + " ORDER BY j.\"jobDate\" DESC, j.code DESC LIMIT ?"
                + " OFFSET ?").params(pageParams))) {
            Map<String, Object> m = new LinkedHashMap<>(j);
            Balance b = balance(str(j, "id"));
            m.put("inputBasis", b.input());
            m.put("outputBasis", b.output());
            m.put("lossBasis", b.loss());
            m.put("inputSummary", summary("ProcessingJobInput", str(j, "id")));
            m.put("outputSummary", summary("ProcessingJobOutput", str(j, "id")));
            rows.add(m);
        }
        return PageResult.of(rows, p, size, total);
    }

    private List<Map<String, Object>> summary(String table, String jobId) {
        return SqlRows.list(db.jdbc().sql("SELECT p.name AS \"productName\", l.\"batchNumber\" FROM \"" + table + "\" l JOIN"
                + " \"Product\" p ON p.id = l.\"productId\" WHERE l.\"organizationId\" = ? AND l.\"jobId\" = ? ORDER BY"
                + " l.\"createdAt\" LIMIT 4").params(MfgDb.org(), jobId));
    }

    /** A job with its lines, the reconciliation, the posted journal trail and its audit history. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> j = SqlRows.one(db.jdbc().sql(SELECT + " AND j.id = ?").params(MfgDb.org(), id));
        if (j == null) {
            throw ApiException.notFound("Processing job not found");
        }
        Map<String, Object> out = new LinkedHashMap<>(j);
        out.put("inputs", lineRows("ProcessingJobInput", id));
        out.put("outputs", lineRows("ProcessingJobOutput", id));
        out.put("costs", SqlRows.list(db.jdbc().sql("SELECT * FROM \"ProcessingJobCost\" WHERE \"organizationId\" = ? AND"
                + " \"jobId\" = ? ORDER BY \"createdAt\"").params(MfgDb.org(), id)));
        out.put("losses", SqlRows.list(db.jdbc().sql("SELECT * FROM \"ProcessingJobLoss\" WHERE \"organizationId\" = ? AND"
                + " \"jobId\" = ? ORDER BY \"createdAt\"").params(MfgDb.org(), id)));
        out.put("genealogy", SqlRows.list(db.jdbc().sql("""
                SELECT g.*, sp.name AS "sourceProductName", op.name AS "outputProductName"
                FROM "LotGenealogyLink" g JOIN "Product" sp ON sp.id = g."sourceProductId"
                JOIN "Product" op ON op.id = g."outputProductId"
                WHERE g."organizationId" = ? AND g."jobId" = ? ORDER BY g."createdAt\"""").params(MfgDb.org(), id)));
        Balance b = balance(id);
        BigDecimal commercialBase = b.input();
        BigDecimal unaccounted = b.input().subtract(b.output()).subtract(b.loss());
        BigDecimal commercial = null;
        List<Map<String, Object>> outputs = lineRows("ProcessingJobOutput", id);
        for (Map<String, Object> o : outputs) {
            if ("main".equals(o.get("role")) && commercialBase.signum() > 0) {
                commercial = dec(o, "quantity").divide(commercialBase, 4, RoundingMode.HALF_UP);
            }
        }
        boolean estimated = outputs.stream().anyMatch(o -> dec(o, "quantity").signum() > 0
                && "template_factor".equals(o.get("basisSource")) && o.get("basisFactor") != null);
        out.put("reconciliation", Obj.of("inputBasis", b.input(), "outputBasis", b.output(), "lossBasis", b.loss(),
                "unaccountedBasis", unaccounted, "massYieldPct", b.input().signum() == 0 ? null : b.output().multiply(HUNDRED)
                        .divide(b.input(), 4, RoundingMode.HALF_UP), "commercialYield", commercial, "basisUnit",
                j.get("yieldBasisUnit"), "estimated", estimated));
        return out;
    }

    private List<Map<String, Object>> lineRows(String table, String jobId) {
        return SqlRows.list(db.jdbc().sql("SELECT l.*, p.code AS \"productCode\", p.name AS \"productName\", p.\"uomId\","
                + " u.name AS \"uomName\" FROM \"" + table + "\" l JOIN \"Product\" p ON p.id = l.\"productId\" LEFT JOIN \"UnitOfMeasure\" u"
                + " ON u.id = p.\"uomId\" WHERE l.\"organizationId\" = ? AND l.\"jobId\" = ? ORDER BY l.\"createdAt\"")
                .params(MfgDb.org(), jobId));
    }

    private record Balance(BigDecimal input, BigDecimal output, BigDecimal loss) {}

    private Balance balance(String jobId) {
        BigDecimal in = db.jdbc().sql("SELECT coalesce(sum(\"basisQty\"), 0) FROM \"ProcessingJobInput\" WHERE"
                + " \"organizationId\" = ? AND \"jobId\" = ? AND role = 'traceable'").params(MfgDb.org(), jobId)
                .query(BigDecimal.class).single();
        BigDecimal out = db.jdbc().sql("SELECT coalesce(sum(\"basisQty\"), 0) FROM \"ProcessingJobOutput\" WHERE"
                + " \"organizationId\" = ? AND \"jobId\" = ?").params(MfgDb.org(), jobId).query(BigDecimal.class).single();
        BigDecimal loss = db.jdbc().sql("SELECT coalesce(sum(\"basisQty\"), 0) FROM \"ProcessingJobLoss\" WHERE"
                + " \"organizationId\" = ? AND \"jobId\" = ? AND \"reversedAt\" IS NULL").params(MfgDb.org(), jobId)
                .query(BigDecimal.class).single();
        return new Balance(in, out, loss);
    }

    // ================================================================== draft

    /** Create a draft and reserve its input, so no other movement can take it before the job starts. */
    @Transactional
    public Map<String, Object> create(CreateJob dto) {
        return get(createDraft(dto));
    }

    private String createDraft(CreateJob dto) {
        Map<String, Object> t = templates.require(dto.templateId());
        if (!"active".equals(t.get("status"))) {
            throw ApiException.badRequest("Template " + t.get("code") + " v" + t.get("version") + " is " + t.get("status")
                    + "; use its active version");
        }
        String location = dto.locationId() != null ? dto.locationId() : str(t, "defaultInputLocationId");
        String outputLocation = dto.outputLocationId() != null ? dto.outputLocationId()
                : str(t, "defaultOutputLocationId") != null ? str(t, "defaultOutputLocationId") : location;
        if (location == null) {
            throw ApiException.badRequest("Choose the location the input is taken from");
        }
        if (dto.reprocessOfJobId() != null) {
            require(dto.reprocessOfJobId());
        }
        Map<String, Map<String, Object>> byInput = new HashMap<>();
        db.children(ProcessingTemplateInput.class, "templateId", t.get("id"), null).forEach(i -> byInput.put(str(i, "id"), i));
        LocalDate date = dto.jobDate() != null ? LocalDate.parse(dto.jobDate().substring(0, 10))
                : LocalDate.now(ZoneOffset.UTC);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", seq.next("processing_job", "PJ-", 6));
        d.put("templateId", t.get("id"));
        d.put("templateVersion", t.get("version"));
        d.put("jobDate", Date.valueOf(date));
        d.put("locationId", location);
        d.put("outputLocationId", outputLocation);
        d.put("reprocessOfJobId", dto.reprocessOfJobId());
        d.put("notes", dto.notes());
        d.put("createdBy", TenantContext.userId());
        d.put("updatedBy", TenantContext.userId());
        Map<String, Object> job = db.insert(ProcessingJob.class, d);
        String id = str(job, "id");
        boolean anyTraceable = false;
        for (JobInputLine l : dto.inputs()) {
            Map<String, Object> ti = byInput.get(l.templateInputId());
            if (ti == null) {
                throw ApiException.badRequest("Input line " + l.templateInputId() + " does not belong to this template");
            }
            Map<String, Object> p = product(str(ti, "productId"));
            boolean traceable = "traceable".equals(ti.get("role"));
            anyTraceable |= traceable;
            if (traceable && Boolean.TRUE.equals(p.get("batchTracking")) && l.batchNumber() == null) {
                throw ApiException.badRequest("Choose a lot of " + p.get("name"));
            }
            BigDecimal factor = ti.get("basisFactor") == null ? BigDecimal.ONE : dec(ti, "basisFactor");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jobId", id);
            row.put("templateInputId", ti.get("id"));
            row.put("productId", ti.get("productId"));
            row.put("role", ti.get("role"));
            row.put("genealogyBasis", ti.get("genealogyBasis"));
            row.put("fixedPct", decOrNull(ti, "fixedPct"));
            row.put("batchNumber", l.batchNumber());
            row.put("plannedQty", l.quantity());
            row.put("basisQty", l.basisQty() != null ? l.basisQty() : scale(l.quantity().multiply(factor)));
            row.put("basisSource", l.basisQty() != null ? "measured" : "template_factor");
            row.put("manualSharePct", l.manualSharePct());
            Map<String, Object> input = db.insert(ProcessingJobInput.class, row);
            reserve(input, location, str(job, "code"), p);
        }
        if (!anyTraceable) {
            throw ApiException.badRequest("A job needs at least one traceable input");
        }
        audit.recordInTx(AuditInput.of("ProcessingJob", id, "create", null, Obj.of("code", job.get("code"), "templateId",
                t.get("id"), "version", t.get("version"))));
        return id;
    }

    private void reserve(Map<String, Object> input, String location, String jobCode, Map<String, Object> p) {
        String productId = str(input, "productId");
        BigDecimal qty = dec(input, "plannedQty");
        reservations.reserve(new Reserve(productId, null, location, qty, RESERVATION_SOURCE, str(input, "id"),
                "Processing job " + jobCode));
        // reserve() holds the StockItem row lock, so this check and any concurrent one are serialised.
        BigDecimal onHand = db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ? AND \"locationId\" = ?").params(MfgDb.org(), productId, location)
                .query(BigDecimal.class).single();
        BigDecimal reserved = reservations.reservedQty(productId, location, null);
        if (reserved.compareTo(onHand) > 0) {
            throw ApiException.badRequest("Insufficient unreserved stock for " + p.get("name") + ": on hand "
                    + JsNumbers.dec(onHand) + ", already reserved " + JsNumbers.dec(reserved.subtract(qty)) + ", requested "
                    + JsNumbers.dec(qty) + ".");
        }
        if (input.get("batchNumber") != null) {
            BigDecimal inLot = lotQty(productId, str(input, "batchNumber"), location);
            if (qty.compareTo(inLot) > 0) {
                throw ApiException.badRequest("Insufficient stock in lot " + input.get("batchNumber") + " of " + p.get("name")
                        + ": on hand " + JsNumbers.dec(inLot) + ", requested " + JsNumbers.dec(qty) + ".");
            }
        }
    }

    private BigDecimal lotQty(String productId, String batch, String location) {
        return db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"batchNumber\" = ? AND \"locationId\" = ? AND quantity > 0 AND \"isActive\" = true")
                .params(MfgDb.org(), productId, batch, location).query(BigDecimal.class).single();
    }

    /** Cancel a draft and release its reservations. */
    @Transactional
    public Map<String, Object> cancel(String id, String reason) {
        Map<String, Object> j = lock(id);
        draftOnly(j);
        releaseReservations(id);
        db.update(ProcessingJob.class, id, Obj.of("cancelledAt", Instant.now(), "cancelledBy", TenantContext.userId(),
                "cancelReason", reason, "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("ProcessingJob", id, "cancel", null, Obj.of("code", j.get("code"), "reason", reason)));
        return get(id);
    }

    private void releaseReservations(String jobId) {
        for (Map<String, Object> in : db.children(ProcessingJobInput.class, "jobId", jobId, null)) {
            reservations.release(RESERVATION_SOURCE, str(in, "id"));
        }
    }

    private static void draftOnly(Map<String, Object> j) {
        if (j.get("cancelledAt") != null) {
            throw ApiException.badRequest("Job " + j.get("code") + " is cancelled");
        }
        if (j.get("orderId") != null) {
            throw ApiException.badRequest("Job " + j.get("code") + " has started");
        }
    }

    // ================================================================== start

    /**
     * Start: creates and stages the order, freezes the recipe and values on the job, and issues the named lots. The
     * availability of every lot is checked again here, atomically under the stock row locks.
     */
    @Transactional
    public Map<String, Object> start(String id) {
        startJob(id);
        return get(id);
    }

    private void startJob(String id) {
        Map<String, Object> j = lock(id);
        draftOnly(j);
        Map<String, Object> t = templates.require(str(j, "templateId"));
        List<Map<String, Object>> inputs = db.children(ProcessingJobInput.class, "jobId", id, "\"createdAt\" ASC");
        List<Map<String, Object>> tOutputs = db.children(ProcessingTemplateOutput.class, "templateId", t.get("id"),
                "sequence ASC");
        Map<String, Object> main = tOutputs.stream().filter(o -> "main".equals(o.get("role"))).findFirst().orElseThrow();
        BigDecimal mainInput = inputs.stream().filter(i -> "traceable".equals(i.get("role"))).map(i -> dec(i, "plannedQty"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal plannedQty = scale(mainInput.multiply(dec(main, "expectedPct")).divide(HUNDRED, 10,
                RoundingMode.HALF_UP));
        String code = str(j, "code");
        String date = String.valueOf(j.get("jobDate"));
        Map<String, Object> mainProduct = product(str(main, "productId"));
        String firstLot = inputs.stream().filter(i -> i.get("batchNumber") != null && "traceable".equals(i.get("role")))
                .map(i -> str(i, "batchNumber")).findFirst().orElse(null);
        String mainLot = Boolean.TRUE.equals(mainProduct.get("batchTracking"))
                ? nextLot(str(main, "productId"), firstLot, code) : null;

        Map<String, Object> order = production.createOrder(new CreateOrder(str(t, "bomId"), null, null, str(j, "locationId"),
                str(j, "outputLocationId"), null, plannedQty, date, mainLot, null, date, "Processing job " + code, null, null));
        String orderId = str(order, "id");
        db.update(ProductionOrder.class, orderId, Obj.of("status", "confirmed", "updatedBy", TenantContext.userId()));
        stages.initialize(orderId, new InitializeStages("Processing job " + code));
        Map<String, Object> stage = SqlRows.list(ProductionOrderStage.class, db.jdbc().sql("SELECT * FROM"
                + " \"ProductionOrderStage\" WHERE \"organizationId\" = ? AND \"orderId\" = ? ORDER BY sequence")
                .params(MfgDb.org(), orderId)).getFirst();
        String stageId = str(stage, "id");
        Map<String, Object> stageData = new LinkedHashMap<>();
        stageData.put("issueMode", "manual");
        stageData.put("expectedYieldPct", t.get("expectedYieldPct"));
        stageData.put("qcGate", Boolean.TRUE.equals(t.get("qcGate")));
        stageData.put("qcCheckpointCode", t.get("qcCheckpointCode"));
        db.update(ProductionOrderStage.class, stageId, stageData);

        // Materials: this job's exact quantities and lots.
        Map<String, Map<String, Object>> materialByProduct = new HashMap<>();
        for (Map<String, Object> m : db.children(ProductionMaterial.class, "orderId", orderId, "sequence ASC")) {
            materialByProduct.put(str(m, "productId"), m);
        }
        Map<String, BigDecimal> planned = new HashMap<>();
        inputs.forEach(i -> planned.merge(str(i, "productId"), dec(i, "plannedQty"), BigDecimal::add));
        planned.forEach((product, qty) -> {
            Map<String, Object> m = materialByProduct.get(product);
            db.update(ProductionMaterial.class, str(m, "id"), Obj.of("qtyPlanned", qty, "distStrategy", "MANUAL"));
        });

        // Snapshot and frozen values: nothing done to the template or a price list later can change this job.
        Map<String, Map<String, Object>> outputLine = new HashMap<>();
        for (Map<String, Object> o : db.children(ProductionOutput.class, "orderId", orderId, null)) {
            outputLine.put(str(o, "productId"), o);
        }
        List<Map<String, Object>> snapOutputs = new ArrayList<>();
        for (Map<String, Object> o : tOutputs) {
            Map<String, Object> p = product(str(o, "productId"));
            BigDecimal market = decOrNull(o, "marketValue") != null ? dec(o, "marketValue")
                    : p.get("salesPrice") == null ? BigDecimal.ZERO : dec(p, "salesPrice");
            boolean isMain = "main".equals(o.get("role"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jobId", id);
            row.put("templateOutputId", o.get("id"));
            row.put("productId", o.get("productId"));
            row.put("role", o.get("role"));
            row.put("batchNumber", isMain ? mainLot : Boolean.TRUE.equals(p.get("batchTracking"))
                    ? nextLot(str(o, "productId"), firstLot, code) : null);
            row.put("outputLineId", isMain ? null : outputLine.containsKey(str(o, "productId"))
                    ? outputLine.get(str(o, "productId")).get("id") : null);
            row.put("basisFactor", decOrNull(o, "basisFactor"));
            row.put("marketValue", market);
            db.insert(ProcessingJobOutput.class, row);
            snapOutputs.add(Obj.of("productId", o.get("productId"), "role", o.get("role"), "expectedPct", o.get("expectedPct"),
                    "valuation", o.get("valuation"), "basisFactor", o.get("basisFactor"), "marketValue", market));
        }
        Map<String, Object> snapshot = Obj.of("templateId", t.get("id"), "code", t.get("code"), "version", t.get("version"),
                "allocationMethod", t.get("allocationMethod"), "yieldBasisUnit", t.get("yieldBasisUnit"), "expectedYieldPct",
                t.get("expectedYieldPct"), "costRates", t.get("costRates"), "outputs", snapOutputs);
        db.update(ProcessingJob.class, id, Obj.of("orderId", orderId, "stageId", stageId, "snapshot", new MfgDb.Json(snapshot),
                "updatedBy", TenantContext.userId()));

        // Issue: reservations turn into real issues in the same transaction, under the stock row locks.
        for (Map<String, Object> in : inputs) {
            reservations.consume(RESERVATION_SOURCE, str(in, "id"));
            Map<String, Object> m = materialByProduct.get(str(in, "productId"));
            BigDecimal qty = dec(in, "plannedQty");
            stages.issue(stageId, new StageIssue(List.of(new IssueLine(str(m, "id"), null, qty, str(in, "batchNumber"))),
                    date, "Input · " + code));
            BigDecimal cost = db.jdbc().sql("SELECT coalesce(sum(l.value), 0) FROM \"ProductionStagePostingLine\" l WHERE"
                    + " l.\"postingId\" = ? AND l.role = 'material'").param(latestPosting(orderId)).query(BigDecimal.class)
                    .single();
            db.update(ProcessingJobInput.class, str(in, "id"), Obj.of("issuedQty", qty, "issuedCost", cost, "materialId",
                    m.get("id")));
        }
        audit.recordInTx(AuditInput.of("ProcessingJob", id, "update", null, Obj.of("code", code, "action", "start", "orderId",
                orderId)));
    }

    /** {@code <source lot>-P<n+1>} one step on from the source lot, or {@code <job code>-P1}; never a used number. */
    private String nextLot(String productId, String sourceLot, String jobCode) {
        String base = sourceLot != null ? sourceLot : jobCode;
        int step = 1;
        Matcher m = LOT_STEP.matcher(base);
        if (m.matches()) {
            base = m.group(1);
            step = Integer.parseInt(m.group(2)) + 1;
        }
        String lot = base + "-P" + step;
        for (int i = 2; lotUsed(productId, lot); i++) {
            lot = base + "-P" + step + "-" + i;
        }
        return lot;
    }

    private boolean lotUsed(String productId, String lot) {
        Integer n = db.jdbc().sql("""
                SELECT (SELECT count(*) FROM "InventoryBatch" WHERE "organizationId" = ? AND "productId" = ? AND "batchNumber" = ?)
                     + (SELECT count(*) FROM "ProcessingJobOutput" WHERE "organizationId" = ? AND "productId" = ? AND "batchNumber" = ?)
                """).params(MfgDb.org(), productId, lot, MfgDb.org(), productId, lot).query(Integer.class).single();
        return n > 0;
    }

    private String latestPosting(String orderId) {
        return db.jdbc().sql("SELECT id FROM \"ProductionStagePosting\" WHERE \"organizationId\" = ? AND \"orderId\" = ?"
                + " ORDER BY \"createdAt\" DESC, code DESC LIMIT 1").params(MfgDb.org(), orderId).query(String.class)
                .optional().orElse(null);
    }

    // ================================================================== cost

    /** Add a cost to WIP. A cost from an existing record credits that record's account and cannot be captured twice. */
    @Transactional
    public Map<String, Object> addCost(String id, JobCostInput dto) {
        addCostTo(id, dto);
        return get(id);
    }

    private void addCostTo(String id, JobCostInput dto) {
        Map<String, Object> j = open(lock(id));
        boolean sourced = dto.sourceType() != null || dto.sourceId() != null;
        if (sourced && (dto.sourceType() == null || dto.sourceId() == null || dto.creditAccountId() == null)) {
            throw ApiException.badRequest("A cost from an existing record needs its type, its id and the account it was"
                    + " charged to");
        }
        if (sourced) {
            Integer used = db.jdbc().sql("SELECT count(*)::int FROM \"ProcessingJobCost\" WHERE \"organizationId\" = ? AND"
                    + " \"sourceType\" = ? AND \"sourceId\" = ? AND \"reversedAt\" IS NULL")
                    .params(MfgDb.org(), dto.sourceType(), dto.sourceId()).query(Integer.class).single();
            if (used > 0) {
                throw ApiException.conflict("This " + dto.sourceType() + " record is already a job cost");
            }
        }
        String base = posting.baseCurrency();
        boolean foreign = dto.currency() != null && !dto.currency().equalsIgnoreCase(base);
        if (foreign && dto.exchangeRate() == null) {
            throw ApiException.badRequest("State the exchange rate of " + dto.currency() + " to " + base);
        }
        BigDecimal baseAmount = (foreign ? dto.amount().multiply(dto.exchangeRate()) : dto.amount()).setScale(2,
                RoundingMode.HALF_UP);
        LocalDate date = dto.costDate() != null ? LocalDate.parse(dto.costDate().substring(0, 10))
                : LocalDate.now(ZoneOffset.UTC);
        stages.conversion(str(j, "stageId"), new StageConversion(baseAmount, conversionKind(dto.category()),
                dto.creditAccountId(), date.toString(), label(dto) + " · " + j.get("code")));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("jobId", id);
        d.put("category", dto.category());
        d.put("description", dto.description());
        d.put("amount", dto.amount());
        d.put("currency", dto.currency() != null ? dto.currency() : base);
        d.put("exchangeRate", foreign ? dto.exchangeRate() : null);
        d.put("baseAmount", baseAmount);
        d.put("costDate", Date.valueOf(date));
        d.put("sourceType", dto.sourceType());
        d.put("sourceId", dto.sourceId());
        d.put("posted", true);
        d.put("postingId", latestPosting(str(j, "orderId")));
        d.put("creditAccountId", dto.creditAccountId());
        d.put("createdBy", TenantContext.userId());
        db.insert(ProcessingJobCost.class, d);
    }

    private static String label(JobCostInput dto) {
        return dto.description() != null && !dto.description().isBlank() ? dto.description() : dto.category();
    }

    private static String conversionKind(String category) {
        return switch (category) {
            case "labour" -> "labour";
            case "machine" -> "machine";
            case "utility", "fuel" -> "utility";
            default -> "overhead";
        };
    }

    // ================================================================== output

    /** Partial output keeps the rest in WIP at its cost; {@code complete} requires a full reconciliation. */
    @Transactional
    public Map<String, Object> recordOutput(String id, RecordOutput dto) {
        recordOutputOn(id, dto);
        return get(id);
    }

    private record Measured(JobOutputLine line, Map<String, Object> row, BigDecimal basis, String source) {}

    @SuppressWarnings("unchecked")
    private void recordOutputOn(String id, RecordOutput dto) {
        Map<String, Object> j = open(lock(id));
        boolean completes = Boolean.TRUE.equals(dto.complete());
        Map<String, Map<String, Object>> rows = new HashMap<>();
        for (Map<String, Object> o : db.children(ProcessingJobOutput.class, "jobId", id, null)) {
            rows.put(str(o, "templateOutputId"), o);
        }
        List<Measured> lines = new ArrayList<>();
        BigDecimal outputBasis = BigDecimal.ZERO;
        for (JobOutputLine l : dto.lines()) {
            Map<String, Object> row = rows.get(l.templateOutputId());
            if (row == null) {
                throw ApiException.badRequest("Output line " + l.templateOutputId() + " does not belong to this job");
            }
            Measured m = measure(l, row);
            lines.add(m);
            outputBasis = outputBasis.add(m.basis());
        }
        BigDecimal loss = dto.lossBasis() == null ? BigDecimal.ZERO : dto.lossBasis();
        if (outputBasis.signum() == 0 && loss.signum() == 0 && !completes) {
            throw ApiException.badRequest("Record an output or a loss, or complete the job");
        }
        Balance before = balance(id);
        BigDecimal processedBefore = before.output().add(before.loss());
        BigDecimal unaccounted = before.input().subtract(processedBefore).subtract(outputBasis).subtract(loss);
        String basisUnit = String.valueOf(j.get("yieldBasisUnit"));
        if (unaccounted.compareTo(TOLERANCE.negate()) < 0 && blank(dto.overYieldReason())) {
            throw ApiException.badRequest("Output and loss are " + JsNumbers.dec(unaccounted.negate()) + " " + basisUnit
                    + " more than the input. Check the measurements, or state why.");
        }
        if (completes && unaccounted.compareTo(TOLERANCE) > 0) {
            throw ApiException.badRequest("Job " + j.get("code") + " has " + JsNumbers.dec(unaccounted) + " " + basisUnit
                    + " not accounted for. Record it as output, recoverable residual or loss before completing.");
        }
        // Loss above the expected yield is written off, so it needs a reason and the right to approve it.
        BigDecimal yieldPct = dec(j, "expectedYieldPct");
        BigDecimal processed = outputBasis.add(loss);
        BigDecimal allowance = processed.multiply(BigDecimal.ONE.subtract(yieldPct.divide(HUNDRED, 10,
                RoundingMode.HALF_UP)));
        BigDecimal abnormalBasis = outputBasis.signum() == 0 ? loss : loss.subtract(allowance).max(BigDecimal.ZERO);
        boolean abnormal = abnormalBasis.compareTo(TOLERANCE) > 0;
        if (abnormal) {
            if (blank(dto.lossReason())) {
                throw ApiException.badRequest(JsNumbers.dec(abnormalBasis) + " " + basisUnit
                        + " is above the expected loss. State the reason.");
            }
            if (!TenantContext.has(Permissions.PROCESSING__APPROVE_LOSS)) {
                throw ApiException.forbidden("Only a user who can approve losses can record a loss above the expected yield");
            }
        }

        // Engine call: real quantities for stock, the common basis for yield, frozen values for allocation.
        BigDecimal mainQty = BigDecimal.ZERO;
        String mainBatch = null;
        List<OutputQty> others = new ArrayList<>();
        Map<String, FrozenOutput> frozen = new HashMap<>();
        Map<String, Measured> given = new HashMap<>();
        lines.forEach(m -> given.put(str(m.row(), "id"), m));
        for (Map<String, Object> row : rows.values()) {
            Measured m = given.get(str(row, "id"));
            BigDecimal qty = m == null ? BigDecimal.ZERO : m.line().quantity();
            BigDecimal basis = m == null ? BigDecimal.ZERO : m.basis();
            BigDecimal weight = qty.signum() > 0 ? basis.divide(qty, 10, RoundingMode.HALF_UP) : BigDecimal.ONE;
            FrozenOutput value = new FrozenOutput(weight, dec(row, "marketValue"));
            String batch = lotFor(row, m);
            if ("main".equals(row.get("role"))) {
                mainQty = qty;
                mainBatch = batch;
                frozen.put("main", value);
            } else if (row.get("outputLineId") != null) {
                others.add(new OutputQty(str(row, "outputLineId"), qty, batch));
                frozen.put(str(row, "outputLineId"), value);
            }
        }
        OutputOverrides overrides = new OutputOverrides(outputBasis, loss, before.input().multiply(yieldPct).divide(HUNDRED,
                10, RoundingMode.HALF_UP), processedBefore, frozen);
        String date = dto.date() != null ? dto.date() : String.valueOf(j.get("jobDate"));
        Map<String, Object> res = stages.output(str(j, "stageId"), new StageOutput(mainQty, loss, null, completes, others,
                mainBatch, null, date, dto.notes() != null ? dto.notes() : "Output · " + j.get("code")), overrides);
        String postingId = str((Map<String, Object>) res.get("posting"), "id");
        Map<String, Object> yield = (Map<String, Object>) res.get("yield");

        Map<String, BigDecimal> valueByProduct = new HashMap<>();
        for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("SELECT \"productId\", value FROM"
                + " \"ProductionStagePostingLine\" WHERE \"organizationId\" = ? AND \"postingId\" = ? AND role IN"
                + " ('main_output', 'co_product', 'by_product', 'waste')").params(MfgDb.org(), postingId))) {
            valueByProduct.merge(str(l, "productId"), dec(l, "value"), BigDecimal::add);
        }
        for (Measured m : lines) {
            BigDecimal value = valueByProduct.getOrDefault(str(m.row(), "productId"), BigDecimal.ZERO);
            BigDecimal qty = dec(m.row(), "quantity").add(m.line().quantity());
            BigDecimal total = dec(m.row(), "value").add(value);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("quantity", qty);
            d.put("value", total);
            d.put("unitCost", qty.signum() > 0 ? total.divide(qty, SCALE, RoundingMode.HALF_UP) : null);
            d.put("basisQty", dec(m.row(), "basisQty").add(m.basis()));
            d.put("basisSource", m.source());
            d.put("batchNumber", lotFor(m.row(), m));
            d.put("grade", m.line().grade() != null ? m.line().grade() : m.row().get("grade"));
            sum(d, m.row(), "weight", m.line().weight());
            sum(d, m.row(), "area", m.line().area());
            sum(d, m.row(), "volume", m.line().volume());
            set(d, "length", m.line().length());
            set(d, "width", m.line().width());
            set(d, "thickness", m.line().thickness());
            set(d, "density", m.line().density());
            db.update(ProcessingJobOutput.class, str(m.row(), "id"), d);
        }
        writeLosses(id, postingId, loss, abnormalBasis, new BigDecimal(String.valueOf(yield.get("abnormalValue"))), dto);
        writeGenealogy(id, postingId, lines, valueByProduct, before.input());
        if (!blank(dto.overYieldReason())) {
            db.update(ProcessingJob.class, id, Obj.of("overYieldReason", dto.overYieldReason()));
        }
        if (completes) {
            assertWipCleared(j);
        }
        audit.recordInTx(AuditInput.of("ProcessingJob", id, "update", null, Obj.of("code", j.get("code"), "action", completes
                ? "complete" : "output", "postingId", postingId, "outputBasis", outputBasis, "lossBasis", loss, "reason",
                dto.lossReason())));
    }

    /** No value may be left behind or disappear when a job is completed. */
    private void assertWipCleared(Map<String, Object> j) {
        Map<String, Object> stage = db.byId(ProductionOrderStage.class, str(j, "stageId"));
        BigDecimal wip = StageExecutionService.wip(stage);
        if (wip.abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw ApiException.badRequest("Job " + j.get("code") + " would leave " + JsNumbers.dec(wip)
                    + " in WIP. Nothing was posted.");
        }
    }

    private Measured measure(JobOutputLine l, Map<String, Object> row) {
        BigDecimal factor = row.get("basisFactor") == null ? BigDecimal.ONE : dec(row, "basisFactor");
        if (l.weight() != null) {
            return new Measured(l, row, scale(l.weight()), "measured");
        }
        if (l.basisQty() != null) {
            return new Measured(l, row, scale(l.basisQty()), "measured");
        }
        if (l.density() != null) {
            BigDecimal volume = l.volume();
            if (volume == null && l.area() != null && l.thickness() != null) {
                volume = l.area().multiply(l.thickness());
            }
            if (volume == null && l.length() != null && l.width() != null && l.thickness() != null) {
                volume = l.length().multiply(l.width()).multiply(l.thickness());
            }
            if (volume != null) {
                return new Measured(l, row, scale(volume.multiply(l.density())), "derived");
            }
        }
        return new Measured(l, row, scale(l.quantity().multiply(factor)), "template_factor");
    }

    private String lotFor(Map<String, Object> row, Measured m) {
        String requested = m == null ? null : m.line().batchNumber();
        String current = str(row, "batchNumber");
        if (requested == null || requested.isBlank() || requested.trim().equals(current) || current == null) {
            return current;
        }
        if (dec(row, "quantity").signum() > 0) {
            throw ApiException.badRequest("Output lot " + current + " already has stock; it cannot be renumbered");
        }
        if (lotUsed(str(row, "productId"), requested.trim())) {
            throw ApiException.conflict("Lot " + requested.trim() + " already exists for this product");
        }
        return requested.trim();
    }

    private void writeLosses(String jobId, String postingId, BigDecimal loss, BigDecimal abnormal, BigDecimal abnormalValue,
            RecordOutput dto) {
        BigDecimal normal = loss.subtract(abnormal);
        if (normal.compareTo(TOLERANCE) > 0) {
            db.insert(ProcessingJobLoss.class, Obj.of("jobId", jobId, "kind", "normal", "basisQty", normal, "postingId",
                    postingId));
        }
        if (abnormal.compareTo(TOLERANCE) > 0) {
            db.insert(ProcessingJobLoss.class, Obj.of("jobId", jobId, "kind", Boolean.TRUE.equals(dto.writeOff()) ? "write_off"
                    : "abnormal", "basisQty", abnormal, "value", abnormalValue, "reason", dto.lossReason(), "approvedBy",
                    TenantContext.userId(), "postingId", postingId));
        }
    }

    /**
     * Many-to-many lot links of this posting. Each input's share comes from the basis its template set; a consumable
     * carries cost but no lot link. Shares use the largest-remainder method, so attributions sum exactly.
     */
    private void writeGenealogy(String jobId, String postingId, List<Measured> lines, Map<String, BigDecimal> values,
            BigDecimal inputBasis) {
        List<Map<String, Object>> sources = db.children(ProcessingJobInput.class, "jobId", jobId, "\"createdAt\" ASC").stream()
                .filter(i -> "traceable".equals(i.get("role"))).toList();
        if (sources.isEmpty()) {
            return;
        }
        String method = str(sources.getFirst(), "genealogyBasis");
        List<BigDecimal> weights = sources.stream().map(i -> switch (method) {
            case "value" -> dec(i, "issuedCost");
            case "fixed_pct" -> i.get("fixedPct") == null ? BigDecimal.ZERO : dec(i, "fixedPct");
            case "manual" -> i.get("manualSharePct") == null ? BigDecimal.ZERO : dec(i, "manualSharePct");
            default -> dec(i, "basisQty");
        }).toList();
        BigDecimal total = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            weights = sources.stream().map(i -> dec(i, "basisQty")).toList();
            total = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        for (Measured m : lines) {
            BigDecimal qty = m.line().quantity();
            if (qty.signum() <= 0 || total.signum() <= 0) {
                continue;
            }
            BigDecimal value = values.getOrDefault(str(m.row(), "productId"), BigDecimal.ZERO);
            Map<String, Object> outProduct = product(str(m.row(), "productId"));
            List<BigDecimal> qtyShares = largestRemainder(qty, weights, total, SCALE);
            List<BigDecimal> costShares = largestRemainder(value, weights, total, 2);
            for (int i = 0; i < sources.size(); i++) {
                Map<String, Object> s = sources.get(i);
                if (weights.get(i).signum() == 0) {
                    continue;
                }
                BigDecimal fraction = inputBasis.signum() > 0 ? m.basis().divide(inputBasis, 10, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                Map<String, Object> sourceProduct = product(str(s, "productId"));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("jobId", jobId);
                d.put("postingId", postingId);
                d.put("sourceProductId", s.get("productId"));
                d.put("sourceBatchNumber", s.get("batchNumber"));
                d.put("sourceQty", scale(dec(s, "issuedQty").multiply(fraction)));
                d.put("sourceUomId", sourceProduct.get("uomId"));
                d.put("outputProductId", m.row().get("productId"));
                d.put("outputBatchNumber", lotFor(m.row(), m));
                d.put("attributedQty", qtyShares.get(i));
                d.put("outputUomId", outProduct.get("uomId"));
                d.put("allocationMethod", method);
                d.put("sharePct", weights.get(i).multiply(HUNDRED).divide(total, 4, RoundingMode.HALF_UP));
                d.put("attributedCost", costShares.get(i));
                db.insert(LotGenealogyLink.class, d);
            }
        }
    }

    /** {@code amount} split by {@code weights} so the parts sum exactly. Package-private for its unit test. */
    static List<BigDecimal> largestRemainder(BigDecimal amount, List<BigDecimal> weights, BigDecimal total, int scale) {
        List<BigDecimal> parts = new ArrayList<>();
        List<BigDecimal> remainders = new ArrayList<>();
        BigDecimal assigned = BigDecimal.ZERO;
        for (BigDecimal w : weights) {
            BigDecimal exact = amount.multiply(w).divide(total, 12, RoundingMode.HALF_UP);
            BigDecimal floor = exact.setScale(scale, RoundingMode.DOWN);
            parts.add(floor);
            remainders.add(exact.subtract(floor));
            assigned = assigned.add(floor);
        }
        BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);
        BigDecimal left = amount.setScale(scale, RoundingMode.HALF_UP).subtract(assigned);
        while (left.signum() > 0) {
            int best = 0;
            for (int i = 1; i < remainders.size(); i++) {
                if (remainders.get(i).compareTo(remainders.get(best)) > 0) {
                    best = i;
                }
            }
            parts.set(best, parts.get(best).add(unit));
            remainders.set(best, BigDecimal.valueOf(-1));
            left = left.subtract(unit);
        }
        return parts;
    }

    // ================================================================== one step, reversal

    /** The whole job in one transaction: draft, start, costs, output. Any failure leaves nothing behind. */
    @Transactional
    public Map<String, Object> post(PostJob dto) {
        String id = createDraft(dto.job());
        startJob(id);
        if (dto.costs() != null) {
            dto.costs().forEach(c -> addCostTo(id, c));
        }
        recordOutputOn(id, dto.output());
        return get(id);
    }

    /**
     * Reverse a started job. Each output lot must still be fully where it was received; otherwise nothing changes and
     * the error names the documents to undo first, latest first. Lots are checked individually: other stock of the same
     * product never makes up for a lot that has moved.
     */
    @Transactional
    public Map<String, Object> reverse(String id, String reason) {
        Map<String, Object> j = lock(id);
        if (j.get("orderId") == null) {
            throw ApiException.badRequest("Job " + j.get("code") + " has not started; cancel it instead");
        }
        String orderId = str(j, "orderId");
        assertOutputsOnHand(j, orderId);
        stages.reverseOrder(orderId, reason);
        Instant now = Instant.now();
        db.jdbc().sql("UPDATE \"LotGenealogyLink\" SET \"reversedAt\" = ? WHERE \"organizationId\" = ? AND \"jobId\" = ?")
                .params(java.sql.Timestamp.from(now), MfgDb.org(), id).update();
        db.jdbc().sql("UPDATE \"ProcessingJobCost\" SET \"reversedAt\" = ? WHERE \"organizationId\" = ? AND \"jobId\" = ?")
                .params(java.sql.Timestamp.from(now), MfgDb.org(), id).update();
        db.jdbc().sql("UPDATE \"ProcessingJobLoss\" SET \"reversedAt\" = ? WHERE \"organizationId\" = ? AND \"jobId\" = ?")
                .params(java.sql.Timestamp.from(now), MfgDb.org(), id).update();
        audit.recordInTx(AuditInput.of("ProcessingJob", id, "cancel", null, Obj.of("code", j.get("code"), "action", "reverse",
                "reason", reason)));
        return get(id);
    }

    private void assertOutputsOnHand(Map<String, Object> j, String orderId) {
        for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("""
                SELECT l."productId", l."locationId", g."batchId", b."batchNumber", SUM(l.quantity) AS quantity, MIN(p.name) AS name
                FROM "ProductionStagePostingLine" l
                JOIN "ProductionStagePosting" pp ON pp.id = l."postingId"
                JOIN "Product" p ON p.id = l."productId"
                LEFT JOIN "InventoryLedger" g ON g."organizationId" = l."organizationId" AND g."ledgerCode" = l."ledgerCode"
                     AND g."quantityChange" > 0
                LEFT JOIN "InventoryBatch" b ON b.id = g."batchId"
                WHERE l."organizationId" = ? AND pp."orderId" = ? AND pp."reversedAt" IS NULL AND l."locationId" IS NOT NULL
                  AND l.quantity > 0 AND l.role IN ('main_output', 'co_product', 'by_product', 'waste', 'qc_release')
                GROUP BY l."productId", l."locationId", g."batchId", b."batchNumber\"""").params(MfgDb.org(), orderId))) {
            String batch = str(l, "batchNumber");
            BigDecimal qty = dec(l, "quantity");
            BigDecimal onHand = batch != null ? lotQty(str(l, "productId"), batch, str(l, "locationId"))
                    : db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                            + " \"productId\" = ? AND \"locationId\" = ?").params(MfgDb.org(), l.get("productId"),
                            l.get("locationId")).query(BigDecimal.class).single();
            if (onHand.compareTo(qty) < 0) {
                List<String> docs = new ArrayList<>();
                if (batch != null) {
                    for (Map<String, Object> g : SqlRows.list(db.jdbc().sql("""
                            SELECT g."referenceType", g."referenceId", g."ledgerCode" FROM "InventoryLedger" g
                            JOIN "InventoryBatch" b ON b.id = g."batchId"
                            WHERE g."organizationId" = ? AND g."productId" = ? AND b."batchNumber" = ? AND g."quantityChange" < 0
                            ORDER BY g."createdAt" DESC, g."ledgerCode" DESC LIMIT 10""").params(MfgDb.org(),
                            l.get("productId"), batch))) {
                        docs.add(g.get("referenceType") + " " + g.get("referenceId") + " (" + g.get("ledgerCode") + ")");
                    }
                }
                throw ApiException.badRequest("Cannot reverse job " + j.get("code") + ": " + l.get("name")
                        + (batch == null ? "" : " lot " + batch) + " is no longer fully in stock (on hand "
                        + JsNumbers.dec(onHand) + " of " + JsNumbers.dec(qty) + ")."
                        + (docs.isEmpty() ? "" : " Undo these first, latest first: " + String.join("; ", docs) + "."));
            }
        }
    }

    // ================================================================== helpers

    private Map<String, Object> require(String id) {
        Map<String, Object> j = SqlRows.one(db.jdbc().sql("SELECT * FROM \"ProcessingJob\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, MfgDb.org()));
        if (j == null) {
            throw ApiException.notFound("Processing job not found");
        }
        return j;
    }

    /** One writer per job: row lock, then the same view the status is derived from. */
    private Map<String, Object> lock(String id) {
        db.jdbc().sql("SELECT id FROM \"ProcessingJob\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(id, MfgDb.org()).query().listOfRows();
        return Map.copyOf(nonNull(SqlRows.one(db.jdbc().sql(SELECT + " AND j.id = ?").params(MfgDb.org(), id))));
    }

    private static Map<String, Object> nonNull(Map<String, Object> row) {
        if (row == null) {
            throw ApiException.notFound("Processing job not found");
        }
        Map<String, Object> out = new HashMap<>();
        row.forEach((k, v) -> {
            if (v != null) {
                out.put(k, v);
            }
        });
        return out;
    }

    private static Map<String, Object> open(Map<String, Object> j) {
        Object status = j.get("status");
        if (!"in_progress".equals(status) && !"partially_output".equals(status)) {
            throw ApiException.badRequest("Job " + j.get("code") + " is " + status);
        }
        return j;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static void sum(Map<String, Object> d, Map<String, Object> row, String key, BigDecimal add) {
        if (add != null) {
            d.put(key, row.get(key) == null ? add : dec(row, key).add(add));
        }
    }

    private static void set(Map<String, Object> d, String key, BigDecimal value) {
        if (value != null) {
            d.put(key, value);
        }
    }

    private Map<String, Object> product(String productId) {
        Map<String, Object> p = SqlRows.one(db.jdbc().sql("SELECT id, name, \"uomId\", \"batchTracking\", \"salesPrice\" FROM"
                + " \"Product\" WHERE id = ? AND \"organizationId\" = ?").params(productId, MfgDb.org()));
        if (p == null) {
            throw ApiException.notFound("Product not found");
        }
        return p;
    }

    private static BigDecimal decOrNull(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : dec(row, key);
    }
}

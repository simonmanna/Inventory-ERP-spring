package com.inventoryerp.quarry.application;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Issued;
import com.inventoryerp.inventory.application.StockMove.Received;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.approvals.ApprovalAdminService;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quarry.application.QuarryCommands.ExplosiveInput;
import com.inventoryerp.quarry.application.QuarryCommands.ExplosiveIssueInput;
import com.inventoryerp.quarry.application.QuarryCommands.IssueLineInput;
import com.inventoryerp.quarry.application.QuarryCommands.IssueQuery;
import com.inventoryerp.quarry.application.QuarryCommands.MagazineInput;
import com.inventoryerp.quarry.application.QuarryCommands.ReconcileInput;
import com.inventoryerp.quarry.application.QuarryCommands.ReturnLineInput;
import com.inventoryerp.quarry.domain.QuarryBlast;
import com.inventoryerp.quarry.domain.QuarryExplosive;
import com.inventoryerp.quarry.domain.QuarryExplosiveIssue;
import com.inventoryerp.quarry.domain.QuarryExplosiveIssueLine;
import com.inventoryerp.quarry.domain.QuarryMagazine;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explosives as controlled stock (2.2, ADR-S012 §5): magazines (inventory locations with their licence), the registry
 * of inventory products flagged as controlled explosives, and explosive issues for a blast.
 *
 * <p>An issue is <b>requested</b> ({@code quarry_blasting:request_explosive}) and goes to the kernel approval engine
 * (entity {@value #APPROVAL_ENTITY}, default workflow: one approver with {@code quarry_blasting:approve_explosive},
 * never the requester); only an <b>approved</b> issue can be <b>issued</b> from the magazine
 * ({@code quarry_blasting:issue_explosive}, and — setting {@code distinctExplosiveIssuer}, default on — not by the
 * requester). Issuing locks the issue row, takes the stock out with {@link StockService#issue} (refused when the
 * magazine is short; concurrent issues serialise on the stock row) and posts Dr blasting / Cr stock; the unused
 * quantity is <b>reconciled</b> back into the magazine at its issue cost. Every step is in the audit trail; an
 * inactive workflow refuses requests (explosives are never issued without an approval).
 */
@Service
public class QuarryExplosiveService {

    public static final String APPROVAL_ENTITY = "quarry_explosive_issue";
    static final String AUDIT = "QuarryExplosiveIssue";
    static final String STOCK_ISSUE = "quarry_explosive_issue";
    static final String STOCK_RETURN = "quarry_explosive_return";

    private final QuarryDb db;
    private final QuarrySiteService sites;
    private final QuarrySettingsService settings;
    private final QuarryPostingService postings;
    private final QuarryRefs refs;
    private final StockService stock;
    private final ApprovalsService approvals;
    private final ApprovalAdminService approvalAdmin;
    private final SequenceService sequence;
    private final AuditService audit;

    public QuarryExplosiveService(QuarryDb db, QuarrySiteService sites, QuarrySettingsService settings,
            QuarryPostingService postings, QuarryRefs refs, StockService stock, ApprovalsService approvals,
            ApprovalAdminService approvalAdmin, SequenceService sequence, AuditService audit) {
        this.db = db;
        this.sites = sites;
        this.settings = settings;
        this.postings = postings;
        this.refs = refs;
        this.stock = stock;
        this.approvals = approvals;
        this.approvalAdmin = approvalAdmin;
        this.sequence = sequence;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ magazines

    @Transactional(readOnly = true)
    public List<Map<String, Object>> magazines(String siteId) {
        List<Object> params = new ArrayList<>(List.of(QuarryDb.org()));
        String extra = "";
        if (!QuarryDb.blank(siteId)) {
            extra = " AND g.\"siteId\" = ?";
            params.add(siteId);
        }
        return SqlRows.list(db.jdbc().sql("""
                SELECT g.*, s.code AS "siteCode", loc.code AS "locationCode", loc.name AS "locationName",
                       trim(concat(e."firstName", ' ', e."lastName")) AS "keeperName",
                       COALESCE((SELECT SUM(si.quantity * x."netExplosiveKg") FROM "StockItem" si
                           JOIN "QuarryExplosive" x ON x."productId" = si."productId"
                             AND x."organizationId" = si."organizationId" AND x."deletedAt" IS NULL
                           WHERE si."organizationId" = g."organizationId" AND si."locationId" = g."locationId"), 0)
                           AS "netExplosiveKg"
                FROM "QuarryMagazine" g
                LEFT JOIN "QuarrySite" s ON s.id = g."siteId"
                LEFT JOIN "InventoryLocation" loc ON loc.id = g."locationId" AND loc."organizationId" = g."organizationId"
                LEFT JOIN "HrEmployee" e ON e.id = g."keeperEmployeeId" AND e."organizationId" = g."organizationId"
                WHERE g."organizationId" = ? AND g."deletedAt" IS NULL""" + extra + " ORDER BY g.code").params(params));
    }

    /** Detail: the magazine, its explosive stock per product and the latest issues from it. */
    @Transactional(readOnly = true)
    public Map<String, Object> magazine(String id) {
        Map<String, Object> g = requireMagazine(id, false);
        String org = QuarryDb.org();
        Map<String, Object> out = new LinkedHashMap<>(magazines(null).stream().filter(m -> id.equals(m.get("id")))
                .findFirst().orElse(g));
        out.put("stock", SqlRows.list(db.jdbc().sql("""
                SELECT x.id AS "explosiveId", x.kind, x."hazardClass", x."unNumber", x."netExplosiveKg",
                       p.id AS "productId", p.code AS "productCode", p.name AS "productName",
                       COALESCE(SUM(si.quantity), 0) AS quantity,
                       COALESCE(SUM(si.quantity * si."runningAverageCost"), 0) AS value
                FROM "QuarryExplosive" x
                JOIN "Product" p ON p.id = x."productId" AND p."organizationId" = x."organizationId"
                LEFT JOIN "StockItem" si ON si."productId" = x."productId" AND si."organizationId" = x."organizationId"
                    AND si."locationId" = ?
                WHERE x."organizationId" = ? AND x."deletedAt" IS NULL
                GROUP BY x.id, p.id ORDER BY p.name""").params(g.get("locationId"), org)));
        out.put("recentIssues", SqlRows.list(db.jdbc().sql("SELECT i.id, i.code, i.status, i.\"issuedAt\","
                + " i.\"issuedValue\", b.code AS \"blastCode\" FROM \"QuarryExplosiveIssue\" i JOIN \"QuarryBlast\" b ON"
                + " b.id = i.\"blastId\" WHERE i.\"organizationId\" = ? AND i.\"magazineId\" = ? ORDER BY"
                + " i.\"createdAt\" DESC LIMIT 20").params(org, id)));
        return out;
    }

    Map<String, Object> requireMagazine(String id, boolean lock) {
        return db.require(QuarryMagazine.class, id, lock, "Magazine not found");
    }

    @Transactional
    public Map<String, Object> createMagazine(MagazineInput in) {
        String code = QuarryDb.text(in.code());
        code = code == null ? sequence.next("quarry_magazine", "MAG-", 2) : code.toUpperCase();
        assertCodeFree("QuarryMagazine", code, null, "A magazine with code " + code + " already exists");
        Map<String, Object> d = magazineData(in);
        String loc = QuarryDb.text(in.locationId());
        if (loc == null) {
            loc = sites.newLocation("MAG-" + code, in.name().trim() + " (explosives magazine)");
        } else {
            sites.requireFreeLocation(loc, null);
        }
        d.put("code", code);
        d.put("locationId", loc);
        d.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(QuarryMagazine.class, d);
        audit.recordInTx(AuditInput.of("QuarryMagazine", (String) row.get("id"), "create", null, row));
        return magazine((String) row.get("id"));
    }

    @Transactional
    public Map<String, Object> updateMagazine(String id, MagazineInput in) {
        Map<String, Object> before = requireMagazine(id, true);
        String loc = QuarryDb.text(in.locationId());
        if (loc != null && !loc.equals(before.get("locationId"))) {
            throw ApiException.badRequest("The inventory location of a magazine cannot change");
        }
        Map<String, Object> d = magazineData(in);
        String code = QuarryDb.text(in.code());
        if (code != null && !code.equalsIgnoreCase((String) before.get("code"))) {
            assertCodeFree("QuarryMagazine", code.toUpperCase(), id, "A magazine with code " + code.toUpperCase()
                    + " already exists");
            d.put("code", code.toUpperCase());
        }
        Map<String, Object> after = db.update(QuarryMagazine.class, id, d);
        audit.recordInTx(AuditInput.of("QuarryMagazine", id, "update", before, after));
        return magazine(id);
    }

    private Map<String, Object> magazineData(MagazineInput in) {
        String site = QuarryDb.text(in.siteId());
        db.requireRef("QuarrySite", site, true, "Quarry site not found");
        String keeper = QuarryDb.text(in.keeperEmployeeId());
        refs.employee(keeper);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("siteId", site);
        d.put("name", in.name().trim());
        d.put("licenceNumber", QuarryDb.text(in.licenceNumber()));
        d.put("licenceAuthority", QuarryDb.text(in.licenceAuthority()));
        d.put("licenceExpiresOn", in.licenceExpiresOn());
        d.put("capacityKg", in.capacityKg());
        d.put("keeperEmployeeId", keeper);
        d.put("isActive", in.isActive() == null || in.isActive());
        d.put("notes", QuarryDb.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }

    // ------------------------------------------------------------------ controlled explosive registry

    @Transactional(readOnly = true)
    public List<Map<String, Object>> explosives(boolean includeInactive) {
        return SqlRows.list(db.jdbc().sql("""
                SELECT x.*, p.code AS "productCode", p.name AS "productName",
                       COALESCE((SELECT SUM(si.quantity) FROM "StockItem" si JOIN "QuarryMagazine" g
                           ON g."locationId" = si."locationId" AND g."organizationId" = si."organizationId"
                           AND g."deletedAt" IS NULL
                         WHERE si."organizationId" = x."organizationId" AND si."productId" = x."productId"), 0)
                         AS "magazineStock"
                FROM "QuarryExplosive" x
                JOIN "Product" p ON p.id = x."productId" AND p."organizationId" = x."organizationId"
                WHERE x."organizationId" = ? AND x."deletedAt" IS NULL""" + (includeInactive ? "" : " AND x.\"isActive\"")
                + " ORDER BY p.name").params(QuarryDb.org()));
    }

    @Transactional
    public Map<String, Object> createExplosive(ExplosiveInput in) {
        Map<String, Object> product = sites.stockProduct(in.productId());
        long dup = db.jdbc().sql("SELECT count(*) FROM \"QuarryExplosive\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"deletedAt\" IS NULL").params(QuarryDb.org(), product.get("id"))
                .query(Long.class).single();
        if (dup > 0) {
            throw ApiException.conflict("Product " + product.get("name") + " is already registered as an explosive");
        }
        Map<String, Object> d = explosiveData(in);
        d.put("productId", product.get("id"));
        d.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(QuarryExplosive.class, d);
        audit.recordInTx(AuditInput.of("QuarryExplosive", (String) row.get("id"), "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> updateExplosive(String id, ExplosiveInput in) {
        Map<String, Object> before = db.require(QuarryExplosive.class, id, true, "Explosive not found");
        if (!Objects.equals(before.get("productId"), in.productId())) {
            throw ApiException.badRequest("The product of a registered explosive cannot change");
        }
        Map<String, Object> after = db.update(QuarryExplosive.class, id, explosiveData(in));
        audit.recordInTx(AuditInput.of("QuarryExplosive", id, "update", before, after));
        return after;
    }

    private static Map<String, Object> explosiveData(ExplosiveInput in) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("kind", in.kind() == null ? "high_explosive" : in.kind());
        d.put("hazardClass", QuarryDb.text(in.hazardClass()));
        d.put("unNumber", QuarryDb.text(in.unNumber()));
        d.put("netExplosiveKg", in.netExplosiveKg() == null ? BigDecimal.ONE : in.netExplosiveKg());
        d.put("controlled", in.controlled() == null || in.controlled());
        d.put("isActive", in.isActive() == null || in.isActive());
        d.put("notes", QuarryDb.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }

    // ------------------------------------------------------------------ issues

    private static final String ISSUE_SELECT = """
            SELECT i.*, b.code AS "blastCode", b.status AS "blastStatus", b."siteId", s.code AS "siteCode",
                   g.code AS "magazineCode", g.name AS "magazineName",
                   trim(concat(e."firstName", ' ', e."lastName")) AS "shotfirerName",
                   (SELECT r.status::text FROM "ApprovalRequest" r WHERE r.id = i."approvalRequestId") AS "approvalStatus"
            FROM "QuarryExplosiveIssue" i
            JOIN "QuarryBlast" b ON b.id = i."blastId"
            JOIN "QuarrySite" s ON s.id = b."siteId"
            JOIN "QuarryMagazine" g ON g.id = i."magazineId"
            LEFT JOIN "HrEmployee" e ON e.id = i."shotfirerEmployeeId" AND e."organizationId" = i."organizationId"
            """;

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> issues(IssueQuery q) {
        int p = QuarryDb.page(q.page());
        int size = QuarryDb.size(q.pageSize());
        StringBuilder where = new StringBuilder(" WHERE i.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(QuarryDb.org()));
        if (!QuarryDb.blank(q.status())) {
            where.append(" AND i.status = ?");
            params.add(q.status());
        }
        if (!QuarryDb.blank(q.blastId())) {
            where.append(" AND i.\"blastId\" = ?");
            params.add(q.blastId());
        }
        if (!QuarryDb.blank(q.magazineId())) {
            where.append(" AND i.\"magazineId\" = ?");
            params.add(q.magazineId());
        }
        if (!QuarryDb.blank(q.search())) {
            where.append(" AND (i.code ILIKE ? OR b.code ILIKE ?)");
            params.add(QuarryDb.like(q.search()));
            params.add(QuarryDb.like(q.search()));
        }
        String from = ISSUE_SELECT.substring(ISSUE_SELECT.indexOf("FROM \"QuarryExplosiveIssue\""));
        long total = db.jdbc().sql("SELECT count(*) " + from + where).params(params).query(Long.class).single();
        List<Object> pp = new ArrayList<>(params);
        pp.add(size);
        pp.add((p - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(ISSUE_SELECT + where
                + " ORDER BY i.\"createdAt\" DESC LIMIT ? OFFSET ?").params(pp)), p, size, total);
    }

    /** Detail: header, lines with product and magazine stock, GL entries and the full audit trail. */
    @Transactional(readOnly = true)
    public Map<String, Object> issue(String id) {
        requireIssue(id, false);
        String org = QuarryDb.org();
        Map<String, Object> out = new LinkedHashMap<>(SqlRows.one(db.jdbc().sql(ISSUE_SELECT
                + " WHERE i.id = ? AND i.\"organizationId\" = ?").params(id, org)));
        out.put("lines", SqlRows.list(db.jdbc().sql("""
                SELECT l.*, p.code AS "productCode", p.name AS "productName", x.kind, x."hazardClass", x."unNumber",
                       x."netExplosiveKg",
                       COALESCE((SELECT SUM(si.quantity) FROM "StockItem" si WHERE si."organizationId" = l."organizationId"
                           AND si."productId" = l."productId" AND si."locationId" = g."locationId"), 0) AS "magazineStock"
                FROM "QuarryExplosiveIssueLine" l
                JOIN "QuarryExplosiveIssue" i ON i.id = l."issueId"
                JOIN "QuarryMagazine" g ON g.id = i."magazineId"
                JOIN "QuarryExplosive" x ON x.id = l."explosiveId"
                JOIN "Product" p ON p.id = l."productId" AND p."organizationId" = l."organizationId"
                WHERE l."organizationId" = ? AND l."issueId" = ? ORDER BY l."lineNumber\"""").params(org, id)));
        out.put("journal", postings.journal(java.util.Arrays.asList((String) out.get("issueJournalEntryId"),
                (String) out.get("returnJournalEntryId"))));
        out.put("audit", SqlRows.list(db.jdbc().sql("""
                SELECT a.entity, a.action::text AS action, a."createdAt", a."actorId",
                       trim(concat(u."firstName", ' ', u."lastName")) AS "actorName", u.email AS "actorEmail"
                FROM "AuditLog" a LEFT JOIN "User" u ON u.id = a."actorId"
                WHERE a."organizationId" = ? AND ((a.entity = ? AND a."entityId" = ?)
                   OR (a.entity = 'ApprovalRequest' AND a."entityId" = ?))
                ORDER BY a."createdAt\"""").params(org, AUDIT, id, out.get("approvalRequestId"))));
        return out;
    }

    Map<String, Object> requireIssue(String id, boolean lock) {
        return db.require(QuarryExplosiveIssue.class, id, lock, "Explosive issue not found");
    }

    /** Request explosives for a blast; the approval engine decides before anything leaves the magazine. */
    @Transactional
    public Map<String, Object> request(ExplosiveIssueInput in) {
        Map<String, Object> blast = db.require(QuarryBlast.class, in.blastId(), true, "Blast not found");
        if (!"planned".equals(blast.get("status")) && !"fired".equals(blast.get("status"))) {
            throw ApiException.badRequest("Blast " + blast.get("code") + " is " + blast.get("status")
                    + "; explosives cannot be requested for it");
        }
        Map<String, Object> magazine = requireMagazine(in.magazineId(), false);
        assertUsable(magazine, LocalDate.now(BusinessZone.get()));
        String shotfirer = QuarryDb.text(in.shotfirerEmployeeId());
        if (shotfirer == null) {
            shotfirer = (String) blast.get("shotfirerEmployeeId");
        }
        refs.employee(shotfirer);
        String licence = QuarryDb.text(in.shotfirerLicenceNo());
        if (licence == null) {
            licence = (String) blast.get("shotfirerLicenceNo");
        }
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> lineRows = new ArrayList<>();
        BigDecimal estimate = BigDecimal.ZERO;
        for (IssueLineInput l : in.lines()) {
            Map<String, Object> x = db.byId(QuarryExplosive.class, l.explosiveId(), false);
            if (x == null) {
                throw ApiException.badRequest("Explosive not found");
            }
            if (!Boolean.TRUE.equals(x.get("isActive"))) {
                throw ApiException.badRequest("Explosive " + productName((String) x.get("productId")) + " is inactive");
            }
            if (!seen.add((String) x.get("id"))) {
                throw ApiException.badRequest("Explosive " + productName((String) x.get("productId"))
                        + " is listed twice");
            }
            BigDecimal avg = db.jdbc().sql("SELECT COALESCE(MAX(\"runningAverageCost\"), 0) FROM \"StockItem\" WHERE"
                    + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ?").params(QuarryDb.org(),
                    x.get("productId"), magazine.get("locationId")).query(BigDecimal.class).single();
            estimate = estimate.add(avg.multiply(l.quantity()));
            lineRows.add(Obj.of("explosiveId", x.get("id"), "productId", x.get("productId"), "requestedQty",
                    l.quantity()));
        }
        Map<String, Object> issue = db.insert(QuarryExplosiveIssue.class, Obj.of(
                "code", sequence.next("quarry_explosive_issue", "EXI-", 5),
                "blastId", blast.get("id"),
                "magazineId", magazine.get("id"),
                "status", "pending_approval",
                "purpose", QuarryDb.text(in.purpose()),
                "shotfirerEmployeeId", shotfirer,
                "shotfirerLicenceNo", licence,
                "requestedBy", TenantContext.userId(),
                "requestedAt", Instant.now(),
                "notes", QuarryDb.text(in.notes())));
        String id = (String) issue.get("id");
        int n = 0;
        for (Map<String, Object> l : lineRows) {
            l.put("issueId", id);
            l.put("lineNumber", ++n);
            db.insert(QuarryExplosiveIssueLine.class, l);
        }
        audit.recordInTx(AuditInput.of(AUDIT, id, "create", null, Obj.of("issue", issue, "lines", lineRows)));
        ensureWorkflow();
        Map<String, Object> snapshot = Obj.of("amount", estimate.setScale(2, RoundingMode.HALF_UP), "blast",
                blast.get("code"), "magazine", magazine.get("code"), "lines", lineRows.size(), "purpose",
                issue.get("purpose"));
        Map<String, Object> gate = approvals.checkOrRequestApproval(APPROVAL_ENTITY, id, snapshot);
        if (gate == null) {
            throw ApiException.badRequest("Explosive issues need an active approval workflow (" + APPROVAL_ENTITY
                    + "); ask an administrator to switch it on under Settings → Approvals");
        }
        db.update(QuarryExplosiveIssue.class, id, Obj.of("approvalRequestId", gate.get("requestId")));
        return issue(id);
    }

    /** Apply the approval engine's decision (idempotent: an issue no longer pending is left alone). */
    @Transactional
    public Map<String, Object> applyDecision(String issueId, boolean approved, String deciderId) {
        Map<String, Object> i = requireIssue(issueId, true);
        if (!"pending_approval".equals(i.get("status"))) {
            return i;
        }
        Map<String, Object> after = db.update(QuarryExplosiveIssue.class, issueId, Obj.of("status",
                approved ? "approved" : "rejected", "decidedBy", deciderId, "decidedAt", Instant.now()));
        audit.recordInTx(AuditInput.of(AUDIT, issueId, approved ? "approve" : "reject", i, after));
        return after;
    }

    /** Re-read the linked approval request and apply its outcome now (normally automatic). */
    @Transactional
    public Map<String, Object> sync(String issueId) {
        Map<String, Object> i = requireIssue(issueId, false);
        if (!"pending_approval".equals(i.get("status"))) {
            return issue(issueId);
        }
        String status = db.jdbc().sql("SELECT status::text FROM \"ApprovalRequest\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(i.get("approvalRequestId"), QuarryDb.org()).query(String.class)
                .optional().orElse("pending");
        if ("pending".equals(status)) {
            throw ApiException.badRequest("Explosive issue " + i.get("code") + " is waiting for approval");
        }
        applyDecision(issueId, "approved".equals(status), lastApprover((String) i.get("approvalRequestId")));
        return issue(issueId);
    }

    /** The approver of the latest decision on an approval request. */
    public String lastApprover(String requestId) {
        return db.jdbc().sql("SELECT \"approverId\" FROM \"ApprovalDecision\" WHERE \"requestId\" = ? ORDER BY"
                + " \"decidedAt\" DESC LIMIT 1").param(requestId).query(String.class).optional().orElse(null);
    }

    /** Issue an approved request from the magazine (exactly once; stock refused when the magazine is short). */
    @Transactional
    public Map<String, Object> issueStock(String id) {
        Map<String, Object> i = requireIssue(id, true);
        if (!"approved".equals(i.get("status"))) {
            throw ApiException.badRequest("Explosive issue " + i.get("code") + " is " + label(i.get("status"))
                    + "; only an approved issue can be issued");
        }
        String user = TenantContext.userId();
        if (!TenantContext.has(Permissions.QUARRY_BLASTING__ISSUE_EXPLOSIVE)) {
            throw ApiException.forbidden("Missing required permission(s): " + Permissions.QUARRY_BLASTING__ISSUE_EXPLOSIVE);
        }
        Map<String, Object> s = settings.current();
        if (Boolean.TRUE.equals(s.get("distinctExplosiveIssuer")) && Objects.equals(user, i.get("requestedBy"))) {
            throw ApiException.forbidden("Segregation of duties: the person who requested the explosives cannot issue"
                    + " them from the magazine");
        }
        Map<String, Object> blast = db.require(QuarryBlast.class, (String) i.get("blastId"), true, "Blast not found");
        QuarryBlastService.assertOpen(blast, "issued explosives");
        Map<String, Object> magazine = requireMagazine((String) i.get("magazineId"), false);
        Instant now = Instant.now();
        assertUsable(magazine, LocalDate.ofInstant(now, BusinessZone.get()));
        Map<String, Object> site = sites.requireSite((String) blast.get("siteId"), false);
        Map<String, BigDecimal> byProduct = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal kg = BigDecimal.ZERO;
        for (Map<String, Object> l : lines(id)) {
            BigDecimal qty = SqlRows.dec(l, "requestedQty");
            StockMove m = StockMove.of((String) l.get("productId"), (String) magazine.get("locationId"), qty)
                    .source(STOCK_ISSUE, id).reference((String) i.get("code")).notes("Explosives " + i.get("code")
                            + " · blast " + blast.get("code")).moveType("internal_use").date(now).skipGl()
                    .requireAvailable(true);
            m.responsibleById = user;
            m.approvedById = (String) i.get("decidedBy");
            Issued out = stock.issue(m);
            BigDecimal value = out.totalValue().setScale(2, RoundingMode.HALF_UP);
            byProduct.merge((String) l.get("productId"), value, BigDecimal::add);
            total = total.add(value);
            kg = kg.add(qty.multiply(netKg((String) l.get("explosiveId"))));
            db.update(QuarryExplosiveIssueLine.class, (String) l.get("id"), Obj.of("issuedQty", qty, "unitCost",
                    out.unitCost(), "issuedValue", value, "stockLedgerCode", out.ledgerCode()));
        }
        String entryId = postings.postExplosives(i, site, byProduct, now, false);
        db.jdbc().sql("UPDATE \"QuarryBlast\" SET \"explosivesKg\" = \"explosivesKg\" + ?, \"explosivesCost\" ="
                + " \"explosivesCost\" + ?, \"updatedAt\" = " + QuarryDb.NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(kg, total, blast.get("id"), QuarryDb.org()).update();
        Map<String, Object> after = db.update(QuarryExplosiveIssue.class, id, Obj.of("status", "issued", "issuedBy",
                user, "issuedAt", now, "issuedValue", total, "issueJournalEntryId", entryId));
        audit.recordInTx(AuditInput.of(AUDIT, id, "issue", i, Obj.merge(after, Obj.of("lines", lines(id),
                "netExplosiveKg", JsNumbers.dec(kg)))));
        return issue(id);
    }

    /** Record the unused explosives returned to the magazine (stock back at its issue cost) and close the issue. */
    @Transactional
    public Map<String, Object> reconcile(String id, ReconcileInput in) {
        Map<String, Object> i = requireIssue(id, true);
        if (!"issued".equals(i.get("status"))) {
            throw ApiException.badRequest("Explosive issue " + i.get("code") + " is " + label(i.get("status"))
                    + "; only an issued request can be reconciled");
        }
        if (!TenantContext.has(Permissions.QUARRY_BLASTING__ISSUE_EXPLOSIVE)) {
            throw ApiException.forbidden("Missing required permission(s): " + Permissions.QUARRY_BLASTING__ISSUE_EXPLOSIVE);
        }
        Map<String, BigDecimal> returned = new HashMap<>();
        for (ReturnLineInput r : in.lines()) {
            if (returned.put(r.lineId(), r.returnedQty()) != null) {
                throw ApiException.badRequest("A line is listed twice");
            }
        }
        List<Map<String, Object>> lines = lines(id);
        Set<String> ids = new HashSet<>();
        lines.forEach(l -> ids.add((String) l.get("id")));
        for (String lineId : returned.keySet()) {
            if (!ids.contains(lineId)) {
                throw ApiException.badRequest("Issue line not found");
            }
        }
        Map<String, Object> blast = db.require(QuarryBlast.class, (String) i.get("blastId"), true, "Blast not found");
        Map<String, Object> magazine = requireMagazine((String) i.get("magazineId"), false);
        Map<String, Object> site = sites.requireSite((String) blast.get("siteId"), false);
        Map<String, BigDecimal> byProduct = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal kg = BigDecimal.ZERO;
        Instant now = Instant.now();
        for (Map<String, Object> l : lines) {
            BigDecimal qty = returned.getOrDefault((String) l.get("id"), BigDecimal.ZERO);
            BigDecimal issued = SqlRows.dec(l, "issuedQty");
            if (qty.compareTo(issued) > 0) {
                throw ApiException.badRequest("More " + l.get("productName") + " returned (" + QuarryCostCalculator
                        .plain(qty) + ") than issued (" + QuarryCostCalculator.plain(issued) + ")");
            }
            if (qty.signum() == 0) {
                continue;
            }
            BigDecimal unit = SqlRows.dec(l, "unitCost");
            Map<String, Object> product = sites.stockProduct((String) l.get("productId"));
            StockMove m = StockMove.of((String) l.get("productId"), (String) magazine.get("locationId"), qty)
                    .unitCost(unit).source(STOCK_RETURN, id).reference((String) i.get("code"))
                    .notes("Unused explosives returned · " + i.get("code")).moveType("return_in");
            if (Boolean.TRUE.equals(product.get("batchTracking"))) {
                m.batch("RET-" + i.get("code"));
            }
            m.responsibleById = TenantContext.userId();
            Received back = stock.receive(m);
            BigDecimal value = qty.multiply(unit).setScale(2, RoundingMode.HALF_UP);
            byProduct.merge((String) l.get("productId"), value, BigDecimal::add);
            total = total.add(value);
            kg = kg.add(qty.multiply(netKg((String) l.get("explosiveId"))));
            db.update(QuarryExplosiveIssueLine.class, (String) l.get("id"), Obj.of("returnedQty", qty, "returnedValue",
                    value, "returnLedgerCode", back.ledgerCode()));
        }
        String entryId = postings.postExplosives(i, site, byProduct, now, true);
        if (total.signum() > 0 || kg.signum() > 0) {
            db.jdbc().sql("UPDATE \"QuarryBlast\" SET \"explosivesKg\" = GREATEST(\"explosivesKg\" - ?, 0),"
                    + " \"explosivesCost\" = GREATEST(\"explosivesCost\" - ?, 0), \"updatedAt\" = " + QuarryDb.NOW
                    + " WHERE id = ? AND \"organizationId\" = ?").params(kg, total, blast.get("id"), QuarryDb.org())
                    .update();
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("status", "reconciled");
        d.put("reconciledBy", TenantContext.userId());
        d.put("reconciledAt", now);
        d.put("returnedValue", total);
        d.put("returnJournalEntryId", entryId);
        if (QuarryDb.text(in.notes()) != null) {
            d.put("notes", i.get("notes") == null ? in.notes().trim() : i.get("notes") + "\n" + in.notes().trim());
        }
        Map<String, Object> after = db.update(QuarryExplosiveIssue.class, id, d);
        audit.recordInTx(AuditInput.of(AUDIT, id, "reconcile", i, Obj.merge(after, Obj.of("lines", lines(id)))));
        return issue(id);
    }

    /** Cancel a request that was not issued yet. */
    @Transactional
    public Map<String, Object> cancel(String id, String reason) {
        Map<String, Object> i = requireIssue(id, true);
        if (!"pending_approval".equals(i.get("status")) && !"approved".equals(i.get("status"))) {
            throw ApiException.badRequest("Explosive issue " + i.get("code") + " is " + label(i.get("status"))
                    + " and can no longer be cancelled");
        }
        Map<String, Object> after = db.update(QuarryExplosiveIssue.class, id, Obj.of("status", "cancelled",
                "cancelReason", reason.trim()));
        audit.recordInTx(AuditInput.of(AUDIT, id, "cancel", i, after));
        return issue(id);
    }

    // ------------------------------------------------------------------ helpers

    private List<Map<String, Object>> lines(String issueId) {
        return SqlRows.list(db.jdbc().sql("SELECT l.*, p.name AS \"productName\" FROM \"QuarryExplosiveIssueLine\" l"
                + " JOIN \"Product\" p ON p.id = l.\"productId\" WHERE l.\"organizationId\" = ? AND l.\"issueId\" = ?"
                + " ORDER BY l.\"lineNumber\"").params(QuarryDb.org(), issueId));
    }

    private BigDecimal netKg(String explosiveId) {
        return db.jdbc().sql("SELECT \"netExplosiveKg\" FROM \"QuarryExplosive\" WHERE id = ? AND \"organizationId\" = ?")
                .params(explosiveId, QuarryDb.org()).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    private String productName(String productId) {
        return db.jdbc().sql("SELECT name FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(productId, QuarryDb.org()).query(String.class).optional().orElse(productId);
    }

    private static void assertUsable(Map<String, Object> magazine, LocalDate day) {
        if (!Boolean.TRUE.equals(magazine.get("isActive"))) {
            throw ApiException.badRequest("Magazine " + magazine.get("code") + " is inactive");
        }
        LocalDate expires = (LocalDate) magazine.get("licenceExpiresOn");
        if (expires != null && day.isAfter(expires)) {
            throw ApiException.badRequest("The licence of magazine " + magazine.get("code") + " expired on " + expires);
        }
    }

    private static String label(Object status) {
        return String.valueOf(status).replace('_', ' ');
    }

    private void assertCodeFree(String table, String code, String exceptId, String message) {
        long n = db.jdbc().sql("SELECT count(*) FROM \"" + table + "\" WHERE \"organizationId\" = ? AND code = ? AND"
                + " \"deletedAt\" IS NULL AND id IS DISTINCT FROM ?").params(QuarryDb.org(), code, exceptId)
                .query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict(message);
        }
    }

    /** Creates the default explosive-issue workflow when the organization has none (active or not). */
    private void ensureWorkflow() {
        long n = db.jdbc().sql("SELECT count(*) FROM \"ApprovalWorkflow\" WHERE \"organizationId\" = ? AND"
                + " \"entityType\" = ?").params(QuarryDb.org(), APPROVAL_ENTITY).query(Long.class).single();
        if (n == 0) {
            approvalAdmin.createWorkflow("Explosive issue", APPROVAL_ENTITY, null, true, true,
                    List.of(new ApprovalAdminService.Step(1, "Blasting manager",
                            List.of(Permissions.QUARRY_BLASTING__APPROVE_EXPLOSIVE), 1, null, null)));
        }
    }
}

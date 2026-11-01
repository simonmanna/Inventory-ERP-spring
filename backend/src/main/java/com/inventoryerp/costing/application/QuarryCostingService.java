package com.inventoryerp.costing.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.AccountingPolicyService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.costing.application.LineageCapitalisation.Layer;
import com.inventoryerp.costing.application.LineageCapitalisation.Part;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quarry costing under IFRIC 20 and IAS 37 (Cost Management CM-5, ADR-S035), on the quarry area dimension
 * (site → pit → zone → bench → face).
 *
 * <ul>
 *   <li><b>Stripping-activity asset</b> (IFRIC 20): only on an approved assessment of an identified ore-body component
 *       (benefit probable, component identified, cost measurable) and the {@code stripping_cost = stripping_asset}
 *       election. Production-phase stripping cost is split by the component's expected waste-to-ore ratio: waste
 *       removed above the ratio for the ore produced is capitalised (Dr 1660), the rest stays production cost.
 *       Amortisation is by units of production over the component's expected ore, into the period's ROM layers
 *       (lineage), Cr 1661.</li>
 *   <li><b>Rehabilitation</b> (IAS 37, IFRIC 1, IAS 16.16c/18): four distinct movements plus utilisation, each its own
 *       provision layer and GL route — initial (Dr rehabilitation asset 1665), unwinding (Dr finance cost 7150, never
 *       inventory), production disturbance (Dr the period's ROM layers through lineage), remeasurement (initial layer
 *       ± asset, a decrease beyond the asset to P&amp;L, an increase flags an IAS 36 impairment indicator; production
 *       layer → P&amp;L 5368) and utilisation (Dr provision / Cr cash or payables). Provision 2690.</li>
 *   <li>Every line carries the {@code quarrySiteId} / {@code quarryAreaId} dimensions, the site's cost centre and
 *       branch. Roll-forward per area ties to the GL.</li>
 * </ul>
 */
@Service
public class QuarryCostingService {

    static final String STRIP_SOURCE = "quarry_stripping";
    static final String REHAB_SOURCE = "quarry_rehab";
    static final String EXTRACTION_STOCK_SOURCE = "quarry_extraction";
    private static final Set<String> AREA_STATUS = Set.of("planned", "development", "production", "exhausted",
            "rehabilitating", "rehabilitated");
    private static final Set<String> MOVEMENTS = Set.of("initial", "unwind", "production", "remeasurement",
            "utilisation");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_/-]{1,30}$");

    /** Area attributes. */
    public record AreaInput(String areaStatus, BigDecimal disturbedAreaM2, BigDecimal reservesTonnes,
            String oreBodyComponent) {}

    /** A stripping-activity asset with its IFRIC 20 assessment. */
    public record StrippingInput(String code, String siteId, String zoneId, String component,
            BigDecimal expectedOreTonnes, BigDecimal expectedWasteTonnes, Boolean benefitProbable,
            Boolean componentIdentified, Boolean costMeasurable, String assessment) {}

    /** Production-phase stripping cost of a period. */
    public record CapitaliseInput(String from, String to, BigDecimal strippingCost, BigDecimal wasteTonnes,
            String creditAccountId) {}

    /** A rehabilitation obligation of one area. */
    public record ObligationInput(String code, String siteId, String zoneId, BigDecimal discountRatePct,
            BigDecimal estimatePerM2, String description) {}

    /** One provision movement. */
    public record MovementInput(String type, String date, String from, BigDecimal amount, BigDecimal disturbedM2,
            String layerId, String creditAccountId, String notes) {}

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final AccountingPolicyService policies;
    private final LineageCapitalisation capitalisation;
    private final AuditService audit;

    public QuarryCostingService(JdbcClient jdbc, PostingService posting, AccountResolverService accounts,
            AccountingPolicyService policies, LineageCapitalisation capitalisation, AuditService audit) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.accounts = accounts;
        this.policies = policies;
        this.capitalisation = capitalisation;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    // ---- accounts ---------------------------------------------------------------------------------------------------

    public String strippingAssetAccount() {
        return accounts.ensureByCode("1660", "Stripping Activity Asset", "non_current_asset", "quarry_stripping_asset");
    }

    public String strippingAmortisationAccount() {
        return accounts.ensureByCode("1661", "Accumulated Amortisation - Stripping Asset", "accumulated_amortization",
                "quarry_stripping_amortisation");
    }

    public String rehabAssetAccount() {
        return accounts.ensureByCode("1665", "Rehabilitation Asset", "non_current_asset", "quarry_rehab_asset");
    }

    public String provisionAccount() {
        return accounts.ensureByCode("2690", "Rehabilitation Provision", "non_current_liability",
                "quarry_rehab_provision");
    }

    public String unwindAccount() {
        return accounts.ensureByCode("7150", "Unwinding of Discount on Provisions", "other_expense",
                "provision_unwind_finance_cost");
    }

    public String remeasurementAccount() {
        return accounts.ensureByCode("5368", "Rehabilitation Estimate Changes", "operating_expense",
                "quarry_rehab_remeasurement");
    }

    // ---- areas ------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> areas(String siteId) {
        boolean all = siteId == null || siteId.isBlank();
        return SqlRows.list(jdbc.sql("SELECT z.id, z.\"siteId\", z.\"parentId\", z.kind, z.code, z.name,"
                + " z.\"areaStatus\", z.\"disturbedAreaM2\", z.\"reservesTonnes\", z.\"oreBodyComponent\", s.code AS"
                + " \"siteCode\" FROM \"QuarryZone\" z JOIN \"QuarrySite\" s ON s.id = z.\"siteId\" WHERE"
                + " z.\"organizationId\" = ? AND z.\"deletedAt\" IS NULL" + (all ? "" : " AND z.\"siteId\" = ?")
                + " ORDER BY s.code, z.code").params(all ? List.of(org()) : List.of(org(), siteId)));
    }

    @Transactional
    public Map<String, Object> updateArea(String zoneId, AreaInput in) {
        Map<String, Object> z = zone(zoneId);
        if (in.areaStatus() != null && !AREA_STATUS.contains(in.areaStatus())) {
            throw ApiException.badRequest("areaStatus must be one of the following values: planned, development,"
                    + " production, exhausted, rehabilitating, rehabilitated");
        }
        if ((in.disturbedAreaM2() != null && in.disturbedAreaM2().signum() < 0)
                || (in.reservesTonnes() != null && in.reservesTonnes().signum() < 0)) {
            throw ApiException.badRequest("disturbedAreaM2 and reservesTonnes must not be negative");
        }
        jdbc.sql("UPDATE \"QuarryZone\" SET \"areaStatus\" = coalesce(?, \"areaStatus\"), \"disturbedAreaM2\" ="
                + " coalesce(?, \"disturbedAreaM2\"), \"reservesTonnes\" = coalesce(?, \"reservesTonnes\"),"
                + " \"oreBodyComponent\" = coalesce(?, \"oreBodyComponent\"), \"updatedAt\" = now() WHERE id = ?")
                .params(in.areaStatus(), in.disturbedAreaM2(), in.reservesTonnes(), blank(in.oreBodyComponent()),
                        zoneId).update();
        audit.recordInTx(AuditInput.of("QuarryZone", zoneId, "update", null, Obj.of("code", z.get("code"),
                "areaStatus", in.areaStatus())));
        return zone(zoneId);
    }

    private Map<String, Object> zone(String zoneId) {
        Map<String, Object> z = zoneId == null ? null : SqlRows.one(jdbc.sql("SELECT * FROM \"QuarryZone\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(zoneId, org()));
        if (z == null) {
            throw ApiException.notFound("Quarry area not found");
        }
        return z;
    }

    private Map<String, Object> site(String siteId) {
        Map<String, Object> s = siteId == null ? null : SqlRows.one(jdbc.sql("SELECT * FROM \"QuarrySite\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(siteId, org()));
        if (s == null) {
            throw ApiException.notFound("Quarry site not found");
        }
        return s;
    }

    /** Posted extraction logs of an area (and every area below it) in a period. */
    private List<Map<String, Object>> logs(String zoneId, LocalDate from, LocalDate to) {
        return SqlRows.list(jdbc.sql("WITH RECURSIVE t AS (SELECT id FROM \"QuarryZone\" WHERE id = ? UNION ALL SELECT"
                + " z.id FROM \"QuarryZone\" z JOIN t ON z.\"parentId\" = t.id) SELECT l.id, l.\"netTonnes\" FROM"
                + " \"QuarryExtractionLog\" l WHERE l.\"organizationId\" = ? AND l.status = 'posted' AND l.\"zoneId\" IN"
                + " (SELECT id FROM t) AND l.\"extractedOn\" >= ? AND l.\"extractedOn\" <= ?").params(zoneId, org(),
                Date.valueOf(from), Date.valueOf(to)));
    }

    private static BigDecimal tonnes(List<Map<String, Object>> logs) {
        return logs.stream().map(l -> SqlRows.dec(l, "netTonnes")).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<Layer> romLayers(List<Map<String, Object>> logs) {
        return capitalisation.receipts(EXTRACTION_STOCK_SOURCE, logs.stream().map(l -> (String) l.get("id")).toList());
    }

    // ---- IFRIC 20 stripping -------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> strippingAssets(String siteId) {
        boolean all = siteId == null || siteId.isBlank();
        return SqlRows.list(jdbc.sql("SELECT a.*, z.code AS \"zoneCode\", a.capitalised - a.amortised AS \"carrying\""
                + " FROM \"QuarryStrippingAsset\" a JOIN \"QuarryZone\" z ON z.id = a.\"zoneId\" WHERE"
                + " a.\"organizationId\" = ?" + (all ? "" : " AND a.\"siteId\" = ?") + " ORDER BY a.code")
                .params(all ? List.of(org()) : List.of(org(), siteId)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> strippingAsset(String id) {
        Map<String, Object> a = asset(id, false);
        a.put("carrying", SqlRows.dec(a, "capitalised").subtract(SqlRows.dec(a, "amortised")));
        a.put("movements", SqlRows.list(jdbc.sql("SELECT * FROM \"QuarryStrippingMovement\" WHERE \"assetId\" = ? ORDER"
                + " BY \"createdAt\"").param(id)));
        return a;
    }

    @Transactional
    public Map<String, Object> createStrippingAsset(StrippingInput in) {
        String code = code(in.code());
        site(in.siteId());
        Map<String, Object> z = zone(in.zoneId());
        if (!in.siteId().equals(z.get("siteId"))) {
            throw ApiException.badRequest("The area belongs to another site");
        }
        if (!Boolean.TRUE.equals(in.benefitProbable()) || !Boolean.TRUE.equals(in.componentIdentified())
                || !Boolean.TRUE.equals(in.costMeasurable())) {
            throw ApiException.badRequest("IFRIC 20.9: a stripping activity asset needs a probable future benefit, an"
                    + " identified component of the ore body and a reliably measurable cost");
        }
        if (in.expectedOreTonnes() == null || in.expectedOreTonnes().signum() <= 0 || in.expectedWasteTonnes() == null
                || in.expectedWasteTonnes().signum() < 0) {
            throw ApiException.badRequest("expectedOreTonnes must be greater than 0 and expectedWasteTonnes at least 0");
        }
        if (in.assessment() == null || in.assessment().trim().length() < 20) {
            throw ApiException.badRequest("Record the IFRIC 20 assessment (at least 20 characters)");
        }
        String component = in.component() == null || in.component().isBlank()
                ? (String) z.getOrDefault("oreBodyComponent", z.get("code")) : in.component().trim();
        if (component == null) {
            component = (String) z.get("code");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"QuarryStrippingAsset\" (id, \"organizationId\", code, \"siteId\", \"zoneId\", component,"
                        + " \"expectedOreTonnes\", \"expectedWasteTonnes\", \"benefitProbable\", \"componentIdentified\","
                        + " \"costMeasurable\", assessment, \"proposedBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, true,"
                        + " true, ?, ?)")
                .params(id, org(), code, in.siteId(), in.zoneId(), component, in.expectedOreTonnes(),
                        in.expectedWasteTonnes(), in.assessment().trim(), TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("QuarryStrippingAsset", id, "create", null, Obj.of("code", code)));
        return strippingAsset(id);
    }

    @Transactional
    public Map<String, Object> approveStrippingAsset(String id) {
        Map<String, Object> a = asset(id, true);
        if (!"proposed".equals(a.get("status"))) {
            throw ApiException.badRequest("Only a proposed assessment can be approved");
        }
        if (TenantContext.userId() != null && TenantContext.userId().equals(a.get("proposedBy"))) {
            throw ApiException.forbidden("An IFRIC 20 assessment must be approved by someone other than its proposer");
        }
        jdbc.sql("UPDATE \"QuarryStrippingAsset\" SET status = 'approved', \"approvedBy\" = ?, \"approvedAt\" = now()"
                + " WHERE id = ?").params(TenantContext.userId(), id).update();
        audit.recordInTx(AuditInput.of("QuarryStrippingAsset", id, "approve", null, Obj.of("code", a.get("code"))));
        return strippingAsset(id);
    }

    /** Capitalise the waste removed above the component's expected strip ratio for the ore produced. */
    @Transactional
    public Map<String, Object> capitalise(String assetId, CapitaliseInput in) {
        Map<String, Object> a = asset(assetId, true);
        requireApproved(a);
        if (!"stripping_asset".equals(policies.current("stripping_cost", "quarry", (String) a.get("siteId")))) {
            throw ApiException.badRequest("Stripping costs are elected to go to inventory (IFRIC 20); approve the"
                    + " stripping_asset election for this quarry first");
        }
        LocalDate from = day(in.from(), "from");
        LocalDate to = period(from, in.to());
        if (in.strippingCost() == null || in.strippingCost().signum() <= 0 || in.wasteTonnes() == null
                || in.wasteTonnes().signum() <= 0) {
            throw ApiException.badRequest("strippingCost and wasteTonnes must be greater than 0");
        }
        String credit = expenseAccount(in.creditAccountId());
        BigDecimal ore = tonnes(logs((String) a.get("zoneId"), from, to));
        BigDecimal ratio = SqlRows.dec(a, "expectedWasteTonnes").divide(SqlRows.dec(a, "expectedOreTonnes"), 10,
                RoundingMode.HALF_UP);
        BigDecimal excess = in.wasteTonnes().subtract(ore.multiply(ratio)).max(BigDecimal.ZERO);
        BigDecimal assetShare = in.strippingCost().multiply(excess).divide(in.wasteTonnes(), 2, RoundingMode.HALF_UP);
        if (assetShare.signum() <= 0) {
            throw ApiException.badRequest("No waste was removed above the expected strip ratio for the ore produced;"
                    + " the whole stripping cost is production cost (IFRIC 20.8)");
        }
        BigDecimal inventoryShare = in.strippingCost().subtract(assetShare);
        String id = UUID.randomUUID().toString();
        Map<String, Object> site = site((String) a.get("siteId"));
        String text = "Stripping asset " + a.get("code") + " · capitalised";
        Map<String, Object> dims = dims(site, (String) a.get("zoneId"));
        String je = post(STRIP_SOURCE, id, to, text, site, List.of(
                line(strippingAssetAccount(), assetShare, text, site, dims),
                line(credit, assetShare.negate(), text, site, dims)));
        insertStripMovement(id, assetId, "capitalise", from, to, in.strippingCost(), in.wasteTonnes(), ore,
                inventoryShare, assetShare, credit, je);
        jdbc.sql("UPDATE \"QuarryStrippingAsset\" SET capitalised = capitalised + ? WHERE id = ?").params(assetShare,
                assetId).update();
        return strippingAsset(assetId);
    }

    /** Units-of-production amortisation over the component's expected ore, into the period's ROM layers. */
    @Transactional
    public Map<String, Object> amortise(String assetId, String fromText, String toText) {
        Map<String, Object> a = asset(assetId, true);
        requireApproved(a);
        LocalDate from = day(fromText, "from");
        LocalDate to = period(from, toText);
        List<Map<String, Object>> logs = logs((String) a.get("zoneId"), from, to);
        BigDecimal ore = tonnes(logs);
        BigDecimal carrying = SqlRows.dec(a, "capitalised").subtract(SqlRows.dec(a, "amortised"));
        BigDecimal remaining = SqlRows.dec(a, "expectedOreTonnes").subtract(SqlRows.dec(a, "amortisedTonnes"));
        if (ore.signum() <= 0 || carrying.signum() <= 0) {
            throw ApiException.badRequest("Nothing to amortise: no ore was extracted from the component in the period"
                    + " or the asset is fully amortised");
        }
        BigDecimal amount = remaining.compareTo(ore) <= 0 ? carrying
                : carrying.multiply(ore).divide(remaining, 2, RoundingMode.HALF_UP).min(carrying);
        String id = UUID.randomUUID().toString();
        Map<String, Object> site = site((String) a.get("siteId"));
        Map<String, Object> dims = dims(site, (String) a.get("zoneId"));
        String text = "Stripping asset " + a.get("code") + " · amortisation " + JsNumbers.dec(ore) + " t";
        List<Part> parts = capitalisation.spread(amount, romLayers(logs));
        List<Line> lines = new ArrayList<>();
        capitalisation.debits(parts).forEach((acc, v) -> lines.add(line(acc, v, text, site, dims)));
        lines.add(line(strippingAmortisationAccount(), amount.negate(), text, site, dims));
        String je = post(STRIP_SOURCE, id, to, text, site, lines);
        insertStripMovement(id, assetId, "amortise", from, to, null, null, ore, BigDecimal.ZERO, amount, null, je);
        capitalisation.record(parts, "stripping_amortisation", STRIP_SOURCE, id, je, text);
        jdbc.sql("UPDATE \"QuarryStrippingAsset\" SET amortised = amortised + ?, \"amortisedTonnes\" ="
                + " \"amortisedTonnes\" + ? WHERE id = ?").params(amount, ore, assetId).update();
        return strippingAsset(assetId);
    }

    /** Reverse the latest posted stripping movement of its asset. */
    @Transactional
    public Map<String, Object> reverseStrippingMovement(String movementId) {
        Map<String, Object> m = SqlRows.one(jdbc.sql("SELECT * FROM \"QuarryStrippingMovement\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(movementId, org()));
        if (m == null) {
            throw ApiException.notFound("Stripping movement not found");
        }
        if (!"posted".equals(m.get("status"))) {
            throw ApiException.badRequest("Only a posted movement can be reversed");
        }
        String latest = jdbc.sql("SELECT id FROM \"QuarryStrippingMovement\" WHERE \"assetId\" = ? AND status = 'posted'"
                + " ORDER BY \"createdAt\" DESC LIMIT 1").param(m.get("assetId")).query(String.class).single();
        if (!movementId.equals(latest)) {
            throw ApiException.badRequest("Reverse the later movements of this asset first");
        }
        boolean amortise = "amortise".equals(m.get("type"));
        if (amortise) {
            capitalisation.assertUnreleased(STRIP_SOURCE, movementId);
        }
        String reversal = reverseEntry((String) m.get("journalEntryId"), "Reversal of stripping movement");
        if (amortise) {
            capitalisation.reverse(STRIP_SOURCE, movementId, (String) m.get("journalEntryId"),
                    "Reversal of stripping amortisation");
            jdbc.sql("UPDATE \"QuarryStrippingAsset\" SET amortised = amortised - ?, \"amortisedTonnes\" ="
                    + " \"amortisedTonnes\" - ? WHERE id = ?").params(m.get("amount"), m.get("oreTonnes"),
                    m.get("assetId")).update();
        } else {
            jdbc.sql("UPDATE \"QuarryStrippingAsset\" SET capitalised = capitalised - ? WHERE id = ?")
                    .params(m.get("amount"), m.get("assetId")).update();
        }
        jdbc.sql("UPDATE \"QuarryStrippingMovement\" SET status = 'reversed', \"reversalEntryId\" = ?, \"reversedAt\" ="
                + " now() WHERE id = ?").params(reversal, movementId).update();
        return strippingAsset((String) m.get("assetId"));
    }

    private void insertStripMovement(String id, String assetId, String type, LocalDate from, LocalDate to,
            BigDecimal cost, BigDecimal waste, BigDecimal ore, BigDecimal inventoryShare, BigDecimal amount,
            String credit, String je) {
        jdbc.sql("INSERT INTO \"QuarryStrippingMovement\" (id, \"organizationId\", \"assetId\", type, \"periodFrom\","
                        + " \"periodTo\", \"strippingCost\", \"wasteTonnes\", \"oreTonnes\", \"inventoryShare\", amount,"
                        + " \"creditAccountId\", \"journalEntryId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?)")
                .params(id, org(), assetId, type, Date.valueOf(from), Date.valueOf(to), cost, waste, ore,
                        inventoryShare, amount, credit, je, TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("QuarryStrippingMovement", id, "post", null, Obj.of("type", type, "amount",
                amount)));
    }

    private Map<String, Object> asset(String id, boolean lock) {
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT * FROM \"QuarryStrippingAsset\" WHERE id = ? AND"
                + " \"organizationId\" = ?" + (lock ? " FOR UPDATE" : "")).params(id, org()));
        if (a == null) {
            throw ApiException.notFound("Stripping asset not found");
        }
        return a;
    }

    private static void requireApproved(Map<String, Object> a) {
        if (!"approved".equals(a.get("status"))) {
            throw ApiException.badRequest("The IFRIC 20 assessment of " + a.get("code") + " is not approved");
        }
    }

    // ---- IAS 37 rehabilitation ------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> obligations(String siteId) {
        boolean all = siteId == null || siteId.isBlank();
        List<Map<String, Object>> out = SqlRows.list(jdbc.sql("SELECT o.*, z.code AS \"zoneCode\" FROM"
                + " \"QuarryRehabObligation\" o JOIN \"QuarryZone\" z ON z.id = o.\"zoneId\" WHERE o.\"organizationId\" ="
                + " ?" + (all ? "" : " AND o.\"siteId\" = ?") + " ORDER BY o.code")
                .params(all ? List.of(org()) : List.of(org(), siteId)));
        for (Map<String, Object> o : out) {
            o.put("balance", balance((String) o.get("id"), null));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obligation(String id) {
        Map<String, Object> o = obligationRow(id, false);
        o.put("balance", balance(id, null));
        o.put("assetCarrying", assetCarrying(id));
        o.put("layers", SqlRows.list(jdbc.sql("SELECT l.*, coalesce((SELECT sum(m.amount) FROM \"QuarryRehabMovement\" m"
                + " WHERE m.\"layerId\" = l.id AND m.status = 'posted'), 0) AS amount FROM \"QuarryRehabLayer\" l WHERE"
                + " l.\"obligationId\" = ? ORDER BY l.\"createdAt\"").param(id)));
        o.put("movements", SqlRows.list(jdbc.sql("SELECT * FROM \"QuarryRehabMovement\" WHERE \"obligationId\" = ? ORDER"
                + " BY \"movementDate\", \"createdAt\"").param(id)));
        return o;
    }

    @Transactional
    public Map<String, Object> createObligation(ObligationInput in) {
        String code = code(in.code());
        site(in.siteId());
        Map<String, Object> z = zone(in.zoneId());
        if (!in.siteId().equals(z.get("siteId"))) {
            throw ApiException.badRequest("The area belongs to another site");
        }
        if (in.discountRatePct() == null || in.discountRatePct().signum() < 0
                || in.discountRatePct().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("discountRatePct must be between 0 and 100 (a pre-tax rate, IAS 37.47)");
        }
        if (in.estimatePerM2() != null && in.estimatePerM2().signum() < 0) {
            throw ApiException.badRequest("estimatePerM2 must not be negative");
        }
        if (in.description() == null || in.description().isBlank()) {
            throw ApiException.badRequest("description should not be empty");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"QuarryRehabObligation\" (id, \"organizationId\", code, \"siteId\", \"zoneId\","
                        + " \"discountRatePct\", \"estimatePerM2\", description, \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?)")
                .params(id, org(), code, in.siteId(), in.zoneId(), in.discountRatePct(), in.estimatePerM2(),
                        in.description().trim(), TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("QuarryRehabObligation", id, "create", null, Obj.of("code", code)));
        return obligation(id);
    }

    /** Post one movement (its own provision layer and GL route). */
    @Transactional
    public Map<String, Object> movement(String obligationId, MovementInput in) {
        Map<String, Object> o = obligationRow(obligationId, true);
        if (in.type() == null || !MOVEMENTS.contains(in.type())) {
            throw ApiException.badRequest("type must be one of the following values: initial, unwind, production,"
                    + " remeasurement, utilisation");
        }
        if (!"open".equals(o.get("status"))) {
            throw ApiException.badRequest("The obligation is closed");
        }
        LocalDate date = in.date() == null || in.date().isBlank() ? today() : day(in.date(), "date");
        if (date.isAfter(today())) {
            throw ApiException.badRequest("A movement cannot be dated in the future");
        }
        Map<String, Object> site = site((String) o.get("siteId"));
        String zoneId = (String) o.get("zoneId");
        Map<String, Object> dims = dims(site, zoneId);
        String provision = provisionAccount();
        String id = UUID.randomUUID().toString();
        String text = "Rehabilitation " + o.get("code") + " · " + in.type();
        List<Line> lines = new ArrayList<>();
        BigDecimal amount;
        BigDecimal assetAmount = BigDecimal.ZERO;
        BigDecimal pl = BigDecimal.ZERO;
        boolean impairment = false;
        String layerId = null;
        LocalDate from = null;
        List<Part> parts = List.of();
        switch (in.type()) {
            case "initial" -> {
                amount = positive(in.amount(), "amount");
                assetAmount = amount;
                layerId = layer(obligationId, "initial", null);
                lines.add(line(rehabAssetAccount(), amount, text, site, dims));
            }
            case "unwind" -> {
                from = in.from() == null || in.from().isBlank() ? date.withDayOfMonth(1) : day(in.from(), "from");
                if (from.isAfter(date)) {
                    throw ApiException.badRequest("from must not be after date");
                }
                long days = ChronoUnit.DAYS.between(from, date) + 1;
                amount = balance(obligationId, date).multiply(SqlRows.dec(o, "discountRatePct"))
                        .multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(36500), 2, RoundingMode.HALF_UP);
                if (amount.signum() <= 0) {
                    throw ApiException.badRequest("Nothing to unwind: the provision has no balance or the rate is 0");
                }
                pl = amount;
                lines.add(line(unwindAccount(), amount, text, site, dims));
            }
            case "production" -> {
                from = in.from() == null || in.from().isBlank() ? date.withDayOfMonth(1) : day(in.from(), "from");
                BigDecimal m2 = in.disturbedM2();
                if (in.amount() != null) {
                    amount = positive(in.amount(), "amount");
                } else if (m2 != null && m2.signum() > 0 && o.get("estimatePerM2") != null) {
                    amount = m2.multiply(SqlRows.dec(o, "estimatePerM2")).setScale(2, RoundingMode.HALF_UP);
                } else {
                    throw ApiException.badRequest("Give the amount, or the disturbed m² with an estimate per m² on the"
                            + " obligation");
                }
                layerId = layer(obligationId, "production", null);
                parts = capitalisation.spread(amount, romLayers(logs(zoneId, from, date)));
                capitalisation.debits(parts).forEach((acc, v) -> lines.add(line(acc, v, text, site, dims)));
                if (m2 != null && m2.signum() > 0) {
                    jdbc.sql("UPDATE \"QuarryZone\" SET \"disturbedAreaM2\" = \"disturbedAreaM2\" + ? WHERE id = ?")
                            .params(m2, zoneId).update();
                }
            }
            case "remeasurement" -> {
                if (in.amount() == null || in.amount().signum() == 0) {
                    throw ApiException.badRequest("amount (the signed change of the estimate) should not be 0");
                }
                amount = in.amount().setScale(2, RoundingMode.HALF_UP);
                Map<String, Object> changed = layerRow(obligationId, in.layerId());
                String origin = rootOrigin(changed);
                layerId = layer(obligationId, "remeasurement", (String) changed.get("id"));
                if ("initial".equals(origin)) {
                    if (amount.signum() > 0) {
                        assetAmount = amount;
                        impairment = true;
                        lines.add(line(rehabAssetAccount(), amount, text, site, dims));
                    } else {
                        BigDecimal decrease = amount.negate();
                        BigDecimal toAsset = decrease.min(assetCarrying(obligationId).max(BigDecimal.ZERO));
                        assetAmount = toAsset.negate();
                        pl = decrease.subtract(toAsset).negate();
                        if (toAsset.signum() > 0) {
                            lines.add(line(rehabAssetAccount(), toAsset.negate(), text, site, dims));
                        }
                        if (pl.signum() != 0) {
                            lines.add(line(remeasurementAccount(), pl, text + " · beyond the asset", site, dims));
                        }
                    }
                } else {
                    pl = amount;
                    lines.add(line(remeasurementAccount(), amount, text + " · production layer", site, dims));
                }
            }
            default -> {
                amount = positive(in.amount(), "amount").negate();
                if (amount.negate().compareTo(balance(obligationId, null)) > 0) {
                    throw ApiException.badRequest("Utilisation exceeds the provision balance; remeasure first");
                }
                String credit = in.creditAccountId();
                if (credit == null || jdbc.sql("SELECT count(*) FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(credit, org()).query(Long.class).single() == 0) {
                    throw ApiException.badRequest("creditAccountId (cash, bank or payables) should not be empty");
                }
                lines.add(line(credit, amount, text, site, dims));
            }
        }
        lines.add(line(provision, amount.negate(), text, site, dims));
        String je = post(REHAB_SOURCE, id, date, text, site, lines);
        jdbc.sql("INSERT INTO \"QuarryRehabMovement\" (id, \"organizationId\", \"obligationId\", \"layerId\", type,"
                        + " \"movementDate\", \"periodFrom\", amount, \"assetAmount\", \"profitOrLoss\", \"disturbedM2\","
                        + " \"impairmentIndicator\", \"creditAccountId\", notes, \"journalEntryId\", \"createdBy\") VALUES"
                        + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), obligationId, layerId, in.type(), Date.valueOf(date), from == null ? null
                                : Date.valueOf(from), amount, assetAmount, pl, in.disturbedM2(), impairment,
                        in.creditAccountId(), blank(in.notes()), je, TenantContext.userId())
                .update();
        if (!parts.isEmpty()) {
            capitalisation.record(parts, "rehab_production", REHAB_SOURCE, id, je, text);
        }
        audit.recordInTx(AuditInput.of("QuarryRehabMovement", id, "post", null, Obj.of("type", in.type(), "amount",
                amount, "impairmentIndicator", impairment)));
        return obligation(obligationId);
    }

    /** Reverse the latest posted movement of its obligation. */
    @Transactional
    public Map<String, Object> reverseMovement(String movementId) {
        Map<String, Object> m = SqlRows.one(jdbc.sql("SELECT * FROM \"QuarryRehabMovement\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(movementId, org()));
        if (m == null) {
            throw ApiException.notFound("Rehabilitation movement not found");
        }
        if (!"posted".equals(m.get("status"))) {
            throw ApiException.badRequest("Only a posted movement can be reversed");
        }
        String latest = jdbc.sql("SELECT id FROM \"QuarryRehabMovement\" WHERE \"obligationId\" = ? AND status ="
                + " 'posted' ORDER BY \"createdAt\" DESC LIMIT 1").param(m.get("obligationId")).query(String.class)
                .single();
        if (!movementId.equals(latest)) {
            throw ApiException.badRequest("Reverse the later movements of this obligation first");
        }
        boolean production = "production".equals(m.get("type"));
        if (production) {
            capitalisation.assertUnreleased(REHAB_SOURCE, movementId);
        }
        String reversal = reverseEntry((String) m.get("journalEntryId"), "Reversal of rehabilitation movement");
        if (production) {
            capitalisation.reverse(REHAB_SOURCE, movementId, (String) m.get("journalEntryId"),
                    "Reversal of rehabilitation production disturbance");
            if (m.get("disturbedM2") != null) {
                jdbc.sql("UPDATE \"QuarryZone\" SET \"disturbedAreaM2\" = greatest(0, \"disturbedAreaM2\" - ?) WHERE id"
                        + " = (SELECT \"zoneId\" FROM \"QuarryRehabObligation\" WHERE id = ?)").params(m.get("disturbedM2"),
                        m.get("obligationId")).update();
            }
        }
        jdbc.sql("UPDATE \"QuarryRehabMovement\" SET status = 'reversed', \"reversalEntryId\" = ?, \"reversedAt\" ="
                + " now() WHERE id = ?").params(reversal, movementId).update();
        audit.recordInTx(AuditInput.of("QuarryRehabMovement", movementId, "cancel", null, Obj.of("reversalEntryId",
                reversal)));
        return obligation((String) m.get("obligationId"));
    }

    /**
     * Roll-forward per obligation (area): opening + initial + production + unwind ± remeasurement − utilisation =
     * closing, tied to the provision account's journal lines of its movements.
     */
    @Transactional
    public Map<String, Object> rollForward(String fromText, String toText, String siteId) {
        LocalDate from = day(fromText, "from");
        LocalDate to = day(toText, "to");
        String provision = provisionAccount();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> o : obligations(siteId)) {
            String id = (String) o.get("id");
            Map<String, BigDecimal> byType = new LinkedHashMap<>();
            MOVEMENTS.stream().sorted().forEach(t -> byType.put(t, BigDecimal.ZERO));
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT type, sum(amount) AS amount FROM"
                    + " \"QuarryRehabMovement\" WHERE \"obligationId\" = ? AND status = 'posted' AND \"movementDate\""
                    + " >= ? AND \"movementDate\" <= ? GROUP BY type").params(id, Date.valueOf(from), Date.valueOf(to)))) {
                byType.put((String) r.get("type"), SqlRows.dec(r, "amount"));
            }
            BigDecimal opening = balance(id, from.minusDays(1));
            BigDecimal closing = balance(id, to);
            BigDecimal gl = jdbc.sql("SELECT coalesce(sum(jl.\"baseCredit\" - jl.\"baseDebit\"), 0) FROM \"JournalLine\""
                    + " jl JOIN \"JournalEntry\" e ON e.id = jl.\"journalEntryId\" WHERE jl.\"accountId\" = ? AND"
                    + " e.\"postingDate\" < ? AND jl.\"journalEntryId\" IN (SELECT \"journalEntryId\" FROM"
                    + " \"QuarryRehabMovement\" WHERE \"obligationId\" = ? UNION SELECT \"reversalEntryId\" FROM"
                    + " \"QuarryRehabMovement\" WHERE \"obligationId\" = ? AND \"reversalEntryId\" IS NOT NULL)")
                    .params(provision, java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()),
                            id, id).query(BigDecimal.class).single();
            Map<String, Object> row = Obj.of("obligationId", id, "code", o.get("code"), "zoneId", o.get("zoneId"),
                    "zoneCode", o.get("zoneCode"), "opening", JsNumbers.dec(opening));
            byType.forEach((t, v) -> row.put(t, JsNumbers.dec(v)));
            row.put("closing", JsNumbers.dec(closing));
            row.put("gl", JsNumbers.dec(gl));
            row.put("difference", JsNumbers.dec(closing.subtract(gl)));
            rows.add(row);
        }
        return Obj.of("from", from.toString(), "to", to.toString(), "rows", rows);
    }

    private BigDecimal balance(String obligationId, LocalDate upTo) {
        return upTo == null
                ? jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"QuarryRehabMovement\" WHERE \"obligationId\" = ? AND"
                        + " status = 'posted'").param(obligationId).query(BigDecimal.class).single()
                : jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"QuarryRehabMovement\" WHERE \"obligationId\" = ? AND"
                        + " status = 'posted' AND \"movementDate\" <= ?").params(obligationId, Date.valueOf(upTo))
                        .query(BigDecimal.class).single();
    }

    private BigDecimal assetCarrying(String obligationId) {
        return jdbc.sql("SELECT coalesce(sum(\"assetAmount\"), 0) FROM \"QuarryRehabMovement\" WHERE \"obligationId\" = ?"
                + " AND status = 'posted'").param(obligationId).query(BigDecimal.class).single();
    }

    private String layer(String obligationId, String origin, String linked) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"QuarryRehabLayer\" (id, \"organizationId\", \"obligationId\", origin, \"linkedLayerId\")"
                + " VALUES (?, ?, ?, ?, ?)").params(id, org(), obligationId, origin, linked).update();
        return id;
    }

    private Map<String, Object> layerRow(String obligationId, String layerId) {
        Map<String, Object> l = layerId == null ? null : SqlRows.one(jdbc.sql("SELECT * FROM \"QuarryRehabLayer\" WHERE"
                + " id = ? AND \"obligationId\" = ?").params(layerId, obligationId));
        if (l == null) {
            throw ApiException.badRequest("layerId must name a provision layer of this obligation");
        }
        return l;
    }

    /** The origin a remeasurement chain ends at (initial or production). */
    private String rootOrigin(Map<String, Object> layer) {
        Map<String, Object> l = layer;
        for (int i = 0; i < 20 && "remeasurement".equals(l.get("origin")) && l.get("linkedLayerId") != null; i++) {
            l = SqlRows.one(jdbc.sql("SELECT * FROM \"QuarryRehabLayer\" WHERE id = ?").param(l.get("linkedLayerId")));
        }
        return (String) l.get("origin");
    }

    private Map<String, Object> obligationRow(String id, boolean lock) {
        Map<String, Object> o = SqlRows.one(jdbc.sql("SELECT * FROM \"QuarryRehabObligation\" WHERE id = ? AND"
                + " \"organizationId\" = ?" + (lock ? " FOR UPDATE" : "")).params(id, org()));
        if (o == null) {
            throw ApiException.notFound("Rehabilitation obligation not found");
        }
        return o;
    }

    // ---- KPIs ---------------------------------------------------------------------------------------------------------

    /**
     * Extraction cost per tonne per area: the log's components plus stripping amortisation and the rehabilitation
     * production increment, over net tonnes. One definition used by every quarry report from CM-5.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> kpis(String fromText, String toText, String siteId) {
        LocalDate from = day(fromText, "from");
        LocalDate to = day(toText, "to");
        boolean all = siteId == null || siteId.isBlank();
        List<Object> params = new ArrayList<>(List.of(org(), Date.valueOf(from), Date.valueOf(to)));
        if (!all) {
            params.add(siteId);
        }
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.\"siteId\", l.\"zoneId\", z.code AS \"zoneCode\","
                + " sum(l.\"netTonnes\") AS tonnes, sum(l.\"fuelCost\") AS fuel, sum(l.\"labourCost\") AS labour,"
                + " sum(l.\"equipmentCost\") AS equipment, sum(l.\"blastingCost\") AS blasting, sum(l.\"royaltyCost\") AS"
                + " royalty, sum(l.\"totalCost\") AS total FROM \"QuarryExtractionLog\" l LEFT JOIN \"QuarryZone\" z ON"
                + " z.id = l.\"zoneId\" WHERE l.\"organizationId\" = ? AND l.status = 'posted' AND l.\"extractedOn\" >= ?"
                + " AND l.\"extractedOn\" <= ?" + (all ? "" : " AND l.\"siteId\" = ?") + " GROUP BY l.\"siteId\","
                + " l.\"zoneId\", z.code ORDER BY z.code").params(params))) {
            BigDecimal other = SqlRows.dec(r, "total").subtract(SqlRows.dec(r, "fuel")).subtract(SqlRows.dec(r,
                    "labour")).subtract(SqlRows.dec(r, "equipment")).subtract(SqlRows.dec(r, "blasting"))
                    .subtract(SqlRows.dec(r, "royalty"));
            r.put("other", other);
            r.put("strippingAmortisation", BigDecimal.ZERO);
            r.put("rehabilitation", BigDecimal.ZERO);
            rows.put(String.valueOf(r.get("zoneId")), r);
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT a.\"zoneId\", sum(m.amount) AS amount FROM"
                + " \"QuarryStrippingMovement\" m JOIN \"QuarryStrippingAsset\" a ON a.id = m.\"assetId\" WHERE"
                + " m.\"organizationId\" = ? AND m.type = 'amortise' AND m.status = 'posted' AND m.\"periodTo\" >= ? AND"
                + " m.\"periodTo\" <= ? GROUP BY a.\"zoneId\"").params(org(), Date.valueOf(from), Date.valueOf(to)))) {
            Map<String, Object> row = rows.get(String.valueOf(r.get("zoneId")));
            if (row != null) {
                row.put("strippingAmortisation", SqlRows.dec(r, "amount"));
            }
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT o.\"zoneId\", sum(m.amount) AS amount FROM"
                + " \"QuarryRehabMovement\" m JOIN \"QuarryRehabObligation\" o ON o.id = m.\"obligationId\" WHERE"
                + " m.\"organizationId\" = ? AND m.type = 'production' AND m.status = 'posted' AND m.\"movementDate\" >="
                + " ? AND m.\"movementDate\" <= ? GROUP BY o.\"zoneId\"").params(org(), Date.valueOf(from),
                Date.valueOf(to)))) {
            Map<String, Object> row = rows.get(String.valueOf(r.get("zoneId")));
            if (row != null) {
                row.put("rehabilitation", SqlRows.dec(r, "amount"));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal tonnes = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (Map<String, Object> r : rows.values()) {
            BigDecimal t = SqlRows.dec(r, "tonnes");
            BigDecimal c = SqlRows.dec(r, "total").add((BigDecimal) r.get("strippingAmortisation"))
                    .add((BigDecimal) r.get("rehabilitation"));
            tonnes = tonnes.add(t);
            cost = cost.add(c);
            Map<String, Object> row = new LinkedHashMap<>();
            r.forEach((k, v) -> row.put(k, v instanceof BigDecimal b ? JsNumbers.dec(b) : v));
            row.put("cost", JsNumbers.dec(c));
            row.put("costPerTonne", t.signum() == 0 ? null : JsNumbers.dec(c.divide(t, 4, RoundingMode.HALF_UP)));
            out.add(row);
        }
        return Obj.of("from", from.toString(), "to", to.toString(), "rows", out, "tonnes", JsNumbers.dec(tonnes),
                "cost", JsNumbers.dec(cost), "costPerTonne", tonnes.signum() == 0 ? null
                        : JsNumbers.dec(cost.divide(tonnes, 4, RoundingMode.HALF_UP)));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private static Map<String, Object> dims(Map<String, Object> site, String zoneId) {
        return Obj.of("quarrySiteId", site.get("id"), "quarryAreaId", zoneId);
    }

    private static Line line(String account, BigDecimal amount, String text, Map<String, Object> site,
            Map<String, Object> dims) {
        String v = JsNumbers.dec(amount.abs());
        String branch = (String) site.get("branchId");
        String cc = (String) site.get("costCenterId");
        return amount.signum() >= 0 ? new Line(account, v, null, null, text, branch, cc, dims)
                : new Line(account, null, v, null, text, branch, cc, dims);
    }

    private String post(String sourceType, String id, LocalDate date, String text, Map<String, Object> site,
            List<Line> lines) {
        Map<String, Object> je = posting.post(new PostingRequest("GEN", date.atStartOfDay(ZoneOffset.UTC).toInstant(),
                text, null, null, sourceType, id, null, "costing:" + sourceType + ":" + org() + ":" + id,
                (String) site.get("branchId"), (String) site.get("costCenterId"), null, lines));
        return je == null ? null : (String) je.get("id");
    }

    private String reverseEntry(String journalEntryId, String text) {
        if (journalEntryId == null) {
            return null;
        }
        return (String) posting.reverse(journalEntryId, Instant.now(), text).get("id");
    }

    private String expenseAccount(String id) {
        String key = id == null ? null : jdbc.sql("SELECT c.key FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id ="
                + " a.\"categoryId\" WHERE a.id = ? AND a.\"organizationId\" = ?").params(id, org()).query(String.class)
                .optional().orElse(null);
        if (key == null || !Set.of("operating_expense", "cost_of_goods_sold", "other_expense").contains(key)) {
            throw ApiException.badRequest("creditAccountId must be the expense account the stripping cost was booked"
                    + " to");
        }
        return id;
    }

    private static BigDecimal positive(BigDecimal v, String field) {
        if (v == null || v.signum() <= 0) {
            throw ApiException.badRequest(field + " must be greater than 0");
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String code(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw ApiException.badRequest("code must be 1 to 30 letters, digits, '-', '/' or '_'");
        }
        return code;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private static LocalDate period(LocalDate from, String toText) {
        LocalDate to = day(toText, "to");
        if (to.isBefore(from)) {
            throw ApiException.badRequest("to must not be before from");
        }
        if (to.isAfter(today())) {
            throw ApiException.badRequest("A period cannot end in the future");
        }
        return to;
    }

    private static LocalDate day(String v, String field) {
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest(field + " should not be empty");
        }
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be a valid ISO 8601 date string");
        }
    }
}

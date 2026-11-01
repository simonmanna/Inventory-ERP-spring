package com.inventoryerp.costing.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.AccountingPolicyService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.costing.application.DistributionCostCalculator.Charged;
import com.inventoryerp.costing.application.DistributionCostCalculator.Trip;
import com.inventoryerp.costing.application.LineageCapitalisation.Layer;
import com.inventoryerp.costing.application.LineageCapitalisation.Part;
import com.inventoryerp.fleet.application.FleetCostingService;
import com.inventoryerp.fleet.application.FleetReportCommands.CostQuery;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Distribution costing and same-entity branch transfers (Cost Management CM-7, ADR-S037, AD-6, AD-10).
 *
 * <ul>
 *   <li><b>Delivery cost run</b>: owned-fleet trips delivered in the period are charged at the vehicle's cost per km
 *       (fleet TCO report, or a manual rate) over loaded + empty km, the empty legs by the elected policy
 *       ({@link DistributionCostCalculator}). Delivery to a customer is <em>distribution</em> cost — expensed, never
 *       inventory: Dr 6460 Outbound Freight &amp; Transport per trip (customer, delivery order, vehicle dimensions; the
 *       trip's cost centre; the order's branch) / Cr 5385 Own Fleet Cost Recharged. P&amp;L-neutral by nature, it
 *       brings own-fleet delivery cost into margin beside the third-party freight already accrued per trip.</li>
 *   <li><b>Branch transfer</b>: stock moves between two branches of this organisation at carrying amount; one
 *       journal carries the branch on every line (Cr stock at the source branch, Dr stock at the destination) and,
 *       with inter-branch clearing, Dr 1299 at the source (due from) / Cr 1299 at the destination (due to), so each
 *       branch's trial balance balances. Freight to bring the goods to the destination is
 *       {@code transfer_capitalisable} (IAS 2.10): it is capitalised on the transfer-in layer through lineage. An
 *       expense account used as the freight source must be classified as capitalisable. Intercompany is a different
 *       organisation (ADR-S028), so a branch transfer can never cross a legal entity.</li>
 * </ul>
 */
@Service
public class DistributionCostingService {

    static final String RUN_SOURCE = "delivery_cost_run";
    static final String TRANSFER_SOURCE = "branch_transfer";
    private static final Set<String> POLICIES = Set.of("loaded_leg", "pooled");

    /** A branch transfer. */
    public record TransferInput(String productId, BigDecimal quantity, String fromLocationId, String toLocationId,
            BigDecimal freight, String freightAccountId, Boolean useClearing, String notes) {}

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final AccountingPolicyService policies;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final LineageCapitalisation capitalisation;
    private final ObjectProvider<FleetCostingService> fleet;
    private final AuditService audit;

    public DistributionCostingService(JdbcClient jdbc, SequenceService seq, PostingService posting,
            AccountResolverService accounts, AccountingPolicyService policies, StockService stock,
            StockPostingService stockPosting, LineageCapitalisation capitalisation,
            ObjectProvider<FleetCostingService> fleet, AuditService audit) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.posting = posting;
        this.accounts = accounts;
        this.policies = policies;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.capitalisation = capitalisation;
        this.fleet = fleet;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    public String distributionAccount() {
        return accounts.ensureByCode("6460", "Outbound Freight & Transport", "operating_expense",
                "fleet_freight_expense");
    }

    public String rechargedAccount() {
        return accounts.ensureByCode("5385", "Own Fleet Cost Recharged", "operating_expense", "fleet_cost_recharged");
    }

    public String clearingAccount() {
        return accounts.ensureByCode("1299", "Inter-branch Clearing", "current_asset", "inter_branch_clearing");
    }

    // ---- delivery cost runs -----------------------------------------------------------------------------------------

    /** Owned-fleet trips delivered in the period with their charge (nothing is posted). */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String fromText, String toText, String branchId,
            Map<String, BigDecimal> manualRates) {
        Period p = period(fromText, toText);
        String policy = policy();
        List<Map<String, Object>> loads = loads(p, branch(branchId));
        List<Charged> charged = charge(loads, p, manualRates, policy);
        return Obj.of("from", p.from().toString(), "to", p.to().toString(), "emptyLegPolicy", policy, "lines",
                render(charged, loads), "total", JsNumbers.dec(total(charged)));
    }

    @Transactional
    public Map<String, Object> run(String fromText, String toText, String branchId,
            Map<String, BigDecimal> manualRates) {
        Period p = period(fromText, toText);
        String branch = branch(branchId);
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("delivery-cost:" + org()).query()
                .listOfRows();
        if (jdbc.sql("SELECT count(*) FROM \"DeliveryCostRun\" WHERE \"organizationId\" = ? AND status = 'posted' AND"
                + " \"periodFrom\" <= ? AND \"periodTo\" >= ? AND (\"branchId\" IS NULL OR ?::text IS NULL OR"
                + " \"branchId\" = ?)").params(org(), Date.valueOf(p.to()), Date.valueOf(p.from()), branch, branch)
                .query(Long.class).single() > 0) {
            throw ApiException.conflict("A delivery cost run already covers " + p.from() + " – " + p.to()
                    + "; reverse it first");
        }
        String policy = policy();
        List<Map<String, Object>> loads = loads(p, branch);
        List<Charged> charged = charge(loads, p, manualRates, policy);
        BigDecimal total = total(charged);
        if (total.signum() <= 0) {
            throw ApiException.badRequest("Nothing to charge: no own-fleet trip was delivered in the period");
        }
        String id = UUID.randomUUID().toString();
        String number = seq.next("delivery_cost_run", "DCR/", 5);
        String text = "Delivery cost " + number;
        Map<String, Map<String, Object>> byLoad = new HashMap<>();
        loads.forEach(l -> byLoad.put((String) l.get("id"), l));
        List<Line> lines = new ArrayList<>();
        String distribution = distributionAccount();
        String recharged = rechargedAccount();
        Map<String, BigDecimal> byVehicle = new LinkedHashMap<>();
        for (Charged c : charged) {
            if (c.amount().signum() == 0) {
                continue;
            }
            Map<String, Object> l = byLoad.get(c.loadId());
            Map<String, Object> dims = Obj.of("customerId", l.get("partnerId"), "fleetLoadId", c.loadId(),
                    "deliveryOrderId", l.get("deliveryOrderId"), "vehicleId", c.vehicleId());
            lines.add(new Line(distribution, JsNumbers.dec(c.amount()), null, (String) l.get("partnerId"), text + " · "
                    + l.get("code"), (String) l.get("branchId"), (String) l.get("costCenterId"), dims));
            byVehicle.merge(c.vehicleId(), c.amount(), BigDecimal::add);
        }
        byVehicle.forEach((vehicle, amount) -> lines.add(new Line(recharged, null, JsNumbers.dec(amount), null,
                text + " · vehicle recharge", null, null, Obj.of("vehicleId", vehicle))));
        Map<String, Object> je = posting.post(new PostingRequest("GEN", p.to().atStartOfDay(ZoneOffset.UTC).toInstant(),
                text, null, null, RUN_SOURCE, id, null, "costing:delivery_cost:" + org() + ":" + id, null, null, null,
                lines));
        String entryId = je == null ? null : (String) je.get("id");
        jdbc.sql("INSERT INTO \"DeliveryCostRun\" (id, \"organizationId\", number, \"periodFrom\", \"periodTo\","
                + " \"branchId\", \"emptyLegPolicy\", total, \"journalEntryId\", \"createdBy\") VALUES (?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?)")
                .params(id, org(), number, Date.valueOf(p.from()), Date.valueOf(p.to()), branch, policy, total,
                        entryId, TenantContext.userId())
                .update();
        for (Charged c : charged) {
            Map<String, Object> l = byLoad.get(c.loadId());
            jdbc.sql("INSERT INTO \"DeliveryCostLine\" (id, \"organizationId\", \"runId\", \"loadId\", \"vehicleId\","
                            + " \"customerId\", \"costCenterId\", \"loadedKm\", \"emptyKm\", tonnes, \"ratePerKm\", amount,"
                            + " \"perTonneKm\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, c.loadId(), c.vehicleId(), l.get("partnerId"),
                            l.get("costCenterId"), c.loadedKm(), c.emptyKm(), c.tonnes(), c.ratePerKm(), c.amount(),
                            c.perTonneKm())
                    .update();
        }
        audit.recordInTx(AuditInput.of("DeliveryCostRun", id, "post", null, Obj.of("number", number, "total", total)));
        return getRun(id);
    }

    @Transactional
    public Map<String, Object> reverseRun(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"DeliveryCostRun\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Delivery cost run not found");
        }
        if (!"posted".equals(r.get("status"))) {
            throw ApiException.badRequest("Only a posted run can be reversed");
        }
        String reversal = r.get("journalEntryId") == null ? null : (String) posting.reverse((String) r.get(
                "journalEntryId"), Instant.now(), "Reversal of " + r.get("number")).get("id");
        jdbc.sql("UPDATE \"DeliveryCostRun\" SET status = 'reversed', \"reversalEntryId\" = ?, \"reversedAt\" = now()"
                + " WHERE id = ?").params(reversal, id).update();
        audit.recordInTx(AuditInput.of("DeliveryCostRun", id, "cancel", null, Obj.of("reversalEntryId", reversal)));
        return getRun(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> runs() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"DeliveryCostRun\" WHERE \"organizationId\" = ? ORDER BY"
                + " \"createdAt\" DESC").param(org()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRun(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"DeliveryCostRun\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Delivery cost run not found");
        }
        r.put("lines", SqlRows.list(jdbc.sql("SELECT l.*, f.code AS \"loadCode\" FROM \"DeliveryCostLine\" l JOIN"
                + " \"FleetLoad\" f ON f.id = l.\"loadId\" WHERE l.\"runId\" = ? ORDER BY f.code").param(id)));
        return r;
    }

    private String policy() {
        String v = policies.current("empty_leg_policy", "organization", "");
        return POLICIES.contains(v) ? v : "loaded_leg";
    }

    private String branch(String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return null;
        }
        if (jdbc.sql("SELECT count(*) FROM \"Branch\" WHERE id = ? AND \"organizationId\" = ?").params(branchId, org())
                .query(Long.class).single() == 0) {
            throw ApiException.notFound("Branch not found");
        }
        return branchId;
    }

    private List<Map<String, Object>> loads(Period p, String branch) {
        return SqlRows.list(jdbc.sql("SELECT f.id, f.code, f.\"vehicleId\", f.\"distanceKm\", f.\"emptyKm\","
                + " coalesce(f.\"deliveredTonnes\", f.\"loadedTonnes\", f.\"plannedTonnes\", 0) AS tonnes,"
                + " f.\"costCenterId\", f.\"deliveryOrderId\", o.\"partnerId\", o.\"branchId\" FROM \"FleetLoad\" f JOIN"
                + " \"FleetDeliveryOrder\" o ON o.id = f.\"deliveryOrderId\" WHERE f.\"organizationId\" = ? AND f.carrier ="
                + " 'own' AND f.\"vehicleId\" IS NOT NULL AND f.status IN ('delivered', 'closed') AND f.\"deliveredAt\""
                + " >= ? AND f.\"deliveredAt\" < ? AND (?::text IS NULL OR o.\"branchId\" = ?) ORDER BY"
                + " f.\"deliveredAt\", f.code").params(org(),
                Timestamp.from(p.from().atStartOfDay(ZoneOffset.UTC).toInstant()),
                Timestamp.from(p.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()), branch, branch));
    }

    private List<Charged> charge(List<Map<String, Object>> loads, Period p, Map<String, BigDecimal> manual,
            String policy) {
        List<Trip> trips = new ArrayList<>();
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (Map<String, Object> l : loads) {
            String vehicle = (String) l.get("vehicleId");
            if (l.get("distanceKm") == null) {
                throw ApiException.badRequest("Trip " + l.get("code") + " has no distance; record it before costing");
            }
            if (!rates.containsKey(vehicle)) {
                rates.put(vehicle, rate(vehicle, p, manual));
            }
            trips.add(new Trip((String) l.get("id"), vehicle, SqlRows.dec(l, "distanceKm"), l.get("emptyKm") == null
                    ? BigDecimal.ZERO : SqlRows.dec(l, "emptyKm"), SqlRows.dec(l, "tonnes")));
        }
        return DistributionCostCalculator.charge(trips, rates, policy);
    }

    /** A manual rate, else the vehicle's cost per km from the fleet TCO report of the period. */
    private BigDecimal rate(String vehicleId, Period p, Map<String, BigDecimal> manual) {
        if (manual != null && manual.get(vehicleId) != null) {
            if (manual.get(vehicleId).signum() < 0) {
                throw ApiException.badRequest("ratePerKm must not be negative");
            }
            return manual.get(vehicleId);
        }
        FleetCostingService costing = fleet.getIfAvailable();
        if (costing != null && costing.report(new CostQuery(p.from(), p.to(), "vehicle", vehicleId, null, null))
                .get("totals") instanceof Map<?, ?> totals && totals.get("perKm") != null) {
            BigDecimal perKm = new BigDecimal(totals.get("perKm").toString());
            if (perKm.signum() > 0) {
                return perKm;
            }
        }
        String plate = jdbc.sql("SELECT \"registrationPlate\" FROM \"FleetVehicle\" WHERE id = ?").param(vehicleId)
                .query(String.class).optional().orElse(vehicleId);
        throw ApiException.badRequest("No cost per km for vehicle " + plate + " in the period; give a manual rate");
    }

    private static BigDecimal total(List<Charged> charged) {
        return charged.stream().map(Charged::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<Map<String, Object>> render(List<Charged> charged, List<Map<String, Object>> loads) {
        Map<String, Object> codes = new HashMap<>();
        loads.forEach(l -> codes.put((String) l.get("id"), l.get("code")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Charged c : charged) {
            out.add(Obj.of("loadId", c.loadId(), "loadCode", codes.get(c.loadId()), "vehicleId", c.vehicleId(),
                    "loadedKm", JsNumbers.dec(c.loadedKm()), "emptyKm", JsNumbers.dec(c.emptyKm()), "tonnes",
                    JsNumbers.dec(c.tonnes()), "ratePerKm", JsNumbers.dec(c.ratePerKm()), "amount",
                    JsNumbers.dec(c.amount()), "perTonneKm", c.perTonneKm() == null ? null
                            : JsNumbers.dec(c.perTonneKm())));
        }
        return out;
    }

    // ---- branch transfers ---------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> transfer(TransferInput in) {
        if (in.quantity() == null || in.quantity().signum() <= 0) {
            throw ApiException.badRequest("quantity must be greater than 0");
        }
        Map<String, Object> from = location(in.fromLocationId());
        Map<String, Object> to = location(in.toLocationId());
        String fromBranch = (String) from.get("branchId");
        String toBranch = (String) to.get("branchId");
        if (fromBranch == null || toBranch == null) {
            throw ApiException.badRequest("Both locations need a branch for a branch transfer");
        }
        if (fromBranch.equals(toBranch)) {
            throw ApiException.badRequest("Both locations belong to the same branch; use a stock transfer");
        }
        if (jdbc.sql("SELECT count(*) FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?").params(in.productId(),
                org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Product not found");
        }
        BigDecimal freight = in.freight() == null ? BigDecimal.ZERO : in.freight().setScale(2, RoundingMode.HALF_UP);
        if (freight.signum() < 0) {
            throw ApiException.badRequest("freight must not be negative");
        }
        String freightAccount = null;
        if (freight.signum() > 0) {
            freightAccount = freightSource(in.freightAccountId());
        }
        boolean clearing = in.useClearing() == null || in.useClearing();
        String id = UUID.randomUUID().toString();
        String number = seq.next("branch_transfer", "BTR/", 5);
        StockMove move = StockMove.transfer(in.productId(), in.fromLocationId(), in.toLocationId(), in.quantity())
                .source(TRANSFER_SOURCE, id).notes(in.notes() == null ? number : in.notes()).skipGl();
        stock.transfer(move);
        Map<String, Object> out = SqlRows.one(jdbc.sql("SELECT id, \"totalValue\" FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? AND \"referenceType\" = ? AND \"referenceId\" = ? AND type::text ="
                + " 'transfer_in' LIMIT 1").params(org(), TRANSFER_SOURCE, id));
        BigDecimal value = out == null ? BigDecimal.ZERO : SqlRows.dec(out, "totalValue").setScale(2,
                RoundingMode.HALF_UP);
        String text = "Branch transfer " + number;
        List<Line> lines = new ArrayList<>();
        if (value.signum() > 0) {
            String stockAccount = stockPosting.stockAccount(in.productId(), value);
            lines.add(new Line(stockAccount, JsNumbers.dec(value), null, null, text, toBranch, null, null));
            lines.add(new Line(stockAccount, null, JsNumbers.dec(value), null, text, fromBranch, null, null));
            if (clearing) {
                String c = clearingAccount();
                lines.add(new Line(c, JsNumbers.dec(value), null, null, text + " · due from destination", fromBranch,
                        null, Obj.of("counterpartBranchId", toBranch)));
                lines.add(new Line(c, null, JsNumbers.dec(value), null, text + " · due to source", toBranch, null,
                        Obj.of("counterpartBranchId", fromBranch)));
            }
        }
        List<Part> parts = List.of();
        if (freight.signum() > 0) {
            Layer layer = new Layer((String) out.get("id"), in.productId(), null, in.toLocationId(), value);
            parts = capitalisation.spread(freight, List.of(layer));
            for (Map.Entry<String, BigDecimal> e : capitalisation.debits(parts).entrySet()) {
                lines.add(new Line(e.getKey(), JsNumbers.dec(e.getValue()), null, null, text + " · freight", toBranch,
                        null, Obj.of("costEligibility", "transfer_capitalisable")));
            }
            lines.add(new Line(freightAccount, null, JsNumbers.dec(freight), null, text + " · freight", toBranch, null,
                    null));
        }
        String entryId = null;
        if (!lines.isEmpty()) {
            Map<String, Object> je = posting.post(new PostingRequest("INV", Instant.now(), text, null, null,
                    TRANSFER_SOURCE, id, null, "costing:branch_transfer:" + org() + ":" + id, null, null, null, lines));
            entryId = je == null ? null : (String) je.get("id");
        }
        if (!parts.isEmpty()) {
            capitalisation.record(parts, "transfer_freight", TRANSFER_SOURCE, id, entryId, text + " · freight");
        }
        jdbc.sql("INSERT INTO \"BranchTransfer\" (id, \"organizationId\", number, \"productId\", quantity,"
                        + " \"fromLocationId\", \"toLocationId\", \"fromBranchId\", \"toBranchId\", value, freight,"
                        + " \"freightAccountId\", \"useClearing\", \"transferLedgerId\", \"journalEntryId\", \"createdBy\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), number, in.productId(), in.quantity(), in.fromLocationId(), in.toLocationId(),
                        fromBranch, toBranch, value, freight, freightAccount, clearing, out == null ? null
                                : out.get("id"), entryId, TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("BranchTransfer", id, "create", null, Obj.of("number", number, "value", value,
                "freight", freight)));
        return transferRow(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> transfers() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"BranchTransfer\" WHERE \"organizationId\" = ? ORDER BY"
                + " \"createdAt\" DESC").param(org()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> transferRow(String id) {
        Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT * FROM \"BranchTransfer\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (t == null) {
            throw ApiException.notFound("Branch transfer not found");
        }
        return t;
    }

    /** Per-branch trial balance of the clearing account: Σ must be 0 for the organisation. */
    @Transactional
    public Map<String, Object> clearingBalances() {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT jl.\"branchId\", b.code AS \"branchCode\","
                + " sum(jl.\"baseDebit\" - jl.\"baseCredit\") AS balance FROM \"JournalLine\" jl JOIN \"JournalEntry\" e"
                + " ON e.id = jl.\"journalEntryId\" LEFT JOIN \"Branch\" b ON b.id = jl.\"branchId\" WHERE"
                + " jl.\"organizationId\" = ? AND jl.\"accountId\" = ? AND e.status <> 'draft' GROUP BY jl.\"branchId\","
                + " b.code ORDER BY b.code").params(org(), clearingAccount()));
        BigDecimal total = rows.stream().map(r -> SqlRows.dec(r, "balance")).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Obj.of("rows", rows, "total", JsNumbers.dec(total));
    }

    private Map<String, Object> location(String id) {
        Map<String, Object> l = id == null ? null : SqlRows.one(jdbc.sql("SELECT id, code, \"branchId\" FROM"
                + " \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(id, org()));
        if (l == null) {
            throw ApiException.notFound("Location not found");
        }
        return l;
    }

    /** Freight paid (cash, bank, payables) or reclassed from an expense classified as capitalisable. */
    private String freightSource(String accountId) {
        String key = accountId == null ? null : jdbc.sql("SELECT c.key FROM \"Account\" a JOIN \"AccountCategory\" c ON"
                + " c.id = a.\"categoryId\" WHERE a.id = ? AND a.\"organizationId\" = ?").params(accountId, org())
                .query(String.class).optional().orElse(null);
        if (key == null) {
            throw ApiException.badRequest("freightAccountId should name the account the freight is paid from");
        }
        if (Set.of("operating_expense", "cost_of_goods_sold", "other_expense").contains(key)) {
            policies.assertCapitalisable("account", accountId, "The freight account", true);
        }
        return accountId;
    }

    record Period(LocalDate from, LocalDate to) {}

    private static Period period(String fromText, String toText) {
        LocalDate from = day(fromText, "from");
        LocalDate to = day(toText, "to");
        if (to.isBefore(from)) {
            throw ApiException.badRequest("to must not be before from");
        }
        if (to.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw ApiException.badRequest("A run cannot end in the future");
        }
        return new Period(from, to);
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

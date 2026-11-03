package com.inventoryerp.fleet.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.fleet.application.FleetReportCommands.CancelFreightBill;
import com.inventoryerp.fleet.application.FleetReportCommands.FreightBillQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.FreightCandidateQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.GenerateFreightBills;
import com.inventoryerp.fleet.application.FleetReportCommands.InboundFreight;
import com.inventoryerp.fleet.application.FleetReportCommands.MatchFreightBill;
import com.inventoryerp.fleet.domain.FleetFreightBill;
import com.inventoryerp.fleet.domain.FleetFreightBillLine;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.VendorBillService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.procurement.application.LandedCostVoucherService;
import com.inventoryerp.procurement.application.LandedCostVoucherService.ChargeInput;
import com.inventoryerp.procurement.application.LandedCostVoucherService.VoucherInput;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Third-party transporter freight bills (fleet 1.9, ADR-S009 §6).
 *
 * <p><b>Outbound</b>: {@link #generate} collects the closed transporter trips of a period that are not on a live
 * bill into one draft bill per transporter (a trip is billed once: partial unique index on the line's trip, plus an
 * organization advisory lock while generating). {@link #match} compares the supplier's invoice with the bill: within
 * the tolerance (org settings {@code fleet.freightMatchTolerancePct} / {@code fleet.freightMatchToleranceAmount})
 * — or with the variance accepted by a {@code fleet_freight:approve_variance} holder — it raises the draft AP vendor
 * bill ({@link VendorBillService}, line on the clearing account 2169 at the supplier's amount; AP approves / posts it:
 * Dr clearing / Cr supplier) and posts the accrual Dr 6460 outbound freight (each trip's cost centre) / Cr 2169, the
 * variance on 6460 (key {@code fleet:freight:<billId>}, balanced, idempotent). Outside the tolerance the bill is
 * {@code disputed}. Cancelling a billed bill reverses the accrual (refused while its AP bill is posted).
 *
 * <p><b>Inbound</b>: {@link #inbound} capitalises a transporter's freight on a posted goods receipt as landed cost
 * (ADR-S007): a landed cost voucher with one capitalised freight charge whose supplier bill procurement generates,
 * approved and posted at once (Dr inventory / COGS for the consumed share, Cr landed-cost clearing; the generated
 * bill Dr clearing / Cr supplier). Trips are outbound deliveries today, so inbound freight is entered per receipt.
 */
@Service
public class FleetFreightBillService {

    static final String SOURCE = "fleet_freight_bill";
    static final String SETTING_PCT = "fleet.freightMatchTolerancePct";
    static final String SETTING_AMOUNT = "fleet.freightMatchToleranceAmount";

    private final FleetDb db;
    private final VendorBillService vendorBills;
    private final LandedCostVoucherService landedCosts;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final SequenceService sequence;
    private final SettingResolverService settings;
    private final AuditService audit;
    private final EventBus events;

    public FleetFreightBillService(FleetDb db, VendorBillService vendorBills, LandedCostVoucherService landedCosts,
            PostingService posting, AccountResolverService accounts, SequenceService sequence,
            SettingResolverService settings, AuditService audit, EventBus events) {
        this.db = db;
        this.vendorBills = vendorBills;
        this.landedCosts = landedCosts;
        this.posting = posting;
        this.accounts = accounts;
        this.sequence = sequence;
        this.settings = settings;
        this.audit = audit;
        this.events = events;
    }

    /** Outbound freight expense (6460, {@code fleet_freight_expense}). */
    public String freightExpenseAccount() {
        return accounts.ensureByCode("6460", "Outbound Freight & Transport", "operating_expense",
                "fleet_freight_expense");
    }

    /** Clearing between the accrued freight and the transporter's AP bill (2169, {@code fleet_freight_accrual}). */
    public String freightClearingAccount() {
        return accounts.ensureByCode("2169", "Transport Freight Bills to Receive", "current_liability",
                "fleet_freight_accrual");
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(FreightBillQuery q) {
        int page = q.page() == null ? 1 : q.page();
        int size = q.pageSize() == null ? 25 : q.pageSize();
        StringBuilder where = new StringBuilder(" WHERE b.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        FleetIncidentService.eq(where, params, "b.status", q.status());
        FleetIncidentService.eq(where, params, "b.direction", q.direction());
        FleetIncidentService.eq(where, params, "b.\"transporterPartnerId\"", q.transporterPartnerId());
        if (FleetVehicleService.notBlank(q.search())) {
            where.append(" AND (b.code ILIKE ? OR b.\"supplierReference\" ILIKE ? OR p.name ILIKE ?)");
            String like = "%" + q.search().trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        String from = " FROM \"FleetFreightBill\" b JOIN \"Partner\" p ON p.id = b.\"transporterPartnerId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT b.*, p.name AS \"p_name\"" + from + where
                + " ORDER BY b.\"createdAt\" DESC, b.code DESC LIMIT ? OFFSET ?").params(pageParams))) {
            Map<String, Object> m = SqlRows.as(FleetFreightBill.class, r);
            m.put("transporter", Obj.of("id", r.get("transporterPartnerId"), "name", r.get("p_name")));
            rows.add(m);
        }
        return PageResult.of(rows, page, size, total);
    }

    /** Detail: transporter, lines with their trip and delivery order, AP bill, journal entries, landed cost. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> b = require(id, false);
        Map<String, Object> out = new LinkedHashMap<>(b);
        out.put("transporter", SqlRows.one(db.jdbc().sql("SELECT id, code, name FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(b.get("transporterPartnerId"), FleetDb.org())));
        out.put("lines", SqlRows.list(db.jdbc().sql("""
                SELECT l.*, t.code AS "tripCode", t."closedAt", t."plannedDate", t."transporterPlate",
                       o.code AS "deliveryOrderCode", pa.name AS "customerName"
                FROM "FleetFreightBillLine" l LEFT JOIN "FleetLoad" t ON t.id = l."tripId"
                LEFT JOIN "FleetDeliveryOrder" o ON o.id = l."deliveryOrderId"
                LEFT JOIN "Partner" pa ON pa.id = o."partnerId"
                WHERE l."organizationId" = ? AND l."freightBillId" = ? ORDER BY t."closedAt", t.code, l."createdAt\"""")
                .params(FleetDb.org(), id)));
        out.put("vendorBill", b.get("vendorBillId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT id,"
                + " \"documentNumber\", status::text AS status, \"totalAmount\", \"amountResidual\","
                + " \"paymentStatus\"::text AS \"paymentStatus\", reference FROM \"Document\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(b.get("vendorBillId"), FleetDb.org())));
        List<String> entries = new ArrayList<>();
        for (Object e : new Object[] {b.get("journalEntryId"), b.get("reversalEntryId")}) {
            if (e != null) {
                entries.add((String) e);
            }
        }
        out.put("journalEntries", entries.isEmpty() ? List.of() : SqlRows.list(db.jdbc().sql("SELECT id,"
                + " \"entryNumber\", \"postingDate\", description, status::text AS status FROM \"JournalEntry\" WHERE"
                + " \"organizationId\" = ? AND id = ANY(?::text[]) ORDER BY \"createdAt\"").params(FleetDb.org(),
                entries.toArray(String[]::new))));
        out.put("landedCost", b.get("landedCostId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT id, code,"
                + " status FROM \"LandedCost\" WHERE id = ? AND \"organizationId\" = ?").params(b.get("landedCostId"),
                FleetDb.org())));
        out.put("tolerance", tolerance());
        return out;
    }

    /**
     * Pickers of the freight screens: supplier partners (transporters, {@code search}) and the latest posted goods
     * receipts (inbound freight).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> lookups(String search) {
        String like = FleetVehicleService.notBlank(search) ? "%" + search.trim() + "%" : "%";
        List<Map<String, Object>> transporters = SqlRows.list(db.jdbc().sql("SELECT id, code, name FROM \"Partner\""
                + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isSupplier\" = true AND (name ILIKE ?"
                + " OR code ILIKE ?) ORDER BY name LIMIT 50").params(FleetDb.org(), like, like));
        List<Map<String, Object>> receipts = SqlRows.list(db.jdbc().sql("SELECT g.id, g.\"receiptNumber\","
                + " g.\"receivedAt\", p.name AS \"partnerName\" FROM \"GoodsReceiptNote\" g LEFT JOIN \"Partner\" p ON"
                + " p.id = g.\"partnerId\" WHERE g.\"organizationId\" = ? AND g.status::text = 'posted' AND"
                + " (g.\"receiptNumber\" ILIKE ? OR p.name ILIKE ?) ORDER BY g.\"receivedAt\" DESC NULLS LAST LIMIT 50")
                .params(FleetDb.org(), like, like));
        return Obj.of("transporters", transporters, "goodsReceipts", receipts, "tolerance", tolerance());
    }

    /** Closed transporter trips with freight that are not on a live bill, per transporter, with totals. */
    @Transactional(readOnly = true)
    public Map<String, Object> candidates(FreightCandidateQuery q) {
        FleetCostingService.Period p = FleetCostingService.period(q.from(), q.to());
        List<Map<String, Object>> trips = candidateTrips(q.transporterPartnerId(), p, false);
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map<String, Object> t : trips) {
            Map<String, Object> g = groups.computeIfAbsent((String) t.get("transporterPartnerId"), k -> Obj.of(
                    "transporterPartnerId", k, "transporterName", t.get("transporterName"), "trips",
                    new ArrayList<Map<String, Object>>(), "total", BigDecimal.ZERO));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) g.get("trips");
            list.add(t);
            g.put("total", ((BigDecimal) g.get("total")).add((BigDecimal) t.get("freightAmount")));
        }
        return Obj.of("from", p.from(), "to", p.to(), "transporters", new ArrayList<>(groups.values()));
    }

    private List<Map<String, Object>> candidateTrips(String transporterId, FleetCostingService.Period p,
            boolean lock) {
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ? AND t.carrier = 'transporter' AND"
                + " t.status = 'closed' AND t.\"transporterPartnerId\" IS NOT NULL AND COALESCE(t.\"freightAmount\", 0)"
                + " > 0 AND t.\"closedAt\" >= ? AND t.\"closedAt\" < ? AND NOT EXISTS (SELECT 1 FROM"
                + " \"FleetFreightBillLine\" l WHERE l.\"organizationId\" = t.\"organizationId\" AND l.\"tripId\" ="
                + " t.id AND l.status = 'active')");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), SqlRows.ts(p.start()), SqlRows.ts(p.end())));
        FleetIncidentService.eq(where, params, "t.\"transporterPartnerId\"", transporterId);
        return SqlRows.list(db.jdbc().sql("""
                SELECT t.id, t.code, t."deliveryOrderId", t."transporterPartnerId", t."freightBasis", t."freightRate",
                       t."freightAmount", COALESCE(t."deliveredTonnes", t."loadedTonnes", t."plannedTonnes") AS tonnes,
                       t."distanceKm", t."costCenterId", t."closedAt", t."transporterPlate", o.code AS "deliveryOrderCode",
                       o."costCenterId" AS "orderCostCenterId", o."branchId", p.name AS "transporterName"
                FROM "FleetLoad" t JOIN "FleetDeliveryOrder" o ON o.id = t."deliveryOrderId"
                JOIN "Partner" p ON p.id = t."transporterPartnerId"
                """ + where + " ORDER BY p.name, t.\"closedAt\", t.code" + (lock ? " FOR UPDATE OF t" : ""))
                .params(params));
    }

    // ------------------------------------------------------------------ outbound

    /**
     * One draft bill per transporter with the period's unbilled closed trips (closing day in {@code [from, to]}).
     * Running it again only picks trips closed since or released by a cancelled bill.
     */
    @Transactional
    public Map<String, Object> generate(GenerateFreightBills in) {
        FleetCostingService.Period p = FleetCostingService.period(in.from(), in.to());
        if (FleetVehicleService.notBlank(in.transporterPartnerId()) && !db.exists("Partner",
                in.transporterPartnerId(), true)) {
            throw ApiException.badRequest("Transporter not found");
        }
        // Serialise generation per organization: a trip lands on one bill even when two runs overlap.
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)::text").param("fleet_freight_bill:" + FleetDb.org())
                .query().listOfRows();
        List<Map<String, Object>> trips = candidateTrips(in.transporterPartnerId(), p, true);
        Map<String, List<Map<String, Object>>> byTransporter = new LinkedHashMap<>();
        for (Map<String, Object> t : trips) {
            byTransporter.computeIfAbsent((String) t.get("transporterPartnerId"), k -> new ArrayList<>()).add(t);
        }
        String currency = db.jdbc().sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?")
                .param(FleetDb.org()).query(String.class).optional().orElse(null);
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byTransporter.entrySet()) {
            BigDecimal total = BigDecimal.ZERO;
            for (Map<String, Object> t : e.getValue()) {
                total = total.add((BigDecimal) t.get("freightAmount"));
            }
            Map<String, Object> first = e.getValue().getFirst();
            Map<String, Object> bill = db.insert(FleetFreightBill.class, Obj.of("code", sequence.next(
                            "fleet_freight_bill", "FB-", 5), "direction", "outbound", "transporterPartnerId", e.getKey(),
                    "periodFrom", p.from(), "periodTo", p.to(), "status", "draft", "tripCount", e.getValue().size(),
                    "totalAmount", total.setScale(2, RoundingMode.HALF_UP), "currencyCode", currency,
                    "varianceAccepted", false, "branchId", first.get("branchId"), "notes",
                    FleetVehicleService.trim(in.notes()), "createdBy", TenantContext.userId(), "updatedBy",
                    TenantContext.userId()));
            for (Map<String, Object> t : e.getValue()) {
                db.insert(FleetFreightBillLine.class, Obj.of("freightBillId", bill.get("id"), "tripId", t.get("id"),
                        "deliveryOrderId", t.get("deliveryOrderId"), "description", "Trip " + t.get("code") + " · "
                                + t.get("deliveryOrderCode") + (t.get("transporterPlate") == null ? ""
                                : " · " + t.get("transporterPlate")),
                        "freightBasis", t.get("freightBasis"), "freightRate", t.get("freightRate"), "tonnes",
                        t.get("tonnes"), "distanceKm", t.get("distanceKm"), "amount", ((BigDecimal) t.get(
                                "freightAmount")).setScale(2, RoundingMode.HALF_UP), "costCenterId",
                        t.get("costCenterId") != null ? t.get("costCenterId") : t.get("orderCostCenterId"),
                        "status", "active"));
            }
            audit.recordInTx(AuditInput.of("FleetFreightBill", (String) bill.get("id"), "create", null, bill));
            created.add(bill);
        }
        return Obj.of("from", p.from(), "to", p.to(), "created", created, "trips", trips.size());
    }

    /** Take a trip off a draft / disputed bill (it can be billed again later). */
    @Transactional
    public Map<String, Object> removeLine(String id, String lineId) {
        Map<String, Object> b = require(id, true);
        assertOpen(b);
        Map<String, Object> l = db.byId(FleetFreightBillLine.class, lineId, true);
        if (l == null || !id.equals(l.get("freightBillId")) || !"active".equals(l.get("status"))) {
            throw ApiException.notFound("Freight bill line not found");
        }
        db.update(FleetFreightBillLine.class, lineId, Obj.of("status", "cancelled"));
        refreshTotals(id);
        audit.recordInTx(AuditInput.of("FleetFreightBill", id, "update", Obj.of("lineId", lineId),
                Obj.of("removed", l.get("tripId"))));
        return get(id);
    }

    /** Match the supplier's invoice (money: Idempotency-Key). See the class comment. */
    @Transactional
    public Map<String, Object> match(String id, MatchFreightBill in) {
        Map<String, Object> b = require(id, true);
        String code = (String) b.get("code");
        if ("billed".equals(b.get("status"))) {
            if (in.supplierReference().trim().equals(b.get("supplierReference"))) {
                return get(id);
            }
            throw ApiException.conflict("Freight bill " + code + " is already matched to supplier invoice "
                    + b.get("supplierReference"));
        }
        assertOpen(b);
        if (!"outbound".equals(b.get("direction"))) {
            throw ApiException.badRequest("Freight bill " + code + " is an inbound landed cost charge");
        }
        List<Map<String, Object>> lines = activeLines(id);
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Freight bill " + code + " has no trips");
        }
        if (in.supplierInvoiceDate().isAfter(LocalDate.now(BusinessZone.get()))) {
            throw ApiException.badRequest("The supplier invoice date cannot be in the future");
        }
        BigDecimal total = SqlRows.dec(b, "totalAmount");
        BigDecimal supplier = in.supplierAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal variance = supplier.subtract(total);
        Map<String, Object> tol = tolerance();
        BigDecimal allowed = FleetCostCalculator.allowedVariance(total, (BigDecimal) tol.get("pct"),
                (BigDecimal) tol.get("amount"));
        BigDecimal pct = FleetCostCalculator.pct(variance, total, 3);
        boolean within = variance.abs().compareTo(allowed) <= 0;
        boolean accept = Boolean.TRUE.equals(in.acceptVariance());
        if (!within && accept && !TenantContext.has(Permissions.FLEET_FREIGHT__APPROVE_VARIANCE)) {
            throw ApiException.forbidden("Accepting a freight variance requires the "
                    + Permissions.FLEET_FREIGHT__APPROVE_VARIANCE + " permission");
        }
        Map<String, Object> data = Obj.of("supplierReference", in.supplierReference().trim(), "supplierInvoiceDate",
                in.supplierInvoiceDate(), "supplierAmount", supplier, "varianceAmount", variance, "variancePct", pct,
                "allowedVariance", allowed, "updatedBy", TenantContext.userId());
        if (FleetVehicleService.notBlank(in.notes())) {
            data.put("notes", in.notes().trim());
        }
        if (!within && !accept) {
            data.put("status", "disputed");
            db.update(FleetFreightBill.class, id, data);
            audit.recordInTx(AuditInput.of("FleetFreightBill", id, "update", Obj.of("status", b.get("status")),
                    Obj.of("status", "disputed", "variance", variance, "allowed", allowed)));
            return get(id);
        }
        String clearing = freightClearingAccount();
        String expense = freightExpenseAccount();
        String partnerName = db.jdbc().sql("SELECT name FROM \"Partner\" WHERE id = ?").param(
                b.get("transporterPartnerId")).query(String.class).optional().orElse("");
        String label = "Freight " + code + " · " + partnerName + " " + in.supplierReference().trim();
        Map<String, Object> bill = vendorBills.create(new VendorBillService.BillInput(
                (String) b.get("transporterPartnerId"), in.supplierInvoiceDate().toString(),
                in.dueDate() == null ? null : in.dueDate().toString(), null, in.supplierReference().trim(),
                "Transport freight " + code + " · " + b.get("periodFrom") + " to " + b.get("periodTo") + " · "
                        + lines.size() + " trip(s)", (String) b.get("branchId"), null,
                List.of(new LineInput(null, null, null, null, clearing, "Freight " + code + " · " + lines.size()
                        + " trip(s)", BigDecimal.ONE, supplier, null, null, null, null, null, null, null, null))));
        List<Line> gl = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            Map<String, Object> dims = Obj.of("freightBillId", id, "tripId", l.get("tripId"), "deliveryOrderId",
                    l.get("deliveryOrderId"));
            gl.add(new Line(expense, JsNumbers.dec(SqlRows.dec(l, "amount")), null, null,
                    (String) l.get("description"), null, (String) l.get("costCenterId"), dims));
        }
        Map<String, Object> billDims = Obj.of("freightBillId", id);
        if (variance.signum() > 0) {
            gl.add(new Line(expense, JsNumbers.dec(variance), null, null, "Freight variance " + code, null, null,
                    billDims));
        } else if (variance.signum() < 0) {
            gl.add(new Line(expense, null, JsNumbers.dec(variance.negate()), null, "Freight variance " + code, null,
                    null, billDims));
        }
        gl.add(new Line(clearing, null, JsNumbers.dec(supplier), (String) b.get("transporterPartnerId"), label, null,
                null, billDims));
        String key = "fleet:freight:" + id;
        Map<String, Object> je = posting.post(new PostingRequest("GEN", in.supplierInvoiceDate().atStartOfDay(
                BusinessZone.get()).toInstant(), label, null, null, SOURCE, id, null, key, (String) b.get("branchId"),
                null, billDims, gl));
        data.put("status", "billed");
        data.put("varianceAccepted", !within);
        data.put("varianceAcceptedBy", !within ? TenantContext.userId() : null);
        data.put("vendorBillId", bill.get("id"));
        data.put("vendorBillNumber", bill.get("documentNumber"));
        data.put("journalEntryId", je.get("id"));
        data.put("postingKey", key);
        data.put("matchedAt", Instant.now());
        data.put("matchedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetFreightBill.class, id, data);
        audit.recordInTx(AuditInput.of("FleetFreightBill", id, "post", Obj.of("status", b.get("status")),
                Obj.of("status", "billed", "vendorBillId", bill.get("id"), "journalEntryId", je.get("id"),
                        "supplierAmount", supplier, "variance", variance)));
        events.publish(DomainEvents.FLEET_FREIGHT_BILL_MATCHED, Obj.of("organizationId", FleetDb.org(),
                "freightBillId", id, "code", code, "direction", "outbound", "transporterPartnerId",
                b.get("transporterPartnerId"), "amount", supplier.toPlainString(), "varianceAmount",
                variance.toPlainString(), "vendorBillId", bill.get("id"), "journalEntryId", je.get("id"),
                "tripCount", after.get("tripCount")));
        return get(id);
    }

    /**
     * Cancel: a draft / disputed bill releases its trips; a billed one also reverses its accrual (refused while the AP
     * bill is posted — cancel it there first; a draft AP bill is left for AP to discard); an inbound charge reverses
     * its landed cost voucher (which cancels the generated supplier bill).
     */
    @Transactional
    public Map<String, Object> cancel(String id, CancelFreightBill in) {
        Map<String, Object> b = require(id, true);
        String code = (String) b.get("code");
        if ("cancelled".equals(b.get("status"))) {
            return get(id);
        }
        String reason = in.reason().trim();
        String reversal = null;
        if ("inbound".equals(b.get("direction"))) {
            if (b.get("landedCostId") != null) {
                Map<String, Object> lc = landedCosts.get((String) b.get("landedCostId"));
                if ("posted".equals(lc.get("status"))) {
                    landedCosts.reverse((String) b.get("landedCostId"));
                } else if (!"reversed".equals(lc.get("status")) && !"cancelled".equals(lc.get("status"))) {
                    landedCosts.cancel((String) b.get("landedCostId"));
                }
            }
        } else if ("billed".equals(b.get("status"))) {
            if (b.get("vendorBillId") != null) {
                Map<String, Object> doc = SqlRows.one(db.jdbc().sql("SELECT \"documentNumber\", status::text AS status"
                        + " FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?").params(b.get("vendorBillId"),
                        FleetDb.org()));
                if (doc != null && "posted".equals(doc.get("status"))) {
                    throw ApiException.badRequest("Vendor bill " + doc.get("documentNumber") + " is posted in Accounts"
                            + " Payable; cancel it there first, then cancel freight bill " + code);
                }
            }
            if (b.get("journalEntryId") != null) {
                reversal = (String) posting.reverse((String) b.get("journalEntryId"), Instant.now(),
                        "Reversal · freight bill " + code + " · " + reason).get("id");
            }
        }
        db.jdbc().sql("UPDATE \"FleetFreightBillLine\" SET status = 'cancelled', \"updatedAt\" = " + FleetDb.NOW
                + " WHERE \"organizationId\" = ? AND \"freightBillId\" = ? AND status = 'active'")
                .params(FleetDb.org(), id).update();
        db.update(FleetFreightBill.class, id, Obj.of("status", "cancelled", "cancelledAt", Instant.now(),
                "cancelledBy", TenantContext.userId(), "cancelReason", reason, "reversalEntryId", reversal,
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("FleetFreightBill", id, "cancel", Obj.of("status", b.get("status")),
                Obj.of("status", "cancelled", "reason", reason, "reversalEntryId", reversal)));
        return get(id);
    }

    // ------------------------------------------------------------------ inbound (landed cost)

    /** Capitalise inbound freight on a posted goods receipt (money: Idempotency-Key). See the class comment. */
    @Transactional
    public Map<String, Object> inbound(InboundFreight in) {
        String ref = in.supplierReference().trim();
        Map<String, Object> existing = SqlRows.one(FleetFreightBill.class, db.jdbc().sql("SELECT * FROM"
                + " \"FleetFreightBill\" WHERE \"organizationId\" = ? AND direction = 'inbound' AND \"goodsReceiptId\" = ?"
                + " AND \"supplierReference\" = ? AND status <> 'cancelled'").params(FleetDb.org(), in.goodsReceiptId(),
                ref));
        if (existing != null) {
            return get((String) existing.get("id"));
        }
        Map<String, Object> grn = SqlRows.one(db.jdbc().sql("SELECT id, \"receiptNumber\", status::text AS status,"
                + " \"branchId\" FROM \"GoodsReceiptNote\" WHERE id = ? AND \"organizationId\" = ?")
                .params(in.goodsReceiptId(), FleetDb.org()));
        if (grn == null) {
            throw ApiException.notFound("Goods receipt not found");
        }
        if (!"posted".equalsIgnoreCase(String.valueOf(grn.get("status")))) {
            throw ApiException.badRequest("Goods receipt " + grn.get("receiptNumber") + " is not posted; inbound"
                    + " freight is capitalised on posted receipts");
        }
        if (!db.exists("Partner", in.transporterPartnerId(), true)) {
            throw ApiException.badRequest("Transporter not found");
        }
        String method = in.allocationMethod() == null ? "value" : in.allocationMethod();
        BigDecimal amount = in.amount().setScale(2, RoundingMode.HALF_UP);
        String currency = db.jdbc().sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?")
                .param(FleetDb.org()).query(String.class).optional().orElse(null);
        String code = sequence.next("fleet_freight_bill", "FB-", 5);
        Map<String, Object> bill = db.insert(FleetFreightBill.class, Obj.of("code", code, "direction", "inbound",
                "transporterPartnerId", in.transporterPartnerId(), "periodFrom", in.invoiceDate(), "periodTo",
                in.invoiceDate(), "status", "draft", "tripCount", 0, "totalAmount", amount, "currencyCode", currency,
                "supplierReference", ref, "supplierInvoiceDate", in.invoiceDate(), "supplierAmount", amount,
                "varianceAmount", BigDecimal.ZERO.setScale(2), "varianceAccepted", false, "goodsReceiptId",
                in.goodsReceiptId(), "branchId", grn.get("branchId"), "notes", FleetVehicleService.trim(in.notes()),
                "createdBy", TenantContext.userId(), "updatedBy", TenantContext.userId()));
        String id = (String) bill.get("id");
        db.insert(FleetFreightBillLine.class, Obj.of("freightBillId", id, "description", "Inbound freight · GRN "
                + grn.get("receiptNumber") + " · " + ref, "amount", amount, "costCenterId",
                FleetVehicleService.trim(in.costCenterId()), "status", "active"));
        Map<String, Object> voucher = landedCosts.create(new VoucherInput(null, List.of(in.goodsReceiptId()),
                in.invoiceDate().toString(), "Inbound freight " + code + " · " + ref,
                FleetVehicleService.trim(in.costCenterId()), List.of(new ChargeInput(null, "freight",
                        "Inbound freight " + code, in.transporterPartnerId(), ref, in.invoiceDate().toString(), null,
                        null, amount, null, "capitalize", method, null, null, FleetVehicleService.trim(
                                in.costCenterId()), null, null, null, null, in.dueDate() == null ? null
                                : in.dueDate().toString()))));
        String voucherId = (String) voucher.get("id");
        landedCosts.submit(voucherId);
        landedCosts.approve(voucherId);
        Map<String, Object> posted = landedCosts.post(voucherId);
        String vendorBillId = null;
        String vendorBillNumber = null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> charges = (List<Map<String, Object>>) posted.get("charges");
        for (Map<String, Object> c : charges) {
            @SuppressWarnings("unchecked")
            Map<String, Object> vb = (Map<String, Object>) c.get("vendorBill");
            if (vb != null) {
                vendorBillId = (String) vb.get("id");
                vendorBillNumber = (String) vb.get("documentNumber");
            }
        }
        db.update(FleetFreightBill.class, id, Obj.of("status", "billed", "landedCostId", voucherId, "journalEntryId",
                posted.get("journalEntryId"), "vendorBillId", vendorBillId, "vendorBillNumber", vendorBillNumber,
                "matchedAt", Instant.now(), "matchedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("FleetFreightBill", id, "post", null, Obj.of("direction", "inbound",
                "landedCostId", voucherId, "goodsReceiptId", in.goodsReceiptId(), "amount", amount)));
        events.publish(DomainEvents.FLEET_FREIGHT_BILL_MATCHED, Obj.of("organizationId", FleetDb.org(),
                "freightBillId", id, "code", code, "direction", "inbound", "transporterPartnerId",
                in.transporterPartnerId(), "amount", amount.toPlainString(), "varianceAmount", "0.00",
                "vendorBillId", vendorBillId, "journalEntryId", posted.get("journalEntryId"), "tripCount", 0));
        return get(id);
    }

    // ------------------------------------------------------------------ helpers

    /** Match tolerance from the org settings: {@code pct} of the bill total, or at least {@code amount}. */
    Map<String, Object> tolerance() {
        return Obj.of("pct", number(SETTING_PCT, BigDecimal.ONE), "amount", number(SETTING_AMOUNT, BigDecimal.ZERO));
    }

    private BigDecimal number(String key, BigDecimal fallback) {
        Object v = settings.resolve(key, SettingResolverService.SettingContext.EMPTY);
        if (v == null) {
            return fallback;
        }
        try {
            BigDecimal d = new BigDecimal(String.valueOf(v));
            return d.signum() < 0 ? fallback : d;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Map<String, Object> require(String id, boolean lock) {
        return db.require(FleetFreightBill.class, id, lock, "Freight bill not found");
    }

    private static void assertOpen(Map<String, Object> b) {
        if (!List.of("draft", "disputed").contains(b.get("status"))) {
            throw ApiException.badRequest("Freight bill " + b.get("code") + " is " + b.get("status")
                    + " and cannot be changed");
        }
    }

    private List<Map<String, Object>> activeLines(String id) {
        return db.list(FleetFreightBillLine.class, " AND \"freightBillId\" = ? AND status = 'active' ORDER BY"
                + " \"createdAt\", id", id);
    }

    private void refreshTotals(String id) {
        List<Map<String, Object>> lines = activeLines(id);
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            total = total.add(SqlRows.dec(l, "amount"));
        }
        db.update(FleetFreightBill.class, id, Obj.of("tripCount", lines.size(), "totalAmount",
                total.setScale(2, RoundingMode.HALF_UP), "updatedBy", TenantContext.userId()));
    }
}

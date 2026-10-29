package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FuelCommands.FuelCardInput;
import com.inventoryerp.fleet.application.FuelCommands.FuelCardPatch;
import com.inventoryerp.fleet.domain.FleetFuelCard;
import com.inventoryerp.fleet.domain.FleetVehicle;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fuel-card register (fleet 1.5): masked number (the full number is never stored, {@link FuelCardNumbers}), provider
 * (partner), assigned vehicle and driver (hr employee), status, expiry and a spend limit per day / week / month. Each
 * card row comes back with its spend in the current limit period.
 */
@Service
public class FleetFuelCardService {

    static final String AUDIT_ENTITY = "FleetFuelCard";

    private static final String SELECT = """
            SELECT c.*, v.code AS "v_code", v."registrationPlate" AS "v_plate", p.code AS "p_code", p.name AS "p_name",
                   e."employeeCode" AS "e_code", e."firstName" AS "e_first", e."lastName" AS "e_last"
            FROM "FleetFuelCard" c
            LEFT JOIN "FleetVehicle" v ON v.id = c."vehicleId" AND v."organizationId" = c."organizationId"
            LEFT JOIN "Partner" p ON p.id = c."providerPartnerId" AND p."organizationId" = c."organizationId"
            LEFT JOIN "HrEmployee" e ON e.id = c."employeeId" AND e."organizationId" = c."organizationId"
            """;

    private final FleetDb db;
    private final AuditService audit;

    public FleetFuelCardService(FleetDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    /** Paged cards ({@code search} = last four digits or label). */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String search, String status, String vehicleId, Integer page,
            Integer pageSize) {
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null ? 20 : Math.max(1, Math.min(200, pageSize));
        StringBuilder where = new StringBuilder(" WHERE c.\"organizationId\" = ? AND c.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (FleetVehicleService.notBlank(search)) {
            where.append(" AND (c.\"last4\" ILIKE ? OR c.label ILIKE ? OR v.code ILIKE ?)");
            String like = "%" + search.trim().replace("%", "\\%").replace("_", "\\_") + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (FleetVehicleService.notBlank(status)) {
            where.append(" AND c.status = ?");
            params.add(status);
        }
        if (FleetVehicleService.notBlank(vehicleId)) {
            where.append(" AND c.\"vehicleId\" = ?");
            params.add(vehicleId);
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"FleetFuelCard\" c LEFT JOIN \"FleetVehicle\" v ON v.id ="
                + " c.\"vehicleId\"" + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((p - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(SELECT + where + " ORDER BY c.status, c.\"last4\","
                + " c.id LIMIT ? OFFSET ?").params(pageParams))) {
            rows.add(render(r));
        }
        return PageResult.of(rows, p, size, total);
    }

    /** One card with references and current-period spend. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = SqlRows.one(db.jdbc().sql(SELECT + " WHERE c.id = ? AND c.\"organizationId\" = ? AND"
                + " c.\"deletedAt\" IS NULL").params(id, FleetDb.org()));
        if (r == null) {
            throw ApiException.notFound("Fuel card not found");
        }
        return render(r);
    }

    /** The card row (404 when unknown). */
    Map<String, Object> require(String id) {
        return db.require(FleetFuelCard.class, id, false, "Fuel card not found");
    }

    /** Register a card; the number is masked and hashed, never stored in clear. */
    @Transactional
    public Map<String, Object> create(FuelCardInput in) {
        String number = FuelCardNumbers.normalize(in.cardNumber());
        String hash = FuelCardNumbers.hash(FleetDb.org(), number);
        long dup = db.jdbc().sql("SELECT count(*) FROM \"FleetFuelCard\" WHERE \"organizationId\" = ? AND"
                + " \"numberHash\" = ? AND \"deletedAt\" IS NULL").params(FleetDb.org(), hash).query(Long.class)
                .single();
        if (dup > 0) {
            throw ApiException.conflict("Fuel card " + FuelCardNumbers.mask(number) + " is already registered");
        }
        assertRefs(in.providerPartnerId(), in.vehicleId(), in.employeeId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("maskedNumber", FuelCardNumbers.mask(number));
        data.put("last4", FuelCardNumbers.last4(number));
        data.put("numberHash", hash);
        data.put("label", FleetVehicleService.trim(in.label()));
        data.put("providerPartnerId", blank(in.providerPartnerId()));
        data.put("vehicleId", blank(in.vehicleId()));
        data.put("employeeId", blank(in.employeeId()));
        data.put("status", in.status() == null ? "active" : in.status());
        data.put("expiresOn", in.expiresOn());
        data.put("limitAmount", in.limitAmount());
        data.put("limitPeriod", in.limitPeriod() == null ? "monthly" : in.limitPeriod());
        data.put("notes", FleetVehicleService.trim(in.notes()));
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> row = db.insert(FleetFuelCard.class, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, (String) row.get("id"), "create", null, safe(row)));
        return get((String) row.get("id"));
    }

    /** Edit a card (status, assignment, provider, limit, expiry, notes). */
    @Transactional
    public Map<String, Object> update(String id, FuelCardPatch in) {
        Map<String, Object> before = db.require(FleetFuelCard.class, id, true, "Fuel card not found");
        List<String> clear = in.clear() == null ? List.of() : in.clear();
        for (String f : clear) {
            if (!FuelCommands.CARD_CLEARABLE.contains(f)) {
                throw ApiException.badRequest("Field " + f + " cannot be cleared");
            }
        }
        assertRefs(in.providerPartnerId(), in.vehicleId(), in.employeeId());
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, "label", FleetVehicleService.trim(in.label()));
        put(data, "providerPartnerId", blank(in.providerPartnerId()));
        put(data, "vehicleId", blank(in.vehicleId()));
        put(data, "employeeId", blank(in.employeeId()));
        put(data, "status", in.status());
        put(data, "expiresOn", in.expiresOn());
        put(data, "limitAmount", in.limitAmount());
        put(data, "limitPeriod", in.limitPeriod());
        put(data, "notes", FleetVehicleService.trim(in.notes()));
        for (String f : clear) {
            data.put(f, null);
        }
        if (data.isEmpty()) {
            return get(id);
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetFuelCard.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", safe(before), safe(after)));
        return get(id);
    }

    /** Soft delete of a card never used on a fuel log (a used card is cancelled instead). */
    @Transactional
    public void delete(String id) {
        Map<String, Object> card = db.require(FleetFuelCard.class, id, true, "Fuel card not found");
        long used = db.jdbc().sql("SELECT count(*) FROM \"FleetFuelLog\" WHERE \"organizationId\" = ? AND"
                + " \"fuelCardId\" = ? AND \"deletedAt\" IS NULL").params(FleetDb.org(), id).query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("Fuel card " + card.get("maskedNumber") + " has fuel logs; set its status to"
                    + " cancelled instead");
        }
        db.update(FleetFuelCard.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "delete", safe(card), null));
    }

    /** Refuses a card that is not active or expired on {@code day} (business date of the fuelling). */
    void assertUsable(Map<String, Object> card, LocalDate day) {
        String masked = (String) card.get("maskedNumber");
        if (!"active".equals(card.get("status"))) {
            throw ApiException.badRequest("Fuel card " + masked + " is " + card.get("status"));
        }
        LocalDate expires = (LocalDate) card.get("expiresOn");
        if (expires != null && expires.isBefore(day)) {
            throw ApiException.badRequest("Fuel card " + masked + " expired on " + expires);
        }
    }

    /** First day of the card's limit period containing {@code day}. */
    static LocalDate periodStart(String period, LocalDate day) {
        return switch (period == null ? "monthly" : period) {
            case "daily" -> day;
            case "weekly" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            default -> day.withDayOfMonth(1);
        };
    }

    /** First day after the period starting {@code start}. */
    static LocalDate periodEnd(String period, LocalDate start) {
        return switch (period == null ? "monthly" : period) {
            case "daily" -> start.plusDays(1);
            case "weekly" -> start.plusWeeks(1);
            default -> start.plusMonths(1);
        };
    }

    /** Posted spend of card {@code cardId} in the business-date range [{@code from}, {@code to}). */
    BigDecimal spend(String cardId, LocalDate from, LocalDate to) {
        java.time.ZoneId zone = BusinessZone.get();
        return db.jdbc().sql("SELECT COALESCE(SUM(amount), 0) FROM \"FleetFuelLog\" WHERE \"organizationId\" = ? AND"
                + " \"fuelCardId\" = ? AND status = 'posted' AND \"deletedAt\" IS NULL AND \"fuelledAt\" >= ? AND"
                + " \"fuelledAt\" < ?").params(FleetDb.org(), cardId,
                        SqlRows.ts(from.atStartOfDay(zone).toInstant()), SqlRows.ts(to.atStartOfDay(zone).toInstant()))
                .query(BigDecimal.class).single();
    }

    private Map<String, Object> render(Map<String, Object> r) {
        Map<String, Object> out = SqlRows.as(FleetFuelCard.class, r);
        out.remove("numberHash");
        out.put("vehicle", r.get("vehicleId") == null ? null
                : Obj.of("id", r.get("vehicleId"), "code", r.get("v_code"), "registrationPlate", r.get("v_plate")));
        out.put("provider", r.get("providerPartnerId") == null ? null
                : Obj.of("id", r.get("providerPartnerId"), "code", r.get("p_code"), "name", r.get("p_name")));
        out.put("employee", r.get("employeeId") == null ? null
                : Obj.of("id", r.get("employeeId"), "employeeCode", r.get("e_code"), "name",
                        FleetFuelLogService.personName(r.get("e_first"), r.get("e_last"))));
        LocalDate today = LocalDate.now(BusinessZone.get());
        String period = (String) r.get("limitPeriod");
        LocalDate start = periodStart(period, today);
        BigDecimal spent = spend((String) r.get("id"), start, periodEnd(period, start));
        out.put("periodSpend", spent);
        out.put("periodStart", start);
        out.put("limitRemaining", r.get("limitAmount") == null ? null : SqlRows.dec(r, "limitAmount").subtract(spent));
        return out;
    }

    /** Audit image without the hash. */
    private static Map<String, Object> safe(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.remove("numberHash");
        return out;
    }

    private void assertRefs(String partnerId, String vehicleId, String employeeId) {
        if (FleetVehicleService.notBlank(partnerId) && !db.exists("Partner", partnerId, true)) {
            throw ApiException.badRequest("Card provider not found");
        }
        if (FleetVehicleService.notBlank(vehicleId) && db.byId(FleetVehicle.class, vehicleId, false) == null) {
            throw ApiException.badRequest("Vehicle not found");
        }
        if (FleetVehicleService.notBlank(employeeId) && !db.exists("HrEmployee", employeeId, true)) {
            throw ApiException.badRequest("Employee not found");
        }
    }

    private static void put(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Landed cost LC-7 (ADR-S053): the Incoterm responsibility matrix, the versioned customs tariff engine with immutable
 * calculations, and allocation templates. A calculation never posts anything: its total becomes a charge only when a
 * user adds it to a voucher, with the treatment the component and tax policy give (nothing is auto-capitalised).
 */
@Service
public class LandedCostCustomsService {

    /** Incoterms 2020: which landed-cost kinds the <b>seller</b> bears (so the buyer should not land them). */
    static final Map<String, Set<String>> SELLER_PAYS = Map.ofEntries(
            Map.entry("EXW", Set.of()),
            Map.entry("FCA", Set.of()),
            Map.entry("FAS", Set.of()),
            Map.entry("FOB", Set.of()),
            Map.entry("CFR", Set.of("freight")),
            Map.entry("CPT", Set.of("freight")),
            Map.entry("CIF", Set.of("freight", "insurance")),
            Map.entry("CIP", Set.of("freight", "insurance")),
            Map.entry("DAP", Set.of("freight", "insurance")),
            Map.entry("DPU", Set.of("freight", "insurance", "handling")),
            Map.entry("DDP", Set.of("freight", "insurance", "handling", "duty")));

    private final JdbcClient jdbc;
    private final AuditService audit;

    public LandedCostCustomsService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- Incoterm matrix ---------------------------------------------------------------------------------------------

    /**
     * Warnings for charges that conflict with the Incoterm of the purchase orders behind the receipts: a kind the
     * seller bears under that Incoterm is probably already in the goods price.
     */
    public List<String> incotermWarnings(List<String> receiptIds, List<Map<String, Object>> charges) {
        List<String> out = new ArrayList<>();
        if (receiptIds.isEmpty()) {
            return out;
        }
        // The voucher's shipment Incoterm, else the receipts' purchase orders.
        List<String> terms = jdbc.sql("SELECT DISTINCT upper(coalesce(s.incoterm, po.incoterm)) FROM \"GoodsReceiptNote\" g"
                + " LEFT JOIN \"PurchaseOrder\" po ON po.id = g.\"purchaseOrderId\" LEFT JOIN \"ShipmentReceipt\" sr ON"
                + " sr.\"goodsReceiptId\" = g.id LEFT JOIN \"Shipment\" s ON s.id = sr.\"shipmentId\" WHERE g.id ="
                + " ANY(?::text[]) AND g.\"organizationId\" = ? AND coalesce(s.incoterm, po.incoterm) IS NOT NULL")
                .params(receiptIds.toArray(String[]::new), org()).query(String.class).list();
        for (String term : terms) {
            Set<String> seller = SELLER_PAYS.get(term);
            if (seller == null) {
                continue;
            }
            for (Map<String, Object> c : charges) {
                String kind = String.valueOf(c.get("kind"));
                if (seller.contains(kind) && Money.dec(c.get("amount")).signum() > 0) {
                    out.add("Charge " + c.get("lineNumber") + " (" + kind + "): the seller bears " + kind + " under "
                            + term + "; check it is not already in the goods price");
                }
            }
        }
        return out;
    }

    // ---- tariffs -----------------------------------------------------------------------------------------------------

    /** A tariff line as entered. */
    public record TariffInput(String importCountry, String hsCode, String originCountry, String preferenceCode,
            String effectiveFrom, String effectiveTo, String valuationBasis, String dutyType, BigDecimal adValoremPct,
            BigDecimal specificAmount, String specificUom, BigDecimal additionalDutyPct, BigDecimal excisePct,
            String legalReference, String source) {}

    @Transactional(readOnly = true)
    public List<Map<String, Object>> tariffs(String hsCode, boolean history) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = history ? "" : " AND \"isActive\"";
        if (hsCode != null && !hsCode.isBlank()) {
            where += " AND \"hsCode\" LIKE ?";
            params.add(hsCode.trim() + "%");
        }
        return SqlRows.list(jdbc.sql("SELECT * FROM \"CustomsTariff\" WHERE \"organizationId\" = ?" + where
                + " ORDER BY \"importCountry\", \"hsCode\", \"effectiveFrom\" DESC, version DESC LIMIT 1000").params(params));
    }

    /** Create a tariff line (version 1). */
    @Transactional
    public Map<String, Object> createTariff(TariffInput in) {
        return insertTariff(in, 1, null);
    }

    /** Edit = a new version; the old one is kept (inactive) so past calculations stay reproducible. */
    @Transactional
    public Map<String, Object> reviseTariff(String id, TariffInput in) {
        Map<String, Object> old = tariff(id);
        if (!Boolean.TRUE.equals(old.get("isActive"))) {
            throw ApiException.badRequest("Only the current version of a tariff line can be revised");
        }
        jdbc.sql("UPDATE \"CustomsTariff\" SET \"isActive\" = FALSE WHERE id = ?").param(id).update();
        return insertTariff(in, ((Number) old.get("version")).intValue() + 1, id);
    }

    @Transactional
    public void retireTariff(String id) {
        tariff(id);
        jdbc.sql("UPDATE \"CustomsTariff\" SET \"isActive\" = FALSE WHERE id = ?").param(id).update();
        audit.recordInTx(AuditInput.of("CustomsTariff", id, "delete", null, Obj.of("retired", true)));
    }

    private Map<String, Object> tariff(String id) {
        Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT * FROM \"CustomsTariff\" WHERE id = ? AND \"organizationId\" ="
                + " ?").params(id, org()));
        if (t == null) {
            throw ApiException.notFound("Tariff line not found");
        }
        return t;
    }

    private Map<String, Object> insertTariff(TariffInput in, int version, String previous) {
        if (blank(in.importCountry()) || blank(in.hsCode())) {
            throw ApiException.badRequest("importCountry and hsCode should not be empty");
        }
        String duty = in.dutyType() == null ? "ad_valorem" : in.dutyType();
        if (!List.of("ad_valorem", "specific", "compound", "exempt").contains(duty)) {
            throw ApiException.badRequest("dutyType must be one of the following values: ad_valorem, specific, compound,"
                    + " exempt");
        }
        String basis = in.valuationBasis() == null ? "cif" : in.valuationBasis();
        if (!List.of("cif", "fob", "transaction").contains(basis)) {
            throw ApiException.badRequest("valuationBasis must be one of the following values: cif, fob, transaction");
        }
        LocalDate from = date(in.effectiveFrom(), "effectiveFrom");
        if (from == null) {
            throw ApiException.badRequest("effectiveFrom should not be empty");
        }
        LocalDate to = date(in.effectiveTo(), "effectiveTo");
        if (to != null && to.isBefore(from)) {
            throw ApiException.badRequest("effectiveTo must not be before effectiveFrom");
        }
        // One active line per country, HS code, origin and preference for any day.
        Map<String, Object> clash = SqlRows.one(jdbc.sql("SELECT id, version, \"effectiveFrom\" FROM \"CustomsTariff\""
                        + " WHERE \"organizationId\" = ? AND \"isActive\" AND \"importCountry\" = ? AND \"hsCode\" = ? AND"
                        + " \"originCountry\" IS NOT DISTINCT FROM ? AND \"preferenceCode\" IS NOT DISTINCT FROM ? AND"
                        + " \"effectiveFrom\" <= coalesce(?::date, DATE '9999-12-31') AND coalesce(\"effectiveTo\","
                        + " DATE '9999-12-31') >= ?::date LIMIT 1")
                .params(org(), in.importCountry().trim().toUpperCase(), in.hsCode().trim(), trimUpper(in.originCountry()),
                        trimUpper(in.preferenceCode()), to == null ? null : to.toString(), from.toString()));
        if (clash != null) {
            throw ApiException.conflict("An active tariff line for HS " + in.hsCode().trim() + " overlaps this period"
                    + " (version " + clash.get("version") + " from " + clash.get("effectiveFrom") + "); revise it instead");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CustomsTariff\" (id, \"organizationId\", \"importCountry\", \"hsCode\", \"originCountry\","
                        + " \"preferenceCode\", \"effectiveFrom\", \"effectiveTo\", \"valuationBasis\", \"dutyType\","
                        + " \"adValoremPct\", \"specificAmount\", \"specificUom\", \"additionalDutyPct\", \"excisePct\","
                        + " \"legalReference\", source, version, \"previousVersionId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?,"
                        + " ?::date, ?::date, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), in.importCountry().trim().toUpperCase(), in.hsCode().trim(), trimUpper(in.originCountry()),
                        trimUpper(in.preferenceCode()), from.toString(), to == null ? null : to.toString(), basis, duty,
                        nz(in.adValoremPct()), nz(in.specificAmount()), in.specificUom(), nz(in.additionalDutyPct()),
                        nz(in.excisePct()), in.legalReference(), in.source(), version, previous, user())
                .update();
        audit.recordInTx(AuditInput.of("CustomsTariff", id, "create", null, Obj.of("hsCode", in.hsCode(), "version",
                version, "previousVersionId", previous)));
        return tariff(id);
    }

    // ---- calculations ------------------------------------------------------------------------------------------------

    /** What a calculation is asked for: the declaration and the values the valuation basis needs. */
    public record CalculationInput(String importCountry, String hsCode, String originCountry, String preferenceCode,
            String declarationDate, BigDecimal transactionValue, BigDecimal fobValue, BigDecimal freight,
            BigDecimal insurance, BigDecimal quantity, BigDecimal manualAdjustment, String adjustmentReason) {}

    /**
     * Calculate the duties of one declaration and save it immutably: the tariff line valid at the declaration date
     * (origin- and preference-specific lines win over generic ones), the customs value on its valuation basis (CIF =
     * FOB + freight + insurance; FOB; transaction value), ad valorem / specific / compound duty, additional duty and
     * excise (on value + duty), and a manual adjustment that needs {@code landed_cost:customs_adjust} and a reason.
     */
    @Transactional
    public Map<String, Object> calculate(CalculationInput in) {
        if (blank(in.importCountry()) || blank(in.hsCode())) {
            throw ApiException.badRequest("importCountry and hsCode should not be empty");
        }
        LocalDate day = date(in.declarationDate(), "declarationDate");
        if (day == null) {
            throw ApiException.badRequest("declarationDate should not be empty");
        }
        Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT * FROM \"CustomsTariff\" WHERE \"organizationId\" = ? AND"
                        + " \"importCountry\" = ? AND \"hsCode\" = ? AND \"isActive\" AND \"effectiveFrom\" <= ?::date AND"
                        + " coalesce(\"effectiveTo\", DATE '9999-12-31') >= ?::date AND (\"originCountry\" IS NULL OR"
                        + " \"originCountry\" = ?) AND (\"preferenceCode\" IS NULL OR \"preferenceCode\" = ?) ORDER BY"
                        + " (\"preferenceCode\" IS NOT NULL) DESC, (\"originCountry\" IS NOT NULL) DESC, \"effectiveFrom\" DESC"
                        + " LIMIT 1")
                .params(org(), in.importCountry().trim().toUpperCase(), in.hsCode().trim(), day.toString(),
                        day.toString(), trimUpper(in.originCountry()), trimUpper(in.preferenceCode())));
        if (t == null) {
            throw ApiException.badRequest("No tariff line for HS " + in.hsCode() + " into " + in.importCountry()
                    + " on " + day);
        }
        String basis = (String) t.get("valuationBasis");
        BigDecimal value = switch (basis) {
            case "cif" -> {
                if (in.fobValue() == null) {
                    throw ApiException.badRequest("This tariff values goods CIF: give fobValue, freight and insurance");
                }
                yield in.fobValue().add(nz(in.freight())).add(nz(in.insurance()));
            }
            case "fob" -> {
                if (in.fobValue() == null) {
                    throw ApiException.badRequest("This tariff values goods FOB: give fobValue");
                }
                yield in.fobValue();
            }
            default -> {
                if (in.transactionValue() == null) {
                    throw ApiException.badRequest("This tariff values goods at the transaction value: give"
                            + " transactionValue");
                }
                yield in.transactionValue();
            }
        };
        value = value.setScale(2, RoundingMode.HALF_UP);
        BigDecimal qty = nz(in.quantity());
        String type = (String) t.get("dutyType");
        BigDecimal adValorem = value.multiply(SqlRows.dec(t, "adValoremPct")).divide(BigDecimal.valueOf(100), 2,
                RoundingMode.HALF_UP);
        BigDecimal specific = qty.multiply(SqlRows.dec(t, "specificAmount")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal duty = switch (type) {
            case "ad_valorem" -> adValorem;
            case "specific" -> specific;
            case "compound" -> adValorem.add(specific);
            default -> BigDecimal.ZERO;
        };
        BigDecimal additional = value.multiply(SqlRows.dec(t, "additionalDutyPct")).divide(BigDecimal.valueOf(100), 2,
                RoundingMode.HALF_UP);
        BigDecimal excise = value.add(duty).multiply(SqlRows.dec(t, "excisePct")).divide(BigDecimal.valueOf(100), 2,
                RoundingMode.HALF_UP);
        BigDecimal adjustment = nz(in.manualAdjustment()).setScale(2, RoundingMode.HALF_UP);
        if (adjustment.signum() != 0) {
            if (!TenantContext.permissions().contains(Permissions.LANDED_COST__CUSTOMS_ADJUST)) {
                throw ApiException.forbidden("Adjusting a customs calculation needs landed_cost:customs_adjust");
            }
            if (blank(in.adjustmentReason())) {
                throw ApiException.badRequest("A manual adjustment needs a reason");
            }
        }
        BigDecimal total = duty.add(additional).add(excise).add(adjustment);
        String id = UUID.randomUUID().toString();
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("transactionValue", in.transactionValue());
        inputs.put("fobValue", in.fobValue());
        inputs.put("freight", in.freight());
        inputs.put("insurance", in.insurance());
        inputs.put("quantity", in.quantity());
        jdbc.sql("INSERT INTO \"CustomsCalculation\" (id, \"organizationId\", \"importCountry\", \"hsCode\","
                        + " \"originCountry\", \"preferenceCode\", \"declarationDate\", \"tariffId\", \"tariffVersion\","
                        + " \"valuationBasis\", \"dutyType\", \"adValoremPct\", \"specificAmount\", \"additionalDutyPct\","
                        + " \"excisePct\", \"legalReference\", inputs, \"customsValue\", quantity, \"dutyAmount\","
                        + " \"additionalAmount\", \"exciseAmount\", \"manualAdjustment\", \"adjustmentReason\", \"totalAmount\","
                        + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?::date, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?)")
                .params(id, org(), t.get("importCountry"), t.get("hsCode"), trimUpper(in.originCountry()),
                        trimUpper(in.preferenceCode()), day.toString(), t.get("id"), t.get("version"), basis, type,
                        t.get("adValoremPct"), t.get("specificAmount"), t.get("additionalDutyPct"), t.get("excisePct"),
                        t.get("legalReference"), EntityJson.toJson(inputs), value, qty, duty, additional, excise,
                        adjustment, blank(in.adjustmentReason()) ? null : in.adjustmentReason().trim(), total, user())
                .update();
        audit.recordInTx(AuditInput.of("CustomsCalculation", id, "create", null, Obj.of("hsCode", t.get("hsCode"),
                "tariffVersion", t.get("version"), "total", JsNumbers.dec(total), "manualAdjustment",
                JsNumbers.dec(adjustment))));
        return calculation(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> calculation(String id) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"CustomsCalculation\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (c == null) {
            throw ApiException.notFound("Customs calculation not found");
        }
        return c;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> calculations() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"CustomsCalculation\" WHERE \"organizationId\" = ? ORDER BY"
                + " \"createdAt\" DESC LIMIT 500").param(org()));
    }

    /** Record the charge a calculation was used for (the only change a calculation accepts). */
    @Transactional
    public Map<String, Object> link(String id, String chargeId) {
        calculation(id);
        if (jdbc.sql("SELECT count(*) FROM \"LandedCostCharge\" WHERE id = ? AND \"organizationId\" = ?")
                .params(chargeId, org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Landed cost charge not found");
        }
        jdbc.sql("UPDATE \"CustomsCalculation\" SET \"landedCostChargeId\" = ? WHERE id = ?").params(chargeId, id)
                .update();
        return calculation(id);
    }

    // ---- allocation templates ----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> templates() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"LandedCostTemplate\" WHERE \"organizationId\" = ? AND \"isActive\""
                + " ORDER BY name").param(org()));
    }

    /**
     * Save a template: named charge presets (kind or cost component, treatment, allocation method, bill mode, supplier,
     * and an optional {@code ratePct} of the goods value used to suggest the amount).
     */
    @Transactional
    public Map<String, Object> saveTemplate(String name, String description, List<Map<String, Object>> charges) {
        if (blank(name)) {
            throw ApiException.badRequest("name should not be empty");
        }
        if (charges == null || charges.isEmpty()) {
            throw ApiException.badRequest("charges must contain at least 1 elements");
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.sql("INSERT INTO \"LandedCostTemplate\" (id, \"organizationId\", name, description, charges, \"createdBy\")"
                    + " VALUES (?, ?, ?, ?, ?::jsonb, ?)").params(id, org(), name.trim(), description,
                    EntityJson.toJson(charges), user()).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("A landed cost template named " + name.trim() + " already exists");
        }
        return SqlRows.one(jdbc.sql("SELECT * FROM \"LandedCostTemplate\" WHERE id = ?").param(id));
    }

    @Transactional
    public void deleteTemplate(String id) {
        if (jdbc.sql("UPDATE \"LandedCostTemplate\" SET \"isActive\" = FALSE WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"isActive\"").params(id, org()).update() == 0) {
            throw ApiException.notFound("Landed cost template not found");
        }
    }

    /** The template's charges as voucher drafts for these receipts (amounts suggested from {@code ratePct}). */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> applyTemplate(String id, List<String> receiptIds) {
        Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT charges::text AS charges FROM \"LandedCostTemplate\" WHERE"
                + " id = ? AND \"organizationId\" = ? AND \"isActive\"").params(id, org()));
        if (t == null) {
            throw ApiException.notFound("Landed cost template not found");
        }
        BigDecimal goods = receiptIds == null || receiptIds.isEmpty() ? BigDecimal.ZERO : jdbc.sql("SELECT"
                + " coalesce(sum(\"totalValue\"), 0) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"referenceType\" = 'goods_receipt' AND \"quantityChange\" > 0 AND \"referenceId\" = ANY(?::text[])")
                .params(org(), receiptIds.toArray(String[]::new)).query(BigDecimal.class).single();
        List<Map<String, Object>> out = new ArrayList<>();
        for (tools.jackson.databind.JsonNode n : EntityJson.tree((String) t.get("charges"))) {
            Map<String, Object> c = new LinkedHashMap<>();
            n.properties().forEach(e -> c.put(e.getKey(), e.getValue().isNumber() ? e.getValue().decimalValue()
                    : e.getValue().isNull() ? null : e.getValue().asString()));
            Object pct = c.remove("ratePct");
            if (pct != null && goods.signum() > 0) {
                c.put("amount", JsNumbers.dec(goods.multiply(Money.dec(pct)).divide(BigDecimal.valueOf(100), 2,
                        RoundingMode.HALF_UP)));
            }
            out.add(c);
        }
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static String trimUpper(String v) {
        return blank(v) ? null : v.trim().toUpperCase();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static LocalDate date(String v, String field) {
        if (blank(v)) {
            return null;
        }
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be a valid ISO 8601 date string");
        }
    }
}

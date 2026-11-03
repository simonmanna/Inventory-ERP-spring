package com.inventoryerp.core.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fiscal positions that act (ACC-7): a position maps source taxes to target taxes (a {@code null} target = exempt), and
 * a partner may carry a default position. Documents resolve the position (stated, else the partner's) and apply the
 * mapping to every line's tax before the tax engine runs.
 */
@Service
public class FiscalPositionTaxService {

    /** Marker for "mapped to no tax" in {@link #taxMap}. */
    public static final String EXEMPT = "";

    private final JdbcClient jdbc;

    public FiscalPositionTaxService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** The position a document uses: the one it states, else its partner's default, else none. */
    @Transactional(readOnly = true)
    public String resolve(String statedPositionId, String partnerId) {
        if (statedPositionId != null && !statedPositionId.isEmpty()) {
            return statedPositionId;
        }
        if (partnerId == null) {
            return null;
        }
        return jdbc.sql("SELECT \"fiscalPositionId\" FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(partnerId, org()).query(String.class).optional().orElse(null);
    }

    /** sourceTaxId → targetTaxId ({@link #EXEMPT} for no tax) of an active position; empty without one. */
    @Transactional(readOnly = true)
    public Map<String, String> taxMap(String fiscalPositionId) {
        Map<String, String> out = new LinkedHashMap<>();
        if (fiscalPositionId == null) {
            return out;
        }
        jdbc.sql("SELECT m.\"sourceTaxId\", m.\"targetTaxId\" FROM \"FiscalPositionTax\" m JOIN \"FiscalPosition\" p ON"
                        + " p.id = m.\"fiscalPositionId\" WHERE m.\"organizationId\" = ? AND m.\"fiscalPositionId\" = ?"
                        + " AND p.\"isActive\" = true AND p.\"deletedAt\" IS NULL")
                .params(org(), fiscalPositionId)
                .query((rs, n) -> out.put(rs.getString(1), rs.getString(2) == null ? EXEMPT : rs.getString(2))).list();
        return out;
    }

    /** One mapping row of the API. */
    public record Mapping(String sourceTaxId, String targetTaxId) {}

    @Transactional(readOnly = true)
    public List<Map<String, Object>> mappings(String fiscalPositionId) {
        requirePosition(fiscalPositionId);
        return SqlRows.list(jdbc.sql("SELECT m.id, m.\"sourceTaxId\", s.name AS \"sourceTaxName\", m.\"targetTaxId\","
                + " t.name AS \"targetTaxName\" FROM \"FiscalPositionTax\" m JOIN \"Tax\" s ON s.id = m.\"sourceTaxId\""
                + " LEFT JOIN \"Tax\" t ON t.id = m.\"targetTaxId\" WHERE m.\"organizationId\" = ? AND"
                + " m.\"fiscalPositionId\" = ? ORDER BY s.name").params(org(), fiscalPositionId));
    }

    /** Replace the position's mappings. Every tax must belong to the organization; a source appears once. */
    @Transactional
    public List<Map<String, Object>> replace(String fiscalPositionId, List<Mapping> rows) {
        requirePosition(fiscalPositionId);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Mapping m : rows) {
            if (m.sourceTaxId() == null || !seen.add(m.sourceTaxId())) {
                throw ApiException.badRequest("Each source tax may be mapped once");
            }
            requireTax(m.sourceTaxId());
            if (m.targetTaxId() != null) {
                requireTax(m.targetTaxId());
            }
        }
        jdbc.sql("DELETE FROM \"FiscalPositionTax\" WHERE \"organizationId\" = ? AND \"fiscalPositionId\" = ?")
                .params(org(), fiscalPositionId).update();
        for (Mapping m : rows) {
            jdbc.sql("INSERT INTO \"FiscalPositionTax\" (id, \"organizationId\", \"fiscalPositionId\", \"sourceTaxId\","
                            + " \"targetTaxId\") VALUES (?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), fiscalPositionId, m.sourceTaxId(), m.targetTaxId())
                    .update();
        }
        return mappings(fiscalPositionId);
    }

    /** Set (or with {@code null} position clear) a partner's default fiscal position. */
    @Transactional
    public Map<String, Object> assignPartner(String fiscalPositionId, String partnerId) {
        if (fiscalPositionId != null) {
            requirePosition(fiscalPositionId);
        }
        int n = jdbc.sql("UPDATE \"Partner\" SET \"fiscalPositionId\" = ?, \"updatedAt\" = now() WHERE id = ? AND"
                + " \"organizationId\" = ?").params(fiscalPositionId, partnerId, org()).update();
        if (n == 0) {
            throw ApiException.notFound("Partner not found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("partnerId", partnerId);
        out.put("fiscalPositionId", fiscalPositionId);
        return out;
    }

    private void requirePosition(String id) {
        if (jdbc.sql("SELECT count(*) FROM \"FiscalPosition\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL").params(id, org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Fiscal position not found");
        }
    }

    private void requireTax(String id) {
        if (jdbc.sql("SELECT count(*) FROM \"Tax\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(id, org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Tax " + id + " not found");
        }
    }
}

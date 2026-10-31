package com.inventoryerp.documents.application;

import com.inventoryerp.documents.application.LabelRenderer.Label;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Label printing (INT-3): products, quarry stockpiles and weighbridge tickets, read org-scoped from their tables and
 * rendered by {@link LabelRenderer}. Precast pallets keep their own QR labels (ADR-S014).
 */
@Service
public class LabelService {

    /** Print {@code ids} of {@code type}. */
    public record LabelRequest(
            @NotBlank @OneOf({"product", "stockpile", "ticket"}) String type,
            @NotEmpty @Size(max = 200) List<@NotBlank String> ids,
            @Min(1) @Max(50) Integer copies,
            @OneOf({"pdf", "zpl"}) String format,
            @OneOf({"qr", "code128"}) String symbology) {}

    /** The rendered file. */
    public record RenderedLabels(String filename, String contentType, byte[] content) {}

    private static final String PRODUCT = """
            SELECT id, name AS title, code AS subtitle, COALESCE(barcode, code) AS code
            FROM "Product" p WHERE p."organizationId" = ? AND p."deletedAt" IS NULL""";
    private static final String STOCKPILE = """
            SELECT p.id, p.name AS title, s.name AS subtitle, p.code AS code
            FROM "QuarryStockpile" p JOIN "QuarrySite" s ON s.id = p."siteId" WHERE p."organizationId" = ?""";
    private static final String TICKET = """
            SELECT p.id, p."ticketNumber" AS title,
                   concat_ws(' · ', p."registrationPlate", pr.name,
                       CASE WHEN p."netKg" IS NULL THEN NULL ELSE trim_scale(p."netKg")::text || ' kg' END) AS subtitle,
                   p."ticketNumber" AS code
            FROM "WeighbridgeTicket" p LEFT JOIN "Product" pr ON pr.id = p."productId"
            WHERE p."organizationId" = ?""";

    private final JdbcClient jdbc;

    public LabelService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String select(String type) {
        return switch (type) {
            case "product" -> PRODUCT;
            case "stockpile" -> STOCKPILE;
            case "ticket" -> TICKET;
            default -> throw ApiException.badRequest("Unknown label type " + type);
        };
    }

    private static String searchColumns(String type) {
        return switch (type) {
            case "product" -> "(p.name ILIKE ? OR p.code ILIKE ? OR COALESCE(p.barcode, '') ILIKE ?)";
            case "stockpile" -> "(p.name ILIKE ? OR p.code ILIKE ? OR s.name ILIKE ?)";
            default -> "(p.\"ticketNumber\" ILIKE ? OR COALESCE(p.\"registrationPlate\", '') ILIKE ?"
                    + " OR COALESCE(pr.name, '') ILIKE ?)";
        };
    }

    /** What can be printed, at most 50. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sources(String type, String search) {
        String like = "%" + (search == null ? "" : search.trim().replace("%", "\\%").replace("_", "\\_")) + "%";
        return SqlRows.list(jdbc.sql(select(type) + " AND " + searchColumns(type) + " ORDER BY 2 LIMIT 50")
                .params(TenantContext.organizationId(), like, like, like));
    }

    /** Renders in the order requested; 404 when an id is unknown here. */
    @Transactional(readOnly = true)
    public RenderedLabels render(LabelRequest in) {
        Map<String, Label> found = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(select(in.type()) + " AND p.id = ANY(?::text[])")
                .params(TenantContext.organizationId(), in.ids().toArray(String[]::new)))) {
            found.put((String) r.get("id"), new Label((String) r.get("title"), (String) r.get("subtitle"),
                    (String) r.get("code")));
        }
        List<Label> labels = new ArrayList<>();
        for (String id : in.ids()) {
            Label label = found.get(id);
            if (label == null) {
                throw ApiException.notFound("Label source not found");
            }
            labels.add(label);
        }
        String symbology = in.symbology() == null ? LabelRenderer.QR : in.symbology();
        int copies = in.copies() == null ? 1 : in.copies();
        if ("zpl".equals(in.format())) {
            return new RenderedLabels("labels.zpl", "text/plain",
                    LabelRenderer.zpl(labels, symbology, copies).getBytes(StandardCharsets.UTF_8));
        }
        return new RenderedLabels("labels.pdf", "application/pdf", LabelRenderer.pdf(labels, symbology, copies));
    }
}

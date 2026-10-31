package com.inventoryerp.biexport.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Csv;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSV over the read-only {@code reporting} views. A dataset is a whitelisted view: the key never reaches the SQL. The
 * date range defaults to the month so far and is capped, so one click cannot pull a decade of journal lines.
 */
@Service
public class BiExportService {

    private static final int MAX_ROWS = 200_000;
    private static final String ORG_COLUMN = "organizationId";

    /** {@code dateColumn} is null for a snapshot, which takes no date range. */
    private record Dataset(String key, String label, String view, String dateColumn) {}

    private static final List<Dataset> DATASETS = List.of(
            new Dataset("sales-lines", "Sales lines", "sales_lines", "issueDate"),
            new Dataset("purchase-lines", "Purchase lines", "purchase_lines", "orderDate"),
            new Dataset("stock-on-hand", "Stock on hand", "stock_on_hand", null),
            new Dataset("crushing-output", "Crushing output", "crushing_output", "runDate"),
            new Dataset("block-production", "Block production", "block_production", "startedAt"),
            new Dataset("weighbridge-tickets", "Weighbridge tickets", "weighbridge_tickets", "createdAt"),
            new Dataset("delivery-lines", "Delivery lines", "delivery_lines", "requestedDate"),
            new Dataset("journal-lines", "Journal lines", "journal_lines", "postingDate"));

    /** A rendered file. */
    public record Export(String filename, byte[] content) {}

    private final JdbcClient jdbc;

    public BiExportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private List<String> columns(Dataset d) {
        return jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'reporting' AND"
                + " table_name = ? AND column_name <> ? ORDER BY ordinal_position").params(d.view(), ORG_COLUMN)
                .query(String.class).list();
    }

    /** Every dataset with its columns, for the export screen and for whoever maps a BI tool onto the views. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> datasets() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Dataset d : DATASETS) {
            out.add(Obj.of("key", d.key(), "label", d.label(), "view", "reporting." + d.view(), "dateColumn",
                    d.dateColumn(), "columns", columns(d)));
        }
        return out;
    }

    private static LocalDate day(String v, String field) {
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be an ISO date (YYYY-MM-DD)");
        }
    }

    /** The dataset as CSV. {@code from} and {@code to} (ISO dates, inclusive) apply to datasets with a date column. */
    @Transactional(readOnly = true)
    public Export export(String key, String from, String to) {
        Dataset d = DATASETS.stream().filter(x -> x.key().equals(key)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Dataset not found"));
        StringBuilder sql = new StringBuilder("SELECT * FROM reporting.\"" + d.view() + "\" WHERE \"" + ORG_COLUMN
                + "\" = ?");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId()));
        String suffix = "";
        if (d.dateColumn() != null) {
            LocalDate last = to == null || to.isBlank() ? LocalDate.now(BusinessZone.get()) : day(to, "to");
            LocalDate first = from == null || from.isBlank() ? last.withDayOfMonth(1) : day(from, "from");
            if (last.isBefore(first)) {
                throw ApiException.badRequest("to must not be before from");
            }
            sql.append(" AND \"").append(d.dateColumn()).append("\" >= ? AND \"").append(d.dateColumn())
                    .append("\" < ?");
            params.add(SqlRows.ts(first.atStartOfDay(BusinessZone.get()).toInstant()));
            params.add(SqlRows.ts(last.plusDays(1).atStartOfDay(BusinessZone.get()).toInstant()));
            sql.append(" ORDER BY \"").append(d.dateColumn()).append("\" DESC");
            suffix = "-" + first + "-" + last;
        }
        sql.append(" LIMIT ").append(MAX_ROWS);
        return new Export(d.key() + suffix + ".csv", Csv.of(columns(d), SqlRows.list(jdbc.sql(sql.toString())
                .params(params))));
    }
}

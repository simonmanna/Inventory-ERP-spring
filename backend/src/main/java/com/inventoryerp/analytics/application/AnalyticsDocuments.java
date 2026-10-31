package com.inventoryerp.analytics.application;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The drill-down lists: one page of documents behind a KPI. Every list selects the same columns ({@code type, id,
 * number, date, party, status, quantity, amount}); {@code type} tells the screen which document page to open.
 */
@Component
public class AnalyticsDocuments {

    /** Page and size of a drill-down request. */
    public record Paging(int page, int pageSize) {

        private static final int MAX_SIZE = 200;

        public static Paging of(Map<String, String> q) {
            int page = Math.max(1, JsNumbers.orDefault(q.get("page"), 1));
            int size = Math.min(MAX_SIZE, Math.max(1, JsNumbers.orDefault(q.get("pageSize"), 25)));
            return new Paging(page, size);
        }

        int offset() {
            return (page - 1) * pageSize;
        }
    }

    private final JdbcClient jdbc;

    public AnalyticsDocuments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code select} and {@code from} are fixed strings of the caller; {@code params} bind the {@code from} part. */
    public PageResult<Map<String, Object>> page(String select, CharSequence from, String order, List<Object> params,
            Paging paging) {
        long total = jdbc.sql("SELECT count(*) " + from).params(params).query(Long.class).single();
        List<Object> all = new ArrayList<>(params);
        all.add(paging.pageSize());
        all.add(paging.offset());
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT " + select + " " + from + " ORDER BY " + order
                + " LIMIT ? OFFSET ?").params(all));
        return PageResult.of(rows, paging.page(), paging.pageSize(), total);
    }

    /** 400 for a drill-down key the dashboard does not have. */
    public static ApiException unknown(String kpi) {
        return ApiException.badRequest("Unknown drill-down: " + kpi);
    }
}

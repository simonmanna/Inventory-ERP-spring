package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import jakarta.servlet.Filter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * POS checkout latency and SQL statement count (PR-1 part 2). Run with {@code ./gradlew perfTest}; the p95 budget
 * ({@code -Dpos.checkout.p95.ms}, default 150) is only asserted when {@code -Dpos.checkout.assert=true}, because a
 * Testcontainers database behind Docker Desktop is not a production-like box.
 */
@Tag("perf")
class PosCheckoutPerfIT extends ApiTestSupport {

    static final ThreadLocal<long[]> REQUEST_SQL = new ThreadLocal<>();
    static final Map<String, Integer> SQL_TEXT = new java.util.concurrent.ConcurrentHashMap<>();
    static volatile boolean capture;

    @TestConfiguration
    static class SqlCounting {

        @Bean
        static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource ds ? proxy(DataSource.class, ds, null) : bean;
                }
            };
        }

        @Bean
        FilterRegistrationBean<Filter> sqlCountHeader() {
            FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>((req, res, chain) -> {
                long[] count = new long[4];
                REQUEST_SQL.set(count);
                var http = (jakarta.servlet.http.HttpServletResponse) res;
                var wrapped = new jakarta.servlet.http.HttpServletResponseWrapper(http) {
                    @Override
                    public jakarta.servlet.ServletOutputStream getOutputStream() throws java.io.IOException {
                        setHeader("X-Sql-Count", count[0] + "," + count[1] + "," + count[2] + "," + count[3]);
                        return super.getOutputStream();
                    }

                    @Override
                    public java.io.PrintWriter getWriter() throws java.io.IOException {
                        setHeader("X-Sql-Count", count[0] + "," + count[1] + "," + count[2] + "," + count[3]);
                        return super.getWriter();
                    }
                };
                try {
                    chain.doFilter(req, wrapped);
                } finally {
                    REQUEST_SQL.remove();
                }
            });
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return reg;
        }

        @SuppressWarnings("unchecked")
        static <T> T proxy(Class<T> type, T target, String sql) {
            InvocationHandler h = (p, m, args) -> {
                if (target instanceof Statement && m.getName().startsWith("execute")) {
                    long[] c = REQUEST_SQL.get();
                    if (c != null) {
                        c[0]++;
                        if (capture) {
                            String text = sql != null ? sql
                                    : args != null && args.length > 0 ? String.valueOf(args[0]) : "?";
                            SQL_TEXT.merge(text.replaceAll("\\s+", " ").trim(), 1, Integer::sum);
                            String trace = System.getProperty("pos.checkout.traceSql");
                            if (trace != null && text.matches("(?s).*" + trace + ".*")) {
                                String where = java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                                        .map(StackTraceElement::toString)
                                        .filter(f -> f.contains("com.inventoryerp.") && !f.contains("PerfIT")
                                                && !f.contains("kernel.crud.SqlRows")
                                                && !f.contains("$$"))
                                        .limit(4).collect(java.util.stream.Collectors.joining(" <- "));
                                SQL_TEXT.merge("TRACE " + where, 1, Integer::sum);
                            }
                        }
                    }
                }
                Object out;
                boolean exec = target instanceof Statement && m.getName().startsWith("execute");
                boolean commit = target instanceof Connection && m.getName().equals("commit");
                long t0 = System.nanoTime();
                try {
                    out = m.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                } finally {
                    long[] c = REQUEST_SQL.get();
                    if (c != null && (exec || commit)) {
                        c[exec ? 1 : 2] += System.nanoTime() - t0;
                        if (commit) {
                            c[3]++;
                        }
                    }
                }
                if (out instanceof Connection c && m.getReturnType() == Connection.class) {
                    return proxy(Connection.class, c, null);
                }
                if (out instanceof Statement s && Statement.class.isAssignableFrom(m.getReturnType())) {
                    String text = args != null && args.length > 0 && args[0] instanceof String q ? q : null;
                    return proxy((Class<Statement>) m.getReturnType(), s, text);
                }
                return out;
            };
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, h);
        }
    }

    static String tail(String s, int n) {
        return s.substring(Math.max(0, s.length() - n));
    }

    String sessionId;
    final List<String> productIds = new ArrayList<>();

    @BeforeAll
    void fixtures() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        for (int i = 0; i < 3; i++) {
            productIds.add(ok(201, "POST", "/products", Map.of("code", "PERF-" + i + "-" + suffix, "name",
                    "Perf item " + i + " " + suffix, "productType", "service", "salesPrice", 100, "costPrice", 0))
                    .get("id").asString());
        }
        String registerId = ok(201, "POST", "/cash-registers", Map.of("code", "PERF-" + suffix, "name",
                "Perf till " + suffix)).get("id").asString();
        sessionId = checkout("/cash-sessions/open", Map.of("cashRegisterId", registerId, "openingFloat", 0))
                .body().get("id").asString();
    }

    Res checkout(String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call("POST", path, body, h);
    }

    Map<String, Object> sale() {
        List<Map<String, Object>> lines = productIds.stream().map(p -> Map.<String, Object>of("productId", p,
                "description", "Perf", "quantity", 1, "unitPrice", 100)).toList();
        return Map.of("cashSessionId", sessionId, "lines", lines, "tenders",
                List.of(Map.of("method", "cash", "amount", 100 * productIds.size())));
    }

    @Test
    void checkoutLatencyAndStatementCount() {
        int warmup = Integer.getInteger("pos.checkout.warmup", 30);
        int runs = Integer.getInteger("pos.checkout.runs", 200);
        for (int i = 0; i < warmup; i++) {
            assertThat(checkout("/pos/checkout", sale()).status()).isEqualTo(200);
        }
        long[] millis = new long[runs];
        int[] sql = new int[runs];
        double execMs = 0;
        double commitMs = 0;
        double commits = 0;
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            Res r = checkout("/pos/checkout", sale());
            millis[i] = (System.nanoTime() - t0) / 1_000_000;
            assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
            String[] h = r.raw().headers().firstValue("X-Sql-Count").orElse("-1,0,0,0").split(",");
            sql[i] = Integer.parseInt(h[0]);
            execMs += Long.parseLong(h[1]) / 1e6 / runs;
            commitMs += Long.parseLong(h[2]) / 1e6 / runs;
            commits += Double.parseDouble(h[3]) / runs;
        }
        if (Boolean.getBoolean("pos.checkout.dumpSql")) {
            capture = true;
            checkout("/pos/checkout", sale());
            capture = false;
            SQL_TEXT.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> System.out.printf("SQL %4d  %s%n", e.getValue(),
                            tail(e.getKey(), Integer.getInteger("pos.checkout.dumpTail", 220))));
        }
        long[] sorted = millis.clone();
        Arrays.sort(sorted);
        long p50 = sorted[runs / 2];
        long p95 = sorted[(int) Math.ceil(runs * 0.95) - 1];
        long p99 = sorted[(int) Math.ceil(runs * 0.99) - 1];
        double sqlAvg = Arrays.stream(sql).average().orElse(0);
        // Round trip of one trivial statement: on Docker Desktop most of the checkout time is network wait.
        for (int i = 0; i < 200; i++) {
            jdbc.sql("SELECT 1").query(Integer.class).single();
        }
        long r0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            jdbc.sql("SELECT 1").query(Integer.class).single();
        }
        double rttMs = (System.nanoTime() - r0) / 1_000_000.0 / 1000;
        double prodRttMs = Double.parseDouble(System.getProperty("pos.checkout.prodRttMs", "0.15"));
        double projectedP95 = p95 - sqlAvg * (rttMs - prodRttMs);
        System.out.printf("POS_CHECKOUT_DB avgExecMs=%.1f avgCommitMs=%.1f commitsPerCheckout=%.1f avgAppMs=%.1f%n",
                execMs, commitMs, commits, Arrays.stream(millis).average().orElse(0) - execMs - commitMs);
        System.out.printf("POS_CHECKOUT_PERF runs=%d lines=%d p50=%dms p95=%dms p99=%dms max=%dms sqlPerCheckout=%.1f"
                + " rtt=%.3fms sqlWait=%.0fms projectedP95@%.2fmsRtt=%.0fms%n", runs, productIds.size(), p50, p95, p99,
                sorted[runs - 1], sqlAvg, rttMs, sqlAvg * rttMs, prodRttMs, projectedP95);
        if (Boolean.getBoolean("pos.checkout.assert")) {
            assertThat(p95).isLessThanOrEqualTo(Long.getLong("pos.checkout.p95.ms", 150L));
        }
    }
}

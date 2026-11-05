package com.inventoryerp.sales.application;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.sales.application.SalesReportKit.Def;
import com.inventoryerp.sales.application.SalesReportKit.Filter;
import com.inventoryerp.sales.application.SalesReportKit.Report;
import java.util.List;
import java.util.function.Function;

/** A group of report-center reports (ADR-S057); every bean is picked up by {@link SalesReportingService}. */
interface SalesReportSet {

    List<String> STD = List.of("period", "branch", "salesperson", "customer", "product", "category",
            "customerCategory", "channel");
    List<String> DOC = List.of("period", "branch", "salesperson", "customer", "product", "category",
            "customerCategory");
    List<String> SNAPSHOT = List.of("branch", "salesperson", "customer", "product", "category", "customerCategory");

    List<Def> definitions();

    static Def def(String key, String category, String title, String description, List<String> filters,
            Function<Filter, Report> run) {
        return new Def(key, category, title, description, Permissions.SALES__REPORT_READ, filters, run);
    }

    static Def def(String key, String category, String title, String description, String permission,
            List<String> filters, Function<Filter, Report> run) {
        return new Def(key, category, title, description, permission, filters, run);
    }

    /** {@code base} plus {@code extra} filter keys. */
    static List<String> with(List<String> base, String... extra) {
        java.util.List<String> out = new java.util.ArrayList<>(base);
        out.addAll(List.of(extra));
        return List.copyOf(out);
    }
}

package com.inventoryerp.biexport;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.biexport.web.BiDashboardController;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The {@code bi} schema Superset reads (V570) and the embed routes, with Superset not configured. */
class BiDashboardIT extends ApiTestSupport {

    /**
     * Relations are looked up by oid through pg_catalog: a name lookup (information_schema, ::regclass) needs USAGE on
     * the schema, which the API role deliberately lacks for `bi`.
     */
    static final String OID = "(SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
            + " WHERE n.nspname = ?::text AND c.relname = ?::text)";

    List<String> biViews() {
        return jdbc.sql("SELECT viewname FROM pg_views WHERE schemaname = 'bi' ORDER BY 1").query(String.class).list();
    }

    @Test
    @DisplayName("no bi view exposes personal or secret columns (salary per employee, bank, tax, contact, password)")
    void noPersonalColumns() {
        List<String> leaked = jdbc.sql("SELECT c.relname || '.' || a.attname FROM pg_attribute a"
                + " JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = 'bi' AND a.attnum > 0 AND NOT a.attisdropped AND a.attname = ANY (?::text[])")
                .param(new String[] {"baseSalary", "hourlyRate", "bankAccountNumber", "bankName", "taxNumber",
                    "pensionNumber", "socialSecurityNumber", "email", "phone", "dateOfBirth", "passwordHash",
                    "pinHash", "mobileMoneyNumber", "address"})
                .query(String.class).list();
        assertThat(leaked).isEmpty();
    }

    @Test
    @DisplayName("every bi view exposes organizationId, the column the guest-token clause filters on")
    void everyViewCarriesOrganization() {
        List<String> views = biViews();
        assertThat(views).contains("sales_lines", "sales_invoices", "purchase_lines", "vendor_bills", "stock_on_hand",
                "stock_movements", "journal_lines", "payments", "pos_sales", "pos_sale_lines", "pos_cash_sessions",
                "pos_voided_items", "hr_employees", "hr_attendance", "hr_leave", "hr_payroll_costs", "production_orders",
                "production_materials", "production_variances");
        for (String v : views) {
            Integer has = jdbc.sql("SELECT count(*) FROM pg_attribute WHERE attrelid = " + OID
                    + " AND attname = 'organizationId' AND NOT attisdropped").params("bi", v).query(Integer.class)
                    .single();
            assertThat(has).as(v).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the API role cannot read the bi views, which row-level security does not filter")
    void apiRoleKeptOut() {
        boolean owner = jdbc.sql("SELECT pg_has_role(current_user, (SELECT nspowner FROM pg_namespace"
                + " WHERE nspname = 'bi'), 'MEMBER')").query(Boolean.class).single();
        if (!owner) {
            for (String v : biViews()) {
                assertThat(privilege("current_user", "bi", v, "SELECT")).as(v).isFalse();
            }
        }
    }

    @Test
    @DisplayName("superset_reader reads the bi views and nothing else")
    void readerIsConfined() {
        boolean role = jdbc.sql("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'superset_reader')")
                .query(Boolean.class).single();
        assertThat(role).isTrue();
        for (String v : biViews()) {
            assertThat(privilege("'superset_reader'", "bi", v, "SELECT")).as(v).isTrue();
            assertThat(privilege("'superset_reader'", "bi", v, "INSERT")).as(v).isFalse();
        }
        for (String[] t : List.of(new String[] {"public", "Document"}, new String[] {"public", "JournalLine"},
                new String[] {"public", "User"}, new String[] {"reporting", "sales_lines"})) {
            assertThat(privilege("'superset_reader'", t[0], t[1], "SELECT")).as(t[1]).isFalse();
        }
    }

    /** {@code role} is SQL: a quoted role name or current_user. */
    boolean privilege(String role, String schema, String relation, String privilege) {
        return jdbc.sql("SELECT has_table_privilege(" + role + ", " + OID + ", ?::text)")
                .params(schema, relation, privilege).query(Boolean.class).single();
    }

    @Test
    @DisplayName("without Superset configured the list is empty and no dashboard is found")
    void disabled() {
        assertThat(ok(200, "GET", "/bi/dashboards", null).size()).isZero();
        assertThat(call("GET", "/bi/dashboards/sales/guest-token", null, adminToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("every route needs its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(BiDashboardController.class)).isEqualTo(2);
    }
}

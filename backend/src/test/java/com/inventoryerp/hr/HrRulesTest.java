package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.hr.application.HrDb;
import com.inventoryerp.hr.application.HrOrgService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Ports of {@code hr-dates.spec.ts} and {@code hr-employee-projection.spec.ts}, plus JS coercion helpers. */
class HrRulesTest {

    @Nested
    @DisplayName("hr-dates")
    class Dates {

        @Test
        @DisplayName("buckets an early-morning Kampala clock-in on the local day, not the UTC one")
        void kampala() {
            Instant at = Instant.parse("2026-09-20T22:30:00.000Z");
            assertThat(HrDb.attendanceDay(at, ZoneId.of("Africa/Kampala"))).isEqualTo("2026-09-21T00:00:00Z");
            assertThat(HrDb.attendanceDay(at, ZoneOffset.UTC)).isEqualTo("2026-09-20T00:00:00Z");
        }

        @Test
        @DisplayName("takes a bare YYYY-MM-DD as the calendar day it names, in any zone")
        void bareDate() {
            assertThat(HrDb.attendanceDay("2026-09-21", ZoneId.of("America/New_York"))).isEqualTo("2026-09-21T00:00:00Z");
            assertThat(HrDb.attendanceDay("2026-09-21", ZoneId.of("Pacific/Kiritimati")))
                    .isEqualTo("2026-09-21T00:00:00Z");
        }

        @Test
        @DisplayName("reads shift minutes in the org zone")
        void minutes() {
            Instant at = Instant.parse("2026-09-21T05:15:00.000Z");
            assertThat(HrDb.minutesOfDay(at, ZoneId.of("Africa/Kampala"))).isEqualTo(8 * 60 + 15);
            assertThat(HrDb.minutesOfDay(at, ZoneOffset.UTC)).isEqualTo(5 * 60 + 15);
        }

        @Test
        @DisplayName("adds whole days without drifting off UTC midnight")
        void addDays() {
            Instant d = Instant.parse("2026-12-31T00:00:00.000Z");
            assertThat(HrDb.addDays(d, 1)).isEqualTo("2027-01-01T00:00:00Z");
            assertThat(HrDb.addDays(d, -30)).isEqualTo("2026-12-01T00:00:00Z");
        }
    }

    @Nested
    @DisplayName("HR employee compensation projection")
    class Projection {

        Map<String, Object> employee() {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", "emp-1");
            e.put("employeeCode", "EMP-00001");
            e.put("firstName", "Ada");
            e.put("lastName", "Byron");
            e.put("phone", "+256700000000");
            e.put("departmentId", "dept-1");
            e.put("baseSalary", "1500000");
            e.put("hourlyRate", "9000");
            e.put("bankName", "Stanbic");
            e.put("bankAccountName", "Ada Byron");
            e.put("bankAccountNumber", "9030001234567");
            e.put("mobileMoneyProvider", "MTN");
            e.put("mobileMoneyNumber", "+256770000000");
            e.put("taxNumber", "TIN-123");
            e.put("pensionNumber", "NSSF-9");
            e.put("socialSecurityNumber", "SSN-4");
            e.put("dateOfBirth", Instant.parse("1990-01-01T00:00:00Z"));
            return e;
        }

        @Test
        @DisplayName("strips every compensation field (deleting the key) without hr:compensation")
        void strips() {
            Map<String, Object> row = HrOrgService.redactEmployee(employee(), false);
            for (String f : HrOrgService.HR_COMPENSATION_FIELDS) {
                assertThat(row).doesNotContainKey(f);
            }
            assertThat(row.get("firstName")).isEqualTo("Ada");
            assertThat(row.get("phone")).isEqualTo("+256700000000");
            assertThat(row.get("departmentId")).isEqualTo("dept-1");
            assertThat(row.get("employeeCode")).isEqualTo("EMP-00001");
        }

        @Test
        @DisplayName("returns everything with hr:compensation; lists redact every row; null tolerated")
        void keepsAndLists() {
            Map<String, Object> row = HrOrgService.redactEmployee(employee(), true);
            assertThat(row.get("baseSalary")).isEqualTo("1500000");
            assertThat(row.get("socialSecurityNumber")).isEqualTo("SSN-4");
            List<Map<String, Object>> rows = new ArrayList<>(List.of(employee(), employee()));
            for (Map<String, Object> r : HrOrgService.redactEmployees(rows, false)) {
                assertThat(r).doesNotContainKeys("baseSalary", "taxNumber");
            }
            assertThat(HrOrgService.redactEmployee(null, false)).isNull();
        }
    }

    @Test
    @DisplayName("JS Number() and Prisma take/skip coercions")
    void coercions() {
        assertThat(HrDb.jsNumber("12")).isEqualTo(12.0);
        assertThat(HrDb.jsNumber("")).isZero();
        assertThat(HrDb.jsNumber("abc")).isNaN();
        assertThat(HrDb.take(null, 50, 200)).isEqualTo(50);
        assertThat(HrDb.take("500", 50, 200)).isEqualTo(200);
        assertThat(HrDb.skip(null)).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> HrDb.take("x", 50, 200))
                .isInstanceOf(IllegalStateException.class);
    }
}

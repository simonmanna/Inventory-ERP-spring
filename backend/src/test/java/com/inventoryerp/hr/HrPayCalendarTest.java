package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.hr.application.HrPayCalendar;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pay calendar maths of HP-0 pay groups. */
class HrPayCalendarTest {

    static List<String[]> periods(String code, String frequency, LocalDate anchor, int paymentDay, int year) {
        return HrPayCalendar.periods(code, frequency, anchor, paymentDay, year).stream()
                .map(s -> new String[] {s.code(), s.start().toString(), s.end().toString(), s.paymentDate().toString()})
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("monthly: twelve calendar months, payment day clamped to the month end, 0 = last day")
    void monthly() {
        List<String[]> p = periods("MAIN", "MONTHLY", null, 28, 2028);
        assertThat(p).hasSize(12);
        assertThat(p.get(0)).containsExactly("MAIN-2028-01", "2028-01-01", "2028-01-31", "2028-01-28");
        assertThat(p.get(1)).containsExactly("MAIN-2028-02", "2028-02-01", "2028-02-29", "2028-02-28");
        List<String[]> last = periods("MAIN", "MONTHLY", null, 0, 2027);
        assertThat(last.get(1)).containsExactly("MAIN-2027-02", "2027-02-01", "2027-02-28", "2027-02-28");
        List<String[]> late = periods("MAIN", "MONTHLY", null, 31, 2027);
        assertThat(late.get(3)[3]).isEqualTo("2027-04-30");
    }

    @Test
    @DisplayName("weekly: stepped from an earlier anchor, periods that start in the year, paid N days after the end")
    void weekly() {
        // 2026-12-28 is a Monday; 2027 weeks start 2027-01-04.
        List<String[]> p = periods("WK", "WEEKLY", LocalDate.of(2026, 12, 28), 3, 2027);
        assertThat(p.get(0)).containsExactly("WK-2027-W01", "2027-01-04", "2027-01-10", "2027-01-13");
        assertThat(p).hasSize(52);
        assertThat(p.get(51)[1]).isEqualTo("2027-12-27");
    }

    @Test
    @DisplayName("bi-weekly: fourteen-day periods; an anchor inside the year starts there; none for an earlier year")
    void biweekly() {
        List<String[]> p = periods("BW", "BIWEEKLY", LocalDate.of(2027, 3, 1), 0, 2027);
        assertThat(p.get(0)).containsExactly("BW-2027-B01", "2027-03-01", "2027-03-14", "2027-03-14");
        assertThat(p).hasSize(22);
        assertThat(periods("BW", "BIWEEKLY", LocalDate.of(2027, 3, 1), 0, 2026)).isEmpty();
    }
}

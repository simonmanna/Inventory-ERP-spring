package com.inventoryerp.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.fleet.application.DriverEligibilityPolicy.DriverFacts;
import com.inventoryerp.fleet.application.DriverEligibilityPolicy.VehicleFacts;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fleet 1.2 — who may take which vehicle: vehicle status, driver status, licence / permit / medical, class. */
class DriverEligibilityPolicyTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    static DriverFacts driver(String status, boolean employeeActive, String licence, LocalDate licenceExpires,
            String permit, LocalDate permitExpires, LocalDate medical, Set<String> classes) {
        return new DriverFacts("DRV-0001", status, employeeActive, licence, licenceExpires, permit, permitExpires,
                medical, classes);
    }

    static DriverFacts good() {
        return driver("active", true, "DL123", TODAY.plusYears(1), null, null, null, Set.of("tipper"));
    }

    static VehicleFacts tipper(String status) {
        return new VehicleFacts("VEH-0001", status, "tipper", "TIPPER", "Tipper truck", "road_vehicle");
    }

    static VehicleFacts excavator() {
        return new VehicleFacts("VEH-0002", "active", "exc", "EXCAVATOR", "Excavator", "mobile_plant");
    }

    @Test
    @DisplayName("a qualified driver with a valid licence may take an active road vehicle")
    void eligible() {
        assertThat(DriverEligibilityPolicy.reasons(good(), tipper("active"), TODAY, "assigned")).isEmpty();
    }

    @Test
    @DisplayName("vehicle not operational: workshop / breakdown / idle / disposed block with the verbatim message")
    void vehicleStatus() {
        assertThat(DriverEligibilityPolicy.reasons(good(), tipper("in_workshop"), TODAY, "checked out"))
                .containsExactly("Vehicle VEH-0001 is in workshop and cannot be checked out");
        assertThat(DriverEligibilityPolicy.reasons(good(), tipper("breakdown"), TODAY, "assigned"))
                .containsExactly("Vehicle VEH-0001 is breakdown and cannot be assigned");
    }

    @Test
    @DisplayName("licence missing / expired blocks road vehicles; a licence expiring today is still valid")
    void licence() {
        DriverFacts none = driver("active", true, null, null, null, null, null, Set.of("tipper"));
        assertThat(DriverEligibilityPolicy.reasons(none, tipper("active"), TODAY, "assigned"))
                .containsExactly("Driver DRV-0001 has no driving licence on file");
        DriverFacts expired = driver("active", true, "DL123", TODAY.minusDays(1), null, null, null, Set.of("tipper"));
        assertThat(DriverEligibilityPolicy.reasons(expired, tipper("active"), TODAY, "assigned"))
                .containsExactly("Driver DRV-0001's driving licence expired on 2026-10-28");
        DriverFacts lastDay = driver("active", true, "DL123", TODAY, null, null, null, Set.of("tipper"));
        assertThat(DriverEligibilityPolicy.reasons(lastDay, tipper("active"), TODAY, "assigned")).isEmpty();
        DriverFacts lifetime = driver("active", true, "DL123", null, null, null, null, Set.of("tipper"));
        assertThat(DriverEligibilityPolicy.reasons(lifetime, tipper("active"), TODAY, "assigned")).isEmpty();
    }

    @Test
    @DisplayName("mobile plant: a valid operator permit or a valid licence; neither blocks")
    void mobilePlant() {
        DriverFacts permitOnly = driver("active", true, null, null, "OP-9", TODAY.plusMonths(3), null, Set.of("exc"));
        assertThat(DriverEligibilityPolicy.reasons(permitOnly, excavator(), TODAY, "assigned")).isEmpty();
        DriverFacts licenceOnly = driver("active", true, "DL1", TODAY.plusMonths(3), null, null, null, Set.of("exc"));
        assertThat(DriverEligibilityPolicy.reasons(licenceOnly, excavator(), TODAY, "assigned")).isEmpty();
        DriverFacts lapsed = driver("active", true, "DL1", TODAY.minusDays(3), "OP-9", TODAY.minusDays(1), null,
                Set.of("exc"));
        assertThat(DriverEligibilityPolicy.reasons(lapsed, excavator(), TODAY, "assigned"))
                .containsExactly("Driver DRV-0001 has no valid driving licence or operator permit for Excavator");
        VehicleFacts generator = new VehicleFacts("VEH-0003", "active", "gen", "GENERATOR", "Generator",
                "fixed_plant");
        DriverFacts nothing = driver("active", true, null, null, null, null, null, Set.of("gen"));
        assertThat(DriverEligibilityPolicy.reasons(nothing, generator, TODAY, "assigned")).isEmpty();
    }

    @Test
    @DisplayName("not qualified for the class, expired medical, suspended driver, inactive employee — in order")
    void orderOfReasons() {
        DriverFacts d = driver("suspended", false, "DL1", TODAY.plusYears(1), null, null, TODAY.minusDays(10),
                Set.of("other"));
        assertThat(DriverEligibilityPolicy.reasons(d, tipper("idle"), TODAY, "checked out")).containsExactly(
                "Vehicle VEH-0001 is idle and cannot be checked out",
                "Driver DRV-0001 is suspended and cannot be checked out",
                "Driver DRV-0001 is not an active employee in HR",
                "Driver DRV-0001's medical certificate expired on 2026-10-19",
                "Driver DRV-0001 is not qualified for vehicle class Tipper truck (TIPPER)");
        assertThatThrownBy(() -> DriverEligibilityPolicy.assertEligible(d, tipper("idle"), TODAY, "checked out"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Vehicle VEH-0001 is idle and cannot be checked out");
    }
}

package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * May this driver / operator take this vehicle on this day (fleet 1.2, ADR-S009)? Checked when a driver is assigned
 * and when a vehicle is checked out; the reasons are returned in a fixed order and the first one is the error.
 *
 * <ol>
 *   <li>the vehicle is operational ({@link VehicleStatusPolicy#isOperational}: only {@code active});</li>
 *   <li>the driver profile is {@code active} and the hr employee is active;</li>
 *   <li>road vehicles and trailers need a driving licence that has not expired; mobile plant needs a valid driving
 *       licence or a valid operator permit; fixed plant / other classes need neither;</li>
 *   <li>a medical certificate on file must not have expired (any class);</li>
 *   <li>the vehicle's class is one of the driver's qualified classes.</li>
 * </ol>
 * A licence or permit without an expiry date counts as valid (lifetime documents). Expiry is inclusive: a document
 * expiring today is still valid today.
 */
public final class DriverEligibilityPolicy {

    private static final Set<String> NEEDS_LICENCE = Set.of("road_vehicle", "trailer");
    private static final String MOBILE_PLANT = "mobile_plant";

    private DriverEligibilityPolicy() {}

    /** What the policy needs to know about the driver. */
    public record DriverFacts(String code, String status, boolean employeeActive, String licenceNumber,
            LocalDate licenceExpiresOn, String permitNumber, LocalDate permitExpiresOn, LocalDate medicalExpiresOn,
            Set<String> qualifiedCategoryIds) {}

    /** What the policy needs to know about the vehicle and its class. */
    public record VehicleFacts(String code, String status, String categoryId, String categoryCode,
            String categoryName, String categoryKind) {}

    /**
     * Blocking reasons (empty = eligible). {@code action} completes "cannot be …" in the vehicle message
     * ({@code "assigned"} / {@code "checked out"}).
     */
    public static List<String> reasons(DriverFacts d, VehicleFacts v, LocalDate on, String action) {
        List<String> out = new ArrayList<>();
        if (!VehicleStatusPolicy.isOperational(v.status())) {
            out.add("Vehicle " + v.code() + " is " + VehicleStatusPolicy.label(v.status()) + " and cannot be "
                    + action);
        }
        if (!"active".equals(d.status())) {
            out.add("Driver " + d.code() + " is " + d.status() + " and cannot be " + action);
        }
        if (!d.employeeActive()) {
            out.add("Driver " + d.code() + " is not an active employee in HR");
        }
        boolean licenceValid = hasValid(d.licenceNumber(), d.licenceExpiresOn(), on);
        boolean permitValid = hasValid(d.permitNumber(), d.permitExpiresOn(), on);
        if (NEEDS_LICENCE.contains(v.categoryKind()) && !licenceValid) {
            out.add(d.licenceNumber() == null || d.licenceNumber().isBlank()
                    ? "Driver " + d.code() + " has no driving licence on file"
                    : "Driver " + d.code() + "'s driving licence expired on " + d.licenceExpiresOn());
        } else if (MOBILE_PLANT.equals(v.categoryKind()) && !licenceValid && !permitValid) {
            out.add("Driver " + d.code() + " has no valid driving licence or operator permit for "
                    + v.categoryName());
        }
        if (d.medicalExpiresOn() != null && d.medicalExpiresOn().isBefore(on)) {
            out.add("Driver " + d.code() + "'s medical certificate expired on " + d.medicalExpiresOn());
        }
        if (v.categoryId() != null && !d.qualifiedCategoryIds().contains(v.categoryId())) {
            out.add("Driver " + d.code() + " is not qualified for vehicle class " + v.categoryName() + " ("
                    + v.categoryCode() + ")");
        }
        return out;
    }

    /** Throws a 400 with the first blocking reason. */
    public static void assertEligible(DriverFacts d, VehicleFacts v, LocalDate on, String action) {
        List<String> r = reasons(d, v, on, action);
        if (!r.isEmpty()) {
            throw ApiException.badRequest(r.getFirst());
        }
    }

    /** A document on file (number present) that has no expiry or expires on / after {@code on}. */
    static boolean hasValid(String number, LocalDate expiresOn, LocalDate on) {
        return number != null && !number.isBlank() && (expiresOn == null || !expiresOn.isBefore(on));
    }
}

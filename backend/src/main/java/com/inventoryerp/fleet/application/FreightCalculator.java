package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Third-party transporter freight of a trip (fleet 1.3): {@code per_trip} = the rate, {@code per_tonne} = rate × tonnes
 * carried, {@code per_km} = rate × trip distance; HALF_UP to 2 decimals.
 */
public final class FreightCalculator {

    private FreightCalculator() {}

    public static BigDecimal amount(String basis, BigDecimal rate, BigDecimal tonnes, BigDecimal km) {
        if (basis == null || rate == null) {
            return null;
        }
        BigDecimal qty = switch (basis) {
            case "per_trip" -> BigDecimal.ONE;
            case "per_tonne" -> {
                if (tonnes == null) {
                    throw ApiException.badRequest("The freight is paid per tonne but the trip has no weighed or"
                            + " planned tonnage");
                }
                yield tonnes;
            }
            case "per_km" -> {
                if (km == null) {
                    throw ApiException.badRequest("Enter the trip distance: the freight is paid per km");
                }
                yield km;
            }
            default -> throw ApiException.badRequest("Unknown freight basis " + basis);
        };
        return rate.multiply(qty).setScale(2, RoundingMode.HALF_UP);
    }
}

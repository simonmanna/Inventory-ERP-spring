package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Third-party transporter freight of a trip (fleet 1.3): {@code per_trip} = the rate, {@code per_tonne} = rate × tonnes
 * carried, {@code per_km} = rate × trip distance; CM-7 (ADR-S037) adds {@code per_m3} = rate × volume, {@code
 * per_tonne_km} = rate × tonnes × km and a minimum charge per trip; HALF_UP to 2 decimals.
 */
public final class FreightCalculator {

    private FreightCalculator() {}

    public static BigDecimal amount(String basis, BigDecimal rate, BigDecimal tonnes, BigDecimal km) {
        return amount(basis, rate, tonnes, km, null, null);
    }

    /** As {@link #amount(String, BigDecimal, BigDecimal, BigDecimal)} with volume and a minimum charge. */
    public static BigDecimal amount(String basis, BigDecimal rate, BigDecimal tonnes, BigDecimal km, BigDecimal m3,
            BigDecimal minimum) {
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
            case "per_m3" -> {
                if (m3 == null) {
                    throw ApiException.badRequest("The freight is paid per m³ but the trip has no planned volume");
                }
                yield m3;
            }
            case "per_tonne_km" -> {
                if (tonnes == null || km == null) {
                    throw ApiException.badRequest("The freight is paid per tonne-km: the trip needs its tonnage and"
                            + " distance");
                }
                yield tonnes.multiply(km);
            }
            default -> throw ApiException.badRequest("Unknown freight basis " + basis);
        };
        BigDecimal amount = rate.multiply(qty).setScale(2, RoundingMode.HALF_UP);
        return minimum != null && minimum.compareTo(amount) > 0 ? minimum.setScale(2, RoundingMode.HALF_UP) : amount;
    }
}

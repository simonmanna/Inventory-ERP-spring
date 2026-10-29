package com.inventoryerp.fleet.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;

/**
 * Trip distance from GPS positions (fleet 1.8): great-circle (haversine) distance summed over consecutive fixes. A
 * segment whose implied speed exceeds {@value #MAX_PLAUSIBLE_KMH} km/h is a GPS glitch and is skipped (the next fix
 * continues from the last plausible one). Coordinates are geodesic doubles, never money; the result is km at 3
 * decimals (HALF_UP).
 */
public final class GeoDistance {

    static final double EARTH_RADIUS_KM = 6371.0088;
    static final double MAX_PLAUSIBLE_KMH = 250;

    /** One fix: time and position (the public position API record carries the same fields). */
    public record Fix(java.time.Instant at, double lat, double lon) {}

    private GeoDistance() {}

    /** Great-circle distance in km between two coordinates (degrees). */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Path length of time-ordered fixes in km (3 decimals); 0 for fewer than two fixes. */
    public static BigDecimal pathKm(List<Fix> fixes) {
        double km = 0;
        Fix last = null;
        for (Fix f : fixes) {
            if (last != null) {
                double d = haversineKm(last.lat(), last.lon(), f.lat(), f.lon());
                long ms = Duration.between(last.at(), f.at()).toMillis();
                if (ms > 0 && d / (ms / 3_600_000.0) > MAX_PLAUSIBLE_KMH) {
                    continue;
                }
                km += d;
            }
            last = f;
        }
        return BigDecimal.valueOf(km).setScale(3, RoundingMode.HALF_UP);
    }
}

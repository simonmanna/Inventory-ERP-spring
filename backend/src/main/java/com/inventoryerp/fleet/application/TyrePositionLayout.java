package com.inventoryerp.fleet.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wheel positions of a vehicle derived from its axle configuration (fleet 1.7). Codes: {@code A1L} / {@code A1R} for
 * a single wheel, {@code A2LO} / {@code A2LI} / {@code A2RI} / {@code A2RO} for a dual, {@code SP1} for the spare.
 *
 * <ul>
 *   <li>Road vehicles: {@code WxD} (wheel ends × driven, e.g. 4x2, 6x4, 8x4) → W/2 axles; one steer axle (two from
 *       8x4 up) with singles, the others duals (singles for light vehicles and all-wheel drive like 4x4); one spare.
 *   <li>Trailers: the first number is the axle count ("3", "3-axle", "tri-axle" = 3); duals; one spare.
 *   <li>Mobile / fixed plant and others: {@code WxD} → W/2 axles of singles (loaders, dozers, forklifts); no spare.
 * </ul>
 * Without a usable configuration the class kind decides: road vehicle 4x2, trailer 3 axles, plant 2 axles.
 */
public final class TyrePositionLayout {

    private static final Pattern WHEELS = Pattern.compile("(\\d{1,2})\\s*[xX×*]\\s*(\\d{1,2})");
    private static final Pattern NUMBER = Pattern.compile("(\\d{1,2})");
    private static final int MAX_AXLES = 10;

    public record Position(String code, int axle, String side, String slot, boolean spare) {}

    public record Axle(int number, boolean steer, boolean dual, List<Position> positions) {}

    public record Layout(String axleConfig, List<Axle> axles, List<Position> spares) {

        public List<String> codes() {
            List<String> out = new ArrayList<>();
            axles.forEach(a -> a.positions().forEach(p -> out.add(p.code())));
            spares.forEach(p -> out.add(p.code()));
            return out;
        }

        public boolean contains(String code) {
            return code != null && codes().contains(code);
        }
    }

    private TyrePositionLayout() {}

    /** Layout of a vehicle: axle configuration text, class kind and class code (LIGHT_VEHICLE → single rears). */
    public static Layout forVehicle(String axleConfig, String kind, String categoryCode) {
        String k = kind == null ? "road_vehicle" : kind;
        String cfg = axleConfig == null ? "" : axleConfig.trim().toLowerCase(Locale.ROOT);
        if ("trailer".equals(k)) {
            int axles = trailerAxles(cfg);
            List<Axle> list = new ArrayList<>();
            for (int i = 1; i <= axles; i++) {
                list.add(axle(i, false, true));
            }
            return new Layout(axleConfig, list, List.of(spare(1)));
        }
        Matcher m = WHEELS.matcher(cfg);
        int wheels = 4;
        int driven = 2;
        if (m.find()) {
            wheels = Math.max(2, Integer.parseInt(m.group(1)));
            driven = Integer.parseInt(m.group(2));
        }
        int axles = Math.max(1, Math.min(MAX_AXLES, wheels / 2));
        boolean road = "road_vehicle".equals(k);
        if (!road) {
            List<Axle> list = new ArrayList<>();
            for (int i = 1; i <= axles; i++) {
                list.add(axle(i, i == 1, false));
            }
            return new Layout(axleConfig, list, List.of());
        }
        int steer = wheels >= 8 ? Math.min(2, axles) : 1;
        boolean singleRears = "LIGHT_VEHICLE".equalsIgnoreCase(categoryCode) || driven >= wheels;
        List<Axle> list = new ArrayList<>();
        for (int i = 1; i <= axles; i++) {
            boolean isSteer = i <= steer;
            list.add(axle(i, isSteer, !isSteer && !singleRears));
        }
        return new Layout(axleConfig, list, List.of(spare(1)));
    }

    private static int trailerAxles(String cfg) {
        if (cfg.contains("tri")) {
            return 3;
        }
        if (cfg.contains("tandem")) {
            return 2;
        }
        Matcher m = NUMBER.matcher(cfg);
        if (m.find()) {
            return Math.max(1, Math.min(MAX_AXLES, Integer.parseInt(m.group(1))));
        }
        return 3;
    }

    private static Axle axle(int n, boolean steer, boolean dual) {
        List<Position> p = new ArrayList<>();
        if (dual) {
            p.add(new Position("A" + n + "LO", n, "left", "outer", false));
            p.add(new Position("A" + n + "LI", n, "left", "inner", false));
            p.add(new Position("A" + n + "RI", n, "right", "inner", false));
            p.add(new Position("A" + n + "RO", n, "right", "outer", false));
        } else {
            p.add(new Position("A" + n + "L", n, "left", "single", false));
            p.add(new Position("A" + n + "R", n, "right", "single", false));
        }
        return new Axle(n, steer, dual, List.copyOf(p));
    }

    private static Position spare(int n) {
        return new Position("SP" + n, 0, "spare", "spare", true);
    }
}

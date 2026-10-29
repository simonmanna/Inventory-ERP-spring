package com.inventoryerp.fleet.application;

import java.io.EOFException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deterministic weighbridge simulator (fleet 1.4) for tests and the demo. It produces indicator frames in the default
 * ASCII format ({@code US,GS,+0032130kg} while "moving", then {@code ST,GS,+0032150kg}) and reads them through the same
 * {@link AsciiFrameParser} / {@link StableWeightReader} as a real indicator. Configuration:
 * {@code weightKg} (the weight on the platform; set it with {@code POST /fleet/weighbridge/devices/{id}/simulator-weight}),
 * {@code motionFrames} (motion frames before it settles, default 2) and {@code mode} — {@code ok} (default),
 * {@code unstable} (never settles: the read times out), {@code overload}, {@code silent} (no frames: timeout),
 * {@code garbage} (frames in an unknown format) or {@code offline} (unreachable).
 */
@Component
public class SimulatorWeighbridgeAdapter implements WeighbridgeAdapter {

    public static final String TYPE = "simulator";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void validate(Map<String, Object> config) {
        Map<String, Object> c = config == null ? Map.of() : config;
        weight(c);
        int motion = AsciiFrameParser.integer(c.get("motionFrames"), 2);
        if (motion < 0 || motion > 50) {
            throw new IllegalArgumentException("Motion frames must be between 0 and 50");
        }
        String mode = mode(c);
        if (!java.util.Set.of("ok", "unstable", "overload", "silent", "garbage", "offline").contains(mode)) {
            throw new IllegalArgumentException("Simulator mode " + mode
                    + " is not known (ok, unstable, overload, silent, garbage, offline)");
        }
    }

    @Override
    public StableWeight readStable(DeviceSpec device, Duration timeout) {
        Map<String, Object> c = device.config() == null ? Map.of() : device.config();
        String mode = mode(c);
        if ("offline".equals(mode)) {
            throw new WeighbridgeReadException("unreachable", "Indicator " + device.code() + " is offline");
        }
        BigDecimal kg = weight(c);
        int motion = AsciiFrameParser.integer(c.get("motionFrames"), 2);
        AsciiFrameParser parser = AsciiFrameParser.of(Map.of());
        Deque<String> frames = new ArrayDeque<>();
        for (int i = motion; i > 0; i--) {
            frames.add(frame("US", kg.subtract(BigDecimal.valueOf(10L * i))));
        }
        for (int i = 0; i < parser.stableFrames(); i++) {
            frames.add(frame("ST", kg));
        }
        String overload = frame("OL", new BigDecimal("9999999"));
        StableWeightReader.FrameSource source = max -> switch (mode) {
            case "unstable" -> pause(max, frame("US", kg));
            case "silent" -> pause(max, null);
            case "garbage" -> pause(max, "@@ noise ##");
            case "overload" -> overload;
            default -> {
                String f = frames.poll();
                if (f == null) {
                    throw new EOFException();
                }
                yield f;
            }
        };
        return StableWeightReader.read(source, parser, timeout, device.code());
    }

    @Override
    public DeviceHealth health(DeviceSpec device, Duration timeout) {
        boolean offline = "offline".equals(mode(device.config() == null ? Map.of() : device.config()));
        return new DeviceHealth(!offline, offline ? "Simulator is offline" : "Simulator ready");
    }

    /** {@code ST,GS,+0032150kg}: status, gross, sign, 7 digits, kg. */
    static String frame(String status, BigDecimal kg) {
        BigDecimal w = kg.setScale(0, RoundingMode.HALF_UP);
        return String.format(Locale.ROOT, "%s,GS,%s%07d kg", status, w.signum() < 0 ? "-" : "+",
                w.abs().longValueExact());
    }

    /** Emits {@code frame} after a short pause (so a never-settling indicator does not spin). */
    private static String pause(Duration max, String frame) {
        try {
            Thread.sleep(Math.min(50, Math.max(1, max.toMillis())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return frame;
    }

    private static String mode(Map<String, Object> c) {
        String m = AsciiFrameParser.str(c.get("mode"));
        return m == null ? "ok" : m.trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal weight(Map<String, Object> c) {
        Object w = c.get("weightKg");
        if (w == null) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal kg = new BigDecimal(String.valueOf(w).trim());
            if (kg.abs().compareTo(new BigDecimal("9999999")) > 0) {
                throw new IllegalArgumentException("The simulated weight is out of range");
            }
            return kg;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The simulated weight must be a number");
        }
    }
}

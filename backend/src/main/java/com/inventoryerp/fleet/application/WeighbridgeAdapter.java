package com.inventoryerp.fleet.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Weighbridge indicator SPI (fleet 1.4, ADR-S009 §7). An implementation reads a <b>stable</b> weight from the indicator
 * of a {@link DeviceSpec}; it never blocks longer than the given timeout (it throws {@link WeighbridgeReadException}
 * instead). Implementations are Spring beans selected by {@link #type()}: {@code simulator} (deterministic, tests and
 * demo), {@code ascii-tcp} (generic continuous-output ASCII indicator over TCP, e.g. through a serial-to-Ethernet
 * converter) and {@code ascii-serial} (the same frames from a local serial device file). Vendor adapters can be added
 * as further beans without schema changes.
 *
 * <p>Deviation from the ADR sketch: the device is passed as a {@link DeviceSpec} record (id, code, adapter type,
 * configuration map) rather than the JPA entity, because fleet rows are read through JDBC.
 */
public interface WeighbridgeAdapter {

    /** Adapter type stored on the device ({@code simulator}, {@code ascii-tcp}, {@code ascii-serial}). */
    String type();

    /** Blocks until the indicator reports a stable weight or {@code timeout} elapses (then throws). */
    StableWeight readStable(DeviceSpec device, Duration timeout);

    /** Quick reachability probe (bounded by {@code timeout}); never throws. */
    DeviceHealth health(DeviceSpec device, Duration timeout);

    /** Validates a device configuration before it is stored; throws {@link IllegalArgumentException} with a message. */
    default void validate(Map<String, Object> config) {
        AsciiFrameParser.of(config);
    }

    /** The device as adapters see it. */
    record DeviceSpec(String id, String code, String adapterType, Map<String, Object> config) {}

    /** A stable reading: weight in kilograms, when, the raw indicator frame (kept for audit). */
    record StableWeight(BigDecimal kilograms, Instant at, String rawFrame, boolean stable) {}

    record DeviceHealth(boolean reachable, String detail) {}
}

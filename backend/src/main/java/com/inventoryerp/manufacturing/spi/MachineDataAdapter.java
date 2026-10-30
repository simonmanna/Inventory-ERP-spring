package com.inventoryerp.manufacturing.spi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Production data capture SPI (ADR-S011 §11, ADR-S015): a PLC, counter, belt scale or data gateway delivers output
 * counts and downtime per machine. Events are never posted: the shop floor turns them into <b>draft</b> production
 * reports / downtime that an operator confirms on the terminal (confirmation posts through the stage engine).
 *
 * <p>Implementations are Spring beans keyed by {@link #code()}. Pull adapters ({@link #pull()} true) are polled by
 * {@code POST /manufacturing/shop-floor/machine-data/poll}; push sources (an MQTT bridge, a gateway) post events to
 * {@code POST /manufacturing/shop-floor/machine-data/push} and need no bean. Built in: {@code simulator} (deterministic
 * demo data) and {@code rest} (HTTP pull from a configured gateway).
 */
public interface MachineDataAdapter {

    /** Adapter key (stored on every event; external ids are unique per adapter). */
    String code();

    /** True when the adapter can be used (endpoint / credentials configured). */
    boolean configured();

    /** True when the adapter is polled (false = push only). */
    default boolean pull() {
        return true;
    }

    /** Events of the given machines in {@code [since, until)}; may be empty. */
    List<MachineEvent> poll(PollRequest request);

    /** A machine the shop floor asks about: its work centre, machine code and ideal rate (for simulators). */
    record MachineRef(String workCenterId, String machineCode, BigDecimal idealRatePerHour) {}

    /** What to poll. */
    record PollRequest(List<MachineRef> machines, Instant since, Instant until) {}

    /**
     * One machine event. {@code type}: {@code count} (good / scrap produced in {@code [occurredAt, endedAt)}; a belt
     * scale reports tonnes as good quantity), {@code downtime} (a closed stop), {@code downtime_start} /
     * {@code downtime_stop} (an open stop and its end). {@code externalId} must be stable across re-deliveries.
     */
    record MachineEvent(String externalId, String machineCode, String type, Instant occurredAt, Instant endedAt,
            BigDecimal goodQty, BigDecimal scrapQty, String reasonCode, String notes) {}
}

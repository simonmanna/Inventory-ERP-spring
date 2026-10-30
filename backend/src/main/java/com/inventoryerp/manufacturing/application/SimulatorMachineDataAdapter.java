package com.inventoryerp.manufacturing.application;

import com.inventoryerp.manufacturing.spi.MachineDataAdapter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Machine-data simulator (ADR-S015) for demos and tests, deterministic: for every fully elapsed clock hour of the
 * window and every machine, one 6-minute minor stop at :20 ({@code MINOR_STOP}) and one count for the hour — 54 run
 * minutes at 90 % of the ideal rate (60 / h when none is set), 2 % of it scrap. External ids are {@code
 * SIM-<machine>-<epoch hour>[-D]}, so polling the same window again creates nothing new. At most 48 hours.
 */
@Component
public class SimulatorMachineDataAdapter implements MachineDataAdapter {

    static final int MAX_HOURS = 48;
    private static final BigDecimal DEFAULT_RATE = BigDecimal.valueOf(60);

    @Override
    public String code() {
        return "simulator";
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public List<MachineEvent> poll(PollRequest request) {
        List<MachineEvent> out = new ArrayList<>();
        Instant until = request.until() != null ? request.until() : Instant.now();
        Instant since = request.since() != null ? request.since() : until.minus(8, ChronoUnit.HOURS);
        Instant first = since.truncatedTo(ChronoUnit.HOURS);
        if (first.isBefore(since)) {
            first = first.plus(1, ChronoUnit.HOURS);
        }
        Instant last = until.minus(MAX_HOURS, ChronoUnit.HOURS);
        if (first.isBefore(last)) {
            first = last.truncatedTo(ChronoUnit.HOURS);
        }
        for (MachineRef m : request.machines()) {
            BigDecimal rate = m.idealRatePerHour() != null && m.idealRatePerHour().signum() > 0
                    ? m.idealRatePerHour() : DEFAULT_RATE;
            BigDecimal total = rate.multiply(BigDecimal.valueOf(54)).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("0.9")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal scrap = total.multiply(new BigDecimal("0.02")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal good = total.subtract(scrap);
            for (Instant h = first; !h.plus(1, ChronoUnit.HOURS).isAfter(until); h = h.plus(1, ChronoUnit.HOURS)) {
                String id = "SIM-" + m.machineCode() + "-" + h.getEpochSecond() / 3600;
                Instant stop = h.plus(20, ChronoUnit.MINUTES);
                out.add(new MachineEvent(id + "-D", m.machineCode(), "downtime", stop, stop.plus(6, ChronoUnit.MINUTES),
                        null, null, "MINOR_STOP", "Simulated minor stop"));
                out.add(new MachineEvent(id, m.machineCode(), "count", h, h.plus(1, ChronoUnit.HOURS), good, scrap,
                        null, "Simulated hourly count"));
            }
        }
        return out;
    }
}

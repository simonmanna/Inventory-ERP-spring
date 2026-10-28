package com.inventoryerp.communication.application;

import com.inventoryerp.kernel.crud.SqlRows;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Prisma {@code new Date()}: millisecond wall clock, written to {@code timestamp(3)} columns as UTC. */
final class CommTime {

    private CommTime() {}

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    static Timestamp ts(Instant instant) {
        return SqlRows.ts(instant);
    }

    static Timestamp nowTs() {
        return ts(now());
    }
}

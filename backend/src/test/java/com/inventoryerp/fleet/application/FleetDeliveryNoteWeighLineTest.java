package com.inventoryerp.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The delivery note prints the feeding weighbridge ticket's number and weights instead of its raw id (Phase A1). */
class FleetDeliveryNoteWeighLineTest {

    static Map<String, Object> ticket(boolean manual) {
        Map<String, Object> t = new HashMap<>();
        t.put("ticketNumber", "WBT-000042");
        t.put("grossKg", new BigDecimal("32040.00"));
        t.put("tareKg", new BigDecimal("12000.00"));
        t.put("netKg", new BigDecimal("20040.00"));
        t.put("isManual", manual);
        return t;
    }

    @Test
    void printsNumberAndWeights() {
        assertThat(FleetDeliveryNoteService.weighLine(ticket(false), "id-1"))
                .isEqualTo("Weighbridge ticket: WBT-000042\nGross 32040 kg · Tare 12000 kg · Net 20040 kg");
    }

    @Test
    void flagsManualWeight() {
        assertThat(FleetDeliveryNoteService.weighLine(ticket(true), "id-1")).endsWith(" (manual weight)");
    }

    @Test
    void fallsBackToTheIdWhenTheTicketIsNotLoaded() {
        assertThat(FleetDeliveryNoteService.weighLine(null, "id-1")).isEqualTo("Weighbridge ticket: id-1");
    }

    @Test
    void missingWeightsPrintADash() {
        Map<String, Object> t = ticket(false);
        t.put("tareKg", null);
        assertThat(FleetDeliveryNoteService.weighLine(t, "id-1")).contains("Tare — kg");
    }
}

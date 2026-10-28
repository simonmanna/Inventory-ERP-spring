package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.workflow.LifecycleRegistry;
import com.inventoryerp.kernel.workflow.LifecycleRegistry.Definition;
import com.inventoryerp.kernel.workflow.LifecycleRegistry.Transition;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Rental custody lifecycles (port of {@code rental-lifecycles.ts}, registered like {@code RentalModule.onModuleInit}).
 * Agreement: draft → confirmed → checked_out → partially_returned / returned → closed; cancel from any non-terminal
 * state. Unit: acquire → available → checked_out → inspect / dispose / swap ...; retire anywhere.
 */
@Component
public class RentalLifecycles {

    public static final String AGREEMENT = "custody.rental_agreement";
    public static final String UNIT = "custody.rental_unit";

    private final LifecycleRegistry registry;

    public RentalLifecycles(LifecycleRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void register() {
        registry.register(new Definition(AGREEMENT, "rental_agreement",
                List.of("draft", "pending_approval", "confirmed", "checked_out", "partially_returned", "returned",
                        "closed", "cancelled"),
                List.of(new Transition("draft", "confirmed", "confirm"),
                        new Transition(null, "cancelled", "cancel"),
                        new Transition("confirmed", "checked_out", "checkout"),
                        new Transition(null, "partially_returned", "partial_return"),
                        new Transition(null, "returned", "return"),
                        new Transition(null, "closed", "settle")),
                List.of("closed", "cancelled")));
        registry.register(new Definition(UNIT, "rental_unit",
                List.of("available", "reserved", "checked_out", "returned", "cleaning", "repair", "damaged", "lost",
                        "retired"),
                List.of(new Transition(null, "available", "acquire"),
                        new Transition("available", "checked_out", "checkout"),
                        new Transition("checked_out", "checked_out", "extend"),
                        new Transition("checked_out", "cleaning", "swap"),
                        new Transition("available", "checked_out", "swap"),
                        new Transition("checked_out", "available", "inspect"),
                        new Transition("checked_out", "damaged", "dispose"),
                        new Transition(null, "retired", "retire")),
                List.of("retired")));
    }
}

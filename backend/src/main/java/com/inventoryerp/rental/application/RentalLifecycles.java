package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.modules.ModuleRegistry;
import com.inventoryerp.kernel.workflow.LifecycleRegistry;
import com.inventoryerp.kernel.workflow.LifecycleRegistry.Definition;
import com.inventoryerp.kernel.workflow.LifecycleRegistry.Transition;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Rental custody lifecycles (port of {@code rental-lifecycles.ts}) and the module manifest, registered like
 * {@code RentalModule.onModuleInit} when ENABLE_RENTAL is on: the {@code rental} module with its {@code rental} order
 * kind (the fee orders are validated against it). Agreement: draft → confirmed → checked_out → partially_returned /
 * returned → closed; cancel from any non-terminal state. Unit: acquire → available → checked_out → inspect / dispose /
 * swap ...; retire anywhere.
 */
@Component
@ConditionalOnProperty(name = "app.modules.rental", havingValue = "true")
public class RentalLifecycles {

    public static final String AGREEMENT = "custody.rental_agreement";
    public static final String UNIT = "custody.rental_unit";

    private final LifecycleRegistry registry;
    private final ModuleRegistry modules;

    public RentalLifecycles(LifecycleRegistry registry, ModuleRegistry modules) {
        this.registry = registry;
        this.modules = modules;
    }

    @PostConstruct
    void register() {
        // Nest also lists dependencies [core, accounting, invoicing, inventory, pos, expenses]; those modules register
        // no manifest in this backend, so declaring them would fail the registry's boot validation.
        modules.register(new ModuleRegistry.ErpModule("rental", "1.0.0", List.of(),
                List.of(new ModuleRegistry.OrderKind("rental", "Rental"))));
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

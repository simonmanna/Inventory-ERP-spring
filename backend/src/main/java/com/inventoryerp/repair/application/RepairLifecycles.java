package com.inventoryerp.repair.application;

import com.inventoryerp.kernel.modules.ModuleRegistry;
import com.inventoryerp.kernel.workflow.LifecycleRegistry;
import com.inventoryerp.kernel.workflow.LifecycleRegistry.Definition;
import com.inventoryerp.kernel.workflow.LifecycleRegistry.Transition;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Repair order lifecycle ({@code repair-lifecycles.ts}) and the module manifest ({@code RepairModule.onModuleInit}):
 * registers {@code repair.order} with the lifecycle registry and the {@code repair} order kind with the module
 * registry.
 *
 * <p>Order spine: received → diagnosis → waiting_approval → approved → repairing → testing → ready_pickup → delivered
 * → closed. {@code cancelled} is legal from any state. Terminal: closed, cancelled.
 */
@Component
@ConditionalOnProperty(name = "app.modules.repair", havingValue = "true")
public class RepairLifecycles {

    public static final String KEY = "repair.order";

    public static final Definition REPAIR_ORDER_LIFECYCLE = new Definition(KEY, "repair_order",
            List.of("received", "diagnosis", "waiting_approval", "approved", "repairing", "testing", "ready_pickup",
                    "delivered", "closed", "cancelled"),
            List.of(
                    new Transition("received", "diagnosis", "diagnose"),
                    new Transition("diagnosis", "waiting_approval", "quote"),
                    new Transition("waiting_approval", "approved", "approve"),
                    new Transition("waiting_approval", "waiting_approval", "revision"),
                    new Transition("approved", "repairing", "start"),
                    new Transition("repairing", "testing", "test"),
                    new Transition("testing", "repairing", "rework"),
                    new Transition("testing", "ready_pickup", "ready"),
                    new Transition("ready_pickup", "delivered", "deliver"),
                    new Transition("delivered", "closed", "close"),
                    new Transition(null, "cancelled", "cancel")),
            List.of("closed", "cancelled"));

    public RepairLifecycles(LifecycleRegistry lifecycles, ModuleRegistry modules) {
        // Nest also lists dependencies [core, accounting, invoicing, inventory, pos]; those modules do not register
        // manifests in this backend, so declaring them would fail the registry's boot validation.
        modules.register(new ModuleRegistry.ErpModule("repair", "1.0.0", List.of(),
                List.of(new ModuleRegistry.OrderKind("repair", "Repair"))));
        lifecycles.register(REPAIR_ORDER_LIFECYCLE);
    }
}

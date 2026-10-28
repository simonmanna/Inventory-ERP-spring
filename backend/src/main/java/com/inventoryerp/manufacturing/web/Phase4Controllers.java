package com.inventoryerp.manufacturing.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.manufacturing.application.MfgCommands.AssignWorkOrder;
import com.inventoryerp.manufacturing.application.MfgCommands.CompleteWorkOrder;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateResource;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateRouting;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateWorkCenter;
import com.inventoryerp.manufacturing.application.ProductionReportService;
import com.inventoryerp.manufacturing.application.ResourceService;
import com.inventoryerp.manufacturing.application.RoutingService;
import com.inventoryerp.manufacturing.application.WorkCenterService;
import com.inventoryerp.manufacturing.application.WorkOrderService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The five controllers of {@code phase4.controller.ts} (work centres, resources, routings, work orders). Their
 * one-line decorators are missed by the route extractor, so these 19 routes are not in the generated table; they are
 * registered by the Nest module and ported here. PATCH bodies are {@code Partial<Dto>} — erased, so unvalidated.
 */
final class Phase4Controllers {

    private Phase4Controllers() {}

    /** {@code /manufacturing/work-centers}. */
    @RestController
    @RequestMapping("/manufacturing/work-centers")
    @RequiresModule("manufacturing")
    @ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
    static class WorkCenterController {

        private final WorkCenterService svc;
        private final ProductionReportService reports;

        WorkCenterController(WorkCenterService svc, ProductionReportService reports) {
            this.svc = svc;
            this.reports = reports;
        }

        @GetMapping
        @RequirePermissions(Permissions.WORK_CENTER__READ)
        public List<Map<String, Object>> list() {
            return svc.list();
        }

        @GetMapping("/capacity")
        @RequirePermissions(Permissions.WORK_CENTER__READ)
        public List<Map<String, Object>> capacity() {
            return reports.capacity();
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.WORK_CENTER__CREATE)
        public Map<String, Object> create(@Valid @RequestBody CreateWorkCenter dto) {
            return svc.create(dto);
        }

        @PatchMapping("/{id}")
        @RequirePermissions(Permissions.WORK_CENTER__UPDATE)
        public Map<String, Object> update(@PathVariable String id,
                @RequestBody(required = false) Map<String, Object> dto) {
            return svc.update(id, dto);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        @RequirePermissions(Permissions.WORK_CENTER__DELETE)
        public void remove(@PathVariable String id) {
            svc.remove(id);
        }
    }

    /** {@code /manufacturing/resources}. */
    @RestController
    @RequestMapping("/manufacturing/resources")
    @ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
    static class ResourceController {

        private final ResourceService svc;

        ResourceController(ResourceService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.RESOURCE__READ)
        public List<Map<String, Object>> list(@RequestParam(required = false) String kind) {
            return svc.list(kind);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.RESOURCE__CREATE)
        public Map<String, Object> create(@Valid @RequestBody CreateResource dto) {
            return svc.create(dto);
        }

        @PatchMapping("/{id}")
        @RequirePermissions(Permissions.RESOURCE__UPDATE)
        public Map<String, Object> update(@PathVariable String id,
                @RequestBody(required = false) Map<String, Object> dto) {
            return svc.update(id, dto);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        @RequirePermissions(Permissions.RESOURCE__DELETE)
        public void remove(@PathVariable String id) {
            svc.remove(id);
        }
    }

    /** {@code /manufacturing/routings}. */
    @RestController
    @RequestMapping("/manufacturing/routings")
    @ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
    static class RoutingController {

        private final RoutingService svc;

        RoutingController(RoutingService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.ROUTING__READ)
        public List<Map<String, Object>> list(@RequestParam(required = false) String bomId,
                @RequestParam(required = false) String productId) {
            return svc.list(bomId, productId);
        }

        @GetMapping("/{id}")
        @RequirePermissions(Permissions.ROUTING__READ)
        public Map<String, Object> get(@PathVariable String id) {
            return svc.get(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.ROUTING__CREATE)
        public Map<String, Object> create(@Valid @RequestBody CreateRouting dto) {
            return svc.create(dto);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        @RequirePermissions(Permissions.ROUTING__DELETE)
        public void remove(@PathVariable String id) {
            svc.remove(id);
        }
    }

    /** {@code /manufacturing/production-orders/:orderId/work-orders}. */
    @RestController
    @RequestMapping("/manufacturing/production-orders/{orderId}/work-orders")
    @ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
    static class WorkOrderController {

        private final WorkOrderService svc;

        WorkOrderController(WorkOrderService svc) {
            this.svc = svc;
        }

        @GetMapping
        @RequirePermissions(Permissions.WORK_ORDER__READ)
        public List<Map<String, Object>> list(@PathVariable String orderId) {
            return svc.list(orderId);
        }

        @PostMapping("/generate")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
        public List<Map<String, Object>> generate(@PathVariable String orderId) {
            return svc.generateForOrder(orderId);
        }
    }

    /** {@code /manufacturing/work-orders}. */
    @RestController
    @RequestMapping("/manufacturing/work-orders")
    @ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
    static class WorkOrderActionController {

        private final WorkOrderService svc;

        WorkOrderActionController(WorkOrderService svc) {
            this.svc = svc;
        }

        @PatchMapping("/{id}/assign")
        @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
        public Map<String, Object> assign(@PathVariable String id,
                @Valid @RequestBody(required = false) AssignWorkOrder dto) {
            return svc.assign(id, dto != null ? dto : new AssignWorkOrder(null, null, null));
        }

        @PostMapping("/{id}/start")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
        public Map<String, Object> start(@PathVariable String id) {
            return svc.start(id);
        }

        @PostMapping("/{id}/pause")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
        public Map<String, Object> pause(@PathVariable String id) {
            return svc.pause(id);
        }

        @PostMapping("/{id}/complete")
        @ResponseStatus(HttpStatus.CREATED)
        @RequirePermissions(Permissions.WORK_ORDER__MANAGE)
        public Map<String, Object> complete(@PathVariable String id,
                @Valid @RequestBody(required = false) CompleteWorkOrder dto) {
            return svc.complete(id, dto != null ? dto : new CompleteWorkOrder(null, null, null, null, null));
        }
    }
}

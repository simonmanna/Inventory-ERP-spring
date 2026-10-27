package com.inventoryerp.repair.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.repair.application.RepairCatalogService;
import com.inventoryerp.repair.application.RepairContractService;
import com.inventoryerp.repair.application.RepairDiagnosisService;
import com.inventoryerp.repair.application.RepairJobService;
import com.inventoryerp.repair.application.RepairPartsService;
import com.inventoryerp.repair.application.RepairPostingService;
import com.inventoryerp.repair.application.RepairReportsService;
import com.inventoryerp.repair.application.RepairService;
import com.inventoryerp.repair.application.RepairWarrantyService;
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
 * {@code /repair} — the RMMS surface (port of {@code repair.controller.ts}): orders → items → diagnosis → quotation →
 * jobs → parts → bill → deliver → close · technicians · labour catalog · warranties + claims · contracts + schedules ·
 * reports. Bodies and queries are untyped ({@code dto: any}) in Nest, so they bind as maps.
 */
@RestController
@RequestMapping("/repair")
@RequiresModule("repair")
@ConditionalOnProperty(name = "app.modules.repair", havingValue = "true")
public class RepairController {

    private final RepairService service;
    private final RepairCatalogService catalog;
    private final RepairDiagnosisService diagnosis;
    private final RepairJobService jobs;
    private final RepairPartsService parts;
    private final RepairWarrantyService warranties;
    private final RepairContractService contracts;
    private final RepairReportsService reports;
    private final RepairPostingService posting;

    public RepairController(RepairService service, RepairCatalogService catalog, RepairDiagnosisService diagnosis,
            RepairJobService jobs, RepairPartsService parts, RepairWarrantyService warranties,
            RepairContractService contracts, RepairReportsService reports, RepairPostingService posting) {
        this.service = service;
        this.catalog = catalog;
        this.diagnosis = diagnosis;
        this.jobs = jobs;
        this.parts = parts;
        this.warranties = warranties;
        this.contracts = contracts;
        this.reports = reports;
        this.posting = posting;
    }

    // ─── dashboard / reports ───

    @GetMapping("/dashboard")
    @RequirePermissions(Permissions.REPAIR__READ)
    public Map<String, Object> dashboard() {
        return reports.dashboard();
    }

    @GetMapping("/reports/operational")
    @RequirePermissions(Permissions.REPAIR__REPORT)
    public Map<String, Object> operationalReport(@RequestParam Map<String, String> q) {
        return reports.operationalReport(q.get("from"), q.get("to"));
    }

    @GetMapping("/reports/revenue")
    @RequirePermissions(Permissions.REPAIR__REPORT)
    public List<Map<String, Object>> revenueByMonth(@RequestParam Map<String, String> q) {
        return reports.revenueByMonth(q.get("from"), q.get("to"));
    }

    @GetMapping("/reports/technician-load")
    @RequirePermissions(Permissions.REPAIR__REPORT)
    public List<Map<String, Object>> technicianLoad() {
        return reports.technicianLoad();
    }

    @GetMapping("/reports/warranty-health")
    @RequirePermissions(Permissions.REPAIR__REPORT)
    public Map<String, Object> warrantyHealth() {
        return reports.warrantyHealth();
    }

    @GetMapping("/reports/contracts")
    @RequirePermissions(Permissions.REPAIR__REPORT)
    public Map<String, Object> contractsOverview() {
        return reports.contractsOverview();
    }

    // ─── orders ───

    @GetMapping("/orders")
    @RequirePermissions(Permissions.REPAIR__READ)
    public Map<String, Object> listOrders(@RequestParam Map<String, String> q) {
        return service.listOrders(q);
    }

    @GetMapping("/orders/{id}")
    @RequirePermissions(Permissions.REPAIR__READ)
    public Map<String, Object> getOrder(@PathVariable String id) {
        return service.getOrder(id);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__CREATE)
    public Map<String, Object> createOrder(@RequestBody(required = false) Map<String, Object> dto) {
        return service.createOrder(dto);
    }

    @PostMapping("/orders/{id}/transition")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__MANAGE)
    public Map<String, Object> transitionOrder(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return service.transitionOrder(id, dto);
    }

    @PatchMapping("/orders/{id}/technician")
    @RequirePermissions(Permissions.REPAIR__ASSIGN)
    public Map<String, Object> assignTechnician(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return service.assignTechnician(id, dto);
    }

    @PostMapping("/orders/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__MANAGE)
    public Map<String, Object> addItem(@PathVariable String id, @RequestBody(required = false) Map<String, Object> dto) {
        return service.addItem(id, dto);
    }

    @DeleteMapping("/orders/items/{itemId}")
    @RequirePermissions(Permissions.REPAIR__MANAGE)
    public Map<String, Object> removeItem(@PathVariable String itemId) {
        return service.removeItem(itemId);
    }

    @PostMapping("/orders/{id}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__MANAGE)
    public Map<String, Object> addAttachment(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return service.addAttachment(id, dto);
    }

    @DeleteMapping("/orders/attachments/{attachmentId}")
    @RequirePermissions(Permissions.REPAIR__MANAGE)
    public Map<String, Object> removeAttachment(@PathVariable String attachmentId) {
        return service.removeAttachment(attachmentId);
    }

    @PostMapping("/orders/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__READ)
    public Map<String, Object> addNote(@PathVariable String id, @RequestBody(required = false) Map<String, Object> dto) {
        return service.addNote(id, dto);
    }

    // ─── billing ───

    @PostMapping("/orders/{id}/bill")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__MANAGE)
    public Map<String, Object> bill(@PathVariable String id, @RequestBody(required = false) Map<String, Object> dto) {
        return posting.billRepair(id, dto);
    }

    @PostMapping("/invoices/{invoiceId}/pay")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__MANAGE)
    public Map<String, Object> payInvoice(@PathVariable String invoiceId,
            @RequestBody(required = false) Map<String, Object> dto) {
        return posting.receivePayment(invoiceId, dto);
    }

    // ─── diagnosis ───

    @PostMapping("/orders/{id}/diagnosis")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__DIAGNOSE)
    public Map<String, Object> createDiagnosis(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return diagnosis.createDiagnosis(id, dto);
    }

    @PatchMapping("/diagnosis/{id}")
    @RequirePermissions(Permissions.REPAIR__DIAGNOSE)
    public Map<String, Object> updateDiagnosis(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return diagnosis.updateDiagnosis(id, dto);
    }

    // ─── quotations ───

    @GetMapping("/quotations")
    @RequirePermissions(Permissions.REPAIR__QUOTE)
    public List<Map<String, Object>> listQuotations(@RequestParam Map<String, String> q) {
        return diagnosis.listQuotations(q);
    }

    @GetMapping("/quotations/{id}")
    @RequirePermissions(Permissions.REPAIR__QUOTE)
    public Map<String, Object> getQuotation(@PathVariable String id) {
        return diagnosis.getQuotation(id);
    }

    @PostMapping("/orders/{id}/quotations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__QUOTE)
    public Map<String, Object> createQuotation(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return diagnosis.createQuotation(id, dto);
    }

    @PostMapping("/quotations/{id}/submit")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__QUOTE)
    public Map<String, Object> submitQuotation(@PathVariable String id) {
        return diagnosis.submitQuotation(id);
    }

    @PostMapping("/quotations/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__APPROVE)
    public Map<String, Object> approveQuotation(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return diagnosis.approveQuotation(id, dto);
    }

    @PostMapping("/quotations/{id}/reject")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__QUOTE)
    public Map<String, Object> rejectQuotation(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return diagnosis.rejectQuotation(id, dto);
    }

    @PostMapping("/quotations/{id}/revise")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__QUOTE)
    public Map<String, Object> reviseQuotation(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return diagnosis.reviseQuotation(id, dto);
    }

    @DeleteMapping("/quotations/{id}")
    @RequirePermissions(Permissions.REPAIR__QUOTE)
    public Map<String, Object> deleteQuotation(@PathVariable String id) {
        return diagnosis.deleteQuotation(id);
    }

    // ─── jobs ───

    @GetMapping("/jobs")
    @RequirePermissions(Permissions.REPAIR__READ)
    public List<Map<String, Object>> listJobs(@RequestParam Map<String, String> q) {
        return jobs.listJobs(q);
    }

    @GetMapping("/jobs/{id}")
    @RequirePermissions(Permissions.REPAIR__READ)
    public Map<String, Object> getJob(@PathVariable String id) {
        return jobs.getJob(id);
    }

    @PostMapping("/orders/{id}/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__WORK)
    public Map<String, Object> createJob(@PathVariable String id, @RequestBody(required = false) Map<String, Object> dto) {
        return jobs.createJob(id, dto);
    }

    @PatchMapping("/jobs/{id}")
    @RequirePermissions(Permissions.REPAIR__WORK)
    public Map<String, Object> updateJob(@PathVariable String id, @RequestBody(required = false) Map<String, Object> dto) {
        return jobs.updateJob(id, dto);
    }

    @PostMapping("/jobs/{id}/start")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__WORK)
    public Map<String, Object> startJob(@PathVariable String id) {
        return jobs.startJob(id);
    }

    @PostMapping("/jobs/{id}/complete")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__WORK)
    public Map<String, Object> completeJob(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return jobs.completeJob(id, dto);
    }

    @PostMapping("/jobs/{id}/test")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__TEST)
    public Map<String, Object> testJob(@PathVariable String id) {
        return jobs.testJob(id);
    }

    @PostMapping("/jobs/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__WORK)
    public Map<String, Object> cancelJob(@PathVariable String id, @RequestBody(required = false) Map<String, Object> dto) {
        return jobs.cancelJob(id, dto);
    }

    @PostMapping("/jobs/{id}/checklist")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__WORK)
    public Map<String, Object> setChecklistItem(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return jobs.setChecklistItem(id, dto);
    }

    // ─── parts ───

    @GetMapping("/parts")
    @RequirePermissions(Permissions.REPAIR__PARTS)
    public List<Map<String, Object>> listParts(@RequestParam Map<String, String> q) {
        return parts.listParts(q);
    }

    @PostMapping("/orders/{id}/parts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__PARTS)
    public Map<String, Object> reservePart(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return parts.reservePart(id, dto);
    }

    @PostMapping("/parts/{id}/issue")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__PARTS)
    public Map<String, Object> issuePart(@PathVariable String id, @RequestBody(required = false) Map<String, Object> dto) {
        return parts.issuePart(id, dto);
    }

    @DeleteMapping("/parts/{id}")
    @RequirePermissions(Permissions.REPAIR__PARTS)
    public Map<String, Object> deletePart(@PathVariable String id) {
        return parts.deletePart(id);
    }

    // ─── labour catalog ───

    @GetMapping("/labour-types")
    @RequirePermissions(Permissions.REPAIR__READ)
    public List<Map<String, Object>> listLabourTypes(@RequestParam Map<String, String> q) {
        return catalog.listLabourTypes(q);
    }

    @PostMapping("/labour-types")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__MANAGE)
    public Map<String, Object> createLabourType(@RequestBody(required = false) Map<String, Object> dto) {
        return catalog.createLabourType(dto);
    }

    @PatchMapping("/labour-types/{id}")
    @RequirePermissions(Permissions.REPAIR__MANAGE)
    public Map<String, Object> updateLabourType(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return catalog.updateLabourType(id, dto);
    }

    @DeleteMapping("/labour-types/{id}")
    @RequirePermissions(Permissions.REPAIR__MANAGE)
    public Map<String, Object> deleteLabourType(@PathVariable String id) {
        return catalog.deleteLabourType(id);
    }

    // ─── technicians ───

    @GetMapping("/technicians")
    @RequirePermissions(Permissions.REPAIR__READ)
    public List<Map<String, Object>> listTechnicians(@RequestParam Map<String, String> q) {
        return catalog.listTechnicians(q);
    }

    @PostMapping("/technicians")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__MANAGE)
    public Map<String, Object> createTechnician(@RequestBody(required = false) Map<String, Object> dto) {
        return catalog.createTechnician(dto);
    }

    @PatchMapping("/technicians/{id}")
    @RequirePermissions(Permissions.REPAIR__MANAGE)
    public Map<String, Object> updateTechnician(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return catalog.updateTechnician(id, dto);
    }

    @DeleteMapping("/technicians/{id}")
    @RequirePermissions(Permissions.REPAIR__MANAGE)
    public Map<String, Object> deleteTechnician(@PathVariable String id) {
        return catalog.deleteTechnician(id);
    }

    // ─── warranties ───

    @GetMapping("/warranties")
    @RequirePermissions(Permissions.REPAIR__WARRANTY)
    public List<Map<String, Object>> listWarranties(@RequestParam Map<String, String> q) {
        return warranties.listWarranties(q);
    }

    @PostMapping("/warranties")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__WARRANTY)
    public Map<String, Object> createWarranty(@RequestBody(required = false) Map<String, Object> dto) {
        return warranties.createWarranty(dto);
    }

    @PatchMapping("/warranties/{id}")
    @RequirePermissions(Permissions.REPAIR__WARRANTY)
    public Map<String, Object> updateWarranty(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return warranties.updateWarranty(id, dto);
    }

    @PostMapping("/warranties/{id}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__WARRANTY)
    public Map<String, Object> createClaim(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return warranties.createClaim(id, dto);
    }

    @PatchMapping("/claims/{claimId}")
    @RequirePermissions(Permissions.REPAIR__WARRANTY)
    public Map<String, Object> resolveClaim(@PathVariable String claimId,
            @RequestBody(required = false) Map<String, Object> dto) {
        return warranties.resolveClaim(claimId, dto);
    }

    // ─── service contracts ───

    @GetMapping("/contracts")
    @RequirePermissions(Permissions.REPAIR__CONTRACT)
    public List<Map<String, Object>> listContracts(@RequestParam Map<String, String> q) {
        return contracts.listContracts(q);
    }

    @PostMapping("/contracts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__CONTRACT)
    public Map<String, Object> createContract(@RequestBody(required = false) Map<String, Object> dto) {
        return contracts.createContract(dto);
    }

    @PatchMapping("/contracts/{id}")
    @RequirePermissions(Permissions.REPAIR__CONTRACT)
    public Map<String, Object> updateContract(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return contracts.updateContract(id, dto);
    }

    @PostMapping("/contracts/{id}/activate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__CONTRACT)
    public Map<String, Object> activateContract(@PathVariable String id) {
        return contracts.activateContract(id);
    }

    @PostMapping("/contracts/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__CONTRACT)
    public Map<String, Object> cancelContract(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return contracts.cancelContract(id, dto);
    }

    @DeleteMapping("/contracts/{id}")
    @RequirePermissions(Permissions.REPAIR__CONTRACT)
    public Map<String, Object> deleteContract(@PathVariable String id) {
        return contracts.deleteContract(id);
    }

    // ─── preventive schedules ───

    @GetMapping("/schedules")
    @RequirePermissions(Permissions.REPAIR__SCHEDULE)
    public List<Map<String, Object>> listSchedules(@RequestParam Map<String, String> q) {
        return contracts.listSchedules(q);
    }

    @PostMapping("/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.REPAIR__SCHEDULE)
    public Map<String, Object> createSchedule(@RequestBody(required = false) Map<String, Object> dto) {
        return contracts.createSchedule(dto);
    }

    @PatchMapping("/schedules/{id}")
    @RequirePermissions(Permissions.REPAIR__SCHEDULE)
    public Map<String, Object> updateSchedule(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return contracts.updateSchedule(id, dto);
    }

    @DeleteMapping("/schedules/{id}")
    @RequirePermissions(Permissions.REPAIR__SCHEDULE)
    public Map<String, Object> deleteSchedule(@PathVariable String id) {
        return contracts.deleteSchedule(id);
    }
}

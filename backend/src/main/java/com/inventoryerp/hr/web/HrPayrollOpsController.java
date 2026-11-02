package com.inventoryerp.hr.web;

import com.inventoryerp.hr.application.HrPayrollEngineV2;
import com.inventoryerp.hr.application.HrPayrollOpsService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payroll engine v2 reads, one-off inputs, statutory schedules and remittances, net-pay settlement, bank / WPS files,
 * payslip PDFs and e-mail, employee payment details (HP-2, HP-3, HP-7).
 */
@RestController
@RequestMapping("/hr")
@RequiresModule("hr")
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrPayrollOpsController {

    private final HrPayrollOpsService ops;
    private final HrPayrollEngineV2 engine;

    public HrPayrollOpsController(HrPayrollOpsService ops, HrPayrollEngineV2 engine) {
        this.ops = ops;
        this.engine = engine;
    }

    static ResponseEntity<byte[]> file(HrPayrollOpsService.FileOut f) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.filename()).build().toString())
                .contentType(MediaType.parseMediaType(f.contentType())).body(f.content());
    }

    @GetMapping("/payroll/runs/{id}/lines")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> lines(@PathVariable String id, @RequestParam(required = false) String employeeId) {
        return engine.resultLines(id, employeeId);
    }

    @GetMapping("/payroll/runs/{id}/variance")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> variance(@PathVariable String id) {
        return engine.variance(id);
    }

    @GetMapping("/payroll/inputs")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> listInputs(@RequestParam Map<String, String> query) {
        return ops.listInputs(query);
    }

    @PostMapping("/payroll/inputs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> addInput(@RequestBody Map<String, Object> dto) {
        return ops.addInput(dto);
    }

    @DeleteMapping("/payroll/inputs/{id}")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> cancelInput(@PathVariable String id) {
        return ops.cancelInput(id, Map.of("reason", "cancelled by HR"));
    }

    @GetMapping("/payroll/statutory-rates")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public List<Map<String, Object>> statutoryRates(@RequestParam(required = false) String pack) {
        return ops.statutoryRates(pack);
    }

    @GetMapping("/payroll/remittances")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> remittances(@RequestParam(required = false) String periodId) {
        return ops.remittances(periodId);
    }

    @PostMapping("/payroll/remittances/pay")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> payRemittance(@RequestBody Map<String, Object> dto) {
        return ops.payRemittance(dto);
    }

    @PostMapping("/payroll/runs/{id}/settle")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYROLL_SETTINGS)
    public Map<String, Object> settle(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return ops.settleRun(id, dto);
    }

    @GetMapping("/payroll/payments")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public Map<String, Object> payments(@RequestParam Map<String, String> query) {
        return ops.payments(query);
    }

    @GetMapping("/payroll/runs/{id}/bank-file")
    @RequirePermissions(Permissions.HR__PAYROLL)
    public ResponseEntity<byte[]> bankFile(@PathVariable String id, @RequestParam(required = false) String format) {
        return file(ops.bankFile(id, format));
    }

    @PostMapping("/payroll/runs/{id}/payslips/email")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__PAYSLIP)
    public Map<String, Object> emailPayslips(@PathVariable String id) {
        return ops.emailPayslips(id);
    }

    @GetMapping("/payslips/{id}/pdf")
    @RequirePermissions(Permissions.HR__PAYSLIP)
    public ResponseEntity<byte[]> payslipPdf(@PathVariable String id) {
        return file(ops.payslipPdf(id, null));
    }

    @GetMapping("/employees/{id}/pay-info")
    @RequirePermissions(Permissions.HR__COMPENSATION)
    public Map<String, Object> payInfo(@PathVariable String id) {
        return ops.payInfo(id);
    }

    @PutMapping("/employees/{id}/pay-info")
    @RequirePermissions(Permissions.HR__COMPENSATION)
    public Map<String, Object> savePayInfo(@PathVariable String id, @RequestBody Map<String, Object> dto) {
        return ops.savePayInfo(id, dto);
    }

    @GetMapping("/employees/{id}/earnings/{year}")
    @RequirePermissions(Permissions.HR__COMPENSATION)
    public Map<String, Object> annualSummary(@PathVariable String id, @PathVariable int year) {
        return ops.annualSummary(id, year);
    }

    @GetMapping("/employees/{id}/earnings/{year}/pdf")
    @RequirePermissions(Permissions.HR__COMPENSATION)
    public ResponseEntity<byte[]> annualCertificate(@PathVariable String id, @PathVariable int year) {
        return file(ops.annualCertificate(id, year));
    }
}

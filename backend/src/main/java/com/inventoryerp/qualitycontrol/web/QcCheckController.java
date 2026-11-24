package com.inventoryerp.qualitycontrol.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.qualitycontrol.application.QcCheckService;
import com.inventoryerp.qualitycontrol.application.QcCommands.CheckQuery;
import com.inventoryerp.qualitycontrol.application.QcCommands.CorrectionInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.ExecuteInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.ManualCheckInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.ReasonInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.WaiveInput;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Quality checks (ADR-S087 §2): overview, list / detail, manual checks, execution, waiver, reset, corrections. */
@RestController
@RequestMapping("/quality-control")
@Validated
public class QcCheckController {

    private final QcCheckService checks;

    public QcCheckController(QcCheckService checks) {
        this.checks = checks;
    }

    @GetMapping("/overview")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public Map<String, Object> overview() {
        return checks.overview();
    }

    @GetMapping("/checks")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) @OneOf({"pending", "passed", "failed", "waived", "cancelled"}) String status,
            @RequestParam(required = false) @OneOf({"generated", "manual", "correction"}) String origin,
            @RequestParam(required = false) @OneOf({"instructions", "pass_fail", "measure", "picture", "worksheet"})
            String checkType,
            @RequestParam(required = false) @OneOf({"production_order", "work_order", "goods_receipt", "sales_delivery",
                    "manual"}) String sourceType,
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String controlPointId,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String teamId,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return checks.list(new CheckQuery(search, status, origin, checkType, sourceType, sourceId, controlPointId,
                productId, teamId, page, pageSize));
    }

    @GetMapping("/checks/{id}")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return checks.get(id);
    }

    @PostMapping("/checks")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY_CONTROL__CHECK)
    public Map<String, Object> create(@Valid @RequestBody ManualCheckInput dto) {
        return checks.createManual(dto);
    }

    /** Dry run: the evaluation a submission would get, nothing saved. */
    @PostMapping("/checks/{id}/evaluate")
    @RequirePermissions(Permissions.QUALITY_CONTROL__CHECK)
    public Map<String, Object> evaluate(@PathVariable String id, @Valid @RequestBody ExecuteInput dto) {
        return checks.evaluate(id, dto);
    }

    @PostMapping("/checks/{id}/execute")
    @RequirePermissions(Permissions.QUALITY_CONTROL__CHECK)
    public Map<String, Object> execute(@PathVariable String id, @Valid @RequestBody ExecuteInput dto) {
        return checks.execute(id, dto);
    }

    @PostMapping("/checks/{id}/waive")
    @RequirePermissions(Permissions.QUALITY_CONTROL__MANAGE)
    public Map<String, Object> waive(@PathVariable String id, @Valid @RequestBody WaiveInput dto) {
        return checks.waive(id, dto);
    }

    @PostMapping("/checks/{id}/reset")
    @RequirePermissions(Permissions.QUALITY_CONTROL__MANAGE)
    public Map<String, Object> reset(@PathVariable String id, @Valid @RequestBody ReasonInput dto) {
        return checks.reset(id, dto);
    }

    @PostMapping("/checks/{id}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.QUALITY_CONTROL__MANAGE)
    public Map<String, Object> correction(@PathVariable String id, @Valid @RequestBody CorrectionInput dto) {
        return checks.openCorrection(id, dto);
    }

    @PostMapping("/checks/{id}/cancel")
    @RequirePermissions(Permissions.QUALITY_CONTROL__MANAGE)
    public Map<String, Object> cancel(@PathVariable String id, @Valid @RequestBody ReasonInput dto) {
        return checks.cancel(id, dto);
    }
}

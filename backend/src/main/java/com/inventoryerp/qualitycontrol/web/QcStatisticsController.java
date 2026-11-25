package com.inventoryerp.qualitycontrol.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.qualitycontrol.application.QcStatisticsService;
import com.inventoryerp.qualitycontrol.application.QcStatisticsService.SpcQuery;
import com.inventoryerp.qualitycontrol.application.QcStatisticsService.StatsQuery;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Quality-control statistics and SPC (ADR-S087 §12, QC4). */
@RestController
@RequestMapping("/quality-control/statistics")
@Validated
public class QcStatisticsController {

    private final QcStatisticsService stats;

    public QcStatisticsController(QcStatisticsService stats) {
        this.stats = stats;
    }

    @GetMapping("/summary")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public Map<String, Object> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String controlPointId, @RequestParam(required = false) String productId,
            @RequestParam(required = false) String workCenterId, @RequestParam(required = false) String teamId,
            @RequestParam(required = false) @OneOf({"day", "week", "month"}) String interval) {
        return stats.summary(new StatsQuery(from, to, controlPointId, productId, workCenterId, teamId), interval);
    }

    @GetMapping("/pareto")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public Map<String, Object> pareto(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String controlPointId, @RequestParam(required = false) String productId,
            @RequestParam(required = false) String workCenterId, @RequestParam(required = false) String teamId,
            @RequestParam(required = false) @OneOf({"failure_class", "control_point", "product", "work_center", "team"})
                    String dimension) {
        return stats.pareto(new StatsQuery(from, to, controlPointId, productId, workCenterId, teamId), dimension);
    }

    @GetMapping("/spc")
    @RequirePermissions(Permissions.QUALITY_CONTROL__READ)
    public Map<String, Object> spc(@RequestParam String controlPointId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String workCenterId,
            @RequestParam(required = false) @Min(2) @Max(1000) Integer baseline,
            @RequestParam(required = false) @Pattern(regexp = "^[1-4](,[1-4]){0,3}$") String rules,
            @RequestParam(required = false) @Min(0) Integer segment) {
        return stats.spc(new SpcQuery(controlPointId, from, to, productId, workCenterId, baseline, rules, segment));
    }
}

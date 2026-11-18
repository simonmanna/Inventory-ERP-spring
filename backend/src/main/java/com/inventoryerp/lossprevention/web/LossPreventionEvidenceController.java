package com.inventoryerp.lossprevention.web;

import com.inventoryerp.kernel.licensing.LicenseFeatures;
import com.inventoryerp.kernel.licensing.RequiresFeature;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.lossprevention.application.LpEvidenceService;
import com.inventoryerp.lossprevention.application.LpVisionCommands.EvidenceQuery;
import com.inventoryerp.lossprevention.application.LpVisionCommands.EvidenceRangeInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.EvidenceRequestInput;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /loss-prevention/evidence} (LP1, ADR-S077 §8): clips for investigators. Every view, download, export and
 * request is recorded; a clip linked to a case about the caller is not found.
 */
@RestController
@RequestMapping("/loss-prevention")
@RequiresModule("loss-prevention")
@RequiresFeature(LicenseFeatures.LP_VISION)
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LossPreventionEvidenceController {

    private final LpEvidenceService evidence;

    public LossPreventionEvidenceController(LpEvidenceService evidence) {
        this.evidence = evidence;
    }

    @GetMapping("/evidence")
    @RequirePermissions(Permissions.LOSS_PREVENTION__EVIDENCE)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String caseId,
            @RequestParam(required = false) String cameraId, @RequestParam(required = false) String zoneId,
            @RequestParam(required = false) String state, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return evidence.list(new EvidenceQuery(caseId, cameraId, zoneId, state, from, to, page, pageSize));
    }

    @GetMapping("/evidence/{id}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__EVIDENCE)
    public Map<String, Object> get(@PathVariable String id) {
        return evidence.get(id);
    }

    @GetMapping("/evidence/{id}/content")
    @RequirePermissions(Permissions.LOSS_PREVENTION__EVIDENCE)
    public ResponseEntity<byte[]> content(@PathVariable String id,
            @RequestParam(required = false) Boolean download) {
        boolean attachment = Boolean.TRUE.equals(download);
        LpEvidenceService.Content c = evidence.content(id, attachment);
        return file(c, attachment);
    }

    @GetMapping("/evidence/{id}/export")
    @RequirePermissions(Permissions.LOSS_PREVENTION__EVIDENCE)
    public ResponseEntity<byte[]> export(@PathVariable String id) {
        return file(evidence.export(id), true);
    }

    @PostMapping("/evidence/{id}/request")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__EVIDENCE)
    public Map<String, Object> request(@PathVariable String id,
            @Valid @RequestBody(required = false) EvidenceRequestInput dto) {
        return evidence.request(id, dto == null ? null : dto.caseId(), dto == null ? null : dto.reason());
    }

    @PostMapping("/evidence/range")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__EVIDENCE)
    public Map<String, Object> range(@Valid @RequestBody EvidenceRangeInput dto) {
        return evidence.range(dto);
    }

    private static ResponseEntity<byte[]> file(LpEvidenceService.Content c, boolean attachment) {
        ContentDisposition disposition = (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(c.filename()).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(c.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore()).body(c.bytes());
    }
}

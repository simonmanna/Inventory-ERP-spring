package com.inventoryerp.kernel.sequence;

import com.inventoryerp.kernel.approvals.ControlledConfigService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /document-numbering} (CTRL-2). A write answers {@code {pendingApproval: true, changeId, requestId}} instead of
 * the row when the organization controls configuration changes.
 */
@RestController
@RequestMapping("/document-numbering")
public class DocumentNumberingController {

    /** Format of one sequence. */
    public record FormatRequest(
            String prefix,
            String suffix,
            @NotNull(message = "padding must be an integer number")
            @Min(value = 1, message = "padding must not be less than 1")
            @Max(value = 12, message = "padding must not be greater than 12") Integer padding) {}

    /** The number the next document should get. */
    public record AdvanceRequest(
            @NotNull(message = "nextNumber must be an integer number")
            @Min(value = 1, message = "nextNumber must not be less than 1") Long nextNumber) {}

    private final DocumentNumberingService numbering;
    private final ControlledConfigService changes;

    public DocumentNumberingController(DocumentNumberingService numbering, ControlledConfigService changes) {
        this.numbering = numbering;
        this.changes = changes;
    }

    @GetMapping
    @RequirePermissions(Permissions.DOCUMENT_NUMBERING__READ)
    public List<Map<String, Object>> list() {
        return numbering.list();
    }

    @PutMapping("/{key}")
    @RequirePermissions(Permissions.DOCUMENT_NUMBERING__MANAGE)
    public Map<String, Object> set(@PathVariable String key, @Valid @RequestBody FormatRequest dto) {
        numbering.assertValid(key, dto.prefix(), dto.suffix());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prefix", dto.prefix());
        payload.put("suffix", dto.suffix());
        payload.put("padding", dto.padding());
        Map<String, Object> held = changes.holdIfControlled(DocumentNumberingService.SET, key, payload);
        return held != null ? held : numbering.set(key, dto.prefix(), dto.suffix(), dto.padding());
    }

    @DeleteMapping("/{key}")
    @RequirePermissions(Permissions.DOCUMENT_NUMBERING__MANAGE)
    public Map<String, Object> reset(@PathVariable String key) {
        numbering.assertKnown(key);
        Map<String, Object> held = changes.holdIfControlled(DocumentNumberingService.RESET, key, Map.of());
        return held != null ? held : numbering.reset(key);
    }

    @PostMapping("/{key}/advance")
    @RequirePermissions(Permissions.DOCUMENT_NUMBERING__MANAGE)
    public Map<String, Object> advance(@PathVariable String key, @Valid @RequestBody AdvanceRequest dto) {
        numbering.assertForward(key, dto.nextNumber());
        Map<String, Object> held = changes.holdIfControlled(DocumentNumberingService.ADVANCE, key,
                Map.of("nextNumber", dto.nextNumber()));
        return held != null ? held : numbering.advance(key, dto.nextNumber());
    }
}

package com.inventoryerp.dms.web;

import com.inventoryerp.dms.application.DmsRequestService;
import com.inventoryerp.dms.application.DmsRequestService.RequestInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** {@code /dms/requests}: ask a colleague for a document; fulfil by uploading. */
@RestController
@RequestMapping("/dms/requests")
public class DmsRequestController {

    public record CreateRequest(
            @NotBlank(message = "title should not be empty")
            @Size(max = 200, message = "title must be at most 200 characters") String title,
            @Size(max = 2000, message = "note must be at most 2000 characters") String note,
            @NotBlank(message = "requestedFromUserId should not be empty") String requestedFromUserId,
            String workspaceId,
            String resType,
            String resId,
            @Pattern(regexp = DmsDocumentController.ISO_DATE, message = "dueOn must be a valid ISO 8601 date string")
            String dueOn) {}

    private final DmsRequestService requests;

    public DmsRequestController(DmsRequestService requests) {
        this.requests = requests;
    }

    @GetMapping
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String scope,
            @RequestParam(required = false) String status) {
        return requests.list(DmsParts.blank(scope), DmsParts.blank(status));
    }

    @PostMapping
    @RequirePermissions(Permissions.DMS__REQUEST_CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateRequest r) {
        return requests.create(new RequestInput(r.title(), r.note(), r.requestedFromUserId(),
                DmsParts.blank(r.workspaceId()), DmsParts.blank(r.resType()), DmsParts.blank(r.resId()),
                DmsParts.date(r.dueOn(), "dueOn")));
    }

    @PostMapping("/{id}/fulfil")
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    public Map<String, Object> fulfil(@PathVariable String id,
            @RequestParam(value = "file", required = false) List<MultipartFile> file,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        return requests.fulfil(id, DmsParts.parts(file, files));
    }

    @PostMapping("/{id}/cancel")
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    public Map<String, Object> cancel(@PathVariable String id) {
        return requests.cancel(id);
    }
}

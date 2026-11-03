package com.inventoryerp.dms.web;

import com.inventoryerp.dms.application.DmsDocumentService;
import com.inventoryerp.dms.application.DmsDocumentService.UploadInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code /dms/records/{resType}/{resId}/attachments}: the attachment panel of any record (sales order, purchase
 * order, expense, invoice, CRM opportunity ...). Needs DMS read / write plus the record type's own permission.
 */
@RestController
@RequestMapping("/dms/records/{resType}/{resId}")
public class DmsRecordController {

    private final DmsDocumentService documents;

    public DmsRecordController(DmsDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/attachments")
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    public List<Map<String, Object>> list(@PathVariable String resType, @PathVariable String resId) {
        return documents.recordAttachments(resType, resId);
    }

    @GetMapping("/count")
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    public Map<String, Object> count(@PathVariable String resType, @PathVariable String resId) {
        return documents.recordCount(resType, resId);
    }

    @PostMapping("/attachments")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<Map<String, Object>> upload(@PathVariable String resType, @PathVariable String resId,
            @RequestParam(value = "file", required = false) List<MultipartFile> file,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(required = false) List<String> tags, @RequestParam(required = false) String description,
            @RequestParam(required = false) String expiresOn) {
        return documents.upload(new UploadInput(null, null, resType, resId, DmsParts.list(tags), description,
                DmsParts.date(expiresOn, "expiresOn"), null, null), DmsParts.parts(file, files));
    }
}

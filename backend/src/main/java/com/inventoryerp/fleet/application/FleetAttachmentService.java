package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.domain.FleetComplianceDocument;
import com.inventoryerp.fleet.domain.FleetVehicle;
import com.inventoryerp.fleet.domain.FleetVehicleAttachment;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vehicle photos and documents (1.1), stored in the platform file store ({@code kernel.files}, owner type
 * {@value #OWNER_TYPE}) — the same store DMS attachments use — with a fleet link row carrying the kind, title and
 * optional compliance document. Downloads use the file store's signed URLs.
 */
@Service
public class FleetAttachmentService {

    public static final String OWNER_TYPE = "fleet_vehicle";

    private static final String SELECT = "SELECT a.*, f.filename, f.\"contentType\", f.\"byteSize\" FROM"
            + " \"FleetVehicleAttachment\" a JOIN \"File\" f ON f.id = a.\"fileId\" AND f.\"deletedAt\" IS NULL";

    private final FleetDb db;
    private final FilesService files;
    private final AuditService audit;

    public FleetAttachmentService(FleetDb db, FilesService files, AuditService audit) {
        this.db = db;
        this.files = files;
        this.audit = audit;
    }

    /** Attachments of a vehicle, newest first, each with file metadata and a signed download {@code url}. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String vehicleId) {
        db.require(FleetVehicle.class, vehicleId, false, "Vehicle not found");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(SELECT + " WHERE a.\"organizationId\" = ? AND"
                + " a.\"vehicleId\" = ? ORDER BY a.\"createdAt\" DESC, a.id").params(FleetDb.org(), vehicleId))) {
            out.add(withUrl(r));
        }
        return out;
    }

    /** Store an uploaded file and link it to the vehicle (and optionally to one of its compliance documents). */
    @Transactional
    public Map<String, Object> upload(String vehicleId, String kind, String title, String complianceDocumentId,
            String filename, String contentType, byte[] bytes) {
        db.require(FleetVehicle.class, vehicleId, false, "Vehicle not found");
        String k = kind == null || kind.isBlank() ? "document" : kind;
        if (!List.of("photo", "document").contains(k)) {
            throw ApiException.badRequest("kind must be one of the following values: photo, document");
        }
        if ("photo".equals(k) && (contentType == null || !contentType.startsWith("image/"))) {
            throw ApiException.badRequest("Photos must be image files");
        }
        String docId = complianceDocumentId == null || complianceDocumentId.isBlank() ? null : complianceDocumentId;
        if (docId != null) {
            Map<String, Object> doc = db.byId(FleetComplianceDocument.class, docId, false);
            if (doc == null || !vehicleId.equals(doc.get("vehicleId"))) {
                throw ApiException.badRequest("Compliance document not found on this vehicle");
            }
        }
        Map<String, Object> file = files.upload(filename, contentType, bytes, OWNER_TYPE, vehicleId);
        Map<String, Object> row = db.insert(FleetVehicleAttachment.class, Obj.of(
                "vehicleId", vehicleId,
                "fileId", file.get("id"),
                "kind", k,
                "complianceDocumentId", docId,
                "title", FleetVehicleService.trim(title),
                "createdBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("FleetVehicleAttachment", (String) row.get("id"), "create", null, row));
        return withUrl(SqlRows.one(db.jdbc().sql(SELECT + " WHERE a.id = ? AND a.\"organizationId\" = ?")
                .params(row.get("id"), FleetDb.org())));
    }

    /** Remove the link and the stored file. */
    @Transactional
    public void delete(String vehicleId, String attachmentId) {
        Map<String, Object> row = db.byId(FleetVehicleAttachment.class, attachmentId, true);
        if (row == null || !vehicleId.equals(row.get("vehicleId"))) {
            throw ApiException.notFound("Attachment not found");
        }
        db.jdbc().sql("DELETE FROM \"FleetVehicleAttachment\" WHERE id = ? AND \"organizationId\" = ?")
                .params(attachmentId, FleetDb.org()).update();
        files.remove((String) row.get("fileId"));
        audit.recordInTx(AuditInput.of("FleetVehicleAttachment", attachmentId, "delete", row, null));
    }

    private Map<String, Object> withUrl(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>(r);
        Map<String, Object> signed = files.signDownload((String) r.get("fileId"));
        out.put("url", signed.get("url"));
        out.put("urlExpiresAt", signed.get("expiresAt"));
        return out;
    }
}

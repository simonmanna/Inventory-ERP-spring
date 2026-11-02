package com.inventoryerp.dms.spi;

import java.util.List;
import java.util.Map;

/** Attach generated or received files to a record from another module (inside the caller's tenant + transaction). */
public interface DmsAttachments {

    /**
     * Store {@code bytes} as a new DMS document linked to {@code resType/resId}. {@code workspaceCode} overrides the
     * provider's default workspace when not null; {@code tags} are created when missing. Returns the document JSON.
     * Permission checks are the caller's job (internal API).
     */
    Map<String, Object> attach(String resType, String resId, String workspaceCode, String filename,
            String contentType, byte[] bytes, List<String> tags);

    /** Like {@link #attach} into a folder path of the workspace (e.g. {@code incoming/2026}), created when missing. */
    Map<String, Object> attachInFolder(String resType, String resId, String workspaceCode, String folderPath,
            String filename, String contentType, byte[] bytes, List<String> tags);

    /**
     * {@code {label, webPath, typeLabel}} of a record in the current organization, or {@code null} when the type is
     * unknown or the record does not exist.
     */
    Map<String, String> describe(String resType, String resId);

    /** Documents linked to the record (no permission checks). */
    List<Map<String, Object>> list(String resType, String resId);
}

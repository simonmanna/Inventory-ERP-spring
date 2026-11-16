package com.inventoryerp.sales.application;

import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.files.FilesService.FileMeta;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Product images on customer documents (quotation lines): the stored {@code Product.image} value (a bare file id, a
 * signed {@code /files/<id>/...} path or an absolute URL) as a fresh signed URL for the browser, or as bytes for a PDF.
 */
@Component
public class SalesProductImages {

    private static final Pattern FILE_ID = Pattern.compile(
            "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})", Pattern.CASE_INSENSITIVE);
    /** Larger files are skipped rather than embedded in a PDF. */
    static final long MAX_BYTES = 5L * 1024 * 1024;

    private final FilesService files;

    public SalesProductImages(FilesService files) {
        this.files = files;
    }

    /** A signed URL the browser can load, the absolute URL unchanged, or {@code null} when there is no image. */
    public String url(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (stored.startsWith("http")) {
            return stored;
        }
        Matcher m = FILE_ID.matcher(stored);
        if (!m.find()) {
            return stored;
        }
        try {
            return (String) files.signDownload(m.group(1)).get("url");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Bytes of an uploaded image file of this organization, or {@code null} (no image, external URL, too large). */
    public byte[] bytes(String stored) {
        Matcher m = FILE_ID.matcher(stored == null ? "" : stored);
        if (stored == null || stored.startsWith("http") || !m.find()) {
            return null;
        }
        try {
            FileMeta meta = files.meta(m.group(1));
            if (meta == null || meta.byteSize() > MAX_BYTES) {
                return null;
            }
            try (InputStream in = files.open(meta)) {
                return in.readNBytes((int) MAX_BYTES);
            }
        } catch (IOException | RuntimeException e) {
            return null; // the document still renders, without this picture
        }
    }
}

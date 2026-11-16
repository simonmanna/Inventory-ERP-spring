package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.files.FilesService.FileMeta;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The organization's letterhead for printed sales documents: business name and address lines from the receipt header
 * (falling back to the organization name), plus the logo uploaded in the company settings.
 */
@Component
public class SalesLetterhead {

    /** What a printed document shows at the top. {@code logo} is PNG / JPEG bytes, or {@code null}. */
    public record Head(String name, List<String> lines, byte[] logo) {}

    private static final Pattern FILE_ID = Pattern.compile("/files/([0-9a-fA-F-]{36})/");
    private static final long MAX_LOGO_BYTES = 5L * 1024 * 1024;

    private final SalesDb db;
    private final FilesService files;

    public SalesLetterhead(SalesDb db, FilesService files) {
        this.db = db;
        this.files = files;
    }

    /** Letterhead of the current organization. */
    public Head current() {
        Map<String, Object> o = db.row("SELECT name, settings->>'logoUrl' AS \"logoUrl\","
                + " \"receiptHeader\"->>'businessName' AS \"businessName\", \"receiptHeader\"->>'addressLine1' AS a1,"
                + " \"receiptHeader\"->>'addressLine2' AS a2, \"receiptHeader\"->>'phone' AS phone,"
                + " \"receiptHeader\"->>'taxId' AS \"taxId\" FROM \"Organization\" WHERE id = ?", org());
        if (o == null) {
            return new Head("", List.of(), null);
        }
        String name = blank(str(o.get("businessName"))) ? str(o.get("name")) : str(o.get("businessName"));
        List<String> lines = new ArrayList<>();
        add(lines, "", str(o.get("a1")));
        add(lines, "", str(o.get("a2")));
        add(lines, "Tel: ", str(o.get("phone")));
        add(lines, "TIN: ", str(o.get("taxId")));
        return new Head(name == null ? "" : name, List.copyOf(lines), logo(str(o.get("logoUrl"))));
    }

    /** Bytes of the organization logo (a file of this org referenced by its signed URL), or {@code null}. */
    public byte[] logo(String logoUrl) {
        Matcher m = FILE_ID.matcher(logoUrl == null ? "" : logoUrl);
        if (!m.find()) {
            return null;
        }
        try {
            FileMeta meta = files.meta(m.group(1));
            if (meta == null || meta.byteSize() > MAX_LOGO_BYTES) {
                return null;
            }
            try (InputStream in = files.open(meta)) {
                return in.readNBytes((int) MAX_LOGO_BYTES);
            }
        } catch (IOException | RuntimeException e) {
            return null; // the document still renders without a logo
        }
    }

    private static void add(List<String> lines, String prefix, String value) {
        if (!blank(value)) {
            lines.add(prefix + value.trim());
        }
    }
}

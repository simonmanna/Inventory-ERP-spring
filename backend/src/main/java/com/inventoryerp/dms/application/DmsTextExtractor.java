package com.inventoryerp.dms.application;

import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.files.FilesService.FileMeta;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Full-text extraction for DMS search (ADR-S046): plain text / CSV / JSON, PDF text layer (OpenPDF), Word and Excel
 * (POI); scanned PDFs and images go through OCR when {@code DMS_OCR_ENABLED=true} and the {@code tesseract} binary is
 * on PATH. Runs after the upload commits (bounded virtual-thread pool) and every few minutes for anything left
 * {@code pending}. Never fails an upload: errors only mark the document {@code failed}.
 */
@Component
public class DmsTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DmsTextExtractor.class);
    static final int MAX_CHARS = 1_000_000;
    static final long MAX_BYTES = 30L * 1024 * 1024;
    static final Set<String> TEXT_TYPES = Set.of("text/plain", "text/csv", "text/markdown", "application/json",
            "application/xml", "text/xml", "text/tab-separated-values");
    static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/tiff", "image/bmp", "image/webp");

    private final JdbcClient jdbc;
    private final FilesService files;
    private final boolean ocrEnabled;
    private final String ocrLanguages;
    private final boolean async;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots = new Semaphore(2);

    public DmsTextExtractor(JdbcClient jdbc, FilesService files,
            @Value("${DMS_OCR_ENABLED:false}") boolean ocrEnabled,
            @Value("${DMS_OCR_LANGUAGES:eng}") String ocrLanguages,
            @Value("${app.dms.async-extraction:true}") boolean async) {
        this.jdbc = jdbc;
        this.files = files;
        this.ocrEnabled = ocrEnabled;
        this.ocrLanguages = ocrLanguages.replaceAll("[^a-z_+]", "");
        this.async = async;
    }

    /** Queue extraction of {@code documentId} once the current transaction commits. */
    public void extractAfterCommit(String documentId) {
        if (!async) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    pool.submit(() -> runGuarded(documentId));
                }
            });
        } else {
            pool.submit(() -> runGuarded(documentId));
        }
    }

    private void runGuarded(String documentId) {
        try {
            if (slots.tryAcquire(5, TimeUnit.MINUTES)) {
                try {
                    extract(documentId);
                } finally {
                    slots.release();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("dms text extraction {} failed: {}", documentId, e.toString());
        }
    }

    /** Sweep documents still pending (missed async runs, restarts). */
    @Scheduled(fixedDelayString = "${app.dms.extract-interval-ms:300000}", initialDelay = 60_000)
    @SchedulerLock(name = "dms-text-extraction", lockAtMostFor = "PT20M")
    public void sweep() {
        processPending(100);
    }

    /** Extract up to {@code limit} pending documents (all organizations); returns how many were processed. */
    public int processPending(int limit) {
        List<String> ids = jdbc.sql("SELECT id FROM \"DmsDocument\" WHERE \"textStatus\" = 'pending' AND"
                + " \"deletedAt\" IS NULL ORDER BY \"updatedAt\" LIMIT ?").param(limit).query(String.class).list();
        for (String id : ids) {
            try {
                extract(id);
            } catch (RuntimeException e) {
                log.warn("dms text extraction {} failed: {}", id, e.toString());
            }
        }
        return ids.size();
    }

    /** Extract and store the text of one document (no tenant needed). */
    public void extract(String documentId) {
        record Doc(String fileId, String contentType, String name) {}
        Doc doc = jdbc.sql("SELECT \"fileId\", \"contentType\", name FROM \"DmsDocument\" WHERE id = ?")
                .param(documentId)
                .query((rs, n) -> new Doc(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional().orElse(null);
        if (doc == null) {
            return;
        }
        FileMeta meta = files.metaUnscoped(doc.fileId());
        String status;
        String text = null;
        if (meta == null || meta.byteSize() > MAX_BYTES) {
            status = "skipped";
        } else {
            try (InputStream in = files.open(meta)) {
                byte[] bytes = in.readAllBytes();
                text = extractText(bytes, doc.contentType(), doc.name());
                status = text == null ? "skipped" : "done";
            } catch (Exception e) {
                log.debug("dms extraction {}: {}", documentId, e.toString());
                status = "failed";
            }
        }
        if (text != null) {
            text = text.replace('\u0000', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n")
                    .trim();
            if (text.length() > MAX_CHARS) {
                text = text.substring(0, MAX_CHARS);
            }
        }
        // only if the file did not change meanwhile (a new version re-queues itself)
        jdbc.sql("UPDATE \"DmsDocument\" SET \"contentText\" = ?, \"textStatus\" = ? WHERE id = ? AND \"fileId\" = ?")
                .params(text, status, documentId, doc.fileId()).update();
    }

    /** Text of a file by type; {@code null} = unsupported type. */
    String extractText(byte[] bytes, String contentType, String filename) throws IOException {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (TEXT_TYPES.contains(type) || type.startsWith("text/") && !type.startsWith("text/html")
                || name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".md")) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (type.equals("application/pdf") || name.endsWith(".pdf")) {
            String text = pdfText(bytes);
            if ((text == null || text.isBlank()) && ocrEnabled) {
                return ocr(bytes, ".pdf");
            }
            return text;
        }
        if (name.endsWith(".docx") || type.contains("wordprocessingml")) {
            try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(new ByteArrayInputStream(bytes));
                    var ex = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)) {
                return ex.getText();
            }
        }
        if (name.endsWith(".xlsx") || type.contains("spreadsheetml")) {
            try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes));
                    var ex = new org.apache.poi.xssf.extractor.XSSFExcelExtractor(wb)) {
                return ex.getText();
            }
        }
        if (IMAGE_TYPES.contains(type) && ocrEnabled) {
            return ocr(bytes, type.equals("image/png") ? ".png" : type.equals("image/tiff") ? ".tif" : ".jpg");
        }
        return null;
    }

    static String pdfText(byte[] bytes) throws IOException {
        PdfReader reader = new PdfReader(bytes);
        try {
            PdfTextExtractor ex = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            int pages = Math.min(reader.getNumberOfPages(), 500);
            for (int i = 1; i <= pages && sb.length() < MAX_CHARS; i++) {
                sb.append(ex.getTextFromPage(i)).append('\n');
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }

    /** OCR through the tesseract CLI (60 s timeout); {@code null} when it is not available. */
    String ocr(byte[] bytes, String ext) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("dms-ocr-", ext);
            Files.write(tmp, bytes);
            Process p = new ProcessBuilder("tesseract", tmp.toString(), "stdout", "-l", ocrLanguages)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            log.debug("tesseract not available: {}", e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // temp file
                }
            }
        }
    }
}

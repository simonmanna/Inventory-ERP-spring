package com.inventoryerp.dms.web;

import com.inventoryerp.dms.application.DmsDocumentService.FilePart;
import com.inventoryerp.shared.web.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/** Multipart helpers shared by the DMS controllers. */
final class DmsParts {

    private DmsParts() {}

    /** Files sent as {@code file} and/or {@code files}. */
    static List<FilePart> parts(List<MultipartFile> file, List<MultipartFile> files) {
        List<FilePart> out = new ArrayList<>();
        for (List<MultipartFile> group : Arrays.asList(file, files)) {
            if (group == null) {
                continue;
            }
            for (MultipartFile f : group) {
                if (f == null || f.isEmpty()) {
                    continue;
                }
                try {
                    out.add(new FilePart(f.getOriginalFilename(), f.getContentType(), f.getBytes()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        if (out.isEmpty()) {
            throw ApiException.badRequest("Missing file");
        }
        return out;
    }

    /** Comma-separated or repeated values. */
    static List<String> list(List<String> raw) {
        if (raw == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            for (String s : r.split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    static LocalDate date(String v, String field) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be a valid ISO 8601 date string");
        }
    }

    static Integer integer(String v, String field) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(field + " must be an integer number");
        }
    }

    static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}

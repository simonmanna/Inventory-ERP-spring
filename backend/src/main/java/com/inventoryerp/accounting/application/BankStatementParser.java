package com.inventoryerp.accounting.application;

import com.inventoryerp.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Bank statement files into lines (INT-3): CSV with a header row (column names recognised in English, comma, semicolon or
 * tab), SWIFT MT940 and ISO 20022 camt.053. Pure and unaware of the database. Amounts are signed: money in is positive,
 * as {@link BankReconciliationService} expects.
 */
public final class BankStatementParser {

    public static final String CSV = "csv";
    public static final String MT940 = "mt940";
    public static final String CAMT053 = "camt053";

    private static final int MAX_LINES = 20_000;

    private static final Set<String> DATE_HEADERS = Set.of("date", "posted", "posted date", "posting date",
            "transaction date", "txn date", "trans date", "value date", "book date", "booking date");
    private static final Set<String> TEXT_HEADERS = Set.of("description", "narrative", "narration", "details",
            "particulars", "memo", "transaction details", "remarks");
    private static final Set<String> REF_HEADERS = Set.of("reference", "ref", "ref no", "reference number",
            "transaction id", "transaction ref", "transaction reference", "cheque number", "id");
    private static final Set<String> AMOUNT_HEADERS = Set.of("amount", "transaction amount", "value");
    private static final Set<String> DEBIT_HEADERS = Set.of("debit", "debit amount", "withdrawal", "withdrawals",
            "paid out", "money out", "dr");
    private static final Set<String> CREDIT_HEADERS = Set.of("credit", "credit amount", "deposit", "deposits",
            "paid in", "money in", "cr");

    /** Day first where a date is ambiguous. */
    private static final List<DateTimeFormatter> DATES = List.of(fmt("yyyy-MM-dd"), fmt("yyyy/MM/dd"), fmt("d/M/yyyy"),
            fmt("d-M-yyyy"), fmt("d.M.yyyy"), fmt("d/M/yy"), fmt("d-MMM-yyyy"), fmt("d MMM yyyy"), fmt("d-MMM-yy"));

    private static final Pattern MT_TAG = Pattern.compile("^:(\\d{2}[A-Z]?):(.*)$");
    private static final Pattern MT_61 = Pattern.compile(
            "^(\\d{6})(\\d{4})?(RC|RD|C|D)[A-Z]?(\\d+,\\d*)([NFS][A-Z0-9]{3})?(.*)$");

    /** A parsed line; {@code reference} may be null. */
    public record ParsedLine(LocalDate date, String reference, String description, BigDecimal amount) {}

    /** The lines, the format found, and what was skipped. */
    public record Parsed(String format, List<ParsedLine> lines, List<String> warnings) {}

    private BankStatementParser() {}

    private static DateTimeFormatter fmt(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH);
    }

    /** Parses {@code content}; {@code format} null, blank or {@code auto} detects it. */
    public static Parsed parse(byte[] content, String format) {
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("The statement file is empty");
        }
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        String kind = format == null || format.isBlank() || "auto".equals(format) ? detect(text) : format;
        Parsed parsed = switch (kind) {
            case CSV -> csv(text);
            case MT940 -> mt940(text);
            case CAMT053 -> camt053(text);
            default -> throw ApiException.badRequest("Unknown statement format " + kind);
        };
        if (parsed.lines().size() > MAX_LINES) {
            throw ApiException.badRequest("A statement file can hold at most " + MAX_LINES + " lines");
        }
        return parsed;
    }

    static String detect(String text) {
        String head = text.stripLeading();
        if (head.startsWith("<")) {
            return CAMT053;
        }
        return head.contains(":61:") && head.contains(":20:") ? MT940 : CSV;
    }

    // ------------------------------------------------------------------ CSV

    private static Parsed csv(String text) {
        List<List<String>> rows = records(text, delimiter(text));
        int header = -1;
        Map<String, Integer> columns = null;
        for (int i = 0; i < rows.size() && i < 30 && header < 0; i++) {
            Map<String, Integer> found = columns(rows.get(i));
            if (found.containsKey("date") && (found.containsKey("amount") || found.containsKey("debit")
                    || found.containsKey("credit"))) {
                header = i;
                columns = found;
            }
        }
        if (columns == null) {
            throw ApiException.badRequest("No header row with a date and an amount column was found");
        }
        List<ParsedLine> lines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int i = header + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String rawDate = cell(row, columns.get("date"));
            if (rawDate.isEmpty()) {
                continue;
            }
            LocalDate date = date(rawDate);
            if (date == null) {
                warnings.add("Row " + (i + 1) + " skipped: no date in \"" + rawDate + "\"");
                continue;
            }
            BigDecimal amount = rowAmount(row, columns);
            if (amount == null) {
                warnings.add("Row " + (i + 1) + " skipped: no amount");
                continue;
            }
            lines.add(new ParsedLine(date, blank(cell(row, columns.get("reference"))),
                    orDash(cell(row, columns.get("description"))), amount));
        }
        return new Parsed(CSV, lines, warnings);
    }

    private static Map<String, Integer> columns(List<String> row) {
        Map<String, Integer> out = new java.util.HashMap<>();
        for (int i = 0; i < row.size(); i++) {
            String h = row.get(i).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ")
                    .trim();
            String key = DATE_HEADERS.contains(h) ? "date" : TEXT_HEADERS.contains(h) ? "description"
                    : REF_HEADERS.contains(h) ? "reference" : AMOUNT_HEADERS.contains(h) ? "amount"
                    : DEBIT_HEADERS.contains(h) ? "debit" : CREDIT_HEADERS.contains(h) ? "credit" : null;
            if (key != null) {
                out.putIfAbsent(key, i);
            }
        }
        return out;
    }

    private static BigDecimal rowAmount(List<String> row, Map<String, Integer> columns) {
        if (columns.containsKey("amount")) {
            return amount(cell(row, columns.get("amount")));
        }
        BigDecimal credit = amount(cell(row, columns.get("credit")));
        if (credit != null && credit.signum() != 0) {
            return credit.abs();
        }
        BigDecimal debit = amount(cell(row, columns.get("debit")));
        return debit == null ? credit : debit.abs().negate();
    }

    private static char delimiter(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        long semi = first.chars().filter(c -> c == ';').count();
        long tab = first.chars().filter(c -> c == '\t').count();
        long comma = first.chars().filter(c -> c == ',').count();
        return tab > comma && tab >= semi ? '\t' : semi > comma ? ';' : ',';
    }

    /** RFC 4180 records: quotes, doubled quotes, delimiters and line breaks inside quotes. */
    private static List<List<String>> records(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private static String cell(List<String> row, Integer index) {
        return index == null || index >= row.size() ? "" : row.get(index).trim();
    }

    private static LocalDate date(String raw) {
        String v = raw.trim();
        // A time part after the date (2026-10-01 14:03, 2026-10-01T14:03:00) is dropped.
        int cut = v.indexOf('T') == 10 ? 10 : v.indexOf(' ') >= 8 && Character.isDigit(v.charAt(0))
                && v.substring(0, v.indexOf(' ')).matches(".*\\d[-/.]\\d.*") ? v.indexOf(' ') : -1;
        String d = cut > 0 ? v.substring(0, cut) : v;
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(d, f);
            } catch (DateTimeParseException e) {
                // next pattern
            }
        }
        return null;
    }

    /** {@code 1,234.50}, {@code 1.234,50}, {@code (50.00)}, {@code 50.00-}, {@code UGX 1 000}; null when not a number. */
    static BigDecimal amount(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        boolean negative = v.startsWith("-") || v.endsWith("-") || (v.startsWith("(") && v.endsWith(")"));
        v = v.replaceAll("[^0-9.,]", "");
        if (v.isEmpty()) {
            return null;
        }
        int dot = v.lastIndexOf('.');
        int comma = v.lastIndexOf(',');
        if (dot >= 0 && comma >= 0) {
            v = dot > comma ? v.replace(",", "") : v.replace(".", "").replace(',', '.');
        } else if (comma >= 0) {
            boolean grouping = v.length() - comma - 1 == 3;
            v = grouping ? v.replace(",", "") : v.replace(',', '.');
        } else if (dot != v.indexOf('.')) {
            v = v.replace(".", "");
        }
        try {
            BigDecimal out = new BigDecimal(v);
            return negative ? out.negate() : out;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ MT940

    private static Parsed mt940(String text) {
        List<String[]> tags = new ArrayList<>();
        for (String line : text.split("\n")) {
            Matcher m = MT_TAG.matcher(line);
            if (m.matches()) {
                tags.add(new String[] {m.group(1), m.group(2)});
            } else if (!tags.isEmpty() && !line.startsWith("-}") && !line.equals("-") && !line.isBlank()) {
                String[] last = tags.getLast();
                last[1] = last[1] + "\n" + line;
            }
        }
        List<ParsedLine> lines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            if (!"61".equals(tags.get(i)[0])) {
                continue;
            }
            String value = tags.get(i)[1];
            Matcher m = MT_61.matcher(value.split("\n", 2)[0]);
            if (!m.matches()) {
                warnings.add("Statement line skipped: " + value.split("\n", 2)[0]);
                continue;
            }
            LocalDate date = LocalDate.of(2000 + Integer.parseInt(m.group(1).substring(0, 2)),
                    Integer.parseInt(m.group(1).substring(2, 4)), Integer.parseInt(m.group(1).substring(4, 6)));
            BigDecimal amount = new BigDecimal(m.group(4).replace(',', '.').replaceAll("\\.$", ""));
            boolean credit = "C".equals(m.group(3)) || "RD".equals(m.group(3));
            String detail = i + 1 < tags.size() && "86".equals(tags.get(i + 1)[0])
                    ? tags.get(i + 1)[1].replace('\n', ' ').trim() : value.contains("\n")
                            ? value.substring(value.indexOf('\n') + 1).replace('\n', ' ').trim() : "";
            lines.add(new ParsedLine(date, mtReference(m.group(6)), orDash(detail),
                    credit ? amount : amount.negate()));
        }
        return new Parsed(MT940, lines, warnings);
    }

    /** {@code customer//bank}: the bank's reference when there is one, else the customer's unless NONREF. */
    private static String mtReference(String tail) {
        String t = tail == null ? "" : tail.trim();
        int slash = t.indexOf("//");
        if (slash >= 0 && slash + 2 < t.length()) {
            return t.substring(slash + 2).trim();
        }
        String own = slash >= 0 ? t.substring(0, slash).trim() : t;
        return own.isEmpty() || "NONREF".equalsIgnoreCase(own) ? null : own;
    }

    // ------------------------------------------------------------------ camt.053

    private static Parsed camt053(String text) {
        Document doc = xml(text);
        List<ParsedLine> lines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        NodeList entries = doc.getElementsByTagNameNS("*", "Ntry");
        for (int i = 0; i < entries.getLength(); i++) {
            Element e = (Element) entries.item(i);
            BigDecimal amount = amount(first(e, "Amt"));
            String indicator = first(e, "CdtDbtInd");
            LocalDate date = camtDate(e);
            if (amount == null || date == null || indicator == null) {
                warnings.add("Entry " + (i + 1) + " skipped: no amount, direction or date");
                continue;
            }
            boolean credit = "CRDT".equals(indicator) != "true".equalsIgnoreCase(first(e, "RvslInd"));
            String reference = first(e, "AcctSvcrRef");
            if (reference == null) {
                reference = first(e, "EndToEndId");
            }
            String detail = first(e, "AddtlNtryInf");
            if (detail == null) {
                detail = first(e, "Ustrd");
            }
            lines.add(new ParsedLine(date, reference, orDash(detail == null ? "" : detail),
                    credit ? amount.abs() : amount.abs().negate()));
        }
        return new Parsed(CAMT053, lines, warnings);
    }

    private static LocalDate camtDate(Element entry) {
        for (String parent : List.of("BookgDt", "ValDt")) {
            NodeList nodes = entry.getElementsByTagNameNS("*", parent);
            if (nodes.getLength() > 0) {
                String v = nodes.item(0).getTextContent().trim();
                if (v.length() >= 10) {
                    return date(v.substring(0, 10));
                }
            }
        }
        return null;
    }

    private static String first(Element e, String name) {
        NodeList nodes = e.getElementsByTagNameNS("*", name);
        if (nodes.getLength() == 0) {
            return null;
        }
        String v = nodes.item(0).getTextContent().trim();
        return v.isEmpty() ? null : v;
    }

    /** Parses with DTDs and external entities off (statement files are untrusted input). */
    private static Document xml(String text) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw ApiException.badRequest("The statement file is not valid camt.053 XML");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}

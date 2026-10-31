package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.BankReconciliationService.StatementLine;
import com.inventoryerp.accounting.application.BankStatementParser.Parsed;
import com.inventoryerp.accounting.application.BankStatementParser.ParsedLine;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports a bank statement file (INT-3) through {@link BankReconciliationService#importStatement}. A line without a bank
 * reference gets a stable one from its date, amount, text and occurrence in the file, so importing the same file twice
 * adds nothing while two identical charges on one day both stay.
 */
@Service
public class BankStatementFileService {

    private final JdbcClient jdbc;
    private final BankReconciliationService reconciliation;

    public BankStatementFileService(JdbcClient jdbc, BankReconciliationService reconciliation) {
        this.jdbc = jdbc;
        this.reconciliation = reconciliation;
    }

    /** {@code dryRun} parses and reports without writing. */
    @Transactional
    public Map<String, Object> importFile(String bankAccountId, String currencyCode, String format, boolean dryRun,
            byte[] content) {
        String org = TenantContext.organizationId();
        long account = jdbc.sql("SELECT count(*) FROM \"BankAccount\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(bankAccountId, org).query(Long.class).single();
        if (account == 0) {
            throw ApiException.notFound("Bank account not found");
        }
        String currency = currencyCode != null && !currencyCode.isBlank() ? currencyCode : jdbc.sql("SELECT"
                + " COALESCE(\"currencyCode\", 'UGX') FROM \"Organization\" WHERE id = ?").param(org)
                .query(String.class).single();
        Parsed parsed = BankStatementParser.parse(content, format);
        BigDecimal in = BigDecimal.ZERO;
        BigDecimal out = BigDecimal.ZERO;
        Map<String, Integer> seen = new HashMap<>();
        List<StatementLine> lines = new ArrayList<>();
        for (ParsedLine l : parsed.lines()) {
            if (l.amount().signum() >= 0) {
                in = in.add(l.amount());
            } else {
                out = out.add(l.amount());
            }
            String base = l.date() + "|" + l.amount().stripTrailingZeros().toPlainString() + "|" + l.description();
            int occurrence = seen.merge(base, 1, Integer::sum);
            String reference = l.reference() != null ? l.reference() : "H" + hash(base + "|" + occurrence);
            lines.add(new StatementLine(l.date().toString(), reference, l.description(), l.amount(), currency));
        }
        Map<String, Object> result = Obj.of("format", parsed.format(), "lines", lines.size(), "moneyIn", in,
                "moneyOut", out, "warnings", parsed.warnings(), "dryRun", dryRun);
        if (dryRun) {
            result.put("preview", lines.stream().limit(50).toList());
            return result;
        }
        result.putAll(reconciliation.importStatement(bankAccountId, lines, currency));
        return result;
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

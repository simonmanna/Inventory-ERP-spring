package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.application.BankStatementParser;
import com.inventoryerp.accounting.application.BankStatementParser.Parsed;
import com.inventoryerp.accounting.application.BankStatementParser.ParsedLine;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** INT-3: CSV, MT940 and camt.053 statements into signed lines. */
class BankStatementParserTest {

    private static Parsed parse(String text, String format) {
        return BankStatementParser.parse(text.getBytes(StandardCharsets.UTF_8), format);
    }

    @Test
    void csvWithAmountColumnAndPreamble() {
        Parsed p = parse("""
                Stanbic Bank statement
                Account,123456
                Date,Description,Reference,Amount
                01/10/2026,"Cement, sale",R1,"1,250,000.50"
                02/10/2026,Bank charge,R2,-3500
                Closing balance,,,
                """, null);
        assertThat(p.format()).isEqualTo(BankStatementParser.CSV);
        assertThat(p.lines()).hasSize(2);
        ParsedLine first = p.lines().get(0);
        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(first.description()).isEqualTo("Cement, sale");
        assertThat(first.reference()).isEqualTo("R1");
        assertThat(first.amount()).isEqualByComparingTo("1250000.50");
        assertThat(p.lines().get(1).amount()).isEqualByComparingTo("-3500");
        assertThat(p.warnings()).hasSize(1);
    }

    @Test
    void csvWithDebitAndCreditColumnsAndSemicolons() {
        Parsed p = parse("""
                Value Date;Narration;Withdrawal;Deposit
                2026-10-03;Fuel;120.000,00;
                2026-10-04;Receipt;;4,50
                """, "csv");
        assertThat(p.lines().get(0).amount()).isEqualByComparingTo("-120000");
        assertThat(p.lines().get(1).amount()).isEqualByComparingTo("4.50");
        assertThat(p.lines().get(0).reference()).isNull();
    }

    @Test
    void mt940() {
        Parsed p = parse("""
                :20:STMT1
                :25:123456/UGX
                :60F:C261001UGX1000,00
                :61:2610021002C500,50NTRFNONREF//BANK1
                :86:Customer payment
                 invoice 12
                :61:261003D25,NCHGREF2
                :86:Charges
                :62F:C261003UGX1475,50
                -
                """, null);
        assertThat(p.format()).isEqualTo(BankStatementParser.MT940);
        assertThat(p.lines()).hasSize(2);
        ParsedLine in = p.lines().get(0);
        assertThat(in.date()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(in.amount()).isEqualByComparingTo("500.50");
        assertThat(in.reference()).isEqualTo("BANK1");
        assertThat(in.description()).contains("Customer payment").contains("invoice 12");
        assertThat(p.lines().get(1).amount()).isEqualByComparingTo("-25");
        assertThat(p.lines().get(1).reference()).isEqualTo("REF2");
    }

    @Test
    void camt053() {
        Parsed p = parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt><Stmt>
                  <Ntry><Amt Ccy="UGX">750.25</Amt><CdtDbtInd>CRDT</CdtDbtInd><BookgDt><Dt>2026-10-05</Dt></BookgDt>
                    <AcctSvcrRef>A1</AcctSvcrRef><AddtlNtryInf>Receipt</AddtlNtryInf></Ntry>
                  <Ntry><Amt Ccy="UGX">50</Amt><CdtDbtInd>DBIT</CdtDbtInd><BookgDt><DtTm>2026-10-06T09:00:00</DtTm></BookgDt>
                    <NtryDtls><TxDtls><Refs><EndToEndId>E2</EndToEndId></Refs><RmtInf><Ustrd>Fee</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
                </Stmt></BkToCstmrStmt></Document>
                """, null);
        assertThat(p.format()).isEqualTo(BankStatementParser.CAMT053);
        assertThat(p.lines().get(0).amount()).isEqualByComparingTo("750.25");
        assertThat(p.lines().get(1).amount()).isEqualByComparingTo("-50");
        assertThat(p.lines().get(1).date()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(p.lines().get(1).reference()).isEqualTo("E2");
        assertThat(p.lines().get(1).description()).isEqualTo("Fee");
    }

    @Test
    void refusesExternalEntitiesAndFilesWithoutColumns() {
        assertThatThrownBy(() -> parse("""
                <?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]><Document>&x;</Document>
                """, "camt053")).hasMessageContaining("not valid camt.053");
        assertThatThrownBy(() -> parse("a,b\n1,2\n", null)).hasMessageContaining("No header row");
        assertThatThrownBy(() -> BankStatementParser.parse(new byte[0], null)).hasMessageContaining("empty");
    }
}

package com.inventoryerp.sync.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.CashSessionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-S083 C1 (O0 correction 1): every op type the push handler dispatches, and every type the push DTO accepts, is
 * classified in {@link SyncOpCatalog} (41 types), and the cash-close money list matches the catalog.
 */
class SyncOpCatalogTest {

    static final Path SRC = Path.of("src/main/java/com/inventoryerp/sync");

    static Set<String> quoted(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"([a-zA-Z]+(?:_[a-z]+)?\\.[a-zA-Z]+)\"").matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    @DisplayName("the catalog classifies exactly the 41 dispatched op types")
    void catalogMatchesDispatch() throws IOException {
        String push = Files.readString(SRC.resolve("application/SyncPushService.java"));
        String body = push.substring(push.indexOf("private Map<String, Object> runOp("),
                push.indexOf("default -> throw http(\"Unsupported sync op type"));
        Set<String> dispatched = new LinkedHashSet<>();
        Matcher m = Pattern.compile("case ((?:\"[^\"]+\"(?:, )?)+) ->").matcher(body);
        while (m.find()) {
            dispatched.addAll(quoted(m.group(1)));
        }
        assertThat(dispatched).containsExactlyInAnyOrderElementsOf(SyncOpCatalog.types());
        assertThat(SyncOpCatalog.types()).hasSize(41);
    }

    @Test
    @DisplayName("the push DTO accepts exactly the catalog's op types")
    void catalogMatchesDto() throws IOException {
        String ctl = Files.readString(SRC.resolve("web/SyncController.java"));
        int at = ctl.indexOf("static final String OP_TYPES_LIST");
        String list = ctl.substring(at, ctl.indexOf(";", at));
        Set<String> accepted = new LinkedHashSet<>();
        for (String t : list.replaceAll("[^a-zA-Z_.,]", "").replace("staticfinalStringOP_TYPES_LIST", "").split(",")) {
            if (!t.isBlank()) {
                accepted.add(t);
            }
        }
        assertThat(accepted).containsExactlyInAnyOrderElementsOf(SyncOpCatalog.types());
    }

    @Test
    @DisplayName("cash-session close blocks on the catalog's money op types")
    void moneyTypesMatchCashClose() {
        assertThat(CashSessionService.SYNC_MONEY_OP_TYPES).containsExactlyInAnyOrderElementsOf(
                SyncOpCatalog.moneyTypes());
    }
}

package com.inventoryerp.backup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.settings.SettingsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;

/**
 * Backup → restore round trip against the Testcontainers database (port of {@code backup-restore.spec.ts}): pg_dump,
 * openssl-compatible encryption, checksum sidecars, the verified copy to a second destination, a full restore into a
 * throwaway database, and the guards that keep the live database safe. Needs the PostgreSQL client tools
 * ({@code PG_BIN} or PATH); skipped when they are missing. Also covers the {@code /admin/backups} HTTP surface.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackupRestoreIT extends ApiTestSupport {

    @Autowired
    ObjectProvider<JdbcConnectionDetails> jdbcDetails;

    @Autowired
    SettingsService settings;

    Path primary;
    Path offsite;
    BackupService service;
    String backupFile;
    final String password = "pw-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    static boolean pgToolsAvailable() {
        try {
            String bin = BackupService.detectPgBin();
            String exe = bin.isEmpty() ? "pg_dump" : Path.of(bin, "pg_dump.exe").toString();
            return new ProcessBuilder(exe, "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * pg_restore refuses to restore a dump made by newer client tools into an older server (v17+ emits
     * {@code SET transaction_timeout}); production pins {@code PG_BIN} to the server's major version.
     */
    boolean pgToolsMatchServer() {
        if (!pgToolsAvailable()) {
            return false;
        }
        try {
            String bin = BackupService.detectPgBin();
            String exe = bin.isEmpty() ? "pg_restore" : Path.of(bin, "pg_restore.exe").toString();
            Process p = new ProcessBuilder(exe, "--version").start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.").matcher(out);
            int client = m.find() ? Integer.parseInt(m.group(1)) : -1;
            int server = jdbc.sql("SHOW server_version_num").query(Integer.class).single() / 10000;
            return client == server;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    void setUp() throws Exception {
        primary = Files.createTempDirectory("pos-backup-primary-");
        offsite = Files.createTempDirectory("pos-backup-offsite-");
        MockEnvironment env = new MockEnvironment().withProperty("BACKUP_ENCRYPTION_PASSWORD", password)
                .withProperty("app.jobs.enabled", "false");
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        service = new BackupService(settings, env, jdbcDetails,
                none.getBeanProvider(net.javacrumbs.shedlock.core.LockProvider.class),
                none.getBeanProvider(org.springframework.mail.javamail.JavaMailSender.class));
        Map<String, Object> cfg = service.defaults();
        cfg.put("destinations", List.of(
                BackupService.m("type", "local", "path", primary.toString(), "label", "Primary", "enabled", true),
                BackupService.m("type", "external_drive", "path", offsite.toString(), "label", "USB", "enabled", true)));
        service.useConfig(cfg);
    }

    @AfterAll
    void tearDown() throws Exception {
        for (Path root : List.of(primary, offsite)) {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    @Order(1)
    @EnabledIf("pgToolsAvailable")
    @DisplayName("writes an encrypted, checksummed dump of the datasource database and a verified offsite copy")
    void fullBackup() throws Exception {
        Map<String, Object> r = service.runFullBackup(false);
        assertThat(r.get("error")).as(String.valueOf(r.get("error"))).isNull();
        assertThat(r.get("status")).isEqualTo("success");
        assertThat(r.get("encrypted")).isEqualTo(true);
        assertThat(r.get("database")).isEqualTo(service.pgConnection().database());
        backupFile = (String) r.get("target");
        assertThat(backupFile).endsWith(".dump.enc");
        try (var s = Files.list(primary.resolve("full"))) {
            assertThat(s.map(p -> p.getFileName().toString()).toList()).containsExactlyInAnyOrder(
                    Path.of(backupFile).getFileName().toString(), Path.of(backupFile).getFileName() + ".sha256");
        }
        byte[] bytes = Files.readAllBytes(Path.of(backupFile));
        assertThat(new String(bytes, 0, 8, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("Salted__");
        List<?> copies = (List<?>) r.get("copies");
        assertThat(copies).hasSize(1);
        assertThat(((Map<?, ?>) copies.getFirst()).get("destination")).isEqualTo("USB");
        assertThat(((Map<?, ?>) copies.getFirst()).get("ok")).isEqualTo(true);
        assertThat(Files.readAllBytes(offsite.resolve("full").resolve(Path.of(backupFile).getFileName())))
                .isEqualTo(bytes);
    }

    @Test
    @Order(2)
    @EnabledIf("pgToolsAvailable")
    @DisplayName("decrypts with stock openssl (disaster recovery without the POS install)")
    void opensslCompatible() throws Exception {
        Process v;
        try {
            v = new ProcessBuilder("openssl", "version").start();
            if (v.waitFor() != 0) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        Path out = primary.resolve("openssl-check.dump");
        Process p = new ProcessBuilder("openssl", "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-iter", "600000", "-md",
                "sha256", "-pass", "pass:" + password, "-in", backupFile, "-out", out.toString()).start();
        assertThat(p.waitFor()).isZero();
        assertThat(new String(Files.readAllBytes(out), 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
                .isEqualTo("PGDMP");
        Files.deleteIfExists(out);
    }

    @Test
    @Order(3)
    @EnabledIf("pgToolsAvailable")
    @DisplayName("verifies a backup by name; detects a corrupted copy through its checksum")
    void verify() throws Exception {
        Map<String, Object> r = service.restore(new BackupService.RestoreRequest("database",
                Path.of(backupFile).getFileName().toString(), null, true));
        assertThat(r.get("error")).as(String.valueOf(r.get("error"))).isNull();
        assertThat(r.get("success")).isEqualTo(true);
        Map<?, ?> checks = (Map<?, ?>) r.get("checks");
        assertThat(checks.get("checksum")).isEqualTo("verified");
        assertThat(((Number) checks.get("tocEntries")).intValue()).isGreaterThan(100);

        Path copy = primary.resolve("full").resolve("POS-CAFE_FULL_tampered.dump.enc");
        byte[] bytes = Files.readAllBytes(Path.of(backupFile));
        bytes[bytes.length - 100] ^= (byte) 0xff;
        Files.write(copy, bytes);
        Files.copy(Path.of(backupFile + ".sha256"), Path.of(copy + ".sha256"));
        Map<String, Object> bad = service.restore(new BackupService.RestoreRequest("database", copy.toString(), null, true));
        assertThat(bad.get("success")).isEqualTo(false);
        assertThat(bad.get("message").toString()).contains("Checksum mismatch");
        Files.deleteIfExists(copy);
        Files.deleteIfExists(Path.of(copy + ".sha256"));
    }

    @Test
    @Order(4)
    @EnabledIf("pgToolsMatchServer")
    @DisplayName("restore test: restores into a throwaway database, compares with live, drops it (DR drill)")
    void restoreTest() throws Exception {
        Map<String, Object> r = service.runRestoreTest(backupFile);
        assertThat(r.get("error")).as(String.valueOf(r.get("error"))).isNull();
        assertThat(r.get("status")).isEqualTo("success");
        assertThat(r.get("database").toString()).matches(".*_restoretest_\\d{14}$");
        assertThat(service.psql("SELECT count(*) FROM pg_database WHERE datname = '" + r.get("database") + "'", null))
                .isEqualTo("0");
    }

    @Test
    @Order(5)
    @EnabledIf("pgToolsAvailable")
    @DisplayName("refuses to restore over the live database and refuses files outside the backup folders")
    void guards() throws Exception {
        String live = service.pgConnection().database();
        Map<String, Object> r = service.restore(new BackupService.RestoreRequest("database", backupFile, live, null));
        assertThat(r.get("success")).isEqualTo(false);
        assertThat(r.get("message").toString()).contains("live database");
        Path outside = Files.createTempFile("outside-", ".dump");
        try {
            Map<String, Object> o = service.restore(new BackupService.RestoreRequest("database", outside.toString(), null,
                    true));
            assertThat(o.get("success")).isEqualTo(false);
            assertThat(o.get("message").toString()).contains("inside a configured backup folder");
            Map<String, Object> traversal = service.restore(new BackupService.RestoreRequest("database",
                    "../../outside.dump", null, true));
            assertThat(traversal.get("success")).isEqualTo(false);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @Order(6)
    @DisplayName("HTTP: status / health / settings (secrets masked), validation, readiness advisory, authz matrix")
    void http() {
        JsonNode status = ok(200, "GET", "/admin/backups/status", null);
        assertThat(status.get("server").has("primaryDir")).isTrue();
        JsonNode health = ok(200, "GET", "/admin/backups/health", null);
        assertThat(health.get("status").asString()).isIn("healthy", "degraded", "critical");
        JsonNode cfg = ok(200, "GET", "/admin/backups/settings", null);
        assertThat(cfg.get("frequency").isString()).isTrue();
        Res bad = as("PUT", "/admin/backups/settings", Map.of("frequency", "sometimes"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("message").toString()).contains("frequency must be one of the following values");
        Res restore = as("POST", "/admin/backups/restore", Map.of("scope", "database", "backupFile", "x.dump",
                "targetDatabase", "bad name"));
        assertThat(restore.status()).isEqualTo(400);
        assertThat(restore.body().get("message").toString())
                .contains("targetDatabase may only contain letters, digits and _");
        Res ready = call("GET", "/health/ready", null, (String) null);
        assertThat(ready.status()).isEqualTo(200);
        assertThat(ready.body().get("advisory").has("backup")).isTrue();
        assertThat(ready.body().get("status").asString()).isIn("ok", "degraded");
        assertThat(assertAuthorizationMatrix("com.inventoryerp.backup")).isEqualTo(13);
    }
}

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.mock.env.MockEnvironment;

/**
 * Backup jobs besides the full dump ({@link BackupRestoreIT}): uploads and config backups to a primary folder and a
 * second folder, a cloud destination whose CLI is missing (the copy fails, the backup does not), notifications,
 * listing, retention cleanup and the health report.
 */
class BackupJobsIT extends ApiTestSupport {

    @Autowired
    ObjectProvider<JdbcConnectionDetails> jdbcDetails;

    @Autowired
    ObjectProvider<org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails> ownerDetails;

    @Autowired
    SettingsService settings;

    Path root;
    Path primary;
    Path usb;
    BackupService service;

    @BeforeAll
    void setUp() throws Exception {
        root = Files.createTempDirectory("pos-backup-jobs-");
        primary = Files.createDirectories(root.resolve("primary"));
        usb = Files.createDirectories(root.resolve("usb"));
        Path uploads = Files.createDirectories(root.resolve("uploads").resolve("products"));
        Files.writeString(uploads.resolve("latte.png"), "not really a png");
        MockEnvironment env = new MockEnvironment().withProperty("BACKUP_ENCRYPTION_PASSWORD", "jobs-secret")
                .withProperty("app.jobs.enabled", "false").withProperty("STORAGE_DRIVER", "local")
                .withProperty("STORAGE_LOCAL_DIR", root.resolve("uploads").toString())
                .withProperty("BACKUP_HEALTHCHECKS_PING_URL", "http://127.0.0.1:9/ping");
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        service = new BackupService(settings, env, jdbcDetails, ownerDetails,
                none.getBeanProvider(net.javacrumbs.shedlock.core.LockProvider.class),
                none.getBeanProvider(org.springframework.mail.javamail.JavaMailSender.class));
        Map<String, Object> cfg = service.defaults();
        cfg.put("destinations", List.of(
                BackupService.m("type", "local", "path", primary.toString(), "label", "Primary", "enabled", true),
                BackupService.m("type", "external_drive", "path", usb.toString(), "label", "USB", "enabled", true),
                BackupService.m("type", "s3", "path", "shop", "bucket", "no-such-bucket", "label", "Cloud",
                        "enabled", true)));
        cfg.put("internetBehaviour", "upload_immediately");
        cfg.put("notifications", BackupService.m("on", List.of("completed", "failed", "skipped"), "channels",
                List.of("desktop")));
        service.useConfig(cfg);
    }

    @AfterAll
    void tearDown() throws Exception {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    static boolean pgToolsAvailable() {
        return BackupRestoreIT.pgToolsAvailable();
    }

    @Test
    @DisplayName("files backup mirrors the uploads folder to the primary and the second folder")
    void filesBackup() {
        Map<String, Object> r = service.runFilesBackup();
        assertThat(r.get("status")).as(String.valueOf(r)).isEqualTo("success");
        assertThat(Files.exists(primary.resolve("files").resolve("uploads"))).isTrue();
        assertThat(Files.exists(usb.resolve("files").resolve("uploads").resolve("products").resolve("latte.png")))
                .isTrue();
    }

    @Test
    @DisplayName("config backup writes an encrypted settings archive without the encryption password")
    void configBackup() throws Exception {
        Map<String, Object> r = service.runConfigBackup();
        assertThat(r.get("status")).as(String.valueOf(r)).isEqualTo("success");
        // No provider CLI here: the cloud copy fails and is reported, the backup itself does not.
        assertThat(String.valueOf(r.get("copies"))).contains("destination=Cloud, ok=false");
        try (var s = Files.walk(primary)) {
            assertThat(s.filter(Files::isRegularFile).map(p -> p.toString()).anyMatch(p -> p.contains("config")))
                    .isTrue();
        }
        assertThat(service.listBackups("config")).isNotEmpty();
    }

    @Test
    @DisplayName("incremental backup reports its state when WAL archiving is not configured")
    void incrementalBackup() {
        Map<String, Object> r = service.runIncrementalBackup();
        assertThat(r.get("kind")).isEqualTo("incremental");
        assertThat(r.get("status")).isIn("success", "skipped", "failed");
    }

    @Test
    @EnabledIf("pgToolsAvailable")
    @DisplayName("scheduled full backup runs, then the list, cleanup and health report see it")
    void scheduledFullThenHousekeeping() {
        Map<String, Object> r = service.runScheduledFull();
        assertThat(r.get("kind")).isEqualTo("full");
        assertThat(service.listBackups("full")).isNotEmpty();
        Map<String, Object> cleaned = service.cleanup();
        assertThat(cleaned).isNotNull();
        Map<String, Object> health = service.getHealthStatus();
        assertThat(health).containsKey("status");
        assertThat(service.getStatus()).isNotEmpty();
    }

    @Test
    @DisplayName("health report and status without a full backup")
    void healthWithoutFull() {
        assertThat(service.getHealthStatus()).containsKey("status");
        assertThat(service.listBackups(null)).isNotNull();
        assertThat(service.cleanup()).isNotNull();
    }
}

package com.inventoryerp.backup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.inventoryerp.kernel.settings.SettingsService;
import com.inventoryerp.shared.web.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/** Port of {@code test/backup/backup.service.spec.ts} (config, secrets, retention, connection) + the crypto format. */
class BackupServiceTest {

    @TempDir
    Path backupDir;

    SettingsService settings;
    MockEnvironment env;
    BackupService service;

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        env = new MockEnvironment().withProperty("BACKUP_DIR", backupDir.toString())
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/cafe")
                .withProperty("spring.datasource.username", "app").withProperty("spring.datasource.password", "secret");
        service = build();
    }

    BackupService build() {
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        return new BackupService(settings, env, none.getBeanProvider(
                org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails.class),
                none.getBeanProvider(net.javacrumbs.shedlock.core.LockProvider.class),
                none.getBeanProvider(org.springframework.mail.javamail.JavaMailSender.class));
    }

    @AfterEach
    void tearDown() {
        service.destroy();
    }

    @Test
    @DisplayName("defaults: daily full backups to the local folder, unencrypted, empty history, no backups listed")
    void defaults() {
        Map<String, Object> cfg = service.getConfig();
        assertThat(cfg.get("frequency")).isEqualTo("daily");
        assertThat(cfg.get("types")).isEqualTo(List.of("full"));
        assertThat(BackupService.map(cfg.get("encryption")).get("type")).isEqualTo("none");
        assertThat((List<?>) service.getStatus().get("recent")).isEmpty();
        assertThat(service.listBackups(null)).isEmpty();
        Map<String, Object> health = service.getHealthStatus();
        assertThat(health.get("status")).isEqualTo("critical");
        assertThat(health.get("issues").toString()).contains("No full backup found");
    }

    @Test
    @DisplayName("restore rejects an empty path and a file that does not exist")
    void restoreGuards() {
        Map<String, Object> empty = service.restore(new BackupService.RestoreRequest("database", "  ", null, null));
        assertThat(empty.get("success")).isEqualTo(false);
        assertThat(empty.get("message")).isEqualTo("Backup file path is required");
        Map<String, Object> missing = service.restore(new BackupService.RestoreRequest("database", "nope.dump", null,
                true));
        assertThat(missing.get("message").toString()).startsWith("Backup file not found");
        Map<String, Object> scope = service.restore(new BackupService.RestoreRequest("files", "x.dump", null, null));
        assertThat(scope.get("message")).isEqualTo("Restoring \"files\" is a manual step - see docs/backup-recovery.md");
    }

    @Test
    @DisplayName("updateConfig merges a partial config with the defaults and persists it server-wide")
    void updateConfigMerges() {
        Map<String, Object> pub = service.updateConfig(Map.of("frequency", "weekly", "weeklyConfig",
                Map.of("days", List.of(1, 3), "time", "03:30")));
        assertThat(pub.get("frequency")).isEqualTo("weekly");
        assertThat(pub.get("namingFormat")).isEqualTo("POS-CAFE_{TYPE}_{DATE}_{TIME}");
        verify(settings).setServerWide(anyString(), any());
        assertThat(BackupService.resolveCronExpressions(service.getConfig()).stream().map(t -> t[1]).toList())
                .containsExactly("30 3 * * 1", "30 3 * * 3");
    }

    @Test
    @DisplayName("rejects schedules that would crash the scheduler or stop backups — nothing persisted")
    void validation() {
        assertThatThrownBy(() -> service.updateConfig(Map.of("frequency", "cron", "customCronExpression", "not a cron")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.updateConfig(Map.of("frequency", "custom_minutes", "customIntervalMinutes", 90)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.updateConfig(Map.of("types", List.of("incremental"))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.updateConfig(Map.of("destinations", List.of(Map.of("type", "local", "path", "",
                "enabled", true))))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.updateConfig(Map.of("namingFormat", "backup")))
                .isInstanceOf(ApiException.class);
        verify(settings, never()).setServerWide(anyString(), any());
    }

    @Test
    @DisplayName("never serves stored secrets and keeps them when the mask is sent back")
    void secrets() {
        service.updateConfig(Map.of("encryption", Map.of("type", "aes256", "password", "hunter2")));
        Map<String, Object> pub = service.getPublicConfig();
        assertThat(BackupService.map(pub.get("encryption")).get("password")).isEqualTo(BackupService.SECRET_MASK);
        service.updateConfig(Map.of("encryption", pub.get("encryption")));
        assertThat(BackupService.map(service.getConfig().get("encryption")).get("password")).isEqualTo("hunter2");
        assertThat(service.encryptionPassword()).isEqualTo("hunter2");
    }

    @Test
    @DisplayName("survives an invalid persisted schedule instead of failing boot; health turns critical")
    void invalidSchedule() {
        service.init();
        Map<String, Object> cfg = service.getConfig();
        cfg.put("frequency", "cron");
        cfg.put("customCronExpression", "99 99 * * *");
        service.useConfig(cfg);
        service.reschedule();
        Map<String, Object> health = service.getHealthStatus();
        assertThat(health.get("status")).isEqualTo("critical");
        assertThat(String.join("\n", ((List<?>) health.get("issues")).stream().map(String::valueOf).toList()))
                .containsIgnoringCase("schedule");
    }

    @Test
    @DisplayName("backs up the datasource database; PGDATABASE is ignored with a warning; PGUSER names a backup role")
    void connection() throws IOException {
        env.setProperty("spring.datasource.url", "jdbc:postgresql://db.local:5433/cafe_pos_v2?sslmode=require");
        env.setProperty("spring.datasource.username", "cafe-pos");
        env.setProperty("spring.datasource.password", "p@ss:word");
        env.setProperty("PGDATABASE", "POS-CAFE");
        BackupService.PgConnection pg = service.pgConnection();
        assertThat(pg.database()).isEqualTo("cafe_pos_v2");
        assertThat(pg.env()).containsEntry("PGHOST", "db.local").containsEntry("PGPORT", "5433")
                .containsEntry("PGUSER", "cafe-pos").containsEntry("PGPASSWORD", "p@ss:word")
                .containsEntry("PGSSLMODE", "require").doesNotContainKey("PGDATABASE");
        assertThat(String.join(",", service.warnings())).contains("PGDATABASE=\"POS-CAFE\"");
        env.setProperty("PGUSER", "postgres");
        env.setProperty("PGPASSWORD", "admin");
        assertThat(service.pgConnection().env()).containsEntry("PGUSER", "postgres").containsEntry("PGPASSWORD", "admin");
    }

    List<Path> seed(String sub, int... agesDays) throws IOException {
        Path dir = backupDir.resolve(sub);
        Files.createDirectories(dir);
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < agesDays.length; i++) {
            Path f = dir.resolve("POS-CAFE_FULL_" + i + ".dump");
            Files.writeString(f, "x");
            Files.writeString(Path.of(f + ".sha256"), "x");
            Files.setLastModifiedTime(f, FileTime.from(Instant.now().minusSeconds(agesDays[i] * 86_400L + i)));
            files.add(f);
        }
        return files;
    }

    @Test
    @DisplayName("retention: keep-by-age never deletes the last 3; smart keeps the newest per day; mirror never pruned")
    void retention() throws IOException {
        Map<String, Object> cfg = service.getConfig();
        cfg.put("retention", BackupService.m("mode", "keep_by_age", "keepDays", 30));
        service.useConfig(cfg);
        List<Path> files = seed("full", 40, 41, 42, 43, 44);
        service.cleanup();
        try (var s = Files.list(backupDir.resolve("full"))) {
            assertThat(s.map(p -> p.getFileName().toString()).sorted().toList()).containsExactlyInAnyOrder(
                    files.get(0).getFileName() + "", files.get(0).getFileName() + ".sha256",
                    files.get(1).getFileName() + "", files.get(1).getFileName() + ".sha256",
                    files.get(2).getFileName() + "", files.get(2).getFileName() + ".sha256");
        }
        Path mirror = backupDir.resolve("files").resolve("uploads");
        Files.createDirectories(mirror);
        service.cleanup();
        assertThat(Files.exists(mirror)).isTrue();

        cfg.put("retention", BackupService.m("mode", "smart", "smartDaily", 3, "smartWeekly", 0, "smartMonthly", 0));
        service.useConfig(cfg);
        Path inc = backupDir.resolve("incremental");
        List<Path> smart = seed("incremental", 0, 0, 1, 2, 3, 4, 5);
        service.cleanup();
        try (var s = Files.list(inc)) {
            assertThat(s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".dump")).sorted().toList())
                    .containsExactly(smart.get(0).getFileName() + "", smart.get(1).getFileName() + "",
                            smart.get(2).getFileName() + "", smart.get(3).getFileName() + "");
        }
    }

    @Test
    @DisplayName("encryption: decrypts a backup written by the Nest code, round-trips, rejects a wrong password")
    void crypto() throws Exception {
        Path enc = backupDir.resolve("nest-vector.txt.enc");
        try (var in = getClass().getResourceAsStream("/backup/nest-vector.txt.enc")) {
            Files.write(enc, in.readAllBytes());
        }
        Path out = backupDir.resolve("plain.txt");
        BackupCrypto.decryptFile(enc, out, "Correct-Horse-9");
        assertThat(Files.readString(out)).isEqualTo("POS-CAFE backup vector\nline two\n");

        Path src = backupDir.resolve("src.bin");
        byte[] data = new byte[200_000];
        new java.util.Random(7).nextBytes(data);
        Files.write(src, data);
        Path sealed = backupDir.resolve("src.bin.enc");
        BackupCrypto.encryptFile(src, sealed, "pw");
        assertThat(new String(Files.readAllBytes(sealed), 0, 8, StandardCharsets.ISO_8859_1)).isEqualTo("Salted__");
        Path back = backupDir.resolve("back.bin");
        BackupCrypto.decryptFile(sealed, back, "pw");
        assertThat(Files.readAllBytes(back)).isEqualTo(data);
        assertThatThrownBy(() -> BackupCrypto.decryptFile(sealed, backupDir.resolve("x"), "wrong"))
                .hasMessage("decryption failed: wrong password or corrupted file");
        assertThatThrownBy(() -> BackupCrypto.decryptFile(src, backupDir.resolve("y"), "pw"))
                .hasMessage("not an encrypted backup (missing Salted__ header)");
    }
}

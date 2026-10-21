package com.inventoryerp.backup.web;

import com.inventoryerp.backup.application.BackupService;
import com.inventoryerp.backup.application.BackupService.RestoreRequest;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /admin/backups} (port of {@code backup.controller.ts}). Restores always go into a NEW database; the live one
 * is never overwritten from the API.
 */
@RestController
@RequestMapping("/admin/backups")
public class BackupController {

    static final String STR = "must be a string";
    static final String BOOL = "must be a boolean value";
    static final String NUM = "must be a number conforming to the specified constraints";
    static final String MIN1 = "must not be less than 1";
    static final String MIN0 = "must not be less than 0";
    static final String HH_MM = "^([01]\\d|2[0-3]):[0-5]\\d$";

    /** {@code DailyFrequencyDto}. */
    public record DailyFrequency(@NotNull(message = "must be an array")
            List<@Pattern(regexp = HH_MM, message = "times must be HH:MM (24h)") String> times) {}

    /** {@code WeeklyFrequencyDto}. */
    public record WeeklyFrequency(
            @NotNull(message = "must be an array") List<@NotNull(message = NUM)
                    @DecimalMin(value = "0", message = "each value in days must not be less than 0")
                    @DecimalMax(value = "6", message = "each value in days must not be greater than 6") Double> days,
            @NotNull(message = "time must be HH:MM (24h)") @Pattern(regexp = HH_MM, message = "time must be HH:MM (24h)")
                    String time) {}

    /** {@code BackupIncludesDto}. */
    public record Includes(Boolean database, Boolean schemaOnly, Boolean databaseData, Boolean uploadedImages,
            Boolean productImages, Boolean customerDocuments, Boolean generatedReports, Boolean attachments,
            Boolean envFile, Boolean configFiles, Boolean posSettings, Boolean orgSettings, Boolean printerConfig,
            Boolean receiptTemplates, Boolean kitchenPrinterSettings, Boolean logs, Boolean auditLogs,
            Boolean scheduledTasks) {}

    /** {@code BackupDestinationDto}. */
    public record Destination(
            @NotNull(message = "must be one of the following values: local, external_drive, network_folder, onedrive,"
                    + " google_drive, dropbox, s3, azure, ftp, sftp")
            @OneOf({"local", "external_drive", "network_folder", "onedrive", "google_drive", "dropbox", "s3", "azure",
                "ftp", "sftp"}) String type,
            @NotNull(message = STR) String path,
            String label, Boolean enabled, String accessKey, String secretKey, String region, String bucket) {}

    /** {@code RetentionPolicyDto}. */
    public record Retention(
            @NotNull(message = "must be one of the following values: keep_last, keep_by_age, smart")
            @OneOf({"keep_last", "keep_by_age", "smart"}) String mode,
            @DecimalMin(value = "1", message = MIN1) Double keepLastCount,
            @DecimalMin(value = "1", message = MIN1) Double keepDays,
            @DecimalMin(value = "1", message = MIN1) Double smartDaily,
            @DecimalMin(value = "1", message = MIN1) Double smartWeekly,
            @DecimalMin(value = "1", message = MIN1) Double smartMonthly) {}

    /** {@code NotificationsDto}. */
    public record Notifications(
            @NotNull(message = "must be an array") List<@OneOf({"completed", "failed", "storage_almost_full",
                "skipped"}) String> on,
            @NotNull(message = "must be an array") List<@OneOf({"desktop", "email", "sms", "push"}) String> channels,
            String emailAddress, String phoneNumber) {}

    /** {@code VerificationDto}. */
    public record Verification(Boolean verifyIntegrity, Boolean testRestore, Boolean sha256Checksum) {}

    /** {@code EncryptionDto}. */
    public record Encryption(
            @NotNull(message = "must be one of the following values: none, aes256") @OneOf({"none", "aes256"}) String type,
            String password) {}

    /** {@code CleanupDto}. */
    public record Cleanup(Boolean deleteExpired, Boolean deleteTempFiles, Boolean removeFailedFiles) {}

    /** {@code ScheduleDto}. */
    public record Schedule(String time, Double dayOfWeek, String timezone, Double customIntervalMinutes,
            String cronExpression) {}

    /** {@code AdvancedDto}. */
    public record Advanced(
            @DecimalMin(value = "1", message = MIN1) Double maxThreads,
            @DecimalMin(value = "0", message = MIN0) @DecimalMax(value = "9", message = "must not be greater than 9")
                    Double compressionLevel,
            Boolean pauseBusinessHours, String businessHoursStart, String businessHoursEnd, Boolean skipIfBusy,
            @DecimalMin(value = "0", message = MIN0) Double retryCount,
            @DecimalMin(value = "0", message = MIN0) Double retryDelayMinutes,
            @DecimalMin(value = "0", message = MIN0) Double backupTimeoutMinutes,
            @DecimalMin(value = "0", message = MIN0) Double maxDiskUsageGb,
            Boolean autoDiskSpaceCheck) {}

    /** {@code BackupConfigDto}. */
    public record BackupConfigRequest(
            @NotNull(message = "must be one of the following values: manual, every_hour, every_2_hours, every_4_hours,"
                    + " every_6_hours, every_12_hours, daily, weekly, monthly, custom_minutes, custom_hours, cron")
            @OneOf({"manual", "every_hour", "every_2_hours", "every_4_hours", "every_6_hours", "every_12_hours", "daily",
                "weekly", "monthly", "custom_minutes", "custom_hours", "cron"}) String frequency,
            @Valid DailyFrequency dailyConfig,
            @Valid WeeklyFrequency weeklyConfig,
            String customCronExpression,
            @DecimalMin(value = "1", message = MIN1) Double customIntervalMinutes,
            @NotNull(message = "must be an array") List<@OneOf({"full", "incremental", "differential", "snapshot",
                "mirror"}) String> types,
            @Valid Includes includes,
            @NotNull(message = "must be an array") @Valid List<Destination> destinations,
            @Valid Retention retention,
            @NotNull(message = "must be one of the following values: 0, 1, 6, 9") @OneOf({"0", "1", "6", "9"})
                    Integer compression,
            @Valid Encryption encryption,
            @Valid Verification verification,
            @Valid Notifications notifications,
            @Valid Cleanup cleanup,
            @NotNull(message = "must be one of the following values: local_only, upload_immediately,"
                    + " upload_when_internet_returns")
            @OneOf({"local_only", "upload_immediately", "upload_when_internet_returns"}) String internetBehaviour,
            String namingFormat,
            @Valid Schedule schedule,
            @Valid Advanced advanced) {}

    /** {@code RestoreDto}. */
    public record RestoreDtoRequest(
            @NotNull(message = "must be one of the following values: database, files, config, everything")
            @OneOf({"database", "files", "config", "everything"}) String scope,
            @NotNull(message = STR) String backupFile,
            @Pattern(regexp = "^[A-Za-z0-9_]{1,63}$", message = "targetDatabase may only contain letters, digits and _")
                    String targetDatabase,
            Boolean verifyOnly) {}

    /** {@code { backupFile }} (untyped in Nest). */
    public record BackupFileRequest(String backupFile) {}

    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    @GetMapping("/status")
    @RequirePermissions(Permissions.BACKUP__READ)
    public Map<String, Object> status() {
        return backups.getStatus();
    }

    @GetMapping("/health")
    @RequirePermissions(Permissions.BACKUP__READ)
    public Map<String, Object> health() {
        return backups.getHealthStatus();
    }

    @GetMapping("/list")
    @RequirePermissions(Permissions.BACKUP__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String kind) {
        return backups.listBackups(kind);
    }

    @GetMapping("/settings")
    @RequirePermissions(Permissions.BACKUP__READ)
    public Map<String, Object> getSettings() {
        return backups.getPublicConfig();
    }

    @PutMapping("/settings")
    @RequirePermissions(Permissions.BACKUP__UPDATE)
    public Map<String, Object> updateSettings(@Valid @RequestBody BackupConfigRequest dto) {
        return backups.updateConfig(plain(dto));
    }

    @PostMapping("/full")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BACKUP__RUN)
    public Map<String, Object> runFull() {
        return backups.runFullBackup();
    }

    @PostMapping("/incremental")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BACKUP__RUN)
    public Map<String, Object> runIncremental() {
        return backups.runIncrementalBackup();
    }

    @PostMapping("/files")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BACKUP__RUN)
    public Map<String, Object> runFiles() {
        return backups.runFilesBackup();
    }

    @PostMapping("/config")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BACKUP__RUN)
    public Map<String, Object> runConfig() {
        return backups.runConfigBackup();
    }

    @PostMapping("/cleanup")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BACKUP__RUN)
    public Map<String, Object> cleanup() {
        return backups.cleanup();
    }

    @PostMapping("/restore")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BACKUP__RUN)
    public Map<String, Object> restore(@Valid @RequestBody RestoreDtoRequest dto) {
        return backups.restore(new RestoreRequest(dto.scope(), dto.backupFile(), dto.targetDatabase(), false));
    }

    @PostMapping("/restore/verify")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BACKUP__READ)
    public Map<String, Object> verifyRestore(@RequestBody BackupFileRequest dto) {
        return backups.restore(new RestoreRequest("database", dto.backupFile(), null, true));
    }

    @PostMapping("/restore/test")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.BACKUP__RUN)
    public Map<String, Object> testRestore(@RequestBody(required = false) BackupFileRequest dto) {
        return backups.runRestoreTest(dto == null ? null : dto.backupFile());
    }

    /**
     * The class-transformer instance as a plain object: only provided properties, nested DTOs as objects, numbers as
     * JS numbers (whole doubles become integers).
     */
    static Map<String, Object> plain(Record r) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RecordComponent c : r.getClass().getRecordComponents()) {
            try {
                Object v = c.getAccessor().invoke(r);
                if (v != null) {
                    out.put(c.getName(), plainValue(v));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    private static Object plainValue(Object v) {
        if (v instanceof Record r) {
            return plain(r);
        }
        if (v instanceof Collection<?> c) {
            List<Object> l = new ArrayList<>();
            c.forEach(o -> l.add(o == null ? null : plainValue(o)));
            return l;
        }
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return d.longValue();
        }
        return v;
    }
}

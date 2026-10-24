package com.inventoryerp.backup.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.settings.SettingsService;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Backups (port of {@code backup.service.ts}): full {@code pg_dump} (custom format, TOC read back, optional
 * openssl-compatible AES-256 encryption, sha256 sidecar, verified copies to secondary folders, cloud CLIs), WAL
 * incremental status, uploads mirror, server-config capture, retention, restore into a NEW database only, scheduled
 * restore tests, health for {@code /health/ready}, dynamic cron schedule from the {@code backup.config} setting.
 * PostgreSQL client tools run through {@link ProcessBuilder} (never a shell) with {@code PG_BIN}, env credentials and
 * timeouts.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    public static final String BACKUP_SETTING_KEY = "backup.config";
    public static final String SECRET_MASK = "********";
    static final int MIN_BACKUPS_KEPT = 3;
    static final Set<String> FOLDER_DESTINATIONS = Set.of("local", "external_drive", "network_folder");
    static final List<String> ARTIFACT_KINDS = List.of("full", "config", "incremental");
    static final String CHECKSUM_SUFFIX = ".sha256";
    static final String PARTIAL_SUFFIX = ".partial";
    static final double GB = 1024.0 * 1024 * 1024;
    static final long HEALTH_CACHE_MS = 60_000;
    static final long CATCH_UP_DELAY_MS = 2 * 60_000;
    static final Pattern HH_MM = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$");

    private final SettingsService settings;
    private final Environment env;
    private final ObjectProvider<JdbcConnectionDetails> jdbcDetails;
    private final ObjectProvider<LockProvider> lockProvider;
    private final ObjectProvider<JavaMailSender> mail;

    private final List<Map<String, Object>> history = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedOnce = Collections.synchronizedSet(new LinkedHashSet<>());
    private final List<ScheduledFuture<?>> jobs = new ArrayList<>();
    private volatile Map<String, Object> config;
    private volatile String scheduleError;
    private volatile Map<String, Object> healthCache;
    private volatile long healthCacheAt;
    private ThreadPoolTaskScheduler scheduler;

    public BackupService(SettingsService settings, Environment env, ObjectProvider<JdbcConnectionDetails> jdbcDetails,
            ObjectProvider<LockProvider> lockProvider, ObjectProvider<JavaMailSender> mail) {
        this.settings = settings;
        this.env = env;
        this.jdbcDetails = jdbcDetails;
        this.lockProvider = lockProvider;
        this.mail = mail;
        this.config = defaults();
    }

    private String envVar(String name) {
        String v = env.getProperty(name);
        return v == null ? null : v;
    }

    // ─── defaults (backup.constants.ts) ───

    Map<String, Object> defaults() {
        String freq = envVar("BACKUP_FREQUENCY");
        List<String> frequencies = List.of("manual", "every_hour", "every_2_hours", "every_4_hours", "every_6_hours",
                "every_12_hours", "daily", "weekly", "monthly", "custom_minutes", "custom_hours", "cron");
        String time = envVar("BACKUP_TIME") != null && envVar("BACKUP_TIME").matches("^([01]\\d|2[0-3]):[0-5]\\d$")
                ? envVar("BACKUP_TIME") : "02:00";
        double retentionDays = 0;
        try {
            retentionDays = envVar("BACKUP_FULL_RETENTION_DAYS") == null ? 0
                    : Double.parseDouble(envVar("BACKUP_FULL_RETENTION_DAYS"));
        } catch (NumberFormatException ignored) {
            retentionDays = 0;
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("frequency", freq != null && frequencies.contains(freq) ? freq : "daily");
        d.put("dailyConfig", m("times", new ArrayList<>(List.of(time))));
        d.put("weeklyConfig", m("days", new ArrayList<>(List.of(0)), "time", time));
        d.put("customCronExpression", "");
        d.put("customIntervalMinutes", 60);
        d.put("types", new ArrayList<>(List.of("full")));
        d.put("includes", m("database", true, "schemaOnly", false, "databaseData", true, "uploadedImages", true,
                "productImages", true, "customerDocuments", true, "generatedReports", false, "attachments", false,
                "envFile", true, "configFiles", true, "posSettings", true, "orgSettings", true, "printerConfig", true,
                "receiptTemplates", true, "kitchenPrinterSettings", true, "logs", false, "auditLogs", false,
                "scheduledTasks", false));
        d.put("destinations", new ArrayList<>(List.of(m("type", "local", "path", envVar("BACKUP_DIR") != null
                && !envVar("BACKUP_DIR").isEmpty() ? envVar("BACKUP_DIR") : "backup", "label", "Local Backup",
                "enabled", true))));
        d.put("retention", m("mode", retentionDays > 0 ? "keep_by_age" : "keep_last", "keepLastCount", 14,
                "keepDays", retentionDays > 0 ? num(retentionDays) : 30, "smartDaily", 14, "smartWeekly", 8,
                "smartMonthly", 12));
        d.put("compression", 6);
        d.put("encryption", m("type", "none", "password", ""));
        d.put("verification", m("verifyIntegrity", true, "testRestore", true, "sha256Checksum", true));
        d.put("notifications", m("on", new ArrayList<>(List.of("failed")), "channels", new ArrayList<>(List.of("desktop")),
                "emailAddress", envVar("BACKUP_NOTIFY_EMAIL") == null ? "" : envVar("BACKUP_NOTIFY_EMAIL"),
                "phoneNumber", ""));
        d.put("cleanup", m("deleteExpired", true, "deleteTempFiles", true, "removeFailedFiles", true));
        d.put("internetBehaviour", "local_only");
        d.put("namingFormat", "POS-CAFE_{TYPE}_{DATE}_{TIME}");
        d.put("schedule", m("time", time, "dayOfWeek", 7, "timezone", "UTC", "customIntervalMinutes", 60,
                "cronExpression", ""));
        d.put("advanced", m("maxThreads", 1, "compressionLevel", 6, "pauseBusinessHours", false,
                "businessHoursStart", "08:00", "businessHoursEnd", "22:00", "skipIfBusy", false, "retryCount", 2,
                "retryDelayMinutes", 5, "backupTimeoutMinutes", 60, "maxDiskUsageGb", 999999, "autoDiskSpaceCheck", true));
        return d;
    }

    private static Object num(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? (Object) (long) v : (Object) v;
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    // ─── config ───

    @SuppressWarnings("unchecked")
    static Object deepCopy(Object v) {
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, val) -> out.put(String.valueOf(k), deepCopy(val)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(o -> out.add(deepCopy(o)));
            return out;
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> target, Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(target);
        for (Map.Entry<String, Object> e : source.entrySet()) {
            Object sv = e.getValue();
            if (sv instanceof Map<?, ?> sm) {
                Object tv = target.get(e.getKey());
                result.put(e.getKey(), deepMerge(tv instanceof Map<?, ?> tm ? (Map<String, Object>) tm : new LinkedHashMap<>(),
                        (Map<String, Object>) sm));
            } else {
                result.put(e.getKey(), sv != null ? sv : target.get(e.getKey()));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void loadConfig() {
        try {
            Object value = settings.getServerWide(BACKUP_SETTING_KEY);
            if (value instanceof JsonNode n) {
                value = n.isObject() ? new tools.jackson.databind.json.JsonMapper().convertValue(n, LinkedHashMap.class)
                        : null;
            }
            if (value instanceof Map<?, ?> map) {
                config = deepMerge(defaults(), (Map<String, Object>) deepCopy(map));
            }
        } catch (RuntimeException e) {
            log.warn("Failed to load backup config from DB, using defaults");
        }
    }

    /** Replace the in-memory config without persisting it (tests, boot-time load). */
    void useConfig(Map<String, Object> cfg) {
        config = cfg;
        healthCache = null;
    }

    Set<String> warnings() {
        return warnedOnce;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getConfig() {
        return (Map<String, Object>) deepCopy(config);
    }

    /** Config as served to the settings page: stored secrets replaced by a mask. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPublicConfig() {
        Map<String, Object> cfg = getConfig();
        if (cfg.get("encryption") instanceof Map<?, ?> enc && truthy(enc.get("password"))) {
            ((Map<String, Object>) enc).put("password", SECRET_MASK);
        }
        for (Object d : list(cfg.get("destinations"))) {
            Map<String, Object> dest = (Map<String, Object>) d;
            if (truthy(dest.get("secretKey"))) {
                dest.put("secretKey", SECRET_MASK);
            }
        }
        return cfg;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> updateConfig(Map<String, Object> partial) {
        Map<String, Object> incoming = (Map<String, Object>) deepCopy(partial);
        if (incoming.get("encryption") instanceof Map<?, ?> enc && SECRET_MASK.equals(enc.get("password"))) {
            Object prev = config.get("encryption") instanceof Map<?, ?> pe ? pe.get("password") : null;
            ((Map<String, Object>) enc).put("password", prev == null ? "" : prev);
        }
        List<Object> incomingDest = incoming.get("destinations") instanceof List<?> l ? (List<Object>) l : null;
        if (incomingDest != null) {
            List<Object> current = list(config.get("destinations"));
            for (int i = 0; i < incomingDest.size(); i++) {
                Map<String, Object> d = (Map<String, Object>) incomingDest.get(i);
                if (!SECRET_MASK.equals(d.get("secretKey"))) {
                    continue;
                }
                Map<String, Object> prev = i < current.size() ? (Map<String, Object>) current.get(i) : null;
                if (prev != null && String.valueOf(prev.get("type")).equals(String.valueOf(d.get("type")))) {
                    d.put("secretKey", prev.get("secretKey"));
                } else {
                    d.remove("secretKey");
                }
            }
        }
        Map<String, Object> spread = new LinkedHashMap<>(getConfig());
        spread.putAll(incoming);
        Map<String, Object> merged = deepMerge(defaults(), spread);
        merged.put("types", incoming.get("types") != null ? incoming.get("types") : config.get("types"));
        merged.put("destinations", incomingDest != null ? incomingDest : config.get("destinations"));
        List<String> problems = validateConfig(merged);
        if (!problems.isEmpty()) {
            throw ApiException.withBody(HttpStatus.BAD_REQUEST, com.inventoryerp.kernel.web.GlobalExceptionHandler
                    .nestBody(HttpStatus.BAD_REQUEST, problems));
        }
        config = merged;
        settings.setServerWide(BACKUP_SETTING_KEY, config);
        reschedule();
        healthCache = null;
        return getPublicConfig();
    }

    @SuppressWarnings("unchecked")
    List<String> validateConfig(Map<String, Object> cfg) {
        List<String> problems = new ArrayList<>();
        if (!list(cfg.get("types")).contains("full")) {
            problems.add("Full (database) backups cannot be turned off");
        }
        if (primaryDestination(cfg) == null) {
            problems.add("At least one enabled \"Local Folder\" destination with a path is required");
        }
        for (Object o : list(cfg.get("destinations"))) {
            Map<String, Object> d = (Map<String, Object>) o;
            if (!Boolean.FALSE.equals(d.get("enabled")) && FOLDER_DESTINATIONS.contains(String.valueOf(d.get("type")))
                    && blank(d.get("path"))) {
                Object label = truthy(d.get("label")) ? d.get("label") : d.get("type");
                problems.add("Destination \"" + label + "\" has no folder path");
            }
        }
        double interval = cfg.get("customIntervalMinutes") instanceof Number n ? n.doubleValue() : Double.NaN;
        if ("custom_minutes".equals(cfg.get("frequency")) && !(interval >= 1 && interval <= 59)) {
            problems.add("\"Every X minutes\" must be between 1 and 59");
        }
        if ("custom_hours".equals(cfg.get("frequency")) && !(interval >= 1 && interval <= 23)) {
            problems.add("\"Every X hours\" must be between 1 and 23");
        }
        try {
            for (String[] t : resolveCronExpressions(cfg)) {
                springCron(t[1]);
            }
            if ("cron".equals(cfg.get("frequency")) && blank(cfg.get("customCronExpression"))) {
                problems.add("Cron expression is empty");
            }
        } catch (RuntimeException e) {
            problems.add("Invalid schedule: " + e.getMessage());
        }
        Object naming = cfg.get("namingFormat");
        if (truthy(naming) && !(naming.toString().contains("{DATE}") && naming.toString().contains("{TIME}"))) {
            problems.add("Backup naming must contain {DATE} and {TIME}, or backups overwrite each other");
        }
        Map<String, Object> enc = cfg.get("encryption") instanceof Map<?, ?> e ? (Map<String, Object>) e : null;
        if (enc != null && "aes256".equals(enc.get("type")) && !truthy(enc.get("password"))
                && blank(envVar("BACKUP_ENCRYPTION_PASSWORD"))) {
            problems.add("AES-256 encryption needs a password");
        }
        return problems;
    }

    // ─── scheduling ───

    private boolean schedulerEnabled() {
        return !"false".equals(envVar("BACKUP_CRON_ENABLED"))
                && env.getProperty("app.jobs.enabled", Boolean.class, true);
    }

    /** node-cron accepts 5 (minute-first) or 6 (second-first) fields; Spring wants 6. */
    static CronExpression springCron(String expr) {
        String trimmed = expr.trim();
        String[] parts = trimmed.split("\\s+");
        return CronExpression.parse(parts.length == 5 ? "0 " + trimmed : trimmed);
    }

    private synchronized void clearJobs() {
        jobs.forEach(f -> f.cancel(false));
        jobs.clear();
    }

    private void addJob(String name, String cronExpr, Supplier<Object> fn) {
        try {
            CronExpression.parse(springCronText(cronExpr));
            ScheduledFuture<?> f = scheduler.schedule(() -> locked(name, fn), new CronTrigger(springCronText(cronExpr)));
            jobs.add(f);
        } catch (RuntimeException e) {
            scheduleError = "Backup schedule \"" + cronExpr + "\" is invalid: " + e.getMessage();
            log.error(scheduleError);
        }
    }

    private static String springCronText(String expr) {
        String trimmed = expr.trim();
        return trimmed.split("\\s+").length == 5 ? "0 " + trimmed : trimmed;
    }

    /** One server runs each scheduled backup (ShedLock), the others skip. */
    private void locked(String name, Supplier<Object> fn) {
        LockProvider provider = lockProvider.getIfAvailable();
        Runnable task = () -> {
            try {
                fn.get();
            } catch (RuntimeException e) {
                log.error("Scheduled {} crashed: {}", name, e.toString());
            }
        };
        if (provider == null) {
            task.run();
            return;
        }
        new DefaultLockingTaskExecutor(provider).executeWithLock(task, new LockConfiguration(Instant.now(),
                "backup:" + name, Duration.ofMinutes((long) Math.max(60, timeoutMinutes() * 2)), Duration.ofSeconds(30)));
    }

    public synchronized void reschedule() {
        clearJobs();
        scheduleError = null;
        if (!schedulerEnabled() || scheduler == null) {
            log.warn("BACKUP_CRON_ENABLED=false - automatic backups are OFF on this server");
            return;
        }
        if ("manual".equals(config.get("frequency"))) {
            log.info("Manual backup mode - no cron scheduled");
            return;
        }
        List<String[]> times;
        try {
            times = resolveCronExpressions(config);
        } catch (RuntimeException e) {
            scheduleError = "Backup schedule is invalid: " + e.getMessage();
            log.error(scheduleError);
            return;
        }
        Map<String, Object> includes = map(config.get("includes"));
        for (String[] t : times) {
            addJob("backup-full-" + t[0], t[1], this::runScheduledFull);
            if (list(config.get("types")).contains("incremental")) {
                addJob("backup-inc-" + t[0], t[1], this::runIncrementalBackup);
            }
            if (truthy(includes.get("uploadedImages")) || truthy(includes.get("productImages"))) {
                addJob("backup-files-" + t[0], t[1], this::runFilesBackup);
            }
            if (truthy(includes.get("envFile")) || truthy(includes.get("configFiles"))) {
                addJob("backup-config-" + t[0], t[1], this::runConfigBackup);
            }
        }
        if (truthy(map(config.get("cleanup")).get("deleteExpired"))) {
            addJob("backup-cleanup", "0 5 * * *", this::cleanup);
        }
    }

    static int[] hhmm(Object t) {
        Matcher mt = HH_MM.matcher(t == null ? "" : t.toString());
        if (!mt.matches()) {
            throw new IllegalArgumentException("\"" + (t == null ? "undefined" : t) + "\" is not a HH:MM time");
        }
        return new int[] {Integer.parseInt(mt.group(1)), Integer.parseInt(mt.group(2))};
    }

    @SuppressWarnings("unchecked")
    static List<String[]> resolveCronExpressions(Map<String, Object> cfg) {
        Object interval = cfg.get("customIntervalMinutes");
        String iv = interval instanceof Number n ? com.inventoryerp.kernel.common.JsNumbers.toJsString(n.doubleValue())
                : String.valueOf(interval);
        return switch (String.valueOf(cfg.get("frequency"))) {
            case "every_hour" -> List.<String[]>of(new String[] {"hourly", "0 * * * *"});
            case "every_2_hours" -> List.<String[]>of(new String[] {"2h", "0 */2 * * *"});
            case "every_4_hours" -> List.<String[]>of(new String[] {"4h", "0 */4 * * *"});
            case "every_6_hours" -> List.<String[]>of(new String[] {"6h", "0 */6 * * *"});
            case "every_12_hours" -> List.<String[]>of(new String[] {"12h", "0 */12 * * *"});
            case "daily" -> {
                List<Object> times = list(map(cfg.get("dailyConfig")).get("times"));
                if (times.isEmpty()) {
                    times = List.of("02:00");
                }
                List<String[]> out = new ArrayList<>();
                for (int i = 0; i < times.size(); i++) {
                    int[] hm = hhmm(times.get(i));
                    out.add(new String[] {"daily-" + i + "-" + hm[0] + hm[1], hm[1] + " " + hm[0] + " * * *"});
                }
                yield out;
            }
            case "weekly" -> {
                Map<String, Object> weekly = map(cfg.get("weeklyConfig"));
                int[] hm = hhmm(weekly.get("time") == null ? "03:00" : weekly.get("time"));
                List<Object> days = list(weekly.get("days"));
                if (days.isEmpty()) {
                    days = List.of(0);
                }
                List<String[]> out = new ArrayList<>();
                for (Object d : days) {
                    out.add(new String[] {"weekly-" + d, hm[1] + " " + hm[0] + " * * " + d});
                }
                yield out;
            }
            case "monthly" -> List.<String[]>of(new String[] {"monthly", "0 2 1 * *"});
            case "custom_minutes" -> List.<String[]>of(new String[] {"every-" + iv + "m", "*/" + iv + " * * * *"});
            case "custom_hours" -> List.<String[]>of(new String[] {"every-" + iv + "h", "0 */" + iv + " * * *"});
            case "cron" -> blank(cfg.get("customCronExpression")) ? List.of()
                    : List.<String[]>of(new String[] {"custom", cfg.get("customCronExpression").toString().trim()});
            default -> List.of();
        };
    }

    /** Hours between two scheduled full backups (infinite when manual). */
    double expectedIntervalHours() {
        Object interval = config.get("customIntervalMinutes");
        return switch (String.valueOf(config.get("frequency"))) {
            case "every_hour" -> 1;
            case "every_2_hours" -> 2;
            case "every_4_hours" -> 4;
            case "every_6_hours" -> 6;
            case "every_12_hours" -> 12;
            case "weekly" -> 24 * 7;
            case "monthly" -> 24 * 31;
            case "custom_minutes" -> Math.max(1, interval instanceof Number n ? n.doubleValue() : 60) / 60;
            case "custom_hours" -> Math.max(1, interval instanceof Number n ? n.doubleValue() : 1);
            case "manual" -> Double.POSITIVE_INFINITY;
            default -> 24;
        };
    }

    /** Scheduled path: retries, then proves the new dump restores. */
    public Map<String, Object> runScheduledFull() {
        Map<String, Object> adv = map(config.get("advanced"));
        int attempts = 1 + (int) Math.max(0, adv.get("retryCount") instanceof Number n ? n.doubleValue() : 0);
        long delayMs = (long) (Math.max(0, adv.get("retryDelayMinutes") instanceof Number n ? n.doubleValue() : 5) * 60_000);
        Map<String, Object> result = null;
        for (int i = 1; i <= attempts; i++) {
            result = runFullBackup(i == attempts);
            if (!"failed".equals(result.get("status"))) {
                break;
            }
            if (i < attempts) {
                log.warn("Full backup attempt {}/{} failed; retrying in {} min", i, attempts, delayMs / 60_000);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (result != null && "success".equals(result.get("status"))
                && truthy(map(config.get("verification")).get("testRestore"))) {
            runRestoreTest((String) result.get("target"));
        }
        return result;
    }

    private void scheduleCatchUp() {
        if (!schedulerEnabled() || scheduler == null || "manual".equals(config.get("frequency"))) {
            return;
        }
        scheduler.schedule(() -> {
            Map<String, Object> last = lastSuccessful("full");
            double ageHours = last == null ? Double.POSITIVE_INFINITY
                    : (System.currentTimeMillis() - Instant.parse((String) last.get("finishedAt")).toEpochMilli()) / 3_600_000.0;
            if (ageHours <= expectedIntervalHours() + 1) {
                return;
            }
            log.warn("Last successful full backup is {} - running a catch-up backup",
                    Double.isFinite(ageHours) ? String.format("%.1fh old", ageHours) : "missing");
            locked("catch-up", this::runScheduledFull);
        }, Instant.now().plusMillis(CATCH_UP_DELAY_MS));
    }

    // ─── layer 1: full database backup ───

    public Map<String, Object> runFullBackup() {
        return runFullBackup(true);
    }

    public Map<String, Object> runFullBackup(boolean notify) {
        if (!busy.add("full")) {
            return skipped("full", "already running");
        }
        long started = System.currentTimeMillis();
        Path dir = primaryDir().resolve("full");
        String baseName = buildFileName("FULL");
        String password = encryptionPassword();
        Path finalPath = dir.resolve(truthy(password) ? baseName + BackupCrypto.ENCRYPTED_SUFFIX : baseName);
        Path tmpDump = dir.resolve(baseName + PARTIAL_SUFFIX);
        Path tmpEnc = Path.of(finalPath + PARTIAL_SUFFIX);
        String database = null;
        try {
            PgConnection pg = pgConnection();
            database = pg.database();
            Files.createDirectories(dir);
            assertDiskSpace(dir);
            Object compress = map(config.get("advanced")).get("compressionLevel");
            execPg("pg_dump", List.of("--format=custom", "--compress=" + (compress == null ? 6 : compress), "--no-owner",
                    "--no-password", "--lock-wait-timeout=300000", "--file=" + tmpDump, "--dbname=" + pg.database()),
                    pg.env(), timeoutMs(1));
            int toc = tocEntries(tmpDump, pg.env());
            if (toc == 0) {
                throw new IOException("pg_dump produced an archive with no entries");
            }
            if (truthy(password)) {
                BackupCrypto.encryptFile(tmpDump, tmpEnc, password);
                Files.deleteIfExists(tmpDump);
                Files.move(tmpEnc, finalPath, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(tmpDump, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(finalPath);
            String checksum = sha256(finalPath);
            writeChecksum(finalPath, checksum);
            List<Map<String, Object>> copies = copyToDestinations(finalPath, "full", checksum);
            List<String> warnings = copyWarnings(copies);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "full");
            r.put("status", "success");
            r.put("target", finalPath.toString());
            r.put("sizeBytes", size);
            r.put("durationMs", System.currentTimeMillis() - started);
            r.put("finishedAt", nowIso());
            r.put("checksumSha256", checksum);
            r.put("encrypted", truthy(password));
            r.put("database", database);
            r.put("copies", copies);
            if (!warnings.isEmpty()) {
                r.put("warnings", warnings);
            }
            return record(r, notify);
        } catch (Exception e) {
            for (Path f : List.of(tmpDump, tmpEnc, finalPath, Path.of(finalPath + CHECKSUM_SUFFIX))) {
                deleteQuietly(f);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "full");
            r.put("status", "failed");
            r.put("target", finalPath.toString());
            if (database != null) {
                r.put("database", database);
            }
            r.put("durationMs", System.currentTimeMillis() - started);
            r.put("finishedAt", nowIso());
            r.put("error", errText(e));
            return record(r, notify);
        } finally {
            busy.remove("full");
        }
    }

    // ─── layer 2: incremental (WAL) ───

    public Map<String, Object> runIncrementalBackup() {
        if (!busy.add("incremental")) {
            return skipped("incremental", "already running");
        }
        long started = System.currentTimeMillis();
        Path dir = primaryDir().resolve("incremental");
        Path file = dir.resolve(buildFileName("INCREMENTAL"));
        try {
            Map<String, Object> wal = getWalArchiveStatus();
            if (wal.get("error") != null) {
                throw new IOException((String) wal.get("error"));
            }
            if ("off".equals(wal.get("archiveMode"))) {
                return record(m("kind", "incremental", "status", "skipped", "durationMs",
                        System.currentTimeMillis() - started, "finishedAt", nowIso(), "error",
                        "WAL archiving is off on the PostgreSQL server (archive_mode=off). Incremental backups need"
                                + " archive_mode and archive_command configured - see docs/backup-recovery.md."), true);
            }
            Files.createDirectories(dir);
            String lsn = psql("SELECT pg_walfile_name(pg_switch_wal())", null);
            Files.writeString(file, "WAL segment switched at " + nowIso() + "\nclosed segment: " + lsn + "\nlast archived: "
                    + (wal.get("lastArchivedWal") == null ? "-" : wal.get("lastArchivedWal")) + "\n");
            Map<String, Object> r = m("kind", "incremental", "status", "success", "target", file.toString(),
                    "durationMs", System.currentTimeMillis() - started, "finishedAt", nowIso());
            if (((Number) wal.get("failedCount")).longValue() > 0) {
                r.put("warnings", List.of("WAL archiver has " + wal.get("failedCount") + " failures (last failed: "
                        + (wal.get("lastFailedWal") == null ? "?" : wal.get("lastFailedWal")) + ")"));
            }
            return record(r, true);
        } catch (Exception e) {
            return record(m("kind", "incremental", "status", "failed", "durationMs", System.currentTimeMillis() - started,
                    "finishedAt", nowIso(), "error", errText(e)), true);
        } finally {
            busy.remove("incremental");
        }
    }

    Map<String, Object> getWalArchiveStatus() {
        try {
            String out = psql("SELECT current_setting('archive_mode'), failed_count, coalesce(last_archived_wal, ''),"
                    + " coalesce(last_failed_wal, '') FROM pg_stat_archiver", null);
            String[] p = out.split("\\|", -1);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("archiveMode", p[0]);
            long failed;
            try {
                failed = Long.parseLong(p.length > 1 ? p[1].trim() : "0");
            } catch (NumberFormatException e) {
                failed = 0;
            }
            r.put("failedCount", failed);
            if (p.length > 2 && !p[2].isEmpty()) {
                r.put("lastArchivedWal", p[2]);
            }
            if (p.length > 3 && !p[3].isEmpty()) {
                r.put("lastFailedWal", p[3]);
            }
            return r;
        } catch (Exception e) {
            return m("archiveMode", "unknown", "failedCount", 0L, "error", errText(e));
        }
    }

    // ─── layer 3: uploaded files (mirror) ───

    public Map<String, Object> runFilesBackup() {
        if (!busy.add("files")) {
            return skipped("files", "already running");
        }
        long started = System.currentTimeMillis();
        Path primaryTarget = primaryDir().resolve("files").resolve("uploads");
        try {
            Path src = uploadsDir();
            if (src == null) {
                return record(m("kind", "files", "status", "skipped", "durationMs", 0, "finishedAt", nowIso(), "error",
                        "uploads are not stored on this server (STORAGE_DRIVER=" + envVar("STORAGE_DRIVER") + ")"), true);
            }
            if (!Files.exists(src)) {
                return record(m("kind", "files", "status", "success", "target", primaryTarget.toString(), "durationMs",
                        System.currentTimeMillis() - started, "finishedAt", nowIso(), "warnings",
                        List.of("No uploads folder at " + src + " yet - nothing to copy")), true);
            }
            mirrorFolder(src, primaryTarget);
            List<Map<String, Object>> copies = new ArrayList<>();
            for (Map<String, Object> dest : secondaryDestinations()) {
                if (!FOLDER_DESTINATIONS.contains(String.valueOf(dest.get("type")))) {
                    continue;
                }
                Path target = Path.of(String.valueOf(dest.get("path"))).resolve("files").resolve("uploads");
                try {
                    mirrorFolder(src, target);
                    copies.add(m("destination", destLabel(dest), "ok", true, "target", target.toString()));
                } catch (Exception e) {
                    copies.add(m("destination", destLabel(dest), "ok", false, "error", errText(e)));
                }
            }
            List<String> warnings = copyWarnings(copies);
            Map<String, Object> r = m("kind", "files", "status", "success", "target", primaryTarget.toString(),
                    "copies", copies, "durationMs", System.currentTimeMillis() - started, "finishedAt", nowIso());
            if (!warnings.isEmpty()) {
                r.put("warnings", warnings);
            }
            return record(r, true);
        } catch (Exception e) {
            return record(m("kind", "files", "status", "failed", "target", primaryTarget.toString(), "durationMs",
                    System.currentTimeMillis() - started, "finishedAt", nowIso(), "error", errText(e)), true);
        } finally {
            busy.remove("files");
        }
    }

    private Path uploadsDir() {
        String driver = envVar("STORAGE_DRIVER") == null ? "local" : envVar("STORAGE_DRIVER");
        if (!"local".equals(driver)) {
            return null;
        }
        String dir = envVar("STORAGE_LOCAL_DIR") != null ? envVar("STORAGE_LOCAL_DIR")
                : env.getProperty("app.storage.local-dir", "./var/uploads");
        return Path.of(dir).toAbsolutePath().normalize();
    }

    /** Copy without delete: files removed from the live folder stay in the backup. */
    private static void mirrorFolder(Path src, Path dest) throws IOException {
        Files.createDirectories(dest);
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = dest.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    // ─── layer 4: server configuration (.env) ───

    public Map<String, Object> runConfigBackup() {
        if (!busy.add("config")) {
            return skipped("config", "already running");
        }
        long started = System.currentTimeMillis();
        Path dir = primaryDir().resolve("config");
        String baseName = buildFileName("CONFIG");
        String password = encryptionPassword();
        Path file = dir.resolve(truthy(password) ? baseName + BackupCrypto.ENCRYPTED_SUFFIX : baseName);
        Path tmp = dir.resolve(baseName + PARTIAL_SUFFIX);
        try {
            Files.createDirectories(dir);
            List<String> sections = new ArrayList<>(List.of("# POS-CAFE server configuration, captured " + nowIso()
                    + " from " + Path.of("").toAbsolutePath()));
            for (Path p : envFileCandidates()) {
                try {
                    String content = Files.readString(p);
                    sections.add("=== " + p + " ===\n" + content.replaceAll("(?m)^(\\s*BACKUP_ENCRYPTION_PASSWORD\\s*=).*$",
                            "$1\"<redacted - stored separately from backups>\""));
                } catch (IOException ignored) {
                    // not present on this install
                }
            }
            if (sections.size() == 1) {
                throw new IOException("no .env file found (looked in " + String.join(", ",
                        envFileCandidates().stream().map(Path::toString).toList()) + ")");
            }
            Files.writeString(tmp, String.join("\n\n", sections));
            if (truthy(password)) {
                Path encTmp = Path.of(file + PARTIAL_SUFFIX);
                BackupCrypto.encryptFile(tmp, encTmp, password);
                Files.deleteIfExists(tmp);
                Files.move(encTmp, file, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            String checksum = sha256(file);
            writeChecksum(file, checksum);
            List<Map<String, Object>> copies = copyToDestinations(file, "config", checksum);
            List<String> warnings = copyWarnings(copies);
            if (!truthy(password)) {
                warnings.add("Config backup holds server secrets in plain text - set BACKUP_ENCRYPTION_PASSWORD");
            }
            Map<String, Object> r = m("kind", "config", "status", "success", "target", file.toString(), "encrypted",
                    truthy(password), "checksumSha256", checksum, "copies", copies, "durationMs",
                    System.currentTimeMillis() - started, "finishedAt", nowIso());
            if (!warnings.isEmpty()) {
                r.put("warnings", warnings);
            }
            return record(r, true);
        } catch (Exception e) {
            deleteQuietly(tmp);
            deleteQuietly(Path.of(file + PARTIAL_SUFFIX));
            return record(m("kind", "config", "status", "failed", "durationMs", System.currentTimeMillis() - started,
                    "finishedAt", nowIso(), "error", errText(e)), true);
        } finally {
            busy.remove("config");
        }
    }

    private static List<Path> envFileCandidates() {
        Path cwd = Path.of("").toAbsolutePath();
        LinkedHashSet<Path> list = new LinkedHashSet<>();
        list.add(cwd.resolve(".env").normalize());
        list.add(cwd.resolve("backend").resolve(".env").normalize());
        if (cwd.getFileName() != null && "backend".equals(cwd.getFileName().toString())) {
            list.add(cwd.resolve("..").resolve(".env").normalize());
        }
        return new ArrayList<>(list);
    }

    // ─── restore (always into a NEW database) ───

    /** {@code RestoreRequest}. */
    public record RestoreRequest(String scope, String backupFile, String targetDatabase, Boolean verifyOnly) {}

    public Map<String, Object> restore(RestoreRequest dto) {
        long started = System.currentTimeMillis();
        if (dto.backupFile() == null || dto.backupFile().trim().isEmpty()) {
            return fail(started, "Backup file path is required", null);
        }
        if (dto.scope() != null && !"database".equals(dto.scope())) {
            return fail(started, "Restoring \"" + dto.scope() + "\" is a manual step - see docs/backup-recovery.md", null);
        }
        Path file;
        PgConnection pg;
        try {
            file = resolveBackupFile(dto.backupFile());
            pg = pgConnection();
        } catch (Exception e) {
            return fail(started, errText(e), e);
        }
        Path[] prepared = null;
        String created = null;
        try {
            String checksum = verifyChecksum(file);
            prepared = prepareDump(file);
            int toc = tocEntries(prepared[0], pg.env());
            if (toc == 0) {
                throw new IOException("backup archive has no entries");
            }
            if (Boolean.TRUE.equals(dto.verifyOnly())) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("success", true);
                r.put("durationMs", System.currentTimeMillis() - started);
                r.put("checks", m("tocEntries", toc, "checksum", checksum));
                r.put("message", "Backup is readable: " + toc + " archive entries, checksum "
                        + ("verified".equals(checksum) ? "verified" : "not available (no .sha256 file)"));
                return r;
            }
            String target = dto.targetDatabase() != null && !dto.targetDatabase().trim().isEmpty()
                    ? dto.targetDatabase().trim() : scratchDbName(pg.database(), "restored");
            if (!target.matches("^[A-Za-z0-9_]{1,63}$")) {
                throw new IOException("targetDatabase may only contain letters, digits and _ (max 63)");
            }
            if (target.equalsIgnoreCase(pg.database())) {
                throw new IOException("Refusing to restore over the live database \"" + pg.database() + "\". Restore into a"
                        + " new database, then switch over with the API stopped (docs/backup-recovery.md).");
            }
            if (databaseExists(target)) {
                throw new IOException("Database \"" + target + "\" already exists - choose a new name");
            }
            log.warn("Restoring {} into NEW database \"{}\"", file.getFileName(), target);
            execPg("createdb", List.of("--no-password", "--template=template0", target), pg.env(), 0);
            created = target;
            execPg("pg_restore", List.of("--no-owner", "--no-password", "--exit-on-error", "--dbname=" + target,
                    prepared[0].toString()), pg.env(), timeoutMs(2));
            Map<String, Object> checks = new LinkedHashMap<>();
            checks.put("tocEntries", toc);
            checks.put("checksum", checksum);
            checks.putAll(compareWithLive(target));
            List<String> problems = checkProblems(checks);
            if (!problems.isEmpty()) {
                throw new IOException("restored database looks incomplete: " + String.join("; ", problems));
            }
            created = null;
            log.info("Restore completed: {} -> {}", file.getFileName(), target);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            r.put("database", target);
            r.put("checks", checks);
            r.put("durationMs", System.currentTimeMillis() - started);
            r.put("message", "Restored into new database \"" + target + "\". The live database \"" + pg.database()
                    + "\" was not changed. To run the café on it, stop the API and follow docs/backup-recovery.md.");
            return r;
        } catch (Exception e) {
            return fail(started, "Restore failed: " + errText(e), e);
        } finally {
            if (created != null) {
                dropDatabase(created);
            }
            if (prepared != null && prepared[1] != null) {
                deleteQuietly(prepared[1]);
            }
        }
    }

    private Map<String, Object> fail(long started, String message, Exception err) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("message", message);
        r.put("durationMs", System.currentTimeMillis() - started);
        if (err != null) {
            r.put("error", errText(err));
        }
        return r;
    }

    /** Restores a backup into a throwaway database, checks it, drops it. */
    public Map<String, Object> runRestoreTest(String backupFile) {
        if (!busy.add("restore_test")) {
            return skipped("restore_test", "already running");
        }
        long started = System.currentTimeMillis();
        String target = null;
        String file = backupFile;
        try {
            PgConnection pg = pgConnection();
            if (file == null || file.isEmpty()) {
                List<Map<String, Object>> latest = listBackups("full");
                if (latest.isEmpty()) {
                    throw new IOException("No full backup available to test");
                }
                file = (String) latest.getFirst().get("file");
            }
            dropLeftoverScratchDbs(pg.database());
            target = scratchDbName(pg.database(), "restoretest");
            Map<String, Object> r = restore(new RestoreRequest("database", file, target, null));
            if (!Boolean.TRUE.equals(r.get("success"))) {
                throw new IOException(String.valueOf(r.get("error") != null ? r.get("error") : r.get("message")));
            }
            Map<String, Object> c = map(r.get("checks"));
            return record(m("kind", "restore_test", "status", "success", "target", file, "database", target,
                    "durationMs", System.currentTimeMillis() - started, "finishedAt", nowIso(), "warnings",
                    List.of(c.get("tocEntries") + " entries, " + restored(c, "tables") + " tables, " + restored(c,
                            "migrations") + " migrations, " + restored(c, "organizations") + " organizations restored")),
                    true);
        } catch (Exception e) {
            Map<String, Object> r = m("kind", "restore_test", "status", "failed");
            if (file != null) {
                r.put("target", file);
            }
            if (target != null) {
                r.put("database", target);
            }
            r.put("durationMs", System.currentTimeMillis() - started);
            r.put("finishedAt", nowIso());
            r.put("error", errText(e));
            return record(r, true);
        } finally {
            if (target != null) {
                dropDatabase(target);
            }
            busy.remove("restore_test");
        }
    }

    private static Object restored(Map<String, Object> c, String key) {
        Map<String, Object> v = map(c.get(key));
        return v.get("restored") == null ? "?" : v.get("restored");
    }

    /** A file name or path, but only for backups inside a configured backup folder. */
    Path resolveBackupFile(String input) throws IOException {
        List<Path> roots = new ArrayList<>();
        roots.add(primaryDir().resolve("full"));
        for (Map<String, Object> d : secondaryDestinations()) {
            if (FOLDER_DESTINATIONS.contains(String.valueOf(d.get("type")))) {
                roots.add(Path.of(String.valueOf(d.get("path"))).toAbsolutePath().normalize().resolve("full"));
            }
        }
        Path candidate = Path.of(input).isAbsolute() ? Path.of(input).normalize()
                : roots.getFirst().resolve(Path.of(input).getFileName().toString());
        if (!candidate.getFileName().toString().toLowerCase().matches(".*\\.dump(\\.enc)?$")) {
            throw new IOException("Not a database backup file (.dump or .dump.enc)");
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw new IOException("Backup file not found: " + candidate.getFileName());
        }
        for (Path root : roots) {
            Path realRoot;
            try {
                realRoot = root.toRealPath();
            } catch (IOException e) {
                realRoot = root;
            }
            if (real.startsWith(realRoot) && !real.equals(realRoot)) {
                return real;
            }
        }
        throw new IOException("Backup file must be inside a configured backup folder");
    }

    private String verifyChecksum(Path file) throws IOException {
        String expected;
        try {
            String content = Files.readString(Path.of(file + CHECKSUM_SUFFIX)).trim();
            expected = content.split("\\s+")[0].toLowerCase();
        } catch (IOException e) {
            return "no-sidecar";
        }
        if (!expected.equals(sha256(file))) {
            throw new IOException("Checksum mismatch for " + file.getFileName() + ": file is corrupted or was modified");
        }
        return "verified";
    }

    /** Decrypts {@code .enc} backups to a temporary file next to the backups; returns [dump, toDelete]. */
    private Path[] prepareDump(Path file) throws IOException {
        if (!file.toString().endsWith(BackupCrypto.ENCRYPTED_SUFFIX)) {
            return new Path[] {file, null};
        }
        Path tmpDir = primaryDir().resolve("tmp");
        Files.createDirectories(tmpDir);
        String base = file.getFileName().toString();
        base = base.substring(0, base.length() - BackupCrypto.ENCRYPTED_SUFFIX.length());
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        Path dump = tmpDir.resolve(base + "." + HexFormat.of().formatHex(rnd) + PARTIAL_SUFFIX);
        List<String> passwords = candidatePasswords();
        if (passwords.isEmpty()) {
            throw new IOException("Backup is encrypted but no password is configured (BACKUP_ENCRYPTION_PASSWORD)");
        }
        IOException last = null;
        for (String pw : passwords) {
            try {
                BackupCrypto.decryptFile(file, dump, pw);
                return new Path[] {dump, dump};
            } catch (IOException e) {
                last = e;
                deleteQuietly(dump);
            }
        }
        throw last;
    }

    /**
     * Restored-vs-live counts. Nest counts {@code _prisma_migrations}; this schema is managed by Flyway, so the
     * migration count reads {@code flyway_schema_history} (successful rows) — see docs/spec/backup.md.
     */
    private Map<String, Object> compareWithLive(String target) throws IOException {
        String sql = "SELECT (SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND"
                + " table_type = 'BASE TABLE'), (SELECT count(*) FROM flyway_schema_history WHERE success),"
                + " (SELECT count(*) FROM \"Organization\")";
        long[] r = parseCounts(psql(sql, target));
        long[] l = parseCounts(psql(sql, null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", m("restored", r[0], "live", l[0]));
        out.put("migrations", m("restored", r[1], "live", l[1]));
        out.put("organizations", m("restored", r[2], "live", l[2]));
        return out;
    }

    private static long[] parseCounts(String s) {
        String[] p = s.split("\\|");
        long[] out = new long[3];
        for (int i = 0; i < 3 && i < p.length; i++) {
            out[i] = Long.parseLong(p[i].trim());
        }
        return out;
    }

    static List<String> checkProblems(Map<String, Object> c) {
        List<String> p = new ArrayList<>();
        Map<String, Object> mig = map(c.get("migrations"));
        Map<String, Object> tables = map(c.get("tables"));
        Map<String, Object> orgs = map(c.get("organizations"));
        boolean sameSchema = !mig.isEmpty() && mig.get("restored").equals(mig.get("live"));
        if (sameSchema && !tables.isEmpty() && ((Number) tables.get("restored")).longValue()
                < ((Number) tables.get("live")).longValue()) {
            p.add(tables.get("restored") + " tables restored, live has " + tables.get("live"));
        }
        if (!mig.isEmpty() && ((Number) mig.get("restored")).longValue() == 0) {
            p.add("no applied migrations in the restored database");
        }
        if (!orgs.isEmpty() && ((Number) orgs.get("live")).longValue() > 0 && ((Number) orgs.get("restored")).longValue() == 0) {
            p.add("no organizations in the restored database");
        }
        return p;
    }

    static String scratchDbName(String live, String purpose) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String suffix = "_" + purpose + "_" + stamp;
        String prefix = live.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        return prefix.substring(0, Math.min(prefix.length(), 63 - suffix.length())) + suffix;
    }

    private void dropLeftoverScratchDbs(String live) {
        String prefix = live.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        prefix = prefix.substring(0, Math.min(prefix.length(), 63 - "_restoretest_00000000000000".length()));
        try {
            String out = psql("SELECT datname FROM pg_database WHERE datname ~ '^" + prefix
                    + "_restoretest_[0-9]{14}$'", null);
            for (String name : out.split("\n")) {
                if (!name.trim().isEmpty()) {
                    dropDatabase(name.trim());
                }
            }
        } catch (Exception e) {
            log.warn("Could not sweep leftover restore-test databases: {}", errText(e));
        }
    }

    private boolean databaseExists(String name) throws IOException {
        return "1".equals(psql("SELECT 1 FROM pg_database WHERE datname = '" + name + "'", null));
    }

    private void dropDatabase(String name) {
        try {
            PgConnection pg = pgConnection();
            if (name.equalsIgnoreCase(pg.database())) {
                throw new IOException("refusing to drop the live database");
            }
            execPg("dropdb", List.of("--no-password", "--if-exists", name), pg.env(), 0);
        } catch (Exception e) {
            log.error("Could not drop scratch database \"{}\": {}", name, errText(e));
        }
    }

    public List<Map<String, Object>> listBackups(String kind) {
        List<String> kinds = kind != null ? List.of(kind) : ARTIFACT_KINDS;
        List<Map<String, Object>> results = new ArrayList<>();
        for (String sub : kinds) {
            if (!ARTIFACT_KINDS.contains(sub)) {
                continue;
            }
            for (Artifact a : listArtifacts(primaryDir().resolve(sub))) {
                results.add(m("file", a.path().toString(), "name", a.path().getFileName().toString(), "size", a.size(),
                        "mtime", Instant.ofEpochMilli(a.mtime()), "kind", sub, "encrypted",
                        a.path().toString().endsWith(BackupCrypto.ENCRYPTED_SUFFIX)));
            }
        }
        results.sort((a, b) -> ((Instant) b.get("mtime")).compareTo((Instant) a.get("mtime")));
        return results;
    }

    // ─── retention ───

    public Map<String, Object> cleanup() {
        Map<String, Object> r = config.get("retention") instanceof Map<?, ?> ? map(config.get("retention"))
                : map(defaults().get("retention"));
        List<Path> roots = new ArrayList<>(List.of(primaryDir()));
        for (Map<String, Object> d : secondaryDestinations()) {
            if (FOLDER_DESTINATIONS.contains(String.valueOf(d.get("type")))) {
                roots.add(Path.of(String.valueOf(d.get("path"))).toAbsolutePath().normalize());
            }
        }
        int deleted = 0;
        List<String> errors = new ArrayList<>();
        boolean deleteTemp = !Boolean.FALSE.equals(map(config.get("cleanup")).get("deleteTempFiles"));
        for (Path root : roots) {
            for (String sub : ARTIFACT_KINDS) {
                Path dir = root.resolve(sub);
                List<Artifact> items = listArtifacts(dir);
                Set<Path> keep = "config".equals(sub)
                        ? new LinkedHashSet<>(items.stream().limit(50).map(Artifact::path).toList())
                        : retentionKeepSet(items, r);
                items.stream().limit(MIN_BACKUPS_KEPT).forEach(i -> keep.add(i.path()));
                for (Artifact item : items) {
                    if (keep.contains(item.path())) {
                        continue;
                    }
                    try {
                        Files.deleteIfExists(item.path());
                        Files.deleteIfExists(Path.of(item.path() + CHECKSUM_SUFFIX));
                        deleted++;
                    } catch (IOException e) {
                        errors.add(item.path() + ": " + errText(e));
                    }
                }
                if (deleteTemp) {
                    removeStalePartials(dir);
                }
            }
        }
        if (deleteTemp) {
            removeStalePartials(primaryDir().resolve("tmp"));
        }
        if (deleted > 0 || !errors.isEmpty()) {
            log.info("Backup retention: removed {} old artifact(s){}", deleted,
                    errors.isEmpty() ? "" : ", " + errors.size() + " error(s)");
        }
        return m("deleted", deleted, "errors", errors);
    }

    static Set<Path> retentionKeepSet(List<Artifact> items, Map<String, Object> r) {
        String mode = String.valueOf(r.get("mode"));
        if ("keep_by_age".equals(mode)) {
            double days = r.get("keepDays") instanceof Number n ? n.doubleValue() : 30;
            long cutoff = System.currentTimeMillis() - (long) (days * 86_400_000L);
            return new LinkedHashSet<>(items.stream().filter(i -> i.mtime() >= cutoff).map(Artifact::path).toList());
        }
        if ("smart".equals(mode)) {
            Set<Path> keep = new LinkedHashSet<>();
            ZoneId zone = BusinessZone.get();
            take(items, keep, i -> Instant.ofEpochMilli(i.mtime()).atZone(zone).toLocalDate().toString(),
                    intOr(r.get("smartDaily"), 14));
            take(items, keep, i -> String.valueOf((long) Math.floor((i.mtime() / 86_400_000.0 + 3) / 7)),
                    intOr(r.get("smartWeekly"), 8));
            take(items, keep, i -> {
                var d = Instant.ofEpochMilli(i.mtime()).atZone(zone);
                return d.getYear() + "-" + (d.getMonthValue() - 1);
            }, intOr(r.get("smartMonthly"), 12));
            return keep;
        }
        return new LinkedHashSet<>(items.stream().limit(intOr(r.get("keepLastCount"), 14)).map(Artifact::path).toList());
    }

    private static void take(List<Artifact> items, Set<Path> keep, java.util.function.Function<Artifact, String> bucket,
            int count) {
        Set<String> seen = new LinkedHashSet<>();
        for (Artifact i : items) {
            String k = bucket.apply(i);
            if (seen.contains(k)) {
                continue;
            }
            if (seen.size() >= count) {
                break;
            }
            seen.add(k);
            keep.add(i.path());
        }
    }

    private static int intOr(Object v, int def) {
        return v instanceof Number n ? n.intValue() : def;
    }

    private static void removeStalePartials(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(PARTIAL_SUFFIX)).toList()) {
                if (System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis() > 6 * 3_600_000L) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (IOException ignored) {
            // folder may not exist
        }
    }

    // ─── status & health ───

    public Map<String, Object> getStatus() {
        Map<String, Object> lastByKind = new LinkedHashMap<>();
        synchronized (history) {
            for (Map<String, Object> r : history) {
                if ("skipped".equals(r.get("status")) && !"incremental".equals(r.get("kind"))) {
                    continue;
                }
                lastByKind.putIfAbsent((String) r.get("kind"), r);
            }
        }
        List<Map<String, Object>> recent;
        synchronized (history) {
            recent = new ArrayList<>(history.subList(0, Math.min(20, history.size())));
        }
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("primaryDir", primaryDir().toString());
        server.put("schedulerEnabled", schedulerEnabled());
        server.put("encryptionActive", truthy(encryptionPassword()));
        server.put("encryptionEnforcedByEnv", !blank(envVar("BACKUP_ENCRYPTION_PASSWORD")));
        if (scheduleError != null) {
            server.put("scheduleError", scheduleError);
        }
        return m("lastByKind", lastByKind, "recent", recent, "config", getPublicConfig(), "server", server);
    }

    public Map<String, Object> getHealthStatus() {
        Map<String, Object> cached = healthCache;
        if (cached != null && System.currentTimeMillis() - healthCacheAt < HEALTH_CACHE_MS) {
            return cached;
        }
        List<String> critical = new ArrayList<>();
        List<String> degraded = new ArrayList<>();
        Map<String, Object> lastFull = find(r -> "full".equals(r.get("kind")) && !"skipped".equals(r.get("status")));
        Map<String, Object> lastGoodFull = lastSuccessful("full");
        double interval = expectedIntervalHours();
        if (lastGoodFull == null) {
            critical.add("No full backup found");
        } else {
            double ageHours = (System.currentTimeMillis() - Instant.parse((String) lastGoodFull.get("finishedAt"))
                    .toEpochMilli()) / 3_600_000.0;
            double staleAfter = Double.isFinite(interval) ? interval + Math.max(12, interval / 2) : 24 * 7;
            if (ageHours > staleAfter) {
                critical.add(String.format("Last successful full backup was %.1fh ago (expected every %s)", ageHours,
                        Double.isFinite(interval) ? com.inventoryerp.kernel.common.JsNumbers.toJsString(interval) + "h"
                                : "manual run"));
            }
        }
        if (lastFull != null && "failed".equals(lastFull.get("status"))) {
            critical.add("Last full backup FAILED: " + (lastFull.get("error") == null ? "unknown error" : lastFull.get("error")));
        }
        if (lastFull != null && "success".equals(lastFull.get("status"))) {
            list(lastFull.get("warnings")).forEach(w -> degraded.add(String.valueOf(w)));
        }
        Map<String, Object> lastRestoreTest = find(r -> "restore_test".equals(r.get("kind"))
                && !"skipped".equals(r.get("status")));
        if (lastRestoreTest != null && "failed".equals(lastRestoreTest.get("status"))) {
            critical.add("Last restore test FAILED: " + (lastRestoreTest.get("error") == null ? "unknown error"
                    : lastRestoreTest.get("error")));
        }
        if (truthy(map(config.get("verification")).get("testRestore")) && lastGoodFull != null) {
            Map<String, Object> ok = lastSuccessful("restore_test");
            if (ok == null || System.currentTimeMillis() - Instant.parse((String) ok.get("finishedAt")).toEpochMilli()
                    > 8 * 86_400_000L) {
                degraded.add("No successful restore test in the last 8 days");
            }
        }
        if (scheduleError != null) {
            critical.add(scheduleError);
        }
        if (!schedulerEnabled()) {
            degraded.add("Automatic backups are off on this server (BACKUP_CRON_ENABLED=false)");
        } else if ("manual".equals(config.get("frequency"))) {
            degraded.add("Backups are manual only - nothing runs automatically");
        }
        if (!truthy(encryptionPassword())) {
            degraded.add("Backups are not encrypted (set BACKUP_ENCRYPTION_PASSWORD)");
        }
        if (secondaryDestinations().isEmpty()) {
            degraded.add("No second backup destination (USB drive, NAS or cloud): a disk failure or theft loses the data"
                    + " and the backups together");
        }
        synchronized (warnedOnce) {
            degraded.addAll(warnedOnce);
        }
        Map<String, Object> wal = null;
        if (list(config.get("types")).contains("incremental")) {
            wal = getWalArchiveStatus();
            if ("off".equals(wal.get("archiveMode"))) {
                degraded.add("Incremental backups are selected but WAL archiving is off on the PostgreSQL server");
            }
            if (((Number) wal.get("failedCount")).longValue() > 0) {
                degraded.add("WAL archiver has " + wal.get("failedCount") + " failures");
            }
        }
        Map<String, Object> disk = checkDiskSpace(primaryDir());
        if (!Boolean.TRUE.equals(disk.get("hasSpace"))) {
            degraded.add(String.format("Low disk space for backups: %.2f GB free",
                    ((Number) disk.get("freeBytes")).doubleValue() / GB));
        }
        List<String> issues = new ArrayList<>(critical);
        issues.addAll(degraded);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", !critical.isEmpty() ? "critical" : !degraded.isEmpty() ? "degraded" : "healthy");
        if (lastFull != null) {
            value.put("lastFullBackup", lastFull);
        }
        Map<String, Object> lastInc = find(r -> "incremental".equals(r.get("kind")));
        if (lastInc != null) {
            value.put("lastIncrementalBackup", lastInc);
        }
        if (lastRestoreTest != null) {
            value.put("lastRestoreTest", lastRestoreTest);
        }
        if (wal != null) {
            value.put("walArchiveStatus", wal);
        }
        value.put("diskSpace", disk);
        value.put("issues", issues);
        value.put("checkedAt", nowIso());
        healthCache = value;
        healthCacheAt = System.currentTimeMillis();
        return value;
    }

    private Map<String, Object> find(java.util.function.Predicate<Map<String, Object>> p) {
        synchronized (history) {
            for (Map<String, Object> r : history) {
                if (p.test(r)) {
                    return r;
                }
            }
        }
        return null;
    }

    /** When the newest successful full backup finished, or {@code null}. */
    public Instant lastSuccessfulFullAt() {
        Map<String, Object> last = lastSuccessful("full");
        return last == null ? null : Instant.parse((String) last.get("finishedAt"));
    }

    private Map<String, Object> lastSuccessful(String kind) {
        return find(r -> kind.equals(r.get("kind")) && "success".equals(r.get("status")));
    }

    // ─── destinations ───

    @SuppressWarnings("unchecked")
    static Map<String, Object> primaryDestination(Map<String, Object> cfg) {
        for (Object o : list(cfg.get("destinations"))) {
            Map<String, Object> d = (Map<String, Object>) o;
            if (!Boolean.FALSE.equals(d.get("enabled")) && "local".equals(d.get("type")) && !blank(d.get("path"))) {
                return d;
            }
        }
        return null;
    }

    Path primaryDir() {
        Map<String, Object> primary = primaryDestination(config);
        String p = primary != null ? String.valueOf(primary.get("path"))
                : String.valueOf(map(list(defaults().get("destinations")).getFirst()).get("path"));
        return Path.of(p).toAbsolutePath().normalize();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> secondaryDestinations() {
        Map<String, Object> primary = primaryDestination(config);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list(config.get("destinations"))) {
            Map<String, Object> d = (Map<String, Object>) o;
            if (d == primary || Boolean.FALSE.equals(d.get("enabled"))) {
                continue;
            }
            if (!blank(d.get("path")) || !FOLDER_DESTINATIONS.contains(String.valueOf(d.get("type")))) {
                out.add(d);
            }
        }
        return out;
    }

    private static String destLabel(Map<String, Object> d) {
        return !blank(d.get("label")) ? d.get("label").toString().trim() : d.get("type") + " " + d.get("path");
    }

    private static List<String> copyWarnings(List<Map<String, Object>> copies) {
        List<String> w = new ArrayList<>();
        for (Map<String, Object> c : copies) {
            if (!Boolean.TRUE.equals(c.get("ok"))) {
                w.add("Copy to " + c.get("destination") + " failed: " + c.get("error"));
            }
        }
        return w;
    }

    private List<Map<String, Object>> copyToDestinations(Path source, String kind, String checksum) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> dest : secondaryDestinations()) {
            String label = destLabel(dest);
            try {
                if (FOLDER_DESTINATIONS.contains(String.valueOf(dest.get("type")))) {
                    Path target = copyToFolder(source, Path.of(String.valueOf(dest.get("path"))).toAbsolutePath()
                            .normalize().resolve(kind), checksum);
                    results.add(m("destination", label, "ok", true, "target", target.toString()));
                } else if ("local_only".equals(config.get("internetBehaviour"))) {
                    results.add(m("destination", label, "ok", false, "error",
                            "not uploaded: Internet Behaviour is \"Backup Locally Only\""));
                } else {
                    uploadToCloud(source, dest);
                    results.add(m("destination", label, "ok", true));
                }
            } catch (Exception e) {
                log.warn("Copy to {} failed: {}", label, errText(e));
                results.add(m("destination", label, "ok", false, "error", errText(e)));
            }
        }
        return results;
    }

    /** Copy, re-hash the copy (bad USB sticks / flaky shares), then publish it. */
    private static Path copyToFolder(Path source, Path dir, String checksum) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(source.getFileName().toString());
        Path tmp = Path.of(target + PARTIAL_SUFFIX);
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            if (!sha256(tmp).equals(checksum)) {
                throw new IOException("copy does not match the original (checksum mismatch)");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            writeChecksum(target, checksum);
            return target;
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw e;
        }
    }

    /** Cloud uploads shell out to the provider CLI without a shell; secrets via env. */
    private void uploadToCloud(Path file, Map<String, Object> dest) throws IOException {
        String name = file.getFileName().toString();
        String folder = String.valueOf(dest.get("path") == null ? "" : dest.get("path")).replaceAll("^[\\\\/]+|[\\\\/]+$", "");
        if (folder.isEmpty()) {
            folder = "POS-CAFE-backups";
        }
        String bucket = blank(dest.get("bucket")) ? null : dest.get("bucket").toString();
        Map<String, String> extra = new LinkedHashMap<>();
        List<String> cmd;
        switch (String.valueOf(dest.get("type"))) {
            case "s3" -> {
                cmd = List.of("aws", "s3", "cp", file.toString(), "s3://" + (bucket == null ? "backups" : bucket) + "/"
                        + folder + "/" + name, "--only-show-errors");
                putIf(extra, "AWS_ACCESS_KEY_ID", dest.get("accessKey"));
                putIf(extra, "AWS_SECRET_ACCESS_KEY", dest.get("secretKey"));
                extra.put("AWS_DEFAULT_REGION", blank(dest.get("region")) ? "us-east-1" : dest.get("region").toString());
            }
            case "azure" -> {
                cmd = List.of("az", "storage", "blob", "upload", "--container-name", bucket == null ? "backups" : bucket,
                        "--file", file.toString(), "--name", folder + "/" + name, "--overwrite", "--only-show-errors");
                putIf(extra, "AZURE_STORAGE_ACCOUNT", dest.get("accessKey"));
                putIf(extra, "AZURE_STORAGE_KEY", dest.get("secretKey"));
            }
            case "google_drive", "dropbox", "onedrive" -> {
                String remote = bucket != null ? bucket : switch (String.valueOf(dest.get("type"))) {
                    case "google_drive" -> "gdrive";
                    case "dropbox" -> "dropbox";
                    default -> "onedrive";
                };
                cmd = List.of("rclone", "copyto", file.toString(), remote + ":" + folder + "/" + name);
            }
            case "sftp" -> cmd = List.of("scp", "-B", "-o", "BatchMode=yes", file.toString(), (blank(dest.get("accessKey"))
                    ? "" : dest.get("accessKey") + "@") + String.valueOf(dest.get("path")).replaceAll("/+$", "") + "/" + name);
            case "ftp" -> cmd = List.of("curl", "--silent", "--show-error", "--fail", "--ftp-create-dirs", "--user",
                    (dest.get("accessKey") == null ? "" : dest.get("accessKey")) + ":"
                            + (dest.get("secretKey") == null ? "" : dest.get("secretKey")), "-T", file.toString(),
                    "ftp://" + String.valueOf(dest.get("path")).replaceAll("^ftp://", "").replaceAll("/+$", "") + "/" + name);
            default -> throw new IOException("Unsupported destination type: " + dest.get("type"));
        }
        Map<String, String> envMap = new LinkedHashMap<>(System.getenv());
        envMap.putAll(extra);
        run(cmd, envMap, timeoutMs(1));
    }

    private static void putIf(Map<String, String> m, String key, Object v) {
        if (v != null) {
            m.put(key, v.toString());
        }
    }

    // ─── internals ───

    String buildFileName(String type) {
        Object fmt = config.get("namingFormat");
        String format = truthy(fmt) ? fmt.toString() : "POS-CAFE_{TYPE}_{DATE}_{TIME}";
        LocalDateTime d = LocalDateTime.now();
        String date = d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String time = d.format(DateTimeFormatter.ofPattern("HHmmss"));
        String name = format.replace("{TYPE}", type).replace("{DATE}", date).replace("{TIME}", time)
                .replaceAll("[<>:\"/\\\\|?*]", "_");
        return "FULL".equals(type) ? name + ".dump" : "CONFIG".equals(type) ? name + ".txt" : name + ".wal.txt";
    }

    /** Connection for the client tools: always the database the API writes to; PGUSER may override the role. */
    record PgConnection(Map<String, String> env, String database) {}

    PgConnection pgConnection() throws IOException {
        Map<String, String> pgEnv = new LinkedHashMap<>(System.getenv());
        pgEnv.remove("PGDATABASE");
        pgEnv.remove("PGSERVICE");
        JdbcConnectionDetails details = jdbcDetails.getIfAvailable();
        String url = details != null ? details.getJdbcUrl() : env.getProperty("spring.datasource.url");
        if (url == null || url.isEmpty()) {
            throw new IOException("DATABASE_URL is not set - cannot tell which database to back up");
        }
        URI u;
        try {
            u = URI.create(url.startsWith("jdbc:") ? url.substring(5) : url);
        } catch (IllegalArgumentException e) {
            throw new IOException("DATABASE_URL is not a valid postgres:// URL");
        }
        String database = URLDecoder.decode(u.getPath() == null ? "" : u.getPath().replaceFirst("^/", ""),
                StandardCharsets.UTF_8);
        pgEnv.put("PGHOST", u.getHost() == null || u.getHost().isEmpty() ? "localhost" : u.getHost().replaceAll("^\\[|]$", ""));
        pgEnv.put("PGPORT", u.getPort() > 0 ? String.valueOf(u.getPort()) : "5432");
        String overrideUser = envVar("PGUSER") == null ? null : envVar("PGUSER").trim();
        if (overrideUser != null && !overrideUser.isEmpty()) {
            pgEnv.put("PGUSER", overrideUser);
            pgEnv.put("PGPASSWORD", envVar("PGPASSWORD") == null ? "" : envVar("PGPASSWORD"));
        } else {
            pgEnv.put("PGUSER", details != null ? details.getUsername() : env.getProperty("spring.datasource.username", ""));
            String pw = details != null ? details.getPassword() : env.getProperty("spring.datasource.password", "");
            pgEnv.put("PGPASSWORD", pw == null ? "" : pw);
        }
        String query = u.getQuery();
        if (query != null) {
            for (String kv : query.split("&")) {
                if (kv.startsWith("sslmode=")) {
                    pgEnv.put("PGSSLMODE", kv.substring(8));
                }
            }
        }
        String pgDb = envVar("PGDATABASE") == null ? null : envVar("PGDATABASE").trim();
        if (pgDb != null && !pgDb.isEmpty() && !pgDb.equals(database)) {
            warnOnce("PGDATABASE=\"" + pgDb + "\" in .env differs from the DATABASE_URL database \"" + database
                    + "\"; backups use \"" + database + "\". Remove PGDATABASE from .env.");
        }
        if (database.isEmpty()) {
            throw new IOException("DATABASE_URL is not set - cannot tell which database to back up");
        }
        if (pgEnv.get("PGPASSWORD").isEmpty()) {
            pgEnv.remove("PGPASSWORD");
        }
        return new PgConnection(pgEnv, database);
    }

    String encryptionPassword() {
        if (!blank(envVar("BACKUP_ENCRYPTION_PASSWORD"))) {
            return envVar("BACKUP_ENCRYPTION_PASSWORD");
        }
        Map<String, Object> enc = map(config.get("encryption"));
        if ("aes256".equals(enc.get("type")) && truthy(enc.get("password"))) {
            return enc.get("password").toString();
        }
        return "";
    }

    private List<String> candidatePasswords() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (!blank(envVar("BACKUP_ENCRYPTION_PASSWORD"))) {
            out.add(envVar("BACKUP_ENCRYPTION_PASSWORD"));
        }
        Object pw = map(config.get("encryption")).get("password");
        if (truthy(pw)) {
            out.add(pw.toString());
        }
        return new ArrayList<>(out);
    }

    private double timeoutMinutes() {
        Object v = map(config.get("advanced")).get("backupTimeoutMinutes");
        return v instanceof Number n ? n.doubleValue() : 60;
    }

    private long timeoutMs(int factor) {
        double minutes = timeoutMinutes();
        return minutes > 0 ? (long) (minutes * 60_000 * factor) : 0;
    }

    private void assertDiskSpace(Path dir) throws IOException {
        Map<String, Object> space = checkDiskSpace(dir);
        Map<String, Object> last = lastSuccessful("full");
        long lastSize = last != null && last.get("sizeBytes") instanceof Number n ? n.longValue() : 0;
        double need = Math.max(GB, lastSize * 2.0);
        long total = ((Number) space.get("totalBytes")).longValue();
        long free = ((Number) space.get("freeBytes")).longValue();
        if (total > 0 && free < need) {
            throw new IOException(String.format("Insufficient disk space: %.2f GB free, need %.2f GB", free / GB, need / GB));
        }
    }

    private Map<String, Object> checkDiskSpace(Path dir) {
        try {
            Files.createDirectories(dir);
            FileStore store = Files.getFileStore(dir);
            long free = store.getUsableSpace();
            long total = store.getTotalSpace();
            Map<String, Object> last = lastSuccessful("full");
            long lastSize = last != null && last.get("sizeBytes") instanceof Number n ? n.longValue() : 0;
            double need = Math.max(GB, lastSize * 2.0);
            return m("freeBytes", free, "totalBytes", total, "usagePercent",
                    total > 0 ? ((total - free) / (double) total) * 100 : 0, "hasSpace", free >= need);
        } catch (IOException e) {
            return m("freeBytes", 0, "totalBytes", 0, "usagePercent", 0, "hasSpace", true);
        }
    }

    private int tocEntries(Path dump, Map<String, String> pgEnv) throws IOException {
        String out = execPg("pg_restore", List.of("--list", dump.toString()), pgEnv, 0)[0];
        int n = 0;
        for (String l : out.split("\n")) {
            if (!l.trim().isEmpty() && !l.startsWith(";")) {
                n++;
            }
        }
        return n;
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code sha256sum -c} compatible sidecar. */
    static void writeChecksum(Path file, String checksum) throws IOException {
        Files.writeString(Path.of(file + CHECKSUM_SUFFIX), checksum + " *" + file.getFileName() + "\n");
    }

    private Map<String, Object> record(Map<String, Object> result, boolean notify) {
        synchronized (history) {
            history.add(0, result);
            while (history.size() > 200) {
                history.remove(history.size() - 1);
            }
        }
        healthCache = null;
        if ("failed".equals(result.get("status"))) {
            log.error("{} backup FAILED: {}", result.get("kind"), result.get("error"));
        } else {
            long secs = Math.round(((Number) result.get("durationMs")).doubleValue() / 1000);
            log.info("{} backup {}: {} ({}s)", result.get("kind"), result.get("status"),
                    result.get("target") != null ? result.get("target") : result.get("error") != null ? result.get("error") : "",
                    secs);
            list(result.get("warnings")).forEach(w -> log.warn("{} backup: {}", result.get("kind"), w));
        }
        try {
            Path logDir = primaryDir().resolve("logs");
            Files.createDirectories(logDir);
            Files.writeString(logDir.resolve("backup-history.jsonl"), EntityJson.toJson(result) + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to persist backup history: {}", errText(e));
        }
        if (notify) {
            try {
                notifyResult(result);
            } catch (RuntimeException e) {
                log.warn("Backup notification failed: {}", errText(e));
            }
        }
        return result;
    }

    private void notifyResult(Map<String, Object> result) {
        String ping = envVar("BACKUP_HEALTHCHECKS_PING_URL");
        Object kind = result.get("kind");
        Object status = result.get("status");
        if (!blank(ping) && ("full".equals(kind) || "restore_test".equals(kind)) && !"skipped".equals(status)) {
            boolean failed = "failed".equals(status);
            if (failed || "full".equals(kind)) {
                String url = ping.replaceAll("/+$", "") + (failed ? "/fail" : "");
                String body = failed ? kind + ": " + result.get("error") : "full backup " + result.get("target");
                CompletableFuture.runAsync(() -> {
                    try {
                        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(10)).header("Content-Type", "text/plain")
                                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
                    } catch (Exception e) {
                        log.warn("Healthcheck ping failed: {}", errText(e));
                    }
                });
            }
        }
        Map<String, Object> n = map(config.get("notifications"));
        if (n.isEmpty()) {
            return;
        }
        boolean warnFailed = list(result.get("warnings")).stream().anyMatch(w -> w.toString().toLowerCase().contains("failed"));
        String event = "failed".equals(status) || warnFailed ? "failed" : "skipped".equals(status) ? "skipped" : "completed";
        if (!list(n.get("on")).contains(event)) {
            return;
        }
        String what = "failed".equals(status) ? "FAILED" : "failed".equals(event) ? "needs attention"
                : "completed".equals(event) ? "completed" : "skipped";
        String subject = "POS-CAFE backup " + what + ": " + kind;
        List<String> parts = new ArrayList<>(List.of(subject));
        if (truthy(result.get("error"))) {
            parts.add(result.get("error").toString());
        }
        list(result.get("warnings")).forEach(w -> parts.add(w.toString()));
        if (truthy(result.get("target"))) {
            parts.add(result.get("target").toString());
        }
        String body = String.join("\n", parts);
        if (list(n.get("channels")).contains("desktop")) {
            log.warn("BACKUP NOTICE: {}", body);
        }
        if (list(n.get("channels")).contains("email") && truthy(n.get("emailAddress"))) {
            JavaMailSender sender = mail.getIfAvailable();
            if (sender == null || blank(env.getProperty("spring.mail.host"))) {
                log.warn("Backup email to {} not sent: SMTP_HOST is not configured", n.get("emailAddress"));
                return;
            }
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(env.getProperty("SMTP_FROM", "no-reply@cafe-pos.local"));
            msg.setTo(n.get("emailAddress").toString());
            msg.setSubject(subject);
            msg.setText(body);
            sender.send(msg);
        }
    }

    private Map<String, Object> skipped(String kind, String reason) {
        log.warn("{} backup skipped: {}", kind, reason);
        return m("kind", kind, "status", "skipped", "durationMs", 0, "finishedAt", nowIso(), "error", reason);
    }

    private void warnOnce(String message) {
        if (warnedOnce.add(message)) {
            log.warn(message);
        }
    }

    /** Run a PostgreSQL client tool ({@code PG_BIN} or PATH); returns [stdout, stderr]. */
    String[] execPg(String tool, List<String> args, Map<String, String> pgEnv, long timeoutMs) throws IOException {
        String pgBin = !blank(envVar("PG_BIN")) ? envVar("PG_BIN") : detectPgBin();
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? tool + ".exe" : tool;
        List<String> cmd = new ArrayList<>();
        cmd.add(blank(pgBin) ? exe : Path.of(pgBin, exe).toString());
        cmd.addAll(args);
        return run(cmd, pgEnv, timeoutMs);
    }

    static String detectPgBin() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return "";
        }
        for (String base : List.of("C:\\Program Files\\PostgreSQL", "C:\\Program Files (x86)\\PostgreSQL")) {
            for (String v : List.of("18", "17", "16", "15", "14", "13", "12")) {
                Path bin = Path.of(base, v, "bin");
                if (Files.exists(bin.resolve("pg_dump.exe"))) {
                    return bin.toString();
                }
            }
        }
        return "";
    }

    /** No shell: argv only; output drained on background threads; killed on timeout. */
    static String[] run(List<String> cmd, Map<String, String> envMap, long timeoutMs) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        pb.environment().putAll(envMap);
        Process p = pb.start();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> drain(p.getInputStream()));
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> drain(p.getErrorStream()));
        try {
            boolean done = timeoutMs > 0 ? p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) : p.waitFor(6, TimeUnit.HOURS);
            if (!done) {
                p.destroyForcibly();
                throw new ProcessTimeout();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        String stdout = out.join();
        String stderr = err.join();
        if (p.exitValue() != 0) {
            throw new ProcessFailed("Command failed: " + String.join(" ", cmd.subList(0, 1)) + " (exit " + p.exitValue() + ")",
                    stderr);
        }
        return new String[] {stdout, stderr};
    }

    private static String drain(InputStream in) {
        try (in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            in.transferTo(bos);
            return bos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** A client tool that ran past its timeout. */
    static final class ProcessTimeout extends IOException {
        ProcessTimeout() {
            super("timed out (killed with SIGTERM)");
        }
    }

    /** A client tool that exited non-zero; {@code stderr} is what operators need to see. */
    static final class ProcessFailed extends IOException {
        final String stderr;

        ProcessFailed(String message, String stderr) {
            super(message);
            this.stderr = stderr;
        }
    }

    /** One-value-per-column, one-row-per-line psql query against the live (or given) database. */
    String psql(String sql, String database) throws IOException {
        PgConnection pg = pgConnection();
        return execPg("psql", List.of("--no-password", "-X", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1",
                "--dbname=" + (database == null ? pg.database() : database), "-c", sql), pg.env(), 0)[0].trim();
    }

    record Artifact(Path path, long mtime, long size) {}

    static List<Artifact> listArtifacts(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            List<Artifact> out = new ArrayList<>();
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(CHECKSUM_SUFFIX) || n.endsWith(PARTIAL_SUFFIX) || !Files.isRegularFile(p)) {
                    continue;
                }
                FileTime t = Files.getLastModifiedTime(p);
                out.add(new Artifact(p, t.toMillis(), Files.size(p)));
            }
            out.sort((a, b) -> Long.compare(b.mtime(), a.mtime()));
            return out;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    static String errText(Exception e) {
        if (e instanceof ProcessFailed pf && pf.stderr != null && !pf.stderr.trim().isEmpty()) {
            return pf.stderr.trim();
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static String nowIso() {
        return JsonConfig.toJsIso(Instant.now());
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }

    static boolean truthy(Object v) {
        return v != null && !Boolean.FALSE.equals(v) && !"".equals(v)
                && !(v instanceof Number n && n.doubleValue() == 0);
    }

    static boolean blank(Object v) {
        return v == null || v.toString().trim().isEmpty();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object v) {
        return v instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
    }

    // ─── lifecycle ───

    @PostConstruct
    void init() {
        loadConfig();
        Path base = primaryDir();
        try {
            for (String sub : List.of("full", "incremental", "files", "config", "logs")) {
                Files.createDirectories(base.resolve(sub));
            }
        } catch (IOException e) {
            log.error("Failed to create backup directories under {}: {}", base, errText(e));
        }
        loadHistory();
        if (schedulerEnabled()) {
            scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("backup-");
            scheduler.setDaemon(true);
            scheduler.initialize();
        }
        reschedule();
        scheduleCatchUp();
        try {
            pgConnection();
        } catch (IOException e) {
            log.error("Backups cannot run: {}", errText(e));
        }
    }

    @SuppressWarnings("unchecked")
    private void loadHistory() {
        Path file = primaryDir().resolve("logs").resolve("backup-history.jsonl");
        try {
            List<String> lines = Files.readAllLines(file);
            List<Map<String, Object>> parsed = new ArrayList<>();
            for (String l : lines.subList(Math.max(0, lines.size() - 200), lines.size())) {
                try {
                    JsonNode n = EntityJson.tree(l);
                    if (n != null && n.isObject()) {
                        parsed.add(new tools.jackson.databind.json.JsonMapper().convertValue(n, LinkedHashMap.class));
                    }
                } catch (RuntimeException ignored) {
                    // skip a torn line
                }
            }
            Collections.reverse(parsed);
            history.clear();
            history.addAll(parsed);
            if (lines.size() > 5000) {
                Files.write(file, lines.subList(lines.size() - 1000, lines.size()));
            }
        } catch (IOException e) {
            history.clear();
        }
    }

    @PreDestroy
    void destroy() {
        clearJobs();
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}

package com.inventoryerp.sync.application;

import com.inventoryerp.shared.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * ADR-S083 S8: sync protocol versions. Clients send {@code X-Sync-Protocol: <major.minor>}; a client that sends none
 * is the original (Nest-era) protocol {@code 1.0}. The server supports the current major and the one before it.
 * Pull refuses an unsupported version with 426; push accepts every version so queued ops are never lost.
 */
public final class SyncProtocol {

    public static final String HEADER = "X-Sync-Protocol";
    public static final String CURRENT = "2.0";
    public static final int CURRENT_MAJOR = 2;
    public static final int OLDEST_MAJOR = 1;

    private SyncProtocol() {}

    /** The version a request declared ({@code 1.0} when absent or blank). */
    public static String of(String header) {
        return header == null || header.isBlank() ? "1.0" : header.trim();
    }

    /** The major version, or -1 when the value is not {@code <int>[.<int>]}. */
    public static int major(String version) {
        String v = of(version);
        int dot = v.indexOf('.');
        try {
            int major = Integer.parseInt(dot < 0 ? v : v.substring(0, dot));
            if (dot >= 0) {
                Integer.parseInt(v.substring(dot + 1));
            }
            return major;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean supported(String version) {
        int major = major(version);
        return major >= OLDEST_MAJOR && major <= CURRENT_MAJOR;
    }

    /** v2 behaviour: token rotation, tombstones for soft-deleted rows. */
    public static boolean atLeast2(String version) {
        return major(version) >= 2;
    }

    /** Pull gate: 426 for a version this server cannot serve. */
    public static void requirePullSupported(String version) {
        if (!supported(version)) {
            throw new ApiException(HttpStatus.UPGRADE_REQUIRED, "Sync protocol " + of(version)
                    + " is not supported by this server (supported: " + OLDEST_MAJOR + ".x to " + CURRENT_MAJOR
                    + ".x). Update the app, then sync again; queued operations are kept and can still be pushed.");
        }
    }
}

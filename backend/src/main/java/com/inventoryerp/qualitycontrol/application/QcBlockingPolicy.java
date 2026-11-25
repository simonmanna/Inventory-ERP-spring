package com.inventoryerp.qualitycontrol.application;

/**
 * The only interpretation of the {@code blocking} / {@code blockOnFailure} flags (ADR-S087 §5). The two flags are
 * independent: {@code blocking} governs a pending check, {@code blockOnFailure} a failed one. Only generated checks
 * (origin {@code generated}) can block; manual and correction checks never do. Waived, passed and cancelled checks
 * never block.
 *
 * <pre>
 * blocking  blockOnFailure  pending  failed
 * true      false           blocks   allows
 * true      true            blocks   blocks
 * false     false           allows   allows
 * false     true            allows   blocks
 * </pre>
 */
public final class QcBlockingPolicy {

    private QcBlockingPolicy() {}

    /** Does a check in this state prevent its source document from completing? */
    public static boolean blocks(String origin, String status, boolean blocking, boolean blockOnFailure) {
        if (!"generated".equals(origin) || status == null) {
            return false;
        }
        return switch (status) {
            case "pending" -> blocking;
            case "failed" -> blockOnFailure;
            default -> false;
        };
    }

    /** Would a check generated with these flags ever block (badge "blocking" in the UI)? */
    public static boolean canBlock(boolean blocking, boolean blockOnFailure) {
        return blocking || blockOnFailure;
    }
}

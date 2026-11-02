package com.inventoryerp.dms.spi;

/**
 * A record type documents can be attached to ({@code resType}, e.g. {@code sales_order}). A module registers one bean
 * per type it owns; the DMS also ships table-backed built-ins for the core ERP records. All calls run inside the
 * caller's tenant.
 */
public interface DmsResourceProvider {

    /** Stable type key, snake_case (e.g. {@code helpdesk_ticket}). */
    String resType();

    /** Human label of the type ("Sales order"). */
    String label();

    /** Permission needed to see the record's attachments, or {@code null} for none beyond DMS read. */
    String readPermission();

    /** Permission needed to attach / detach files on the record, or {@code null} for none beyond DMS write. */
    String writePermission();

    /** Code of the system workspace new attachments land in. */
    String workspaceCode();

    /** Display name of the record in the current organization, or {@code null} when it does not exist there. */
    String displayName(String resId);

    /** Web path of the record ({@code /sales/orders/<id>}), or {@code null}. */
    String webPath(String resId);
}

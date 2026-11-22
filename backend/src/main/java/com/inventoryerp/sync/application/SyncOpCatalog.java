package com.inventoryerp.sync.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * ADR-S083 C1 (O0 correction 1): every sync op type, with the transaction boundary that carries its applied-op ledger
 * row. An op type that is not listed here is refused ("Unsupported sync op type"), so a new handler cannot ship without
 * being classified.
 *
 * <ul>
 *   <li>{@link Boundary#OP_TRANSACTION}: the whole handler runs inside one transaction opened by the push service.
 *   Every business write and the ledger row commit or roll back together; a failure leaves nothing behind.</li>
 *   <li>{@link Boundary#SERVICE_TRANSACTIONS}: the handler calls an online service that owns its transactions (some
 *   commit in several steps on purpose, e.g. ADR-S072 R-B per-line stock commits). The ledger row rides on the first
 *   business commit; any later failure is reported as {@code PARTIAL_COMMIT} and the op is never run again
 *   automatically. The audit (ADR-S083 §C1 handler table) confirmed no service here writes business rows outside a
 *   transaction before its first commit.</li>
 * </ul>
 */
public final class SyncOpCatalog {

    /** Where the ledger row commits. */
    public enum Boundary {
        OP_TRANSACTION, SERVICE_TRANSACTIONS
    }

    /** One classified op type. {@code sideEffects} lists non-transactional effects (empty = none). */
    public record Entry(String type, Boundary boundary, boolean money, String sideEffects) {}

    private static final Map<String, Entry> ENTRIES;

    static {
        Map<String, Entry> m = new LinkedHashMap<>();
        // Cash and sales: online services with their own transactions.
        svc(m, "cash_session.open", false, "deviceId stamp after commit (idempotent metadata)");
        svc(m, "cash_session.close", false, "stock-posting drain before close (claimed, idempotent per job/line)");
        svc(m, "cash_session.movement", true, "");
        svc(m, "cash_drawer.open", false, "");
        svc(m, "sale.checkout", true, "domain events via the outbox; deviceId stamp after commit (idempotent)");
        svc(m, "tab.settle", true, "domain events via the outbox; deviceId stamp after commit (idempotent)");
        svc(m, "sale.refund", true, "domain events via the outbox");
        svc(m, "sale.void", true, "domain events via the outbox");
        svc(m, "reservation.create", false, "");
        svc(m, "reservation.seat", false, "");
        svc(m, "reservation.cancel", false, "");
        svc(m, "reservation.noShow", false, "");
        svc(m, "product.upsert", false, "");
        svc(m, "product.delete", false, "");
        svc(m, "cashRegister.delete", false, "");
        svc(m, "stock.in", false, "");
        svc(m, "stock.out", false, "");
        svc(m, "expense.create", true, "");
        // ADR-S083 O4: each calls inventory / procurement services that own their transactions (create, then post).
        // A later step the server refuses is held for a supervisor instead of failing the op (S4).
        svc(m, "transfer.dispatch", false, "");
        svc(m, "transfer.receive", false, "");
        svc(m, "stock.adjust", false, "");
        svc(m, "grn.receive", false, "");
        // ADR-S083 O5: sales / payment services own their transactions (create, then confirm / submit). A later step
        // the server refuses becomes a review item instead of failing the op.
        svc(m, "salesOrder.create", false, "domain events via the outbox");
        svc(m, "quote.create", false, "");
        svc(m, "payment.receive", true, "domain events via the outbox");
        // Thin writes and services that write in auto-commit: one op transaction.
        op(m, "stock.count", false);
        op(m, "kitchen.void", false); // per-line waste commits stay REQUIRES_NEW, keyed per line (retry-safe)
        op(m, "customer.upsert", false);
        op(m, "customer.delete", false);
        op(m, "setting.set", false);
        op(m, "menuCategory.upsert", false);
        op(m, "menuCategory.delete", false);
        op(m, "modifierGroup.delete", false);
        op(m, "accompanimentGroup.delete", false);
        op(m, "tax.delete", false);
        op(m, "menuItem.upsert", false);
        op(m, "menuItem.delete", false);
        op(m, "modifierGroup.upsert", false);
        op(m, "accompanimentGroup.upsert", false);
        op(m, "tax.upsert", false);
        op(m, "productCategory.upsert", false);
        op(m, "productCategory.delete", false);
        op(m, "posTable.upsert", false);
        op(m, "posTable.delete", false);
        op(m, "cashRegister.upsert", false);
        op(m, "staff.pinChange", false);
        op(m, "message.send", false);
        op(m, "message.markRead", false);
        ENTRIES = Collections.unmodifiableMap(m);
    }

    private static void svc(Map<String, Entry> m, String type, boolean money, String sideEffects) {
        m.put(type, new Entry(type, Boundary.SERVICE_TRANSACTIONS, money, sideEffects));
    }

    private static void op(Map<String, Entry> m, String type, boolean money) {
        m.put(type, new Entry(type, Boundary.OP_TRANSACTION, money, ""));
    }

    private SyncOpCatalog() {}

    /** The classification of {@code type}, or null when the type is not supported. */
    public static Entry of(String type) {
        return type == null ? null : ENTRIES.get(type);
    }

    /** Every supported op type, in catalog order. */
    public static Set<String> types() {
        return ENTRIES.keySet();
    }

    /** Op types that move stock: their unresolved dead letters block a fiscal-period close (ADR-S083 S4/S5). */
    public static final Set<String> STOCK_TYPES = Set.of("stock.in", "stock.out", "stock.count", "kitchen.void",
            "transfer.dispatch", "transfer.receive", "stock.adjust", "grn.receive");

    /** Op types whose unresolved dead letters block a cash-session close (ADR-S083 S5). */
    public static Set<String> moneyTypes() {
        return ENTRIES.values().stream().filter(Entry::money).map(Entry::type)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}

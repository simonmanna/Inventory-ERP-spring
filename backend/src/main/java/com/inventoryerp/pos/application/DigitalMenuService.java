package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.num;
import static com.inventoryerp.pos.application.PosSql.org;

import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.core.domain.ProductCategory;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.domain.MenuQrSession;
import com.inventoryerp.pos.domain.OnlineOrder;
import com.inventoryerp.shared.web.ApiException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Digital (QR) menu: QR sessions, public catalog, online orders placed through the cashier checkout, tracking
 * (port of {@code digital-menu.service.ts}). Prisma {@code client} queries are scoped to the caller's tenant (the
 * tenancy extension overrides {@code organizationId}); {@code raw} lookups (session by token) are not.
 */
@Service
public class DigitalMenuService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PosDb db;
    private final AuditService audit;
    private final PosService pos;

    public DigitalMenuService(PosDb db, AuditService audit, PosService pos) {
        this.db = db;
        this.audit = audit;
        this.pos = pos;
    }

    // ─── QR sessions (admin) ───

    public Map<String, Object> createSession(String branchId, String tableNumber, Double expiresInHours) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        double hours = expiresInHours == null ? 6 : expiresInHours;
        Instant expiresAt = Instant.ofEpochMilli(System.currentTimeMillis() + (long) (hours * 60 * 60_000));
        Map<String, Object> session = db.insert(MenuQrSession.class, Obj.of("organizationId", org(), "branchId", branchId,
                "tableNumber", tableNumber, "token", token, "expiresAt", expiresAt));
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("branchId", branchId);
        newValues.put("tableNumber", tableNumber);
        newValues.put("expiresAt", expiresAt);
        audit.record(AuditInput.of("MenuQrSession", (String) session.get("id"), "create", null, newValues));
        return session;
    }

    public List<Map<String, Object>> listSessions(String branchId) {
        if (branchId != null && !branchId.isEmpty()) {
            return db.list(MenuQrSession.class, "\"organizationId\" = ? AND \"branchId\" = ? ORDER BY \"createdAt\" DESC"
                    + " LIMIT 100", org(), branchId);
        }
        return db.list(MenuQrSession.class, "\"organizationId\" = ? ORDER BY \"createdAt\" DESC LIMIT 100", org());
    }

    public void revokeSession(String id) {
        db.updateMany(MenuQrSession.class, Obj.of("isActive", false), "id = ? AND \"organizationId\" = ?", id, org());
    }

    private Map<String, Object> sessionByToken(String token) {
        return token == null ? null : db.first(MenuQrSession.class, "token = ?", token);
    }

    private static boolean usable(Map<String, Object> session) {
        return session != null && Boolean.TRUE.equals(session.get("isActive"))
                && !((Instant) session.get("expiresAt")).isBefore(Instant.now());
    }

    // ─── public catalog ───

    /** Validate a token and return the session header plus the public catalog; marks the session used. */
    public Map<String, Object> getPublicCatalog(String token) {
        Map<String, Object> session = sessionByToken(token);
        if (!usable(session)) {
            throw ApiException.notFound("This menu link has expired. Please ask staff for a new QR.");
        }
        db.jdbc().sql("UPDATE \"MenuQrSession\" SET \"lastUsedAt\" = " + NOW + " WHERE id = ?").param(session.get("id"))
                .update();
        String sessionOrg = (String) session.get("organizationId");
        String orgName = db.jdbc().sql("SELECT name FROM \"Organization\" WHERE id = ?").param(sessionOrg)
                .query(String.class).optional().orElse(null);
        String branchName = db.jdbc().sql("SELECT name FROM \"Branch\" WHERE id = ? AND \"organizationId\" = ? LIMIT 1")
                .params(session.get("branchId"), sessionOrg).query(String.class).optional().orElse(null);

        String orgId = org();
        List<String> warehouses = db.jdbc().sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND type = 'warehouse' AND \"isActive\" = true").param(orgId)
                .query(String.class).list();
        List<Map<String, Object>> products = SqlRows.list(db.jdbc().sql("SELECT p.id, p.name, p.description,"
                + " p.\"salesPrice\", p.\"categoryId\", c.name AS \"categoryName\", p.station, p.\"productType\"::text AS"
                + " \"productType\", p.\"trackInventory\" FROM \"Product\" p LEFT JOIN \"ProductCategory\" c ON c.id ="
                + " p.\"categoryId\" WHERE p.\"organizationId\" = ? AND p.\"deletedAt\" IS NULL AND p.\"isActive\" = true"
                + " ORDER BY p.name ASC").param(orgId));
        List<Map<String, Object>> publicProducts = new ArrayList<>();
        for (Map<String, Object> p : products) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.get("id"));
            m.put("name", p.get("name"));
            m.put("description", p.get("description"));
            m.put("price", PosSql.js(p.get("salesPrice")));
            m.put("categoryId", p.get("categoryId"));
            if (p.get("categoryName") != null) {
                m.put("categoryName", p.get("categoryName"));
            }
            m.put("station", p.get("station"));
            m.put("productType", p.get("productType"));
            boolean inStock = true;
            if (Boolean.TRUE.equals(p.get("trackInventory"))) {
                inStock = !warehouses.isEmpty() && db.jdbc().sql("SELECT count(*) FROM \"StockItem\" WHERE \"productId\" = ?"
                        + " AND quantity > 0 AND \"locationId\" IN (" + PosSql.marks(warehouses.size()) + ")")
                        .params(concat(p.get("id"), warehouses)).query(Long.class).single() > 0;
            }
            m.put("inStock", inStock);
            publicProducts.add(m);
        }
        List<Map<String, Object>> categories = db.list(ProductCategory.class,
                "\"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY name ASC", orgId);
        return Obj.of("session", Obj.of("id", session.get("id"), "branchId", session.get("branchId"), "tableNumber",
                        session.get("tableNumber")),
                "orgName", orgName == null ? "Cafe" : orgName, "branchName", branchName == null ? "Main" : branchName,
                "categories", categories, "products", publicProducts, "combos", List.of());
    }

    private static List<Object> concat(Object first, List<String> rest) {
        List<Object> out = new ArrayList<>();
        out.add(first);
        out.addAll(rest);
        return out;
    }

    // ─── place order ───

    /** Place an order through the same {@code PosService.checkout} the cashier uses, then record the OnlineOrder. */
    public Map<String, Object> placeOrder(Map<String, Object> args) {
        Map<String, Object> session = sessionByToken(PosSql.str(args, "token"));
        if (!usable(session)) {
            throw ApiException.badRequest("Menu link expired");
        }
        List<Map<String, Object>> lines = PosOrdersService.maps(args.get("lines"));
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Cart is empty");
        }
        String customerName = PosSql.str(args, "customerName");
        if (customerName == null || customerName.trim().isEmpty()) {
            throw ApiException.badRequest("Customer name is required");
        }
        double total = 0;
        for (Map<String, Object> l : lines) {
            total += num(l.get("quantity")) * num(l.get("unitPrice"));
        }
        if (total <= 0) {
            throw ApiException.badRequest("Order total is zero");
        }
        String phone = PosSql.str(args, "customerPhone");
        String email = PosSql.str(args, "customerEmail");
        Map<String, Object> partner = findOrCreateCustomer(customerName, phone, email);
        List<Map<String, Object>> tenders = PosOrdersService.maps(args.get("tenders"));
        double tendered = 0;
        for (Map<String, Object> t : tenders) {
            tendered += num(t.get("amount"));
        }
        if (Math.abs(tendered - total) > 0.01) {
            throw ApiException.badRequest("Tenders do not cover the total");
        }
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> t : tenders) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", mapPaymentMethod((String) t.get("method")));
            m.put("amount", t.get("amount"));
            if (t.get("reference") != null) {
                m.put("reference", t.get("reference"));
            }
            mapped.add(m);
        }
        Object tableNumber = session.get("tableNumber");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("partnerId", partner.get("id"));
        input.put("branchId", session.get("branchId"));
        input.put("lines", lines);
        input.put("tenders", mapped);
        input.put("reference", "Online order from " + (PosSql.truthy(tableNumber) ? "table " + tableNumber : "web"));
        if (args.get("notes") != null) {
            input.put("notes", args.get("notes"));
        }
        Map<String, Object> checkout = pos.checkout(input);

        Map<String, Object> first = tenders.isEmpty() ? null : tenders.getFirst();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", org());
        data.put("sessionId", session.get("id"));
        data.put("customerName", customerName);
        data.put("customerPhone", phone);
        data.put("customerEmail", email);
        data.put("orderType", args.get("orderType") != null ? args.get("orderType") : "dine_in");
        data.put("items", PosDb.json(lines));
        data.put("subtotal", PosSql.decOf(total));
        data.put("taxAmount", 0);
        data.put("totalAmount", PosSql.decOf(total));
        data.put("invoiceId", checkout.get("invoiceId"));
        data.put("paymentMethod", first == null ? "cash_on_pickup" : first.get("method"));
        data.put("paymentRef", first == null ? null : first.get("reference"));
        data.put("status", "received");
        data.put("notes", args.get("notes"));
        Map<String, Object> onlineOrder = db.insert(OnlineOrder.class, data);
        return Obj.of("onlineOrderId", onlineOrder.get("id"), "orderNumber", checkout.get("invoiceNumber"),
                "total", PosSql.js(total), "change", checkout.get("change") == null ? 0 : checkout.get("change"),
                "status", onlineOrder.get("status"));
    }

    // ─── tracking / cashier queue ───

    public Map<String, Object> trackOrder(String orderId, String token) {
        Map<String, Object> order = db.first(OnlineOrder.class, "id = ?", orderId);
        if (order == null) {
            throw ApiException.notFound("Order not found");
        }
        if (order.get("sessionId") != null) {
            Map<String, Object> session = db.first(MenuQrSession.class, "id = ?", order.get("sessionId"));
            if (session == null || !session.get("token").equals(token)) {
                throw ApiException.notFound("Invalid tracking token");
            }
        }
        return Obj.of("id", order.get("id"), "orderNumber", order.get("invoiceId"), "status", order.get("status"),
                "total", PosSql.js(order.get("totalAmount")), "items", order.get("items"), "notes", order.get("notes"),
                "createdAt", order.get("createdAt"), "updatedAt", order.get("updatedAt"));
    }

    /** Untyped status (the Nest DTO has no validators, so the whitelist rejects any body field). */
    public Map<String, Object> updateOrderStatus(String orderId, String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (status != null) {
            data.put("status", status);
        }
        return db.update(OnlineOrder.class, data, "id = ? AND \"organizationId\" = ?", orderId, org());
    }

    public List<Map<String, Object>> listOnlineOrders(String status) {
        if (status != null && !status.isEmpty()) {
            return db.list(OnlineOrder.class, "\"organizationId\" = ? AND status = ? ORDER BY \"createdAt\" DESC LIMIT 50",
                    org(), status);
        }
        return db.list(OnlineOrder.class, "\"organizationId\" = ? ORDER BY \"createdAt\" DESC LIMIT 50", org());
    }

    // ─── helpers ───

    private Map<String, Object> findOrCreateCustomer(String name, String phone, String email) {
        String orgId = org();
        if (PosSql.truthy(phone)) {
            Map<String, Object> existing = db.first(Partner.class, "\"organizationId\" = ? AND \"deletedAt\" IS NULL"
                    + " AND phone = ?", orgId, phone);
            if (existing != null) {
                return existing;
            }
        }
        if (PosSql.truthy(email)) {
            Map<String, Object> existing = db.first(Partner.class, "\"organizationId\" = ? AND \"deletedAt\" IS NULL"
                    + " AND email = ?", orgId, email);
            if (existing != null) {
                return existing;
            }
        }
        String code = "CUST-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase();
        return db.insert(Partner.class, Obj.of("organizationId", orgId, "code", code, "name", name.trim(),
                "phone", phone == null ? null : phone.trim(), "email", email == null ? null : email.trim(),
                "isCustomer", true, "isCompany", false));
    }

    private static String mapPaymentMethod(String m) {
        if ("cash_on_pickup".equals(m)) {
            return "cash";
        }
        if ("mobile_money".equals(m)) {
            return "mobile_money";
        }
        return "card";
    }
}

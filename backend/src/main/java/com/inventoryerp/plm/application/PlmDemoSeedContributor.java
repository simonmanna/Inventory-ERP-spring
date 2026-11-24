package com.inventoryerp.plm.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.seed.SeedContributor;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.manufacturing.application.BomService;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateBom;
import com.inventoryerp.manufacturing.application.MfgCommands.UpdateBom;
import com.inventoryerp.plm.application.PlmCommands.CreateChange;
import com.inventoryerp.plm.application.PlmCommands.RegisterItem;
import com.inventoryerp.plm.application.PlmCommands.SaveRequest;
import com.inventoryerp.plm.application.PlmCommands.SaveTag;
import com.inventoryerp.plm.application.PlmCommands.SaveType;
import com.inventoryerp.plm.application.PlmCommands.Transition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * PLM part of the demo seed (ADR-S086), opt-in with {@code SEED_PLM_DEMO=true}: an office desk with a released BOM, its
 * parts in different lifecycle states (the steel leg being phased out for an aluminium prototype), an ECO type for
 * product data, tags, an open change request and two change orders (a BOM change with its draft new version, and a
 * product data change), so the PLM application opens on real work. Skipped when the desk exists.
 */
@Component
public class PlmDemoSeedContributor implements SeedContributor {

    private static final Logger log = LoggerFactory.getLogger(PlmDemoSeedContributor.class);
    static final String DESK = "PLM-DESK";

    private final PlmDb db;
    private final JsonMapper mapper;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final BomService boms;
    private final PlmLifecycleService lifecycle;
    private final PlmTypeService types;
    private final PlmTagService tags;
    private final PlmRequestService requests;
    private final PlmChangeService changes;

    public PlmDemoSeedContributor(PlmDb db, JsonMapper mapper, PlatformTransactionManager txm,
            @Value("${SEED_PLM_DEMO:false}") boolean enabled, BomService boms, PlmLifecycleService lifecycle,
            PlmTypeService types, PlmTagService tags, PlmRequestService requests, PlmChangeService changes) {
        this.db = db;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txm);
        this.enabled = enabled;
        this.boms = boms;
        this.lifecycle = lifecycle;
        this.types = types;
        this.tags = tags;
        this.requests = requests;
        this.changes = changes;
    }

    /** After the manufacturing demo (950). */
    @Override
    public int order() {
        return 960;
    }

    @Override
    public void seed(String organizationId) {
        if (enabled) {
            Map<String, Object> out = seedDemo(organizationId);
            log.info("PLM demo in org {}: {}", organizationId, out);
        }
    }

    /** Seed the PLM demo into {@code organizationId} as its first user; {@code skipped} when the desk exists. */
    public Map<String, Object> seedDemo(String organizationId) {
        String admin = db.jdbc().sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\""
                + " LIMIT 1").param(organizationId).query(String.class).optional().orElse(null);
        return TenantContext.run(TenantStore.user(organizationId, admin), () -> {
            TenantContext.setPermissions(List.copyOf(Permissions.ALL));
            return tx.execute(s -> build(organizationId, admin));
        });
    }

    private <T> T in(Class<T> type, Object... kv) {
        return mapper.convertValue(Obj.of(kv), type);
    }

    private String productId(String org, String code) {
        return db.jdbc().sql("SELECT id FROM \"Product\" WHERE \"organizationId\" = ? AND code = ? LIMIT 1")
                .params(org, code).query(String.class).optional().orElse(null);
    }

    /** A stockable AVCO product, as the other demo seeds create theirs. */
    private String product(String org, String uom, String code, String name, int sales, int cost) {
        db.jdbc().sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"salesPrice\", \"costPrice\", \"uomId\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, 'stockable'::\"ProductType\", true, 'AVCO'::\"CostingMethod\", ?, ?, ?, now())"
                + " ON CONFLICT (\"organizationId\", code) DO NOTHING").params(UUID.randomUUID().toString(), org, code,
                        name, sales, cost, uom).update();
        return productId(org, code);
    }

    private Map<String, Object> line(String productId, int qty, int sequence) {
        return Obj.of("componentProductId", productId, "quantity", qty, "sequence", sequence);
    }

    private Map<String, Object> build(String org, String admin) {
        if (productId(org, DESK) != null) {
            return Obj.of("skipped", true);
        }
        if (admin == null) {
            log.warn("PLM demo skipped: the organization has no user");
            return Obj.of("skipped", true);
        }
        String uom = db.jdbc().sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'"
                + " LIMIT 1").param(org).query(String.class).optional().orElse(null);
        String desk = product(org, uom, DESK, "Office desk 120 cm", 650000, 0);
        String top = product(org, uom, "PLM-TOP", "Desk top, oak veneer 120 x 60 cm", 0, 180000);
        String steel = product(org, uom, "PLM-LEG-ST", "Desk leg, steel", 0, 25000);
        String alu = product(org, uom, "PLM-LEG-AL", "Desk leg, aluminium", 0, 32000);
        String screws = product(org, uom, "PLM-SCREW", "Assembly screw pack", 0, 3000);

        Map<String, Object> bom = boms.create(in(CreateBom.class, "name", "Office desk 120 cm", "outputProductId", desk,
                "outputQuantity", 1, "isDefault", true, "notes", "PLM demo.", "lines",
                List.of(line(top, 1, 10), line(steel, 4, 20), line(screws, 1, 30))));
        String bomId = str(bom, "id");
        boms.activate(bomId);

        // Products under lifecycle management: released parts, an aluminium leg in prototype.
        lifecycle.register(new RegisterItem(desk, "A", "released", admin, "Flagship office desk.", null));
        lifecycle.register(new RegisterItem(top, "A", "released", admin, null, null));
        Map<String, Object> steelItem = lifecycle.register(new RegisterItem(steel, "A", "released", admin, null, null));
        lifecycle.register(new RegisterItem(alu, "A", "prototype", admin, "Lighter leg, same load rating.", null));
        lifecycle.register(new RegisterItem(screws, "A", "released", admin, null, null));
        lifecycle.transition(str(steelItem, "id"), new Transition("phase_out",
                "Steel legs are being replaced by aluminium legs.", alu));

        // ECO types (the default one with its pipeline, plus product data) and tags.
        types.list();
        String productData = str(types.create(new SaveType("Product data",
                "Name, description, barcode, weight and image changes.", "#7c3aed", null, false)), "id");
        String customer = str(tags.create(new SaveTag("Customer request", "#2563eb")), "id");
        tags.create(new SaveTag("Cost reduction", "#16a34a"));
        tags.create(new SaveTag("Quality", "#dc2626"));

        String request = str(requests.create(new SaveRequest("Customers ask for a lighter desk",
                "Office-fit customers want a desk two people can carry; the steel legs are most of its weight.", desk,
                bomId, "customer", "normal", null)), "id");

        // A BOM change: the draft new version swaps the steel legs for aluminium ones.
        String bomChange = str(changes.create(new CreateChange("bom", "Aluminium legs on the office desk",
                "Replace the four steel legs with aluminium legs: about 6 kg lighter, same load rating.",
                "Customer request for a lighter desk.", "high", desk, bomId, null, null, null, "asap", null, "use_up",
                "continue", "B", admin, request, null, List.of(customer), null, "propose", true)), "id");
        String draft = db.jdbc().sql("SELECT \"newBomId\" FROM \"EngineeringChange\" WHERE id = ?").param(bomChange)
                .query(String.class).single();
        boms.update(draft, in(UpdateBom.class, "lines",
                List.of(line(top, 1, 10), line(alu, 4, 20), line(screws, 1, 30))));

        // A product data change in its own ECO type.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "Office desk 120 cm, oak");
        data.put("weight", "24.5");
        String dataChange = str(changes.create(new CreateChange("product", "Desk: oak in the name, shipping weight",
                "Sales asked for the finish in the product name; the weight is needed for delivery planning.", null,
                "normal", desk, null, null, null, null, "asap", null, null, null, null, admin, null, productData,
                null, data, null, null)), "id");

        return Obj.of("items", 5, "bomId", bomId, "requestId", request, "bomChangeId", bomChange, "draftBomId", draft,
                "productChangeId", dataChange, "changeTypeId", productData);
    }
}

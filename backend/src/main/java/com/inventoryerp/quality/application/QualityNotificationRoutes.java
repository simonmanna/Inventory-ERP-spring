package com.inventoryerp.quality.application;

import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Inspection decisions, nonconformities and CAPAs (ADR-S065). A lot from production reaches the creator of its
 * production order (directly, or through a precast batch or a crushing run).
 */
@Component
public class QualityNotificationRoutes implements RoutedEventSource {

    private static final String AREA = "quality";

    /** Creator of the production order behind lot {@code lotExpr}. */
    private static String orderCreatorOfLot(String lotExpr) {
        return """
                SELECT po."createdBy" FROM "QualityInspectionLot" l JOIN "ProductionOrder" po ON po.id = l."sourceId"
                WHERE l.id = %1$s AND l."organizationId" = :org AND l."sourceType" = 'production_order'
                UNION
                SELECT po."createdBy" FROM "QualityInspectionLot" l JOIN "PrecastCuringBatch" b ON b.id = l."sourceId"
                JOIN "ProductionOrder" po ON po.id = b."orderId"
                WHERE l.id = %1$s AND l."organizationId" = :org AND l."sourceType" = 'precast_batch'
                UNION
                SELECT r."createdBy" FROM "QualityInspectionLot" l JOIN "CrushingRun" r ON r.id = l."sourceId"
                WHERE l.id = %1$s AND l."organizationId" = :org AND l."sourceType" = 'crushing_run'"""
                .formatted(lotExpr);
    }

    @Override
    public List<RoutedEvent> routedEvents() {
        return List.of(
                RoutedEvent.on("quality.lot.decided", "lotId")
                        .area(AREA)
                        .label("Inspection lot released, rejected or downgraded")
                        .record("SELECT \"lotNumber\" FROM \"QualityInspectionLot\" WHERE id = :id AND"
                                + " \"organizationId\" = :org")
                        .party(Party.query("orderOwner", "Created the production order", orderCreatorOfLot(":id")))
                        .party(Party.column("lotCreator", "Opened the lot", "QualityInspectionLot", "createdBy"))
                        .defaults("orderOwner", "lotCreator")
                        .skipWhen("decision", "hold|quarantine|cancel")
                        .title("Lot {lotNumber}: {decision}")
                        .href("/quality/lots/{id}")
                        .build(),
                RoutedEvent.on("quality.ncr.raised", "ncrId")
                        .area(AREA)
                        .label("Nonconformity raised")
                        .record("SELECT \"ncrNumber\", title FROM \"QualityNcr\" WHERE id = :id AND \"organizationId\" ="
                                + " :org")
                        .party(Party.column("owner", "NCR owner", "QualityNcr", "ownerUserId"))
                        .party(Party.query("orderOwner", "Created the production order (when the NCR has no owner)",
                                orderCreatorOfLot("(SELECT n.\"lotId\" FROM \"QualityNcr\" n WHERE n.id = :id AND"
                                        + " n.\"ownerUserId\" IS NULL)")))
                        .defaults("owner", "orderOwner")
                        .title("NCR {ncrNumber} ({severity}): {title}")
                        .href("/quality/ncrs/{id}")
                        .build(),
                RoutedEvent.on("quality.capa.created", "capaId")
                        .area(AREA)
                        .label("Corrective action assigned")
                        .record("SELECT \"capaNumber\", title FROM \"QualityCapa\" WHERE id = :id AND \"organizationId\""
                                + " = :org")
                        .party(Party.column("owner", "CAPA owner", "QualityCapa", "ownerUserId"))
                        .defaults("owner")
                        .title("CAPA {capaNumber} assigned to you: {title}")
                        .href("/quality/capas/{id}")
                        .build(),
                RoutedEvent.on("quality.capa.closed", "capaId")
                        .area(AREA)
                        .label("Corrective action closed")
                        .record("SELECT \"capaNumber\", title FROM \"QualityCapa\" WHERE id = :id AND \"organizationId\""
                                + " = :org")
                        .party(Party.column("owner", "CAPA owner", "QualityCapa", "ownerUserId"))
                        .party(Party.column(Party.CREATOR, "Created the CAPA", "QualityCapa", "createdBy"))
                        .party(Party.query("ncrOwner", "Owner of the nonconformity", """
                                SELECT n."ownerUserId" FROM "QualityCapa" c JOIN "QualityNcr" n ON n.id = c."ncrId"
                                WHERE c.id = :id AND c."organizationId" = :org"""))
                        .defaults("ncrOwner", "owner", Party.CREATOR)
                        .title("CAPA {capaNumber} closed")
                        .body("{title}")
                        .href("/quality/capas/{id}")
                        .build());
    }
}

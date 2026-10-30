package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.org;

import com.inventoryerp.sales.application.QuotationOpportunityPort;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * CRM side of the sales quotation opportunity port (ADR-S011): opportunity visibility and win on conversion. Prices
 * come from the sales pricing engine since phase S2 (it honours the CRM price rules as a tier).
 */
@Component
public class CrmQuotationPorts implements QuotationOpportunityPort {

    private final CrmDb db;
    private final CrmPipelineService pipeline;

    public CrmQuotationPorts(CrmDb db, CrmPipelineService pipeline) {
        this.db = db;
        this.pipeline = pipeline;
    }

    @Override
    public Map<String, Object> visibleOpportunity(String dealId) {
        return pipeline.visible(dealId);
    }

    @Override
    public void markWonIfOpen(String dealId) {
        Map<String, Object> deal = db.row("SELECT status, kind FROM \"Deal\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL", dealId, org());
        if (deal != null && "open".equals(deal.get("status")) && "opportunity".equals(deal.get("kind"))) {
            pipeline.won(dealId);
        }
    }
}

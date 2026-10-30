package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.org;

import com.inventoryerp.sales.application.QuotationOpportunityPort;
import com.inventoryerp.sales.application.QuotationPricingPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * CRM side of the sales quotation ports (ADR-S011): opportunity visibility / win, and price resolution over the CRM
 * customer price rules until the sales pricelist engine (phase S2) takes over.
 */
@Component
public class CrmQuotationPorts implements QuotationOpportunityPort, QuotationPricingPort {

    private final CrmDb db;
    private final CrmPipelineService pipeline;
    private final CrmPricingService pricing;

    public CrmQuotationPorts(CrmDb db, CrmPipelineService pipeline, CrmPricingService pricing) {
        this.db = db;
        this.pipeline = pipeline;
        this.pricing = pricing;
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

    @Override
    public Price price(String partnerId, String productId, BigDecimal qty, LocalDate on) {
        CrmPricingService.Price p = pricing.price(partnerId, productId, qty, on);
        return new Price(p.listPrice(), p.unitPrice(), p.discountPercent(), p.source(), p.ruleId(), p.taxId(),
                p.taxInclusive(), p.description());
    }
}

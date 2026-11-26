package com.inventoryerp.crm.application;

import com.inventoryerp.sales.application.QuotationOpportunityPort;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * CRM side of the sales quotation opportunity port (ADR-S011): opportunity visibility and win on conversion. Prices
 * come from the sales pricing engine since phase S2 (it honours the CRM price rules as a tier).
 */
@Component
public class CrmQuotationPorts implements QuotationOpportunityPort {

    private final CrmPipelineService pipeline;
    private final CrmPlannerService planner;

    public CrmQuotationPorts(CrmPipelineService pipeline, CrmPlannerService planner) {
        this.pipeline = pipeline;
        this.planner = planner;
    }

    @Override
    public Map<String, Object> visibleOpportunity(String dealId) {
        return pipeline.visible(dealId);
    }

    @Override
    public void markWonIfOpen(String dealId, String quotationNumber) {
        pipeline.wonByQuotation(dealId, quotationNumber);
    }

    @Override
    public void quotationSent(String dealId, String partnerId, String number) {
        planner.logQuotationSent(dealId, partnerId, number);
    }

    @Override
    public void quotationEmailed(String dealId, String partnerId, String number, String sentTo, boolean delivered) {
        planner.logQuotationEmail(dealId, partnerId, number, sentTo, delivered);
    }

    @Override
    public void quotationAnsweredOnline(String dealId, String partnerId, String title, String body, String ownerId) {
        planner.logCustomerEvent(dealId, partnerId, title, body, ownerId);
    }
}

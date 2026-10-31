package com.inventoryerp.crm.application;

import com.inventoryerp.sales.application.SalesQuotationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * {@code /crm/quotations} facade (ADR-S010 routes, unchanged JSON). Since ADR-S011 phase S0 quotations are owned by
 * the sales module; every call delegates to {@link SalesQuotationService}.
 */
@Service
public class CrmQuotationService {

    public static final String APPROVAL_ENTITY = SalesQuotationService.APPROVAL_ENTITY;

    private final SalesQuotationService sales;

    public CrmQuotationService(SalesQuotationService sales) {
        this.sales = sales;
    }

    public Map<String, Object> list(Map<String, String> p) {
        return sales.list(p);
    }

    public Map<String, Object> get(String id) {
        return sales.get(id);
    }

    public Map<String, Map<String, Object>> stock(List<String> productIds) {
        return sales.stock(productIds);
    }

    public Map<String, Object> credit(String partnerId, BigDecimal extra) {
        return sales.credit(partnerId, extra);
    }

    public Map<String, Object> create(Map<String, Object> in) {
        return sales.create(in);
    }

    public Map<String, Object> update(String id, Map<String, Object> in) {
        return sales.update(id, in);
    }

    public Map<String, Object> submit(String id) {
        return sales.submit(id);
    }

    public Map<String, Object> approve(String id, String comment) {
        return sales.approve(id, comment);
    }

    public Map<String, Object> reject(String id, String reason) {
        return sales.reject(id, reason);
    }

    public void applyDecision(String id, boolean approved, String comment) {
        sales.applyDecision(id, approved, comment);
    }

    public Map<String, Object> send(String id) {
        return sales.send(id);
    }

    public Map<String, Object> accept(String id) {
        return sales.accept(id);
    }

    public Map<String, Object> decline(String id, String reason) {
        return sales.decline(id, reason);
    }

    public Map<String, Object> cancel(String id) {
        return sales.cancel(id);
    }

    public Map<String, Object> revise(String id) {
        return sales.revise(id);
    }

    public Map<String, Object> delete(String id) {
        return sales.delete(id);
    }

    public Map<String, Object> convert(String id, Map<String, Object> in) {
        return sales.convert(id, in);
    }
}

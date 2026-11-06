package com.inventoryerp.sales.web;

import com.inventoryerp.sales.application.SalesAccess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * SG-1: the one place cost and margin leave Sales. Without {@code sales:cost:read} every matching field is removed from
 * the JSON of Sales routes and of the CRM quotation and pricing facades, however deep it sits.
 */
@RestControllerAdvice(basePackages = {"com.inventoryerp.sales.web", "com.inventoryerp.crm.web"})
public class SalesCostGuard implements ResponseBodyAdvice<Object> {

    private final SalesAccess access;

    public SalesCostGuard(SalesAccess access) {
        this.access = access;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // In CRM only the quotation and pricing facades go through here: other CRM figures are not product cost.
        Class<?> owner = returnType.getContainingClass();
        return owner.getName().startsWith("com.inventoryerp.sales.web.") || owner.getSimpleName().contains("Quotation") || owner.getSimpleName().equals("PricingController");
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        return (body instanceof Map<?, ?> || body instanceof List<?>) && !access.canSeeCost() ? strip(body) : body;
    }

    static Object strip(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<Object, Object> out = new LinkedHashMap<>();
            boolean floor = m.containsKey("floorPrice");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (SalesAccess.isCostField(k) || floor && "minMarginPercent".equals(k)) {
                    continue;
                }
                out.put(e.getKey(), strip(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && m.get("key") instanceof String k && SalesAccess.isCostField(k)) {
                    continue;
                }
                out.add(strip(o));
            }
            return out;
        }
        return v;
    }
}

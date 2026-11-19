package com.inventoryerp.invoicing.application;

import java.util.List;

/**
 * How a customer deposit receipt is headed and closed (title, business name, detail lines, logo, footer). Implemented
 * by the sales module from Sales settings → Deposit receipt and the company settings; invoicing only renders it, so it
 * does not depend on sales.
 */
public interface DepositReceiptBranding {

    /**
     * What the receipt prints. {@code logo} is PNG / JPEG bytes or {@code null}; {@code footer} may be blank (no
     * footer note).
     */
    record Branding(String title, String name, List<String> lines, byte[] logo, String footer, boolean showSignature) {}

    /**
     * Branding of the current organization: the saved layout when {@code unsavedLayout} is {@code null}, otherwise that
     * layout (a partial map from the settings screen, validated and merged onto the saved one; 400 when invalid).
     */
    Branding branding(Object unsavedLayout);
}

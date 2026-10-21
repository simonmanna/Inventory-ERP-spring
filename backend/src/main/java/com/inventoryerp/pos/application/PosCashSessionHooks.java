package com.inventoryerp.pos.application;

import com.inventoryerp.accounting.application.CashSessionHooks;
import org.springframework.stereotype.Component;

/**
 * POS side of the cash-session hooks: the close screen drains the shift's stock postings first (Nest
 * {@code registerStockPostingDrainer}), and the daily reset asks for the Z-report ({@code PosReportsService.zReport},
 * which throws when no frozen snapshot exists — reported by the caller as "Failed to generate Z-report snapshot").
 */
@Component
public class PosCashSessionHooks implements CashSessionHooks {

    private final PosStockPostingService stockPosting;
    private final PosReportsService reports;

    public PosCashSessionHooks(PosStockPostingService stockPosting, PosReportsService reports) {
        this.stockPosting = stockPosting;
        this.reports = reports;
    }

    @Override
    public void drainStockPostings(String cashSessionId) {
        stockPosting.drainSession(cashSessionId);
    }

    @Override
    public boolean generateZReport(String cashSessionId) {
        reports.zReport(cashSessionId);
        return true;
    }
}

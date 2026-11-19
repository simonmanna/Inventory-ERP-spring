package com.inventoryerp.sales.application;

import com.inventoryerp.invoicing.application.DepositReceiptBranding;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Deposit receipt branding from Sales settings → Deposit receipt: the business name (layout override, else the company
 * settings name), the detail lines (custom text, else the company settings address / phone / e-mail / TIN), the
 * company logo, the title, the signature line and the footer note.
 */
@Component
class SalesDepositReceiptBranding implements DepositReceiptBranding {

    private final SalesSettingsService settings;
    private final SalesLetterhead letterhead;

    SalesDepositReceiptBranding(SalesSettingsService settings, SalesLetterhead letterhead) {
        this.settings = settings;
        this.letterhead = letterhead;
    }

    @Override
    public Branding branding(Object unsavedLayout) {
        DepositReceiptLayout saved = settings.depositReceiptLayout();
        DepositReceiptLayout l = unsavedLayout == null ? saved : DepositReceiptLayout.validate(unsavedLayout, saved);
        SalesLetterhead.Head head = letterhead.company(l.companyName());
        List<String> lines = !l.showCompanyDetails() ? List.of() : l.detailLines().isEmpty() ? head.lines()
                : l.detailLines();
        return new Branding(l.title(), head.name(), lines, l.showLogo() ? head.logo() : null, l.footerText(),
                l.showSignature());
    }
}

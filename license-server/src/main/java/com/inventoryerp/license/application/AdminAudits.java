package com.inventoryerp.license.application;

import com.inventoryerp.license.domain.AdminAudit;
import com.inventoryerp.license.domain.AdminAuditRepository;
import com.inventoryerp.license.shared.Json;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who did what, from where. Written in its own transaction so a refused action is still recorded.
 *
 * @see LicenseEvents for the licence's own history
 */
@Service
public class AdminAudits {

    private final AdminAuditRepository audits;

    public AdminAudits(AdminAuditRepository audits) {
        this.audits = audits;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AdminTokens.Principal actor, String action, String target, String ip,
            Map<String, Object> detail) {
        AdminAudit row = new AdminAudit();
        row.setAdminId(actor == null ? null : actor.adminId());
        row.setAdminEmail(actor == null ? null : actor.email());
        row.setAction(action);
        row.setTarget(target);
        row.setIp(ip);
        row.setDetail(detail == null || detail.isEmpty() ? null : Json.write(detail));
        audits.save(row);
    }

    @Transactional(readOnly = true)
    public Page<AdminAudit> recent(int page, int pageSize) {
        return audits.findAllByOrderByAtDesc(
                PageRequest.of(Math.max(0, page - 1), Math.min(200, Math.max(1, pageSize))));
    }
}

package com.inventoryerp.quality.application;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Certificate of analysis PDF of a lot (ADR-S013; OpenPDF like the other printable documents). */
@Service
public class QualityCertificateService {

    public record Certificate(String filename, byte[] content) {}

    private final QualityDb db;
    private final QualityLotService lots;

    public QualityCertificateService(QualityDb db, QualityLotService lots) {
        this.db = db;
        this.lots = lots;
    }

    @Transactional(readOnly = true)
    public Certificate certificate(String lotId) {
        Map<String, Object> lot = lots.get(lotId);
        String company = db.jdbc().sql("SELECT name FROM \"Organization\" WHERE id = ?").param(QualityDb.org())
                .query(String.class).optional().orElse("");
        java.util.List<String> standards = lot.get("specificationId") == null ? java.util.List.of()
                : db.jdbc().sql("SELECT standard FROM \"QualitySpecification\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(lot.get("specificationId"), QualityDb.org()).query(String.class).list();
        String standard = standards.isEmpty() ? null : standards.getFirst();
        return new Certificate("CoA-" + lot.get("lotNumber") + ".pdf",
                CertificateOfAnalysisPdf.render(lot, company, standard));
    }
}

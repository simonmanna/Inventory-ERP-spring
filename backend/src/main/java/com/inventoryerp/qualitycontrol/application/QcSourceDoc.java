package com.inventoryerp.qualitycontrol.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * A business document as the quality gate sees it (ADR-S087 §6). The calling module (manufacturing, procurement,
 * sales) builds it; quality control never reads those modules' tables.
 *
 * @param sourceType {@code production_order | work_order | goods_receipt | sales_delivery}
 * @param operation control-point operation: {@code manufacturing | work_order | receipt | delivery}
 * @param locationId where the document's stock is (output / receive location); null when the document moves none
 * @param lines quantities in the product's base unit
 */
public record QcSourceDoc(
        String sourceType,
        String sourceId,
        String sourceRef,
        String operation,
        String workOrderId,
        String routingOperationId,
        String workCenterId,
        String supplierPartnerId,
        String locationId,
        List<Line> lines) {

    /** One product line; {@code lineId} identifies it for {@code controlPer = quantity}. */
    public record Line(String lineId, String productId, String variantId, BigDecimal baseQty, String batchNumber) {}

    public QcSourceDoc {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** The same document with a stock location (e.g. the actual receive location at completion). */
    public QcSourceDoc at(String location) {
        return new QcSourceDoc(sourceType, sourceId, sourceRef, operation, workOrderId, routingOperationId, workCenterId,
                supplierPartnerId, location, lines);
    }

    /** The same document with other lines (e.g. actual output quantity and batch at completion). */
    public QcSourceDoc withLines(List<Line> newLines) {
        return new QcSourceDoc(sourceType, sourceId, sourceRef, operation, workOrderId, routingOperationId, workCenterId,
                supplierPartnerId, locationId, newLines);
    }
}

package com.deihs.mms.model;

import java.math.BigDecimal;

/**
 * A part consumed by a maintenance request, joined with the part's display
 * fields. The unit cost is snapshotted when the part is used so later price
 * changes do not rewrite history.
 */
public record RequestPart(
        int id,
        int requestId,
        int partId,
        String name,
        String unit,
        int quantity,
        BigDecimal unitCost
) {

    public BigDecimal total() {
        if (unitCost == null) {
            return BigDecimal.ZERO;
        }
        return unitCost.multiply(BigDecimal.valueOf(quantity));
    }
}

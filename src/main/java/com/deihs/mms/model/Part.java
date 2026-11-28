package com.deihs.mms.model;

import java.math.BigDecimal;

/**
 * One row of the parts inventory. {@code lowStock} is derived by the DAO so
 * the table can highlight items that need reordering without extra queries.
 */
public record Part(
        int id,
        String name,
        String sku,
        String unit,
        int quantity,
        int reorderLevel,
        BigDecimal unitCost,
        boolean active,
        boolean lowStock
) {
}

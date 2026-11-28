package com.deihs.mms.model;

import java.math.BigDecimal;

/**
 * The values typed into the part form. Validation lives in
 * {@code PartService}; this is only a carrier.
 */
public record PartDraft(
        String name,
        String sku,
        String unit,
        Integer quantity,
        Integer reorderLevel,
        BigDecimal unitCost
) {
}

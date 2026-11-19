package com.deihs.mms.model;

import java.time.LocalDate;

/**
 * Values from the equipment form. facilityId is null when not installed/linked.
 */
public record EquipmentDraft(
        String propertyNo,
        String name,
        String category,
        String brand,
        String model,
        String serialNo,
        Integer facilityId,
        String condition,
        LocalDate acquisitionDate,
        String notes
) {
}

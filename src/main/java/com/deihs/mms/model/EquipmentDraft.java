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
        String notes,
        LocalDate warrantyUntil,
        String lifecycleStatus,
        LocalDate disposalDate,
        String disposalNotes
) {
    public EquipmentDraft(String propertyNo, String name, String category, String brand, String model,
                          String serialNo, Integer facilityId, String condition, LocalDate acquisitionDate, String notes) {
        this(propertyNo, name, category, brand, model, serialNo, facilityId, condition, acquisitionDate, notes,
                null, "IN_SERVICE", null, null);
    }
}

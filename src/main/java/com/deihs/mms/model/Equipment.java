package com.deihs.mms.model;

import java.time.LocalDate;

/**
 * One row of the equipment inventory. facilityName is denormalized for the list.
 */
public record Equipment(
        int id,
        String propertyNo,
        String name,
        String category,
        String brand,
        String model,
        String serialNo,
        Integer facilityId,
        String facilityName,
        String condition,
        LocalDate acquisitionDate,
        String notes,
        boolean active
) {
}

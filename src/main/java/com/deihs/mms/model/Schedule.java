package com.deihs.mms.model;

import java.time.LocalDate;

/**
 * A preventive maintenance schedule row with denormalized display names.
 */
public record Schedule(
        int id,
        Integer facilityId,
        String facilityName,
        Integer equipmentId,
        String equipmentName,
        String title,
        String description,
        String frequency,
        LocalDate nextDueDate,
        LocalDate lastDoneDate,
        Integer assignedTo,
        String assignedName,
        boolean active
) {
}

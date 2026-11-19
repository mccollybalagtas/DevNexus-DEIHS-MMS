package com.deihs.mms.model;

import java.time.LocalDate;

public record ScheduleDraft(
        Integer facilityId,
        Integer equipmentId,
        String title,
        String description,
        String frequency,
        LocalDate nextDueDate,
        Integer assignedTo
) {
}

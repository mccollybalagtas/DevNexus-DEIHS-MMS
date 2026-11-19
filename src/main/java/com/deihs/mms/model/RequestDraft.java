package com.deihs.mms.model;

import java.io.File;
import java.time.LocalDate;

/**
 * What the New Request form collects, before validation and persistence.
 */
public record RequestDraft(
        String title,
        Integer categoryId,
        Integer facilityId,
        Integer equipmentId,
        String priority,
        String description,
        LocalDate targetDate,
        File photo
) {
}

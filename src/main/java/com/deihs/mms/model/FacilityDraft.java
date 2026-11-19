package com.deihs.mms.model;

/**
 * The values typed into the facility form. Validation lives in
 * {@code FacilityService}; this is only a carrier.
 */
public record FacilityDraft(
        String code,
        String name,
        String building,
        String floor,
        String roomNumber,
        String type,
        String condition,
        String notes
) {
}

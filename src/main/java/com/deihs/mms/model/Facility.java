package com.deihs.mms.model;

/**
 * One row of the facility registry. The two counters are computed by the DAO
 * so the list can show how busy a facility is without extra round trips.
 */
public record Facility(
        int id,
        String code,
        String name,
        String building,
        String floor,
        String roomNumber,
        String type,
        String condition,
        String notes,
        boolean active,
        int equipmentCount,
        int openRequests
) {
}

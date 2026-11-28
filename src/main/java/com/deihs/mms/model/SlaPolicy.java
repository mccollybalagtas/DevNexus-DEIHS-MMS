package com.deihs.mms.model;

public record SlaPolicy(
        int id,
        String priority,
        int targetHours,
        boolean active
) {
    public String targetLabel() {
        if (targetHours % 24 == 0) {
            int days = targetHours / 24;
            return days + (days == 1 ? " day" : " days");
        }
        return targetHours + " hours";
    }
}

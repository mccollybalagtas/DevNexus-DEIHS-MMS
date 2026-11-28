package com.deihs.mms.model;

public record WorkloadSummary(
        int userId,
        String name,
        int openRequests,
        int overdue,
        int dueToday,
        int dueSoon,
        int slaBreached
) {}

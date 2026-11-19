package com.deihs.mms.model;

import java.util.List;

/**
 * Everything the dashboard needs, in one snapshot.
 */
public record DashboardData(
        int pending,
        int pendingNewToday,
        int inProgress,
        int inProgressDueThisWeek,
        int overdue,
        int completedThisMonth,
        int total,
        List<MonthPoint> monthly,
        List<StatusSlice> slices,
        List<RequestRow> recent
) {

    public record MonthPoint(String label, int count) {
    }

    public record StatusSlice(String label, int count, String color) {
    }
}

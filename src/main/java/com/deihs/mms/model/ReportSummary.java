package com.deihs.mms.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ReportSummary(
        LocalDate from,
        LocalDate to,
        int totalRequests,
        int openRequests,
        int completedRequests,
        int overdueRequests,
        BigDecimal totalCost,
        double totalDowntimeHours,
        List<CountRow> byStatus,
        List<CountRow> byPriority,
        List<CountRow> byCategory,
        List<CountRow> byFacility
) {
    public record CountRow(String label, int count, BigDecimal amount) {
        public CountRow(String label, int count) {
            this(label, count, null);
        }
    }
}

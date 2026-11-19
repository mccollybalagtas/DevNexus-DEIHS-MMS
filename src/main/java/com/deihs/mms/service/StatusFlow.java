package com.deihs.mms.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The status workflow of a maintenance request, in one place so the detail
 * screen, the service, and any future report agree on what is legal.
 *
 * <pre>
 *   PENDING ──► ASSIGNED ──► IN_PROGRESS ──► COMPLETED ──► CLOSED
 *      │           │             │  ▲
 *      │           │             ▼  │
 *      │           └──────────► ON_HOLD
 *      │                         │
 *      └──► REJECTED             └──► CANCELLED
 *   (CANCELLED is also reachable from PENDING, ASSIGNED, and ON_HOLD)
 * </pre>
 */
public final class StatusFlow {

    public static final String PENDING = "PENDING";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String ON_HOLD = "ON_HOLD";
    public static final String COMPLETED = "COMPLETED";
    public static final String CLOSED = "CLOSED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";

    /** Statuses a request can never leave. */
    private static final Set<String> TERMINAL = Set.of(CLOSED, REJECTED, CANCELLED);

    /** The moves an administrator or the maintenance head may make. */
    private static final Map<String, List<String>> TRANSITIONS = Map.of(
            PENDING, List.of(ASSIGNED, REJECTED, CANCELLED),
            ASSIGNED, List.of(IN_PROGRESS, ON_HOLD, CANCELLED),
            IN_PROGRESS, List.of(ON_HOLD, COMPLETED),
            ON_HOLD, List.of(IN_PROGRESS, CANCELLED),
            COMPLETED, List.of(CLOSED),
            CLOSED, List.of(),
            REJECTED, List.of(),
            CANCELLED, List.of()
    );

    /** Maintenance personnel only move their own work along this subset. */
    private static final Map<String, List<String>> PERSONNEL_TRANSITIONS = Map.of(
            ASSIGNED, List.of(IN_PROGRESS, ON_HOLD),
            IN_PROGRESS, List.of(ON_HOLD, COMPLETED),
            ON_HOLD, List.of(IN_PROGRESS)
    );

    /** Moves that require the actor to explain why. */
    private static final Set<String> REQUIRES_REMARKS = Set.of(REJECTED, ON_HOLD, CANCELLED);

    private StatusFlow() {
    }

    public static boolean isTerminal(String status) {
        return status != null && TERMINAL.contains(status);
    }

    public static boolean requiresRemarks(String toStatus) {
        return toStatus != null && REQUIRES_REMARKS.contains(toStatus);
    }

    /** Every status the actor may move {@code from} to, for their role. */
    public static List<String> allowed(String from, boolean personnelOnly) {
        if (from == null) {
            return List.of();
        }
        Map<String, List<String>> table = personnelOnly ? PERSONNEL_TRANSITIONS : TRANSITIONS;
        return table.getOrDefault(from, List.of());
    }

    public static boolean canMove(String from, String to, boolean personnelOnly) {
        return to != null && allowed(from, personnelOnly).contains(to);
    }
}

package com.deihs.mms.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The allowed status moves for a request. Kept in one place so the detail
 * screen, the service, and any future report agree on what is legal.
 */
public final class Workflow {

    private static final Map<String, List<String>> NEXT = Map.of(
            "PENDING", List.of("ASSIGNED", "REJECTED", "CANCELLED"),
            "ASSIGNED", List.of("IN_PROGRESS", "ON_HOLD", "CANCELLED"),
            "IN_PROGRESS", List.of("COMPLETED", "ON_HOLD", "CANCELLED"),
            "ON_HOLD", List.of("IN_PROGRESS", "CANCELLED"),
            "COMPLETED", List.of("CLOSED"),
            "CLOSED", List.of(),
            "REJECTED", List.of(),
            "CANCELLED", List.of()
    );

    /** Moves a maintenance worker may make on their own assigned task. */
    private static final Set<String> PERSONNEL_MOVES = Set.of(
            "ASSIGNED>IN_PROGRESS",
            "IN_PROGRESS>COMPLETED",
            "ON_HOLD>IN_PROGRESS"
    );

    private Workflow() {
    }

    public static List<String> nextStatuses(String from) {
        return NEXT.getOrDefault(from, List.of());
    }

    public static boolean canTransition(String from, String to) {
        return nextStatuses(from).contains(to);
    }

    public static boolean isTerminal(String status) {
        return nextStatuses(status).isEmpty();
    }

    public static boolean isPersonnelMove(String from, String to) {
        return PERSONNEL_MOVES.contains(from + ">" + to);
    }
}

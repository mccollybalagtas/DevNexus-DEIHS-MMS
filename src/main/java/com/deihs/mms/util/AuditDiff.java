package com.deihs.mms.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Builds simple old/new diff payloads for audit_logs. */
public final class AuditDiff {

    private final Map<String, String> oldVals = new LinkedHashMap<>();
    private final Map<String, String> newVals = new LinkedHashMap<>();
    private boolean changed;

    public static AuditDiff builder() { return new AuditDiff(); }

    public AuditDiff put(String field, Object oldValue, Object newValue) {
        String o = str(oldValue);
        String n = str(newValue);
        if (!Objects.equals(o, n)) {
            oldVals.put(field, o);
            newVals.put(field, n);
            changed = true;
        }
        return this;
    }

    public boolean hasChanges() { return changed; }
    public String oldValues() { return changed ? format(oldVals) : null; }
    public String newValues() { return changed ? format(newVals) : null; }

    private static String str(Object v) {
        if (v == null) return "";
        return String.valueOf(v).trim();
    }

    private static String format(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append(": ").append(e.getValue());
        }
        return sb.toString();
    }

    private AuditDiff() {}
}

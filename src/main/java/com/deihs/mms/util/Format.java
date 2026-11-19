package com.deihs.mms.util;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Format {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a");
    private static final NumberFormat PESO = NumberFormat.getCurrencyInstance(Locale.of("en", "PH"));

    private Format() {
    }

    public static String date(LocalDate d) {
        return d == null ? "—" : DATE.format(d);
    }

    public static String dateTime(LocalDateTime d) {
        return d == null ? "—" : DATE_TIME.format(d);
    }

    public static String time(LocalDateTime d) {
        return d == null ? "—" : TIME.format(d);
    }

    public static String peso(BigDecimal amount) {
        if (amount == null) {
            return "₱0.00";
        }
        return PESO.format(amount);
    }

    /** Human readable file size: 512 B, 12.4 KB, 3.1 MB. */
    public static String fileSize(long bytes) {
        if (bytes <= 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
    }

    /** Human readable status: IN_PROGRESS -> In Progress */
    public static String pretty(String enumValue) {
        if (enumValue == null) {
            return "—";
        }
        String lower = enumValue.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Relative time such as "2h ago", used in the recent requests table. */
    public static String relative(LocalDateTime then) {
        if (then == null) {
            return "—";
        }
        long minutes = java.time.Duration.between(then, LocalDateTime.now()).toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = hours / 24;
        if (days < 30) {
            return days + "d ago";
        }
        return date(then.toLocalDate());
    }
}

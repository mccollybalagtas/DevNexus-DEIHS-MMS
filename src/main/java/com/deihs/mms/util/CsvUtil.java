package com.deihs.mms.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small CSV reader/writer with RFC4180-style quoting. */
public final class CsvUtil {

    private CsvUtil() {}

    public static void write(Path file, List<String> headers, List<List<String>> rows) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writeLine(w, headers);
            for (List<String> row : rows) {
                writeLine(w, row);
            }
        }
    }

    private static void writeLine(BufferedWriter w, List<String> cols) throws IOException {
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) w.write(',');
            w.write(escape(cols.get(i)));
        }
        w.write("\r\n");
    }

    public static String escape(String value) {
        if (value == null) return "";
        boolean needs = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        String v = value.replace("\"", "\"\"");
        return needs ? "\"" + v + "\"" : v;
    }

    public static List<Map<String, String>> readWithHeaders(Path file) throws IOException {
        List<List<String>> all = parse(file);
        if (all.isEmpty()) return List.of();
        List<String> headers = all.get(0);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int r = 1; r < all.size(); r++) {
            List<String> line = all.get(r);
            if (line.size() == 1 && (line.get(0) == null || line.get(0).isBlank())) continue;
            Map<String, String> map = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String key = headers.get(c) == null ? "" : headers.get(c).trim();
                String val = c < line.size() ? line.get(c) : "";
                map.put(key, val == null ? "" : val);
            }
            rows.add(map);
        }
        return rows;
    }

    public static List<List<String>> parse(Path file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StringBuilder field = new StringBuilder();
            List<String> row = new ArrayList<>();
            boolean inQuotes = false;
            int ch;
            boolean started = false;
            while ((ch = r.read()) != -1) {
                started = true;
                char c = (char) ch;
                if (inQuotes) {
                    if (c == '"') {
                        r.mark(1);
                        int next = r.read();
                        if (next == '"') {
                            field.append('"');
                        } else {
                            inQuotes = false;
                            if (next != -1) r.reset();
                        }
                    } else {
                        field.append(c);
                    }
                } else {
                    if (c == '"') {
                        inQuotes = true;
                    } else if (c == ',') {
                        row.add(field.toString());
                        field.setLength(0);
                    } else if (c == '\n') {
                        row.add(field.toString());
                        field.setLength(0);
                        rows.add(row);
                        row = new ArrayList<>();
                    } else if (c == '\r') {
                        // ignore; handle \n
                    } else {
                        field.append(c);
                    }
                }
            }
            if (started) {
                row.add(field.toString());
                rows.add(row);
            }
        }
        // strip UTF-8 BOM from first header
        if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
            String first = rows.get(0).get(0);
            if (first != null && first.startsWith("\uFEFF")) {
                rows.get(0).set(0, first.substring(1));
            }
        }
        return rows;
    }
}

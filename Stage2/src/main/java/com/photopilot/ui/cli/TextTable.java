package com.photopilot.ui.cli;

import java.util.*;

/**
 * Plain-text table for terminal reports: columns fit the given width by wrapping the widest wrappable cells (never by
 * truncating), headers wrap too, and widths count East Asian wide characters as two columns so Chinese text aligns.
 * Output is ASCII apart from the cell text, so redirected output stays readable.
 */
final class TextTable {
    enum Align { LEFT, RIGHT }
    record Column(String header, Align align, int minWidth, boolean wrap) {
        static Column text(String header, int minWidth) { return new Column(header, Align.LEFT, minWidth, true); }
        static Column fixed(String header) { return new Column(header, Align.LEFT, 1, false); }
        static Column number(String header) { return new Column(header, Align.RIGHT, 1, false); }
    }
    private static final int GAP = 2;
    private final List<Column> columns;
    private final List<List<String>> rows = new ArrayList<>();

    TextTable(Column... columns) { this.columns = List.of(columns); }
    TextTable row(String... cells) {
        if (cells.length != columns.size()) throw new IllegalArgumentException("Expected " + columns.size() + " cells");
        rows.add(List.of(cells)); return this;
    }
    boolean isEmpty() { return rows.isEmpty(); }

    List<String> render(int width) {
        int n = columns.size();
        int[] widths = new int[n];
        for (int i = 0; i < n; i++) {
            widths[i] = width(columns.get(i).header());
            for (var row : rows) widths[i] = Math.max(widths[i], width(row.get(i)));
        }
        int total = Arrays.stream(widths).sum() + GAP * (n - 1);
        while (total > width) {
            int widest = -1;
            for (int i = 0; i < n; i++) {
                var column = columns.get(i);
                if (column.wrap() && widths[i] > column.minWidth() && (widest < 0 || widths[i] > widths[widest])) widest = i;
            }
            if (widest < 0) break;
            widths[widest]--; total--;
        }
        if (total > width) return stacked(width);
        var lines = new ArrayList<String>();
        lines.addAll(line(columns.stream().map(Column::header).toList(), widths));
        lines.add("-".repeat(Math.min(width, total)));
        for (var row : rows) lines.addAll(line(row, widths));
        return lines;
    }

    /**
     * Narrow terminals: one "header: value" line per cell, rows separated by a blank line, nothing dropped. A column
     * without a header (a row marker) contributes its value alone, and only when it has one.
     */
    private List<String> stacked(int width) {
        var lines = new ArrayList<String>();
        for (var row : rows) {
            if (!lines.isEmpty()) lines.add("");
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).header().isBlank()) { if (!row.get(i).isBlank()) lines.addAll(hanging(row.get(i), width, 2)); continue; }
                lines.addAll(hanging(columns.get(i).header() + ": " + row.get(i), width, 2));
            }
        }
        return lines;
    }

    private List<String> line(List<String> cells, int[] widths) {
        var wrapped = new ArrayList<List<String>>(); int height = 1;
        for (int i = 0; i < cells.size(); i++) { var parts = wrap(cells.get(i), widths[i]); wrapped.add(parts); height = Math.max(height, parts.size()); }
        var lines = new ArrayList<String>();
        for (int r = 0; r < height; r++) {
            var text = new StringBuilder();
            for (int i = 0; i < cells.size(); i++) {
                String part = r < wrapped.get(i).size() ? wrapped.get(i).get(r) : "";
                if (i > 0) text.append(" ".repeat(GAP));
                text.append(pad(part, widths[i], columns.get(i).align()));
            }
            lines.add(strip(text.toString()));
        }
        return lines;
    }

    /** Terminal columns of {@code text}: wide and full-width characters count as two. */
    static int width(String text) {
        int columns = 0;
        for (int i = 0; i < text.length(); ) { int cp = text.codePointAt(i); columns += wide(cp) ? 2 : 1; i += Character.charCount(cp); }
        return columns;
    }
    private static boolean wide(int cp) {
        return cp >= 0x1100 && cp <= 0x115F || cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F || cp >= 0xAC00 && cp <= 0xD7A3
            || cp >= 0xF900 && cp <= 0xFAFF || cp >= 0xFE30 && cp <= 0xFE4F || cp >= 0xFF00 && cp <= 0xFF60 || cp >= 0xFFE0 && cp <= 0xFFE6
            || cp >= 0x20000 && cp <= 0x3FFFD;
    }
    static String pad(String text, int width, Align align) {
        String space = " ".repeat(Math.max(0, width - width(text)));
        return align == Align.RIGHT ? space + text : text + space;
    }
    static String strip(String line) { return line.replaceAll("\\s+$", ""); }

    /** Word wrap to {@code width} columns; a word longer than the width is split, never cut off. */
    static List<String> wrap(String text, int width) {
        width = Math.max(1, width);
        var lines = new ArrayList<String>();
        var current = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            if (word.isEmpty() && current.isEmpty()) continue;
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (width(candidate) <= width) { current.setLength(0); current.append(candidate); continue; }
            if (!current.isEmpty()) { lines.add(current.toString()); current.setLength(0); }
            // Split an over-long word (or unspaced Chinese text) at character boundaries.
            for (int i = 0; i < word.length(); ) {
                int cp = word.codePointAt(i); String ch = new String(Character.toChars(cp));
                if (width(current + ch) > width && !current.isEmpty()) { lines.add(current.toString()); current.setLength(0); }
                current.append(ch); i += Character.charCount(cp);
            }
        }
        if (!current.isEmpty() || lines.isEmpty()) lines.add(current.toString());
        return lines;
    }

    /**
     * {@code text} wrapped to {@code width}: its own leading spaces stay on the first line, and every continuation line
     * is indented by {@code indent} columns (at least as far as the first line).
     */
    static List<String> hanging(String text, int width, int indent) {
        String body = text.stripLeading(); int lead = text.length() - body.length(); int step = Math.max(indent, lead);
        var parts = wrap(body, Math.max(1, width - step));
        var lines = new ArrayList<String>();
        for (int i = 0; i < parts.size(); i++) lines.add(" ".repeat(i == 0 ? lead : step) + parts.get(i));
        return lines;
    }
}

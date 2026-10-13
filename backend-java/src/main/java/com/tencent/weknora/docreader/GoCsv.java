package com.tencent.weknora.docreader;

import java.util.ArrayList;
import java.util.List;

/** encoding/csv behavior with LazyQuotes=true, TrimLeadingSpace=true and inferred field count. */
public final class GoCsv {
    private GoCsv() { }
    static String toMarkdown(byte[] data) {
        List<List<String>> records = readRecords(data,true);
        if (records.isEmpty()) return "";
        var header = records.getFirst();
        StringBuilder out = new StringBuilder("| " + String.join(" | ", header) + " |\n|");
        out.append(" --- |".repeat(header.size())).append('\n');
        for (var record : records.subList(1, records.size())) out.append("| ").append(String.join(" | ", record)).append(" |\n");
        return out.toString();
    }
    /** The document converter infers width; source structure accepts ragged records like Go FieldsPerRecord=-1. */
    public static List<List<String>> readRecords(byte[] data,boolean inferFields) {return read(GoUtf8.decode(data),inferFields);}
    private static List<List<String>> read(String input,boolean inferFields) {
        Cursor cursor = new Cursor(input);
        List<List<String>> records = new ArrayList<>();
        int fieldsPerRecord = 0;
        while (true) {
            String line;
            do { line = cursor.readLine(); } while (line != null && (line.isEmpty() || line.equals("\n")));
            if (line == null) return records;
            int recordLine = cursor.lineNumber;
            List<String> fields = new ArrayList<>();
            int at = 0;
            boolean complete = false;
            while (!complete) {
                while (at < line.length()) {
                    int cp = line.codePointAt(at);
                    if (!GoUtf8.isSpace(cp)) break;
                    at += Character.charCount(cp);
                }
                if (at == line.length() || line.charAt(at) != '"') {
                    int comma = line.indexOf(',', at);
                    int end = comma >= 0 ? comma : line.length() - (line.endsWith("\n") ? 1 : 0);
                    fields.add(line.substring(at, Math.max(at, end)));
                    if (comma >= 0) at = comma + 1; else complete = true;
                } else {
                    at++;
                    StringBuilder field = new StringBuilder();
                    while (true) {
                        int quote = line.indexOf('"', at);
                        if (quote >= 0) {
                            field.append(line, at, quote); at = quote + 1;
                            if (at < line.length() && line.charAt(at) == '"') { field.append('"'); at++; }
                            else if (at < line.length() && line.charAt(at) == ',') { at++; fields.add(field.toString()); break; }
                            else if (at == line.length() || at == line.length() - 1 && line.charAt(at) == '\n') {
                                fields.add(field.toString()); complete = true; break;
                            } else { field.append('"'); } // LazyQuotes preserves a bare quote.
                        } else {
                            field.append(line, at, line.length());
                            line = cursor.readLine(); at = 0;
                            if (line == null) { fields.add(field.toString()); complete = true; break; }
                        }
                    }
                }
            }
            if (fieldsPerRecord == 0) fieldsPerRecord = fields.size();
            else if (inferFields && fields.size() != fieldsPerRecord) throw new IllegalArgumentException("csv conversion failed: record on line " + recordLine + ": wrong number of fields");
            records.add(List.copyOf(fields));
        }
    }
    private static final class Cursor {
        private final String input;
        private int offset;
        private int lineNumber;
        Cursor(String input) { this.input = input; }
        String readLine() {
            lineNumber++;
            if (offset >= input.length()) return null;
            int newline = input.indexOf('\n', offset);
            int end = newline < 0 ? input.length() : newline + 1;
            String line = input.substring(offset, end); offset = end;
            if (line.endsWith("\r\n")) line = line.substring(0, line.length() - 2) + "\n";
            else if (newline < 0 && line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            return line;
        }
    }
}

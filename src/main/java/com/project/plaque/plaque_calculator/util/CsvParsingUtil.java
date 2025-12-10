package com.project.plaque.plaque_calculator.util;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.CSVParser;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility helpers for parsing and normalizing CSV text where rows are separated by semicolons
 * in the UI but columns follow RFC-4180 quoting rules.
 */
public final class CsvParsingUtil {

    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT
            .builder()
            .setTrim(false)
            .setIgnoreSurroundingSpaces(false)
            .setIgnoreEmptyLines(false)
            .build();

    private CsvParsingUtil() {}

    public static List<List<String>> parseRows(String manualData) {
        if (manualData == null || manualData.trim().isEmpty()) {
            return List.of();
        }

        // Split by semicolon but respect quoted values that may contain semicolons
        List<String> rowStrings = splitRespectingQuotes(manualData, ';');

        List<List<String>> rows = new ArrayList<>();
        for (int rowIdx = 0; rowIdx < rowStrings.size(); rowIdx++) {
            String row = rowStrings.get(rowIdx);
            String trimmed = row == null ? "" : row.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                List<String> parsedRow = parseSingleRow(trimmed);
                if (!parsedRow.isEmpty()) {
                    rows.add(parsedRow);
                }
            } catch (Exception ex) {
                // Fallback: split by comma respecting quotes
                List<String> fallbackRow = splitRespectingQuotes(trimmed, ',').stream()
                        .map(cell -> {
                            if (cell == null) return "";
                            String cleaned = cell.trim();
                            // Remove surrounding quotes if present
                            if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                                cleaned = cleaned.substring(1, cleaned.length() - 1);
                                // Unescape doubled quotes
                                cleaned = cleaned.replace("\"\"", "\"");
                            }
                            return cleaned;
                        })
                        .toList();
                if (!fallbackRow.isEmpty()) {
                    rows.add(new ArrayList<>(fallbackRow));
                }
            }
        }
        return rows;
    }

    /**
     * Split a string by a delimiter, but respect quoted sections.
     * Quoted sections (enclosed in double quotes) are not split even if they contain the delimiter.
     * Handles escaped quotes ("") within quoted sections.
     */
    private static List<String> splitRespectingQuotes(String input, char delimiter) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        int quoteToggleCount = 0;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '"') {
                // Check for escaped quote ("")
                if (inQuotes && i + 1 < input.length() && input.charAt(i + 1) == '"') {
                    // This is an escaped quote, add both and skip next
                    current.append(c);
                    current.append(input.charAt(i + 1));
                    i++; // Skip the next quote
                } else {
                    // Toggle quote state
                    inQuotes = !inQuotes;
                    quoteToggleCount++;
                    current.append(c);
                }
            } else if (c == delimiter && !inQuotes) {
                // Split here
                result.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }

        // Add the last segment
        if (!current.isEmpty()) {
            result.add(current.toString());
        }


        return result;
    }

    private static List<String> parseSingleRow(String row) {
        try (CSVParser parser = CSVParser.parse(new StringReader(row), CSV_FORMAT)) {
            List<String> parsed = new ArrayList<>();
            for (CSVRecord record : parser) {
                record.forEach(value -> parsed.add(value == null ? "" : value));
            }
            return parsed;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to parse CSV row", ex);
        }
    }

    public static String toCompactString(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        List<String> serialized = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            serialized.add(writeRow(row));
        }
        return String.join(";", serialized);
    }

    private static String writeRow(List<String> row) {
        if (row == null || row.isEmpty()) {
            return "";
        }
        try (StringWriter writer = new StringWriter();
             CSVPrinter printer = new CSVPrinter(writer, CSV_FORMAT)) {
            printer.printRecord(row);
            return writer.toString().trim();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize CSV row", ex);
        }
    }

    public static List<List<String>> normalizeRows(String manualData) {
        List<List<String>> parsed = parseRows(manualData);
        if (parsed.isEmpty()) {
            return List.of();
        }
        int expectedCols = parsed.get(0).size();
        List<List<String>> normalized = new ArrayList<>(parsed.size());
        for (List<String> row : parsed) {
            List<String> copy = new ArrayList<>(row);
            if (copy.size() < expectedCols) {
                for (int i = copy.size(); i < expectedCols; i++) {
                    copy.add("");
                }
            } else if (copy.size() > expectedCols) {
                copy = new ArrayList<>(copy.subList(0, expectedCols));
            }
            normalized.add(Collections.unmodifiableList(copy));
        }
        return Collections.unmodifiableList(normalized);
    }

    /**
     * Convert rows to a simple semicolon-separated format for RIC JAR consumption.
     * Cell values containing commas or semicolons are handled by replacing them with a placeholder
     * character (vertical bar |) to avoid parsing issues in the external RIC jar.
     * This is specifically for RIC computation where the jar expects simple CSV without quotes.
     *
     * @param rows List of rows, each row is a list of cell values
     * @return Semicolon-separated string where each row is comma-separated, with internal commas/semicolons replaced
     */
    public static String toRicCompatibleString(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        List<String> serialized = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            // Replace commas and semicolons within cell values with a safe placeholder
            List<String> sanitizedCells = new ArrayList<>(row.size());
            for (String cell : row) {
                if (cell == null) {
                    sanitizedCells.add("");
                } else {
                    // Replace internal commas and semicolons with vertical bar to preserve data integrity
                    // Also remove any double quotes that might interfere with parsing
                    String sanitized = cell.replace(",", "|").replace(";", "|").replace("\"", "");
                    sanitizedCells.add(sanitized.trim());
                }
            }
            String rowStr = String.join(",", sanitizedCells);
            if (!rowStr.replace(",", "").trim().isEmpty()) {
                serialized.add(rowStr);
            }
        }
        return String.join(";", serialized);
    }
}

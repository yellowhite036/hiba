package org.gslab.fileProtection.performance;

import org.gslab.fileProtection.performance.impl.CsvReportWriter;
import org.gslab.fileProtection.performance.impl.HtmlReportWriter;
import org.gslab.fileProtection.performance.impl.JsonReportWriter;

/**
 * Report Writer Factory - Creates appropriate PerformanceReportWriter instances
 *
 * <p>Follows Clean Code principles:
 * <ul>
 *   <li>Factory Pattern: Centralizes object creation logic</li>
 *   <li>Type Safety: Uses enum instead of strings</li>
 *   <li>Open/Closed: Easy to add new formats without modifying client code</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * // Simple creation
 * PerformanceReportWriter jsonWriter = ReportWriterFactory.create(ReportFormat.JSON);
 * jsonWriter.save(record, Path.of("result.json"));
 *
 * // CSV with append mode
 * PerformanceReportWriter csvWriter = ReportWriterFactory.create(ReportFormat.CSV_APPEND);
 * csvWriter.save(record, Path.of("all-tests.csv"));
 *
 * // Multiple formats in a loop
 * for (ReportFormat format : ReportFormat.values()) {
 *     PerformanceReportWriter writer = ReportWriterFactory.create(format);
 *     writer.save(record, getPathForFormat(format));
 * }
 * </pre>
 */
public class ReportWriterFactory {

    /**
     * Report Format Enumeration - Type-safe format selection
     */
    public enum ReportFormat {
        /**
         * JSON format - Structured data with Jackson serialization
         */
        JSON("json"),

        /**
         * CSV format (overwrite mode) - Creates new file or overwrites existing
         */
        CSV("csv"),

        /**
         * CSV format (append mode) - Appends to existing file, creates if not exists
         */
        CSV_APPEND("csv"),

        /**
         * HTML format - Visual report with embedded CSS
         */
        HTML("html");

        private final String extension;

        ReportFormat(String extension) {
            this.extension = extension;
        }

        /**
         * Get the file extension for this format
         *
         * @return File extension (e.g., "json", "csv", "html")
         */
        public String getExtension() {
            return extension;
        }

        @Override
        public String toString() {
            return this.name();
        }
    }

    /**
     * Create a PerformanceReportWriter for the specified format
     *
     * <p>This is the main factory method following the Factory Pattern.
     * Each format is mapped to its corresponding implementation.
     *
     * @param format The desired report format
     * @return A PerformanceReportWriter instance for the specified format
     * @throws IllegalArgumentException If format is null
     */
    public static PerformanceReportWriter create(ReportFormat format) {
        if (format == null) {
            throw new IllegalArgumentException("Report format cannot be null");
        }

        return switch (format) {
            case JSON -> new JsonReportWriter();
            case CSV -> new CsvReportWriter(false); // Overwrite mode
            case CSV_APPEND -> new CsvReportWriter(true); // Append mode
            case HTML -> new HtmlReportWriter();
        };
    }

    /**
     * Create a PerformanceReportWriter from format name (case-insensitive)
     *
     * <p>Convenience method for string-based format selection.
     * Useful for configuration files or command-line arguments.
     *
     * @param formatName The format name (e.g., "json", "csv", "html")
     * @return A PerformanceReportWriter instance for the specified format
     * @throws IllegalArgumentException If formatName is null, empty, or invalid
     */
    public static PerformanceReportWriter createFromString(String formatName) {
        if (formatName == null || formatName.trim().isEmpty()) {
            throw new IllegalArgumentException("Format name cannot be null or empty");
        }

        String normalizedName = formatName.trim().toUpperCase();

        try {
            ReportFormat format = ReportFormat.valueOf(normalizedName);
            return create(format);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid format name: " + formatName + ". Valid formats: JSON, CSV, CSV_APPEND, HTML");
        }
    }

    /**
     * Get all available report formats
     *
     * @return Array of all supported ReportFormat values
     */
    public static ReportFormat[] getAvailableFormats() {
        return ReportFormat.values();
    }

    /**
     * Private constructor to prevent instantiation
     * This is a utility class with only static methods
     */
    private ReportWriterFactory() {
        throw new UnsupportedOperationException("ReportWriterFactory is a utility class and cannot be instantiated");
    }
}

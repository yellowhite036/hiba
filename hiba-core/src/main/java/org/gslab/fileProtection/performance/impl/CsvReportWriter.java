package org.gslab.fileProtection.performance.impl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.gslab.fileProtection.performance.PerformanceReportWriter;
import org.gslab.fileProtection.performance.PerformanceTestRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CSV Report Writer - Writes performance test reports in CSV format
 *
 * <p>Follows Clean Code principles:
 * <ul>
 *   <li>Single Responsibility: Only handles CSV file operations</li>
 *   <li>Clear constructor: append mode explicitly specified</li>
 *   <li>Smart header management: writes header only when needed</li>
 * </ul>
 *
 * <p>Features:
 * <ul>
 *   <li>Two modes: Overwrite or Append</li>
 *   <li>Automatic header insertion for new files</li>
 *   <li>Excel/spreadsheet compatible format</li>
 *   <li>Chinese column names for readability</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * // Overwrite mode - creates new file
 * CsvReportWriter writer = new CsvReportWriter();
 * writer.saveAll(records, Path.of("results.csv"));
 *
 * // Append mode - adds to existing file
 * CsvReportWriter appendWriter = new CsvReportWriter(true);
 * appendWriter.save(record, Path.of("all-tests.csv"));
 * </pre>
 */
public class CsvReportWriter implements PerformanceReportWriter {

    private static final Logger logger = LoggerFactory.getLogger(CsvReportWriter.class);

    private final boolean appendMode;

    /**
     * Create CSV writer in overwrite mode (default)
     */
    public CsvReportWriter() {
        this(false);
    }

    /**
     * Create CSV writer with specified mode
     *
     * @param appendMode true to append to existing file, false to overwrite
     */
    public CsvReportWriter(boolean appendMode) {
        this.appendMode = appendMode;
    }

    /**
     * Save a single test record to CSV file
     * In append mode, adds to existing file; in overwrite mode, creates new file
     *
     * @param record   The test record to save
     * @param filePath Path to the output CSV file
     * @throws IOException              If file writing fails
     * @throws IllegalArgumentException If record or filePath is null
     */
    @Override
    public void save(PerformanceTestRecord record, Path filePath) throws IOException {
        validateInputs(record, filePath);
        ensureDirectoryExists(filePath.getParent());

        File file = filePath.toFile();
        boolean fileExists = file.exists() && file.length() > 0;

        // Determine if we need to write header
        boolean needsHeader = !fileExists || !appendMode;

        StandardOpenOption[] options = appendMode
                ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};

        try (BufferedWriter writer = Files.newBufferedWriter(filePath, options)) {
            // Write header if needed
            if (needsHeader) {
                writer.write(PerformanceTestRecord.getCsvHeader());
                writer.newLine();
            }

            // Write data row
            writer.write(record.toCsvRow());
            writer.newLine();
        }

        String mode = appendMode ? "Appended" : "Saved";
        logger.info("{} performance test record to CSV: {}", mode, filePath);
    }

    /**
     * Save multiple test records to CSV file
     * Always overwrites existing file and writes header
     *
     * @param records  List of test records to save
     * @param filePath Path to the output CSV file
     * @throws IOException              If file writing fails
     * @throws IllegalArgumentException If records is empty or filePath is null
     */
    @Override
    public void saveAll(List<PerformanceTestRecord> records, Path filePath) throws IOException {
        validateInputs(records, filePath);
        ensureDirectoryExists(filePath.getParent());

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()))) {
            // Write header
            writer.write(PerformanceTestRecord.getCsvHeader());
            writer.newLine();

            // Write data rows
            for (PerformanceTestRecord record : records) {
                writer.write(record.toCsvRow());
                writer.newLine();
            }
        }

        logger.info("Saved {} performance test records to CSV: {}", records.size(), filePath);
    }

    /**
     * Get the file extension for CSV format
     *
     * @return "csv"
     */
    @Override
    public String getFileExtension() {
        return "csv";
    }

    /**
     * Validate single record input
     */
    private void validateInputs(PerformanceTestRecord record, Path filePath) {
        if (record == null) {
            throw new IllegalArgumentException("Test record cannot be null");
        }
        if (filePath == null || filePath.toString().trim().isEmpty()) {
            throw new IllegalArgumentException("File path cannot be null or empty");
        }
    }

    /**
     * Validate multiple records input
     */
    private void validateInputs(List<PerformanceTestRecord> records, Path filePath) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("Records list cannot be null or empty");
        }
        if (filePath == null || filePath.toString().trim().isEmpty()) {
            throw new IllegalArgumentException("File path cannot be null or empty");
        }
    }

    /**
     * Ensure that the directory exists, create if necessary
     */
    private void ensureDirectoryExists(Path directory) throws IOException {
        if (directory != null && !Files.exists(directory)) {
            Files.createDirectories(directory);
            logger.debug("Created directory: {}", directory);
        }
    }
}

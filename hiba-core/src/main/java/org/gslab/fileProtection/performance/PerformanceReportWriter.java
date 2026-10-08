package org.gslab.fileProtection.performance;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Performance Report Writer Interface - Writes performance test reports to various formats
 *
 * <p>Follows Clean Code principles:
 * <ul>
 *   <li>Single Responsibility: Each implementation handles ONE format</li>
 *   <li>Open/Closed: Add new formats by creating new implementations, not modifying existing code</li>
 *   <li>Interface Segregation: Simple, focused interface</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * PerformanceReportWriter jsonWriter = new JsonReportWriter();
 * jsonWriter.save(record, Path.of("result.json"));
 *
 * PerformanceReportWriter csvWriter = new CsvReportWriter(true); // append mode
 * csvWriter.save(record, Path.of("results.csv"));
 * </pre>
 */
public interface PerformanceReportWriter {

    /**
     * Save a single test record to file
     *
     * @param record   The test record to save
     * @param filePath Path to the output file
     * @throws IOException              If file writing fails
     * @throws IllegalArgumentException If record or filePath is null/invalid
     */
    void save(PerformanceTestRecord record, Path filePath) throws IOException;

    /**
     * Save multiple test records to file
     *
     * @param records  List of test records to save
     * @param filePath Path to the output file
     * @throws IOException              If file writing fails
     * @throws IllegalArgumentException If records is null/empty or filePath is null/invalid
     */
    void saveAll(List<PerformanceTestRecord> records, Path filePath) throws IOException;

    /**
     * Get the file extension for this format
     *
     * @return File extension (e.g., "json", "csv", "html")
     */
    String getFileExtension();
}

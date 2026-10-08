package org.gslab.fileProtection.performance.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gslab.fileProtection.performance.PerformanceReportWriter;
import org.gslab.fileProtection.performance.PerformanceTestRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON Report Writer - Writes performance test reports in JSON format
 *
 * <p>Follows Clean Code principles:
 * <ul>
 *   <li>Single Responsibility: Only handles JSON serialization</li>
 *   <li>Uses Jackson for reliable JSON generation</li>
 *   <li>Formatted output with proper indentation</li>
 *   <li>ISO-8601 date format (not Unix timestamps)</li>
 * </ul>
 *
 * <p>Features:
 * <ul>
 *   <li>Pretty-printed JSON for readability</li>
 *   <li>Proper LocalDateTime serialization</li>
 *   <li>Automatic directory creation</li>
 * </ul>
 */
public class JsonReportWriter implements PerformanceReportWriter {

    private static final Logger logger = LoggerFactory.getLogger(JsonReportWriter.class);

    /**
     * Shared ObjectMapper configuration - thread-safe and reusable
     * Configured once for optimal performance
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Save a single test record to JSON file
     *
     * @param record   The test record to save
     * @param filePath Path to the output JSON file
     * @throws IOException              If file writing fails
     * @throws IllegalArgumentException If record or filePath is null
     */
    @Override
    public void save(PerformanceTestRecord record, Path filePath) throws IOException {
        validateInputs(record, filePath);
        ensureDirectoryExists(filePath.getParent());

        OBJECT_MAPPER.writeValue(filePath.toFile(), record);

        logger.info("Performance test record saved to JSON: {}", filePath);
    }

    /**
     * Save multiple test records to JSON file
     *
     * @param records  List of test records to save
     * @param filePath Path to the output JSON file
     * @throws IOException              If file writing fails
     * @throws IllegalArgumentException If records is empty or filePath is null
     */
    @Override
    public void saveAll(List<PerformanceTestRecord> records, Path filePath) throws IOException {
        validateInputs(records, filePath);
        ensureDirectoryExists(filePath.getParent());

        OBJECT_MAPPER.writeValue(filePath.toFile(), records);

        logger.info("Saved {} performance test records to JSON: {}", records.size(), filePath);
    }

    /**
     * Get the file extension for JSON format
     *
     * @return "json"
     */
    @Override
    public String getFileExtension() {
        return "json";
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

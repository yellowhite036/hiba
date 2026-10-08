package org.gslab.fileProtection.performance.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.gslab.fileProtection.performance.PerformanceReportWriter;
import org.gslab.fileProtection.performance.PerformanceTestRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTML Report Writer - Generates visual HTML reports for performance tests
 *
 * <p>Follows Clean Code principles:
 * <ul>
 *   <li>Single Responsibility: Only handles HTML report generation</li>
 *   <li>Small methods: HTML generation broken into logical sections</li>
 *   <li>Self-contained output: Embedded CSS, no external dependencies</li>
 * </ul>
 *
 * <p>Features:
 * <ul>
 *   <li>Responsive design with modern CSS</li>
 *   <li>Summary statistics dashboard</li>
 *   <li>Detailed test results table</li>
 *   <li>Chinese language interface</li>
 *   <li>Color-coded success/failure indicators</li>
 * </ul>
 */
public class HtmlReportWriter implements PerformanceReportWriter {

    private static final Logger logger = LoggerFactory.getLogger(HtmlReportWriter.class);

    /**
     * Save a single test record to HTML file
     *
     * @param record   The test record to save
     * @param filePath Path to the output HTML file
     * @throws IOException              If file writing fails
     * @throws IllegalArgumentException If record or filePath is null
     */
    @Override
    public void save(PerformanceTestRecord record, Path filePath) throws IOException {
        saveAll(List.of(record), filePath);
    }

    /**
     * Save multiple test records to HTML file
     *
     * @param records  List of test records to save
     * @param filePath Path to the output HTML file
     * @throws IOException              If file writing fails
     * @throws IllegalArgumentException If records is empty or filePath is null
     */
    @Override
    public void saveAll(List<PerformanceTestRecord> records, Path filePath) throws IOException {
        validateInputs(records, filePath);
        ensureDirectoryExists(filePath.getParent());

        String html = generateHtmlContent(records);
        Files.writeString(filePath, html);

        logger.info("HTML report generated: {}", filePath);
    }

    /**
     * Get the file extension for HTML format
     *
     * @return "html"
     */
    @Override
    public String getFileExtension() {
        return "html";
    }

    /**
     * Generate complete HTML content from test records
     */
    private String generateHtmlContent(List<PerformanceTestRecord> records) {
        StringBuilder html = new StringBuilder();

        appendHtmlHeader(html);
        appendSummarySection(html, records);
        appendDetailedResultsTable(html, records);
        appendHtmlFooter(html);

        return html.toString();
    }

    /**
     * Append HTML header with CSS styling
     */
    private void appendHtmlHeader(StringBuilder html) {
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-TW\">\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\">\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("    <title>區塊鏈性能測試報告</title>\n");
        html.append("    <style>\n");
        html.append(
                "        body { font-family: 'Segoe UI', Arial, sans-serif; margin: 20px; background: #f5f5f5; }\n");
        html.append(
                "        .container { max-width: 1200px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n");
        html.append("        h1 { color: #333; border-bottom: 3px solid #4CAF50; padding-bottom: 10px; }\n");
        html.append("        h2 { color: #555; margin-top: 30px; }\n");
        html.append(
                "        .summary { background: #f9f9f9; padding: 15px; border-left: 4px solid #4CAF50; margin: 20px 0; }\n");
        html.append("        table { width: 100%; border-collapse: collapse; margin: 20px 0; }\n");
        html.append("        th, td { padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }\n");
        html.append("        th { background: #4CAF50; color: white; }\n");
        html.append("        tr:hover { background: #f5f5f5; }\n");
        html.append("        .success { color: #4CAF50; font-weight: bold; }\n");
        html.append("        .failure { color: #f44336; font-weight: bold; }\n");
        html.append("        .metric { display: inline-block; margin: 10px 20px 10px 0; }\n");
        html.append("        .metric-label { font-weight: bold; color: #666; }\n");
        html.append("        .metric-value { color: #333; font-size: 1.2em; }\n");
        html.append("        .timestamp { color: #999; font-size: 0.9em; }\n");
        html.append("    </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("    <div class=\"container\">\n");
        html.append("        <h1>區塊鏈性能測試報告</h1>\n");
        html.append("        <p class=\"timestamp\">生成時間: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("</p>\n");
    }

    /**
     * Append summary statistics section
     */
    private void appendSummarySection(StringBuilder html, List<PerformanceTestRecord> records) {
        html.append("        <div class=\"summary\">\n");
        html.append("            <h2>測試概覽</h2>\n");
        html.append(
                        "            <div class=\"metric\"><span class=\"metric-label\">總測試數:</span> <span class=\"metric-value\">")
                .append(records.size())
                .append("</span></div>\n");

        long successCount =
                records.stream().filter(PerformanceTestRecord::isSuccess).count();
        long failureCount = records.size() - successCount;
        html.append(
                        "            <div class=\"metric\"><span class=\"metric-label\">成功:</span> <span class=\"metric-value success\">")
                .append(successCount)
                .append("</span></div>\n");
        html.append(
                        "            <div class=\"metric\"><span class=\"metric-label\">失敗:</span> <span class=\"metric-value failure\">")
                .append(failureCount)
                .append("</span></div>\n");

        long totalFiles =
                records.stream().mapToLong(PerformanceTestRecord::getFileCount).sum();
        html.append(
                        "            <div class=\"metric\"><span class=\"metric-label\">總文件數:</span> <span class=\"metric-value\">")
                .append(totalFiles)
                .append("</span></div>\n");

        long totalDuration =
                records.stream().mapToLong(PerformanceTestRecord::getDurationMs).sum();
        html.append(
                        "            <div class=\"metric\"><span class=\"metric-label\">總耗時:</span> <span class=\"metric-value\">")
                .append(String.format("%.2f", totalDuration / 1000.0))
                .append(" 秒</span></div>\n");
        html.append("        </div>\n");
    }

    /**
     * Append detailed test results table
     */
    private void appendDetailedResultsTable(StringBuilder html, List<PerformanceTestRecord> records) {
        html.append("        <h2>測試詳情</h2>\n");
        html.append("        <table>\n");
        html.append("            <thead>\n");
        html.append("                <tr>\n");
        html.append("                    <th>測試 ID</th>\n");
        html.append("                    <th>測試名稱</th>\n");
        html.append("                    <th>區塊鏈</th>\n");
        html.append("                    <th>文件數</th>\n");
        html.append("                    <th>耗時 (秒)</th>\n");
        html.append("                    <th>吞吐量 (文件/秒)</th>\n");
        html.append("                    <th>狀態</th>\n");
        html.append("                </tr>\n");
        html.append("            </thead>\n");
        html.append("            <tbody>\n");

        for (PerformanceTestRecord record : records) {
            html.append("                <tr>\n");
            html.append("                    <td>")
                    .append(escapeHtml(record.getTestId()))
                    .append("</td>\n");
            html.append("                    <td>")
                    .append(escapeHtml(record.getTestName()))
                    .append("</td>\n");
            html.append("                    <td>").append("Blockchain").append("</td>\n");
            html.append("                    <td>")
                    .append(record.getFileCount())
                    .append("</td>\n");
            html.append("                    <td>")
                    .append(String.format("%.2f", record.getDurationMs() / 1000.0))
                    .append("</td>\n");

            // Calculate throughput
            double throughput =
                    record.getDurationMs() > 0 ? (record.getFileCount() * 1000.0) / record.getDurationMs() : 0;
            html.append("                    <td>")
                    .append(String.format("%.2f", throughput))
                    .append("</td>\n");

            html.append("                    <td class=\"")
                    .append(record.isSuccess() ? "success" : "failure")
                    .append("\">")
                    .append(record.isSuccess() ? "✓ 成功" : "✗ 失敗")
                    .append("</td>\n");
            html.append("                </tr>\n");
        }

        html.append("            </tbody>\n");
        html.append("        </table>\n");
    }

    /**
     * Append HTML footer
     */
    private void appendHtmlFooter(StringBuilder html) {
        html.append("    </div>\n");
        html.append("</body>\n");
        html.append("</html>\n");
    }

    /**
     * Escape HTML special characters to prevent XSS
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Validate input arguments
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

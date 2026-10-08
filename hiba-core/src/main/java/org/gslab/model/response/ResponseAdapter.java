package org.gslab.model.response;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gslab.model.response.FileOperationResult.FileInfo;
import org.gslab.model.response.UnifiedError.ErrorCode;

/**
 * Transforms legacy response formats to unified format.
 *
 * Handles conversion of:
 * - Old getFile responses (with "transfers", "transferred", etc.)
 * - Old BlockchainFileProtect responses (with "processedFiles", etc.)
 * - TaskResult-based responses with inconsistent status fields
 *
 * This allows gradual migration without breaking existing code.
 */
public class ResponseAdapter {

    /**
     * Transform legacy service result to FileOperationResult.
     * Detects service type and applies appropriate conversion.
     */
    @SuppressWarnings("unchecked")
    public static FileOperationResult transformToFileOperation(Map<String, Object> legacyResult) {
        if (legacyResult == null) {
            return FileOperationResult.builder().build();
        }

        // Unwrap nested result if present (backward compatibility)
        // Support both formats: flat {transfers: [...]} and nested {result: {transfers: [...]}}
        Map<String, Object> actualResult = legacyResult;

        // Check if we have a nested result structure
        if (legacyResult.containsKey("result")) {
            Object nestedObj = legacyResult.get("result");
            if (nestedObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedResult = (Map<String, Object>) nestedObj;

                // If nested result contains file operation data, unwrap it
                if (nestedResult.containsKey("transfers") || nestedResult.containsKey("processedFiles")) {
                    actualResult = nestedResult;
                }
            }
        }

        // Detect service type by checking for characteristic fields
        if (actualResult.containsKey("processedFiles")) {
            return transformBlockchainProtectResult(actualResult);
        } else if (actualResult.containsKey("transfers") || actualResult.containsKey("transferred")) {
            return transformGetFileResult(actualResult);
        } else if (actualResult.containsKey("files")) {
            // New standardized format with files array (BlockchainFileIntegrity, etc.)
            return transformStandardizedFileResult(actualResult);
        } else {
            // Generic transformation for unknown formats
            return transformGenericFileResult(actualResult);
        }
    }

    /**
     * Transform BlockchainFileProtect legacy response.
     *
     * Old format:
     * {
     *   "processedFiles": [...],
     *   "totalFiles": 2,
     *   "successCount": 2,
     *   "failureCount": 0,
     *   "receiveTargetPath": "...",
     *   "updateResource": [...]
     * }
     */
    @SuppressWarnings("unchecked")
    private static FileOperationResult transformBlockchainProtectResult(Map<String, Object> result) {
        FileOperationResult.Builder builder = FileOperationResult.builder();

        List<Map<String, Object>> processedFiles = (List<Map<String, Object>>) result.get("processedFiles");
        if (processedFiles != null) {
            for (Map<String, Object> file : processedFiles) {
                FileInfo fileInfo = FileInfo.builder()
                        .fileName((String) file.get("filename"))
                        .fileHash((String) file.get("fileHash"))
                        .fileSize(getLongValue(file, "fileSize"))
                        .localPath((String) file.get("storedPath"))
                        .success(getBooleanValue(file, "success", true))
                        .contentType((String) file.get("contentType"))
                        .protectedFile(getBooleanValue(file, "protected", false))
                        .timestamp(getLongValue(file, "timestamp"))
                        .build();
                builder.addFile(fileInfo);
            }
        }

        Integer totalFiles = getIntValue(result, "totalFiles");
        if (totalFiles != null) {
            builder.totalFiles(totalFiles);
        }

        Integer successCount = getIntValue(result, "successCount");
        if (successCount != null) {
            builder.successCount(successCount);
        }

        Integer failureCount = getIntValue(result, "failureCount");
        if (failureCount != null) {
            builder.failureCount(failureCount);
        }

        // Preserve additional metadata
        String receiveTargetPath = (String) result.get("receiveTargetPath");
        if (receiveTargetPath != null) {
            builder.metadata("receiveTargetPath", receiveTargetPath);
        }

        List<String> updateResource = (List<String>) result.get("updateResource");
        if (updateResource != null) {
            builder.metadata("updateResource", updateResource);
        }

        return builder.build();
    }

    /**
     * Transform getFile legacy response.
     *
     * Old format:
     * {
     *   "transfers": [
     *     {
     *       "fileName": "...",
     *       "transferred": true,
     *       "sourceNode": "...",
     *       "fileMetadata": {...},
     *       "filePath": "...",
     *       "fileHash": "...",
     *       "fileId": "..."
     *     }
     *   ],
     *   "totalTransferCount": 1,
     *   "fileName": "...",
     *   "primaryFileId": "...",
     *   "primaryFileHash": "..."
     * }
     */
    @SuppressWarnings("unchecked")
    private static FileOperationResult transformGetFileResult(Map<String, Object> result) {
        FileOperationResult.Builder builder = FileOperationResult.builder();

        // Handle transfers array
        List<Map<String, Object>> transfers = (List<Map<String, Object>>) result.get("transfers");
        if (transfers != null) {
            for (Map<String, Object> transfer : transfers) {
                Object fileMetadataObj = transfer.get("fileMetadata");
                Map<String, Object> fileMetadata = convertFileMetadataToMap(fileMetadataObj);

                String transferFileName = (String) transfer.get("fileName");
                String localPath = (String) transfer.get("localPath");
                if (localPath == null) {
                    String targetPath = (String) transfer.get("targetPath");
                    if (targetPath != null && transferFileName != null) {
                        localPath = Paths.get(targetPath, transferFileName).toString();
                    } else {
                        localPath = (String) transfer.get("filePath");
                    }
                }

                FileInfo.FileInfoBuilder fileBuilder = FileInfo.builder()
                        .fileName(transferFileName)
                        .fileHash((String) transfer.get("fileHash"))
                        .fileId((String) transfer.get("fileId"))
                        .localPath(localPath)
                        .success(getBooleanValue(transfer, "transferred", false))
                        .sourceNode((String) transfer.get("sourceNode"));

                // Extract metadata if available
                if (fileMetadata != null) {
                    fileBuilder.fileSize(getLongValue(fileMetadata, "fileSize"));
                    fileBuilder.metadata(fileMetadata);
                }

                builder.addFile(fileBuilder.build());
            }
        }

        // Handle single file case (no transfers array)
        if (transfers == null && result.containsKey("fileName")) {
            FileInfo fileInfo = FileInfo.builder()
                    .fileName((String) result.get("fileName"))
                    .fileHash((String) result.get("primaryFileHash"))
                    .fileId((String) result.get("primaryFileId"))
                    .fileSize(getLongValue(result, "fileSize"))
                    .success(getBooleanValue(result, "transferred", false))
                    .build();
            builder.addFile(fileInfo);
        }

        Integer totalFiles = getIntValue(result, "totalTransferCount");
        if (totalFiles == null) {
            totalFiles = getIntValue(result, "totalFiles");
        }
        if (totalFiles != null) {
            builder.totalFiles(totalFiles);
        }

        // Add metadata availability info
        Boolean metadataAvailable = (Boolean) result.get("metadataAvailable");
        if (metadataAvailable != null) {
            builder.metadata("metadataAvailable", metadataAvailable);
        }

        Integer metadataConsistentCount = getIntValue(result, "metadataConsistentCount");
        if (metadataConsistentCount != null) {
            builder.metadata("metadataConsistentCount", metadataConsistentCount);
        }

        return builder.build();
    }

    /**
     * Transform new standardized format with files array.
     * Used by BlockchainFileIntegrity and other services using the new response
     * format.
     *
     * Format:
     * {
     * "totalFiles": 1,
     * "files": [{
     * "fileName": "...",
     * "localPath": "...",
     * "success": true/false,
     * "errorMessage": "..." (optional)
     * }],
     * "successCount": 0/1,
     * "failureCount": 0/1 (optional)
     * }
     */
    @SuppressWarnings("unchecked")
    private static FileOperationResult transformStandardizedFileResult(Map<String, Object> result) {
        FileOperationResult.Builder builder = FileOperationResult.builder();

        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
        if (files != null) {
            for (Map<String, Object> file : files) {
                FileInfo.FileInfoBuilder fileBuilder = FileInfo.builder()
                        .fileName((String) file.get("fileName"))
                        .fileHash((String) file.get("fileHash"))
                        .fileSize(getLongValue(file, "fileSize"))
                        .localPath((String) file.get("localPath"))
                        .success(getBooleanValue(file, "success", true))
                        .errorMessage((String) file.get("errorMessage"));

                // Preserve additional file-level metadata
                Map<String, Object> fileMetadata = new HashMap<>();
                for (String key : file.keySet()) {
                    if (!isStandardFileField(key)) {
                        fileMetadata.put(key, file.get(key));
                    }
                }
                if (!fileMetadata.isEmpty()) {
                    fileBuilder.metadata(fileMetadata);
                }

                builder.addFile(fileBuilder.build());
            }
        }

        Integer totalFiles = getIntValue(result, "totalFiles");
        if (totalFiles != null) {
            builder.totalFiles(totalFiles);
        }

        Integer successCount = getIntValue(result, "successCount");
        if (successCount != null) {
            builder.successCount(successCount);
        }

        Integer failureCount = getIntValue(result, "failureCount");
        if (failureCount != null) {
            builder.failureCount(failureCount);
        }

        return builder.build();
    }

    /**
     * Check if a key is a standard file field that shouldn't be included in
     * metadata.
     */
    private static boolean isStandardFileField(String key) {
        return "fileName".equals(key)
                || "fileHash".equals(key)
                || "fileSize".equals(key)
                || "localPath".equals(key)
                || "success".equals(key)
                || "errorMessage".equals(key)
                || "fileId".equals(key)
                || "sourceNode".equals(key)
                || "contentType".equals(key)
                || "protectedFile".equals(key)
                || "timestamp".equals(key);
    }

    /**
     * Generic transformation for unknown file operation formats.
     * Attempts best-effort extraction of file information.
     */
    @SuppressWarnings("unchecked")
    private static FileOperationResult transformGenericFileResult(Map<String, Object> result) {
        FileOperationResult.Builder builder = FileOperationResult.builder();

        // Try to extract any file-like data
        String fileName = (String) result.get("fileName");
        if (fileName != null) {
            FileInfo fileInfo = FileInfo.builder()
                    .fileName(fileName)
                    .fileHash((String) result.get("fileHash"))
                    .fileId((String) result.get("fileId"))
                    .fileSize(getLongValue(result, "fileSize"))
                    .localPath((String) result.get("filePath"))
                    .success(true)
                    .build();
            builder.addFile(fileInfo);
        }

        return builder.build();
    }

    /**
     * Extract UnifiedError from legacy response.
     * Handles various error representation formats.
     */
    public static UnifiedError extractError(Map<String, Object> legacyResult) {
        if (legacyResult == null) {
            return new UnifiedError(ErrorCode.INTERNAL_ERROR, "Null result");
        }

        // Check for explicit error message
        String errorMessage = (String) legacyResult.get("errorMessage");
        if (errorMessage != null) {
            ErrorCode code = determineErrorCode(errorMessage, legacyResult);
            return new UnifiedError(code, errorMessage);
        }

        // Check for failure state
        Boolean success = (Boolean) legacyResult.get("success");
        Boolean state = (Boolean) legacyResult.get("state");

        if ((success != null && !success) || (state != null && !state)) {
            ErrorCode code = determineErrorCode(null, legacyResult);
            String message = (String)
                    legacyResult.getOrDefault("message", "Operation failed: " + legacyResult.get("serviceName"));
            return new UnifiedError(code, message);
        }

        return null; // No error found
    }

    /**
     * Determine appropriate error code from legacy response.
     */
    private static ErrorCode determineErrorCode(String errorMessage, Map<String, Object> result) {
        if (errorMessage != null) {
            String msg = errorMessage.toLowerCase();
            if (msg.contains("timeout")) return ErrorCode.TIMEOUT;
            if (msg.contains("not found")) return ErrorCode.FILE_NOT_FOUND;
            if (msg.contains("checksum") || msg.contains("hash")) return ErrorCode.CHECKSUM_MISMATCH;
            if (msg.contains("transfer")) return ErrorCode.TRANSFER_FAILED;
            if (msg.contains("blockchain")) return ErrorCode.BLOCKCHAIN_ERROR;
            if (msg.contains("network")) return ErrorCode.NETWORK_ERROR;
            if (msg.contains("storage")) return ErrorCode.STORAGE_ERROR;
        }

        // Check for specific service-related errors
        String nodeName = (String) result.get("nodeName");
        if ("unknown".equals(nodeName)) {
            return ErrorCode.NODE_UNREACHABLE;
        }

        return ErrorCode.INTERNAL_ERROR;
    }

    /**
     * Extract execution time from various legacy formats.
     */
    public static Long extractExecutionTime(Map<String, Object> result) {
        if (result == null) return null;

        // Try different field names used in legacy responses
        Object execTime = result.get("executionTimeMs");
        if (execTime == null) execTime = result.get("taskExecutionTime");
        if (execTime == null) execTime = result.get("exeTime");
        if (execTime == null) {
            Object spendTime = result.get("spendTime");
            if (spendTime instanceof Double) {
                return ((Double) spendTime).longValue();
            }
        }

        if (execTime instanceof Number) {
            return ((Number) execTime).longValue();
        }

        return null;
    }

    /**
     * Determine if legacy response indicates success.
     * Handles multiple inconsistent success indicators.
     */
    public static boolean isSuccess(Map<String, Object> result) {
        if (result == null) return false;

        // Check explicit success field
        Boolean success = (Boolean) result.get("success");
        if (success != null) return success;

        // Check state field (used in some responses)
        Boolean state = (Boolean) result.get("state");
        if (state != null) return state;

        // Check status string
        String status = (String) result.get("status");
        if (status != null) {
            return "success".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status);
        }

        // Check for error message presence
        String errorMessage = (String) result.get("errorMessage");
        if (errorMessage != null && !errorMessage.isEmpty()) {
            return false;
        }

        // Default to true if no negative indicators
        return true;
    }

    // Helper methods
    private static long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    private static Integer getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    private static boolean getBooleanValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    /**
     * Convert FileMetadata object to Map for JSON serialization.
     * FileMetadata objects cannot be directly serialized by JSONObject.
     * This method handles both Map and FileMetadata object types.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> convertFileMetadataToMap(Object fileMetadataObj) {
        if (fileMetadataObj == null) {
            return null;
        }

        // If already a Map, return it
        if (fileMetadataObj instanceof Map) {
            return (Map<String, Object>) fileMetadataObj;
        }

        // If it's a FileMetadata object, convert it to Map using reflection
        // FileMetadata has: id, fileName, fileHash, uploadTime, uploader, fileSize, filePath, description
        Map<String, Object> metadata = new HashMap<>();

        try {
            Class<?> clazz = fileMetadataObj.getClass();

            // Use reflection to get all getter methods
            java.lang.reflect.Method[] methods = clazz.getMethods();
            for (java.lang.reflect.Method method : methods) {
                String methodName = method.getName();

                // Extract property name from getter method
                if (methodName.startsWith("get")
                        && methodName.length() > 3
                        && method.getParameterCount() == 0
                        && !methodName.equals("getClass")) {

                    String propertyName = methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
                    Object value = method.invoke(fileMetadataObj);

                    if (value != null) {
                        // Convert LocalDateTime and other complex types to String
                        if (value.getClass().getName().contains("LocalDateTime")
                                || value.getClass().getName().contains("LocalDate")) {
                            metadata.put(propertyName, value.toString());
                        } else {
                            metadata.put(propertyName, value);
                        }
                    }
                } else if (methodName.startsWith("is") && methodName.length() > 2 && method.getParameterCount() == 0) {
                    // Handle boolean getters (isXxx)
                    String propertyName = methodName.substring(2, 3).toLowerCase() + methodName.substring(3);
                    Object value = method.invoke(fileMetadataObj);
                    if (value != null) {
                        metadata.put(propertyName, value);
                    }
                }
            }
        } catch (Exception e) {
            // If reflection fails, try toString parsing as fallback
            String str = fileMetadataObj.toString();
            // FileMetadata{id='...', fileName='...', fileHash='...', ...}
            if (str.startsWith("FileMetadata{") && str.endsWith("}")) {
                // This is a last resort - just return a simple representation
                metadata.put("_raw", str);
            }
        }

        return metadata.isEmpty() ? null : metadata;
    }
}

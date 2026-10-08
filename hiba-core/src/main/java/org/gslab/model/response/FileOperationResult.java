package org.gslab.model.response;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standardized result structure for file operations.
 *
 * Used by:
 * - getFile: file retrieval and transfer
 * - BlockchainFileProtect: file upload and blockchain protection
 * - Any other file-based service
 *
 * Eliminates inconsistency between "processedFiles", "transfers", "resultFiles".
 * One structure, one contract.
 */
public class FileOperationResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final List<FileInfo> files;
    private final int totalFiles;
    private final int successCount;
    private final int failureCount;
    private final Map<String, Object> metadata;

    private FileOperationResult(Builder builder) {
        this.files = builder.files;
        this.totalFiles = builder.totalFiles > 0 ? builder.totalFiles : files.size();
        this.successCount = builder.successCount >= 0
                ? builder.successCount
                : (int) files.stream().filter(FileInfo::isSuccess).count();
        this.failureCount = builder.failureCount >= 0
                ? builder.failureCount
                : (int) files.stream().filter(f -> !f.isSuccess()).count();
        this.metadata = builder.metadata;
    }

    public List<FileInfo> getFiles() {
        return files;
    }

    public int getTotalFiles() {
        return totalFiles;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();

        List<Map<String, Object>> filesList = new ArrayList<>();
        for (FileInfo file : files) {
            filesList.add(file.toMap());
        }
        map.put("files", filesList);
        map.put("totalFiles", totalFiles);
        map.put("successCount", successCount);

        if (failureCount > 0) {
            map.put("failureCount", failureCount);
        }

        if (metadata != null && !metadata.isEmpty()) {
            map.putAll(metadata);
        }

        return map;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<FileInfo> files = new ArrayList<>();
        private int totalFiles = -1;
        private int successCount = -1;
        private int failureCount = -1;
        private Map<String, Object> metadata = new HashMap<>();

        public Builder addFile(FileInfo file) {
            this.files.add(file);
            return this;
        }

        public Builder files(List<FileInfo> files) {
            this.files = files;
            return this;
        }

        public Builder totalFiles(int totalFiles) {
            this.totalFiles = totalFiles;
            return this;
        }

        public Builder successCount(int successCount) {
            this.successCount = successCount;
            return this;
        }

        public Builder failureCount(int failureCount) {
            this.failureCount = failureCount;
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }

        public FileOperationResult build() {
            return new FileOperationResult(this);
        }
    }

    /**
     * Standardized file information structure.
     * Covers both upload and download scenarios.
     */
    public static class FileInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String fileId;
        private final String fileName;
        private final String fileHash;
        private final long fileSize;
        private final String localPath;
        private final boolean success;
        private final String sourceNode;
        private final String contentType;
        private final boolean protectedFile;
        private final Long timestamp;
        private final Map<String, Object> metadata;
        private final String errorMessage;

        private FileInfo(FileInfoBuilder builder) {
            this.fileId = builder.fileId;
            this.fileName = builder.fileName;
            this.fileHash = builder.fileHash;
            this.fileSize = builder.fileSize;
            this.localPath = builder.localPath;
            this.success = builder.success;
            this.sourceNode = builder.sourceNode;
            this.contentType = builder.contentType;
            this.protectedFile = builder.protectedFile;
            this.timestamp = builder.timestamp;
            this.metadata = builder.metadata;
            this.errorMessage = builder.errorMessage;
        }

        public String getFileId() {
            return fileId;
        }

        public String getFileName() {
            return fileName;
        }

        public String getFileHash() {
            return fileHash;
        }

        public long getFileSize() {
            return fileSize;
        }

        public String getLocalPath() {
            return localPath;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getSourceNode() {
            return sourceNode;
        }

        public String getContentType() {
            return contentType;
        }

        public boolean isProtected() {
            return protectedFile;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();

            if (fileId != null) map.put("fileId", fileId);
            if (fileName != null) map.put("fileName", fileName);
            if (fileHash != null) map.put("fileHash", fileHash);
            if (fileSize > 0) map.put("fileSize", fileSize);
            if (localPath != null) map.put("localPath", localPath);

            map.put("success", success);

            if (sourceNode != null) map.put("sourceNode", sourceNode);
            if (contentType != null) map.put("contentType", contentType);
            if (protectedFile) map.put("protected", protectedFile);
            if (timestamp != null) map.put("timestamp", timestamp);
            if (errorMessage != null) map.put("errorMessage", errorMessage);

            if (metadata != null && !metadata.isEmpty()) {
                map.put("metadata", metadata);
            }

            return map;
        }

        public static FileInfoBuilder builder() {
            return new FileInfoBuilder();
        }

        public static class FileInfoBuilder {
            private String fileId;
            private String fileName;
            private String fileHash;
            private long fileSize;
            private String localPath;
            private boolean success = true;
            private String sourceNode;
            private String contentType;
            private boolean protectedFile = false;
            private Long timestamp;
            private Map<String, Object> metadata;
            private String errorMessage;

            public FileInfoBuilder fileId(String fileId) {
                this.fileId = fileId;
                return this;
            }

            public FileInfoBuilder fileName(String fileName) {
                this.fileName = fileName;
                return this;
            }

            public FileInfoBuilder fileHash(String fileHash) {
                this.fileHash = fileHash;
                return this;
            }

            public FileInfoBuilder fileSize(long fileSize) {
                this.fileSize = fileSize;
                return this;
            }

            public FileInfoBuilder localPath(String localPath) {
                this.localPath = localPath;
                return this;
            }

            public FileInfoBuilder success(boolean success) {
                this.success = success;
                return this;
            }

            public FileInfoBuilder sourceNode(String sourceNode) {
                this.sourceNode = sourceNode;
                return this;
            }

            public FileInfoBuilder contentType(String contentType) {
                this.contentType = contentType;
                return this;
            }

            public FileInfoBuilder protectedFile(boolean protectedFile) {
                this.protectedFile = protectedFile;
                return this;
            }

            public FileInfoBuilder timestamp(Long timestamp) {
                this.timestamp = timestamp;
                return this;
            }

            public FileInfoBuilder metadata(Map<String, Object> metadata) {
                this.metadata = metadata;
                return this;
            }

            public FileInfoBuilder errorMessage(String errorMessage) {
                this.errorMessage = errorMessage;
                return this;
            }

            public FileInfo build() {
                if (fileName == null) {
                    throw new IllegalStateException("fileName is required");
                }
                return new FileInfo(this);
            }
        }
    }
}

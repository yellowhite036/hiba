package org.gslab.fileProtection.adapter;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.gslab.fileProtection.contract.FileProtectionContract;
import org.gslab.fileProtection.model.FileMetadata;

/**
 * FileMetadata 轉換器 將應用程式的 FileMetadata 轉換為合約的 FileProtectionContract.FileMetadata
 */
public class FileMetadataAdapter {

    private FileMetadataAdapter() {}

    /**
     * 將單個 FileMetadata 轉換為合約格式
     */
    public static FileProtectionContract.FileMetadata toContractFileMetadata(FileMetadata source) {
        if (source == null) {
            throw new IllegalArgumentException("Source FileMetadata cannot be null");
        }

        validateForBlockchain(source);

        return new FileProtectionContract.FileMetadata(
                source.getId(),
                source.getFileName(),
                source.getFileHash(),
                source.getUploader(),
                BigInteger.valueOf(source.getFileSize()),
                source.getFilePath(),
                source.getDescription() != null ? source.getDescription() : "");
    }

    /**
     * 將 FileMetadata 列表轉換為合約格式列表
     */
    public static List<FileProtectionContract.FileMetadata> toContractFileMetadataList(List<FileMetadata> sourceList) {
        if (sourceList == null) {
            throw new IllegalArgumentException("Source list cannot be null");
        }

        validateListForBlockchain(sourceList);

        return sourceList.stream()
                .map(FileMetadataAdapter::toContractFileMetadata)
                .collect(Collectors.toList());
    }

    /**
     * 從合約的 StoredFileMetadata 轉換為應用程式格式
     */
    public static FileMetadata fromContractStoredFileMetadata(FileProtectionContract.StoredFileMetadata source) {
        if (source == null) {
            throw new IllegalArgumentException("Source stored contract metadata cannot be null");
        }

        return new FileMetadata(
                source.id,
                source.fileName,
                source.fileHash,
                LocalDateTime.ofEpochSecond(source.timestamp.longValue(), 0, ZoneOffset.UTC),
                source.uploader,
                source.fileSize.longValue(),
                source.filePath,
                source.description);
    }

    /**
     * 驗證 FileMetadata 是否適合上傳到區塊鏈
     */
    public static void validateForBlockchain(FileMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("FileMetadata cannot be null");
        }

        if (metadata.getFileName() == null || metadata.getFileName().trim().isEmpty()) {
            throw new IllegalArgumentException("File name cannot be empty");
        }

        if (metadata.getFileHash() == null || metadata.getFileHash().trim().isEmpty()) {
            throw new IllegalArgumentException("File hash cannot be empty");
        }

        if (metadata.getUploader() == null || metadata.getUploader().trim().isEmpty()) {
            throw new IllegalArgumentException("Uploader cannot be empty");
        }

        if (metadata.getFileSize() <= 0) {
            throw new IllegalArgumentException("File size must be positive");
        }
    }

    /**
     * 批量驗證
     */
    public static void validateListForBlockchain(List<FileMetadata> metadataList) {
        if (metadataList == null || metadataList.isEmpty()) {
            throw new IllegalArgumentException("Metadata list cannot be null or empty");
        }

        for (int i = 0; i < metadataList.size(); i++) {
            try {
                validateForBlockchain(metadataList.get(i));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Validation failed for item " + i + ": " + e.getMessage());
            }
        }
    }
}

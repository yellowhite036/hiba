package org.gslab.fileProtection.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 檔案元資料模型 包含檔案的基本資訊和雜湊值
 */
public class FileMetadata implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String fileName;
    private String fileHash;
    private LocalDateTime uploadTime;
    private String uploader;
    private long fileSize;
    private String filePath;
    private String description;

    public FileMetadata() {}

    public FileMetadata(
            String id,
            String fileName,
            String fileHash,
            LocalDateTime uploadTime,
            String uploader,
            long fileSize,
            String filePath) {
        this.id = id;
        this.fileName = fileName;
        this.fileHash = fileHash;
        this.uploadTime = uploadTime;
        this.uploader = uploader;
        this.fileSize = fileSize;
        this.filePath = filePath;
    }

    public FileMetadata(
            String id,
            String fileName,
            String fileHash,
            LocalDateTime uploadTime,
            String uploader,
            long fileSize,
            String filePath,
            String description) {
        this(id, fileName, fileHash, uploadTime, uploader, fileSize, filePath);
        this.description = description;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public LocalDateTime getUploadTime() {
        return uploadTime;
    }

    public void setUploadTime(LocalDateTime uploadTime) {
        this.uploadTime = uploadTime;
    }

    public String getUploader() {
        return uploader;
    }

    public void setUploader(String uploader) {
        this.uploader = uploader;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        FileMetadata that = (FileMetadata) o;
        return Objects.equals(fileHash, that.fileHash)
                && Objects.equals(fileName, that.fileName)
                && Objects.equals(fileSize, that.fileSize);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileHash, fileName, fileSize);
    }

    @Override
    public String toString() {
        return "FileMetadata{" + "id='"
                + id + '\'' + ", fileName='"
                + fileName + '\'' + ", fileHash='"
                + fileHash + '\'' + ", uploadTime="
                + uploadTime + ", uploader='"
                + uploader + '\'' + ", fileSize="
                + fileSize + ", filePath='"
                + filePath + '\'' + ", description='"
                + description + '\'' + '}';
    }
}

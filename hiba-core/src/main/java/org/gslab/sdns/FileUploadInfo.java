package org.gslab.sdns;

import java.io.Serializable;

/**
 * Value object representing uploaded file metadata.
 * Contains file information after Netty has received and stored the file.
 * Services receive this instead of file bytes to avoid memory pressure.
 */
public class FileUploadInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String originalFilename;
    private final String storedPath;
    private final long fileSize;
    private final String contentType;

    public FileUploadInfo(String originalFilename, String storedPath, long fileSize, String contentType) {
        this.originalFilename = originalFilename;
        this.storedPath = storedPath;
        this.fileSize = fileSize;
        this.contentType = contentType;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getStoredPath() {
        return storedPath;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getContentType() {
        return contentType;
    }

    @Override
    public String toString() {
        return "FileUploadInfo{" + "originalFilename='"
                + originalFilename + '\'' + ", storedPath='"
                + storedPath + '\'' + ", fileSize="
                + fileSize + ", contentType='"
                + contentType + '\'' + '}';
    }
}

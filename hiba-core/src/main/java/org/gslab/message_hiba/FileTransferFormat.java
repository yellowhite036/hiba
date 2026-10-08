package org.gslab.message_hiba;

import java.io.File;
import java.io.Serializable;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.model.RequestId;

public class FileTransferFormat implements Serializable {

    private static final long serialVersionUID = 1L;
    private RequestId requestId;
    private transient File file; // 檔案
    private String fileName; // 檔案名
    private int startingPoint; // 開始位置
    private int endPoint; // 结尾位置
    private byte[] bytes; // 檔案長度
    private String targetPath; // 存檔路徑

    // Blockchain integration fields (TODO 8)
    private FileMetadata metadata; // 檔案metadata資訊

    public FileTransferFormat() {}

    public void setRequestId(RequestId requestId) {
        this.requestId = requestId;
    }

    public RequestId getRequestId() {
        return requestId;
    }

    public void setFile(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setStartingPoint(int startingPoint) {
        this.startingPoint = startingPoint;
    }

    public int getStartingPoint() {
        return startingPoint;
    }

    public void setEndPoint(int endPoint) {
        this.endPoint = endPoint;
    }

    public int getEndPoint() {
        return endPoint;
    }

    public void setBytes(byte[] bytes) {
        this.bytes = bytes;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    public String getTargetPath() {
        return targetPath;
    }

    // Blockchain integration getters/setters (TODO 8)

    public void setMetadata(FileMetadata metadata) {
        this.metadata = metadata;
    }

    public FileMetadata getMetadata() {
        return metadata;
    }
}

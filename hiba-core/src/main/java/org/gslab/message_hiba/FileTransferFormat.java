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

    // 傳輸標頭（傳送端 TPM 簽章，見 org.gslab.security.FileTransferAuth）。舊版傳送端沒有這些欄位，收到時為預設值
    private String transferId;
    private String senderEk;
    private long timestamp;
    private long fileSize;
    private String fileSha256;
    private String signature;

    public FileTransferFormat() {}

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getSenderEk() {
        return senderEk;
    }

    public void setSenderEk(String senderEk) {
        this.senderEk = senderEk;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public void setFileSha256(String fileSha256) {
        this.fileSha256 = fileSha256;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

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

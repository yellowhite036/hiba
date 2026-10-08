package org.gslab.message_hiba;

public class FileDownloadMessage extends Message {

    public static final int DOWNLOAD_TASK = 1;
    public static final int DOWNLOAD_RESULT = 2;

    private int DownloadType = 0;
    private String RequestID = "";

    public FileDownloadMessage(int DownloadType, String RequestID) {
        this.DownloadType = DownloadType;
        this.RequestID = RequestID;
    }

    public String getRequestID() {
        return RequestID;
    }

    public int getDownloadType() {
        return DownloadType;
    }
}

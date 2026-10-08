package org.gslab.message_hiba;

public class FileTransferMessage extends Message {

    private String FileName = "";
    private String TargetPath = "";
    private byte[] FileBuf = null;
    private long FileSeek = 0;
    private int len = 0;

    public FileTransferMessage(String FileName, byte[] FileBuf, long FileSeek, int len, String TargetPath) {
        this.FileName = FileName;
        this.FileBuf = FileBuf;
        this.FileSeek = FileSeek;
        this.TargetPath = TargetPath;
        this.len = len;
    }

    public int getLen() {
        return len;
    }

    public byte[] getFileBuf() {
        return FileBuf;
    }

    public String getFileName() {
        return FileName;
    }

    public long getFileSeek() {
        return FileSeek;
    }

    public String getTargetPath() {
        return TargetPath;
    }
}

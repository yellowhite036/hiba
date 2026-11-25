package org.gslab.security;

import java.io.File;
import org.gslab.message_hiba.FileTransferFormat;
import org.gslab.sdns.FileTransferClient;

/**
 * 測試工具：模擬攻擊者，對節點的檔案傳輸埠送出「沒有 TPM 簽章」的檔案，可指定任意存檔路徑。
 * 用來確認 TransferSecurity.fileTransferAuth=enforce 時會被拒絕。
 *
 * <pre>
 * cd /tmp &amp;&amp; java -cp ~/HiBA/hiba-core-0.0.1.jar org.gslab.security.FileTransferProbe \
 *     192.168.1.141 8088 /tmp/hiba_probe probe.txt
 * </pre>
 */
public final class FileTransferProbe {

    private FileTransferProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("usage: FileTransferProbe <host> <port> <targetPath> <localFile> [remoteFileName]");
            return;
        }
        File file = new File(args[3]);
        if (!file.isFile()) {
            System.out.println("local file not found: " + file);
            return;
        }
        FileTransferFormat f = new FileTransferFormat();
        f.setFile(file);
        f.setFileName(args.length > 4 ? args[4] : file.getName());
        f.setTargetPath(args[2]);
        f.setStartingPoint(0);
        System.out.println("Sending UNSIGNED " + file + " -> " + args[0] + ":" + args[1] + "  targetPath=" + args[2]
                + "  fileName=" + f.getFileName());
        new FileTransferClient().connect(Integer.parseInt(args[1]), args[0], f);
        System.out.println("Connection closed by receiver");
        System.exit(0);
    }
}

package org.gslab.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

/**
 * 這台設備自己的區塊鏈錢包（檔案上鏈用）。
 * <p>
 * 私鑰存在 HiBA 目錄的 {@value #FILE_NAME}（權限 600），第一次需要時自動產生，之後沿用。
 * 錢包要由管理員用 FileProtectionContract.bindDeviceWallet 綁定到這台的 EK 指紋才能上鏈
 * （register_node.sh 的第三個參數），所以鏈上的每一筆檔案紀錄都看得出是哪台設備送出的。
 * </p>
 * 安裝腳本用命令列取得錢包位址（只印位址，不印私鑰）：
 *
 * <pre>
 *   java -cp hiba-core-0.0.1.jar org.gslab.util.DeviceWallet [HiBA目錄]
 * </pre>
 */
public final class DeviceWallet {

    private static final Logger logger = LoggerFactory.getLogger(DeviceWallet.class);

    public static final String FILE_NAME = "device_wallet.key";

    private DeviceWallet() {}

    /** 讀取 dir 下的錢包；沒有就產生新的 */
    public static synchronized Credentials loadOrCreate(Path dir) throws IOException {
        Path file = dir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            ECKeyPair pair;
            try {
                pair = Keys.createEcKeyPair();
            } catch (Exception e) {
                throw new IOException("無法產生錢包金鑰：" + e.getMessage(), e);
            }
            String hex = Numeric.toHexStringWithPrefixZeroPadded(pair.getPrivateKey(), 64);
            try {
                writePrivate(file, hex + "\n");
                logger.atInfo().log("Created device wallet {} ({})", Credentials.create(pair).getAddress(), file);
            } catch (FileAlreadyExistsException race) {
                // 另一個程序剛好同時建立：沿用它的
            }
        }
        String key = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (!key.matches("(0x)?[0-9a-fA-F]{64}")) {
            throw new IOException(file + " 的內容不是有效的私鑰");
        }
        return Credentials.create(key);
    }

    private static void writePrivate(Path file, String content) throws IOException {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file); // 不支援 POSIX 權限的系統（Windows）
        }
        Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** 印出錢包位址（沒有就先產生）。不印私鑰。 */
    public static void main(String[] args) {
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        try {
            System.out.println(loadOrCreate(dir).getAddress());
        } catch (Exception e) {
            System.err.println("DeviceWallet: " + e.getMessage());
            System.exit(1);
        }
    }
}

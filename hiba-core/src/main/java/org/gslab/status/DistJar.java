package org.gslab.status;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * accounting server 提供給節點自動更新用的 jar。
 * <p>
 * 預設就是 accounting server 自己正在執行的 jar，所以「Pi 換上新版並重啟」之後，
 * 各節點的自動更新程式就會抓到同一個版本；要改用別的檔案可用 {@code -Dhiba.distJar=/path/to.jar}。
 * md5 依檔案大小與修改時間快取，檔案被換掉時自動重算。
 * </p>
 */
public final class DistJar {

    private static final Logger logger = LoggerFactory.getLogger(DistJar.class);

    /** 節點端下載與比對時使用的檔名（與節點上 ~/HiBA 內的檔名相同） */
    public static final String FILE_NAME = "hiba-core-0.0.1.jar";

    private static File cachedFile;
    private static long cachedSize = -1;
    private static long cachedModified = -1;
    private static String cachedMd5;

    private DistJar() {}

    /** @return 要提供下載的 jar；找不到時回傳 null */
    public static File file() {
        String override = System.getProperty("hiba.distJar");
        if (override != null && !override.isBlank()) {
            File f = new File(override);
            return f.isFile() ? f : null;
        }
        return runningJar();
    }

    /** @return 目前這個程式執行的 jar；從 IDE / classes 目錄執行時回傳 null */
    public static File runningJar() {
        try {
            File f = new File(DistJar.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            return f.isFile() && f.getName().endsWith(".jar") ? f : null; // 從 IDE / classes 目錄執行時沒有 jar
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            return null;
        }
    }

    /** @return jar 的 md5（小寫 16 進位）；沒有 jar 或讀取失敗時回傳 null */
    public static synchronized String md5() {
        File f = file();
        if (f == null) {
            return null;
        }
        long size = f.length();
        long modified = f.lastModified();
        if (f.equals(cachedFile) && size == cachedSize && modified == cachedModified && cachedMd5 != null) {
            return cachedMd5;
        }
        String md5 = md5Of(f);
        if (md5 != null) {
            cachedMd5 = md5;
            cachedFile = f;
            cachedSize = size;
            cachedModified = modified;
        }
        return md5;
    }

    /** @return 檔案的 md5（小寫 16 進位）；讀取失敗時回傳 null */
    public static String md5Of(File f) {
        if (f == null) {
            return null;
        }
        try (InputStream in = Files.newInputStream(f.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            logger.atWarn().log("Cannot compute md5 of {}: {}", f, e.getMessage());
            return null;
        }
    }
}

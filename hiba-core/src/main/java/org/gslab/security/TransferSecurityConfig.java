package org.gslab.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * env.json 的 "TransferSecurity" 區塊：檔案傳輸（DATA_PORT 8088）與反序列化的安全設定。
 *
 * <pre>
 * "TransferSecurity": {
 *     "fileTransferAuth": "log",           // off | log | enforce：傳送端 TPM 簽章與存檔路徑檢查
 *     "deserializationFilter": "log",      // off | log | enforce：Java 反序列化白名單（8091、8088 都適用）
 *     "allowedReceiveDirectories": ["receiveFile", "files"],
 *     "maxChunkBytes": 67108864
 * }
 * </pre>
 *
 * 節點的 env.json 不會被自動更新覆蓋，所以每一項都有程式內預設值；沒有這個區塊時兩個模式都是 log
 * （只記錄、不擋），確認 log 沒有誤判後再改成 enforce。
 */
@Slf4j
public final class TransferSecurityConfig {

    public enum Mode {
        OFF,
        LOG,
        ENFORCE;

        static Mode parse(String s, Mode def) {
            if (s == null || s.isBlank()) {
                return def;
            }
            try {
                return Mode.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("[Security] Unknown mode '{}', using {}", s, def);
                return def;
            }
        }
    }

    static final int DEFAULT_MAX_CHUNK_BYTES = 64 * 1024 * 1024;

    private static volatile TransferSecurityConfig current;

    private final Mode fileTransferAuth;
    private final Mode deserializationFilter;
    private final List<String> allowedReceiveDirectories;
    private final int maxChunkBytes;

    public TransferSecurityConfig(
            Mode fileTransferAuth, Mode deserializationFilter, List<String> allowedReceiveDirectories, int maxChunkBytes) {
        this.fileTransferAuth = fileTransferAuth;
        this.deserializationFilter = deserializationFilter;
        this.allowedReceiveDirectories = List.copyOf(allowedReceiveDirectories);
        this.maxChunkBytes = maxChunkBytes;
    }

    /** 目前設定（第一次呼叫時讀 ./env.json） */
    public static TransferSecurityConfig get() {
        TransferSecurityConfig c = current;
        if (c == null) {
            synchronized (TransferSecurityConfig.class) {
                if (current == null) {
                    current = load("./env.json", "receiveFile");
                }
                c = current;
            }
        }
        return c;
    }

    /**
     * @param defaultReceiveDir 沒有設定 allowedReceiveDirectories 時的第一個允許目錄（HiBANetwork.FileTransferDirectory）
     */
    public static TransferSecurityConfig load(String envJsonPath, String defaultReceiveDir) {
        JSONObject block = null;
        String receiveDir = defaultReceiveDir == null || defaultReceiveDir.isBlank() ? "receiveFile" : defaultReceiveDir;
        try {
            JSONObject env = new JSONObject(Files.readString(Path.of(envJsonPath)));
            block = env.optJSONObject("TransferSecurity");
            JSONObject net = env.optJSONObject("HiBANetwork");
            if (net != null && !net.optString("FileTransferDirectory", "").isBlank()) {
                receiveDir = net.optString("FileTransferDirectory");
            }
        } catch (Exception e) {
            log.warn("[Security] Cannot read {}: {} — using defaults", envJsonPath, e.getMessage());
        }
        if (block == null) {
            block = new JSONObject();
        }
        List<String> dirs = new ArrayList<>();
        JSONArray arr = block.optJSONArray("allowedReceiveDirectories");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String d = arr.optString(i, "");
                if (!d.isBlank()) {
                    dirs.add(d);
                }
            }
        }
        if (dirs.isEmpty()) {
            dirs.add(receiveDir);
            dirs.add("files"); // getFile 執行節點把檔案送回 broker 時存到 broker 的 files/<節點名>
        }
        TransferSecurityConfig c = new TransferSecurityConfig(
                Mode.parse(block.optString("fileTransferAuth", ""), Mode.LOG),
                Mode.parse(block.optString("deserializationFilter", ""), Mode.LOG),
                dirs,
                block.optInt("maxChunkBytes", DEFAULT_MAX_CHUNK_BYTES));
        log.info(
                "[Security] Transfer security: fileTransferAuth={}, deserializationFilter={}, allowedReceiveDirectories={}, maxChunkBytes={}",
                c.fileTransferAuth,
                c.deserializationFilter,
                c.allowedReceiveDirectories,
                c.maxChunkBytes);
        return c;
    }

    public Mode fileTransferAuth() {
        return fileTransferAuth;
    }

    public Mode deserializationFilter() {
        return deserializationFilter;
    }

    public List<String> allowedReceiveDirectories() {
        return allowedReceiveDirectories;
    }

    public int maxChunkBytes() {
        return maxChunkBytes;
    }
}

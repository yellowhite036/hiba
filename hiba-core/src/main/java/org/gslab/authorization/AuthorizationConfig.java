package org.gslab.authorization;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * env.json 的 "Authorization" 區塊（M1 多 Validator 授權）。
 * <p>
 * 節點的 env.json 不會被自動更新覆蓋，所以每一項都有程式內預設值；沒有這個區塊等於 mode=off。
 * </p>
 */
@Slf4j
public final class AuthorizationConfig {

    public enum Mode {
        /** 完全不啟用（不收集簽章、不驗證、不開簽章服務） */
        OFF,
        /** 收集與驗證，但結果只寫 log，不攔截任務 */
        LOG,
        /** 驗證不通過就拒絕執行任務 */
        ENFORCE
    }

    /** Broker 向 Validator 收集簽章的方式 */
    public enum Transport {
        /** 沿 HiBA 既有連線轉送（SignRelay），上層不需要能主動連到下層（預設） */
        NETTY,
        /** 直接 HTTP 連到每個 Validator 的 endpoint（M1 最初的做法） */
        HTTP,
        /** 先 NETTY，缺少的 Validator 再用 HTTP 補 */
        BOTH
    }

    private final Mode mode;
    private final String validatorRegistry;
    private final int validatorPort;
    private final int certTtlSeconds;
    private final int signTimeoutMs;
    private final List<String> allowedServices;
    private final Transport signTransport;

    private AuthorizationConfig(
            Mode mode,
            String validatorRegistry,
            int validatorPort,
            int certTtlSeconds,
            int signTimeoutMs,
            List<String> allowedServices,
            Transport signTransport) {
        this.mode = mode;
        this.validatorRegistry = validatorRegistry;
        this.validatorPort = validatorPort;
        this.certTtlSeconds = certTtlSeconds;
        this.signTimeoutMs = signTimeoutMs;
        this.allowedServices = List.copyOf(allowedServices);
        this.signTransport = signTransport;
    }

    public static AuthorizationConfig off() {
        return new AuthorizationConfig(Mode.OFF, "", 8095, 300, 3000, List.of(), Transport.NETTY);
    }

    public static AuthorizationConfig load(String envJsonPath) {
        try {
            JSONObject root = new JSONObject(Files.readString(Path.of(envJsonPath)));
            JSONObject a = root.optJSONObject("Authorization");
            if (a == null) {
                return off();
            }
            Mode mode;
            try {
                mode = Mode.valueOf(a.optString("mode", "off").trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("[Auth] Unknown Authorization.mode '{}', using off", a.optString("mode"));
                mode = Mode.OFF;
            }
            String registry = a.optString("validatorRegistry", "").trim();
            if (mode != Mode.OFF && registry.isEmpty()) {
                log.warn("[Auth] Authorization.mode={} but validatorRegistry is empty, using off", mode);
                mode = Mode.OFF;
            }
            Transport transport;
            try {
                transport = Transport.valueOf(a.optString("signTransport", "netty").trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("[Auth] Unknown Authorization.signTransport '{}', using netty", a.optString("signTransport"));
                transport = Transport.NETTY;
            }
            List<String> allowed = new ArrayList<>();
            JSONArray arr = a.optJSONArray("allowedServices");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "").trim();
                    if (!s.isEmpty()) {
                        allowed.add(s);
                    }
                }
            }
            return new AuthorizationConfig(
                    mode,
                    registry,
                    a.optInt("validatorPort", 8095),
                    Math.max(30, a.optInt("certTtlSeconds", 300)),
                    Math.max(500, a.optInt("signTimeoutMs", 3000)),
                    allowed,
                    transport);
        } catch (Exception e) {
            log.warn("[Auth] Cannot read Authorization config from {}: {}, using off", envJsonPath, e.getMessage());
            return off();
        }
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isEnabled() {
        return mode != Mode.OFF;
    }

    public String getValidatorRegistry() {
        return validatorRegistry;
    }

    public int getValidatorPort() {
        return validatorPort;
    }

    public int getCertTtlSeconds() {
        return certTtlSeconds;
    }

    public int getSignTimeoutMs() {
        return signTimeoutMs;
    }

    public Transport getSignTransport() {
        return signTransport;
    }

    /** 空的代表不限制服務 */
    public List<String> getAllowedServices() {
        return allowedServices;
    }

    @Override
    public String toString() {
        return "mode=" + mode + ", validatorRegistry=" + validatorRegistry + ", validatorPort=" + validatorPort
                + ", certTtlSeconds=" + certTtlSeconds + ", signTimeoutMs=" + signTimeoutMs
                + ", allowedServices=" + allowedServices + ", signTransport=" + signTransport;
    }
}

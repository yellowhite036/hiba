package org.gslab.security;

import java.io.ObjectInputFilter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.gslab.security.TransferSecurityConfig.Mode;

/**
 * Java 反序列化白名單（JVM 全域）。
 * <p>
 * HiBA 的 8091（MessageCodec、MessageTPMVerifyHandler）與 8088（檔案傳輸）都用 Java 反序列化，
 * 而且在驗證 TPM 簽章之前就先反序列化，所以任何連得到的人都能送出惡意物件。這個過濾器只允許
 * HiBA 自己的類別與 JDK 的基本型別、集合，並限制物件深度、參照數與陣列長度。
 * </p>
 * <ul>
 * <li>log：不在白名單的類別照常放行，但每個類別記一次 warn（先用這個模式收集實際會用到的類別）</li>
 * <li>enforce：不在白名單的類別直接拒絕（InvalidClassException）</li>
 * </ul>
 */
@Slf4j
public final class SerialFilter implements ObjectInputFilter {

    static final long MAX_DEPTH = 64;
    static final long MAX_REFERENCES = 1_000_000;
    static final long MAX_ARRAY_LENGTH = 128L * 1024 * 1024;

    private static final String[] ALLOWED_PREFIXES = {
        "org.gslab.", "java.lang.", "java.util.", "java.math.", "java.time.",
    };

    /** 白名單套件裡仍然不允許的部分（反射、lambda、動態代理） */
    private static final String[] DENIED_PREFIXES = {
        "java.lang.reflect.", "java.lang.invoke.", "java.lang.ref.",
    };

    private static boolean installed;

    private final Mode mode;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    public SerialFilter(Mode mode) {
        this.mode = mode;
    }

    /** 啟動時呼叫一次（JVM 全域只能設定一次） */
    public static synchronized void install(Mode mode) {
        if (installed || mode == null || mode == Mode.OFF) {
            if (mode == Mode.OFF) {
                log.warn("[Security] Deserialization filter is OFF");
            }
            return;
        }
        try {
            ObjectInputFilter.Config.setSerialFilter(new SerialFilter(mode));
            installed = true;
            log.info("[Security] Deserialization filter installed: mode={}", mode);
        } catch (IllegalStateException e) {
            log.warn("[Security] A deserialization filter is already set: {}", e.getMessage());
        }
    }

    @Override
    public Status checkInput(FilterInfo info) {
        if (info.depth() > MAX_DEPTH
                || info.references() > MAX_REFERENCES
                || info.arrayLength() > MAX_ARRAY_LENGTH) {
            return decide("limits exceeded (depth " + info.depth() + ", references " + info.references()
                    + ", array length " + info.arrayLength() + ")");
        }
        Class<?> c = info.serialClass();
        if (c == null) {
            return Status.UNDECIDED;
        }
        while (c.isArray()) {
            c = c.getComponentType();
        }
        if (c.isPrimitive() || isAllowed(c.getName())) {
            return Status.ALLOWED;
        }
        return decide(c.getName());
    }

    static boolean isAllowed(String className) {
        for (String p : DENIED_PREFIXES) {
            if (className.startsWith(p)) {
                return false;
            }
        }
        for (String p : ALLOWED_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private Status decide(String what) {
        if (mode == Mode.ENFORCE) {
            if (reported.add(what)) {
                log.warn("[Security] Deserialization REJECTED: {}", what);
            }
            return Status.REJECTED;
        }
        if (reported.add(what)) {
            log.warn("[Security] Deserialization would be rejected (log mode): {}", what);
        }
        return Status.ALLOWED;
    }
}

package org.gslab.sdns;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SslContextFactory {

    private static final Logger logger = LoggerFactory.getLogger(SslContextFactory.class);
    /*
     * 測試用, 尚未加入主程式, 先push上git而已
     * 之後有新方法可以直接把這個刪掉沒差
     */

    private static final String PROTOCOL = "TLSv1.2";
    private static SSLContext SERVER_CONTEXT;
    private static SSLContext CLIENT_CONTEXT;

    public static SSLContext getServerContext(String pkPath) {
        if (SERVER_CONTEXT != null) {
            return SERVER_CONTEXT;
        }
        InputStream in = null;

        try {
            KeyManagerFactory kmf = null;
            if (pkPath != null) {
                KeyStore ks = KeyStore.getInstance("JKS"); // 金鑰庫KeyStore
                in = new FileInputStream(pkPath); // 加載服務端證書
                ks.load(in, "qqq123".toCharArray()); // 加載server端的KeyStore, sNetty是生成倉庫時設置的密碼, 用於檢查密鑰庫完整性的密碼
                kmf = KeyManagerFactory.getInstance("SunX509");
                kmf.init(ks, "qqq123".toCharArray()); // 初始化密鑰管理器
            }
            SERVER_CONTEXT = SSLContext.getInstance(PROTOCOL); // 獲取TLS 的對象
            // 初始化此上下文
            // 參數一：認證的密鑰, 參數二：對等信任認證, 參數三：偽隨機數生成器
            // 由於單向認證, 服務端不用驗證客戶端, 所以第二個參數為null
            SERVER_CONTEXT.init(kmf.getKeyManagers(), null, null);
        } catch (Exception e) {
            throw new Error("Failed to initialize the server-side SSLContext", e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return SERVER_CONTEXT;
    }

    public static SSLContext getClientContext(String caPath) {
        if (CLIENT_CONTEXT != null) {
            return CLIENT_CONTEXT;
        }
        InputStream tIN = null;
        try {
            TrustManagerFactory tf = null; // 信任庫
            if (caPath != null) {
                KeyStore tks = KeyStore.getInstance("JKS"); // 密鑰庫KeyStore
                tIN = new FileInputStream(caPath); // 加載客戶端證書
                tks.load(tIN, "qqq123".toCharArray());
                tf = TrustManagerFactory.getInstance("SunX509");
                tf.init(tks); // 初始化信任庫
            }
            CLIENT_CONTEXT = SSLContext.getInstance(PROTOCOL);
            CLIENT_CONTEXT.init(null, tf == null ? null : tf.getTrustManagers(), null); // 設置信任證書
        } catch (Exception e) {
            throw new Error("Failed to initialize the client-side SSLContext");
        } finally {
            if (tIN != null) {
                try {
                    tIN.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return CLIENT_CONTEXT;
    }

    public static void main(String[] args) throws Exception {
        // check supported TLS version.
        SSLContext TLSContext = SSLContext.getInstance("TLS");
        TLSContext.init(null, null, new SecureRandom());
        SSLSocketFactory factory = TLSContext.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket();
        String[] protocols = socket.getSupportedProtocols();
        for (String protocol : protocols) {
            logger.atInfo().addKeyValue("protocol", protocol).log(" {}", protocol);
        }

        protocols = socket.getEnabledCipherSuites();
        logger.atInfo().addKeyValue("protocolCount", protocols.length).log("Enable Protocols: {}", protocols.length);
        for (String protocol : protocols) {
            logger.atInfo().addKeyValue("protocol", protocol).log(" {}", protocol);
        }
    }
}

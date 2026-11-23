package org.gslab.authorization;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;

/**
 * Validator 簽章服務（HTTP）。
 *
 * <pre>
 * POST /auth/sign   body = 授權請求 JSON（AuthorizationCertificate，不含 validator 簽章）
 *   200 {"validatorId": EK, "signature": Base64}
 *   403 {"error": 不簽的理由}
 * GET  /auth/status  這台的 Validator 身分（除錯用）
 * </pre>
 *
 * 所有節點在 mode≠off 時都會開這個服務；不在鏈上 Validator 名單裡的節點一律回 403。
 * 請求本身由提出者用 TPM 簽章，回應的簽章由呼叫端用鏈上公鑰驗證，所以走一般 HTTP 即可。
 */
@Slf4j
final class ValidatorSignServer {

    static final String PATH = "/auth/sign";
    private static final int MAX_BODY = 64 * 1024;

    private final AuthorizationService service;
    private final int port;
    private HttpServer server;

    ValidatorSignServer(AuthorizationService service, int port) {
        this.service = service;
        this.port = port;
    }

    void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext(PATH, this::handleSign);
            server.createContext("/auth/status", this::handleStatus);
            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "auth-sign");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            log.info("[Auth] Validator sign service listening on port {}", port);
        } catch (IOException e) {
            log.error("[Auth] Cannot start validator sign service on port {}: {}", port, e.getMessage());
        }
    }

    private void handleSign(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                reply(ex, 405, new JSONObject().put("error", "POST only"));
                return;
            }
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readNBytes(MAX_BODY + 1);
            }
            if (body.length > MAX_BODY) {
                reply(ex, 413, new JSONObject().put("error", "request too large"));
                return;
            }
            AuthorizationCertificate cert =
                    AuthorizationCertificate.fromRequestJson(new JSONObject(new String(body, StandardCharsets.UTF_8)));
            try {
                String sig = service.evaluateAndSign(cert);
                reply(ex, 200, new JSONObject().put("validatorId", service.selfIdOrEmpty()).put("signature", sig));
            } catch (AuthorizationService.Rejection r) {
                log.info(
                        "[Auth] Refused to sign certificate {} from {}: {}",
                        cert.shortId(), ex.getRemoteAddress().getAddress().getHostAddress(), r.getMessage());
                reply(ex, 403, new JSONObject().put("error", r.getMessage()));
            }
        } catch (Exception e) {
            log.debug("[Auth] Bad sign request: {}", e.getMessage());
            reply(ex, 400, new JSONObject().put("error", "bad request: " + e.getMessage()));
        }
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        reply(ex, 200, new JSONObject()
                .put("validatorId", service.selfIdOrEmpty())
                .put("mode", service.mode().name().toLowerCase()));
    }

    private static void reply(HttpExchange ex, int status, JSONObject json) throws IOException {
        byte[] out = json.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }
}

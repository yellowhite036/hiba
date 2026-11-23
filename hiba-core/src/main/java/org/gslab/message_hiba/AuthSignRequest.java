package org.gslab.message_hiba;

/**
 * M1 授權簽章請求，沿著 HiBA 既有的 broker／client 連線轉送（見 org.gslab.authorization.SignRelay）。
 * <p>
 * 收到的節點：是 Validator 就自己簽，並轉給除了來源以外的所有相鄰節點，收齊後以 {@link AuthSignResponse} 回覆來源。
 * 內容是 AuthorizationCertificate 的請求 JSON（含提出者的 TPM 簽章），轉送的節點無法修改。
 * </p>
 */
public class AuthSignRequest extends Message {

    private static final long serialVersionUID = 1L;

    private final String correlationId;
    private final String requestJson;
    /** 這一跳最多可以等多久（毫秒）；往下轉送時會扣掉一段餘裕 */
    private final long budgetMs;

    public AuthSignRequest(String correlationId, String requestJson, long budgetMs) {
        this.correlationId = correlationId;
        this.requestJson = requestJson;
        this.budgetMs = budgetMs;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getRequestJson() {
        return requestJson;
    }

    public long getBudgetMs() {
        return budgetMs;
    }
}

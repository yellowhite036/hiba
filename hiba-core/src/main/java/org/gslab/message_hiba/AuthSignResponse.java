package org.gslab.message_hiba;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AuthSignRequest} 的回覆：這個節點和它轉送出去的節點收集到的 Validator 簽章。
 * <p>
 * 轉送的節點只負責傳遞，不需要被信任：每個簽章都由發起者用鏈上公鑰驗證，偽造或修改的簽章會被丟棄。
 * </p>
 */
public class AuthSignResponse extends Message {

    private static final long serialVersionUID = 1L;

    private final String correlationId;
    /** 每筆是 {validatorId, signature(Base64)} */
    private final ArrayList<String[]> signatures;
    /** 拒簽或連不到的說明（除錯用），例如 "NewNode-12: service 'x' not allowed" */
    private final ArrayList<String> notes;

    public AuthSignResponse(String correlationId, List<String[]> signatures, List<String> notes) {
        this.correlationId = correlationId;
        this.signatures = new ArrayList<>(signatures);
        this.notes = new ArrayList<>(notes);
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public List<String[]> getSignatures() {
        return signatures;
    }

    public List<String> getNotes() {
        return notes;
    }
}

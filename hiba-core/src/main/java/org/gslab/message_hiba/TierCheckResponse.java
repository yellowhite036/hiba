package org.gslab.message_hiba;

public class TierCheckResponse extends Message {

    private boolean Success = false;

    public TierCheckResponse(boolean Success) {
        this.Success = Success;
    }

    public boolean isSuccess() {
        return Success;
    }
}

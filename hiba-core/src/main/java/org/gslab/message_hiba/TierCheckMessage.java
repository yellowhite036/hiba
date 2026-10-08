package org.gslab.message_hiba;

public class TierCheckMessage extends Message {

    private boolean Checked = false;

    public TierCheckMessage(boolean Checked) {
        this.Checked = Checked;
    }

    public boolean isChecked() {
        return Checked;
    }
}

package org.gslab.message_hiba;

/**
 * broker 因同網段有勝出的 broker 而讓位時，關閉 accounting server 連線前送出的最後一則訊息。
 * accounting server 把它記進節點狀態頁的「broker 讓位紀錄」。
 */
public class BrokerStepDownNotice extends Message {

    private static final long serialVersionUID = 1L;

    private final String brokerName;
    private final String brokerIp;
    private final String winnerName;
    private final String winnerIp;
    private final long stepDownTime;

    public BrokerStepDownNotice(String brokerName, String brokerIp, String winnerName, String winnerIp) {
        this.brokerName = brokerName;
        this.brokerIp = brokerIp;
        this.winnerName = winnerName;
        this.winnerIp = winnerIp;
        this.stepDownTime = System.currentTimeMillis();
    }

    public String getBrokerName() {
        return brokerName;
    }

    public String getBrokerIp() {
        return brokerIp;
    }

    public String getWinnerName() {
        return winnerName;
    }

    public String getWinnerIp() {
        return winnerIp;
    }

    public long getStepDownTime() {
        return stepDownTime;
    }
}

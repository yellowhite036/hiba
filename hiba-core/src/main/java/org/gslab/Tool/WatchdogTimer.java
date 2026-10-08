package org.gslab.Tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WatchdogTimer extends Thread {

    private volatile boolean interrupt = false;
    private static final Logger LOGGER = LoggerFactory.getLogger(WatchdogTimer.class);

    public void systemForcedRestart() {}

    public void run() {
        int count = 0;
        while (true) {
            try {
                count += 1;
                if (interrupt) {
                    break;
                }
                if (count >= 30) { // 30秒沒有回應, 觸發重啟
                    LOGGER.atInfo().log("A system exception is found and a restart mechanism is triggered!");
                    runCommand();
                }
                Thread.sleep(1000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void stopRunning() {
        interrupt = true;
    }

    private void runCommand() { // 直接系統呼叫bash檔案, kill hiba主程式PID, 然後重啟
        try {
            Runtime runtime = Runtime.getRuntime();
            String cmd = "bash ./scripts/watchdogtimer.sh";
            runtime.exec(cmd);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

package org.gslab.Tool;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReverseSSHTunnelManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ReverseSSHTunnelManager.class);
    private Process reverseSSHProcess;

    public boolean createTunnel(String localPort, String remotePort, String user, String sshHost) {
        try {
            // 先清理可能存在的隧道
            cleanupExistingTunnel(localPort, sshHost);

            logger.atDebug()
                    .addKeyValue("remotePort", remotePort)
                    .addKeyValue("localPort", localPort)
                    .addKeyValue("sshHost", sshHost)
                    .log("Creating Reverse SSH Tunnel");

            ProcessBuilder pb = new ProcessBuilder(
                    "ssh",
                    "-CNR",
                    remotePort + ":localhost" + ":" + localPort,
                    user + "@" + sshHost,
                    "-o",
                    "ServerAliveInterval=30",
                    "-o",
                    "ServerAliveCountMax=3",
                    "-o",
                    "ExitOnForwardFailure=yes");

            // 重定向輸出到日誌
            pb.redirectOutput(new File("./logs/reverse_ssh_tunnel.log"));
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            reverseSSHProcess = pb.start();

            // 等待一下確保隧道建立
            Thread.sleep(2000);

            // 驗證隧道是否成功建立
            return verifyTunnel(localPort, sshHost);

        } catch (Exception e) {
            logger.atError().setCause(e).log("Failed to create Reverse SSH tunnel");
            return false;
        }
    }

    private void cleanupExistingTunnel(String port, String host) throws Exception {
        logger.atDebug()
                .addKeyValue("port", port)
                .addKeyValue("host", host)
                .log("Attempting to kill SSH tunnel processes");
        ProcessBuilder pb = new ProcessBuilder("pkill", "-f", "ssh.*" + port + ".*" + host);
        Process cleanup = pb.start();
        int exitCode = cleanup.waitFor(5, TimeUnit.SECONDS) ? cleanup.exitValue() : -1;
        logger.atDebug().addKeyValue("exitCode", exitCode).log("pkill command completed");
    }

    private boolean checkForExistingTunnel(String port, String host) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("pgrep", "-f", "ssh.*" + port + ".*" + host);
        Process check = pb.start();
        int exitCode = check.waitFor(5, TimeUnit.SECONDS) ? check.exitValue() : -1;
        boolean processExists = exitCode == 0;
        logger.atDebug()
                .addKeyValue("exitCode", exitCode)
                .addKeyValue("processExists", processExists)
                .log("Process check for SSH tunnel returned");
        return processExists;
    }

    private boolean verifyTunnel(String port, String host) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("ssh", "user@" + host, "netstat -ln | grep " + port);
        Process verify = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(verify.getInputStream()));
        String output = reader.readLine();

        return output != null && output.contains(port);
    }

    @Override
    public void close() {
        if (reverseSSHProcess != null && reverseSSHProcess.isAlive()) {
            logger.atInfo().log("Closing active reverse SSH tunnel...");
            reverseSSHProcess.destroy();
            try {
                if (!reverseSSHProcess.waitFor(5, TimeUnit.SECONDS)) {
                    logger.atWarn().log("SSH tunnel did not terminate gracefully, forcing termination...");
                    reverseSSHProcess.destroyForcibly();
                    reverseSSHProcess.waitFor(3, TimeUnit.SECONDS);
                }
                logger.atInfo().log("SSH tunnel closed successfully");
            } catch (InterruptedException e) {
                logger.atInfo().setCause(e).log("Interrupted while waiting for SSH tunnel to close");
                Thread.currentThread().interrupt();
            }
        } else {
            logger.atInfo().log("No active reverse SSH tunnel to close.");
        }
    }
}

package org.gslab.Tool;

import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;
import oshi.util.GlobalConfig;

public class PerformanceMonitor {

    private static final SystemInfo systemInfo = new SystemInfo();
    private static final HardwareAbstractionLayer hardwareLayer = systemInfo.getHardware();
    private static final OperatingSystem os = systemInfo.getOperatingSystem();
    private static final int pid = os.getProcessId();

    static {
        GlobalConfig.set(GlobalConfig.OSHI_OS_WINDOWS_PERFOS_DIABLED, false);
        GlobalConfig.set(GlobalConfig.OSHI_OS_WINDOWS_PERFPROC_DIABLED, false);
        GlobalConfig.set(GlobalConfig.OSHI_OS_WINDOWS_PERFDISK_DIABLED, false);
    }

    public PerformanceMonitor() {}

    public static void main(String[] args) throws InterruptedException {
        System.out.println("pid : " + pid);

        while (true) {
            System.out.println(getMemoryUsage());
            Thread.sleep(1000);
        }
    }

    public static double getCpuUsageInSec() {
        return 0;
    }

    public static double getMemoryUsage() {
        double usedSize = os.getProcess(pid).getResidentSetSize();
        double totalSize = hardwareLayer.getMemory().getTotal();
        return (double) (Math.round(usedSize * 10000 / totalSize) / 100);
    }
}

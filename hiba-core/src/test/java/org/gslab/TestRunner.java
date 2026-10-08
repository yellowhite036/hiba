package org.gslab;

import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * 可執行的測試運行器
 *
 * <p>用法：</p>
 * <pre>
 * # 執行特定測試類別
 * java -jar hiba-core-tests.jar org.gslab.tpm.TPMMultiStageVerificationTest
 *
 * # 執行特定測試方法
 * java -jar hiba-core-tests.jar org.gslab.tpm.TPMMultiStageVerificationTest#testExpiredMessage
 *
 * # 執行整個套件（所有測試）
 * java -jar hiba-core-tests.jar --all
 *
 * # 列出可用的測試類別
 * java -jar hiba-core-tests.jar --list
 * </pre>
 */
public class TestRunner {

    private static final String[] KNOWN_TEST_CLASSES = {
        "org.gslab.tpm.TPMMultiStageVerificationTest",
        "org.gslab.tpm.TPMPerformanceTest",
        "org.gslab.tpm.FileMetadataIntegrityTest"
    };

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String arg = args[0];

        if ("--help".equals(arg) || "-h".equals(arg)) {
            printUsage();
            return;
        }

        if ("--list".equals(arg)) {
            listTests();
            return;
        }

        if ("--all".equals(arg)) {
            runAllTests();
            return;
        }

        // 執行指定的測試
        runTest(arg);
    }

    private static void printUsage() {
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("                    HiBA Test Runner                            ");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("用法：java -jar hiba-core-tests.jar <選項>");
        System.out.println();
        System.out.println("選項：");
        System.out.println("  <類別名稱>              執行指定的測試類別");
        System.out.println("  <類別名稱>#<方法名稱>   執行指定的測試方法");
        System.out.println("  --all                   執行所有測試");
        System.out.println("  --list                  列出可用的測試類別");
        System.out.println("  --help, -h              顯示此說明");
        System.out.println();
        System.out.println("範例：");
        System.out.println("  java -jar hiba-core-tests.jar org.gslab.tpm.TPMMultiStageVerificationTest");
        System.out.println("  java -jar hiba-core-tests.jar TPMMultiStageVerificationTest");
        System.out.println("  java -jar hiba-core-tests.jar TPMMultiStageVerificationTest#testExpiredMessage");
        System.out.println();
        System.out.println("系統屬性：");
        System.out.println("  -Dtpm.test.use.blockchain=true    使用真實區塊鏈");
        System.out.println("  -Dtpm.test.json=/path/to/file     指定測試 JSON 檔案");
        System.out.println();
    }

    private static void listTests() {
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("                    可用的測試類別                              ");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();
        for (String testClass : KNOWN_TEST_CLASSES) {
            String simpleName = testClass.substring(testClass.lastIndexOf('.') + 1);
            System.out.println("  " + simpleName);
            System.out.println("    完整類別名稱: " + testClass);
            System.out.println();
        }
    }

    private static void runAllTests() {
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("                    執行所有測試                                ");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectPackage("org.gslab"))
                .build();

        executeTests(request);
    }

    private static void runTest(String testSpec) {
        // 處理簡短類別名稱
        String className = testSpec;
        String methodName = null;

        if (testSpec.contains("#")) {
            String[] parts = testSpec.split("#", 2);
            className = parts[0];
            methodName = parts[1];
        }

        // 如果不包含包名，嘗試在已知類別中查找
        if (!className.contains(".")) {
            String found = null;
            for (String known : KNOWN_TEST_CLASSES) {
                if (known.endsWith("." + className)) {
                    found = known;
                    break;
                }
            }
            if (found != null) {
                className = found;
            } else {
                // 預設使用 org.gslab.tpm 包
                className = "org.gslab.tpm." + className;
            }
        }

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("                    執行測試                                    ");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("測試類別: " + className);
        if (methodName != null) {
            System.out.println("測試方法: " + methodName);
        }
        System.out.println();

        LauncherDiscoveryRequest request;

        if (methodName != null) {
            request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectMethod(className, methodName))
                    .build();
        } else {
            request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClass(className))
                    .build();
        }

        executeTests(request);
    }

    private static void executeTests(LauncherDiscoveryRequest request) {
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();

        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("                    測試結果摘要                                ");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("測試總數: " + summary.getTestsFoundCount());
        System.out.println("成功: " + summary.getTestsSucceededCount());
        System.out.println("失敗: " + summary.getTestsFailedCount());
        System.out.println("跳過: " + summary.getTestsSkippedCount());
        System.out.println("執行時間: " + (summary.getTimeFinished() - summary.getTimeStarted()) + " ms");
        System.out.println();

        if (!summary.getFailures().isEmpty()) {
            System.out.println("失敗的測試：");
            summary.printFailuresTo(new PrintWriter(System.out, true));
            System.exit(1);
        } else {
            System.out.println("所有測試通過！");
            System.exit(0);
        }
    }
}

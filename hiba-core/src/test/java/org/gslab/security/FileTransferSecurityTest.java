package org.gslab.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.gslab.message_hiba.FileTransferFormat;
import org.gslab.model.RequestId;
import org.gslab.sdns.FileTransferServerHandler;
import org.gslab.security.TransferSecurityConfig.Mode;
import org.gslab.tpm.TPMService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 檔案傳輸（8088）安全修正：存檔路徑限制、傳送端 TPM 簽章、反序列化白名單、完整傳輸流程。
 * 用軟體 RSA 金鑰模擬 TPM，鏈上公鑰查詢以函式替換，不需要 TPM、區塊鏈或網路。
 */
class FileTransferSecurityTest {

    private static final String EK = "SENDER_EK_0000000000000000";
    private static final String EK_UNREGISTERED = "STRANGER_EK_0000000000000";
    private static KeyPair senderKeys;
    private static KeyPair strangerKeys;

    @TempDir
    Path work;

    private Function<String, Optional<PublicKey>> savedLookup;
    private Supplier<TPMService> savedTpm;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        senderKeys = g.generateKeyPair();
        strangerKeys = g.generateKeyPair();
    }

    @BeforeEach
    void setUp() throws Exception {
        savedLookup = FileTransferAuth.keyLookup;
        savedTpm = FileTransferAuth.tpm;
        // 只有 EK 登記在鏈上
        FileTransferAuth.keyLookup = ek -> EK.equals(ek) ? Optional.of(senderKeys.getPublic()) : Optional.empty();
        actAs(EK, senderKeys);
    }

    @AfterEach
    void tearDown() {
        FileTransferAuth.keyLookup = savedLookup;
        FileTransferAuth.tpm = savedTpm;
    }

    // =====================================================================
    // 存檔路徑
    // =====================================================================

    @Test
    @DisplayName("路徑：允許目錄底下的子目錄 → 通過")
    void pathInsideAllowedDirectory() {
        ReceivePathPolicy.Decision d = policy().check("receiveFile/getFile_1/NewNode-141", "a.txt");
        assertThat(d.ok()).as(String.valueOf(d.problem())).isTrue();
        assertThat(d.file()).isEqualTo(work.resolve("receiveFile/getFile_1/NewNode-141/a.txt"));
    }

    @Test
    @DisplayName("路徑：./receiveFile 寫法與 files 目錄 → 通過")
    void pathVariantsAllowed() {
        assertThat(policy().check("./receiveFile/x/", "a.txt").ok()).isTrue();
        assertThat(policy().check("files/NewNode-141", "a.txt").ok()).isTrue();
    }

    @Test
    @DisplayName("路徑：沒有指定存檔路徑 → 用第一個允許目錄")
    void pathDefaultsToFirstRoot() {
        ReceivePathPolicy.Decision d = policy().check(null, "a.txt");
        assertThat(d.ok()).isTrue();
        assertThat(d.file()).isEqualTo(work.resolve("receiveFile/a.txt"));
    }

    @Test
    @DisplayName("路徑：用 ../ 跳出允許目錄（例如覆寫 jar）→ 拒絕")
    void pathTraversalRejected() {
        assertThat(policy().check("receiveFile/../", "hiba-core-0.0.1.jar").ok()).isFalse();
        assertThat(policy().check("..", "env.json").ok()).isFalse();
    }

    @Test
    @DisplayName("路徑：絕對路徑在允許目錄外（例如 ~/.ssh）→ 拒絕；在允許目錄內 → 通過")
    void absolutePaths() {
        assertThat(policy().check("/tmp/x", "a.txt").ok()).isFalse();
        assertThat(policy().check(work.resolve("receiveFile/y").toString(), "a.txt").ok()).isTrue();
    }

    @Test
    @DisplayName("路徑：檔名帶路徑或是 .. → 拒絕")
    void fileNameWithPathRejected() {
        assertThat(policy().check("receiveFile", "../../env.json").ok()).isFalse();
        assertThat(policy().check("receiveFile", "sub/a.txt").ok()).isFalse();
        assertThat(policy().check("receiveFile", "..").ok()).isFalse();
        assertThat(policy().check("receiveFile", "").ok()).isFalse();
    }

    @Test
    @DisplayName("路徑：允許目錄內的符號連結指向外面 → 拒絕")
    void symlinkEscapeRejected() throws Exception {
        Path outside = Files.createDirectories(work.resolve("outside"));
        Files.createDirectories(work.resolve("receiveFile"));
        try {
            Files.createSymbolicLink(work.resolve("receiveFile/link"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.abort("symbolic links not supported here: " + e.getMessage());
        }
        ReceivePathPolicy p = policy();
        ReceivePathPolicy.Decision d = p.check("receiveFile/link", "a.txt");
        assertThat(d.ok()).isTrue(); // 字串上看起來在允許目錄內
        assertThat(p.checkReal(d)).contains("symbolic link");
    }

    // =====================================================================
    // 傳送端簽章
    // =====================================================================

    @Test
    @DisplayName("簽章：登記過的設備簽的標頭 → 通過")
    void signedHeaderVerifies() throws Exception {
        FileTransferFormat f = signedHeader("receiveFile/x", "a.txt", "hello");
        FileTransferAuth.Result r = FileTransferAuth.verify(f);
        assertThat(r.ok()).as(r.reason()).isTrue();
        assertThat(r.senderEk()).isEqualTo(EK);
    }

    @Test
    @DisplayName("簽章：沒有簽章（舊版傳送端）→ 不通過")
    void unsignedHeaderFails() {
        FileTransferFormat f = new FileTransferFormat();
        f.setFileName("a.txt");
        assertThat(FileTransferAuth.verify(f).ok()).isFalse();
        assertThat(FileTransferAuth.verify(f).reason()).contains("unsigned");
    }

    @Test
    @DisplayName("簽章：簽完後改存檔路徑 → 簽章無效")
    void tamperedTargetPathFails() throws Exception {
        FileTransferFormat f = signedHeader("receiveFile/x", "a.txt", "hello");
        f.setTargetPath("files/evil");
        assertThat(FileTransferAuth.verify(f).reason()).contains("signature invalid");
    }

    @Test
    @DisplayName("簽章：未登記（或已撤銷）的設備 → 不通過")
    void unregisteredSenderFails() throws Exception {
        actAs(EK_UNREGISTERED, strangerKeys);
        FileTransferFormat f = signedHeader("receiveFile/x", "a.txt", "hello");
        assertThat(FileTransferAuth.verify(f).reason()).contains("not registered");
    }

    @Test
    @DisplayName("簽章：冒用登記設備的 EK，但用自己的金鑰簽 → 簽章無效")
    void impersonationFails() throws Exception {
        actAs(EK, strangerKeys);
        FileTransferFormat f = signedHeader("receiveFile/x", "a.txt", "hello");
        assertThat(FileTransferAuth.verify(f).reason()).contains("signature invalid");
    }

    @Test
    @DisplayName("簽章：同一個標頭送第二次 → 重放")
    void replayFails() throws Exception {
        FileTransferFormat f = signedHeader("receiveFile/x", "a.txt", "hello");
        assertThat(FileTransferAuth.verify(f).ok()).isTrue();
        assertThat(FileTransferAuth.verify(f).reason()).contains("replay");
    }

    @Test
    @DisplayName("簽章：時間差超過 5 分鐘 → 不通過")
    void staleTimestampFails() throws Exception {
        FileTransferFormat f = signedHeader("receiveFile/x", "a.txt", "hello");
        f.setTimestamp(System.currentTimeMillis() - 10 * 60_000L);
        f.setSignature(sign(senderKeys, FileTransferAuth.payload(f)));
        assertThat(FileTransferAuth.verify(f).reason()).contains("timestamp");
    }

    // =====================================================================
    // 完整傳輸（接收端 handler）
    // =====================================================================

    @Test
    @DisplayName("傳輸：enforce，簽章正確、路徑合法 → 寫入，內容與雜湊一致")
    void enforceAcceptsValidTransfer() throws Exception {
        byte[] content = "line1\nline2\nline3\n".repeat(50).getBytes(StandardCharsets.UTF_8);
        EmbeddedChannel ch = channel(Mode.ENFORCE);
        sendFile(ch, signedHeader("receiveFile/getFile_1/NewNode-141", "a.txt", content), content, 4);
        ch.close();
        Path written = work.resolve("receiveFile/getFile_1/NewNode-141/a.txt");
        assertThat(written).exists();
        assertThat(Files.readAllBytes(written)).isEqualTo(content);
        // 每個區塊都回覆累計位元組數（舊版協定）
        List<Integer> acks = new ArrayList<>();
        Object o;
        while ((o = ch.readOutbound()) != null) {
            acks.add((Integer) o);
        }
        assertThat(acks).isNotEmpty();
        assertThat(acks.get(acks.size() - 1)).isEqualTo(content.length);
    }

    @Test
    @DisplayName("傳輸：enforce，沒有簽章 → 斷線、不寫檔")
    void enforceRejectsUnsigned() throws Exception {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        FileTransferFormat f = new FileTransferFormat();
        f.setFileName("a.txt");
        f.setTargetPath("receiveFile/x");
        EmbeddedChannel ch = channel(Mode.ENFORCE);
        sendFile(ch, f, content, 1);
        assertThat(ch.isOpen()).isFalse();
        assertThat(work.resolve("receiveFile/x/a.txt")).doesNotExist();
    }

    @Test
    @DisplayName("傳輸：enforce，簽章正確但路徑跳出允許目錄 → 斷線、不寫檔")
    void enforceRejectsTraversal() throws Exception {
        byte[] content = "evil".getBytes(StandardCharsets.UTF_8);
        EmbeddedChannel ch = channel(Mode.ENFORCE);
        sendFile(ch, signedHeader("receiveFile/../", "env.json", content), content, 1);
        assertThat(ch.isOpen()).isFalse();
        assertThat(work.resolve("env.json")).doesNotExist();
    }

    @Test
    @DisplayName("傳輸：enforce，傳輸途中內容被改 → 雜湊不符，檔案刪除")
    void enforceDiscardsAlteredContent() throws Exception {
        byte[] content = "original content".getBytes(StandardCharsets.UTF_8);
        FileTransferFormat f = signedHeader("receiveFile/x", "a.txt", content);
        byte[] altered = "ORIGINAL content".getBytes(StandardCharsets.UTF_8);
        EmbeddedChannel ch = channel(Mode.ENFORCE);
        sendFile(ch, f, altered, 1);
        ch.close();
        assertThat(work.resolve("receiveFile/x/a.txt")).doesNotExist();
    }

    @Test
    @DisplayName("傳輸：log 模式，沒有簽章 → 照常寫入（只記錄）")
    void logModeStillWritesUnsigned() throws Exception {
        byte[] content = "legacy".getBytes(StandardCharsets.UTF_8);
        FileTransferFormat f = new FileTransferFormat();
        f.setFileName("a.txt");
        f.setTargetPath(work.resolve("receiveFile/x").toString());
        EmbeddedChannel ch = channel(Mode.LOG);
        sendFile(ch, f, content, 1);
        ch.close();
        assertThat(Files.readAllBytes(work.resolve("receiveFile/x/a.txt"))).isEqualTo(content);
    }

    // =====================================================================
    // 反序列化白名單
    // =====================================================================

    @Test
    @DisplayName("反序列化：HiBA 類別與 JDK 集合 → 允許")
    void filterAllowsHibaAndCollections() throws Exception {
        HashMap<String, Object> m = new HashMap<>();
        m.put("requestId", RequestId.of(UUID.randomUUID().toString()));
        m.put("list", new ArrayList<>(List.of(1, 2L, "s", new byte[] {1, 2})));
        Object back = roundTrip(m, Mode.ENFORCE);
        assertThat(back).isInstanceOf(HashMap.class);
    }

    @Test
    @DisplayName("反序列化：白名單外的類別 → enforce 拒絕，log 放行")
    void filterRejectsUnknownClass() throws Exception {
        assertThatThrownBy(() -> roundTrip(new SimpleDateFormat("yyyy"), Mode.ENFORCE))
                .isInstanceOf(InvalidClassException.class);
        assertThat(roundTrip(new SimpleDateFormat("yyyy"), Mode.LOG)).isInstanceOf(SimpleDateFormat.class);
    }

    @Test
    @DisplayName("反序列化：巢狀過深的物件 → enforce 拒絕")
    void filterRejectsDeepNesting() {
        Object nested = "leaf";
        for (int i = 0; i < 100; i++) {
            nested = new ArrayList<>(List.of(nested));
        }
        Object deep = nested;
        assertThatThrownBy(() -> roundTrip((Serializable) deep, Mode.ENFORCE)).isInstanceOf(InvalidClassException.class);
    }

    @Test
    @DisplayName("反序列化：反射、lambda 相關類別不在白名單")
    void filterDeniesReflection() {
        assertThat(SerialFilter.isAllowed("java.lang.reflect.Proxy")).isFalse();
        assertThat(SerialFilter.isAllowed("java.lang.invoke.SerializedLambda")).isFalse();
        assertThat(SerialFilter.isAllowed("org.apache.commons.collections.functors.InvokerTransformer"))
                .isFalse();
        assertThat(SerialFilter.isAllowed("java.util.HashMap")).isTrue();
    }

    // =====================================================================

    private ReceivePathPolicy policy() {
        return new ReceivePathPolicy(work, List.of("receiveFile", "files"));
    }

    private EmbeddedChannel channel(Mode mode) {
        TransferSecurityConfig cfg = new TransferSecurityConfig(mode, Mode.LOG, List.of("receiveFile", "files"), 1 << 20);
        return new EmbeddedChannel(new FileTransferServerHandler(cfg, policy()));
    }

    /** 模擬 FileTransferClientHandler：同一個物件換 bytes／endPoint 依序送出 */
    private static void sendFile(EmbeddedChannel ch, FileTransferFormat f, byte[] content, int chunks) {
        int size = Math.max(1, (content.length + chunks - 1) / chunks);
        for (int off = 0; off < content.length && ch.isOpen(); off += size) {
            byte[] part = Arrays.copyOfRange(content, off, Math.min(content.length, off + size));
            f.setBytes(part);
            f.setEndPoint(part.length);
            ch.writeInbound(f);
        }
    }

    private FileTransferFormat signedHeader(String targetPath, String name, String content) throws Exception {
        return signedHeader(targetPath, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private FileTransferFormat signedHeader(String targetPath, String name, byte[] content) throws Exception {
        File src = Files.write(work.resolve("src-" + UUID.randomUUID()), content).toFile();
        FileTransferFormat f = new FileTransferFormat();
        f.setFileName(name);
        f.setTargetPath(targetPath);
        f.setRequestId(RequestId.of(UUID.randomUUID().toString()));
        FileTransferAuth.sign(f, src);
        return f;
    }

    private static void actAs(String ek, KeyPair keys) throws Exception {
        TPMService t = mock(TPMService.class);
        when(t.getEKFingerprint()).thenReturn(ek);
        when(t.sign(any(byte[].class))).thenAnswer(inv -> {
            Signature s = Signature.getInstance(TPMService.SIGNATURE_ALGORITHM);
            s.initSign(keys.getPrivate());
            s.update(inv.getArgument(0, byte[].class));
            return s.sign();
        });
        FileTransferAuth.tpm = () -> t;
    }

    private static String sign(KeyPair keys, byte[] data) throws Exception {
        Signature s = Signature.getInstance(TPMService.SIGNATURE_ALGORITHM);
        s.initSign(keys.getPrivate());
        s.update(data);
        return java.util.Base64.getEncoder().encodeToString(s.sign());
    }

    private static Object roundTrip(Serializable o, Mode mode) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(o);
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            ois.setObjectInputFilter(new SerialFilter(mode));
            return ois.readObject();
        }
    }
}

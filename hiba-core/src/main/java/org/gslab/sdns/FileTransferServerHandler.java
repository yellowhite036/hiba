package org.gslab.sdns;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.gslab.message_hiba.FileTransferFormat;
import org.gslab.security.FileTransferAuth;
import org.gslab.security.ReceivePathPolicy;
import org.gslab.security.TransferSecurityConfig;
import org.gslab.security.TransferSecurityConfig.Mode;
import org.gslab.util.PathUtil;

/**
 * 檔案傳輸接收端（每條連線一個實例，一條連線傳一個檔案）。
 * <p>
 * 第一個區塊：驗證傳送端 TPM 簽章與存檔路徑（mode=enforce 不通過就斷線；mode=log 只記錄，照舊寫入）。
 * 之後的區塊依序寫入並計算 SHA-256，連線結束時與標頭宣告的雜湊比對。
 * 回覆給傳送端的是目前已收到的位元組數（Integer），與舊版協定相同。
 * </p>
 */
@Slf4j
public class FileTransferServerHandler extends ChannelInboundHandlerAdapter {

    private final TransferSecurityConfig cfg;
    private final ReceivePathPolicy policy;

    private FileTransferFormat header;
    private Path target;
    private RandomAccessFile out;
    private MessageDigest digest;
    private long offset;
    private boolean rejected;
    private boolean finished;

    public FileTransferServerHandler(TransferSecurityConfig cfg, ReceivePathPolicy policy) {
        this.cfg = cfg;
        this.policy = policy;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FileTransferFormat f) || rejected || finished) {
            return;
        }
        try {
            if (header == null && !open(ctx, f)) {
                rejected = true;
                ctx.close();
                return;
            }
            int n = f.getEndPoint();
            if (n > 0) {
                byte[] bytes = f.getBytes();
                if (bytes == null || n > bytes.length) {
                    abort(ctx, "malformed chunk");
                    return;
                }
                if (header.getFileSize() > 0 && offset + n > header.getFileSize() && cfg.fileTransferAuth() == Mode.ENFORCE) {
                    abort(ctx, "more data than the declared size " + header.getFileSize());
                    return;
                }
                out.seek(offset);
                out.write(bytes, 0, n);
                digest.update(bytes, 0, n);
                offset += n;
                ctx.writeAndFlush((int) offset);
            } else {
                finish();
                ctx.close();
            }
        } catch (IOException e) {
            log.warn("[FileTransfer] Write failed for {}: {}", target, e.getMessage());
            abort(ctx, "I/O error");
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        finish();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("[FileTransfer] Connection from {} failed: {}", ctx.channel().remoteAddress(), cause.toString());
        abort(ctx, "exception");
    }

    /** 第一個區塊：驗證並開檔。回傳 false 表示拒絕 */
    private boolean open(ChannelHandlerContext ctx, FileTransferFormat f) throws IOException {
        header = f;
        Mode mode = cfg.fileTransferAuth();
        Object remote = ctx.channel().remoteAddress();
        String name = f.getFileName();

        List<String> problems = new ArrayList<>();
        FileTransferAuth.Result auth = null;
        ReceivePathPolicy.Decision d = null;
        if (mode != Mode.OFF) {
            auth = FileTransferAuth.verify(f);
            if (!auth.ok()) {
                problems.add(auth.reason());
            }
            d = policy.check(f.getTargetPath(), name);
            if (!d.ok()) {
                problems.add(d.problem());
            } else {
                String real = policy.checkReal(d);
                if (real != null) {
                    problems.add(real);
                    d = new ReceivePathPolicy.Decision(d.dir(), d.file(), real);
                }
            }
        }

        if (!problems.isEmpty()) {
            if (mode == Mode.ENFORCE) {
                log.warn(
                        "[FileTransfer] REJECTED {} -> {} from {} (sender {}): {}",
                        name,
                        f.getTargetPath(),
                        remote,
                        FileTransferAuth.shortId(f.getSenderEk()),
                        String.join("; ", problems));
                return false;
            }
            log.warn(
                    "[FileTransfer] Would reject (log mode) {} -> {} from {} (sender {}): {}",
                    name,
                    f.getTargetPath(),
                    remote,
                    FileTransferAuth.shortId(f.getSenderEk()),
                    String.join("; ", problems));
        } else if (auth != null) {
            log.info(
                    "[FileTransfer] Verified {} from {} (sender {})",
                    name,
                    remote,
                    FileTransferAuth.shortId(auth.senderEk()));
        }

        if (d != null && d.ok()) {
            target = d.file();
        } else {
            // mode=off 或 log 模式下不合規則：照舊版行為寫入
            String dir = f.getTargetPath() != null ? f.getTargetPath() : HiBAInitial.fileTransferDirectory;
            Path dirPath = PathUtil.normalizePath(dir);
            target = PathUtil.buildPath(dirPath, name);
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        out = new RandomAccessFile(target.toFile(), "rw");
        out.setLength(0);
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        log.info("[FileTransfer] Receiving {} (requestId {})", target, f.getRequestId());
        return true;
    }

    /** 連線結束：關檔並比對大小與雜湊 */
    private void finish() {
        if (finished || out == null) {
            return;
        }
        finished = true;
        closeQuietly();
        String problem = null;
        if (header.getFileSize() > 0 && offset != header.getFileSize()) {
            problem = "incomplete: received " + offset + " of " + header.getFileSize() + " bytes";
        } else if (header.getFileSha256() != null) {
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(header.getFileSha256())) {
                problem = "SHA-256 mismatch (content altered in transit?)";
            }
        }
        if (problem == null) {
            log.info("[FileTransfer] receive: {} ({} bytes)", target, offset);
            return;
        }
        if (cfg.fileTransferAuth() == Mode.ENFORCE) {
            deleteQuietly();
            log.warn("[FileTransfer] {} discarded: {}", target, problem);
        } else {
            log.warn("[FileTransfer] {}: {}", target, problem);
        }
    }

    private void abort(ChannelHandlerContext ctx, String reason) {
        rejected = true;
        if (out != null && !finished) {
            finished = true;
            closeQuietly();
            if (cfg.fileTransferAuth() == Mode.ENFORCE) {
                deleteQuietly();
            }
            log.warn("[FileTransfer] Transfer of {} aborted: {}", target, reason);
        }
        ctx.close();
    }

    private void closeQuietly() {
        try {
            out.close();
        } catch (IOException e) {
            log.debug("close failed: {}", e.getMessage());
        }
    }

    private void deleteQuietly() {
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.debug("delete failed: {}", e.getMessage());
        }
    }
}

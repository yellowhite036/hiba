# 檔案傳輸（8088）安全修正測試

> 修正前，檔案傳輸埠（DATA_PORT 8088）沒有任何身分驗證、存檔路徑由傳送端任意指定、
> 用 Java 反序列化讀取資料，任何連得到 8088 的人都能把檔案寫到節點任何位置（例如覆寫 jar 或 `~/.ssh`），
> 完全繞過 M1 授權。8091 也是在驗證 TPM 簽章之前就先反序列化。
> 修改 `org.gslab.security.*`、`FileTransferServerHandler`、`FileTransferClient`、`FileTransferFormat` 後應依本文件重新測試。

---

## 1. 運作方式

```
傳送端 FileTransferClient                         接收端 FileTransferServerHandler（每條連線一個）
  │ 計算檔案大小與 SHA-256                            │
  │ 標頭：傳輸 ID、EK、時間、檔名、存檔路徑、大小、雜湊  │
  │ 用 TPM 簽章 ──── 第一個區塊 ──────────────────────▶│ 1. 用鏈上公鑰驗證簽章（未登記或已撤銷不通過）
  │                                                  │ 2. 時間差 ≤ 5 分鐘、傳輸 ID 沒用過（防重放）
  │                                                  │ 3. 存檔路徑在允許目錄內、檔名不帶路徑、不經由符號連結跳出
  │◀──────────── 已收位元組數（舊版協定）─────────────│ 依序寫入並計算 SHA-256
  │ ─── 其餘區塊 ───▶                                  │ 連線結束：比對大小與雜湊，不符就刪除（enforce）
```

| 設定（env.json `TransferSecurity`） | 預設 | 說明 |
|---|---|---|
| `fileTransferAuth` | `log` | `off`／`log`（只記錄，照舊寫入）／`enforce`（不通過就斷線、不寫檔） |
| `deserializationFilter` | `log` | JVM 全域反序列化白名單：`org.gslab.*` 與 JDK `java.lang/util/math/time`（排除反射、lambda），限制深度 64、參照數、陣列長度 |
| `allowedReceiveDirectories` | `receiveFile`、`files` | `files/<節點名>` 是 getFile 執行節點把檔案送回 broker 的位置 |
| `maxChunkBytes` | 64 MB | 單一物件上限（原本 `Integer.MAX_VALUE`） |

節點的 env.json 不會被自動更新覆蓋；沒有這個區塊時兩個模式都是 `log`。

## 2. 測試項目與結果（2026-11-25）

### U2 單元測試 `FileTransferSecurityTest`（23 項）

| 類別 | 情境 | 預期 |
|---|---|---|
| 路徑 | 允許目錄下的子目錄、`./receiveFile`、`files`、未指定（用第一個允許目錄） | 通過 |
| 路徑 | `receiveFile/../`、`..`、允許目錄外的絕對路徑 | 拒絕 |
| 路徑 | 檔名 `../../env.json`、`sub/a.txt`、`..`、空字串 | 拒絕 |
| 路徑 | 允許目錄內的符號連結指向外面 | 拒絕 |
| 簽章 | 登記設備簽的標頭 | 通過 |
| 簽章 | 沒有簽章、簽完改存檔路徑、未登記設備、冒用 EK、重放、時間差 10 分鐘 | 不通過 |
| 傳輸 | enforce：合法傳輸（分 4 個區塊） | 寫入，內容一致，回覆累計位元組數 |
| 傳輸 | enforce：沒有簽章、路徑跳出 | 斷線，不寫檔 |
| 傳輸 | enforce：途中內容被改 | 雜湊不符，檔案刪除 |
| 傳輸 | log：沒有簽章 | 照常寫入 |
| 反序列化 | HiBA 類別與 JDK 集合 | 允許 |
| 反序列化 | `java.text.SimpleDateFormat`（白名單外） | enforce 拒絕、log 放行 |
| 反序列化 | 巢狀 100 層 | enforce 拒絕 |

### F1 log 模式（5 台換版後）

| # | 情境 | 結果 |
|---|---|---|
| F1-1 | 透過 205 送 getFile | 173：`Verified wallet_test.txt from /192.168.1.141 (sender 42BD3E6B…)`，存到 `files/NewNode-141/`；205：`Verified … (sender 621EB343…)`，`receive: … (73 bytes)`；沒有 `Would reject`、沒有反序列化警告 |
| F1-2 | 從 173 用 `FileTransferProbe` 對 141:8088 送沒有簽章的檔案，存檔路徑 `/tmp/hiba_probe` | 141：`Would reject (log mode) … unsigned transfer …; target /tmp/hiba_probe is outside the allowed directories`，**檔案仍被寫入**（證明原本的漏洞） |

### F2 enforce 模式（5 台 `fileTransferAuth=enforce`，反序列化仍為 log）

| # | 情境 | 結果 |
|---|---|---|
| F2-1 | 同 F1-2 再送一次 | 141：`REJECTED probe.txt -> /tmp/hiba_probe … unsigned transfer …; target … outside …`，`/tmp/hiba_probe` 不存在 |
| F2-2 | 透過 205 送 getFile（requestId `d8192fe4`） | 205：`Verified wallet_test.txt from /192.168.1.141 (sender 42BD3E6B…)`，`receive: … (73 bytes)`；任務成功（重啟後拓撲改變，這次由 141 直接送給 205） |

**結果：通過。** 沒有簽章或路徑不合法的傳輸被拒絕，正常 getFile 不受影響。

### F3 反序列化白名單 enforce（173）

173 在 log 模式下運作約 20 分鐘（選舉、keepalive、節點回報、授權簽章、getFile），沒有任何
`Deserialization would be rejected`，改成 `deserializationFilter=enforce` 後重啟：
`Deserialization filter installed: mode=ENFORCE`，15:56 透過 205 送 getFile 成功，沒有 `Deserialization REJECTED`。
其他 4 台仍為 log，確認沒有警告後再逐台改（一次一台，避免重新選舉）。

指令：
```bash
# 攻擊測試（在任一節點）
cd /tmp && echo "probe $(date)" > probe.txt && \
  java -cp ~/HiBA/hiba-core-0.0.1.jar org.gslab.security.FileTransferProbe 192.168.1.141 8088 /tmp/hiba_probe probe.txt
# 改成 enforce（每台節點）
python3 -c "import json,os;p=os.path.expanduser('~/HiBA/env.json');d=json.load(open(p));d.setdefault('TransferSecurity',{})['fileTransferAuth']='enforce';json.dump(d,open(p,'w'),indent=4,ensure_ascii=False)" && sudo systemctl restart hiba.service
# 查 log
grep -a '\[FileTransfer\]\|\[Security\]' ~/HiBA/logs/hiba.log | tail -10 | grep -o '"message":"[^"]*"'
```

## 3. 尚未完成

| 項目 | 說明 |
|---|---|
| 反序列化白名單 enforce（其餘 4 台） | 173 已 enforce；其他節點先維持 log，跑過其他服務（BlockchainFileProtect、模型訓練等）並確認 log 沒有 `Deserialization would be rejected` 後再改 enforce |
| 傳送端不知道被拒絕 | `FileTransferClient.doTransfer` 不論接收端是否拒絕都回傳 true，任務結果仍顯示成功；要看接收端 log |
| 傳輸內容是明文 | 雜湊比對可發現竄改，但內容可被監聽；之後以 TLS 處理 |
| 允許目錄包含 `files` | 登記過的設備可以把檔案放進其他節點的 `files/`（getFile 的來源目錄）；getFile 另有鏈上雜湊驗證 |

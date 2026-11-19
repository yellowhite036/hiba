# 區塊鏈離線容錯測試

> 驗證「區塊鏈（Besu）或 accounting server 主機離線時，HiBA 網路仍然穩定運作」。
> 修改下列任何一處後，都應依本文件重新測試：
> `ChainCircuitBreaker`、`Web3BlockchainService`、`PublicKeyCacheService`、`LastKnownKeyStore`、
> `MessageTPMVerifyHandler`、`RpcHttp`、`MulticastReceiver`／`HiBANetwork` 的黑名單與讓位邏輯。

---

## 1. 背景

2026-11-19，accounting server 主機（Pi）離線時，同網段出現兩個 broker、兩個 accounting server，且不會自動合併。原因：

1. 每則 HiBA 訊息都要經過 `MessageTPMVerifyHandler` 驗章，驗章時查詢鏈上公鑰，而且是在 Netty 網路執行緒上執行。
2. RPC 主機離線時，每次查詢約 3 秒才失敗；失敗結果不會快取，每則訊息都重查。
3. 網路執行緒被卡住，心跳回覆不了，節點 3 秒後判定 broker 斷線，不斷重新選舉。
4. 連不上 broker 3 次後，節點把它列入黑名單，自己當 broker；而當上 broker 後黑名單永遠不清除，所以不會讓位。

## 2. 防護機制

| # | 機制 | 位置 | 行為 |
|---|---|---|---|
| 1 | 區塊鏈斷路器 | `ChainCircuitBreaker` | RPC 連線失敗後暫停查鏈 30 秒，之後只放行一個試探查詢 |
| 2 | 最後已知公鑰 | `LastKnownKeyStore`（`known_device_keys.json`） | 鏈連不上時，沿用最後一次從鏈上驗證過的公鑰，最多 24 小時；鏈恢復後全部重新驗證 |
| 3 | 鏈離線時拒絕未知設備 | `MessageTPMVerifyHandler` | 鏈連不上、又沒有最後已知公鑰的設備直接拒絕，不改用封包內附的公鑰 |
| 4 | RPC 連線逾時 1 秒 | `RpcHttp` | 可用 `-Dhiba.rpc.connectTimeoutMs`、`-Dhiba.rpc.readTimeoutMs` 調整 |
| 5 | 黑名單 60 秒過期 | `HiBANetwork.isBlacklisted` | 不論模式都會過期；讓位時把勝方移出黑名單 |
| — | 鏈離線仍可啟動 | `Web3jBlockchainServiceImpl` | HTTP 模式啟動時連不上鏈只記警告 |

## 3. 測試前準備

- **所有 accounting server 與 broker 候選機器都必須已在鏈上註冊 TPM。** 未註冊的機器在鏈正常時是靠封包公鑰通過驗章，鏈離線時會被拒絕（機制 3）。用 `install_node.sh` 安裝的節點會自動註冊；手動設定的機器（例如 Pi）要用 `register_node.sh` 補註冊。
- 新版 jar 已部署到所有機器，且**在鏈正常時運作至少 5 分鐘**，讓各節點互相從鏈上驗證、建立 `known_device_keys.json`。
- log 是 JSON 格式，而且可能含非文字字元。下文的指令都用 `grep -a`，並只取出時間與訊息。

以下用代號表示機器：

| 代號 | 角色 |
|---|---|
| `ACCT` | accounting server 兼 Besu 主機（Pi） |
| `BROKER` | 根網段的 broker（ACCT 離線時接手 accounting server） |
| `NODE` | BROKER 底下的一般節點 |

共用指令：

```bash
# 取出最近的相關 log（把 PATTERN 換成要找的訊息）
grep -a -E "PATTERN" ~/HiBA/logs/hiba.log | tail -6 | grep -o '"epochSecond":[0-9]*\|"message":"[^"]\{0,100\}'

# 這台目前認定的 accounting server
curl -s http://127.0.0.1:8092/api/acct

# 已知公鑰數量
grep -c '"publicKey"' ~/HiBA/known_device_keys.json
```

Besu 以 Docker compose 執行（`deploy/besu/setup_besu.sh`，資料目錄 `~/besu`）：

```bash
cd ~/besu && docker compose stop     # 停止鏈
cd ~/besu && docker compose start    # 啟動鏈
```

## 4. 測試項目

### A. 鏈正常時沒有改壞

| # | 做法 | 預期 |
|---|---|---|
| A1 | 開 `http://ACCT:8092/nodes` | 所有節點在線、版本相同、鏈上註冊正常 |
| A2 | 每台執行 `curl -s http://127.0.0.1:8092/api/acct` | 全部指向同一個 accounting server |
| A3 | 每台查 `Rejected device\|RPC unreachable` | 部署後沒有新增 |
| A4 | 在已綁定錢包的節點上傳檔案 | 上鏈成功，`/chain` 看得到 |

### B. 只停止 Besu（ACCT 主機在線）

**B1. 鏈離線，網路不受影響**

1. ACCT：`cd ~/besu && docker compose stop`
2. 等 3 分鐘。

| 檢查 | 預期 |
|---|---|
| ACCT 查 `RPC unreachable\|still unreachable\|Query failed` | 一行 `RPC unreachable … pausing … 30 s`，之後每 30 秒一行 `still unreachable`；**沒有**連續的 `Query failed` |
| BROKER、NODE 查 `Rejected device\|Broker Lost Connect` | 停止 Besu 後沒有新紀錄 |
| `/nodes` | 所有節點在線，accounting server 不變 |

> 快取有效期間（1 小時內驗證過）的節點完全不會查鏈，所以不會出現 `RPC unreachable`，這是正常的。

**B2. 鏈離線時重啟 broker**

1. BROKER：`sudo systemctl restart hiba.service`
2. 等 1 分鐘，查 `Loaded .* last-known|Blockchain not reachable at startup|last verified|Rejected device`。

| 預期 |
|---|
| `systemctl is-active hiba.service` 為 `active` |
| `Blockchain not reachable at startup, file protection will work once it is back`（警告，不影響啟動） |
| `Loaded N last-known device key(s) from known_device_keys.json` |
| `RPC unreachable, using last verified public key for …` |
| 沒有新的 `Rejected device`；底下的節點約 30 秒內重新連上 |

**B3. 從沒驗證過的設備被拒絕**

1. NODE：`sudo systemctl stop hiba.service; mv ~/HiBA/known_device_keys.json ~/HiBA/known_device_keys.json.bak; sudo systemctl start hiba.service`
2. 等 1 分鐘，查 `Rejected device|Broker Lost Connect`。

| 預期 |
|---|
| NODE 出現 `Rejected device <BROKER 的 EK>… blockchain unreachable … it can join after the blockchain is back` |
| BROKER 沒有拒絕紀錄 |
| `/nodes` 中 NODE 顯示離線（這是預期的拒絕） |

先不要還原檔案，接著做 B4。

**B4. 鏈恢復**

1. ACCT：`cd ~/besu && docker compose start`
2. 等 2 分鐘（斷路器下一次試探最多等 30 秒）。

| 預期 |
|---|
| 出現 `RPC reachable again, resuming blockchain queries` 與 `Re-verifying all cached public keys against the blockchain` |
| NODE 之後不再出現 `Rejected device`，`/nodes` 中恢復在線 |
| NODE 的 `known_device_keys.json` 重新產生 |

最後在 NODE 上刪除 `known_device_keys.json.bak`。

### C. ACCT 主機整台離線

1. 確認 NODE 已記住 ACCT：`grep -c <ACCT 的 EK 前 16 碼> ~/HiBA/known_device_keys.json` 為 1。
2. ACCT：`sudo poweroff`
3. 每隔幾分鐘檢查，維持 10 分鐘以上。

| 預期 |
|---|
| BROKER 接手 accounting server：所有機器的 `/api/acct` 只有一台是 `acting`，其他都指向它 |
| `Broker Lost Connect`、`add to block list` 的數量不會持續增加 |
| 從任一台開 `/nodes`，所有節點在線 |

ACCT 開機後，Besu 與 HiBA 會自動啟動（Docker、`hiba.service` 都已設定開機啟動）。各節點應出現 `RPC reachable again`。

### D. 兩個 broker 自動合併

1. 在另一台根網段節點（以下稱 `NODE2`）查 HiBA port：`grep -o '"HiBA_PORT"[^,}]*' ~/HiBA/env.json`（只印 port）。
2. NODE2：`sudo iptables -I OUTPUT -p tcp -d <BROKER IP> --dport <PORT> -j DROP`
3. 等 1 分鐘，NODE2 出現 `add to block list`（可能自己當上 broker）。
4. NODE2：`sudo iptables -D OUTPUT -p tcp -d <BROKER IP> --dport <PORT> -j DROP`
5. 等 2 分鐘。

| 預期 |
|---|
| 其中一台出現 `Another broker … wins, local broker steps down and rejoins.` |
| 所有機器的 `/api/acct` 只有一台是 `acting` |

## 5. 常見問題

| 現象 | 原因與處理 |
|---|---|
| 鏈離線後，所有 broker 拒絕 ACCT（`Rejected device <ACCT 的 EK>`） | ACCT 的 TPM 沒有在鏈上註冊。用 `register_node.sh` 註冊後，等各節點重新驗證一次 |
| 節點重啟後 `systemctl is-active` 一直是 `activating` | 舊版在鏈離線時無法啟動；確認已部署含 `Blockchain not reachable at startup` 修正的版本 |
| 沒有 `Loaded … last-known` | `known_device_keys.json` 不存在：鏈正常時節點還沒驗證過任何設備 |
| 看不到 `PublicKeyCacheService` 的訊息 | 服務以 `-DTPM_LOG_LEVEL=WARN` 啟動，該類別的 INFO 不顯示；恢復訊息改看 `Web3BlockchainService` 的 `Re-verifying …` |
| 兩台 VM 的提示字元相同，分不出是哪台 | 先執行 `hostname -I` |
| accounting server 換手後，其他網段的節點短暫不在清單上 | 其他網段的 broker 要重新找到新的 accounting server（每 15 秒重試），稍等即可。跨網段 ping 不通不代表 HiBA 連不上（可能只擋 ICMP），改用 `curl http://<IP>:8092/api/acct` 確認 |

## 6. 測試紀錄

| 日期 | jar md5 | A | B1 | B2 | B3 | B4 | C | D | 備註 |
|---|---|---|---|---|---|---|---|---|---|
| 2026-11-19 | `5ffb2c96fc4bb7747311445326ff41ec` | A1、A2 ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | 測試中發現 ACCT 未註冊 TPM、鏈離線時節點無法啟動，皆已修正。D：封鎖期間新 broker 一出現，原 broker 即讓位，未出現兩個 accounting server |

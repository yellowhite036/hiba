# M1 多 Validator 授權測試

> 驗證「Broker 無法單方面授權任務：任務必須取得鏈上 Validator 名單中達門檻數量的 TPM 簽章，
> 且由執行任務的節點自行驗證」。
> 修改下列任何一處後，都應依本文件重新測試：
> `org.gslab.authorization.*`、`ValidatorRegistry.sol`、`DistributionThreadExecutor`（送出 `TaskStartMessage` 處）、
> `ClientChannelHandlers.TaskStartHandler`、`Web3BlockchainService.callView`。

---

## 1. 運作方式

```
請求 ──▶ 一般節點 ──RegisteMessage──▶ Broker（DistributionThreadExecutor）
                                         │ 1. 讀鏈上 ValidatorRegistry（名單、版本、門檻）
                                         │ 2. 建立授權憑證（任務內容 SHA-256、nonce、期限、名單版本），用自己的 TPM 簽
                                         │ 3. POST 到每個 Validator 的 :8095/auth/sign
                                         ▼
                        各 Validator 獨立檢查後用 TPM 簽章（或回 403 與理由）
                                         │
                       Broker 把憑證放進 TaskStartMessage 任務 Map 的 "authorization"
                                         ▼
           執行任務的節點：自己查鏈上名單，驗證門檻、每個簽章、任務內容、期限、nonce
```

| 檢查項目 | Validator 簽章前 | 執行節點執行前 |
|---|---|---|
| 自己是 Active Validator | ✔ | — |
| 名單版本＝鏈上目前版本 | ✔ | ✔ |
| 未過期、issuedAt 不在未來（容許 60 秒時差） | ✔ | ✔ |
| 有效期不超過 `certTtlSeconds` × 2 | ✔ | — |
| 服務在 `allowedServices` 內（空＝不限） | ✔ | — |
| 請求者是鏈上登記、未撤銷的設備，且 TPM 簽章正確 | ✔ | ✔ |
| nonce 沒用過 | ✔（各 Validator 自己記） | ✔ |
| 目標節點是自己 | — | ✔ |
| 任務內容雜湊與憑證一致 | — | ✔ |
| 目標節點所屬網域內的有效 Validator 簽章數 ≥ 該網域過半數（D1 起） | — | ✔ |

**門檻（D1 起）**：以執行任務節點所屬網域計算，需要該網域 Active Validator 的過半數 `floor(n/2)+1`；
其他網域的簽章不計入。斷線的 Validator 仍算在 n 內（不因收不到回應而降低門檻），計畫性停機請用 `validators.sh suspend`／`resume`。
節點的網域：是 Validator 時以鏈上名單為準，否則用 env.json 的 `Authorization.domainId`；兩者都沒有時退回鏈上全網門檻（`setThreshold`，0＝ceil(2n/3)）。

`Authorization.mode`：`off`（預設，完全不啟用）／`log`（只記錄，任務照常執行）／`enforce`（驗證不過就不執行）。

## 2. 測試環境（2026-11-23）

| 項目 | 值 |
|---|---|
| jar | md5 `a65f255d37d9c6f6fb337f11c425ea33` |
| 區塊鏈 | Besu QBFT `http://192.168.1.62:8545`，chainId 20261117 |
| TPMDeviceRegistry | `0x08ACdce648Ef96e4734d5956D96EBC60A2bf1C25` |
| ValidatorRegistry | `0x2D0332C4CA7964Efe46426975fd6957EA85363e6`（owner `0xd98f37C8…`，在 Pi 上用 forge 部署） |
| 名單 | setVersion 4，門檻 0（自動 ceil(2n/3) = 2） |
| Validator | NewNode-173 `621EB343…`（broker）、NewNode-141 `42BD3E6B…`、NewNode-205 `7C55477C…`，皆 domainA、埠 8095 |
| mode | 173、141、205 為 `log`（E1 起為 `enforce`）；11、12 於 R1-2 起以 `log` 加入（domainB） |
| 測試發起端 | 192.168.1.223（Windows） |

部署步驟：`deploy/besu/deploy_validator_registry.sh` → Pi 上 `validators.sh set-env <位址>` → `validators.sh add <EK> domainA <IP>:8095`
→ 各節點 env.json 加 `"Authorization": {"mode": "log", "validatorRegistry": "<位址>"}` 後重啟。EK 可從 `http://192.168.1.62:8092/api/nodes` 的 `ek` 欄位取得。

## 3. 觸發任務分派的方法

**請求要送到「沒有該資源的一般節點」。** 直接送給 broker 時，broker 的資源表含整個網域的檔案，`Taker.checkCanHandleRequest` 會判斷可在本機處理而直接執行（log：`Local can handle - executing directly`），不經過 `DistributionThreadExecutor`，不會產生授權憑證。

本次使用 141 上的 `wallet_test.txt`，從沒有該檔案的 205 發出：

```bash
curl -s -m 90 -X POST http://192.168.1.205:8092/ -H "Content-Type: application/json" \
  -d '{"serviceName":"getFile","requestName":"getFile","fileName":"wallet_test.txt"}'
```

流程：205 → `RegisteMessage` → 173 分派 → `TaskStartMessage` → 141 執行，檔案回到 205。

查看結果（各節點）：

```bash
hostname -I; grep -a '\[Auth\]' ~/HiBA/logs/hiba.log | tail -6 | grep -o '"message":"[^"]*"'
```

## 4. 測試項目與結果

### P1 正常授權（名單 v4，3 台 Active）

| 節點 | log |
|---|---|
| 三台 | `Validator set version 4 loaded: 3 active, threshold 2` |
| 173、141、205 | `Signed certificate a8e31c4f from NewNode-173 for NewNode-141 (getFile)` |
| 173 | `Certificate a8e31c4f for NewNode-141 (getFile): 3/3 validator signatures (threshold 2, set v4)` |
| 141 | `Task authorized: 3/2 validator signatures, ok (certificate a8e31c4f)`（3 個有效、需要 2 個；之後的版本改寫成 `3 valid validator signatures (need 2)`） |

**結果：通過。**

### T0 偽造簽章請求（直接打 Validator）

從沒有 TPM 私鑰的機器（192.168.1.223）冒充 173，直接 POST 到 141 的 `/auth/sign`。請求簽章是隨機位元組。

```python
# python -I forge.py
import json, time, uuid, base64, os, urllib.request
EK173 = "621EB343B8E834135E05E703478D94FACE277B93BEC644FB5CAA0DD23BCEA604"
now = int(time.time() * 1000)
def req(name, ip="141", **over):
    c = dict(certificateId=str(uuid.uuid4()), requesterId=EK173, requesterNode="NewNode-173",
             targetNode="NewNode-141", requestId=str(uuid.uuid4()), requestName="getFile", serviceName="getFile",
             taskDigest="ab" * 32, nonce=str(uuid.uuid4()), issuedAt=now, expiresAt=now + 60000,
             validatorSetVersion=4, requiredThreshold=2,
             requesterSignature=base64.b64encode(os.urandom(256)).decode())
    c.update(over)
    r = urllib.request.Request(f"http://192.168.1.{ip}:8095/auth/sign", json.dumps(c).encode(),
                               {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=5) as resp:
            print(name, resp.status, resp.read().decode()[:120])
    except urllib.error.HTTPError as e:
        print(name, e.code, e.read().decode())
req("A")                                                  # 偽造請求簽章
req("B", validatorSetVersion=3)                           # 舊名單版本
req("C", issuedAt=now - 600000, expiresAt=now - 300000)   # 已過期
req("D", expiresAt=now + 86400000)                        # 有效期 1 天
req("E", requesterId="DEADBEEF" * 8)                      # 未登記的請求者
```

| # | 情境 | 回應 |
|---|---|---|
| A | 冒充 173，請求簽章偽造 | 403 `requester signature invalid or requester not registered` |
| B | 名單版本 v3（目前 v4） | 403 `validator set version mismatch: request v3, current v4` |
| C | 已過期 | 403 `request already expired` |
| D | 有效期 1 天 | 403 `validity period too long` |
| E | 未在鏈上登記的設備當請求者 | 403 `requester signature invalid or requester not registered` |

**結果：通過。** 沒有已登記設備的 TPM 私鑰，就拿不到任何 Validator 簽章。

### T1 簽章數不足門檻

141、205 設定 `Authorization.allowedServices = ["BlockchainFileProtect"]` 後重啟，使兩者拒簽 getFile，只剩 173 簽章（1/3，門檻 2）。

```bash
python3 -c "import json,os;p=os.path.expanduser('~/HiBA/env.json');d=json.load(open(p));d['Authorization']['allowedServices']=['BlockchainFileProtect'];json.dump(d,open(p,'w'),indent=4)" && sudo systemctl restart hiba.service
```

| 節點 | log |
|---|---|
| 141、205 | `Refused to sign certificate cd2a5d17 from 192.168.1.173: service 'getFile' not allowed by this validator` |
| 173 | `Validator 192.168.1.141:8095 refused: …`、`Validator 192.168.1.205:8095 refused: …` |
| 173 | `Certificate cd2a5d17 for NewNode-141 (getFile) below threshold: 1/3 signatures, need 2 (set v4)` |
| 141 | `Task would be rejected (mode=log, still executing): only 1 valid validator signatures, need 2` |

**結果：通過。** 各 Validator 獨立決定是否簽章；執行節點正確判定應拒絕（log 模式下仍執行）。還原：把 `allowedServices` 設回 `[]` 後重啟。

### T2 暫停 Validator，名單版本立即生效

Pi 上暫停 205，名單 v4 → v5（2 台 Active，門檻 ceil(4/3) = 2）：

```bash
sudo bash ~/hiba_dist/validators.sh suspend 7C55477C424BC1EE301D06A0124A29834C26273FEDA964731E90408127FCDFD9
```

| 測試 | 結果 |
|---|---|
| 對 141 送 v4 的簽章請求（T0 腳本，`validatorSetVersion=4`） | 403 `validator set version mismatch: request v4, current v5` |
| 對被暫停的 205 送 v5 的簽章請求 | 403 `this node is not an active validator (set v5)` |
| 173 分派任務 | `Validator set version 5 loaded: 2 active, threshold 2`，`Certificate e3d6bc3e … 2/2 validator signatures (threshold 2, set v5)`，沒有向 205 要簽章 |
| 141 執行任務 | `Task authorized: 2/2 validator signatures, ok (certificate e3d6bc3e)` |
| 141 記錄攻擊來源 | `Refused to sign certificate f31ca4de from 192.168.1.223: validator set version mismatch: request v4, current v5` |

**結果：通過。** 鏈上名單一變動，舊版本的請求即失效，被暫停的 Validator 立刻被排除。
還原：`validators.sh resume <205 的 EK>`（名單 → v6，3 台 Active）。

### E1／E2 enforce 模式（2026-11-23，jar `654c192f`，名單 v6）

173、141、205 設定 `Authorization.mode = "enforce"` 後重啟。這一版在拒絕時會回傳失敗的 `TaskFinishMessage`
（`success=false`、`transferred=false`、`error="Unauthorized: <原因>"`），因為 Broker 等待結果沒有逾時，不回覆會一直卡住。

| # | 情境 | 結果 |
|---|---|---|
| E1 | 正常任務（3/3 簽章） | 205 取回 141 的檔案（`successCount 1`），整個請求 1.5 秒 |
| E2 | 141、205 設 `allowedServices=["BlockchainFileProtect"]`，只剩 1/3 簽章 | 141：`Refused to sign certificate aa9360fe … not allowed`，接著 `Task REJECTED (mode=enforce, not executing): only 1 valid validator signatures, need 2`；205 收到 `successCount 0`，請求 1.3 秒結束，沒有卡住 |

**結果：通過。** 簽章不足門檻時，執行節點拒絕執行，Broker 正常收尾。

| # | 情境 | 結果（jar `0f2209f4`） |
|---|---|---|
| E3 | 同 E2，檢查請求者收到的回應 | `"success": false`，`error.message` = `NewNode-173: Requested file not in resource list: wallet_test.txt; NewNode-141: Unauthorized: only 1 valid validator signatures, need 2` |

E3 之前（jar `654c192f`）getFile 只回 `successCount 0`，看不到原因。現在 `getFileDistributor` 在完全沒取到檔案時，
把各節點回報的錯誤放進內層 `result` 的 `success`／`errorMessage`（請求節點的 `RequestFinishMessageHandler` 只讀內層 `result`）。
錯誤代碼目前顯示 `INTERNAL_ERROR`（`UnifiedError.ErrorCode` 沒有「未授權」這一類）。

### U1 離線單元測試（JUnit，2026-11-23）

實機難以重現的攻擊（需攔截並改寫 Netty 訊息）改用 `hiba-core/src/test/java/org/gslab/authorization/AuthorizationServiceTest.java` 驗證：
以軟體 RSA 金鑰模擬 3 台 Validator（V1–V3）、Broker（R）與一台已登記但不在名單的設備（X），鏈上查詢以 mock 回傳，
簽章演算法與實機相同（SHA256withRSA）。不需要 TPM、區塊鏈或網路。

```bash
cd ~/build/hiba-dev/hiba && mvn -pl hiba-core -am test -Dtest=AuthorizationServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

| 對象 | 情境 | 預期 |
|---|---|---|
| 執行節點 | 2/3 簽章 | 通過 |
| 執行節點 | 經 Java 序列化（模擬網路傳送）後驗證 | 通過（任務雜湊不變） |
| 執行節點 | 竄改任務內容（換檔名） | `task content does not match` |
| 執行節點 | 簽完章後竄改憑證欄位 | `requester signature invalid` |
| 執行節點 | 竄改其中一個 Validator 簽章 | 不計入 → `only 1 valid …, need 2` |
| 執行節點 | 重放同一張憑證 | 第二次 `nonce already used` |
| 執行節點 | 給 141 的憑證送到 205 | `certificate is for NewNode-141, not NewNode-205` |
| 執行節點 | 不在名單的設備簽章 | 不計入 |
| 執行節點 | 同一 Validator 的簽章放兩份 | 只算 1 個 |
| 執行節點 | 名單 v4 → v5 後使用舊憑證 | `validator set version mismatch` |
| 執行節點 | 已過期 | `certificate expired` |
| 執行節點 | 請求者未登記 | `requester … not registered` |
| 執行節點 | 沒有附憑證 | `no authorization certificate` |
| 執行節點 | mode=off | 一律通過 |
| Validator | 合法請求 | 簽章，可用公鑰驗證 |
| Validator | 同一請求簽第二次 | `nonce already signed` |
| Validator | 請求者簽章偽造 | `requester signature invalid` |
| Validator | 自己不在名單 | `not an active validator` |
| Validator | 服務不在 allowedServices | `service 'getFile' not allowed` |
| Validator | 有效期過長 | `validity period too long` |
| 任務雜湊 | 欄位順序不同、含 authorization 欄位 | 雜湊相同；內容不同則雜湊不同 |

**結果：`Tests run: 21, Failures: 0, Errors: 0` — 通過。**

D1（網域過半數）新增 6 項，名單模擬 v9：V1–V3 domainA、V4–V5 domainB、全網門檻 4：

| 對象 | 情境 | 預期 |
|---|---|---|
| 執行節點（domainA） | 網域 B 全部沒簽，A 有 3/3 | 通過（舊規則 3/5 < 4 會被拒） |
| 執行節點（domainA） | A 只有 1 個、B 有 2 個簽章 | `only 1 … in domainA, need 2`（別網域的不計入） |
| 執行節點（domainB） | B 只有 1 個簽章 | 被拒（2 台的網域需要 2 個） |
| 執行節點（domainA） | A 的 3 台中 1 台斷線（沒簽） | 2/3 通過，門檻仍為 2（斷線不降低門檻） |
| 執行節點（非 Validator） | env.json `domainId = domainB` | 以 domainB 的過半數計算 |
| 執行節點（非 Validator） | 不知道網域 | 退回全網門檻 4 |

**結果：共 27 項，`Tests run: 27, Failures: 0, Errors: 0`（待在 Pi 上執行確認）。**

### R1 Netty 轉送收集簽章（2026-11-23，jar `e32744bd`）

網段 10.10.2.0/24（11、12）在路由器 192.168.1.132 後面，173 沒有往 10.10.2.x 的路由、無法主動連到 11、12。
HiBA 的連線一律由下層主動連上層，所以簽章請求改為沿既有連線轉送（`SignRelay`，`Authorization.signTransport = "netty"`，預設）：

```
173 ─AuthSignRequest─▶ 141、205、11（173 的 clients）
                        11 ─AuthSignRequest─▶ 12（11 的 client）
                        11 ◀─AuthSignResponse─ 12
173 ◀─AuthSignResponse（11＋12 的簽章）─ 11
```

| # | 情境 | 結果 |
|---|---|---|
| R1-1 | 網域 A 3 台（v6），改走 Netty | 173：`Certificate 37de89ca … 3/3 validator signatures (threshold 2, set v6)`，`signTransport=NETTY`；141：`Signed certificate 37de89ca` |
| R1-2 | 173 移除 10.10.2.x 路由（`curl 10.10.2.11:8095` 連不到）後，11、12 以 domainB 加入名單（v8，5 台，門檻 4） | 173：`Certificate c4586616 for NewNode-141 (getFile): 5/5 validator signatures (threshold 4, set v8)`；11：`Signed certificate c4586616`；任務成功 |

**結果：通過。** 上層不需要能主動連到下層；轉送節點（11）只傳遞，12 的簽章由 173 以鏈上公鑰驗證。

### D1 網域過半數門檻（2026-11-24，名單 v8）

原本的全網門檻 ceil(2n/3) 在 5 台時是 4：任一網域整個斷線（例如網域 B 兩台）其他網域的任務就全部停擺。
改為「執行任務節點所屬網域的過半數」後，每條產線只需要自己網域的 Validator 同意。

| # | 情境 | 結果 |
|---|---|---|
| D1-1 | 5 台都在線，透過 205 送 getFile（目標 141，domainA） | 173：`Certificate bdc05e9c for NewNode-141 (getFile): domainA 3/3 (need 2), domainB 2/2 (need 2) (set v8)`；141：`Task authorized: 3 valid validator signatures (need 2), ok (certificate bdc05e9c in domainA)`；任務成功 |
| D1-2 | 停止 11、12 的 `hiba.service`（網域 B 整個斷線），再送一次 | 173：`Certificate f72da38f for NewNode-141 (getFile): domainA 3/3 (need 2), domainB 0/2 (need 2) (set v8)`；141：`Task authorized: 3 valid validator signatures (need 2), ok (certificate f72da38f in domainA)`；任務成功（舊規則 3/5 < 4 會被拒） |

**結果：通過。** 一個網域斷線不影響其他網域；網域 B 的 n 仍為 2（斷線不降低門檻）。

## 5. 尚未測試

| 項目 | 說明 |
|---|---|
| enforce 模式下的其他服務 | 只測過 getFile；其他服務的 `merge` 收到失敗結果的行為未確認 |
| 鏈離線 | 名單沿用最後一次讀到的版本（最多 24 小時），公鑰沿用 `LastKnownKeyStore`；可依 `CHAIN_OFFLINE_TEST.md` 停止 Besu 後重做 P1 |

## 6. 已知限制

- Broker 每分派一個任務，都要等到簽章收齊或逾時（`signTimeoutMs`，預設 3000 ms）才送出。
- 任務內容雜湊是對任務 Map 的標準化字串計算。若 Map 中有物件沒有實作 `toString`，兩端算出的值會不同，log 會出現 `task content does not match certificate`。
- jar 自動更新目前沒有簽章保護：accounting server 被入侵時，可推送新版 jar 繞過本機制（M2 處理）。
- Validator 名單目前由管理員維護（M1）；改由網域投票決定是之後的里程碑。
- 網域過半數：網域內 Validator 斷線超過一半時，該網域的任務會被拒（刻意設計，避免惡意切斷降低門檻）；計畫性停機請先 `suspend`。
  只有 1 台 Validator 的網域，門檻是 1，等於該台可單獨授權，網域至少應有 3 台。

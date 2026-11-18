# Besu 私有鏈：取代 Anvil 測試鏈

正式使用時，HiBA 的設備註冊（`TPMDeviceRegistry`）與檔案保護（`FileProtectionContract`）合約部署在
Hyperledger Besu 私有鏈上（QBFT 共識、零手續費、資料存在硬碟、只給授權機器連線）。

| 檔案 | 在哪台執行 | 做什麼 |
|---|---|---|
| `setup_besu.sh` | Pi（鏈主機） | 用 Docker 建立 1 或 4 個 Besu 驗證節點，RPC 開在 `:8545` |
| `deploy_contracts.sh` | 有 Foundry 的 x86 機器（例如 192.168.1.60） | 產生正式的管理員 / 檔案上鏈帳號，部署兩份合約 |
| `update_env.sh` | Pi | 把 Pi 與節點範本的 env.json 換成新鏈的設定 |

## 步驟

1. **Pi：建立鏈**（需要 Docker；腳本會檢查並提示安裝方式）

   ```bash
   bash ~/build/hiba-dev/hiba/deploy/besu/setup_besu.sh
   ```

   記憶體 6 GB 以上會用 4 個驗證節點，否則 1 個（`--nodes` 可指定）。完成後 `--status` 可查看狀態。

2. **192.168.1.60：部署合約**

   ```bash
   bash deploy_contracts.sh --rpc http://192.168.1.62:8545
   scp -r besu-deploy user@192.168.1.62:~/besu-deploy
   ```

3. **Pi：更新設定並重啟**

   ```bash
   bash ~/build/hiba-dev/hiba/deploy/besu/update_env.sh --deploy ~/besu-deploy
   sudo systemctl restart hiba.service
   ```

4. **重新登記設備、更新已安裝節點的 env.json**：依 `update_env.sh` 最後印出的說明。

## 私鑰放哪裡

| 帳號 | 權限 | 放在 |
|---|---|---|
| 管理員（`admin.key`） | 登記、更新公鑰、撤銷設備、管理管理員 | 只放在 Pi 的 `~/HiBA/env.json`，另外離線備份 |
| 檔案上鏈（`uploader.key`） | 只能寫檔案紀錄、讀鏈 | 各節點的 env.json |
| Besu 驗證節點金鑰（`~/besu/node*/key`） | 參與出塊 | 只放在鏈主機 |

## 注意

- 4 個驗證節點都在同一台 Pi 上時，Pi 壞掉整條鏈就停了；正式環境把節點分散到不同機器。
- RPC（8545）目前對整個區網開放；正式環境用防火牆只開放給 HiBA 的機器。
- `setup_besu.sh` 預設用 `hyperledger/besu:latest`；要固定版本可設 `BESU_IMAGE=hyperledger/besu:<版本>`。

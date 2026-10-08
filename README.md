# hiba

## Makefile 使用方式

本專案在根目錄提供 `Makefile`，將常用的 Maven 流程與 `ansible/` 的部署/重啟/停止腳本封裝成 `make <target>`。

可用 target 一覽：

```bash
make help
```

### Maven

- `make clean`：等同 `mvn clean`
- `make compile`：等同 `mvn clean compile -DskipTests`
- `make package`：等同 `mvn clean package -DskipTests`

如需額外 Maven 參數可用 `MAVEN_ARGS`：

```bash
make package MAVEN_ARGS='-Dspotless.check.skip=true'
```

### Ansible（部署/重啟/停止）

以下 target 會自動切到 `ansible/` 目錄執行對應腳本：

- `make ansible-deploy`：執行 `ansible/deploy-run-hiba-service.sh`
- `make ansible-restart`：執行 `ansible/restart-hiba-service.sh`
- `make ansible-stop`：執行 `ansible/stop-hiba-service.sh`
- `make ansible-rolling-restart`：執行 `ansible/rolling-restart-hiba-service.sh`

#### 指定節點（HOST / HOSTS）

`ansible/` 的腳本本身支援指定 host（inventory 內的別名），Makefile 也支援用變數傳入：

- 指定單一節點：用 `HOST=<host>`
- 指定多個節點：用 `HOSTS='host1 host2 ...'`（以空白分隔）

範例：

```bash
# 停止單一節點
make ansible-stop HOST=node1

# 部署到多個節點
make ansible-deploy HOSTS='node1 node2'

# 重啟單一節點
make ansible-restart HOST=accounting-server
```

#### 快捷 target（單一節點）

也可以直接用 pattern target（等同於帶入 `HOST`）：

```bash
make ansible-stop-node1
make ansible-restart-accounting-server
make ansible-deploy-node2
```

#### 查看可用 hosts

```bash
make hosts
```

等同從 `ansible/hosts.ini` 列出可用的 host 別名。

## 設定讀取方式

本專案主要從 `env.json` 讀取系統設定，並允許使用環境變數覆蓋敏感資訊（例如 private key）。

### 主要來源（env.json）

- Blockchain：共用區塊鏈連線設定（RPC、network ID、gas、polling）
- BlockchainFileProtect：檔案保護服務設定（DB、serviceType、合約位址、重試等）
- BlockchainTPMRegistry：TPM 裝置註冊合約設定（contractAddress、privateKey）

範例片段：

```json
"Blockchain": {
 "rpcURL": "http://127.0.0.1:8545",
 "networkID": "1337",
 "gasPrice": 20000000000,
 "gasLimit": 500000,
 "pollingIntervalMs": 1500,
 "pollingAttempts": 40
},
"BlockchainFileProtect": {
 "serviceType": "web3j",
 "databaseURL": "jdbc:sqlite:blockchain_file_protect.db",
 "databaseUserName": "root",
 "databasePassword": "password",
 "contractAddress": "0x...",
 "privateKey": "",
 "enableDatabaseRetry": false
},
"BlockchainTPMRegistry": {
 "contractAddress": "0x...",
 "privateKey": ""
}
```

### 環境變數覆蓋（建議用於敏感資訊）

**FileProtection / BlockchainFileProtect**

- `BLOCKCHAIN_PRIVATE_KEY`
- `BLOCKCHAIN_CONTRACT_ADDRESS`
- `BLOCKCHAIN_RPC_URL`
- `BLOCKCHAIN_NETWORK_ID`

**TPM Device Registry**

- `TPM_DEVICE_REGISTRY_PRIVATE_KEY`
- `TPM_DEVICE_REGISTRY_CONTRACT_ADDRESS`
- `BLOCKCHAIN_RPC_URL`
- `BLOCKCHAIN_CHAIN_ID`
- `BLOCKCHAIN_GAS_PRICE`
- `BLOCKCHAIN_GAS_LIMIT`
- `BLOCKCHAIN_NETWORK_NAME`

> 注意：環境變數會優先於 `env.json`，建議在生產環境使用環境變數或 Secret 管理機制。

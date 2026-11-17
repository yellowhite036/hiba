# hiba_dist：節點安裝與自動更新腳本

accounting server（Pi）上的 `~/hiba_dist` 是新節點安裝時抓的資料夾。這裡只放腳本；
jar 和設定範本不進 git：

| 檔案 | 在 git 裡 | 說明 |
|---|---|---|
| `install_node.sh` | ✔ | 在新節點上執行：裝 JDK、建立軟體 TPM、建立 `~/HiBA`、註冊 TPM、設定開機自動啟動，最後呼叫 `setup_node.sh` |
| `setup_node.sh` | ✔ | 在節點上更新 jar／設定並重啟 HiBA，安裝自動更新計時器（`--status`、`--rollback` 等選項見檔頭） |
| `hiba_autoupdate.sh` | ✔ | 由 `setup_node.sh` 安裝成 `/usr/local/sbin/hiba-autoupdate`，每 2 分鐘向 accounting server 比對 jar 版本 |
| `register_node.sh` | ✔ | 在 accounting server 上把節點的 TPM 註冊到 TPMDeviceRegistry（`install_node.sh` 透過 ssh 呼叫） |
| `hiba-core-0.0.1.jar` | ✘ | 編譯產物，由 Pi 編譯後複製進 `~/hiba_dist` |
| `template/` | ✘ | 新節點的 env.json 等設定範本，**含區塊鏈私鑰**，只放在 Pi 上 |

這些 `.sh` 也會被 `hiba-core/pom.xml` 打包進 jar 的 `hiba_dist/`。節點的自動更新換上新 jar 後，
會從 jar 取出新版 `hiba_autoupdate.sh` 自我更新，並順便把 `hiba.service` 改成 `Restart=always`、
調寬軟體 TPM 的字典攻擊保護，所以已安裝的節點只要 jar 是最新版就好。

## 更新 Pi 上的 hiba_dist

在 Pi 上編譯完之後：

```bash
cp ~/build/hiba-dev/hiba/deploy/hiba_dist/*.sh ~/hiba_dist/ && cp ~/HiBA/hiba-core-0.0.1.jar ~/hiba_dist/
```

## 新節點安裝

```bash
scp -r user@192.168.1.62:/home/user/hiba_dist ~/ && bash ~/hiba_dist/install_node.sh --name NewNode-XXX
```

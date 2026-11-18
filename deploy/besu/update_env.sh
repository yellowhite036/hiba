#!/bin/bash
# 在 Pi 上把 HiBA 的設定從 Anvil 測試鏈換成 Besu 私有鏈（deploy_contracts.sh 的輸出）。
#
#   bash update_env.sh --deploy ~/besu-deploy
#   bash update_env.sh --deploy DIR --hiba ~/HiBA --template ~/hiba_dist/template
#
# 改的欄位（改之前會備份成 env.json.bak-anvil-<時間>）：
#   Blockchain.rpcURL / networkID / gasPrice=0
#   BlockchainTPMRegistry.contractAddress / privateKey
#   BlockchainFileProtect.contractAddress / privateKey
# 私鑰怎麼放：
#   Pi 的 env.json         BlockchainTPMRegistry.privateKey = 管理員私鑰（register_node.sh 用它登記設備）
#   節點範本 env.json      BlockchainTPMRegistry.privateKey = 檔案上鏈帳號（不是管理員：只能讀鏈、不能登記或撤銷）
#   兩者的 BlockchainFileProtect.privateKey = 檔案上鏈帳號
# 已安裝的節點不會自動改：各節點要改自己的 ~/HiBA/env.json（腳本最後會印出要改的值）。
set -uo pipefail

DEPLOY=""
HIBA="$HOME/HiBA"
TEMPLATE="$HOME/hiba_dist/template"

ok()   { echo "  ✔ $*"; }
warn() { echo "  ⚠ $*"; }
fail() { echo "  ✘ $*"; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --deploy) DEPLOY="$2"; shift 2 ;;
        --hiba) HIBA="$2"; shift 2 ;;
        --template) TEMPLATE="$2"; shift 2 ;;
        -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
        *) fail "不認得的選項: $1" ;;
    esac
done

[ -n "$DEPLOY" ] || fail "需要 --deploy（deploy_contracts.sh 產生的 besu-deploy 目錄）"
for f in deploy.json admin.key uploader.key; do
    [ -f "$DEPLOY/$f" ] || fail "找不到 $DEPLOY/$f"
done
[ -f "$HIBA/env.json" ] || fail "找不到 $HIBA/env.json"

STAMP="$(date +%Y%m%d-%H%M%S)"

patch() { # $1 = env.json, $2 = admin|node
    cp -p "$1" "$1.bak-anvil-$STAMP"
    python3 - "$1" "$DEPLOY" "$2" <<'EOF' || return 1
import json, os, sys
path, deploy, role = sys.argv[1:4]
d = json.load(open(os.path.join(deploy, "deploy.json")))
admin = open(os.path.join(deploy, "admin.key")).read().strip()
uploader = open(os.path.join(deploy, "uploader.key")).read().strip()
env = json.load(open(path))
chain = env.setdefault("Blockchain", {})
chain["rpcURL"] = d["rpcURL"]
chain["networkID"] = str(d["chainId"]) if isinstance(chain.get("networkID"), str) else d["chainId"]
chain["gasPrice"] = 0
reg = env.setdefault("BlockchainTPMRegistry", {})
reg["contractAddress"] = d["TPMDeviceRegistry"]
reg["privateKey"] = admin if role == "admin" else uploader
fp = env.setdefault("BlockchainFileProtect", {})
fp["contractAddress"] = d["FileProtectionContract"]
fp["privateKey"] = uploader
json.dump(env, open(path, "w"), indent=4, ensure_ascii=False)
EOF
    chmod 600 "$1"
}

patch "$HIBA/env.json" admin || fail "修改 $HIBA/env.json 失敗"
ok "$HIBA/env.json（管理員私鑰，備份 env.json.bak-anvil-$STAMP）"

if [ -f "$TEMPLATE/env.json" ]; then
    patch "$TEMPLATE/env.json" node || fail "修改 $TEMPLATE/env.json 失敗"
    ok "$TEMPLATE/env.json（節點範本，不含管理員私鑰）"
else
    warn "找不到 $TEMPLATE/env.json，新節點範本沒有更新"
fi

# register_node.sh 讀 Pi 的 env.json；確認它能用新的管理員帳號登記
python3 - "$DEPLOY/deploy.json" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))
print(f"""
接下來：
  1. 重啟 HiBA 套用新設定：sudo systemctl restart hiba.service
  2. 打開 http://<Pi>:8092/chain，Chain ID 應為 {d['chainId']}，不再出現 Anvil 提醒
  3. 已安裝的節點：在各節點上把 ~/HiBA/env.json 改成下列值後重啟（或重跑 install_node.sh 前先刪掉舊的 env.json）
       Blockchain.rpcURL                      {d['rpcURL']}
       Blockchain.networkID                   {d['chainId']}
       Blockchain.gasPrice                    0
       BlockchainTPMRegistry.contractAddress  {d['TPMDeviceRegistry']}
       BlockchainFileProtect.contractAddress  {d['FileProtectionContract']}
       兩個 privateKey                        檔案上鏈帳號（besu-deploy/uploader.key，不是 admin.key）
     （只換了檔案合約時，只要改 BlockchainFileProtect.contractAddress）
  4. 每台設備登記並綁定它自己的錢包（檔案上鏈改用 ~/HiBA/device_wallet.key，不再用共用帳號）：
       在節點上取得錢包位址：java -cp ~/HiBA/hiba-core-0.0.1.jar org.gslab.util.DeviceWallet ~/HiBA
       在 Pi 上逐台執行：    sudo bash ~/hiba_dist/register_node.sh <EK> <公鑰> <錢包位址>
     EK 與公鑰在各節點的 ~/HiBA/tpm_registration.txt；設備已登記過時只會補綁錢包
  5. 管理員私鑰 admin.key：確認 Pi 的 env.json 已寫入後，把 besu-deploy/admin.key 移到離線的地方保管
""")
EOF

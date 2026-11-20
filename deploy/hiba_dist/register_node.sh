#!/bin/bash
# 在 accounting server（Pi）上把節點的 TPM 註冊到 TPMDeviceRegistry。
# 通常由 install_node.sh 透過 ssh 自動呼叫；也可以手動執行：
#   sudo bash /home/user/hiba_dist/register_node.sh <EK指紋> <簽章公鑰base64> [設備錢包位址]
#
# 未註冊 → registerDevice；已註冊但公鑰不同 → updatePublicKey；已註冊且相同 → 不做事。
# 有給設備錢包位址（節點的 ~/HiBA/device_wallet.key；在節點上用
#   java -cp hiba-core-0.0.1.jar org.gslab.util.DeviceWallet ~/HiBA 取得位址）時，再到 FileProtectionContract
# 把錢包綁定到這個 EK（bindDeviceWallet）。綁定後這台才能把檔案登記上鏈，鏈上紀錄也會寫出它的 EK。
# 合約地址、RPC 讀自 accounting server 的 env.json（可用 ENV_JSON=路徑 覆蓋）；
# 管理員私鑰讀自 /home/user/.hiba_admin.key（可用 ADMIN_KEY_FILE=路徑 覆蓋）。
set -uo pipefail

ENV_JSON="${ENV_JSON:-/home/user/HiBA/env.json}"
EK="${1:-}"
PUB="${2:-}"
WALLET="${3:-}"

fail() { echo "  ✘ $*"; exit 1; }
ok()   { echo "  ✔ $*"; }

[[ "$EK" =~ ^[0-9A-F]{64}$ ]] || fail "EK 指紋格式不對（應為 64 個大寫十六進位字元）"
[[ "$PUB" =~ ^MIIB[A-Za-z0-9+/]+=*$ ]] || fail "公鑰格式不對（應為 MIIB 開頭的 base64）"
[ -z "$WALLET" ] || [[ "$WALLET" =~ ^0x[0-9a-fA-F]{40}$ ]] || fail "錢包位址格式不對（應為 0x 開頭的 40 個十六進位字元）"
[ -r "$ENV_JSON" ] || fail "讀不到 $ENV_JSON（請用 sudo 執行）"

CAST=""
for c in "$(command -v cast 2>/dev/null)" "$HOME/.foundry/bin/cast" /home/soc/.foundry/bin/cast /home/*/.foundry/bin/cast; do
    [ -n "$c" ] && [ -x "$c" ] && { CAST="$c"; break; }
done
[ -n "$CAST" ] || fail "找不到 cast（Foundry）。soc 帳號裝過：請用 sudo 執行，或安裝 Foundry"

read -r RPC CONTRACT ENV_KEY GAS_PRICE FILE_CONTRACT < <(python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
k = d["BlockchainTPMRegistry"].get("privateKey") or "-"
print(d["Blockchain"]["rpcURL"], d["BlockchainTPMRegistry"]["contractAddress"], k,
      d["Blockchain"].get("gasPrice", "-"), d.get("BlockchainFileProtect", {}).get("contractAddress") or "-")' "$ENV_JSON") \
    || fail "無法從 $ENV_JSON 讀取 Blockchain / BlockchainTPMRegistry 設定"

# 管理員私鑰：放在 ADMIN_KEY_FILE（預設 /home/user/.hiba_admin.key，chmod 600），不放在 HiBA 的 env.json。
# HiBA 執行時不需要管理權；找不到這個檔案時才退回 env.json 的 BlockchainTPMRegistry.privateKey（舊做法）。
ADMIN_KEY_FILE="${ADMIN_KEY_FILE:-/home/user/.hiba_admin.key}"
if [ -r "$ADMIN_KEY_FILE" ]; then
    KEY="$(tr -d ' \r\n' < "$ADMIN_KEY_FILE")"
elif [ "$ENV_KEY" != - ]; then
    KEY="$ENV_KEY"
    echo "  ⚠ 管理員私鑰還放在 $ENV_JSON：建議執行 sudo bash /home/user/hiba_dist/move_admin_key.sh 移出"
else
    fail "找不到管理員私鑰：請放在 $ADMIN_KEY_FILE（chmod 600），或用 ADMIN_KEY_FILE=路徑 指定"
fi
[[ "$KEY" == 0x* ]] || KEY="0x$KEY"
ADMIN_ADDR="$("$CAST" wallet address --private-key "$KEY" 2>/dev/null)" || fail "管理員私鑰格式不對"
IS_ADMIN="$("$CAST" call "$CONTRACT" "admins(address)(bool)" "$ADMIN_ADDR" --rpc-url "$RPC" 2>/dev/null)"
OWNER="$("$CAST" call "$CONTRACT" "owner()(address)" --rpc-url "$RPC" 2>/dev/null)"
[ "$IS_ADMIN" = true ] || [ "$(echo "$OWNER" | tr A-F a-f)" = "$(echo "$ADMIN_ADDR" | tr A-F a-f)" ] \
    || fail "$ADMIN_ADDR 不是 TPMDeviceRegistry 的管理員（或區塊鏈 $RPC 連不上）"
echo "  管理員 $ADMIN_ADDR"
# 零手續費的私有鏈（Besu，gasPrice=0）：送 gasPrice 0 的舊式交易，帳號不需要有餘額
FEE_OPTS=()
[ "$GAS_PRICE" = 0 ] && FEE_OPTS=(--legacy --gas-price 0)
[ "$FILE_CONTRACT" = - ] && FILE_CONTRACT=""

echo "  合約 $CONTRACT  RPC $RPC"
echo "  EK  $EK"

strip() { sed -e 's/^"//' -e 's/"$//'; }
REGISTERED="$("$CAST" call "$CONTRACT" "isDeviceRegistered(string)(bool)" "$EK" --rpc-url "$RPC")" \
    || fail "查詢鏈上狀態失敗（區塊鏈 $RPC 是否在執行？）"

if [ "$REGISTERED" = true ]; then
    ACTIVE="$("$CAST" call "$CONTRACT" "isDeviceActive(string)(bool)" "$EK" --rpc-url "$RPC")"
    if [ "$ACTIVE" != true ]; then
        fail "這個 EK 已註冊但被撤銷了。確定要恢復請執行：cast send $CONTRACT \"reactivateDevice(string)\" $EK --rpc-url $RPC --private-key <金鑰>"
    fi
    CUR="$("$CAST" call "$CONTRACT" "getPublicKey(string)(string)" "$EK" --rpc-url "$RPC" | strip)"
    if [ "$CUR" = "$PUB" ]; then
        ok "已註冊，公鑰相同，不需要變更"
    else
        echo "  已註冊但公鑰不同 → updatePublicKey"
        "$CAST" send "$CONTRACT" "updatePublicKey(string,string)" "$EK" "$PUB" \
            --rpc-url "$RPC" --private-key "$KEY" "${FEE_OPTS[@]}" >/dev/null || fail "updatePublicKey 交易失敗"
    fi
else
    echo "  尚未註冊 → registerDevice"
    "$CAST" send "$CONTRACT" "registerDevice(string,string)" "$EK" "$PUB" \
        --rpc-url "$RPC" --private-key "$KEY" "${FEE_OPTS[@]}" >/dev/null || fail "registerDevice 交易失敗"
fi

CUR="$("$CAST" call "$CONTRACT" "getPublicKey(string)(string)" "$EK" --rpc-url "$RPC" | strip)"
[ "$CUR" = "$PUB" ] || fail "交易送出了，但鏈上的公鑰和預期不同"
ok "鏈上公鑰已確認"

# ---- 設備錢包：綁定後這台才能把檔案登記上鏈 ----
if [ -z "$WALLET" ]; then
    echo "  （沒有給錢包位址：略過錢包綁定。這台要上鏈檔案，請加上第三個參數再執行一次）"
    exit 0
fi
[ -n "$FILE_CONTRACT" ] || fail "$ENV_JSON 沒有 BlockchainFileProtect.contractAddress"
echo "  錢包 $WALLET  檔案合約 $FILE_CONTRACT"
lower() { tr '[:upper:]' '[:lower:]'; }
ZERO=0x0000000000000000000000000000000000000000
REG_OF_FILE="$("$CAST" call "$FILE_CONTRACT" "deviceRegistry()(address)" --rpc-url "$RPC" 2>/dev/null)" \
    || fail "檔案合約沒有 deviceRegistry()：這是舊版 FileProtectionContract，請先用 deploy_contracts.sh --file-only 部署新版"
[ "$(echo "$REG_OF_FILE" | lower)" = "$(echo "$CONTRACT" | lower)" ] \
    || fail "檔案合約的 deviceRegistry 是 $REG_OF_FILE，不是 $CONTRACT（部署時沒有設定，或 env.json 的位址不一致）"

BOUND="$("$CAST" call "$FILE_CONTRACT" "getWalletOfDevice(string)(address)" "$EK" --rpc-url "$RPC")" || fail "查詢錢包綁定失敗"
if [ "$(echo "$BOUND" | lower)" = "$(echo "$WALLET" | lower)" ]; then
    ok "錢包已綁定到這台設備，不需要變更"
    exit 0
fi
OWNER_EK="$("$CAST" call "$FILE_CONTRACT" "getDeviceOfWallet(address)(string)" "$WALLET" --rpc-url "$RPC" | strip)"
[ -z "$OWNER_EK" ] || [ "$OWNER_EK" = "$EK" ] || fail "這個錢包已經綁定到另一台設備（EK $OWNER_EK），不能重複使用"
if [ "$BOUND" = "$ZERO" ]; then
    echo "  綁定錢包 → bindDeviceWallet"
else
    echo "  這台原本綁定 $BOUND，改綁新錢包 → bindDeviceWallet（舊錢包會失效）"
fi
"$CAST" send "$FILE_CONTRACT" "bindDeviceWallet(string,address)" "$EK" "$WALLET" \
    --rpc-url "$RPC" --private-key "$KEY" "${FEE_OPTS[@]}" >/dev/null || fail "bindDeviceWallet 交易失敗"
BOUND="$("$CAST" call "$FILE_CONTRACT" "getWalletOfDevice(string)(address)" "$EK" --rpc-url "$RPC")"
[ "$(echo "$BOUND" | lower)" = "$(echo "$WALLET" | lower)" ] || fail "交易送出了，但鏈上綁定的錢包是 $BOUND"
ok "錢包已綁定，這台可以把檔案登記上鏈"

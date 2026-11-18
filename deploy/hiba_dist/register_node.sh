#!/bin/bash
# 在 accounting server（Pi）上把節點的 TPM 註冊到 TPMDeviceRegistry。
# 通常由 install_node.sh 透過 ssh 自動呼叫；也可以手動執行：
#   sudo bash /home/user/hiba_dist/register_node.sh <EK指紋> <簽章公鑰base64>
#
# 未註冊 → registerDevice；已註冊但公鑰不同 → updatePublicKey；已註冊且相同 → 不做事。
# 合約地址、RPC、私鑰讀自 accounting server 的 env.json（可用 ENV_JSON=路徑 覆蓋）。
set -uo pipefail

ENV_JSON="${ENV_JSON:-/home/user/HiBA/env.json}"
EK="${1:-}"
PUB="${2:-}"

fail() { echo "  ✘ $*"; exit 1; }
ok()   { echo "  ✔ $*"; }

[[ "$EK" =~ ^[0-9A-F]{64}$ ]] || fail "EK 指紋格式不對（應為 64 個大寫十六進位字元）"
[[ "$PUB" =~ ^MIIB[A-Za-z0-9+/]+=*$ ]] || fail "公鑰格式不對（應為 MIIB 開頭的 base64）"
[ -r "$ENV_JSON" ] || fail "讀不到 $ENV_JSON（請用 sudo 執行）"

CAST=""
for c in "$(command -v cast 2>/dev/null)" "$HOME/.foundry/bin/cast" /home/soc/.foundry/bin/cast /home/*/.foundry/bin/cast; do
    [ -n "$c" ] && [ -x "$c" ] && { CAST="$c"; break; }
done
[ -n "$CAST" ] || fail "找不到 cast（Foundry）。soc 帳號裝過：請用 sudo 執行，或安裝 Foundry"

read -r RPC CONTRACT KEY GAS_PRICE < <(python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
k = d["BlockchainTPMRegistry"]["privateKey"]
print(d["Blockchain"]["rpcURL"], d["BlockchainTPMRegistry"]["contractAddress"], k if k.startswith("0x") else "0x" + k,
      d["Blockchain"].get("gasPrice", ""))' "$ENV_JSON") \
    || fail "無法從 $ENV_JSON 讀取 Blockchain / BlockchainTPMRegistry 設定"

# 零手續費的私有鏈（Besu，gasPrice=0）：送 gasPrice 0 的舊式交易，帳號不需要有餘額
FEE_OPTS=()
[ "$GAS_PRICE" = 0 ] && FEE_OPTS=(--legacy --gas-price 0)

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
        exit 0
    fi
    echo "  已註冊但公鑰不同 → updatePublicKey"
    "$CAST" send "$CONTRACT" "updatePublicKey(string,string)" "$EK" "$PUB" \
        --rpc-url "$RPC" --private-key "$KEY" "${FEE_OPTS[@]}" >/dev/null || fail "updatePublicKey 交易失敗"
else
    echo "  尚未註冊 → registerDevice"
    "$CAST" send "$CONTRACT" "registerDevice(string,string)" "$EK" "$PUB" \
        --rpc-url "$RPC" --private-key "$KEY" "${FEE_OPTS[@]}" >/dev/null || fail "registerDevice 交易失敗"
fi

CUR="$("$CAST" call "$CONTRACT" "getPublicKey(string)(string)" "$EK" --rpc-url "$RPC" | strip)"
[ "$CUR" = "$PUB" ] || fail "交易送出了，但鏈上的公鑰和預期不同"
ok "註冊完成，鏈上公鑰已確認"

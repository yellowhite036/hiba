#!/bin/bash
# 在有管理員私鑰的機器（Pi）上，一次把所有節點的設備錢包綁定到它們的 EK。
#
#   sudo bash /home/user/hiba_dist/bind_wallets.sh            列出待綁定的節點，確認後綁定
#   sudo bash /home/user/hiba_dist/bind_wallets.sh --dry-run  只列出，不送交易
#   sudo bash /home/user/hiba_dist/bind_wallets.sh --yes      不詢問，直接綁定
#   --from URL   錢包聲明來源（預設 http://127.0.0.1:8092/api/wallets；本機不是 acct 時會自動轉送）
#
# 每台節點會定期用 TPM 簽「我的錢包是 0x…」送給 accounting server（GET /api/wallets 列出）。
# 這個腳本不信任 accounting server：每一份聲明都用鏈上登記的該設備公鑰重新驗章，
# 驗章失敗、設備未註冊或已撤銷、錢包已屬於別台的都會跳過。
# 合約位址、RPC 讀自本機 env.json，管理員私鑰讀自 /home/user/.hiba_admin.key（和 register_node.sh 相同，
# 可用 ENV_JSON=路徑、ADMIN_KEY_FILE=路徑 覆蓋）。
set -uo pipefail

ENV_JSON="${ENV_JSON:-/home/user/HiBA/env.json}"
FROM="http://127.0.0.1:8092/api/wallets"
DRY=0
YES=0

fail() { echo "  ✘ $*"; exit 1; }
ok()   { echo "  ✔ $*"; }
skip() { echo "  – $*"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --from) FROM="$2"; shift 2 ;;
        --dry-run) DRY=1; shift ;;
        --yes|-y) YES=1; shift ;;
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
        *) fail "不認得的選項: $1" ;;
    esac
done

[ -r "$ENV_JSON" ] || fail "讀不到 $ENV_JSON（請用 sudo 執行）"
command -v openssl >/dev/null || fail "沒有 openssl（sudo apt install openssl）"
command -v curl >/dev/null || fail "沒有 curl"

CAST=""
for c in "$(command -v cast 2>/dev/null)" "$HOME/.foundry/bin/cast" /home/soc/.foundry/bin/cast \
    /home/soc/.foundry/versions/foundry-rs/foundry/*/cast /home/*/.foundry/bin/cast; do
    [ -n "$c" ] && [ -x "$c" ] && { CAST="$c"; break; }
done
[ -n "$CAST" ] || fail "找不到 cast（Foundry）"

read -r RPC REGISTRY ENV_KEY GAS_PRICE FILE_CONTRACT < <(python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
k = d["BlockchainTPMRegistry"].get("privateKey") or "-"
print(d["Blockchain"]["rpcURL"], d["BlockchainTPMRegistry"]["contractAddress"], k,
      d["Blockchain"].get("gasPrice", "-"), d.get("BlockchainFileProtect", {}).get("contractAddress") or "-")' "$ENV_JSON") \
    || fail "無法從 $ENV_JSON 讀取區塊鏈設定"
[ "$FILE_CONTRACT" != - ] || fail "$ENV_JSON 沒有 BlockchainFileProtect.contractAddress"
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
IS_ADMIN="$("$CAST" call "$REGISTRY" "admins(address)(bool)" "$ADMIN_ADDR" --rpc-url "$RPC" 2>/dev/null)"
OWNER="$("$CAST" call "$REGISTRY" "owner()(address)" --rpc-url "$RPC" 2>/dev/null)"
[ "$IS_ADMIN" = true ] || [ "$(echo "$OWNER" | tr A-F a-f)" = "$(echo "$ADMIN_ADDR" | tr A-F a-f)" ] \
    || fail "$ADMIN_ADDR 不是 TPMDeviceRegistry 的管理員（或區塊鏈 $RPC 連不上）"
echo "  管理員 $ADMIN_ADDR"
FEE_OPTS=()
[ "$GAS_PRICE" = 0 ] && FEE_OPTS=(--legacy --gas-price 0)

lower() { tr '[:upper:]' '[:lower:]'; }
strip() { sed -e 's/^"//' -e 's/"$//'; }
ZERO=0x0000000000000000000000000000000000000000

echo "  設備登記合約 $REGISTRY"
echo "  檔案合約     $FILE_CONTRACT"
REG_OF_FILE="$("$CAST" call "$FILE_CONTRACT" "deviceRegistry()(address)" --rpc-url "$RPC" 2>/dev/null)" \
    || fail "檔案合約沒有 deviceRegistry()：這是舊版 FileProtectionContract（或連不到 $RPC）"
[ "$(echo "$REG_OF_FILE" | lower)" = "$(echo "$REGISTRY" | lower)" ] \
    || fail "檔案合約的 deviceRegistry 是 $REG_OF_FILE，和 env.json 的 $REGISTRY 不同"

JSON="$(curl -s -m 15 "$FROM")" || fail "讀不到 $FROM（本機 HiBA 是否在執行？）"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
python3 -c '
import json, sys
d = json.loads(sys.argv[1])
if "wallets" not in d:
    sys.exit("回應沒有 wallets：" + str(d.get("error", d))[:200])
for w in d["wallets"]:
    name = (w.get("name") or "?").replace("\t", " ").replace(" ", "_") or "?"
    print("\t".join([name, w["ek"], w["wallet"], str(w["timestamp"]), w["signature"]]))' "$JSON" > "$TMP/list" \
    || fail "無法解析 $FROM 的回應"
TOTAL="$(wc -l < "$TMP/list")"
[ "$TOTAL" -gt 0 ] || { echo "  還沒有任何節點送來錢包聲明（節點更新到新版 jar、啟動 30 秒後才會送出）"; exit 0; }
echo "  收到 $TOTAL 份錢包聲明，逐一檢查："
echo

PENDING=()
i=0
while IFS=$'\t' read -r NAME EK WALLET TS SIG; do
    i=$((i + 1))
    WALLET="$(echo "$WALLET" | lower)"
    label="$NAME（EK ${EK:0:12}…）"
    [[ "$EK" =~ ^[0-9A-F]{64}$ && "$WALLET" =~ ^0x[0-9a-f]{40}$ && "$TS" =~ ^[0-9]+$ ]] \
        || { skip "$label：格式不對，跳過"; continue; }

    ACTIVE="$("$CAST" call "$REGISTRY" "isDeviceActive(string)(bool)" "$EK" --rpc-url "$RPC" 2>/dev/null)"
    [ "$ACTIVE" = true ] || { skip "$label：EK 沒有註冊或已撤銷，跳過"; continue; }

    # 用鏈上登記的公鑰驗 TPM 簽章（RSASSA-PKCS1-v1.5 / SHA-256）
    PUB="$("$CAST" call "$REGISTRY" "getPublicKey(string)(string)" "$EK" --rpc-url "$RPC" | strip)"
    python3 -c '
import sys, textwrap
print("-----BEGIN PUBLIC KEY-----\n" + "\n".join(textwrap.wrap(sys.argv[1], 64)) + "\n-----END PUBLIC KEY-----")' \
        "$PUB" > "$TMP/pub.pem"
    printf '%s' "HiBA-device-wallet:v1:$EK:$WALLET:$TS" > "$TMP/msg"
    printf '%s' "$SIG" | base64 -d > "$TMP/sig" 2>/dev/null
    if ! openssl dgst -sha256 -verify "$TMP/pub.pem" -signature "$TMP/sig" "$TMP/msg" >/dev/null 2>&1; then
        skip "$label：TPM 簽章驗證失敗（不是這台設備簽的），跳過"
        continue
    fi

    BOUND="$("$CAST" call "$FILE_CONTRACT" "getWalletOfDevice(string)(address)" "$EK" --rpc-url "$RPC" | lower)"
    if [ "$BOUND" = "$WALLET" ]; then
        ok "$label：錢包 $WALLET 已綁定"
        continue
    fi
    OWNER="$("$CAST" call "$FILE_CONTRACT" "getDeviceOfWallet(address)(string)" "$WALLET" --rpc-url "$RPC" | strip)"
    if [ -n "$OWNER" ] && [ "$OWNER" != "$EK" ]; then
        skip "$label：錢包 $WALLET 已屬於另一台設備（EK ${OWNER:0:12}…），跳過"
        continue
    fi
    if [ "$BOUND" = "$ZERO" ]; then
        echo "  ● $label：待綁定 $WALLET"
    else
        echo "  ● $label：待改綁 $BOUND → $WALLET（舊錢包會失效）"
    fi
    PENDING+=("$NAME	$EK	$WALLET")
done < "$TMP/list"

echo
if [ "${#PENDING[@]}" = 0 ]; then
    ok "沒有需要綁定的節點"
    exit 0
fi
if [ "$DRY" = 1 ]; then
    echo "  共 ${#PENDING[@]} 台待綁定（--dry-run，沒有送出交易）"
    exit 0
fi
if [ "$YES" != 1 ]; then
    read -r -p "  確定用管理員帳號綁定以上 ${#PENDING[@]} 台？[y/N] " ans
    [[ "$ans" =~ ^[Yy]$ ]] || { echo "  已取消"; exit 0; }
fi

FAILED=0
for row in "${PENDING[@]}"; do
    IFS=$'\t' read -r NAME EK WALLET <<< "$row"
    if "$CAST" send "$FILE_CONTRACT" "bindDeviceWallet(string,address)" "$EK" "$WALLET" \
        --rpc-url "$RPC" --private-key "$KEY" "${FEE_OPTS[@]}" >/dev/null 2>"$TMP/err"; then
        BOUND="$("$CAST" call "$FILE_CONTRACT" "getWalletOfDevice(string)(address)" "$EK" --rpc-url "$RPC" | lower)"
        if [ "$BOUND" = "$WALLET" ]; then
            ok "$NAME 已綁定 $WALLET"
            continue
        fi
    fi
    echo "  ✘ $NAME 綁定失敗：$(tail -n 1 "$TMP/err" 2>/dev/null)"
    FAILED=$((FAILED + 1))
done
[ "$FAILED" = 0 ] && ok "完成" || fail "$FAILED 台綁定失敗"

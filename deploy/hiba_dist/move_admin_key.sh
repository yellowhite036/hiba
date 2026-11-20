#!/bin/bash
# 在 accounting server（Pi）上把 TPMDeviceRegistry 的管理員私鑰移出 HiBA 的 env.json。
#
#   sudo bash /home/user/hiba_dist/move_admin_key.sh            移出並重啟 HiBA
#   sudo bash /home/user/hiba_dist/move_admin_key.sh --check    只檢查，不改任何東西
#
# 做的事：
#   1. 確認執行中的 jar 支援「privateKey 留空＝唯讀模式」（舊版留空會無法啟動）
#   2. 確認 env.json 的 BlockchainTPMRegistry.privateKey 真的是鏈上管理員
#   3. 寫到 ADMIN_KEY_FILE（預設 /home/user/.hiba_admin.key，root 擁有、chmod 600）
#      register_node.sh、bind_wallets.sh 之後都從這個檔案讀
#   4. env.json 的 BlockchainTPMRegistry.privateKey 改成 ""，重啟 HiBA，確認以唯讀模式啟動
# 之後 HiBA 本身不再持有管理權。更進一步：把 .hiba_admin.key 也移到離線的地方，
# 要登記新節點時再放回來（或用 ADMIN_KEY_FILE=路徑 指定 USB 上的檔案）。
set -uo pipefail

HIBA="/home/user/HiBA"
ENV_JSON="$HIBA/env.json"
JAR="$HIBA/hiba-core-0.0.1.jar"
ADMIN_KEY_FILE="${ADMIN_KEY_FILE:-/home/user/.hiba_admin.key}"
UNIT="hiba.service"
CHECK=0

ok()   { echo "  ✔ $*"; }
warn() { echo "  ⚠ $*"; }
fail() { echo "  ✘ $*"; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --check) CHECK=1; shift ;;
        --hiba) HIBA="$2"; ENV_JSON="$2/env.json"; JAR="$2/hiba-core-0.0.1.jar"; shift 2 ;;
        -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
        *) fail "不認得的選項: $1" ;;
    esac
done

[ "$(id -u)" = 0 ] || fail "請用 sudo 執行"
[ -r "$ENV_JSON" ] || fail "讀不到 $ENV_JSON"

CAST=""
for c in "$(command -v cast 2>/dev/null)" /home/user/.foundry/bin/cast /home/soc/.foundry/bin/cast \
    /home/soc/.foundry/versions/foundry-rs/foundry/*/cast /home/*/.foundry/bin/cast; do
    [ -n "$c" ] && [ -x "$c" ] && { CAST="$c"; break; }
done
[ -n "$CAST" ] || fail "找不到 cast（Foundry）"

# 1. jar 是否支援唯讀模式
python3 - "$JAR" <<'EOF' || fail "$JAR 還是舊版（privateKey 留空會無法啟動）：先用 deploy_acct.sh 部署新版再執行"
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
data = z.read("org/gslab/tpm/blockchain/Web3BlockchainService.class")
sys.exit(0 if b"warnIfAdminKeyAsync" in data else 1)
EOF
ok "執行中的 jar 支援唯讀模式"

read -r RPC REGISTRY ENV_KEY < <(python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
r = d["BlockchainTPMRegistry"]
print(d["Blockchain"]["rpcURL"], r["contractAddress"], r.get("privateKey") or "-")' "$ENV_JSON") \
    || fail "無法從 $ENV_JSON 讀取區塊鏈設定"

if [ "$ENV_KEY" = - ]; then
    ok "$ENV_JSON 的 BlockchainTPMRegistry.privateKey 已經是空的（唯讀模式）"
    [ -r "$ADMIN_KEY_FILE" ] && ok "管理員私鑰在 $ADMIN_KEY_FILE" || warn "$ADMIN_KEY_FILE 不存在：要登記新節點時用 ADMIN_KEY_FILE=路徑 指定管理員私鑰"
    exit 0
fi

# 2. env.json 裡的這把是不是管理員
[[ "$ENV_KEY" == 0x* ]] || ENV_KEY="0x$ENV_KEY"
ADDR="$("$CAST" wallet address --private-key "$ENV_KEY" 2>/dev/null)" || fail "env.json 的 privateKey 格式不對"
IS_ADMIN="$("$CAST" call "$REGISTRY" "admins(address)(bool)" "$ADDR" --rpc-url "$RPC")" || fail "查詢鏈上管理員失敗（區塊鏈 $RPC 連不上？）"
OWNER="$("$CAST" call "$REGISTRY" "owner()(address)" --rpc-url "$RPC")" || fail "查詢 owner 失敗"
lower() { tr '[:upper:]' '[:lower:]'; }
if [ "$IS_ADMIN" != true ] && [ "$(echo "$OWNER" | lower)" != "$(echo "$ADDR" | lower)" ]; then
    warn "env.json 的帳號 $ADDR 不是管理員，不需要移（要改成唯讀模式可以手動把 privateKey 改成 \"\"）"
    exit 0
fi
ok "env.json 的帳號 $ADDR 是鏈上管理員"

if [ -r "$ADMIN_KEY_FILE" ]; then
    CUR="$(tr -d ' \r\n' < "$ADMIN_KEY_FILE")"
    [[ "$CUR" == 0x* ]] || CUR="0x$CUR"
    [ "$CUR" = "$ENV_KEY" ] || fail "$ADMIN_KEY_FILE 已存在但內容是另一把私鑰，請先確認要用哪一把"
fi

if [ "$CHECK" = 1 ]; then
    echo "  （--check：沒有改任何東西。拿掉 --check 再執行一次就會移出）"
    exit 0
fi

# 3. 寫到獨立檔案
umask 077
printf '%s\n' "$ENV_KEY" > "$ADMIN_KEY_FILE" && chown root:root "$ADMIN_KEY_FILE" && chmod 600 "$ADMIN_KEY_FILE" \
    || fail "寫入 $ADMIN_KEY_FILE 失敗"
ok "管理員私鑰已寫到 $ADMIN_KEY_FILE（只有 root 能讀）"

# 4. env.json 改成唯讀模式（就地改寫，保留檔案擁有者）
python3 - "$ENV_JSON" <<'EOF' || fail "修改 $ENV_JSON 失敗（管理員私鑰已在 $ADMIN_KEY_FILE，env.json 沒動）"
import json, sys
p = sys.argv[1]
d = json.load(open(p))
d["BlockchainTPMRegistry"]["privateKey"] = ""
with open(p, "r+") as f:
    f.seek(0)
    json.dump(d, f, indent=4, ensure_ascii=False)
    f.write("\n")
    f.truncate()
EOF
ok "$ENV_JSON 的 BlockchainTPMRegistry.privateKey 已改成 \"\""

systemctl restart "$UNIT" && ok "已重啟 $UNIT，等它啟動（約 40 秒）"
LOG="$HIBA/logs/hiba.log"
for i in $(seq 120); do
    if grep -aq "read-only mode (no privateKey configured)" <(tail -n 2000 "$LOG" 2>/dev/null); then
        ok "HiBA 以唯讀模式啟動（第 $i 秒）"
        break
    fi
    [ "$i" = 120 ] && warn "120 秒內沒在 $LOG 看到唯讀模式的紀錄，請檢查：systemctl status $UNIT"
    sleep 1
done
echo
echo "  完成。之後："
echo "   - register_node.sh / bind_wallets.sh 會自動讀 $ADMIN_KEY_FILE"
echo "   - 想再更安全：把 $ADMIN_KEY_FILE 備份到離線的地方後從 Pi 刪除，要登記新節點時再放回"
echo "   - 要復原：把 $ADMIN_KEY_FILE 的內容放回 env.json 的 BlockchainTPMRegistry.privateKey 再重啟"

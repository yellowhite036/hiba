#!/bin/bash
# 把 HiBA 的兩份合約部署到 Besu 私有鏈，並產生正式使用的帳號。
# 在有 Foundry（forge、cast）的 x86 機器上執行（例如 192.168.1.60）；Pi 上編譯 Solidity 不一定順利。
#
#   bash deploy_contracts.sh --rpc http://192.168.1.62:8545
#   bash deploy_contracts.sh --rpc URL --contracts DIR   合約原始碼目錄（預設 hiba-core/contract）
#   bash deploy_contracts.sh --rpc URL --admin-key FILE  使用既有的管理員私鑰檔（預設產生新的）
#   bash deploy_contracts.sh --rpc URL --file-only       只重新部署 FileProtectionContract，沿用 besu-deploy/deploy.json
#                                                        裡的 TPMDeviceRegistry（已登記的設備不用重來）
#
# FileProtectionContract 部署後會呼叫 setDeviceRegistry(TPMDeviceRegistry)：之後只有「錢包已由管理員
# 綁定到設備 EK」的節點能登記檔案（register_node.sh 第三個參數），鏈上紀錄會寫出是哪台設備。
#
# 產生（都在目前目錄的 besu-deploy/，請移到安全的地方保管）：
#   admin.key      管理員私鑰：只放在負責登記設備的機器（Pi）上，另外離線備份
#   uploader.key   檔案上鏈用的一般帳號私鑰：給各節點（不是管理員，外流也不能登記或撤銷設備）
#   deploy.json    RPC、Chain ID、兩份合約位址、兩個帳號的地址（不含私鑰）
set -uo pipefail

RPC=""
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONTRACTS="$SCRIPT_DIR/../../hiba-core/contract"
ADMIN_KEY_FILE=""
FILE_ONLY=0
OUT="$PWD/besu-deploy"

ok()   { echo "  ✔ $*"; }
warn() { echo "  ⚠ $*"; }
fail() { echo "  ✘ $*"; exit 1; }
step() { echo; echo "== $* =="; }

while [ $# -gt 0 ]; do
    case "$1" in
        --rpc) RPC="$2"; shift 2 ;;
        --contracts) CONTRACTS="$2"; shift 2 ;;
        --admin-key) ADMIN_KEY_FILE="$2"; shift 2 ;;
        --file-only) FILE_ONLY=1; shift ;;
        -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
        *) fail "不認得的選項: $1" ;;
    esac
done

[ -n "$RPC" ] || fail "需要 --rpc，例如 --rpc http://192.168.1.62:8545"
command -v forge >/dev/null || fail "沒有 forge（安裝 Foundry：curl -L https://foundry.paradigm.xyz | bash && foundryup）"
command -v cast >/dev/null || fail "沒有 cast"
[ -f "$CONTRACTS/TPMDeviceRegistry.sol" ] || fail "找不到 $CONTRACTS/TPMDeviceRegistry.sol（用 --contracts 指定）"
[ -f "$CONTRACTS/FileMetadataContract.sol" ] || fail "找不到 $CONTRACTS/FileMetadataContract.sol"

# ---------------------------------------------------------------------------
step "1. 確認鏈"
CHAIN_ID="$(cast chain-id --rpc-url "$RPC" 2>/dev/null)" || fail "連不到 $RPC"
[ "$CHAIN_ID" = 31337 ] && fail "Chain ID 是 31337（Anvil 測試鏈），不是 Besu"
ok "Chain ID $CHAIN_ID，最新區塊 $(cast block-number --rpc-url "$RPC")"

# ---------------------------------------------------------------------------
step "2. 帳號"
mkdir -p "$OUT" && chmod 700 "$OUT"
new_wallet() { # $1 = 輸出檔。私鑰只寫進檔案，絕不印到畫面（cast 新版把結果印在 stderr，所以兩個都要接住）
    local out key
    out="$(cast wallet new --json 2>&1)" || fail "cast wallet new 失敗"
    key="$(printf '%s' "$out" | python3 -c '
import json, re, sys
s = sys.stdin.read()
try:
    d = json.loads(s[s.index("["):] if "[" in s else s)
    d = d[0] if isinstance(d, list) else d
    print(d.get("private_key") or d.get("privateKey") or "")
except Exception:
    m = re.search(r"0x[0-9a-fA-F]{64}", s)
    print(m.group(0) if m else "")
')"
    [[ "$key" =~ ^0x[0-9a-fA-F]{64}$ ]] || fail "無法從 cast wallet new 的輸出取得私鑰"
    (umask 077 && printf '%s\n' "$key" > "$1")
    chmod 600 "$1"
}
if [ -n "$ADMIN_KEY_FILE" ]; then
    cp "$ADMIN_KEY_FILE" "$OUT/admin.key" && chmod 600 "$OUT/admin.key"
    ok "沿用管理員私鑰 $ADMIN_KEY_FILE"
elif [ -f "$OUT/admin.key" ]; then
    ok "沿用 $OUT/admin.key"
else
    new_wallet "$OUT/admin.key"
    ok "已產生新的管理員私鑰 $OUT/admin.key"
fi
[ -f "$OUT/uploader.key" ] || { new_wallet "$OUT/uploader.key"; ok "已產生檔案上鏈帳號 $OUT/uploader.key"; }
ADMIN_KEY="$(cat "$OUT/admin.key")"
ADMIN_ADDR="$(cast wallet address --private-key "$ADMIN_KEY")" || fail "admin.key 格式不對"
UPLOADER_ADDR="$(cast wallet address --private-key "$(cat "$OUT/uploader.key")")" || fail "uploader.key 格式不對"
case "${ADMIN_ADDR,,}" in
    0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266|0x70997970c51812dc3a010c7d01b50e0d17dc79c8|0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc)
        fail "這是 Anvil 的公開測試帳號，不能當正式的管理員" ;;
esac
ok "管理員 $ADMIN_ADDR"
ok "檔案上鏈帳號 $UPLOADER_ADDR"

# ---------------------------------------------------------------------------
step "3. 編譯並部署合約"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/src"
cp "$CONTRACTS/TPMDeviceRegistry.sol" "$CONTRACTS/FileMetadataContract.sol" "$WORK/src/"
cat > "$WORK/foundry.toml" <<'EOF'
[profile.default]
src = "src"
out = "out"
optimizer = true
optimizer_runs = 200
evm_version = "paris"
EOF
BROADCAST=""
forge create --help 2>/dev/null | grep -q -- "--broadcast" && BROADCAST="--broadcast"

deploy() { # $1 = 檔名:合約名
    local log addr
    log="$(cd "$WORK" && forge create "src/$1" --rpc-url "$RPC" --private-key "$ADMIN_KEY" \
        --legacy --gas-price 0 $BROADCAST 2>&1)"
    addr="$(echo "$log" | awk '/Deployed to:/ {print $NF}')"
    if [ -z "$addr" ]; then
        echo "$log" | tail -n 20 | sed 's/^/    /' >&2
        return 1
    fi
    echo "$addr"
}
if [ "$FILE_ONLY" = 1 ]; then
    [ -f "$OUT/deploy.json" ] || fail "--file-only 需要 $OUT/deploy.json（上一次完整部署的結果）"
    REGISTRY="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["TPMDeviceRegistry"])' "$OUT/deploy.json")" \
        || fail "讀不到 $OUT/deploy.json 的 TPMDeviceRegistry"
    [ "$(cast code "$REGISTRY" --rpc-url "$RPC" 2>/dev/null)" != 0x ] || fail "鏈上 $REGISTRY 沒有合約（鏈被重置過？請改做完整部署）"
    ok "沿用 TPMDeviceRegistry  $REGISTRY"
else
    REGISTRY="$(deploy TPMDeviceRegistry.sol:TPMDeviceRegistry)" || fail "部署 TPMDeviceRegistry 失敗（訊息如上）"
    ok "TPMDeviceRegistry       $REGISTRY"
fi
FILE_CONTRACT="$(deploy FileMetadataContract.sol:FileProtectionContract)" || fail "部署 FileProtectionContract 失敗（訊息如上）"
ok "FileProtectionContract  $FILE_CONTRACT"

# 確認管理員權限（部署者就是 owner 兼 admin）
IS_ADMIN="$(cast call "$REGISTRY" "admins(address)(bool)" "$ADMIN_ADDR" --rpc-url "$RPC" 2>/dev/null)"
[ "$IS_ADMIN" = true ] && ok "管理員權限確認" || warn "admins($ADMIN_ADDR) 回傳 $IS_ADMIN，請確認合約的 owner/admin 設定"

# 檔案合約改成只接受已綁定設備的錢包
cast send "$FILE_CONTRACT" "setDeviceRegistry(address)" "$REGISTRY" --rpc-url "$RPC" --private-key "$ADMIN_KEY" \
    --legacy --gas-price 0 >/dev/null || fail "setDeviceRegistry 交易失敗"
SET_REG="$(cast call "$FILE_CONTRACT" "deviceRegistry()(address)" --rpc-url "$RPC" 2>/dev/null)"
[ "${SET_REG,,}" = "${REGISTRY,,}" ] || fail "檔案合約的 deviceRegistry 是 $SET_REG，不是 $REGISTRY"
ok "檔案合約只接受已綁定到設備 EK 的錢包"

# ---------------------------------------------------------------------------
python3 - "$OUT/deploy.json" "$RPC" "$CHAIN_ID" "$REGISTRY" "$FILE_CONTRACT" "$ADMIN_ADDR" "$UPLOADER_ADDR" <<'EOF'
import json, sys, time
p, rpc, chain, reg, file_, admin, up = sys.argv[1:8]
json.dump({"rpcURL": rpc, "chainId": int(chain), "TPMDeviceRegistry": reg, "FileProtectionContract": file_,
           "adminAddress": admin, "uploaderAddress": up, "deployedAt": time.strftime("%Y-%m-%d %H:%M:%S")},
          open(p, "w"), indent=2)
EOF
ok "$OUT/deploy.json"

echo
if [ "$FILE_ONLY" = 1 ]; then
    echo "下一步（在 Pi 上）："
    echo "  1. bash ~/build/hiba-dev/hiba/deploy/besu/update_env.sh --deploy $OUT   （更新 Pi 與節點範本的檔案合約位址）"
    echo "     sudo systemctl restart hiba.service"
    echo "  2. 各節點會由自動更新改成新的檔案合約位址（約 2～4 分鐘），不用手動改"
    echo "  3. 等節點送出錢包聲明後，一次綁定：sudo bash /home/user/hiba_dist/bind_wallets.sh"
    exit 0
fi
echo "下一步：把 $OUT 整個複製到 Pi，再在 Pi 上更新設定："
echo "  scp -r $OUT user@<Pi的IP>:~/besu-deploy"
echo "  bash ~/build/hiba-dev/hiba/deploy/besu/update_env.sh --deploy ~/besu-deploy"
echo "admin.key 是正式的管理員私鑰：複製到 Pi 後，這台機器上的副本請移到離線的地方備份，不要留在這裡。"

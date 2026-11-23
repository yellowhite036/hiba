#!/bin/bash
# 部署 ValidatorRegistry（授權簽章的 Validator 名單，M1）到 Besu。
# 跟 deploy_contracts.sh 一樣在有 Foundry 的 x86 機器上執行（例如 192.168.1.60）。
#
#   bash deploy_validator_registry.sh --rpc http://192.168.1.62:8545 --registry 0x08AC...
#   bash deploy_validator_registry.sh --rpc URL --registry ADDR --admin-key FILE --contracts DIR
#
#   --registry   既有的 TPMDeviceRegistry 位址（Validator 必須是裡面 active 的設備）
#   --admin-key  部署用私鑰，部署者就是 ValidatorRegistry 的 owner（預設 ./besu-deploy/admin.key）
#                請用跟 TPMDeviceRegistry 同一把管理員私鑰，Pi 上的 validators.sh 才能用 .hiba_admin.key 管理
#   --contracts  合約原始碼目錄（預設 hiba-core/contract）
# 成功後把位址寫進 ./besu-deploy/deploy.json 的 "ValidatorRegistry"（檔案存在時），並印出下一步。
set -uo pipefail

RPC=""
REGISTRY=""
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONTRACTS="$SCRIPT_DIR/../../hiba-core/contract"
OUT="$PWD/besu-deploy"
ADMIN_KEY_FILE="$OUT/admin.key"

ok()   { echo "  ✔ $*"; }
warn() { echo "  ⚠ $*"; }
fail() { echo "  ✘ $*"; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --rpc) RPC="$2"; shift 2 ;;
        --registry) REGISTRY="$2"; shift 2 ;;
        --admin-key) ADMIN_KEY_FILE="$2"; shift 2 ;;
        --contracts) CONTRACTS="$2"; shift 2 ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *) fail "不認得的選項: $1" ;;
    esac
done

[ -n "$RPC" ] || fail "需要 --rpc，例如 --rpc http://192.168.1.62:8545"
[[ "$REGISTRY" =~ ^0x[0-9a-fA-F]{40}$ ]] || fail "需要 --registry <TPMDeviceRegistry 位址>"
command -v forge >/dev/null || fail "沒有 forge"
command -v cast >/dev/null || fail "沒有 cast"
[ -f "$CONTRACTS/ValidatorRegistry.sol" ] || fail "找不到 $CONTRACTS/ValidatorRegistry.sol（用 --contracts 指定）"
[ -r "$ADMIN_KEY_FILE" ] || fail "讀不到私鑰檔 $ADMIN_KEY_FILE（用 --admin-key 指定）"

CHAIN_ID="$(cast chain-id --rpc-url "$RPC" 2>/dev/null)" || fail "連不到 $RPC"
[ "$CHAIN_ID" = 31337 ] && fail "Chain ID 是 31337（Anvil 測試鏈），不是 Besu"
ok "Chain ID $CHAIN_ID"

[ "$(cast code "$REGISTRY" --rpc-url "$RPC")" != 0x ] || fail "$REGISTRY 上沒有合約"
cast call "$REGISTRY" "getDeviceCount()(uint256)" --rpc-url "$RPC" >/dev/null 2>&1 || fail "$REGISTRY 不像 TPMDeviceRegistry"
ok "TPMDeviceRegistry $REGISTRY"

KEY="$(tr -d ' \r\n' < "$ADMIN_KEY_FILE")"
[[ "$KEY" == 0x* ]] || KEY="0x$KEY"
ADDR="$(cast wallet address --private-key "$KEY" 2>/dev/null)" || fail "私鑰格式不對"
IS_ADMIN="$(cast call "$REGISTRY" "admins(address)(bool)" "$ADDR" --rpc-url "$RPC")"
[ "$IS_ADMIN" = true ] && ok "部署者 $ADDR（也是 TPMDeviceRegistry 管理員）" \
    || warn "部署者 $ADDR 不是 TPMDeviceRegistry 管理員：Pi 上的 validators.sh 要用 ADMIN_KEY_FILE=這把私鑰"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/src"
cp "$CONTRACTS/ValidatorRegistry.sol" "$WORK/src/"
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
LOG="$(cd "$WORK" && forge create src/ValidatorRegistry.sol:ValidatorRegistry --rpc-url "$RPC" --private-key "$KEY" \
    --legacy --gas-price 0 $BROADCAST --constructor-args "$REGISTRY" 2>&1)"
VR="$(echo "$LOG" | awk '/Deployed to:/ {print $NF}')"
[ -n "$VR" ] || { echo "$LOG" | tail -n 20 | sed 's/^/    /'; fail "部署失敗（訊息如上）"; }
ok "ValidatorRegistry $VR"
ok "setVersion $(cast call "$VR" "setVersion()(uint256)" --rpc-url "$RPC")，owner $(cast call "$VR" "owner()(address)" --rpc-url "$RPC")"

if [ -f "$OUT/deploy.json" ]; then
    python3 - "$OUT/deploy.json" "$VR" <<'EOF' && ok "已寫入 $OUT/deploy.json"
import json, sys
p, vr = sys.argv[1:3]
d = json.load(open(p))
d["ValidatorRegistry"] = vr
json.dump(d, open(p, "w"), indent=2)
EOF
fi

cat <<EOF

接下來（在 Pi 上）：
  1. 各節點 ~/HiBA/env.json 加上（Pi 的 ~/HiBA/env.json 和 ~/hiba_dist/template/env.json 也加，方便之後的新節點）：
       "Authorization": { "mode": "log", "validatorRegistry": "$VR" }
     或在 Pi 上：sudo bash ~/hiba_dist/validators.sh set-env $VR
  2. 加入 Validator：sudo bash ~/hiba_dist/validators.sh add <EK> <網域> <IP>:8095
  3. 查看名單：      bash ~/hiba_dist/validators.sh list
EOF

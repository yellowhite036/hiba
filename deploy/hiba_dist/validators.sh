#!/bin/bash
# 管理 ValidatorRegistry（有授權簽章資格的設備名單，M1）。在 Pi 上執行。
#
#   sudo bash /home/user/hiba_dist/validators.sh list
#   sudo bash /home/user/hiba_dist/validators.sh add <EK> <網域ID> <IP:埠>     例：add 3f2a… domainA 192.168.1.173:8095
#   sudo bash /home/user/hiba_dist/validators.sh suspend|resume|revoke <EK>   revoke 之後不能恢復
#   sudo bash /home/user/hiba_dist/validators.sh endpoint <EK> <IP:埠>
#   sudo bash /home/user/hiba_dist/validators.sh threshold <k>                0 = 自動 ceil(2n/3)
#   sudo bash /home/user/hiba_dist/validators.sh set-env <ValidatorRegistry 位址> [env.json ...]
#        把 Authorization.validatorRegistry 寫進 env.json（預設 Pi 的 ~/HiBA/env.json 和
#        ~/hiba_dist/template/env.json），沒有 mode 時設成 "log"。改完要重啟 HiBA
#
# EK 在各節點的 ~/HiBA/tpm_registration.txt。ValidatorRegistry 位址讀自 env.json 的
# Authorization.validatorRegistry；管理員私鑰讀自 /home/user/.hiba_admin.key（可用 ADMIN_KEY_FILE=路徑 覆蓋）。
# 名單／門檻每變動一次，setVersion 加 1，舊版本簽的授權憑證會失效。
set -uo pipefail

ENV_JSON="${ENV_JSON:-/home/user/HiBA/env.json}"
ADMIN_KEY_FILE="${ADMIN_KEY_FILE:-/home/user/.hiba_admin.key}"

ok()   { echo "  ✔ $*"; }
warn() { echo "  ⚠ $*"; }
fail() { echo "  ✘ $*"; exit 1; }

CMD="${1:-}"
[ -n "$CMD" ] || { sed -n '2,16p' "$0"; exit 1; }
shift
case "$CMD" in -h|--help) sed -n '2,16p' "$0"; exit 0 ;; esac

if [ "$CMD" = set-env ]; then
    VR="${1:-}"
    [[ "$VR" =~ ^0x[0-9a-fA-F]{40}$ ]] || fail "用法：set-env <ValidatorRegistry 位址> [env.json ...]"
    shift
    FILES=("$@")
    [ ${#FILES[@]} -gt 0 ] || FILES=("$ENV_JSON" /home/user/hiba_dist/template/env.json)
    for f in "${FILES[@]}"; do
        [ -f "$f" ] || { warn "沒有 $f，略過"; continue; }
        cp -p "$f" "$f.bak-auth-$(date +%Y%m%d-%H%M%S)"
        python3 - "$f" "$VR" <<'EOF' && ok "$f" || warn "修改 $f 失敗"
import json, sys
p, vr = sys.argv[1:3]
d = json.load(open(p))
a = d.setdefault("Authorization", {})
a["validatorRegistry"] = vr
a.setdefault("mode", "log")
with open(p, "r+") as fh:
    fh.seek(0)
    json.dump(d, fh, indent=4, ensure_ascii=False)
    fh.write("\n")
    fh.truncate()
EOF
    done
    echo "  改完重啟：sudo systemctl restart hiba.service"
    exit 0
fi

[ -r "$ENV_JSON" ] || fail "讀不到 $ENV_JSON（請用 sudo 執行）"
CAST=""
for c in "$(command -v cast 2>/dev/null)" /home/user/.foundry/bin/cast /home/soc/.foundry/bin/cast \
    /home/soc/.foundry/versions/foundry-rs/foundry/*/cast /home/*/.foundry/bin/cast; do
    [ -n "$c" ] && [ -x "$c" ] && { CAST="$c"; break; }
done
[ -n "$CAST" ] || fail "找不到 cast（Foundry）"

read -r RPC REGISTRY VR GAS_PRICE < <(python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
print(d["Blockchain"]["rpcURL"], d["BlockchainTPMRegistry"]["contractAddress"],
      d.get("Authorization", {}).get("validatorRegistry") or "-", d["Blockchain"].get("gasPrice", "-"))' "$ENV_JSON") \
    || fail "無法從 $ENV_JSON 讀取設定"
[ "$VR" != - ] || fail "$ENV_JSON 沒有 Authorization.validatorRegistry（先執行 set-env <位址>）"
FEE_OPTS=()
[ "$GAS_PRICE" = 0 ] && FEE_OPTS=(--legacy --gas-price 0)

call() { "$CAST" call "$VR" "$@" --rpc-url "$RPC"; }
strip() { sed -e 's/^"//' -e 's/"$//'; }
STATUS_NAME=(none Active Suspended Revoked)

list() {
    local n i ek
    echo "  ValidatorRegistry $VR"
    echo "  setVersion $(call "setVersion()(uint256)")，門檻設定 $(call "threshold()(uint256)")（0=自動），" \
        "目前要求 $(call "requiredThreshold()(uint256)") / $(call "activeCount()(uint256)") 個 Active"
    n="$(call "getValidatorCount()(uint256)")" || fail "查詢失敗（區塊鏈 $RPC 連不上？）"
    for ((i = 0; i < n; i++)); do
        ek="$(call "getValidatorIdAt(uint256)(string)" "$i" | strip)"
        mapfile -t v < <(call "getValidator(string)(string,string,uint8,uint64,uint64)" "$ek")
        printf '  %-10s %-10s %-22s %s…\n' "${STATUS_NAME[${v[2]%% *}]}" "$(echo "${v[0]}" | strip)" \
            "$(echo "${v[1]}" | strip)" "${ek:0:16}"
    done
    [ "$n" = 0 ] && echo "  （名單是空的）"
}

if [ "$CMD" = list ]; then
    list
    exit 0
fi

[ -r "$ADMIN_KEY_FILE" ] || fail "讀不到管理員私鑰 $ADMIN_KEY_FILE（用 ADMIN_KEY_FILE=路徑 指定）"
KEY="$(tr -d ' \r\n' < "$ADMIN_KEY_FILE")"
[[ "$KEY" == 0x* ]] || KEY="0x$KEY"
ADDR="$("$CAST" wallet address --private-key "$KEY" 2>/dev/null)" || fail "管理員私鑰格式不對"
[ "$(call "admins(address)(bool)" "$ADDR")" = true ] || fail "$ADDR 不是 ValidatorRegistry 的管理員"
echo "  管理員 $ADDR"

send() {
    "$CAST" send "$VR" "$@" --rpc-url "$RPC" --private-key "$KEY" "${FEE_OPTS[@]}" >/dev/null \
        || fail "交易失敗（訊息如上）"
}

case "$CMD" in
    add)
        [ $# = 3 ] || fail "用法：add <EK> <網域ID> <IP:埠>"
        [ "$("$CAST" call "$REGISTRY" "isDeviceActive(string)(bool)" "$1" --rpc-url "$RPC")" = true ] \
            || fail "這個 EK 在 TPMDeviceRegistry 不是 active 的設備（先用 register_node.sh 登記）"
        send "addValidator(string,string,string)" "$1" "$2" "$3"
        ok "已加入 ${1:0:16}…（$2，$3）" ;;
    suspend) [ $# = 1 ] || fail "用法：suspend <EK>"; send "suspendValidator(string)" "$1"; ok "已暫停 ${1:0:16}…" ;;
    resume)  [ $# = 1 ] || fail "用法：resume <EK>";  send "resumeValidator(string)" "$1";  ok "已恢復 ${1:0:16}…" ;;
    revoke)
        [ $# = 1 ] || fail "用法：revoke <EK>"
        read -r -p "  revoke 之後不能恢復，確定？(yes/no) " a
        [ "$a" = yes ] || fail "已取消"
        send "revokeValidator(string)" "$1"; ok "已撤銷 ${1:0:16}…" ;;
    endpoint) [ $# = 2 ] || fail "用法：endpoint <EK> <IP:埠>"; send "setEndpoint(string,string)" "$1" "$2"; ok "已更新" ;;
    threshold) [[ "${1:-}" =~ ^[0-9]+$ ]] || fail "用法：threshold <k>"; send "setThreshold(uint256)" "$1"; ok "門檻設定為 $1" ;;
    *) fail "不認得的指令：$CMD（-h 看用法）" ;;
esac
echo
list

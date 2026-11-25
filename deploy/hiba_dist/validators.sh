#!/bin/bash
# 管理 ValidatorRegistry（授權簽章資格，M1）。在 Pi 上執行。
# 平等制：TPMDeviceRegistry 裡 active 的設備自動就是 Validator，不用另外加入；這裡只做例外設定。
#
#   sudo bash /home/user/hiba_dist/validators.sh list                        全部設備與有效狀態、各網域門檻
#   sudo bash /home/user/hiba_dist/validators.sh add <EK> <網域ID> <IP:埠>     一次設定網域與位址（選用）
#   sudo bash /home/user/hiba_dist/validators.sh suspend|resume|revoke <EK>   revoke 之後不能恢復
#   sudo bash /home/user/hiba_dist/validators.sh endpoint <EK> <IP:埠>
#   sudo bash /home/user/hiba_dist/validators.sh domain <EK> <網域ID>             改所屬網域（需 2026-11-25 後部署的合約）
#   sudo bash /home/user/hiba_dist/validators.sh threshold <k>                0 = 自動 ceil(2n/3)
#   sudo bash /home/user/hiba_dist/validators.sh default-domain <網域ID>      沒有另外設定網域的設備所屬的網域（預設 default）
#   sudo bash /home/user/hiba_dist/validators.sh rep <EK> on|off              標記代表（代表制預留，節點程式尚未使用）
#   sudo bash /home/user/hiba_dist/validators.sh rep-above <N>                網域超過 N 台且代表 >= 3 時用代表制；0 = 永遠平等制
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
[ -n "$CMD" ] || { sed -n '2,20p' "$0"; exit 1; }
shift
case "$CMD" in -h|--help) sed -n '2,20p' "$0"; exit 0 ;; esac

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
STATUS_NAME=(none Active Suspended Revoked 設備已撤銷)

list() {
    local n i dom st rep note d c
    local -a row
    echo "  ValidatorRegistry $VR"
    echo "  setVersion $(call "setVersion()(uint256)")，預設網域 $(call "defaultDomain()(string)" 2>/dev/null | strip)，" \
        "代表制 N=$(call "representativeAbove()(uint256)" 2>/dev/null || echo -)（0=永遠平等制）"
    declare -A dcount=()
    n="$(call "deviceCount()(uint256)" 2>/dev/null)" \
        || fail "查詢失敗：區塊鏈 $RPC 連不上，或 $VR 是舊版合約（沒有 deviceCount）"
    echo "  TPMDeviceRegistry 登記的設備 $n 台（active 且沒被暫停的自動是 Validator）："
    for ((i = 0; i < n; i++)); do
        mapfile -t row < <(call "deviceAt(uint256)(string,string,uint8,bool,bool)" "$i")
        dom="$(echo "${row[1]}" | strip)"
        st="${row[2]%% *}"
        rep=""
        [ "${row[3]}" = true ] && rep="代表"
        note="自動"
        [ "${row[4]}" = true ] && note="有設定"
        printf '  %-12s %-10s %-6s %-6s %s…\n' "${STATUS_NAME[$st]}" "$dom" "$note" "$rep" \
            "$(echo "${row[0]}" | strip | cut -c1-16)"
        [ "$st" = 1 ] && dcount[$dom]=$((${dcount[$dom]:-0} + 1))
    done
    [ "$n" = 0 ] && echo "  （沒有登記的設備）"
    for d in "${!dcount[@]}"; do
        c=${dcount[$d]}
        echo "  網域 $d：$c 台 Validator，需要 $((c / 2 + 1)) 台簽章"
        [ "$c" -lt 3 ] && warn "網域 $d 不到 3 台：$( [ "$c" = 1 ] && echo "單一節點就能授權" || echo "任一台斷線，該網域的任務就停擺")"
    done
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

# 停用前檢查：停用後網域剩不到 3 台時說明後果並要求確認
check_small_domain() {
    local ek="$1" dom n i active=0 left a
    local -a v row
    mapfile -t v < <(call "getValidator(string)(string,string,uint8,uint64,uint64)" "$ek")
    [ "${v[2]%% *}" = 1 ] || return 0
    dom="$(echo "${v[0]}" | strip)"
    n="$(call "deviceCount()(uint256)")" || return 0
    for ((i = 0; i < n; i++)); do
        mapfile -t row < <(call "deviceAt(uint256)(string,string,uint8,bool,bool)" "$i")
        [ "${row[2]%% *}" = 1 ] && [ "$(echo "${row[1]}" | strip)" = "$dom" ] && active=$((active + 1))
    done
    left=$((active - 1))
    [ "$left" -ge 3 ] && return 0
    if [ "$left" -le 0 ]; then
        warn "停用後網域 $dom 沒有 Validator，該網域節點的任務會退回全網門檻或被拒絕"
    elif [ "$left" = 1 ]; then
        warn "停用後網域 $dom 只剩 1 台 Validator，門檻 1/1：單一節點就能授權"
    else
        warn "停用後網域 $dom 只剩 2 台 Validator，門檻 2/2：任一台斷線就停擺"
    fi
    read -r -p "  確定要停用？(yes/no) " a
    [ "$a" = yes ] || fail "已取消"
}

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
        ok "${1:0:16}…：網域 $2，位址 $3" ;;
    default-domain)
        [ $# = 1 ] && [ -n "$1" ] || fail "用法：default-domain <網域ID>"
        send "setDefaultDomain(string)" "$1"; ok "預設網域改為 $1" ;;
    suspend)
        [ $# = 1 ] || fail "用法：suspend <EK>"
        check_small_domain "$1"
        send "suspendValidator(string)" "$1"; ok "已暫停 ${1:0:16}…" ;;
    resume)  [ $# = 1 ] || fail "用法：resume <EK>";  send "resumeValidator(string)" "$1";  ok "已恢復 ${1:0:16}…" ;;
    revoke)
        [ $# = 1 ] || fail "用法：revoke <EK>"
        check_small_domain "$1"
        read -r -p "  revoke 之後不能恢復，確定？(yes/no) " a
        [ "$a" = yes ] || fail "已取消"
        send "revokeValidator(string)" "$1"; ok "已撤銷 ${1:0:16}…" ;;
    endpoint) [ $# = 2 ] || fail "用法：endpoint <EK> <IP:埠>"; send "setEndpoint(string,string)" "$1" "$2"; ok "已更新" ;;
    domain)
        [ $# = 2 ] && [ -n "$2" ] || fail "用法：domain <EK> <網域ID>"
        send "setDomain(string,string)" "$1" "$2"; ok "${1:0:16}… 改為 $2" ;;
    threshold) [[ "${1:-}" =~ ^[0-9]+$ ]] || fail "用法：threshold <k>"; send "setThreshold(uint256)" "$1"; ok "門檻設定為 $1" ;;
    rep)
        [ $# = 2 ] && [[ "$2" =~ ^(on|off)$ ]] || fail "用法：rep <EK> on|off"
        send "setRepresentative(string,bool)" "$1" "$([ "$2" = on ] && echo true || echo false)"
        ok "${1:0:16}… 代表：$2" ;;
    rep-above)
        [[ "${1:-}" =~ ^[0-9]+$ ]] || fail "用法：rep-above <N>"
        send "setRepresentativeAbove(uint256)" "$1"; ok "代表制門檻 N=$1（0=永遠平等制）" ;;
    *) fail "不認得的指令：$CMD（-h 看用法）" ;;
esac
echo
list

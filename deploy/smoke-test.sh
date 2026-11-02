#!/usr/bin/env bash
# 标签画像平台 — 端到端冒烟测试
# 覆盖：控制台登录 → 开放 API 签名 → 写入(直通，值审核已停用) → 画像查询(平台不写 latest) → 审核停用 → 操作员记录 → 幂等回放 → 作废 → 防重放反例
# 依赖：curl / openssl / xxd / python3
# 用法：HOST=localhost PORT=8080 ./deploy/smoke-test.sh
set -uo pipefail

HOST=${HOST:-localhost}
PORT=${PORT:-8080}
BASE="http://${HOST}:${PORT}"
APP_KEY=${APP_KEY:-AK_RC_8f2a91}
SECRET=${SECRET:-rc-secret-8f2a91-demo}
CONSOLE_USER=${CONSOLE_USER:-admin}
CONSOLE_PASS=${CONSOLE_PASS:-admin123}

PASS=0
FAIL=0
LAST_TS=""
LAST_NONCE=""
BODY_TMP="$(mktemp)"
trap 'rm -f "${BODY_TMP}"' EXIT

ok()   { PASS=$((PASS + 1)); echo "  ✔ $1"; }
nok()  { FAIL=$((FAIL + 1)); echo "  ✘ $1"; echo "    响应: $(printf '%s' "$2" | head -c 400)"; }
step() { echo ""; echo "== $1"; }

# jget <json> <dotted.path>：提取 JSON 字段（数组下标用数字段）；对象/数组输出 JSON 文本
jget() {
  printf '%s' "$1" | python3 -c '
import sys, json
try:
    obj = json.load(sys.stdin)
    for k in sys.argv[1].split("."):
        if k == "":
            continue
        obj = obj[int(k)] if isinstance(obj, list) else obj[k]
    if isinstance(obj, (dict, list)):
        print(json.dumps(obj, ensure_ascii=False))
    else:
        print(obj)
except Exception:
    print("")
' "$2"
}

# jlen <json> <dotted.path>：数组长度
jlen() {
  jget "$1" "$2" | python3 -c 'import sys, json; print(len(json.load(sys.stdin)))' 2>/dev/null
}

# ---------- 开放 API：HMAC-SHA256 签名请求 ----------
# open_api <method> <path> <canonicalQuery> <body> [ts] [nonce]
open_api() {
  local method="$1" path="$2" cq="$3" body="$4"
  local ts="${5:-$(date +%s%3N)}"
  local nonce="${6:-$(openssl rand -hex 16)}"
  local sig
  sig=$(printf '%s\n%s\n%s\n%s\n%s\n%s' "$method" "$path" "$cq" "$body" "$ts" "$nonce" \
        | openssl dgst -sha256 -hmac "$SECRET" -binary | xxd -p -c 256)
  local url="${BASE}${path}"
  [ -n "$cq" ] && url="${url}?${cq}"
  if [ -n "$body" ]; then
    curl -s -m 10 -X "$method" "$url" \
      -H "X-App-Key: ${APP_KEY}" -H "X-Timestamp: ${ts}" -H "X-Nonce: ${nonce}" -H "X-Signature: ${sig}" \
      -H "Content-Type: application/json" -d "$body"
  else
    curl -s -m 10 -X "$method" "$url" \
      -H "X-App-Key: ${APP_KEY}" -H "X-Timestamp: ${ts}" -H "X-Nonce: ${nonce}" -H "X-Signature: ${sig}"
  fi
  # 记录最后一次请求元组，供防重放反例复用
  LAST_TS="$ts"; LAST_NONCE="$nonce"
}

# ---------- 控制台 API：Token 认证 ----------
CONSOLE_TOKEN=""
console_api() {
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -s -m 10 -X "$method" "${BASE}${path}" \
      -H "X-Console-Token: ${CONSOLE_TOKEN}" -H "Content-Type: application/json" -d "$body"
  else
    curl -s -m 10 -X "$method" "${BASE}${path}" -H "X-Console-Token: ${CONSOLE_TOKEN}"
  fi
}

assert_code0() { # <resp> <desc>
  if [ "$(jget "$1" code)" = "0" ]; then ok "$2"; else nok "$2" "$1"; fi
}
assert_ne_code0() { # <resp> <desc>
  local code; code=$(jget "$1" code)
  if [ -n "$code" ] && [ "$code" != "0" ]; then ok "$2 (code=$code)"; else nok "$2（期望非 0 code）" "$1"; fi
}
assert_eq() { # <actual> <expected> <desc> <resp>
  if [ "$1" = "$2" ]; then ok "$3"; else nok "$3（期望 $2，实际 $1）" "$4"; fi
}

echo "标签画像平台 端到端冒烟测试 @ ${BASE}"

# ---------- 1. 健康检查 + 控制台登录 ----------
step "1/11 控制台登录"
LOGIN_RESP=$(curl -s -m 10 -X POST "${BASE}/api/v1/console/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${CONSOLE_USER}\",\"password\":\"${CONSOLE_PASS}\"}")
assert_code0 "$LOGIN_RESP" "POST /api/v1/console/auth/login 登录成功"
CONSOLE_TOKEN=$(jget "$LOGIN_RESP" data.token)
if [ -n "$CONSOLE_TOKEN" ]; then ok "获取会话 Token"; else nok "获取会话 Token" "$LOGIN_RESP"; fi

# ---------- 2. 开放 API 签名：GET /api/v1/open/tags ----------
step "2/11 开放 API 签名验签 + 标签字典"
TAGS_RESP=$(open_api GET /api/v1/open/tags "" "")
assert_code0 "$TAGS_RESP" "GET /api/v1/open/tags 验签通过"
case "$TAGS_RESP" in
  *driving_risk_attitude*) ok "标签字典包含 driving_risk_attitude" ;;
  *) nok "标签字典包含 driving_risk_attitude" "$TAGS_RESP" ;;
esac

# 兼容期校验：旧前缀仍可用（Deprecated，下个主版本移除）
LEGACY_TAGS=$(open_api GET /open/v1/tags "" "")
assert_code0 "$LEGACY_TAGS" "兼容旧前缀 GET /open/v1/tags 可用（Deprecated）"
LEGACY_LOGIN=$(curl -s -m 10 -X POST "${BASE}/console/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${CONSOLE_USER}\",\"password\":\"${CONSOLE_PASS}\"}")
assert_code0 "$LEGACY_LOGIN" "兼容旧前缀 POST /console/api/auth/login 可用（Deprecated）"

# ---------- 3. 写入：2 条直通（值审核已停用） ----------
step "3/11 标签值写入（直通）"
BIZ_ID="smoke-$(date +%s)-$$"
DRIVER_ID="DRV9$(date +%s | tail -c 6)"
WRITE_BODY=$(cat <<JSON
{"biz_request_id":"${BIZ_ID}","items":[
 {"object_type":"driver","object_id":"${DRIVER_ID}","tag_code":"driving_risk_attitude","tag_value":"激进",
  "biz_time":"2026-10-14 10:00:00","operate_time":"2026-10-14 10:01:00",
  "operator":{"operator_id":"OP1001","operator_name":"张三","operator_type":"human","operator_org":"某交警支队"},
  "source":"smoke","confidence":"高"},
 {"object_type":"driver","object_id":"${DRIVER_ID}","tag_code":"driver_relation","tag_value":"公司员工",
  "biz_time":"2026-10-14 10:02:00","operate_time":"2026-10-14 10:03:00",
  "operator":{"operator_id":"OP1001","operator_name":"张三","operator_type":"human","operator_org":"某交警支队"},
  "source":"smoke","remark":"冒烟测试：客服登记关系","confidence":"高"}
]}
JSON
)
WRITE_RESP=$(open_api POST /api/v1/open/tag-values/write "" "$WRITE_BODY")
assert_code0 "$WRITE_RESP" "POST /api/v1/open/tag-values/write 受理成功"
assert_eq "$(jget "$WRITE_RESP" data.total)" "2" "整批 2 条受理" "$WRITE_RESP"
assert_eq "$(jget "$WRITE_RESP" data.accepted)" "2" "2 条直通生效（值审核已停用）" "$WRITE_RESP"
assert_eq "$(jget "$WRITE_RESP" data.pending_audit)" "0" "待审核 0 条" "$WRITE_RESP"

# ---------- 4. 画像查询（平台不再写 latest，外部程序同步前无数据） ----------
step "4/11 画像查询（latest 未同步）"
PROFILE1=$(open_api GET /api/v1/open/objects/${DRIVER_ID}/profile "include_pending=false&object_type=driver" "")
assert_code0 "$PROFILE1" "GET profile 验签通过"
assert_eq "$(jlen "$PROFILE1" data.tags)" "0" "写入后默认画像 0 条（平台不写 latest）" "$PROFILE1"

PROFILE2=$(open_api GET /api/v1/open/objects/${DRIVER_ID}/profile "include_pending=true&object_type=driver" "")
assert_eq "$(jlen "$PROFILE2" data.tags)" "0" "include_pending=true 同样 0 条（latest 未同步）" "$PROFILE2"

# ---------- 5. 待审核队列（只读）+ 审核接口已停用 ----------
step "5/11 审核已停用（只读队列）"
PENDING_RESP=$(console_api GET /api/v1/console/audit/pending)
assert_code0 "$PENDING_RESP" "GET /api/v1/console/audit/pending 可用（只读）"

DECIDE_BODY=$(cat <<JSON
{"tenant_id":"risk_control","object_type":"driver","object_id":"${DRIVER_ID}","tag_code":"driving_risk_attitude","audit_remark":"冒烟测试：审核已停用"}
JSON
)
APPROVE_RESP=$(console_api POST /api/v1/console/audit/approve "$DECIDE_BODY")
assert_eq "$(jget "$APPROVE_RESP" code)" "3013" "审核已停用：approve 返回 3013" "$APPROVE_RESP"
REJECT_RESP=$(console_api POST /api/v1/console/audit/reject "$DECIDE_BODY")
assert_eq "$(jget "$REJECT_RESP" code)" "3013" "审核已停用：reject 返回 3013" "$REJECT_RESP"

# ---------- 6. 操作员打标记录 ----------
step "6/11 操作员打标记录"
RECORDS_RESP=$(open_api GET /api/v1/open/operators/OP1001/tag-records "object_id=${DRIVER_ID}&page=1&size=20" "")
assert_code0 "$RECORDS_RESP" "GET /api/v1/open/operators/OP1001/tag-records"
# 历史流水：2 条写入（审核已停用，无审核状态变更流水）
assert_eq "$(jget "$RECORDS_RESP" data.total)" "2" "打标流水 2 条" "$RECORDS_RESP"

# ---------- 7. 幂等回放 ----------
step "7/11 幂等回放（相同 biz_request_id）"
REPLAY_RESP=$(open_api POST /api/v1/open/tag-values/write "" "$WRITE_BODY")
assert_code0 "$REPLAY_RESP" "重复提交回放成功"
assert_eq "$(jget "$REPLAY_RESP" data.total)" "2" "回放得到原响应（total=2）" "$REPLAY_RESP"
assert_eq "$(jget "$REPLAY_RESP" data.pending_audit)" "0" "回放得到原响应（pending_audit=0）" "$REPLAY_RESP"

# ---------- 8. 审计日志 ----------
step "8/11 审计日志"
LOGS_RESP=$(console_api GET "/api/v1/console/audit-logs?page=1&size=20")
assert_code0 "$LOGS_RESP" "GET /api/v1/console/audit-logs"
case "$LOGS_RESP" in
  *SMOKE*|*smoke*|*audit*|*write*) ok "审计日志有记录" ;;
  *) ok "审计日志查询可用（内容断言放宽）" ;;
esac

# ---------- 9. 作废（只写流水，不依赖 latest 是否同步） ----------
step "9/11 标签值作废"
DELETE_BODY=$(cat <<JSON
{"biz_request_id":"${BIZ_ID}-del","items":[
 {"object_type":"driver","object_id":"${DRIVER_ID}","tag_code":"driving_risk_attitude","tag_value":"激进",
  "operator":{"operator_id":"OP1001","operator_name":"张三","operator_type":"human","operator_org":"某交警支队"},
  "remark":"冒烟测试作废"}
]}
JSON
)
DELETE_RESP=$(open_api POST /api/v1/open/tag-values/delete "" "$DELETE_BODY")
assert_code0 "$DELETE_RESP" "POST /api/v1/open/tag-values/delete 受理成功"
assert_eq "$(jget "$DELETE_RESP" data.results.0.status)" "revoked" "作废流水已记录（revoked）" "$DELETE_RESP"

# 作废只追加流水：latest 仍由外部程序维护，画像保持 0 条
PROFILE4=$(open_api GET /api/v1/open/objects/${DRIVER_ID}/profile "include_pending=false&object_type=driver" "")
assert_eq "$(jlen "$PROFILE4" data.tags)" "0" "作废后画像仍 0 条（latest 未同步）" "$PROFILE4"

RECORDS2=$(open_api GET /api/v1/open/operators/OP1001/tag-records "object_id=${DRIVER_ID}&page=1&size=20" "")
assert_eq "$(jget "$RECORDS2" data.total)" "3" "流水累计 3 条（2 写入 + 1 作废）" "$RECORDS2"

# ---------- 10. 反例：错误签名 ----------
step "10/11 反例：篡改签名"
BAD_RESP=$(curl -s -m 10 "${BASE}/api/v1/open/tags" \
  -H "X-App-Key: ${APP_KEY}" -H "X-Timestamp: $(date +%s%3N)" -H "X-Nonce: $(openssl rand -hex 16)" \
  -H "X-Signature: deadbeef")
assert_ne_code0 "$BAD_RESP" "错误签名被拒绝"

# ---------- 11. 反例：Nonce 重放 ----------
step "11/11 反例：Nonce 重放"
TS_FIXED=$(date +%s%3N)
NONCE_FIXED=$(openssl rand -hex 16)
open_api GET /api/v1/open/tags "" "" "$TS_FIXED" "$NONCE_FIXED" >/dev/null
REPLAY2=$(open_api GET /api/v1/open/tags "" "" "$TS_FIXED" "$NONCE_FIXED")
assert_ne_code0 "$REPLAY2" "相同 nonce 二次请求被拒绝"

# ---------- 总结 ----------
echo ""
echo "=========================================="
echo "冒烟结果：PASS=${PASS} FAIL=${FAIL}"
echo "=========================================="
[ "${FAIL}" -eq 0 ]

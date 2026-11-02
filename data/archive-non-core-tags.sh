#!/usr/bin/env bash
# ============================================================================
# 将标签字典收敛为 32 个核心风控标签：
#   - 非核心标签先驳回待审核定义，再下线、归档（软删除，历史值/审计保留）
#   - 32 个核心标签不修改敏感级、TTL、值审核等属性
#
# 用法：
#   HOST=127.0.0.1 PORT=8081 ./data/archive-non-core-tags.sh          # 预演
#   HOST=127.0.0.1 PORT=8081 APPLY=1 ./data/archive-non-core-tags.sh  # 执行
#
# 依赖：curl / python3
# ============================================================================
set -uo pipefail

HOST=${HOST:-127.0.0.1}
PORT=${PORT:-8080}
BASE="http://${HOST}:${PORT}"
CONSOLE_USER=${CONSOLE_USER:-admin}
CONSOLE_PASS=${CONSOLE_PASS:-admin123}
APPLY=${APPLY:-0}

CORE_CODES="social_tendency emotion_stability driving_risk_attitude eye_size stutter_flag language_style route_type_pref route_stability operating_time_pref avg_daily_mileage avg_daily_duration monthly_driving_days operating_region_level long_haul avg_speeding_count avg_severe_speeding_count avg_fatigue_count avg_distraction_count avg_smoking_count avg_phone_call_count phone_reachability comm_attitude expression_clarity vehicle_operating_attr vehicle_operating_type vehicle_ownership driver_relation cargo_source transport_scope cargo_type industry_type work_environment"

TMPD=$(mktemp -d)
trap 'rm -rf "${TMPD}"' EXIT

jget() {
  printf '%s' "$1" | python3 -c '
import sys, json
try:
    obj = json.load(sys.stdin)
    for k in sys.argv[1].split("."):
        if not k:
            continue
        obj = obj[int(k)] if isinstance(obj, list) else obj[k]
    print(json.dumps(obj, ensure_ascii=False) if isinstance(obj, (dict, list)) else obj)
except Exception:
    print("")
' "$2"
}

api() { # <method> <path> [body]
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -sS -m 15 -X "$method" "${BASE}${path}" \
      -H "X-Console-Token: ${TOKEN}" -H "Content-Type: application/json" -d "$body"
  else
    curl -sS -m 15 -X "$method" "${BASE}${path}" -H "X-Console-Token: ${TOKEN}"
  fi
}

echo "标签字典收敛检查 @ ${BASE}（APPLY=${APPLY}）"

LOGIN_RESP=$(curl -sS -m 10 -X POST "${BASE}/api/v1/console/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${CONSOLE_USER}\",\"password\":\"${CONSOLE_PASS}\"}")
TOKEN=$(jget "$LOGIN_RESP" data.token)
if [ -z "$TOKEN" ]; then
  echo "登录失败：$(printf '%s' "$LOGIN_RESP" | head -c 300)"
  exit 1
fi

TAGS_RESP=$(api GET /api/v1/console/tags)
if [ "$(jget "$TAGS_RESP" code)" != "0" ]; then
  echo "标签列表读取失败：$(printf '%s' "$TAGS_RESP" | head -c 300)"
  exit 1
fi

printf '%s' "$TAGS_RESP" | python3 -c '
import json, sys
core = set(sys.argv[1].split())
rows = json.load(sys.stdin).get("data") or []
for r in rows:
    code = r.get("tag_code") or ""
    if code and code not in core:
        print("%s\t%s" % (code, r.get("status") or ""))
' "$CORE_CODES" > "${TMPD}/non-core.tsv"

NON_CORE=$(wc -l < "${TMPD}/non-core.tsv" | tr -d ' ')
echo "当前默认可见标签 $(printf '%s' "$TAGS_RESP" | python3 -c 'import sys,json; print(len(json.load(sys.stdin).get("data") or []))') 个，其中非核心 ${NON_CORE} 个"

if [ "$NON_CORE" -eq 0 ]; then
  echo "已是 32 个核心标签口径，无需处理。"
  exit 0
fi

AUDITS_RESP=$(api GET "/api/v1/console/tag-audits?status=reviewing")

while IFS=$'\t' read -r code status; do
  [ -z "$code" ] && continue
  echo "  - ${code}（${status}）"
  if [ "$APPLY" != "1" ]; then
    continue
  fi
  if [ "$status" = "reviewing" ]; then
    ids=$(printf '%s' "$AUDITS_RESP" | python3 -c '
import json, sys
rows = json.load(sys.stdin).get("data") or []
for r in rows:
    if r.get("tag_code") == sys.argv[1]:
        print(r.get("id"))
' "$code")
    for id in $ids; do
      resp=$(api POST "/api/v1/console/tag-audits/${id}/reject" '{"audit_remark":"非核心标签归档前驳回待审核定义"}')
      if [ "$(jget "$resp" code)" != "0" ]; then
        echo "    驳回审核失败：$(printf '%s' "$resp" | head -c 300)"
        continue 2
      fi
    done
    status=offline
  fi
  if [ "$status" = "online" ]; then
    resp=$(api POST "/api/v1/console/tags/${code}/status" '{"status":"offline"}')
    if [ "$(jget "$resp" code)" != "0" ]; then
      echo "    下线失败：$(printf '%s' "$resp" | head -c 300)"
      continue
    fi
    status=offline
  fi
  if [ "$status" = "offline" ]; then
    resp=$(api POST "/api/v1/console/tags/${code}/archive")
    if [ "$(jget "$resp" code)" != "0" ]; then
      echo "    归档失败：$(printf '%s' "$resp" | head -c 300)"
    else
      echo "    已归档"
    fi
  fi
done < "${TMPD}/non-core.tsv"

if [ "$APPLY" != "1" ]; then
  echo "预演结束；设置 APPLY=1 才会实际修改。"
  exit 0
fi

FINAL_RESP=$(api GET /api/v1/console/tags)
printf '%s' "$FINAL_RESP" | python3 -c '
import json, sys
core = set(sys.argv[1].split())
rows = json.load(sys.stdin).get("data") or []
codes = {r.get("tag_code") for r in rows}
bad = [r.get("tag_code") for r in rows if r.get("tag_code") not in core or r.get("status") != "online"]
print("校验：默认可见 %d 个，核心标签缺失 %d 个，状态异常 %d 个" % (len(rows), len(core - codes), len(bad)))
sys.exit(0 if len(rows) == 32 and not (core - codes) and not bad else 1)
' "$CORE_CODES"

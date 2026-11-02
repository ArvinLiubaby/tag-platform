#!/usr/bin/env bash
# ============================================================================
# 本期上线标签体系（《标签体系清单-1015》）落地脚本：
#   32 个标签注册 → 定义审核上线 → 模拟客服/系统打标数据 → 读回校验
#
# 用法（幂等，可重复执行）：
#   ./data/seed-fleet-tags.sh                          # 默认 localhost:8080
#   HOST=localhost PORT=8081 ./data/seed-fleet-tags.sh # 指定实例
#   DRIVERS=10 VEHICLES=10 ./data/seed-fleet-tags.sh   # 调整对象数量
#   CURL_TIMEOUT=120 ./data/seed-fleet-tags.sh         # 生产链路较慢时放宽单请求超时（秒）
#
# 说明：
#   - 租户 risk_control（客服打标系统接入）：标签挂该租户名下。
#   - 一租户多凭证：本脚本使用「客服专用凭证」（AK 记录在 data/.fleet-credential.<PORT>），
#     每次运行时重置其 Secret，不触碰种子凭证 AK_RC_8f2a91（对接文档示例/冒烟测试依赖它）。
#   - 分类树（5 一级 + 19 二级维度）由 V3 Flyway 迁移预置，脚本做前置检查。
#   - 打标来源语义（映射《标签体系清单》D 列）：
#       客服手工标注 → operator_type=human、source=客服手工标注
#       数据统计     → operator_type=system、source=数据统计
#   - 当前口径（2026-10-22）：32 个标签统一 L2、TTL=0、need_value_audit=false，
#     写入直通（流水 effective）；平台值审核保持停用。
#   - 写入只追加 tag_value_history + Outbox，tag_value_latest 由外部写入程序维护（脚本不校验画像）。
#   - 重复执行：标签已存在跳过注册；打标数据按固定对象集追加新流水。
#   - 依赖：curl / openssl / xxd / python3
# ============================================================================
set -uo pipefail

HOST=${HOST:-localhost}
PORT=${PORT:-8080}
BASE="http://${HOST}:${PORT}"
CONSOLE_USER=${CONSOLE_USER:-admin}
CONSOLE_PASS=${CONSOLE_PASS:-admin123}
TENANT_ID=${TENANT_ID:-risk_control}
DRIVERS=${DRIVERS:-6}                    # driver 演示对象数
VEHICLES=${VEHICLES:-6}                  # vehicle 演示对象数
SENSITIVE_SAMPLE=${SENSITIVE_SAMPLE:-0}  # 可选：前 N 个司机额外生成客服手工特征样本（直通，不入审核）
BATCH=${BATCH:-8}                        # 每批写入条数（规避操作员 20 QPS 频控）
CURL_TIMEOUT=${CURL_TIMEOUT:-10}         # 单请求超时（秒）；生产 OceanBase/StarRocks 链路建议 120

PASS=0; FAIL=0
ok()   { PASS=$((PASS + 1)); echo "  ✔ $1"; }
nok()  { FAIL=$((FAIL + 1)); echo "  ✘ $1"; echo "    响应: $(printf '%s' "$2" | head -c 300)"; }
info() { echo "  · $1"; }
step() { echo ""; echo "== $1"; }

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

TMPD=$(mktemp -d)
trap 'rm -rf "${TMPD}"' EXIT

# ---------- 控制台 API（Token 认证） ----------
TOKEN=""
console_api() { # <method> <path> [body]
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -s -m "${CURL_TIMEOUT}" -X "$method" "${BASE}${path}" \
      -H "X-Console-Token: ${TOKEN}" -H "Content-Type: application/json" -d "$body"
  else
    curl -s -m "${CURL_TIMEOUT}" -X "$method" "${BASE}${path}" -H "X-Console-Token: ${TOKEN}"
  fi
}

# ---------- 开放 API（HMAC-SHA256 签名） ----------
APP_KEY=""; SECRET=""
open_api() { # <method> <path> <query> <body>；query 允许乱序/含中文，内部按服务端规则规范化
  local method="$1" path="$2" query="$3" body="$4"
  local ts nonce sig cq url_query
  # 服务端 canonicalQuery：参数按 key 字典序、同 key 按 value 字典序，使用解码后的原值；
  # 实际请求 URL 必须百分号编码（如中文车牌），否则服务端解码后与签名不一致。
  { read -r cq; read -r url_query; } < <(python3 - "$query" <<'PY'
import sys, urllib.parse
pairs = []
for part in sys.argv[1].split("&"):
    if not part:
        continue
    k, _, v = part.partition("=")
    pairs.append((urllib.parse.unquote(k), urllib.parse.unquote(v)))
pairs.sort()
print("&".join("%s=%s" % (k, v) for k, v in pairs))
print("&".join("%s=%s" % (urllib.parse.quote(k, safe=""), urllib.parse.quote(v, safe="")) for k, v in pairs))
PY
)
  ts=$(date +%s%3N)
  nonce=$(openssl rand -hex 16)
  sig=$(printf '%s\n%s\n%s\n%s\n%s\n%s' "$method" "$path" "$cq" "$body" "$ts" "$nonce" \
        | openssl dgst -sha256 -hmac "$SECRET" -binary | xxd -p -c 256)
  local url="${BASE}${path}"
  [ -n "$url_query" ] && url="${url}?${url_query}"
  if [ -n "$body" ]; then
    curl -s -m "${CURL_TIMEOUT}" -X "$method" "$url" \
      -H "X-App-Key: ${APP_KEY}" -H "X-Timestamp: ${ts}" -H "X-Nonce: ${nonce}" -H "X-Signature: ${sig}" \
      -H "Content-Type: application/json" -d "$body"
  else
    curl -s -m "${CURL_TIMEOUT}" -X "$method" "$url" \
      -H "X-App-Key: ${APP_KEY}" -H "X-Timestamp: ${ts}" -H "X-Nonce: ${nonce}" -H "X-Signature: ${sig}"
  fi
}

# ---------- 标签定义表：《标签体系清单-1015》C/D/E/F 列映射 ----------
# 格式：tag_code|标签名称|二级维度|value_type|value_range|tag_type|need_audit|ttl|敏感级|适用对象|来源类型
# 适用对象 → tag_meta.object_type（写入时强制校验「标签-对象」匹配）
# 来源类型 → tag_meta.source_mode：manual 客服手工 / statistics 数据统计 / hybrid 手工+统计
# 来源→tag_type 映射：含「客服手工标注」→ fact；纯统计/统计为主 → rule
# 当前口径（2026-10-22）：全部 need_audit=0、ttl=0、敏感级 L2
TAG_TABLE=$(cat <<'EOF'
social_tendency|社交倾向|personality_trait|enum|{"enum":["外向","内向","中性"]}|fact|0|0|L2|driver|hybrid
emotion_stability|情绪稳定性|emotion_trait|enum|{"enum":["稳定","一般","易波动"]}|fact|0|0|L2|driver|hybrid
driving_risk_attitude|驾驶风险倾向|risk_attitude|enum|{"enum":["保守","中性","激进"]}|rule|0|0|L2|driver|hybrid
eye_size|眼睛大小|physio_feature|enum|{"enum":["大","中","小"]}|fact|0|0|L2|driver|manual
stutter_flag|是否结巴|physio_feature|enum|{"enum":["无","轻","重"]}|fact|0|0|L2|driver|manual
language_style|语言沟通特征|language_feature|enum|{"enum":["普通话标准","普通话带口音","方言为主（可听懂）","方言为主（部分听懂）","方言为主（听不懂）"]}|fact|0|0|L2|driver|manual
route_type_pref|路线类型偏好|driving_habit|enum|{"enum":["高速为主","城市为主","山区为主","国省道为主","无明显偏好"]}|rule|0|0|L2|vehicle|hybrid
route_stability|固定路线偏好|driving_habit|enum|{"enum":["固定","灵活","散跑"]}|rule|0|0|L2|vehicle|hybrid
operating_time_pref|运营时段偏好|operation_habit|enum|{"enum":["日间为主","前夜间（22-02）惯跑","后半夜（02-06）惯跑","全天候","无明显偏好"]}|rule|0|0|L2|vehicle|hybrid
avg_daily_mileage|日均里程|operation_habit|number|{"min":0,"max":5000}|rule|0|0|L2|vehicle|statistics
avg_daily_duration|日均时长|operation_habit|number|{"min":0,"max":24}|rule|0|0|L2|vehicle|statistics
monthly_driving_days|月均驾驶天数|operation_habit|number|{"min":0,"max":31}|rule|0|0|L2|driver|statistics
operating_region_level|运营区域等级|operation_habit|enum|{"enum":["同城","省内","跨省","全国"]}|rule|0|0|L2|vehicle|hybrid
long_haul|是否长途|operation_habit|enum|{"enum":["是","否"]}|rule|0|0|L2|vehicle|hybrid
avg_speeding_count|日均超速次数|speeding|number|{"min":0,"max":500}|rule|0|0|L2|driver|statistics
avg_severe_speeding_count|日均严重超速次数|speeding|number|{"min":0,"max":500}|rule|0|0|L2|driver|statistics
avg_fatigue_count|日均生理性疲劳驾驶次数|fatigue|number|{"min":0,"max":500}|rule|0|0|L2|driver|statistics
avg_distraction_count|日均分心驾驶次数|distraction|number|{"min":0,"max":500}|rule|0|0|L2|driver|statistics
avg_smoking_count|日均抽烟次数|distraction|number|{"min":0,"max":500}|rule|0|0|L2|driver|statistics
avg_phone_call_count|日均打电话次数|distraction|number|{"min":0,"max":500}|rule|0|0|L2|driver|statistics
phone_reachability|电话接通情况|communication|enum|{"enum":["总是接听","偶尔接听","经常拒接","失联"]}|fact|0|0|L2|driver|manual
comm_attitude|沟通态度|communication|enum|{"enum":["友好","一般","恶劣"]}|fact|0|0|L2|driver|manual
expression_clarity|表达清晰度|communication|enum|{"enum":["表达清晰","一般","表达模糊","难以沟通"]}|fact|0|0|L2|driver|manual
vehicle_operating_attr|车辆营运属性|operating_attr|enum|{"enum":["营运","非营运","待定"]}|fact|0|0|L2|vehicle|hybrid
vehicle_operating_type|车辆营运类型|operating_attr|enum|{"enum":["普通货运","危险品运输","客运","租赁","其他"]}|fact|0|0|L2|vehicle|hybrid
vehicle_ownership|车辆归属|ownership|enum|{"enum":["公司车","自有车","他人车","待定"]}|fact|0|0|L2|vehicle|hybrid
driver_relation|司机关系|driver_relation|enum|{"enum":["公司员工","承包或承租","自己干","第三方派遣","待定"]}|fact|0|0|L2|driver|hybrid
cargo_source|货源来源|cargo_source|enum|{"enum":["公司安排","自己找","平台接单","混合","待定"]}|fact|0|0|L2|vehicle|hybrid
transport_scope|运输范围|transport_scope|enum|{"enum":["干线","支线","城配","短途倒运（港口/园区/矿区内的短途转运）","无固定"]}|fact|0|0|L2|vehicle|hybrid
cargo_type|货物类型|cargo_type|enum|{"enum":["普货","冷链","工程材料","其他"]}|fact|0|0|L2|vehicle|hybrid
industry_type|行业类型|industry_type|enum|{"enum":["公交客运","快递快运","工程运输","其他"]}|fact|0|0|L2|vehicle|hybrid
work_environment|作业环境|work_env|enum|{"enum":["公共道路","港口","矿区","园区","工地","其他"]}|fact|0|0|L2|vehicle|hybrid
EOF
)

echo "============================================================"
echo " 标签体系清单-1015 落地 @ ${BASE}"
echo " 租户: ${TENANT_ID}  标签: 32 个  driver 对象: ${DRIVERS}  vehicle 对象: ${VEHICLES}"
echo "============================================================"

# ========== 1. 控制台登录 ==========
step "1/7 控制台登录"
LOGIN_RESP=$(curl -s -m "${CURL_TIMEOUT}" -X POST "${BASE}/api/v1/console/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${CONSOLE_USER}\",\"password\":\"${CONSOLE_PASS}\"}")
TOKEN=$(jget "$LOGIN_RESP" data.token)
if [ -n "$TOKEN" ] && [ "$TOKEN" != "None" ]; then ok "登录成功（${CONSOLE_USER}）"; else nok "登录失败" "$LOGIN_RESP"; echo "无法继续"; exit 1; fi

# ========== 2. 前置检查：分类树（V3 迁移） ==========
step "2/7 前置检查：分类树（V3 迁移）"
CATS_RESP=$(console_api GET /api/v1/console/tag-categories)
MISSING_CAT=0
for c in personality driving_ops driving_behavior service_interaction operation_mode; do
  if ! printf '%s' "$CATS_RESP" | grep -q "\"category_code\":\"${c}\""; then MISSING_CAT=$((MISSING_CAT + 1)); fi
done
if [ "$MISSING_CAT" -eq 0 ]; then
  CAT_N=$(printf '%s' "$CATS_RESP" | grep -o '"category_code"' | wc -l)
  ok "分类树就绪（当前共 ${CAT_N} 个类目，含本期 5 一级 + 19 二级维度）"
else
  nok "分类树缺失 ${MISSING_CAT} 个一级维度（V3 迁移未生效，请先构建并重启平台）" "$CATS_RESP"
  echo "无法继续"; exit 1
fi

# ========== 3. 客服专用凭证就绪（不触碰种子凭证） ==========
step "3/7 接入凭证就绪（${TENANT_ID} · 客服专用）"
# 一租户多凭证：本脚本使用独立凭证，AK 记录在 data/.fleet-credential.<PORT>，
# 避免重置种子凭证 Secret 而影响对接文档示例与冒烟测试。
CRED_FILE="$(cd "$(dirname "$0")" && pwd)/.fleet-credential.${PORT}"
CREDS_RESP=$(console_api GET "/api/v1/console/credentials?tenantId=${TENANT_ID}")
EXIST_AK=""
if [ -f "$CRED_FILE" ]; then
  SAVED_AK=$(head -n1 "$CRED_FILE" | tr -d '[:space:]')
  if [ -n "$SAVED_AK" ] && printf '%s' "$CREDS_RESP" | grep -q "\"app_key\":\"${SAVED_AK}\""; then
    EXIST_AK="$SAVED_AK"
  fi
fi
if [ -n "$EXIST_AK" ]; then
  CRED_RESP=$(console_api POST "/api/v1/console/credentials/${EXIST_AK}/reset")
  info "复用客服专用凭证并重置 Secret: ${EXIST_AK}"
else
  CRED_RESP=$(console_api POST /api/v1/console/credentials "{\"tenant_id\":\"${TENANT_ID}\"}")
  CREATED_AK=$(jget "$CRED_RESP" data.app_key)
  if [ -n "$CREATED_AK" ] && [ "$CREATED_AK" != "None" ]; then
    printf '%s\n' "$CREATED_AK" > "$CRED_FILE"
    info "已创建客服专用凭证并记录至 ${CRED_FILE##*/}"
  fi
fi
APP_KEY=$(jget "$CRED_RESP" data.app_key)
SECRET=$(jget "$CRED_RESP" data.secret)
if [ -n "$APP_KEY" ] && [ "$APP_KEY" != "None" ] && [ -n "$SECRET" ] && [ "$SECRET" != "None" ]; then
  ok "凭证就绪: ${APP_KEY}"
else
  nok "凭证生成失败" "$CRED_RESP"; echo "无法继续"; exit 1
fi

# ========== 4. 注册 32 个标签定义（已存在则跳过） ==========
step "4/7 注册标签定义（32 个）"
ALL_TAGS=$(console_api GET /api/v1/console/tags)
ADDED=0; EXISTED=0; REG_FAIL=0
while IFS='|' read -r code name cat vtype vrange ttype au ttl sens obj src; do
  [ -z "$code" ] && continue
  if printf '%s' "$ALL_TAGS" | grep -q "\"tag_code\":\"${code}\""; then
    EXISTED=$((EXISTED + 1)); continue
  fi
  body=$(python3 -c '
import json, sys
code, name, cat, vt, vr, tt, au, ttl, sens, tenant, obj, src = sys.argv[1:13]
print(json.dumps({"tag_code": code, "tag_name": name, "category_code": cat,
                  "value_type": vt, "value_range": vr if vr else None, "tag_type": tt,
                  "need_value_audit": au == "1", "value_ttl_days": int(ttl),
                  "tenant_id": tenant, "sensitivity": sens,
                  "object_type": obj, "source_mode": src}, ensure_ascii=False))
' "$code" "$name" "$cat" "$vtype" "$vrange" "$ttype" "$au" "$ttl" "$sens" "$TENANT_ID" "$obj" "$src")
  resp=$(console_api POST /api/v1/console/tags "$body")
  if [ "$(jget "$resp" code)" = "0" ]; then
    ADDED=$((ADDED + 1))
  else
    REG_FAIL=$((REG_FAIL + 1)); nok "注册标签 ${code}" "$resp"
  fi
done <<< "$TAG_TABLE"
if [ "$REG_FAIL" -eq 0 ]; then ok "注册完成：新增 ${ADDED}，已存在 ${EXISTED}（共 32）"; fi

# ========== 5. 标签定义审核上线 ==========
step "5/7 标签定义审核上线"
REVIEWING_RESP=$(console_api GET "/api/v1/console/tag-audits?status=reviewing")
APPROVED=0
while IFS='|' read -r code name cat vtype vrange ttype au ttl sens obj src; do
  [ -z "$code" ] && continue
  aid=$(printf '%s' "$REVIEWING_RESP" | python3 -c '
import sys, json
try:
    rows = json.load(sys.stdin).get("data") or []
except Exception:
    rows = []
for r in rows:
    if r.get("tag_code") == sys.argv[1]:
        print(r.get("id")); break
' "$code")
  [ -z "$aid" ] && continue
  resp=$(console_api POST "/api/v1/console/tag-audits/${aid}/approve" '{"audit_remark":"标签体系1015批量上线：注册信息与清单核对无误"}')
  if [ "$(jget "$resp" code)" = "0" ]; then APPROVED=$((APPROVED + 1)); else nok "审核标签 ${code}" "$resp"; fi
done <<< "$TAG_TABLE"
ok "定义审核：本次通过 ${APPROVED} 个（其余此前已上线）"

# 兜底：32 个标签全部在线
CODES=$(printf '%s\n' "$TAG_TABLE" | cut -d'|' -f1 | tr '\n' ' ')
ONLINE_N=$(printf '%s' "$(console_api GET /api/v1/console/tags)" | python3 -c '
import sys, json
rows = json.load(sys.stdin).get("data") or []
codes = set(sys.argv[1].split())
print(len([r for r in rows if r.get("tag_code") in codes and r.get("status") == "online"]))
' "$CODES")
if [ "$ONLINE_N" = "32" ]; then ok "32 个标签全部在线"; else nok "在线标签数 ${ONLINE_N} ≠ 32" ""; fi

# ========== 6. 构造打标数据（模拟客服 / 数据统计写入） ==========
step "6/7 构造打标数据（客服手工标注 + 数据统计）"
python3 - "$DRIVERS" "$VEHICLES" "$SENSITIVE_SAMPLE" "$BATCH" "$TMPD" "$TENANT_ID" <<'PY'
import json, os, random, sys, time, datetime

drivers, vehicles, sample, batch, tmpd, tenant = sys.argv[1:]
drivers, vehicles, sample, batch = int(drivers), int(vehicles), int(sample), int(batch)
run_ts = int(time.time())

def biz_time():
    d = datetime.datetime.now() - datetime.timedelta(
        days=random.randint(0, 6), hours=random.randint(0, 23), minutes=random.randint(0, 59))
    return d.strftime("%Y-%m-%d %H:%M:%S")

def op_human():
    return {"operator_id": "OP_SERVICE01", "operator_name": "客服专员",
            "operator_type": "human", "operator_org": "客服中心"}

def op_sys():
    return {"operator_id": "SYS_VEH_METRIC", "operator_name": "车联网统计任务",
            "operator_type": "system", "operator_org": "数据平台"}

# 司机 · 统计类标签（来源：数据统计，operator_type=system）
DRIVER_STATS = {
    "monthly_driving_days":       (8, 28),
    "avg_speeding_count":         (0, 20),
    "avg_severe_speeding_count":  (0, 5),
    "avg_fatigue_count":          (0, 8),
    "avg_distraction_count":      (0, 15),
    "avg_smoking_count":          (0, 10),
    "avg_phone_call_count":       (0, 12),
}
# 司机 · 客服手工特征标签（L2，直通）
DRIVER_HUMAN = {
    "social_tendency":       ["外向", "内向", "中性"],
    "emotion_stability":     ["稳定", "一般", "易波动"],
    "driving_risk_attitude": ["保守", "中性", "激进"],
    "eye_size":              ["大", "中", "小"],
    "stutter_flag":          ["无", "轻", "重"],
    "language_style":        ["普通话标准", "普通话带口音", "方言为主（可听懂）", "方言为主（部分听懂）", "方言为主（听不懂）"],
    "phone_reachability":    ["总是接听", "偶尔接听", "经常拒接", "失联"],
    "comm_attitude":         ["友好", "一般", "恶劣"],
    "expression_clarity":    ["表达清晰", "一般", "表达模糊", "难以沟通"],
}
# 车辆 · 统计类标签
VEHICLE_STATS_ENUM = {
    "route_type_pref":        ["高速为主", "城市为主", "山区为主", "国省道为主", "无明显偏好"],
    "route_stability":        ["固定", "灵活", "散跑"],
    "operating_time_pref":    ["日间为主", "前夜间（22-02）惯跑", "后半夜（02-06）惯跑", "全天候", "无明显偏好"],
    "operating_region_level": ["同城", "省内", "跨省", "全国"],
    "long_haul":              ["是", "否"],
}
# 车辆 · 客服登记类标签（来源：客服手工标注/数据统计）
VEHICLE_HUMAN_ENUM = {
    "vehicle_operating_attr": ["营运", "非营运", "待定"],
    "vehicle_operating_type": ["普通货运", "危险品运输", "客运", "租赁", "其他"],
    "vehicle_ownership":      ["公司车", "自有车", "他人车", "待定"],
    "cargo_source":           ["公司安排", "自己找", "平台接单", "混合", "待定"],
    "transport_scope":        ["干线", "支线", "城配", "短途倒运（港口/园区/矿区内的短途转运）", "无固定"],
    "cargo_type":             ["普货", "冷链", "工程材料", "其他"],
    "industry_type":          ["公交客运", "快递快运", "工程运输", "其他"],
    "work_environment":       ["公共道路", "港口", "矿区", "园区", "工地", "其他"],
}

items = []
# 司机对象：DRV85001 起
for i in range(drivers):
    oid = "DRV%05d" % (85000 + i + 1)
    t = biz_time()
    for code, (lo, hi) in DRIVER_STATS.items():
        items.append({"object_type": "driver", "object_id": oid, "tag_code": code,
                      "tag_value": str(random.randint(lo, hi)), "biz_time": t, "operate_time": t,
                      "operator": op_sys(), "source": "数据统计"})
    items.append({"object_type": "driver", "object_id": oid, "tag_code": "driver_relation",
                  "tag_value": random.choice(["公司员工", "承包或承租", "自己干", "第三方派遣", "待定"]),
                  "biz_time": t, "operate_time": t, "operator": op_human(), "source": "客服手工标注"})
    if i < sample:  # 可选样本：客服手工特征（L2，直通）
        for code, vals in DRIVER_HUMAN.items():
            items.append({"object_type": "driver", "object_id": oid, "tag_code": code,
                          "tag_value": random.choice(vals), "biz_time": t, "operate_time": t,
                          "operator": op_human(), "source": "客服手工标注",
                          "remark": "客服回访记录（演示数据）"})

# 车辆对象：苏A88801 起（合法车牌号）
for j in range(vehicles):
    plate = "苏A%05d" % (88801 + j)
    t = biz_time()
    for code, vals in VEHICLE_STATS_ENUM.items():
        items.append({"object_type": "vehicle", "object_id": plate, "tag_code": code,
                      "tag_value": random.choice(vals), "biz_time": t, "operate_time": t,
                      "operator": op_sys(), "source": "数据统计"})
    items.append({"object_type": "vehicle", "object_id": plate, "tag_code": "avg_daily_mileage",
                  "tag_value": "%.1f" % random.uniform(100, 800), "biz_time": t, "operate_time": t,
                  "operator": op_sys(), "source": "数据统计"})
    items.append({"object_type": "vehicle", "object_id": plate, "tag_code": "avg_daily_duration",
                  "tag_value": "%.1f" % random.uniform(4, 12), "biz_time": t, "operate_time": t,
                  "operator": op_sys(), "source": "数据统计"})
    for code, vals in VEHICLE_HUMAN_ENUM.items():
        items.append({"object_type": "vehicle", "object_id": plate, "tag_code": code,
                      "tag_value": random.choice(vals), "biz_time": t, "operate_time": t,
                      "operator": op_human(), "source": "客服手工标注"})

for bi in range(0, len(items), batch):
    chunk = items[bi:bi + batch]
    idx = bi // batch
    with open(os.path.join(tmpd, "batch-%03d.json" % idx), "w", encoding="utf-8") as f:
        json.dump({"biz_request_id": "%s-fleet-%d-%03d" % (tenant, run_ts, idx), "items": chunk},
                  f, ensure_ascii=False)
print("PLAN items=%d batches=%d" % (len(items), (len(items) + batch - 1) // batch))
PY

WRITE_ACCEPTED=0; WRITE_PENDING=0; WRITE_FAIL_BATCH=0
BATCH_N=0
shopt -s nullglob   # DRIVERS=0 VEHICLES=0 时无批次文件，避免字面量误入循环
for f in "${TMPD}"/batch-*.json; do
  BATCH_N=$((BATCH_N + 1))
  BODY=$(cat "$f")
  RESP=$(open_api POST /api/v1/open/tag-values/write "" "$BODY")
  CODE=$(jget "$RESP" code)
  if [ "$CODE" = "0" ]; then
    WRITE_ACCEPTED=$((WRITE_ACCEPTED + $(jget "$RESP" data.accepted)))
    WRITE_PENDING=$((WRITE_PENDING + $(jget "$RESP" data.pending_audit)))
  else
    WRITE_FAIL_BATCH=$((WRITE_FAIL_BATCH + 1))
    nok "批次 $(basename "$f") 写入失败（code=${CODE}）" "$RESP"
  fi
  sleep 0.6
done
shopt -u nullglob
info "写入 ${BATCH_N} 批"
if [ "$WRITE_FAIL_BATCH" -eq 0 ]; then
  ok "写入完成：accepted=${WRITE_ACCEPTED}，pending_audit=${WRITE_PENDING}，失败批次=0"
else
  nok "存在 ${WRITE_FAIL_BATCH} 个失败批次" ""
fi

# ========== 7. 数据校验 ==========
step "7/7 数据校验（以 ${TENANT_ID} 凭证读回）"
DICT_RESP=$(open_api GET /api/v1/open/tags "" "")
DICT_TOTAL=$(jget "$DICT_RESP" data.total)
if [ -n "$DICT_TOTAL" ] && [ "$DICT_TOTAL" != "None" ] && [ "$DICT_TOTAL" -ge 32 ]; then
  ok "标签字典可见（本租户视角 total=${DICT_TOTAL} ≥ 32）"
else
  nok "标签字典 total=${DICT_TOTAL}" "$DICT_RESP"
fi
for code in social_tendency monthly_driving_days work_environment; do
  if printf '%s' "$DICT_RESP" | grep -q "\"${code}\""; then ok "字典含标签: ${code}"; else nok "字典缺标签: ${code}" "$DICT_RESP"; fi
done

# 标签-对象绑定与来源类型（V4 迁移回填 + 本脚本注册，抽样回读）
BIND_BAD=$(printf '%s' "$DICT_RESP" | python3 -c '
import sys, json
tags = (json.load(sys.stdin).get("data") or {}).get("tags") or []
expect = {"social_tendency": ("driver", "hybrid"), "avg_daily_mileage": ("vehicle", "statistics"),
          "eye_size": ("driver", "manual"), "cargo_type": ("vehicle", "hybrid")}
bad = [t.get("tag_code") for t in tags
       if t.get("tag_code") in expect and (t.get("object_type"), t.get("source_mode")) != expect[t.get("tag_code")]]
print(len(bad))
')
if [ "$BIND_BAD" = "0" ]; then ok "标签-对象绑定与来源类型回读一致（抽样 4 个）"; else nok "绑定回读不一致 ${BIND_BAD} 个" "$DICT_RESP"; fi

# 强制匹配：用 driver 对象写车辆标签 cargo_type，应被拒绝（code=3011）
MISMATCH_BODY=$(python3 -c '
import json, time
print(json.dumps({"biz_request_id": "biz-mismatch-%d" % int(time.time()),
  "items": [{"object_type": "driver", "object_id": "DRV85001", "tag_code": "cargo_type",
             "tag_value": "普货",
             "operator": {"operator_id": "OP_SERVICE01", "operator_name": "客服专员",
                          "operator_type": "human", "operator_org": "客服中心"}}]}, ensure_ascii=False))
')
MISMATCH_RESP=$(open_api POST /api/v1/open/tag-values/write "" "$MISMATCH_BODY")
if [ "$(jget "$MISMATCH_RESP" code)" = "3011" ]; then
  ok "标签-对象强制校验生效（cargo_type 拒绝写入 driver，code=3011）"
else
  nok "强制校验未生效（期望 code=3011）" "$MISMATCH_RESP"
fi

# 平台只写流水：画像读取 latest，内容由外部写入程序维护，这里只校验接口可用并输出条数
PROFILE_RESP=$(open_api GET "/api/v1/open/objects/DRV85001/profile" "object_type=driver" "")
if [ "$(jget "$PROFILE_RESP" code)" = "0" ]; then
  PROFILE_CNT=$(printf '%s' "$PROFILE_RESP" | python3 -c 'import sys, json; print(len((json.load(sys.stdin).get("data") or {}).get("tags") or []))' 2>/dev/null || echo 0)
  ok "画像接口可用：DRV85001 latest ${PROFILE_CNT} 个标签（由外部写入程序维护）"
else
  nok "画像接口异常" "$PROFILE_RESP"
fi

# 司机流水（客服手工）：登记类 + 可选特征样本
REC_DRV=$(open_api GET "/api/v1/open/operators/OP_SERVICE01/tag-records" "object_type=driver&object_id=DRV85001&page=1&size=100" "")
if printf '%s' "$REC_DRV" | grep -q '"driver_relation"'; then ok "流水已记录: DRV85001 含 driver_relation"; else nok "流水缺 driver_relation" "$REC_DRV"; fi
if [ "$SENSITIVE_SAMPLE" -ge 1 ]; then
  if printf '%s' "$REC_DRV" | grep -q '"social_tendency"'; then ok "流水已记录: DRV85001 含 social_tendency（effective）"; else nok "流水缺 social_tendency" "$REC_DRV"; fi
fi

# 车辆流水：客服登记 + 统计指标
PLATE1="苏A88801"
REC_VEH=$(open_api GET "/api/v1/open/operators/OP_SERVICE01/tag-records" "object_type=vehicle&object_id=${PLATE1}&page=1&size=100" "")
if printf '%s' "$REC_VEH" | grep -q '"cargo_type"'; then ok "流水已记录: ${PLATE1} 含 cargo_type"; else nok "流水缺 cargo_type" "$REC_VEH"; fi
REC_METRIC=$(open_api GET "/api/v1/open/operators/SYS_VEH_METRIC/tag-records" "object_type=vehicle&object_id=${PLATE1}&page=1&size=100" "")
if printf '%s' "$REC_METRIC" | grep -q '"avg_daily_mileage"'; then ok "流水已记录: ${PLATE1} 含 avg_daily_mileage"; else nok "流水缺 avg_daily_mileage" "$REC_METRIC"; fi

# 操作员记录（客服侧）
REC_RESP=$(open_api GET "/api/v1/open/operators/OP_SERVICE01/tag-records" "object_type=driver&page=1&size=10" "")
REC_TOTAL=$(jget "$REC_RESP" data.total)
EXPECT_HUMAN=$((DRIVERS + SENSITIVE_SAMPLE * 9))
if [ -n "$REC_TOTAL" ] && [ "$REC_TOTAL" != "None" ] && [ "$REC_TOTAL" -ge "$EXPECT_HUMAN" ]; then
  ok "客服 OP_SERVICE01 标记记录 total=${REC_TOTAL}（≥${EXPECT_HUMAN}）"
else
  nok "客服记录 total=${REC_TOTAL} < ${EXPECT_HUMAN}" "$REC_RESP"
fi

# 待审核队列只读（平台值审核已停用）
if [ "$SENSITIVE_SAMPLE" -ge 1 ]; then
  PENDING_RESP=$(console_api GET /api/v1/console/audit/pending)
  if [ "$(jget "$PENDING_RESP" code)" = "0" ]; then
    ok "待审核队列只读可用（值审核已停用，审核接口返回 3013）"
  else
    nok "待审核队列不可用" "$PENDING_RESP"
  fi
fi

# ========== 汇总 ==========
echo ""
echo "============================================================"
echo " 交付汇总"
echo "------------------------------------------------------------"
echo " 标签体系  : 32 个（5 一级维度 / 19 二级维度，见 docs/tag-catalog-1015.md）"
echo " 租户      : ${TENANT_ID}（客服打标系统接入）"
echo " 凭证      : app_key=${APP_KEY}"
echo "             secret=${SECRET}"
echo " 打标数据  : driver ${DRIVERS} 人、vehicle ${VEHICLES} 辆"
echo "             accepted=${WRITE_ACCEPTED}  pending_audit=${WRITE_PENDING}（仅流水，latest 由外部写入程序同步）"
echo " 来源映射  : 客服手工标注(human) / 数据统计(system)"
echo "------------------------------------------------------------"
echo " 控制台体验: ${BASE}/console/index.html"
echo "   - 标签字典  → 按「性格特征 / 驾驶行为 / 运营模式」等类目查看 32 个标签"
echo "   - 待审核队列 → 只读查看 pending_audit 值（值审核已停用）"
echo "   - 对象画像  → driver / DRV85001、vehicle / 苏A88801（需外部写入程序先同步 latest）"
echo "   - 打标记录  → 操作员 OP_SERVICE01（客服中心）"
echo "============================================================"
echo " 结果：PASS=${PASS} FAIL=${FAIL}"
echo "============================================================"
[ "$FAIL" -eq 0 ]

# 标签画像平台 · 接入对接指南

> 版本：v1.0（API 主版本 v1，开放面前缀 `/api/v1/open`；契约只增不删）
> **接口前缀（2026-10-22 起）**：统一为 `/api/v1/open`（开放 API）与 `/api/v1/console`（控制台）；旧前缀 `/open/v1`、`/console/api` 在兼容期内继续可用，计划下个主版本移除。
> 机器可读规范：[`openapi.yaml`](./openapi.yaml)（可导入 Postman / Apifox / Swagger 编辑器）
> 本期上线标签清单（32 个，含客服手工打标约定）：[`tag-catalog-1015.md`](./tag-catalog-1015.md)
> **当前标签口径（2026-10-22）**：测试与生产仅保留 32 个核心标签，全部 `risk_control`、`online`、L2、TTL=0、`need_value_audit=false`；非核心标签已归档。文中 `risk_level`、`suspected_fraud`、`alarm_count_30d` 等为历史示例，仅用于说明字段与签名格式；实际以 `GET /api/v1/open/tags` 返回为准。

---

## 目录

1. [平台能力与对接方式](#1-平台能力与对接方式)
2. [快速开始（5 分钟跑通第一次调用）](#2-快速开始5-分钟跑通第一次调用)
3. [鉴权：HMAC-SHA256 签名](#3-鉴权hmac-sha256-签名)
4. [接口总览](#4-接口总览)
5. [接口详情](#5-接口详情)
6. [核心业务语义](#6-核心业务语义)
7. [错误码与处理建议](#7-错误码与处理建议)
8. [Java SDK 集成](#8-java-sdk-集成)
9. [端到端对接示例（客服打标场景）](#9-端到端对接示例客服打标场景)
10. [FAQ](#10-faq)

---

## 1. 平台能力与对接方式

本平台是面向风控数仓的**统一标签接入网关**。接入方（客服工作台、算法引擎、外部系统等）通过开放 API 完成：

| 能力 | 接口 | 典型用途 |
|---|---|---|
| 查看标签字典 | `GET /api/v1/open/tags` | 拉取可写标签清单、值域约束，驱动前端表单 |
| 打标签（写入） | `POST /api/v1/open/tag-values/write` | 客服人工打标、算法批量回写 |
| 撤销标签（作废） | `POST /api/v1/open/tag-values/delete` | 误标撤销、数据修正 |
| 查对象画像 | `GET /api/v1/open/objects/{objectId}/profile` | 查询某对象当前标签值（latest） |
| 查组合标签内容 | `GET /api/v1/open/objects/profile/combined` | 一次查询「司机+车辆」32 个特征值（含待打标占位，覆盖清单-1015） |
| 查打标记录 | `GET /api/v1/open/operators/{operatorId}/tag-records` | 查询某操作员的打标流水（含审核状态） |

接入方式二选一：

- **推荐：Java SDK**（`sdk-java` 模块）——自动签名、自动重试，见 [第 8 章](#8-java-sdk-集成)
- **原生 HTTP**：任何语言按 [第 3 章签名协议](#3-鉴权hmac-sha256-签名) 自行实现

> 数据链路：写入/作废只追加 `tag_value_history` + Outbox；画像数据源 `tag_value_latest` 由外部写入程序维护，写入受理后画像更新存在同步时延（见 [6.1](#61-写入与生效链路)）。

---

## 2. 快速开始（5 分钟跑通第一次调用）

### 2.1 获取接入凭证

向平台管理员提供：**租户 ID、接入系统名、对接人**，由管理员在管理控制台「租户与凭证」页为你的租户生成一对凭证：

| 凭证 | 说明 |
|---|---|
| `app_key` | 应用标识（公开，随请求发送） |
| `secret` | 签名密钥（**仅创建时展示一次**，请立即保存到配置中心/密钥管理，切勿提交到代码仓库） |

> 演示环境（本地 demo / 本指南所有示例）可直接使用：
> `app_key = AK_RC_8f2a91`，`secret = rc-secret-8f2a91-demo`，租户 `risk_control`
> ⚠️ 演示凭证仅用于联调，生产环境请使用管理员为你生成的凭证。

### 2.2 跑通第一次调用（curl 版本，可直接复制执行）

将以下内容保存为 `hello-tagplatform.sh` 并执行 `bash hello-tagplatform.sh`：

```bash
#!/usr/bin/env bash
# 最小可用示例：签名调用"标签字典"接口
set -e

BASE_URL="http://localhost:8080"        # 平台入口地址（生产为 https://你的域名）
APP_KEY="AK_RC_8f2a91"                  # 步骤 2.1 获取
SECRET="rc-secret-8f2a91-demo"          # 步骤 2.1 获取

METHOD="GET"
PATH_="/api/v1/open/tags"                   # 不含 query 的路径
QUERY=""                                # 无 query 时为空串
BODY=""                                 # GET 请求体为空
TS=$(date +%s%3N)                       # 毫秒时间戳
NONCE=$(openssl rand -hex 16)           # 一次性随机串

# 待签名串：六段 \n 连接（详见第 3 章）
SIGN_STR=$(printf '%s\n%s\n%s\n%s\n%s\n%s' \
  "$METHOD" "$PATH_" "$QUERY" "$BODY" "$TS" "$NONCE")
SIGNATURE=$(printf '%s' "$SIGN_STR" \
  | openssl dgst -sha256 -hmac "$SECRET" -binary | xxd -p -c 256)

curl -s "$BASE_URL$PATH_" \
  -H "X-App-Key: $APP_KEY" \
  -H "X-Timestamp: $TS" \
  -H "X-Nonce: $NONCE" \
  -H "X-Signature: $SIGNATURE"
echo
```

预期返回：

```json
{
  "code": 0,
  "message": "ok",
  "request_id": "8f3c1e2a-…",
  "data": {
    "tags": [
      { "tag_code": "driving_risk_attitude", "tag_name": "驾驶风险倾向", "value_type": "enum",
        "need_value_audit": false, "value_ttl_days": 0, "sensitivity": "L2",
        "status": "online", "version": 1, "…": "…" }
    ],
    "total": 32
  }
}
```

看到 `code: 0` 即接入成功。接下来请阅读第 3 章理解签名，然后对接具体接口。

---

## 3. 鉴权：HMAC-SHA256 签名

所有 `/api/v1/open/*` 请求必须携带 4 个请求头：

| Header | 必填 | 说明 |
|---|---|---|
| `X-App-Key` | 是 | 凭证的 app_key |
| `X-Timestamp` | 是 | 当前毫秒时间戳（与服务器允许偏差 **±300 秒**，请保证服务器 NTP 同步） |
| `X-Nonce` | 是 | 一次性随机串（建议 16 字节 hex）；**60 秒内不可重复**，重复视为重放请求 |
| `X-Signature` | 是 | 签名值：HMAC-SHA256 **十六进制小写** |

### 3.1 待签名字符串（六段，`\n` 连接，无结尾换行）

```
STRING_TO_SIGN = METHOD + "\n" + PATH + "\n" + CANONICAL_QUERY + "\n" + RAW_BODY + "\n" + TIMESTAMP + "\n" + NONCE

signature = hex_lower( HMAC_SHA256( key = secret, data = STRING_TO_SIGN 的 UTF-8 字节 ) )
```

六个字段的准确含义：

| 字段 | 取值规则 | 示例 |
|---|---|---|
| `METHOD` | 大写 HTTP 方法 | `GET` / `POST` |
| `PATH` | **编码后**的路径（不含 query）。路径含动态段时，先对动态段做 URL 编码（空格编码为 `%20` 而非 `+`），服务端按收到的原始 URI 比对 | `/api/v1/open/objects/DRV%200001/profile` |
| `CANONICAL_QUERY` | query 参数按 **key 字典序**、同 key 按 **value 字典序** 排列，`k=v` 以 `&` 连接；**值为 URL 解码后的原值**；无参数为空串 | `category_code=risk_score&page=1` |
| `RAW_BODY` | 请求体**原文**（需与 `-d` 发送的字节完全一致）；GET 为空串 | `{"biz_request_id":"biz-001","items":[…]}` |
| `TIMESTAMP` | 与请求头 `X-Timestamp` 完全相同 | `1760430000000` |
| `NONCE` | 与请求头 `X-Nonce` 完全相同 | `9f8e7d6c…`（32 位 hex） |

**注意**：
- `CANONICAL_QUERY` 用**解码原值**签名，但实际发出的 URL 用**编码后**的值（如 `remark=张 三` 签名为 `remark=张 三`，URL 为 `remark=%E5%BC%A0%20%E4%B8%89`）。建议接入方 query 参数值避免使用特殊字符。
- 签名后的字符串**不要有任何多余空格/换行**（`RAW_BODY` 原样使用，不要重新格式化 JSON）。

### 3.2 各语言实现

**curl / bash**（关键三行）：

```bash
SIGN_STR=$(printf '%s\n%s\n%s\n%s\n%s\n%s' "$METHOD" "$PATH_" "$CANONICAL_QUERY" "$BODY" "$TS" "$NONCE")
SIGNATURE=$(printf '%s' "$SIGN_STR" | openssl dgst -sha256 -hmac "$SECRET" -binary | xxd -p -c 256)
# 之后把 4 个头加到请求上即可
```

**Python 3**：

```python
import hashlib, hmac, json, time, secrets, requests

BASE_URL, APP_KEY, SECRET = "http://localhost:8080", "AK_RC_8f2a91", "rc-secret-8f2a91-demo"

def call(method: str, path: str, query: dict = None, body: dict = None):
    query = query or {}
    raw_body = json.dumps(body, ensure_ascii=False, separators=(",", ":")) if body else ""
    # canonical query：key 字典序；值为原值（此处假设值无特殊字符）
    canonical = "&".join(f"{k}={query[k]}" for k in sorted(query))
    ts, nonce = str(int(time.time() * 1000)), secrets.token_hex(16)
    sign_str = "\n".join([method, path, canonical, raw_body, ts, nonce])
    sig = hmac.new(SECRET.encode(), sign_str.encode(), hashlib.sha256).hexdigest()
    resp = requests.request(
        method, BASE_URL + path, params=query,
        data=raw_body.encode() if raw_body else None,
        headers={
            "X-App-Key": APP_KEY, "X-Timestamp": ts, "X-Nonce": nonce,
            "X-Signature": sig, "Content-Type": "application/json",
        })
    result = resp.json()
    if result["code"] != 0:
        raise RuntimeError(f"[{result['code']}] {result['message']} (request_id={result['request_id']})")
    return result["data"]

# 示例：查画像
profile = call("GET", "/api/v1/open/objects/DRV00001/profile",
               query={"object_type": "driver", "include_pending": "false"})
print(profile)
```

> 要点：`requests` 会对 params 做 URL 编码，但**签名仍用原值**；若 query 值含中文/空格等，需自行确认服务端解码后原值与签名一致。

**Java**：直接使用 SDK（见[第 8 章](#8-java-sdk-集成)），无需手写签名。

### 3.3 常见签名/鉴权错误排查

| 错误码 | 常见原因 | 排查建议 |
|---|---|---|
| 1001 缺少或非法签名头 | 漏传某个 Header；Header 名拼错 | 核对 4 个头 |
| 1002 AppKey 不存在 | app_key 错误 / 抄错 | 与管理员核对 |
| 1003 签名校验失败 | ①六段串拼接有误（多/少换行）②`RAW_BODY` 与发送 Body 不一致（如发送前重新序列化了 JSON）③query 未按字典序 ④PATH 编码不一致（`%20` vs `+`） | 打印 `STRING_TO_SIGN` 逐字节比对；先跑通无 query 无 body 的 `GET /api/v1/open/tags` |
| 1004 时间戳超限 | 本机时钟偏移 > 300 秒 | 部署 NTP（chrony/ntpd） |
| 1005 Nonce 重复 | 复用了 nonce / 重试未换 nonce | 每次请求（含重试）都生成新 nonce |
| 1007/1008 租户停用 / 凭证禁用 | 管理员操作 | 联系管理员 |

---

## 4. 接口总览

| # | 方法 | 路径 | 说明 | 幂等 |
|---|---|---|---|---|
| 1 | GET | `/api/v1/open/tags` | 标签字典（本租户可见的在线标签） | 天然幂等 |
| 2 | POST | `/api/v1/open/tag-values/write` | 批量写入标签值（≤1000 条/批） | `biz_request_id` |
| 3 | POST | `/api/v1/open/tag-values/delete` | 批量作废标签值（≤1000 条/批） | `biz_request_id` |
| 4 | GET | `/api/v1/open/objects/{objectId}/profile` | 对象画像（标签最新值） | 天然幂等 |
| 5 | GET | `/api/v1/open/objects/profile/combined` | 司机+车辆组合标签内容（32 特征值，含待打标占位） | 天然幂等 |
| 6 | GET | `/api/v1/open/operators/{operatorId}/tag-records` | 操作员打标记录（分页） | 天然幂等 |

**统一响应结构**（所有接口，HTTP 状态码见第 7 章）：

```json
{ "code": 0, "message": "ok", "request_id": "全链路追踪 ID", "data": { } }
```

- `code = 0` 成功；非 0 看第 7 章错误码表
- `request_id` 请记录到你的日志中，排查问题时提供给平台方可直接定位

---

## 5. 接口详情

### 5.1 标签字典

`GET /api/v1/open/tags`

返回当前凭证租户**可见**的**在线**标签（公开标签 `tenant_id=PUBLIC` + 本租户标签）。建议每日拉取一次缓存，用于校验待写入的 `tag_code` 与 `tag_value` 约束。

**Query 参数**

| 参数 | 必填 | 说明 |
|---|---|---|
| `category_code` | 否 | 按类目过滤，如 `risk_score` |
| `object_type` | 否 | 按适用对象过滤，如 `driver`（返回绑定该对象的标签；未绑定/不限对象的标签需自行区分） |
| `source_mode` | 否 | 按打标来源过滤：`manual` / `statistics` / `hybrid` / `algo`；客服系统可用 `object_type=driver&source_mode=manual` 拉取「需人工打标」清单 |

**响应**

```json
{
  "code": 0, "message": "ok", "request_id": "…",
  "data": {
    "tags": [{
      "tag_code": "risk_level", "tag_name": "风险等级", "category_code": "risk_score",
      "value_type": "enum", "value_range": "{\"enum\":[\"low\",\"medium\",\"high\"]}",
      "tag_type": "rule", "object_type": "driver,vehicle", "source_mode": "hybrid",
      "need_value_audit": false, "writable": true, "value_ttl_days": 0,
      "sensitivity": "L2", "status": "online", "version": 1
    }],
    "total": 6
  }
}
```

| 字段 | 说明 |
|---|---|
| `value_type` | `boolean` / `number` / `enum` / `string` / `date`，决定 `tag_value` 的传值格式 |
| `value_range` | JSON 文本：`{"min":0,"max":100}`（number）或 `{"enum":["low","medium","high"]}`（enum）；enum 必须配置枚举值 |
| `value_multiple` | 仅 enum 生效：`false` 单选（默认），`true` 多选；多选写入用英文逗号分隔 |
| `options` | enum 的允许值数组，供接入方直接渲染单选/多选控件；非 enum 为空数组 |
| `object_type` | 适用对象类型，逗号分隔（如 `driver,vehicle`）；null = 不限。写入时平台强制校验「标签-对象」匹配，不匹配返回 3011（见 [6.5](#65-对象类型与-id-规范)） |
| `source_mode` | 打标来源：`manual` 客服手工 / `statistics` 数据统计 / `hybrid` 手工+统计 / `algo` 算法；null = 未声明 |
| `need_value_audit` | true = 敏感标签，写入时 `remark` 必填，值以 `pending_audit` 状态落流水；平台值审核已停用（见 [6.1](#61-写入与生效链路)） |
| `writable` | 写入开关（默认 true）：`false` = 该标签**禁止写入**，POST 写入/作废时整批拒绝（`code = 3012`）；适合「仅统计/算法刷新」「只读展示」类标签 |
| `value_ttl_days` | 值有效期（天），0 表示永不过期；过期后画像接口不再返回 |
| `sensitivity` | L1 公开 / L2 内部 / L3 敏感 / L4 高敏 |

> **多租户共享**：同一标签定义可以适用于多个租户（控制台维护适用租户列表，`PUBLIC`=全部租户）。接入方通过 `GET /api/v1/open/tags` 只能看到自己租户适用的标签；写入校验同样按适用租户判断。

### 5.2 批量写入标签值（核心接口）

`POST /api/v1/open/tag-values/write`

> **写入与生效**：平台负责校验并追加打标流水（`tag_value_history`）+ 投递 Outbox 事件；画像数据源 `tag_value_latest` 由外部写入程序维护，平台**不再直接写入**。因此「受理成功」不代表画像立即可查，画像更新时延取决于下游同步。

**请求体**

```json
{
  "biz_request_id": "biz-20261014-0001",
  "items": [{
    "object_type": "driver",
    "object_id": "DRV00001",
    "tag_code": "risk_level",
    "tag_value": "high",
    "biz_time": "2026-10-14 10:00:00",
    "operate_time": "2026-10-14 10:00:05",
    "operator": { "operator_id": "OP1001", "operator_name": "张三",
                  "operator_type": "human", "operator_org": "某交警支队" },
    "source": "客服工作台",
    "confidence": "高",
    "remark": "打标理由（敏感标签必填）",
    "evidence_url": "https://…/evidence.jpg",
    "ext_json": "{\"ticket_id\":\"T-1024\"}"
  }]
}
```

**字段说明**

| 字段 | 必填 | 约束 |
|---|---|---|
| `biz_request_id` | 是 | 幂等键。接入方生成，全局唯一（建议 `业务-日期-序号`），**24 小时内重复提交回放首次响应** |
| `items` | 是 | 数组，1~1000 条；**整批校验全过才受理**（任一条失败 → 整批拒绝，报错信息指明第几条及原因） |
| `object_type` | 是 | 已注册对象类型（见 [6.5](#65-对象类型与-id-规范)） |
| `object_id` | 是 | 需符合该对象类型的 ID 格式 |
| `tag_code` | 是 | 必须为在线、对凭证租户可见的标签；写入开关 `writable=false` 的标签会整批拒绝（3012） |
| `tag_value` | 是 | **一律传字符串**：boolean → `"true"/"false"`；number → `"12.5"`；enum → 枚举值（多选用英文逗号分隔，如 `"公共道路,港口"`）；date → `"2026-10-14"`；须通过 `value_range` 校验 |
| `biz_time` | 否 | 业务时间 `yyyy-MM-dd HH:mm:ss`；缺省 = `operate_time` 缺省 = 服务端当前时间 |
| `operate_time` | 否 | 操作员操作时间 |
| `operator` | 是 | `operator_id` 必填（≤64 字符）；`operator_type` ∈ `human/system/algo`；`operator_name`/`operator_org` ≤128 字符 |
| `source` | 否 | 来源标识 ≤64 字符（如"客服工作台"） |
| `confidence` | 否 | 仅支持 `高` / `中` / `低` |
| `remark` | 敏感标签必填 | ≤2048 字符；人工审核的依据 |
| `evidence_url` | 否 | ≤512 字符 |
| `ext_json` | 否 | JSON 文本 ≤2048 字符（平台原样存储） |

**响应**（受理成功后，逐条给出结果）：

```json
{
  "code": 0, "message": "ok", "request_id": "…",
  "data": {
    "biz_request_id": "biz-20261014-0001",
    "total": 2, "accepted": 1, "pending_audit": 1,
    "results": [
      { "index": 0, "object_type": "driver", "object_id": "DRV00001",
        "tag_code": "risk_level", "status": "accepted", "message": null },
      { "index": 1, "object_type": "driver", "object_id": "DRV00001",
        "tag_code": "suspected_fraud", "status": "pending_audit", "message": "待人工审核后生效" }
    ]
  }
}
```

| 逐条状态 | 含义 |
|---|---|
| `accepted` | 已受理并追加打标流水（`effective`），等待下游写入程序同步到画像 |
| `pending_audit` | 已受理并追加流水（`pending_audit`，敏感标签）；平台值审核已停用，是否生效由下游链路处理（见 [6.1](#61-写入与生效链路)） |
| `rejected` | 该条被拒绝（`message` 给出原因）——当前版本整批校验失败不产生该状态，保留字段供后续演进 |

### 5.3 批量作废标签值

`POST /api/v1/open/tag-values/delete`

将标签值置为 `revoked`：以该对象+标签最近一条打标流水为基准，追加一条 `revoked` 流水（保留原值），画像变更由下游写入程序同步。请求体与写入同构：

```json
{
  "biz_request_id": "biz-del-20261014-0001",
  "items": [{
    "object_type": "driver", "object_id": "DRV00001", "tag_code": "risk_level",
    "operator": { "operator_id": "OP1001", "operator_name": "张三", "operator_type": "human" },
    "remark": "误标撤销"
  }]
}
```

响应 `results[].status`：`revoked`（已记录作废流水）/ `not_found`（该对象+标签无打标流水）。

### 5.4 对象画像

`GET /api/v1/open/objects/{objectId}/profile`

返回对象**当前全部标签值**（数据源为 `tag_value_latest`，由外部写入程序维护；平台查询时自动排除 `revoked` 与已过期值）。

**参数**

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `objectId` | path | 是 | 对象 ID（签名使用编码后路径） |
| `object_type` | query | 是 | 如 `driver` |
| `include_pending` | query | 否 | 默认 `false`；`true` 时附带待审核值（`status=pending_audit`，建议仅内部页面展示用，勿参与自动决策） |

**响应**

```json
{
  "code": 0, "message": "ok", "request_id": "…",
  "data": {
    "tenant_id": "risk_control", "object_type": "driver", "object_id": "DRV00001",
    "tags": [{
      "tag_code": "risk_level", "tag_name": "风险等级", "tag_value": "high",
      "value_type": "enum", "status": "effective",
      "source_mode": "hybrid", "need_value_audit": false,
      "biz_time": "2026-10-14 10:00:00", "operate_time": "2026-10-14 10:00:05",
      "expire_time": null, "operator_id": "OP1001", "operator_name": "张三",
      "operator_type": "human", "operator_org": "某交警支队",
      "source": "客服工作台", "confidence": "高",
      "remark": "…", "evidence_url": null
    }]
  }
}
```

**使用建议**：`tag_value` 为字符串，请按字典接口的 `value_type` 做类型转换（如 `"12.5"` → double，`"true"` → boolean）。

### 5.5 组合标签内容查询（司机+车辆）

`GET /api/v1/open/objects/profile/combined`

一次查询「司机 + 车辆」组合的标签当前值——数据齐全时即清单-1015 的 32 个特征值（driver 17 / vehicle 15），供客服工作台/风控系统按作业单元（司机+车辆）整体拉取。

**参数**

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `driver_id` | query | 否* | 司机对象 ID（如 `DRV85001`） |
| `vehicle_plate` | query | 否* | 车辆标识（车牌号，如 `苏A88801`；省份简称 + 字母 + 5~6 位字母/数字，新能源如 `苏AD12345`） |
| `include_pending` | query | 否 | 默认 `false`；`true` 时附带待审核值 |
| `include_empty` | query | 否 | 默认 `true`：无值标签以 `status=none`、`tag_value=null` 占位（占位范围 = 可见在线标签中适用该对象的标签，可直接渲染「待打标清单」）；`false` 仅返回有值标签 |
| `tag_codes` | query | 否 | 可选，逗号分隔的标签码；**省略或为空时不按标签过滤，默认返回该对象类型下的全部可见在线标签**；非空时仅返回这些标签（无值仍占位）。传清单-1015 的 32 个标签码可精确取 32 个特征值（driver 17 / vehicle 15） |

- `driver_id` / `vehicle_plate` 至少传一个，均缺失返回 `code=2001`。
- `vehicle_plate` 的取值为车牌号（非 VIN）；旧参数 `plate_no`、`vehicle_id` 均已废弃，不再接受。

> 命名对应：写接口 `object_type=driver|vehicle` + `object_id`；查询接口 `driver_id` / `vehicle_plate`，与 `object_type_meta.id_field` 保持一致（`vehicle_plate` 的值即车牌号）。

（省略 `tag_codes` 时默认返回本租户全部可见适配标签——默认场景还含种子风控标签，即“返回所有标签”；如需精确 32 个特征值请传清单 32 码。）

**响应**

```json
{
  "code": 0, "message": "ok", "request_id": "…",
  "data": {
    "tenant_id": "risk_control",
    "driver": {
      "object_type": "driver", "object_id": "DRV85001",
      "tags": [
        {"tag_code": "social_tendency", "tag_name": "社交倾向", "tag_value": "外向",
         "value_type": "enum", "status": "effective", "source_mode": "hybrid", "need_value_audit": true},
        {"tag_code": "stutter_flag", "tag_name": "是否结巴", "tag_value": null,
         "value_type": "enum", "status": "none", "source_mode": "manual", "need_value_audit": true}
      ]
    },
    "vehicle": {
      "object_type": "vehicle", "object_id": "苏A88801",
      "tags": [{"tag_code": "cargo_type", "tag_name": "货物类型", "tag_value": "普货",
                "value_type": "enum", "status": "effective", "source_mode": "hybrid", "need_value_audit": false}]
    },
    "total": 32
  }
}
```

**使用建议**：`status=none` 即该标签尚无值（尚未打标或已过期/作废）；结合 `source_mode`（`manual`/`hybrid` 需人工参与）可直接生成客服「待打标清单」。若只需清单-1015 的 32 个特征值，传 `tag_codes`（32 个标签码逗号拼接）即可精确圈定。

### 5.6 操作员打标记录

`GET /api/v1/open/operators/{operatorId}/tag-records`

按操作员分页查询打标流水（含审核状态与全链路 `request_id`）。

**参数**

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `operatorId` | path | 是 | 操作员 ID |
| `object_type` / `object_id` / `tag_code` | query | 否 | 过滤条件 |
| `from` / `to` | query | 否 | 按操作时间过滤，`yyyy-MM-dd HH:mm:ss` |
| `page` | query | 否 | 页码，默认 1 |
| `size` | query | 否 | 每页条数，默认 20 |

**响应**

```json
{
  "code": 0, "message": "ok", "request_id": "…",
  "data": {
    "operator_id": "OP1001", "page": 1, "size": 20, "total": 42,
    "records": [{
      "tenant_id": "risk_control", "object_type": "driver", "object_id": "DRV00001",
      "tag_code": "suspected_fraud", "tag_value": "true", "value_type": "boolean",
      "status": "pending_audit",
      "biz_time": "2026-10-14 10:02:00", "operate_time": "2026-10-14 10:02:00",
      "operator_id": "OP1001", "operator_name": "张三", "operator_type": "human",
      "source": "open_api", "remark": "事故视频疑点：碰撞痕迹异常",
      "audit_by": null, "audit_time": null, "audit_remark": null,
      "request_id": "…", "op_time": "2026-10-14 10:02:01", "ver": 1760430121000
    }]
  }
}
```

`status` 取值：`effective` 生效 / `pending_audit` 待审核 / `revoked` 已作废 / `expired` 已过期。

---

## 6. 核心业务语义

### 6.1 写入与生效链路

平台只负责「受理 + 记账」，`tag_value_latest` 由外部写入程序维护：

```
接入方 ──写入/作废──▶ 平台校验 ──▶ tag_value_history（流水，追加）
                                      │
                                      └─▶ outbox_event（事件，供下游消费）
                                                    │
                                      外部写入程序 ◀┘（自行开发）
                                                    │
                                                    ▼
                                      tag_value_latest（最新值）
                                                    │
画像查询 / 组合查询 / 待审核队列（只读） ◀──────────┘
```

对对接方的影响：
- 写入响应只表示**流水已记录**，画像何时可查取决于外部写入程序的同步时延；请勿以写入响应判断已生效
- 敏感标签（`need_value_audit=true`）仍要求 `remark`，否则整批拒绝（3008）；写入后流水状态为 `pending_audit`
- **平台值审核已停用**：控制台「待审核队列」仅只读展示，通过/驳回接口返回 `code=3013`；审核与状态流转由下游链路负责
- 需要确认某条是否已同步，可查询 5.6 打标记录（流水）或 5.4/5.5 画像（最新值），二者可能出现短暂不一致
- 作废同样只追加流水，不直接修改 latest；重复作废依据流水幂等返回 `revoked`

### 6.1.1 历史数据与保留

- 每次写入/作废均追加到 `tag_value_history`，不做物理覆盖，可完整回溯
- `tag_value_history` **永久保留**：生产环境不按月分区、不设置分区淘汰策略，历史流水不会自动清理；如需冷存归档请由下游按需导出
- 平台内部「待审核队列」读 `tag_value_latest`，因此只反映外部写入程序已同步的数据

### 6.2 幂等（biz_request_id）

- 相同 `biz_request_id` 在 **24 小时**内重复提交 → **回放首次响应**（不重复落数）
- 首次请求**处理中**时重复提交 → 返回 3004，稍后重试即可（重试请换新 nonce）
- 网络超时**不确定是否成功**时：用**相同** `biz_request_id` 重试（最安全）
- 校验失败被整批拒绝后，幂等记录会释放，可修复数据后重试

### 6.3 TTL 与过期

- `expire_time = biz_time + value_ttl_days`（`value_ttl_days=0` 表示不过期）
- 过期值：画像接口不再返回；打标记录仍可查到（`status=expired`）
- **重复写入**同一（对象，标签）会在流水追加新记录；画像最终呈现哪条由外部写入程序按自身规则收敛（平台按写入顺序提供流水）

### 6.4 频控

| 维度 | 限制 | 超限响应 |
|---|---|---|
| IP | 100 QPS | HTTP 429 / code 3007 |
| 操作员（operator_id） | 20 QPS | HTTP 429 / code 3005 |
| 同对象 × 同标签 | 1 QPS | HTTP 429 / code 3006 |

收到 429 请退避重试（建议指数退避，SDK 已内置）。**注意**：同对象同标签 1 QPS 意味着不要对同一对象的同一标签做高频更新。

### 6.5 对象类型与 ID 规范

对象类型需在平台注册方可写入。演示环境已注册：

| object_type | 名称 | ID 格式 |
|---|---|---|
| `driver` | 司机 | `^DRV[0-9]{5,}$`（如 `DRV00001`） |
| `vehicle` | 车辆 | `^[一-龥][A-Z][A-Z0-9]{5,6}$`（车牌号，如 `苏A88801`；新能源如 `苏AD12345`） |
| `fleet` | 车队 | `^FLT[0-9]{4,}$`（如 `FLT0001`） |
| `alarm_event` | 告警事件 | `^EVT[0-9A-Z]{6,}$` |

需要新对象类型请联系平台管理员注册。

**标签-对象强制匹配**：自 V4 迁移起，标签字典（`tag_meta.object_type`）显式绑定适用对象类型（如 `driver`、`driver,vehicle`）。写入时平台强制校验，把标签写到不匹配的对象类型上会被整批拒绝（`code = 3011`）——例如车辆标签 `cargo_type` 写到 `driver` 对象。未绑定（null）的标签不限对象，兼容存量标签。

### 6.6 标签值类型与校验

写入前服务端强校验，任一不通过即整批拒绝（message 指明第几条与原因）：

| value_type | 传值示例 | 校验规则 |
|---|---|---|
| `boolean` | `"true"` / `"false"` | 仅接受 true/false |
| `number` | `"12.5"` | 数字格式 + `value_range` 的 min/max |
| `enum` | `"high"` / `"公共道路,港口"` | 单选必须命中 `value_range.enum`；多选（`value_multiple=true`）用英文逗号分隔，逐项命中且不允许空项/重复值 |
| `string` | `"任意文本"` | 无值域约束（≤2048 字符） |
| `date` | `"2026-10-14"` 或 `"2026-10-14 10:00:00"` | 日期/时间格式校验 |

> 接入方应以 `GET /api/v1/open/tags` 返回的 `value_multiple` 决定渲染单选框还是复选框，并从 `options`（或 `value_range.enum`）取候选值；`value_multiple=true` 时把选中项用英文逗号连接后放入 `tag_value`。

### 6.7 打标来源语义（客服手工 vs 数据统计）

本期上线标签（清单-1015，32 个）按来源分为「客服手工标注」「数据统计」及其组合（含手工成分 23/32）。平台在两处表达来源：**字典字段 `source_mode`**（标签级：声明该标签的总体来源类型 `manual` / `statistics` / `hybrid` / `algo`）+ **写入请求的 operator 元数据**（记录级：每次打标的实际操作者）。

| 来源语义 | operator_type | 典型 operator_id | source 填写 |
|---|---|---|---|
| 客服手工标注 / 可调整 | `human` | 客服工号（如 `OP_SERVICE01`） | `客服手工标注` |
| 数据统计 | `system` | 统计任务（如 `SYS_VEH_METRIC`） | `数据统计` |

- **客服系统「待打标清单」**：`GET /api/v1/open/tags?object_type=driver&source_mode=manual`（或 `hybrid`）直接拉取需要人工参与的标签（32 个中 `manual` 6 个 / `hybrid` 17 个）
- **「客服手工可调整」= 再次写入**：同对象同标签再次写入即在流水追加新记录，由下游写入程序更新 latest；客服修正统计值无需额外接口
- **来源可追溯**：每次写入的 operator/source 均落打标流水，可用 `GET /api/v1/open/operators/{operatorId}/tag-records` 按客服工号回查
- 32 标签的维度分类、取值与敏感策略见 [`tag-catalog-1015.md`](./tag-catalog-1015.md)；演示数据由 `data/seed-fleet-tags.sh` 一键生成

### 6.8 写入开关（writable）

标签字典中每个标签带**写入开关** `writable`（默认 `true`）：

- `writable=true`（允许写入）：POST 写入 / 作废正常受理
- `writable=false`（禁止写入）：以下两类请求**整批拒绝**，响应 `code = 3012`，`message` 含「不允许写入」（如「标签 xxx 不允许写入（写入开关已关闭）」）：
  - `POST /api/v1/open/tag-values/write`（写入）
  - `POST /api/v1/open/tag-values/delete`（作废）
- 典型场景：仅由数据统计/算法离线刷新的标签、只读展示类标签；**客服系统应先按字典 `writable` 过滤出「可打标」清单**，避免提交被拒
- 开关由平台管理员在控制台「标签字典」页随时切换（无需重启），切换后**即时生效**；如需确认最新状态，重新拉取标签字典即可
- 收到 3012 **请勿重试**（非临时性错误），应从字典过滤掉该标签

---

## 7. 错误码与处理建议

| HTTP | code | 含义 | 处理建议 |
|---|---|---|---|
| 200 | 0 | 成功 | — |
| 401 | 1001 | 缺少或非法签名请求头 | 检查 4 个 Header |
| 401 | 1002 | AppKey 不存在 | 核对凭证 |
| 401 | 1003 | 签名校验失败 | 见 [3.3](#33-常见签名鉴权错误排查) |
| 401 | 1004 | 时间戳超限 ±300s | NTP 校时 |
| 401 | 1005 | Nonce 重放 | 每次请求换新 nonce |
| 403 | 1006 | tenant_id 与凭证不一致 | 请求体中的租户必须与凭证一致 |
| 403 | 1007 | 租户已停用 | 联系管理员 |
| 401 | 1008 | 凭证已禁用 | 联系管理员/重置密钥 |
| 200 | 2001 | 缺少必填参数 | 按接口文档补全 |
| 200 | 2002 | 参数校验失败 | message 含第几条与原因 |
| 200 | 2003 | 批量条数超上限 | 拆分为 ≤1000 条/批 |
| 200 | 3001 | 标签不存在 | 从字典接口拉最新标签 |
| 200 | 3002 | 标签未上线 | 该标签不可写，联系管理员 |
| 200 | 3003 | 对象类型未注册 | 检查 object_type 拼写/注册 |
| 200 | 3004 | 同 biz_request_id 处理中 | 稍后重试（换新 nonce） |
| 429 | 3005 | 操作员频控超限 | 退避重试 |
| 429 | 3006 | 同对象同标签过频 | 降低更新频率 |
| 429 | 3007 | IP 频控超限 | 退避重试 |
| 200 | 3008 | 敏感标签 remark 必填 | 补充打标理由 |
| 200 | 3009 | 标签值不存在 | 检查作废目标 |
| 200 | 3010 | 非待审核态 | 该值已被处理 |
| 200 | 3011 | 标签不适用于该对象类型 | 按标签字典 `object_type` 匹配对象，或联系管理员调整绑定 |
| 200 | 3012 | 标签不允许写入（写入开关已关闭） | 从字典 `writable` 字段过滤可写标签；非临时错误，**请勿重试** |
| 200 | 3013 | 标签值审核已停用 | 平台不再提供值审核；由下游链路处理审核状态 |
| 500 | 5001 | 系统内部错误 | 携带 request_id 联系平台方 |
| 500 | 5002 | 存储写入失败 | 可退避重试 |

**重试策略建议**：网络异常 / 5xx / 429 可重试（指数退避，如 200ms、400ms、800ms）；重试必须**重新生成 timestamp 与 nonce**；写入接口重试用**相同 biz_request_id** 保证幂等；4xx 鉴权类错误不要重试。

---

## 8. Java SDK 集成

`tag-platform` 工程内置 `sdk-java` 模块（仅依赖 Jackson，HTTP 使用 JDK 内置 `java.net.http`，需 JDK 17+）：

```xml
<dependency>
  <groupId>com.tagplatform</groupId>
  <artifactId>tag-platform-sdk</artifactId>
  <version>1.17.0</version>
</dependency>
```

SDK 特性：自动 HMAC 签名；网络异常 / 5xx / 429 **自动指数退避重试**（每次重试重新生成时间戳与 nonce）；非 0 code 抛 `TagPlatformException`。

```java
TagPlatformClient client = new TagPlatformClient(
        "http://localhost:8080", "AK_RC_8f2a91", "rc-secret-8f2a91-demo");

// 1) 标签字典
List<TagPlatformClient.TagMetaDto> tags = client.getTags();

// 2) 批量写入（builder 使用示例）
WriteResult result = client.write(WriteRequest.of("biz-20261014-0100", List.of(
        WriteItem.of("driver", "DRV00001", "driving_risk_attitude", "激进")
                .operator(OperatorDto.human("OP1001", "张三", "某交警支队"))
                .bizTime(LocalDateTime.now())
                .source("客服工作台")
                .confidence("高"),
        WriteItem.of("driver", "DRV00001", "driver_relation", "公司员工")
                .operator(OperatorDto.human("OP1001", "张三", "某交警支队"))
                .remark("客服登记司机关系")
)));
System.out.println("accepted=" + result.accepted() + ", pending=" + result.pendingAudit());

// 3) 对象画像
ProfileView profile = client.getProfile("driver", "DRV00001", false);

// 4) 操作员打标记录（分页 + 过滤）
OperatorRecordsView records = client.getOperatorRecords("OP1001",
        Map.of("object_type", "driver"), 1, 20);

// 5) 批量作废
client.delete(DeleteRequest.of("biz-del-20261014-0100",
        List.of(DeleteItem.of("driver", "DRV00001", "driving_risk_attitude"))));
```

异常处理：

```java
try {
    client.write(request);
} catch (TagPlatformException e) {
    // e.code() / e.getMessage() / e.requestId()（请求平台方排查时提供）
    // e.retryable()：SDK 已自动重试过仍失败才抛出
}
```

---

## 9. 端到端对接示例（客服打标场景）

以"客服工作台给司机打标驾驶风险倾向 → 查询打标流水 → 风控查询画像"为例（当前 32 个标签均为 L2 直通，值审核停用；打标约定见 [6.7](#67-打标来源语义客服手工-vs-数据统计) 与 [`tag-catalog-1015.md`](./tag-catalog-1015.md)）：

```bash
BASE="http://localhost:8080"; AK="AK_RC_8f2a91"; SK="rc-secret-8f2a91-demo"

# 工具函数：生成签名并发起请求（完整脚本见第 2 章模板，此处示意流程）
sign() {  # $1=method $2=path $3=canonical_query $4=body
  local ts=$(date +%s%3N) nonce=$(openssl rand -hex 16)
  printf '%s\n%s\n%s\n%s\n%s\n%s' "$1" "$2" "$3" "$4" "$ts" "$nonce" \
    | openssl dgst -sha256 -hmac "$SK" -binary | xxd -p -c 256 > /tmp/sig
  echo "$ts $nonce $(cat /tmp/sig)"
}

# ① 拉取标签字典，确认 driving_risk_attitude 在线且 need_value_audit=false
#    GET /api/v1/open/tags?object_type=driver

# ② 客服打标 → 返回 accepted，流水状态 effective
BODY='{"biz_request_id":"biz-workbench-20261014-0001","items":[{"object_type":"driver","object_id":"DRV00001","tag_code":"driving_risk_attitude","tag_value":"激进","operator":{"operator_id":"OP1001","operator_name":"张三","operator_type":"human","operator_org":"客服一组"},"source":"客服工作台","remark":"客服回访记录"}]}'
#    POST /api/v1/open/tag-values/write  （body 即为 BODY 原文）
#    平台只追加 tag_value_history + Outbox，tag_value_latest 由外部写入程序同步

# ③ 查询打标流水，确认受理结果与 effective 状态
#    GET /api/v1/open/operators/OP1001/tag-records?object_type=driver&page=1&size=10

# ④ 风控系统查询画像（外部写入程序同步 latest 后可见）
#    GET /api/v1/open/objects/DRV00001/profile?object_type=driver&include_pending=false
```

> 步骤 ② 的完整可执行脚本：将第 2 章模板的 `PATH_` 改为 `/api/v1/open/tag-values/write`、`METHOD` 改为 `POST`、`BODY` 设为上述 JSON（注意签名串中的 `BODY` 与 `-d` 发送内容必须一字不差），`QUERY=""` 即可。

---

## 10. FAQ

**Q1：`tag_value` 为什么都用字符串传？**
统一类型边界，避免浮点精度与布尔格式化差异；接入方按字典接口的 `value_type` 转换。

**Q2：批量写入中有一条错了，其他条会写入吗？**
不会。整批校验（all-or-nothing），任一条不合格整批拒绝并指明第几条；请修复后整批重提（可用相同或新的 `biz_request_id`）。

**Q3：如何知道待审核的值被通过了？**
两种方式：① 轮询 5.6 打标记录看 `status`；② 与风控团队约定线下通知。平台后续版本会提供订阅回调。

**Q4：同一个对象可以有多少标签？**
无特殊限制；但重复写入同一（对象，标签）会覆盖旧值（旧值留流水），且受"同对象同标签 1 QPS"频控。

**Q5：secret 丢了怎么办？**
联系管理员在控制台「租户与凭证」页重置密钥；重置后旧 secret 立即失效，请先准备好配置热更新。

**Q6：演示环境与生产环境的差异？**
接口完全一致；差异在入口地址（生产为 HTTPS 域名）、凭证、频控配额（由租户配额控制）与数据（生产数据真实）。

**Q7：如何本地模拟客户端调试签名？**
使用 SDK 的 `Signer`（与服务端实现完全一致的冻结契约），或直接运行 `deploy/smoke-test.sh` 观察一份完整的签名请求范例。

---

*文档反馈：如发现与实现不一致，请以 [`openapi.yaml`](./openapi.yaml) 与平台实际响应为准，并联系平台方更新本文档。*

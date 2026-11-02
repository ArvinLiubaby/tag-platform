# 标签推送 / 标签查询 接口文档

> 适用版本：V9（2026-10-19，运行口径更新于 2026-10-22）  
> 适用接口：标签值批量推送、司机+车辆组合标签查询  
> 配套资料：[接入对接指南](integration-guide.md) · [OpenAPI 定义](openapi.yaml) · [Postman 集合](postman-32-tags.json)
>
> **当前标签口径（2026-10-22）**：测试与生产仅保留 32 个核心标签，全部 L2、TTL=0、`need_value_audit=false`；非核心标签已归档。文中 `risk_level`、`alarm_count_30d` 等为历史示例，仅用于说明字段与调用格式；实际以 `GET /api/v1/open/tags` 返回为准。

---

## 目录

- [1. 通用约定](#1-通用约定)
  - [1.1 传输与命名](#11-传输与命名)
  - [1.2 认证与签名](#12-认证与签名)
  - [1.3 统一响应](#13-统一响应)
  - [1.4 通用限制](#14-通用限制)
  - [1.5 对象类型与 ID 规范](#15-对象类型与-id-规范)
- [2. 标签推送](#2-标签推送)
- [3. 标签查询](#3-标签查询)
- [4. 联调建议与变更记录](#4-联调建议与变更记录)

---

## 1. 通用约定

### 1.1 传输与命名

| 项目 | 约定 |
|---|---|
| 协议 | HTTP/HTTPS |
| 编码 | UTF-8 |
| 请求体 | JSON，`Content-Type: application/json` |
| 字段命名 | `snake_case` |
| 时间格式 | `yyyy-MM-dd HH:mm:ss`（Asia/Shanghai） |
| 数值类型 | `tag_value` **一律传字符串**；`confidence` 为枚举字符串 `高`/`中`/`低` |
| 测试地址 | 生产形态：`http://<host>:8081`；本机 demo：`http://localhost:8080` |

### 1.2 认证与签名

所有开放 API 均需在请求头携带以下 4 个字段：

| Header | 必填 | 说明 |
|---|---|---|
| `X-App-Key` | 是 | 接入凭证 AppKey |
| `X-Timestamp` | 是 | 毫秒级 Unix 时间戳，允许偏差 ±300 秒 |
| `X-Nonce` | 是 | 一次性随机串（建议 16~32 位），60 秒内不可重复 |
| `X-Signature` | 是 | HMAC-SHA256 签名，小写十六进制 |

**待签名字符串**（六段，使用 `\n` 连接，**无结尾换行**）：

```text
HTTP_METHOD
REQUEST_PATH
CANONICAL_QUERY
RAW_BODY
TIMESTAMP
NONCE
```

- `REQUEST_PATH`：实际请求路径，如 `/api/v1/open/tag-values/write`；含非 ASCII 字符时使用 URL 编码后的形态。
- `CANONICAL_QUERY`：
  - 取全部 query 参数，按参数名升序、同名参数按值升序；
  - 使用**解码后的原值**拼成 `k=v&k=v`；无参数时为空串。
  - 例：`driver_id=DRV85001&include_empty=true&vehicle_plate=苏A88801`
- `RAW_BODY`：POST 请求实际发送的 JSON 原文（建议紧凑序列化，签名后不要重新格式化）；GET 请求为空串。
- 签名算法：`hex(HMAC-SHA256(secret, stringToSign))`，secret 为凭证密钥。

> 各语言完整签名示例见《[接入对接指南](integration-guide.md)》第 3 章；Java 可直接使用 `sdk-java` 中的 `TagPlatformClient`，无需手工签名。

### 1.3 统一响应

所有接口返回统一结构：

```json
{
  "code": 0,
  "message": "ok",
  "request_id": "b06b3c5016cc4b68bcaf2e72",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | integer | `0` 成功；非 0 见错误码 |
| `message` | string | 成功为 `ok`；失败为可读原因 |
| `request_id` | string | 服务端请求 ID，排障时提供 |
| `data` | object | 业务数据；失败时可能为 `null` 或错误上下文 |

**HTTP 状态码与业务码**：

| HTTP | 含义 |
|---|---|
| `200` | 请求已处理；业务是否成功看 `code`（参数/业务校验失败也是 200） |
| `401/403` | 签名、凭证、租户等鉴权失败（1xxx） |
| `429` | 频控（3005/3006/3007） |
| `500` | 系统或存储异常（5001/5002） |

> 客户端应先判断 HTTP 状态，再判断 `code`；**不要仅以 HTTP 200 判定业务成功**。

### 1.4 通用限制

| 限制项 | 默认值 | 说明 |
|---|---|---|
| 单批条数 | 1000 | `items` 1~1000 条 |
| 请求体字段 | 见各接口 | `tag_value` ≤2048 字符 |
| IP 频控 | 100 次/秒 | 验签前拦截，超限 3007（429） |
| 操作员频控 | 20 次/秒 | 同一 `operator_id` 按请求计，超限 3005（429） |
| 同对象同标签 | 1 次/秒 | 同一 `(object_type, object_id, tag_code)` 1 秒内只允许 1 条，超限 3006（429） |
| 幂等窗口 | 24 小时 | 相同 `biz_request_id` 回放首次响应 |
| 时间戳偏差 | ±300 秒 | 超出返回 1004 |
| Nonce 有效期 | 60 秒 | 重复返回 1005 |

### 1.5 对象类型与 ID 规范

| object_type | 名称 | object_id 格式 | 示例 |
|---|---|---|---|
| `driver` | 司机 | `^DRV[0-9]{5,}$` | `DRV85001` |
| `vehicle` | 车辆 | `^[一-龥][A-Z][A-Z0-9]{5,6}$` | `苏A88801`、`苏AD12345` |
| `fleet` | 车队 | `^FLT[0-9]{4,}$` | `FLT0001` |
| `alarm_event` | 告警事件 | `^EVT[0-9A-Z]{6,}$` | `EVT20261019001` |

> 车辆标识已统一为**车牌号**；对外查询字段名为 `vehicle_plate`。旧 VIN（17 位）不再接受，返回 `2002`。

**写接口与查询接口的字段映射**

| 业务概念 | 写接口字段 | 查询接口字段 |
|---|---|---|
| 对象类型 | `object_type=driver` / `vehicle` | 由 `driver_id` / `vehicle_plate` 隐含 |
| 司机标识 | `object_id`（值为司机 ID） | `driver_id` |
| 车辆标识 | `object_id`（值为车牌号） | `vehicle_plate`（值为车牌号） |

写接口是通用多态接口，因此统一使用 `object_type` + `object_id`；查询接口一次查两个对象，因此使用类型化标识字段。查询字段名与 `object_type_meta.id_field` 一致（driver → `driver_id`，vehicle → `vehicle_plate`），对象类型值和标识值也保持一致。

---

## 2. 标签推送

### 2.1 接口信息

| 项目 | 内容 |
|---|---|
| 方法 | `POST` |
| 路径 | `/api/v1/open/tag-values/write` |
| 用途 | 批量写入标签值，支持人工/系统/算法打标 |
| 批量语义 | **整批校验，全部通过才受理**；任一条失败则整批拒绝，不写入任何条目 |
| 审核路由 | 标签 `need_value_audit=true` 时流水记为 `pending_audit`；平台值审核已停用（3013） |
| 数据链路 | 只追加 `tag_value_history` + Outbox；`tag_value_latest`（画像数据源）由外部写入程序维护，受理成功 ≠ 画像立即可查 |

### 2.2 请求头

```http
Content-Type: application/json
X-App-Key: <AppKey>
X-Timestamp: <毫秒时间戳>
X-Nonce: <随机串>
X-Signature: <HMAC-SHA256>
```

### 2.3 请求体

**顶层字段**

| 字段 | 必填 | 类型 | 约束与说明 |
|---|---|---|---|
| `biz_request_id` | 是 | string | 幂等键，长度 ≤96，全局唯一；重复提交回放首次响应 |
| `items` | 是 | array | 1~1000 条 |

**items[] 字段**

| 字段 | 必填 | 类型 | 约束与说明 |
|---|---|---|---|
| `object_type` | 是 | string | `driver` / `vehicle` / `fleet` / `alarm_event` |
| `object_id` | 是 | string | 符合对应对象类型的格式 |
| `tag_code` | 是 | string | 必须存在、对租户可见、已上线、允许写入，且适用于该对象类型 |
| `tag_value` | 是 | string | 按标签 `value_type` 传字符串，规则见 2.4；≤2048 字符 |
| `biz_time` | 否 | string | 业务时间；缺省 = `operate_time`；格式 `yyyy-MM-dd HH:mm:ss` |
| `operate_time` | 否 | string | 操作时间；缺省 = 服务端当前时间 |
| `operator` | 是 | object | 操作员四元组，见下表 |
| `source` | 否 | string | 来源标识，≤64 字符；缺省 `open_api` |
| `confidence` | 否 | string | 置信度，仅支持 `高` / `中` / `低`（≤10 字符） |
| `remark` | 条件必填 | string | 敏感标签（`need_value_audit=true`）必填；≤2048 字符 |
| `evidence_url` | 否 | string | 证据链接，≤512 字符 |
| `ext_json` | 否 | string | 扩展字段，JSON 文本，≤2048 字符；平台原样存储 |

**operator 字段**

| 字段 | 必填 | 约束 |
|---|---|---|
| `operator_id` | 是 | ≤64 字符 |
| `operator_type` | 是 | `human` / `system` / `algo` |
| `operator_name` | 否 | ≤128 字符 |
| `operator_org` | 否 | ≤128 字符 |

### 2.4 tag_value 类型规则

| value_type | 规则 | 示例 |
|---|---|---|
| `boolean` | 仅接受小写 `true` / `false` | `"true"` |
| `number` | 可解析为数值，且满足标签 `value_range` 的 min/max | `"12.5"` |
| `enum` | 必须命中标签 `value_range.enum`；多选标签用英文逗号分隔多个值 | `"high"`、`"公共道路,港口"` |
| `date` | `yyyy-MM-dd` 或 `yyyy-MM-dd HH:mm:ss` | `"2026-10-19"` |
| `string` | 非空，≤2048 字符 | `"备注文本"` |

> **单选 / 多选**：`GET /api/v1/open/tags` 返回 `value_multiple` 与 `options`。`value_multiple=false` 时只能传一个枚举值；`true` 时用英文逗号分隔多个值（如 `"公共道路,港口"`），服务端逐项校验并去除首尾空格，空项、重复值、非枚举值都会被拒绝。

### 2.5 处理语义

1. **幂等**：同一 `biz_request_id` 重复提交（无论内容是否一致）会回放首次响应；仍在处理中返回 `3004`；处理失败会释放幂等记录，可用同一 ID 修复后重试。
2. **整批校验**：先完成全部条目校验，任一条失败则整批拒绝，错误信息指明第几条及原因。
3. **写入与生效**：所有条目仅追加打标流水（`tag_value_history`）；敏感标签流水状态为 `pending_audit`，其余为 `effective`。平台不再写入 `tag_value_latest`，画像更新由外部写入程序完成；值审核已停用（3013）。
4. **过期时间**：由标签的 `value_ttl_days` 推导：`expire_time = biz_time + TTL`；TTL 为 0 时不过期。
5. **批量内重复**：同一批中同一 `(object_type, object_id, tag_code)` 只能出现一次，否则第二条可能触发同对象同标签频控 `3006`；如需多条请拆批或间隔 ≥1 秒。
6. **写入开关**：标签 `writable=false` 时整批拒绝，返回 `3012`。

### 2.6 请求示例

```json
{
  "biz_request_id": "biz-20261019-0001",
  "items": [
    {
      "object_type": "driver",
      "object_id": "DRV85001",
      "tag_code": "risk_level",
      "tag_value": "high",
      "biz_time": "2026-10-19 10:30:00",
      "operate_time": "2026-10-19 10:30:05",
      "operator": {
        "operator_id": "OP1001",
        "operator_name": "张三",
        "operator_type": "human",
        "operator_org": "客服中心"
      },
      "source": "客服工作台",
      "confidence": "高",
      "remark": "客户来电核实",
      "evidence_url": "https://example.com/evidence/1001.jpg",
      "ext_json": "{\"ticket_id\":\"T-1024\"}"
    },
    {
      "object_type": "driver",
      "object_id": "DRV85001",
      "tag_code": "social_tendency",
      "tag_value": "外向",
      "operator": {
        "operator_id": "OP1001",
        "operator_name": "张三",
        "operator_type": "human",
        "operator_org": "客服中心"
      },
      "remark": "面访记录：沟通主动"
    },
    {
      "object_type": "vehicle",
      "object_id": "苏A88801",
      "tag_code": "cargo_type",
      "tag_value": "普货",
      "operator": {
        "operator_id": "SYS_CRM",
        "operator_name": "CRM 同步任务",
        "operator_type": "system",
        "operator_org": "数据平台"
      }
    }
  ]
}
```

### 2.7 响应

**data 字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `biz_request_id` | string | 原样返回 |
| `total` | integer | 本批条数 |
| `accepted` | integer | 已受理（流水 `effective`）条数 |
| `pending_audit` | integer | 已受理（流水 `pending_audit`）条数 |
| `results` | array | 逐条结果，顺序与请求 `items` 一致 |

**results[] 字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `index` | integer | 条目下标，从 0 开始 |
| `object_type` | string | 对象类型 |
| `object_id` | string | 对象 ID |
| `tag_code` | string | 标签码 |
| `status` | string | `accepted`（已受理）/ `pending_audit`（敏感标签待处理） |
| `message` | string/null | `accepted` 为 `null`；`pending_audit` 为“待人工审核后生效” |

> `rejected` 为预留状态，当前版本整批校验失败时不会出现在 `results` 中，而是返回顶层错误码。

**响应示例**

```json
{
  "code": 0,
  "message": "ok",
  "request_id": "f1c2a9d0e7b34a5c9d02e6f812345678",
  "data": {
    "biz_request_id": "biz-20261019-0001",
    "total": 3,
    "accepted": 2,
    "pending_audit": 1,
    "results": [
      {
        "index": 0,
        "object_type": "driver",
        "object_id": "DRV85001",
        "tag_code": "risk_level",
        "status": "accepted",
        "message": null
      },
      {
        "index": 1,
        "object_type": "driver",
        "object_id": "DRV85001",
        "tag_code": "social_tendency",
        "status": "pending_audit",
        "message": "待人工审核后生效"
      },
      {
        "index": 2,
        "object_type": "vehicle",
        "object_id": "苏A88801",
        "tag_code": "cargo_type",
        "status": "accepted",
        "message": null
      }
    ]
  }
}
```

### 2.8 失败响应（整批拒绝）

参数/业务校验失败（2xxx、3xxx 中非频控类）时，HTTP 仍为 200，`data` 给出失败条目上下文：
（频控 3005/3006/3007 返回 HTTP 429，系统异常返回 500。）

```json
{
  "code": 3012,
  "message": "第 3 条校验失败: 标签 cargo_type 不允许写入（写入开关已关闭）",
  "request_id": "a1b2c3d4e5f6470890a1b2c3d4e5f607",
  "data": {
    "index": 2,
    "object_type": "vehicle",
    "object_id": "苏A88801",
    "tag_code": "cargo_type",
    "reason": "标签 cargo_type 不允许写入（写入开关已关闭）"
  }
}
```

### 2.9 错误码（推送相关）

| code | HTTP | 含义 | 处理建议 |
|---|---|---|---|
| `1001` | 401 | 签名头缺失/非法 | 检查 4 个 Header |
| `1002` | 401 | AppKey 不存在 | 检查凭证 |
| `1003` | 401 | 签名失败 | 核对 stringToSign 与 RAW_BODY |
| `1004` | 401 | 时间戳超限 | 校准系统时间 |
| `1005` | 401 | Nonce 重复 | 每次请求生成新 Nonce |
| `1006` | 403 | 租户不匹配 | 检查凭证归属 |
| `1007` | 403 | 租户停用 | 联系平台 |
| `1008` | 401 | 凭证禁用 | 联系平台 |
| `2001` | 200 | 缺少必填参数 | 检查 `biz_request_id`、`items` |
| `2002` | 200 | 参数校验失败 | 按 message 修正条目 |
| `2003` | 200 | 批量超限 | 拆分至 ≤1000 条 |
| `3001` | 200 | 标签不存在/不可见 | 检查 `tag_code` |
| `3002` | 200 | 标签未上线 | 换用在线标签 |
| `3003` | 200 | 对象类型未注册/停用 | 检查 `object_type` |
| `3004` | 200 | 同一幂等键处理中 | 稍后重试 |
| `3005` | 429 | 操作员频率超限 | 退避重试 |
| `3006` | 429 | 同对象同标签频率超限 | 拆批或间隔 ≥1 秒 |
| `3007` | 429 | IP 频率超限 | 退避重试 |
| `3008` | 200 | 敏感标签缺少 `remark` | 补充打标理由 |
| `3011` | 200 | 标签不适用于该对象类型 | 核对标签与对象类型 |
| `3012` | 200 | 标签写入开关已关闭 | 换标签或联系平台 |
| `5001` | 500 | 系统内部错误 | 记录 `request_id` 联系平台 |
| `5002` | 500 | 存储写入失败 | 使用同一幂等键重试 |

### 2.10 调用示例（cURL）

```bash
curl -X POST "$BASE_URL/api/v1/open/tag-values/write" \
  -H "Content-Type: application/json" \
  -H "X-App-Key: $APP_KEY" \
  -H "X-Timestamp: $TS" \
  -H "X-Nonce: $NONCE" \
  -H "X-Signature: $SIGNATURE" \
  --data-binary @payload.json
```

### 2.11 Java SDK

```java
TagPlatformClient client = new TagPlatformClient(baseUrl, appKey, secret);

TagPlatformClient.WriteResult result = client.write(new TagPlatformClient.WriteRequest(
        "biz-20261019-0001",
        List.of(
                new TagPlatformClient.WriteItem(
                        "driver", "DRV85001", "risk_level", "high",
                        null, null,
                        TagPlatformClient.OperatorDto.human("OP1001", "张三", "客服中心"),
                        "客服工作台", 0.9, "客户来电核实", null, null),
                new TagPlatformClient.WriteItem(
                        "driver", "DRV85001", "social_tendency", "外向",
                        null, null,
                        TagPlatformClient.OperatorDto.human("OP1001", "张三", "客服中心"),
                        null, null, "面访记录：沟通主动", null, null)
        )));
```

---

## 3. 标签查询

### 3.1 接口信息

| 项目 | 内容 |
|---|---|
| 方法 | `GET` |
| 路径 | `/api/v1/open/objects/profile/combined` |
| 用途 | 一次查询司机 + 车辆组合的标签当前值 |
| 认证 | 同 1.2，GET 无请求体，`RAW_BODY` 为空串 |

### 3.2 请求参数

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `driver_id` | query | 否* | string | 司机 ID，需符合 `^DRV[0-9]{5,}$` |
| `vehicle_plate` | query | 否* | string | 车辆标识（车牌号），需符合 `^[一-龥][A-Z][A-Z0-9]{5,6}$` |
| `tag_codes` | query | 否 | string | 逗号分隔的标签码；省略/空串 = 返回该对象类型下全部可见在线标签 |
| `include_empty` | query | 否 | boolean | 默认 `true`：无值标签以 `status=none` 占位；`false` 仅返回有值标签 |
| `include_pending` | query | 否 | boolean | 默认 `false`：不返回待审核值；`true` 时包含 `status=pending_audit` |

\* `driver_id` 与 `vehicle_plate` **至少传一个**；都缺失返回 `2001`。两者都传时同时返回 `driver` 与 `vehicle` 两段。旧参数 `plate_no`、`vehicle_id` 均已废弃，不再接受。

**参数语义**

- `tag_codes` 非空时仅返回指定标签；传清单-1015 的 32 个标签码可精确得到 driver 17 + vehicle 15 = 32 条。
- `include_empty=true` 时，占位范围 = 对租户可见、在线、且适用于该对象类型的标签；按标签字典顺序返回。
- 有值但已下线/不适配当前对象类型的标签会追加在末尾，避免画像数据丢失。
- `include_pending=false` 时，若某标签只有待审核值，且 `include_empty=true`，该标签会以 `status=none` 占位返回。
- 已作废（`revoked`）与已过期（`expire_time` 早于当前时间）的值**不会返回**。
- 车牌号含中文，实际发送时需 URL 编码；**签名使用解码后的原值**。

### 3.3 请求示例

```http
GET /api/v1/open/objects/profile/combined?driver_id=DRV85001&vehicle_plate=%E8%8B%8FA88801&include_empty=true
```

指定标签（示例仅列 2 个，实际可传 32 个）：

```http
GET /api/v1/open/objects/profile/combined?driver_id=DRV85001&vehicle_plate=%E8%8B%8FA88801&tag_codes=risk_level,cargo_type&include_empty=true
```

### 3.4 响应

**data 字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `tenant_id` | string | 租户 ID |
| `driver` | object/null | 司机段；未传 `driver_id` 时为 `null` |
| `vehicle` | object/null | 车辆段；未传 `vehicle_plate` 时为 `null` |
| `total` | integer | 两段标签条数之和 |

**ProfileSection 字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `object_type` | string | `driver` 或 `vehicle` |
| `object_id` | string | 对象 ID（车辆为车牌号） |
| `tags` | array | 标签列表 |

**tags[]（TagItem）字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `tag_code` | string | 标签码 |
| `tag_name` | string | 标签名称 |
| `tag_value` | string/null | 标签值；`status=none` 时为 `null` |
| `value_type` | string | `boolean` / `number` / `enum` / `date` / `string` |
| `status` | string | `effective` / `pending_audit` / `none` |
| `source_mode` | string/null | `manual` / `statistics` / `hybrid` / `algo` |
| `need_value_audit` | boolean | 是否敏感标签（写入后需审核） |
| `biz_time` | string/null | 业务时间 |
| `operate_time` | string/null | 操作时间 |
| `expire_time` | string/null | 过期时间；`null` 表示不过期 |
| `operator_id` | string/null | 操作员 ID |
| `operator_name` | string/null | 操作员名称 |
| `operator_type` | string/null | `human` / `system` / `algo` |
| `operator_org` | string/null | 操作员组织 |
| `source` | string/null | 来源标识 |
| `confidence` | string/null | 置信度：`高` / `中` / `低` |
| `remark` | string/null | 打标理由 |
| `evidence_url` | string/null | 证据链接 |

**响应示例**（节选 3 条；实际 `total=38`）

```json
{
  "code": 0,
  "message": "ok",
  "request_id": "b06b3c5016cc4b68bcaf2e72",
  "data": {
    "tenant_id": "risk_control",
    "driver": {
      "object_type": "driver",
      "object_id": "DRV85001",
      "tags": [
        {
          "tag_code": "avg_distraction_count",
          "tag_name": "日均分心驾驶次数",
          "tag_value": "14",
          "value_type": "number",
          "status": "effective",
          "source_mode": "statistics",
          "need_value_audit": false,
          "biz_time": "2026-10-16 15:43:25",
          "operate_time": "2026-10-16 15:43:25",
          "expire_time": "2026-10-23 15:43:25",
          "operator_id": "SYS_VEH_METRIC",
          "operator_name": "车联网统计任务",
          "operator_type": "system",
          "operator_org": "数据平台",
          "source": "数据统计",
          "confidence": null,
          "remark": null,
          "evidence_url": null
        },
        {
          "tag_code": "alarm_count_30d",
          "tag_name": "30 日告警数",
          "tag_value": null,
          "value_type": "number",
          "status": "none",
          "source_mode": "statistics",
          "need_value_audit": false,
          "biz_time": null,
          "operate_time": null,
          "expire_time": null,
          "operator_id": null,
          "operator_name": null,
          "operator_type": null,
          "operator_org": null,
          "source": null,
          "confidence": null,
          "remark": null,
          "evidence_url": null
        }
      ]
    },
    "vehicle": {
      "object_type": "vehicle",
      "object_id": "苏A88801",
      "tags": [
        {
          "tag_code": "avg_daily_mileage",
          "tag_name": "日均里程",
          "tag_value": "112.7",
          "value_type": "number",
          "status": "effective",
          "source_mode": "statistics",
          "need_value_audit": false,
          "biz_time": "2026-10-19 09:26:25",
          "operate_time": "2026-10-19 09:26:25",
          "expire_time": "2026-10-26 09:26:25",
          "operator_id": "SYS_VEH_METRIC",
          "operator_name": "车联网统计任务",
          "operator_type": "system",
          "operator_org": "数据平台",
          "source": "数据统计",
          "confidence": null,
          "remark": null,
          "evidence_url": null
        }
      ]
    },
    "total": 38
  }
}
```

### 3.5 典型场景

| 场景 | 参数 | 预期 |
|---|---|---|
| 查询全部标签 | `driver_id=DRV85001&vehicle_plate=苏A88801` | 返回司机 + 车辆全部可见标签，默认含无值占位；demo 数据为 38 条（21 + 17） |
| 精确查询 32 个特征值 | 同上 + `tag_codes=<32 个码>` | driver 17 + vehicle 15 = 32 条 |
| 只查司机 | 仅 `driver_id` | `vehicle=null` |
| 只查车辆 | 仅 `vehicle_plate` | `driver=null` |
| 只看有值标签 | `include_empty=false` | 不返回 `status=none` 的占位项 |
| 查看待审核值 | `include_pending=true` | 可能返回 `status=pending_audit` 的项 |

### 3.6 错误码（查询相关）

| code | HTTP | 含义 | 处理建议 |
|---|---|---|---|
| `1001`~`1008` | 401/403 | 鉴权失败 | 同 2.9 |
| `2001` | 200 | `driver_id` 与 `vehicle_plate` 均未传 | 至少传一个 |
| `2002` | 200 | 对象 ID 格式不符合要求 | 检查司机 ID / 车牌号格式 |
| `3003` | 200 | 对象类型未注册/停用 | 联系平台 |
| `3007` | 429 | IP 频率超限 | 退避重试 |
| `5001` | 500 | 系统内部错误 | 记录 `request_id` 联系平台 |
| `5002` | 500 | 存储读取失败 | 稍后重试 |

### 3.7 Java SDK

```java
TagPlatformClient.CombinedProfileView view = client.getCombinedProfile(
        "DRV85001", "苏A88801", false, true, null);
// 省略 tagCodes：返回全部可见标签
// 精确 32 个：传入 32 个标签码的 Collection
```

---

## 4. 联调建议与变更记录

### 4.1 联调建议

1. **签名排障**：先核对 `stringToSign` 六段；POST 的 `RAW_BODY` 必须与实际发送字节完全一致；中文车牌在 query 中先 URL 编码再发送，但签名使用解码值。
2. **重试策略**：
   - 网络超时 / `5002`：使用**同一** `biz_request_id` 重试（幂等回放，不会重复写入）。
   - `3004`：等待片刻后重试。
   - `429`：按指数退避重试。
   - `2002/3001/3002/3011/3012`：修正数据后再重试，不要盲目重试。
3. **批量建议**：单批 ≤1000 条；同一 `(object_type, object_id, tag_code)` 不要在同一批重复出现；高频场景按对象/标签拆批。
4. **测试数据**：demo 环境司机 `DRV85001`、车牌 `苏A88801`；32 标签清单见 [tag-catalog-1015.md](tag-catalog-1015.md)。
5. **Postman**：直接导入 [postman-32-tags.json](postman-32-tags.json)，集合级脚本会自动生成签名；注意 `tag_codes` 请求项默认启用/禁用状态。

### 4.2 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| V6 | 2026-10-19 | 车辆标识由 VIN 改为车牌号；查询参数曾改为 `plate_no`；`tag_codes` 改为可选，省略时返回全部可见在线标签 |
| V7 | 2026-10-19 | 对外标识字段短暂统一为 `vehicle_id`（值仍为车牌号）；`object_type_meta.id_field=vehicle_id` |
| V8 | 2026-10-19 | 车辆标识定名为 `vehicle_plate`（值仍为车牌号）；`object_type_meta.id_field=vehicle_plate`；旧参数 `plate_no`、`vehicle_id` 不再接受 |
| V9 | 2026-10-19 | 写入/作废只追加 `tag_value_history` + Outbox，`tag_value_latest` 改由外部写入程序维护；值审核停用（3013）；作废改为基于最近一条流水判断，不再依赖 latest |

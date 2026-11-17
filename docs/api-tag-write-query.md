# 标签推送 / 标签查询 接口文档

> 适用版本：V9（2026-10-19，运行口径更新于 2026-10-22）  
> 适用接口：标签值批量推送、对象画像查询（统一入口 `/objects/profiles`）  
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
- [3. 对象画像查询（统一入口）](#3-对象画像查询统一入口)
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

| object_type | 名称 | 推荐 ID 格式（仅约定，不校验） | 示例 |
|---|---|---|---|
| `driver` | 司机 | `DRV` + 数字 | `DRV85001` |
| `vehicle` | 车辆 | 车辆 ID 或历史车牌号 | **车辆 ID**：`VH10086`、`CL-2026-0001`；历史**车牌号**：`苏A88801`、`苏AD12345` |
| `fleet` | 车队 | `FLT` + 数字 | `FLT0001` |
| `alarm_event` | 告警事件 | `EVT` + 数字/字母 | `EVT20261019001` |

> **平台不对 `object_id` 做格式校验**（2026-11-11 起）：只要求非空。`object_type_meta.id_pattern`
> 仅作为接入约定保留在元数据里，不参与请求拦截——上游的 `driver_id` / `vehicle_id` 编码规则不统一
> （历史车牌、外部系统编号、测试数据并存），强制正则会误挡合法数据。
>
> 口径约定（不校验，但建议遵守）：车辆的 `object_id` 使用**车辆 ID**（车联网内部唯一编号，非车牌号），
> 车牌号放 `object_name`；历史上以车牌为 `object_id` 的接入保持原值、勿混用。

**写接口与查询接口的字段映射**

| 业务概念 | 写接口字段 | 查询接口字段 |
|---|---|---|
| 对象类型 | `object_type=driver` / `vehicle` | 由 `driver_id` / `vehicle_plate` 隐含 |
| 司机标识 | `object_id`（值为司机 ID） | `driver_id` |
| 车辆标识 | `object_id`（值为**车辆 ID**，非车牌号；车牌号放 `object_name`） | `vehicle_plate`（值为车牌号） |

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
| `task_id` | 否 | long | 关联打标任务。**不传 = 通用写入通道**：可在任意时间推送标签值，不受任务状态与进度约束（任务进行中、已验收、已取消都照常受理），只追加流水与事件，不影响任何任务的进度与对象项状态；传入才进入任务作业通道，任务须为 `published` / `in_progress` / `reviewing`（已验收拒绝），并在标签写齐后自动推进任务项。**任务为「全部对象」模式（`all_objects=true`）时不校验对象是否已在任务快照中**：写入的新对象自动纳入任务项并参与进度统计 |

**items[] 字段**

| 字段 | 必填 | 类型 | 约束与说明 |
|---|---|---|---|
| `object_type` | 是 | string | `driver` / `vehicle` / `fleet` / `alarm_event` |
| `object_id` | 是 | string | 对象唯一标识，非空即可（不校验格式） |
| `object_name` | 否 | string | 对象名称（姓名 / 车牌号 / 车队名等），≤256 字符；仅用于展示与流水追溯，不参与对象主键与幂等判断 |
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
7. **对象名称**：`object_name` 为可选展示字段（姓名 / 车牌号 / 车队名等），随流水原样保存（≤256 字符）；**不参与**对象主键、幂等判断与频控键，同一对象多次写入可传不同值，流水以写入时点的名称为准。
8. **任务写入可分批**：`task_id` 对应任务含多个标签时，单次推送**只需包含其中任意几个**（甚至 1 个），不要求推齐。平台按「任务 + 对象」累计已写标签：对象项先置 `in_progress`；当该任务的**必填标签**（`required=true`）全部写完，才自动置为 `submitted`（需审核）或 `approved`。同一批里给同一对象写多个标签，用多条 `items`（`object_id` 相同、`tag_code` 不同）即可；已 `submitted / approved / skipped` 的对象项再推，标签值仍会写入，但任务项不再变化。

### 2.6 请求示例

```json
{
  "biz_request_id": "biz-20261019-0001",
  "task_id": 12,
  "items": [
    {
      "object_type": "driver",
      "object_id": "DRV85001",
      "object_name": "张伟",
      "tag_code": "driving_risk_attitude",
      "tag_value": "中性",
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
      "object_name": "张伟",
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
      "object_id": "VH10086",
      "object_name": "苏A88801（重型半挂牵引车）",
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
        "object_id": "VH10086",
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
    "object_id": "VH10086",
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

### 2.12 Kafka 推送通道（可选）

除 HTTP 接口外，平台也直接消费 Kafka 主题作为标签写入通道。**消息体与 2.3 的请求体完全一致**，写入后的校验、幂等、任务联动、审计行为与 HTTP 接口相同。

| 环境 | Broker | Topic |
|---|---|---|
| 测试 | `kafka-host:9092` | `profile_tag_write` |
| 生产 | `kafka-prod-1:9092`、`kafka-prod-2:9092`、`kafka-prod-3:9092` | `profile_tag_write` |

消费组 `tag-platform-write` 由平台维护，接入方只需向 topic 生产消息，**无需调用 HTTP、无需签名**。

消息体在请求体基础上多两个可选字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| `tenant_id` | 推荐 | 租户归属。可放消息体，或 Kafka header `tenant_id`（header 优先）；都缺时回落平台配置的默认租户，未配置则进死信 |
| `app_key` | 否 | 调用方标识，用于审计与任务项归属；缺省记为 `kafka:<topic>` |
| `biz_request_id` | **是** | 幂等键（≤64 字符）。**同一批数据重投必须保持不变**；缺失直接进死信 |
| `items[]` | **是** | 与 2.3 的 `items` **完全共用同一套定义**（含 `source` 来源标识、`confidence` 置信度、`remark` 备注、`evidence_url`、`ext_json`），单批 ≤1000 条 |
| `task_id` | 否 | 带则写入同时推进任务进度 |

```json
{
  "tenant_id": "risk_control",
  "biz_request_id": "biz-20261111-0001",
  "items": [
    {
      "object_type": "driver",
      "object_id": "DRV90001",
      "object_name": "张伟",
      "tag_code": "driver_relation",
      "tag_value": "公司员工",
      "source": "外部系统推送",
      "operator": { "operator_id": "OP1001", "operator_type": "human", "operator_name": "张三" }
    }
  ]
}
```

**可靠性约定**

- **重复不重复写**：同一 `biz_request_id` 再次消费时回放首次受理结果，视为成功。
- **失败不丢消息**：解析失败、缺幂等键、业务校验失败 → 转死信 `profile_tag_write_dlq`（保留原消息，header 带 `x-error` / `x-origin-topic` / `x-origin-partition` / `x-origin-offset` / `x-failed-at`）；数据库或存储抖动 → 退避重试 3 次后转死信。
- **位点策略**：首次接入只消费**启动之后的新消息**，不补发 topic 历史；之后按已提交位点续消费，平台重启不漏消息。确需补历史时联系平台运维重置消费组位点。
- **顺序**：建议以 `object_id` 作为消息 key，保证同一对象的消息进入同一分区、按序处理。
- **频控**：Kafka 为内部可信通道，不受 API 的 QPS 限制，仅受单批 ≤1000 条约束。
- **观察消费情况**：控制台「开发者 → 接入监控」展示该 topic 的分区数、未消费积压、死信量，以及两组消费计数——
  **本次**（服务进程启动以来，重启清零）与**累计**（持久化于 Redis，跨重启保留，附成功率）。
  累计口径自该看板上线起统计，不含上线前的历史消费；死信量按死信 topic 存量计算，本身即为累计值。

---

## 3. 对象画像查询（统一入口）

### 3.1 接口信息

| 项目 | 内容 |
|---|---|
| 方法 | `GET` |
| 路径 | `/api/v1/open/objects/profiles` |
| 用途 | 一次读取一个或多个对象的当前标签画像（`tag_value_latest` + 组合标签查询期计算） |
| 认证 | 同 1.2，GET 无请求体，`RAW_BODY` 为空串 |

### 3.2 请求参数

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `object_type` | query | 是 | string | 对象类型，逗号分隔多值（`driver,vehicle`）；只查一种类型时只传 1 个即可配多个 ID |
| `object_id` | query | 是 | string | 对象 ID（车辆为车辆 ID，非车牌号），逗号分隔多值；**必须指定对象**，不传不会被解释为「该类型全部对象」；含中文或特殊字符时需 URL 编码，**签名使用解码原值** |
| `include_pending` | query | 否 | boolean | 默认 `false`：不返回待审核值；`true` 时包含 `status=pending_audit` |
| `include_empty` | query | 否 | boolean | **默认 `false`**（只返回有值标签）；`true` 时按「可见在线标签中适用于该对象的标签」补全 `status=none` 空占位，占位项同样带 `unit`，组合标签算不出值时也返回占位 |
| `tag_codes` | query | 否 | string | 逗号分隔标签码，**精确取码**：当前值、空占位与组合标签都受同一份过滤约束；不传或空串 = 不过滤；可传清单-1015 的 32 个标签码 |

**配对规则**

| 场景 | object_type | object_id | 实际查询 |
|---|---|---|---|
| 单对象 | `driver` | `DRV85001` | (driver,DRV85001) |
| 同类型多对象 | `driver` | `DRV85001,DRV85002` | (driver,DRV85001)、(driver,DRV85002) |
| 跨类型混合 | `driver,vehicle` | `DRV85001,VH10086` | (driver,DRV85001)、(vehicle,VH10086) |

`object_type` / `object_id` **按位置配对、不做笛卡尔积**；类型个数 > 1 且与 ID 个数不等返回 2002。重复 (类型, ID) 自动去重，单次上限 20 个类型 / 200 个对象。

**参数语义**

- `include_empty=true` 时，占位范围 = 对租户可见、在线、且适用于该对象类型的标签；标签项按字典顺序返回，随后追加组合标签，最后追加「有值但已下线 / 不适配当前对象」的标签（避免画像丢数据）。**不保证维度顺序**，展示排序请用每个 tags[] 自带的 `dim1_priority`（一级维度）+ `priority`（维度内标签）两级排序。
- `include_pending=false` 时，若某标签只有待审核值且 `include_empty=true`，该标签以 `status=none` 占位返回。
- 已作废（`revoked`）与已过期（`expire_time` 早于当前时间）的值**不会返回**；组合标签（`derived=true`）由平台在查询期实时计算，不落 latest。
- `tag_codes` 与 `include_empty` 可叠加，例如 `include_empty=true&tag_codes=risk_level,avg_daily_duration` 即「一次取多个对象的指定特征值，缺值也给占位」；传入不存在或不可见的标签码会被忽略，不报错。

### 3.3 请求示例

```http
GET /api/v1/open/objects/profiles?object_type=driver&object_id=DRV85001&include_pending=false
```

一次查司机 + 车辆，并补全无值占位（等价于原组合画像接口）：

```http
GET /api/v1/open/objects/profiles?object_type=driver,vehicle&object_id=DRV85001,%E8%8B%8FA88801&include_empty=true
```

多对象 + 精确取码（示例仅列 2 个码，实际可传 32 个）：

```http
GET /api/v1/open/objects/profiles?object_type=driver,driver,vehicle&object_id=DRV85001,DRV85002,%E8%8B%8FA88801&include_empty=true&tag_codes=risk_level,cargo_type
```

### 3.4 响应

**data 字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `tenant_id` | string | 租户 ID |
| `total` | integer | **去重后的对象数 = `records` 条数**（本接口不分页） |
| `records[]` | array | 每个对象一条，顺序与传入对象一致；结构与原单对象画像 `data` 完全一致 |

**records[] 字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `tenant_id` | string | 租户 ID |
| `object_type` | string | `driver` / `vehicle` / `fleet` 等已注册类型 |
| `object_id` | string | 对象 ID（**车辆为车辆 ID，非车牌号**；若历史接入以车牌号作为 object_id 则含中文，需 URL 编码） |
| `tags[]` | array | 标签列表；默认只返回有值项，`include_empty=true` 时含 `status=none` 占位 |

**tags[]（TagItem）字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `tag_code` / `tag_name` | string | 标签码与名称 |
| `dim1_code` / `dim1_name` | string/null | 一级维度编码与**中文名称**（实时解析自维度字典）；组合标签与未配置维度的标签为 `null` |
| `dim2_code` / `dim2_name` | string/null | 二级维度编码与**中文名称** |
| `priority` / `dim1_priority` | int/null | 标签优先级（维度内顺序）与所属一级维度的展示优先级，数值越小越靠前，可直接用于两级排序；未配置为 `null` |
| `tag_value` | string/null | 标签值；`status=none` 时为 `null` |
| `value_type` | string | `boolean` / `number` / `enum` / `date` / `string` |
| `unit` | string/null | 计量单位：数值型统计指标返回 小时 / 天 / 次 / 公里 等；文本、布尔、枚举类为 `null`；占位项同样带单位 |
| `status` | string | `effective` / `pending_audit` / `none` |
| `derived` | boolean | `true` 为组合标签（查询期实时计算） |
| `source_mode` | string/null | `manual` / `statistics` / `hybrid` / `algo` / `derived` |
| `need_value_audit` | boolean | 是否敏感标签（写入后需审核） |
| `biz_time` / `operate_time` | string/null | 业务时间 / 操作时间 |
| `expire_time` | string/null | 过期时间；`null` 表示不过期 |
| `operator_id` / `operator_name` / `operator_type` / `operator_org` | string/null | 操作员信息 |
| `source` | string/null | 来源标识（写入时可选传，如「车联网统计任务」「客服工作台」），未传为 `null` |
| `confidence` | string/null | **置信度**，仅 `高` / `中` / `低`；写入时可选，算法/外部系统推送常用，**人工打标一般不填 → 返回 `null`** |
| `remark` | string/null | 打标理由（敏感标签必填） |
| `evidence_url` | string/null | 证据链接（可选） |

**响应示例**（默认 `include_empty=false`，多类型混合）

```json
{
  "code": 0,
  "message": "ok",
  "request_id": "b06b3c5016cc4b68bcaf2e72",
  "data": {
    "tenant_id": "risk_control", "total": 2,
    "records": [
      { "tenant_id": "risk_control", "object_type": "driver", "object_id": "DRV85001",
        "tags": [{ "tag_code": "driver_relation", "tag_name": "司机关系", "tag_value": "第三方派遣",
                   "dim1_code": "operation_mode", "dim1_name": "运营模式",
                   "dim2_code": "driver_relation", "dim2_name": "司机关系",
                   "priority": 5, "dim1_priority": 4,
                   "value_type": "enum", "unit": null, "status": "effective", "derived": false,
                   "source_mode": "manual", "need_value_audit": false,
                   "biz_time": "2026-10-16 12:15:43", "operate_time": "2026-10-16 12:16:02",
                   "expire_time": null,
                   "operator_id": "U1008", "operator_name": "孙启勇",
                   "operator_type": "human", "operator_org": "客服一组",
                   "source": "客服工作台", "confidence": null,
                   "remark": "客户来电确认", "evidence_url": null }] },
      { "tenant_id": "risk_control", "object_type": "vehicle", "object_id": "VH10086",
        "tags": [{ "tag_code": "avg_daily_mileage", "tag_name": "日均里程", "tag_value": "112.7",
                   "value_type": "number", "unit": "公里", "status": "effective", "derived": false,
                   "source_mode": "statistics",
                   "operator_id": "SYS_VEH_METRIC", "operator_name": "车联网统计任务",
                   "operator_type": "system", "operator_org": null,
                   "source": "数据统计", "confidence": "高",
                   "biz_time": "2026-10-21 03:00:00", "operate_time": "2026-10-21 03:00:12",
                   "expire_time": null, "remark": null, "evidence_url": null }] }
    ]
  }
}
```

**空占位形态**（`include_empty=true` 时追加）

```json
{ "tag_code": "social_tendency", "tag_name": "社交倾向", "tag_value": null,
  "dim1_code": "personality", "dim1_name": "性格特征",
  "dim2_code": "risk_attitude", "dim2_name": "风险态度",
  "priority": 1, "dim1_priority": 1,
  "value_type": "enum", "unit": null, "status": "none", "derived": false }
```

默认口径下对象没有任何有值标签时仍返回一条 `tags: []`，**不会漏掉对象**。

### 3.5 典型场景

| 场景 | 参数 | 预期 |
|---|---|---|
| 查单个司机 | `object_type=driver&object_id=DRV85001` | `total=1`，取 `records[0]`，默认只含有值标签 |
| 同类型多对象 | `object_type=driver&object_id=DRV85001,DRV85002` | `total=2`，两条记录 |
| 司机 + 车辆全部特征值 | `object_type=driver,vehicle&object_id=DRV85001,VH10086&include_empty=true` | 两段对象各返回全部可见标签，无值占位 |
| 精确取 32 个特征值 | 同上 + `tag_codes=<32 个码>` | 仅返回指定标签，缺值仍占位 |
| 查看待审核值 | `include_pending=true` | 可能返回 `status=pending_audit` 的项 |

### 3.6 错误码（查询相关）

| code | HTTP | 含义 | 处理建议 |
|---|---|---|---|
| `1001`~`1008` | 401/403 | 鉴权失败 | 同 2.9 |
| `2001` | 200 | `object_type` / `object_id` 未传 | 两者均为必填 |
| `2002` | 200 | 类型与 ID 个数不匹配、object_id 为空、超出上限 | 检查类型与 ID 的配对数量 |
| `3003` | 200 | 对象类型未注册/停用 | 联系平台 |
| `3007` | 429 | IP 频率超限 | 退避重试 |
| `5001` | 500 | 系统内部错误 | 记录 `request_id` 联系平台 |
| `5002` | 500 | 存储读取失败 | 稍后重试 |

### 3.7 Java SDK

```java
// 批量 / 多对象（含空占位与精确取码）
TagPlatformClient.ProfileBatchView batch = client.getProfiles(
        "driver,vehicle", "DRV85001,VH10086", false, true, null);
// 精确 32 个特征值：第 5 个参数传 32 个标签码的 Collection

// 单对象便捷方法（内部同样走统一入口，返回 records[0]）
TagPlatformClient.ProfileView one = client.getProfile("driver", "DRV85001", false);
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
4. **测试数据**：demo 环境司机 `DRV85001`；车辆对象的历史演示数据中 `object_id` 为车牌（如 `苏A88801`）——按新口径车辆应使用车辆 ID（如示例 `VH10086`），同一对象请保持单一标识口径；32 标签清单见 [tag-catalog-1015.md](tag-catalog-1015.md)。
5. **Postman**：直接导入 [postman-32-tags.json](postman-32-tags.json)，集合级脚本会自动生成签名；注意 `tag_codes` 请求项默认启用/禁用状态。

### 4.2 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| V6 | 2026-10-19 | 车辆标识由 VIN 改为车牌号；查询参数曾改为 `plate_no`；`tag_codes` 改为可选，省略时返回全部可见在线标签 |
| V7 | 2026-10-19 | 对外标识字段短暂统一为 `vehicle_id`（值仍为车牌号）；`object_type_meta.id_field=vehicle_id` |
| V8 | 2026-10-19 | 车辆标识定名为 `vehicle_plate`（值仍为车牌号）；`object_type_meta.id_field=vehicle_plate`；旧参数 `plate_no`、`vehicle_id` 不再接受 |
| V9 | 2026-10-19 | 写入/作废只追加 `tag_value_history` + Outbox，`tag_value_latest` 改由外部写入程序维护；值审核停用（3013）；作废改为基于最近一条流水判断，不再依赖 latest |
| V12 | 2026-11-10 | 画像 tags[] 新增维度信息：`dim1_code/dim1_name`、`dim2_code/dim2_name`、`priority/dim1_priority`（与标签字典口径一致，字段只增不删） |
| V13 | 2026-11-12 | 文档补充：画像 `tags[]` 补齐 `expire_time` / `operator_*` / `source` / `confidence` / `remark` / `evidence_url` 说明（**接口字段未变**，此前表格与实现不同步） |
| V14 | 2026-11-12 | 响应示例补全：`tags[]` 示例现在包含 `source_mode` / `need_value_audit` / `expire_time` / `operator_*` / `source` / `confidence` / `remark` / `evidence_url`，并演示「人工打标 confidence=null」与「系统推送 confidence=高」两种写法 |
| V11 | 2026-11-10 | 画像查询统一为 `GET /objects/profiles`；删除单对象画像与组合画像两个端点（能力全部并入统一入口），同步 SDK、控制台文档与 Postman 集合 |
| V10 | 2026-11-06 | 写入请求 items[] 新增可选 `object_name`（对象名称，置于 `object_id` 之后），随流水落库；顶层补充 `task_id` 说明（任务联动，已验收任务拒绝写入） |

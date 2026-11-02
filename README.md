# 标签画像平台

标签画像平台的完整工程实现。业务系统（风控平台、业务系统 A 等）通过开放 API 在**司机 / 车辆 / 车队 / 告警事件**等对象上打标签，平台统一采集、治理与存储，支撑画像查询与后续数据挖掘分析。

技术形态按《标签画像平台技术方案》落地：

**Nginx + 模块化单体（Spring Boot 3 · JDK 21）+ StarRocks（PK 宽表）+ MySQL 8 + Redis 单机 + Outbox**

不引入 Kafka / Flink / APISIX / Flowable / K8s（均绑定后续版本升级触发条件）。

> **当前运行口径（2026-10-22）**：本地、测试与生产环境均只保留 32 个核心风控标签，全部归属 `risk_control`、状态 `online`、敏感级 L2、TTL=0、`need_value_audit=false`；非核心标签已归档（软删除，历史值与审计保留）。清单与变更说明见 [`docs/tag-catalog-1015.md`](./docs/tag-catalog-1015.md)。
>
> **接口前缀（2026-10-22 起）**：统一为 `/api/v1/open`（开放 API）与 `/api/v1/console`（控制台接口）；旧前缀 `/open/v1`、`/console/api` 在兼容期内继续可用，计划下个主版本移除。
>
> **版本号**：应用版本由父 POM 统一管理（当前 `1.17.0`），构建时生成 build-info；控制台通过 `GET /api/v1/console/system/version` 自动显示，不在静态页面硬编码。每次发布按 SemVer 升级父 POM 与子模块 `<parent><version>`。

---

## 1. 技术栈与模块

| 模块 | 职责 | 说明 |
|---|---|---|
| `access` | 接入控制 | HMAC-SHA256 验签、防重放（nonce）、频控（IP/操作员/对象维度）、KV 抽象（Redis / 内存双实现） |
| `meta` | 元数据 | 租户与凭证、标签字典与类目、对象类型注册、定义版本快照/对比/回滚；开放 API `GET /api/v1/open/tags` |
| `write` | 写入链路 | 批量写入/作废、契约校验、幂等（biz_request_id）、打标流水（history，永久保留）+ Outbox；（`tag_value_latest` 由外部写入程序维护） |
| `query` | 查询 | 对象画像、操作员打标记录 |
| `audit` | 审核治理 | 待审核队列（只读，值审核已停用）、标签定义审核、审计日志 |
| `analytics` | 接口埋点与看板 | 开放 API 调用流水、标签访问日统计、看板聚合接口 |
| `workbench` | 打标工作台与任务 | 控制台站内打标、任务创建/导入/派发/领取/审核/监控；复用统一写入链路，任务数据落元数据库 |
| `market` | 标签市场 | 租户管理员上架、跨租户申请与平台审批；审批通过后扩展标签适用租户并生成版本 |
| `console` | 管理控制台 | 登录 + 模块化 SPA（`js/core.js`、`js/pages/*`、`js/ui.js`、`js/main.js`）；菜单由权限接口驱动，任务/标签等复杂弹窗采用分区表单、卡片选择器与统一现代化视觉；下拉选与时间选择由 `js/ui.js` 自绘接管（原生控件保留为值载体） |
| `sdk-java` | Java SDK | 签名、重试、客户端与 DTO，零第三方运行时依赖（仅 Jackson） |

## 2. 目录结构

```
tag-platform/
├── pom.xml                   # 父 POM（server + sdk-java）
├── settings.xml              # 阿里云镜像 + 显式 localRepository
├── build.sh                  # 构建脚本（自动探测 JDK 21 / Maven）
├── server/                   # 模块化单体应用
│   ├── Dockerfile            # 两阶段镜像构建（Maven 构建 → JRE 21 运行）
│   └── src/main/
│       ├── java/com/tagplatform/
│       │   ├── access/       # 验签 / 防重放 / 频控 / KV
│       │   ├── meta/         # 租户 / 凭证 / 标签字典
│       │   ├── write/        # 写入 / 幂等 / 审核路由 / Outbox
│       │   ├── query/        # 画像 / 操作员记录
│       │   ├── audit/        # 审核队列 / 审计日志
│       │   ├── analytics/    # 开放 API 埋点 / 数据看板
│       │   ├── workbench/    # 打标工作台 / 任务管理 / 任务项审核
│       │   ├── console/      # 控制台接口
│       │   ├── common/       # 统一响应 / 错误码 / 异常 / 上下文 / 加解密
│       │   └── config/       # 双数据源 / 配置绑定 / StarRocks 建表
│       └── resources/
│           ├── application.yml / application-demo.yml
│           ├── db/migration/     # Flyway：MySQL 元数据库（V1 结构 + V2 种子）
│           ├── db/migration-h2/  # Flyway：demo 专用标签值表
│           ├── db/starrocks/     # StarRocks 宽表 DDL（应用启动自动执行）
│           └── static/console/   # 控制台 SPA：index.html + js/core.js、js/shared.js、js/ui.js、js/pages/*.js、js/main.js
├── sdk-java/                 # Java SDK（可独立打包给接入方）
├── data/                     # 演示数据与脚本
│   ├── seed-fleet-tags.sh    # 32 个核心标签注册上线 + 客服/统计打标数据（幂等可重跑）
│   └── archive-non-core-tags.sh  # 将历史/非核心标签下线并归档，收敛到 32 标签口径
└── deploy/
    ├── docker-compose.yml    # 本地：MySQL + Redis + StarRocks + 服务 + Nginx 单机编排
    ├── docker-compose.test.yml  # 测试：仅 MySQL + Redis + StarRocks（端口绑定 127.0.0.1）
    ├── nginx/nginx.conf      # 统一入口：反代 / 控制台 / IP 级限速兜底
    ├── .env.example          # 部署环境变量样例
    ├── .env.test.example     # 测试环境变量样例（口令留空）
    ├── test/                 # 测试环境：systemd unit、env 样例与依赖等待脚本
    ├── prod/                 # 生产环境：systemd unit、env 样例与 OceanBase 迁移脚本
    ├── starrocks/init.sql    # StarRocks 人工初始化脚本（等价于自动 DDL）
    └── smoke-test.sh         # 端到端冒烟（29 项断言）
```

## 3. 快速开始（demo 模式，零外部依赖）

demo 模式用 **H2 文件库**模拟 MySQL 与 StarRocks、**内存 KV** 模拟 Redis，一条命令即可本地起全链路。

环境要求：JDK 21 与 Maven 3.9+（`build.sh` 会自动探测 `JAVA_HOME`、SDKMAN、JBR 等常见位置）。

```bash
# 1) 构建（首次会下载依赖；含单元测试）
./build.sh clean package

# 2) 启动 demo（默认 8080 端口）
nohup java -jar server/target/tag-platform-server.jar --spring.profiles.active=demo > demo.log 2>&1 &

# 3) 端到端冒烟（签名写入 → 画像查询（latest 未同步）→ 审核停用 → 幂等 → 防重放反例）
./deploy/smoke-test.sh

# 4) 打开控制台
#    http://localhost:8080/console/index.html    账号 admin / admin123

# 5) （可选）32 个核心标签注册上线 + 客服/统计打标数据
#    清单与维度对照见 docs/tag-catalog-1015.md
./data/seed-fleet-tags.sh
#    指定实例与规模：PORT=8081 DRIVERS=10 VEHICLES=10 ./data/seed-fleet-tags.sh

# 6) （可选）若库中仍有历史种子标签，收敛为 32 个核心标签
#    默认只预演；确认后加 APPLY=1 执行
HOST=127.0.0.1 PORT=8081 ./data/archive-non-core-tags.sh
HOST=127.0.0.1 PORT=8081 APPLY=1 ./data/archive-non-core-tags.sh
```

demo 内置演示凭证：

| 项 | 值 |
|---|---|
| AppKey | `AK_RC_8f2a91` |
| Secret | `rc-secret-8f2a91-demo` |
| 租户 | `risk_control` |
| 标签 | `driving_risk_attitude`、`driver_relation`、`avg_daily_mileage` 等（共 32 个核心标签） |

### 3.1 本地环境（真实组件，8081）

本地联调使用真实 MySQL / Redis / StarRocks 容器，应用直跑 `8081`。配置**不使用独立 env 文件**，全部来自 `server/src/main/resources/application.yml` 默认值，端口由命令行覆盖：

| 项 | 取值 |
|---|---|
| 元数据库 | `jdbc:mysql://localhost:3306/tag_platform`（`tag_app`） |
| Redis | `localhost:6379`（`tag-platform.kv=redis`） |
| StarRocks | `jdbc:mysql://localhost:9030/tag_platform`（`tag-platform.store=starrocks`，启动自动建表） |
| Flyway | 开启（启动自动迁移） |

```bash
# 1) 三件套（容器名 tp-mysql / tp-redis / tp-starrocks）
cd deploy && docker compose up -d mysql redis starrocks && cd ..

# 2) 等 StarRocks 查询端口就绪后再起应用
curl -s http://127.0.0.1:8030/api/health

# 3) 应用
nohup java -jar server/target/tag-platform-server.jar --server.port=8081 > server-prod.log 2>&1 &
```

- 容器数据：MySQL / StarRocks 绑定到外接盘 `docker-volumes/{mysql,starrocks}`（见 `deploy/local/setup-docker.sh`），Redis 走 Docker 卷；无 `deploy/.env` 时使用 compose 默认口令，仅限本地。
- 改指向不改代码：用环境变量覆盖 `SPRING_DATASOURCE_URL`、`SPRING_DATA_REDIS_HOST`、`TAG_PLATFORM_STARROCKS_URL` 即可。

## 4. 开放 API（/api/v1/open）

> **接入方请看这里**：面向外部系统的完整对接指南（快速开始/签名/接口详情/错误码/多语言示例/Java SDK/FAQ）见 [`docs/integration-guide.md`](./docs/integration-guide.md)；机器可读契约见 [`docs/openapi.yaml`](./docs/openapi.yaml)（可导入 Postman/Apifox）。  
> **标签推送 / 标签查询速查**：字段表、请求响应示例与错误码见 [`docs/api-tag-write-query.md`](./docs/api-tag-write-query.md)。

### 4.1 签名协议

所有开放 API 使用 HMAC-SHA256 签名，请求头：

| Header | 说明 |
|---|---|
| `X-App-Key` | 应用标识 |
| `X-Timestamp` | 毫秒时间戳（允许偏差 ±300 秒） |
| `X-Nonce` | 一次性随机串（60 秒内不可重复，防重放） |
| `X-Signature` | HMAC-SHA256 十六进制小写签名 |

待签名字符串（六段，`\n` 连接）：

```
STRING_TO_SIGN = METHOD + "\n" + PATH + "\n" + CANONICAL_QUERY + "\n" + RAW_BODY + "\n" + TIMESTAMP + "\n" + NONCE

signature = hex(HMAC-SHA256(secret, STRING_TO_SIGN))
```

- `PATH`：不含 query 的**编码后**路径（如 `/api/v1/open/objects/DRV%200001/profile`）
- `CANONICAL_QUERY`：query 参数按 key 字典序、同 key 按 value 字典序，`k=v` 以 `&` 连接（URL 解码后的原值）
- `RAW_BODY`：请求体原文（GET 为空串）

### 4.2 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/open/tags?category_code=` | 标签字典（仅本租户可见的在线标签） |
| POST | `/api/v1/open/tag-values/write` | 批量写入（≤1000 条，整批受理，`biz_request_id` 幂等） |
| POST | `/api/v1/open/tag-values/delete` | 批量作废 |
| GET | `/api/v1/open/objects/{objectId}/profile?object_type=&include_pending=` | 对象画像（标签最新值） |
| GET | `/api/v1/open/operators/{operatorId}/tag-records?page=&size=` | 操作员打标记录（分页） |

写入语义：

- 批内**全过才受理**（all-or-nothing）；`results` 逐条给出 `accepted / pending_audit / rejected`
- **enum 单选/多选**：`tag_meta.value_multiple` 控制（默认单选）；多选值用英文逗号分隔（如 `公共道路,港口`），平台逐项校验、拒绝空项与重复值；`GET /api/v1/open/tags` 返回 `value_multiple` 与 `options` 供接入方渲染单选/多选控件
- **多租户适用**：标签定义可共享给多个租户（`tag_meta.tenant_id` 逗号分隔，`PUBLIC`=全部租户）；开放 API 按凭证租户过滤，写入校验同样按适用租户判断；控制台编辑弹窗可多选租户
- 平台**只追加打标流水**（`tag_value_history`）+ Outbox 事件；画像数据源 `tag_value_latest` 由**外部写入程序**维护，受理成功 ≠ 画像立即可查
- 敏感标签（`need_value_audit=1`）仍要求 `remark`，流水状态为 `pending_audit`；平台值审核已停用（控制台只读，审核接口返回 3013）
- 作废以最近一条流水为基准追加 `revoked` 流水，不直接修改 latest
- `tag_value_history` **永久保留**：不分区、不自动淘汰，可直接用于审计与血缘追溯
- 相同 `biz_request_id` 重复提交 → 回放首次响应（幂等，24h）
- 频控：IP 100 QPS、操作员 20 QPS、同对象同标签 1 QPS（超限返回 429）

### 4.3 统一响应与错误码

```json
{ "code": 0, "message": "ok", "request_id": "…", "data": { } }
```

| 区段 | 含义 | 示例 |
|---|---|---|
| 0 | 成功 | |
| 1xxx | 鉴权（HTTP 401/403） | 1003 签名校验失败、1004 时间戳超限、1005 Nonce 重放 |
| 2xxx | 参数（HTTP 200） | 2002 参数校验失败、2003 批量超限 |
| 3xxx | 业务（HTTP 200/429） | 3001 标签不存在、3005~3007 频控 429、3008 remark 必填、3013 值审核已停用 |
| 4xxx | 控制台（HTTP 401/403） | 4002 用户名或密码错误 |
| 5xxx | 系统（HTTP 500） | 5001 系统内部错误、5002 存储写入失败 |

## 5. Java SDK

接入方引入 `sdk-java` 后（仅依赖 Jackson，HTTP 用 JDK 内置 `java.net.http`）：

```java
TagPlatformClient client = new TagPlatformClient(
        "http://localhost:8080", "AK_RC_8f2a91", "rc-secret-8f2a91-demo");

// 批量写入（自动签名；网络异常/5xx/429 指数退避自动重试，每次重试换新 nonce）
WriteResult result = client.write(WriteRequest.of("biz-2026-1014-001", List.of(
        WriteItem.of("driver", "DRV00001", "driving_risk_attitude", "激进")
                .operator(OperatorDto.human("OP1001", "张三", "某交警支队"))
                .bizTime(LocalDateTime.now()))));

// 对象画像
ProfileView profile = client.getProfile("driver", "DRV00001", false);

// 操作员打标记录
OperatorRecordsView records = client.getOperatorRecords("OP1001",
        Map.of("object_type", "driver"), 1, 20);
```

## 6. 生产部署（Docker Compose）

`deploy/docker-compose.yml` 编排全套组件：Nginx（80 入口）→ tag-platform（8080）→ MySQL 8 / Redis 7 / StarRocks（all-in-one）。

```bash
cd deploy
cp .env.example .env          # 修改密码与加密主密钥（TAG_PLATFORM_MASTER_KEY 生产必改）
docker compose up -d --build

# 首次启动 StarRocks 需 1~3 分钟；随后执行冒烟（经 Nginx 入口）
HOST=localhost PORT=80 ../deploy/smoke-test.sh
```

要点：

- Docker Compose（MySQL 8）下服务启动时 Flyway 自动执行迁移（V1 结构 + V2 种子 + V9001 表/字段注释）；OceanBase 环境见 6.1
- 存量 StarRocks 环境升级：执行一次 `deploy/starrocks/migrate-v9-comments-retention.sql`，补齐表/字段注释并将 `tag_value_history` 改为永久保留（无分区淘汰）
- 置信度语义调整（v10）：执行一次 `deploy/starrocks/migrate-v10-confidence.sql`，将 `confidence` 由 FLOAT 改为 `VARCHAR(10)`（取值 `高`/`中`/`低`，存量 0.8 及以上→高、0.5 及以上→中）；`tag_value_latest` 由外部写入程序维护，切换后写入方须同步改为传枚举字符串；阶段二的 RENAME 需 root/管理员账号执行（应用账号仅有 DML 权限，报 5203），切换后保留 `*_bak` 表待确认无误再清理
- 标签单选/多选（v11）：新增 `V9004__tag_value_multiple.sql` 与 `V9005__tag_value_multiple_comments.sql`，为 `tag_meta` 增加 `value_multiple`（0 单选 / 1 多选，仅 enum 生效）；生产 OceanBase 按 6.1 预执行迁移后重启
- 标签多租户（v12）：`V9006__tag_tenant_multi.sql`（MySQL/OceanBase）与 `V1004__tag_tenant_multi.sql`（H2 demo）将 `tag_meta.tenant_id` 扩为 `VARCHAR(512)`，支持逗号分隔的适用租户列表；生产 OceanBase 按 6.1 预执行迁移后重启
- 打标工作台与任务（v13）：`V9007__workbench_task.sql` 扩展 `console_user` 的租户/操作员/班组字段并新增 5 张任务表；测试环境重启时 Flyway 自动迁移，生产按 6.1 预执行后重启。TAGGER / TASK_MANAGER 账号需在用户管理中绑定租户与操作员
- 标签定义版本（v14）：`V9008__tag_meta_version.sql` 新增定义快照表并为存量标签回填基线；注册/修改/写入开关/回滚自动生成版本，上下线与归档不生成定义版本；生产按 6.1 预执行后重启
- StarRocks 应用账号权限：`deploy/starrocks/grant-app-user.sql` 一次性授予 `tag_platform` 库的建表/改表/删表与增删改查权限；StarRocks 4.0 中库级 ALTER/DROP 不向下覆盖到表，必须额外 `GRANT ... ON ALL TABLES IN DATABASE`，否则表级操作报 5203（该表级授权对后续新建表同样生效）
- Nginx 对 `/api/v1/open/*` 路径**原样透传**（签名基于编码后 URI，不可改写路径），IP 级限速仅作兜底，租户/操作员级精细频控在应用内实现
- 生产 TLS 由外层 LB/证书终止；凭证 Secret 以可逆加密存储（`enc:v1:` 前缀），主密钥经 `TAG_PLATFORM_CRYPTO_MASTER_KEY` 注入
- 不想用容器时：直接 `java -jar` 启动，通过环境变量覆盖 `SPRING_DATASOURCE_URL`、`SPRING_DATA_REDIS_HOST`、`TAG_PLATFORM_STARROCKS_URL` 即可

### 6.1 裸机 systemd 部署（OceanBase / StarRocks）

运行配置集中在两处：应用环境变量 `/etc/tag-platform/tag-platform.env`（`640 root:tagplatform`）与迁移专用 `/etc/tag-platform/ob-migrate.env`（`600 root:root`）；程序与历史备份在 `/opt/tag-platform/`，日志在 `/var/log/tag-platform/`。

OceanBase 4.x 不支持 MySQL 的 `GET_LOCK`，Flyway 的 MySQL 实现依赖命名锁，启动会报 `Unable to acquire MySQL named lock`（OB 错误码 1235）；Flyway 11 也已移除 OceanBase 专用模块。因此裸机部署采用“预执行迁移 + 关闭 Flyway”：

1. 首次部署先执行迁移（脚本按版本顺序执行 `db/migration` + `db/migration-mysql`，以 `schema_migration` 表登记并校验 sha256，可重复执行）。先按模板 `deploy/prod/ob-migrate.env.example` 准备 `/etc/tag-platform/ob-migrate.env`（`600 root:root`）：

   ```bash
   set -a; . /etc/tag-platform/ob-migrate.env; set +a
   /opt/tag-platform/bin/apply-oceanbase-migrations.sh /opt/tag-platform/db tag_platform
   ```

2. 按模板 `deploy/prod/tag-platform.env.example` 准备 `/etc/tag-platform/tag-platform.env`（`640 root:tagplatform`），并保持 `SPRING_FLYWAY_ENABLED=false`，应用启动不再执行 Flyway。
3. StarRocks 两张表由应用启动时自动建表（`init-schema: true`）；业务账号需具备表级读写权限：

   ```sql
   GRANT SELECT, INSERT ON ALL TABLES IN DATABASE tag_platform TO USER 'tag_user'@'%';
   ```

4. 后续版本升级：上传新的 `db/migration*` 脚本后重复执行第 1 步即可。
5. 进程托管：按模板 `deploy/prod/tag-platform.service` 安装到 `/etc/systemd/system/`，然后 `systemctl daemon-reload && systemctl enable --now tag-platform`。

### 6.2 测试环境部署（Docker 基础设施 + systemd 应用）

与本地环境一致：MySQL / Redis / StarRocks 跑容器，应用 `java -jar` 直跑，端口 `8081`；库表端口仅绑定 `127.0.0.1`。当前实例：`<test-host>`。

运行配置分散在三处：应用环境变量 `/etc/tag-platform-test/tag-platform-test.env`（`640 root:tagplatform`）、compose 变量 `/opt/tag-platform-test/.env.test`（`600 root:root`）与编排文件 `docker-compose.test.yml`；日志在 `/var/log/tag-platform-test/`。

```bash
# 1) 基础设施
cd deploy
cp .env.test.example .env.test        # 填口令与主密钥（已 gitignore）
docker compose -f docker-compose.test.yml --env-file .env.test up -d

# 2) 首次初始化 StarRocks 库（应用只建表、不建库）
docker exec tp-test-starrocks mysql -h127.0.0.1 -P9030 -uroot \
  -e "CREATE DATABASE IF NOT EXISTS tag_platform"

# 3) 应用：/opt/tag-platform-test/ 放 jar 与 wait-deps.sh
#    /etc/tag-platform-test/tag-platform-test.env 写数据源与主密钥
#    （模板 deploy/test/tag-platform-test.env.example，部署后 chmod 640）
scp server/target/tag-platform-server.jar root@<host>:/opt/tag-platform-test/
scp deploy/test/wait-deps.sh            root@<host>:/opt/tag-platform-test/
scp deploy/test/tag-platform-test.service root@<host>:/etc/systemd/system/
ssh root@<host> 'systemctl daemon-reload && systemctl enable --now tag-platform-test'

# 4) 初始化 32 标签口径
HOST=<host> PORT=8081 ./data/seed-fleet-tags.sh
HOST=<host> PORT=8081 APPLY=1 ./data/archive-non-core-tags.sh

# 5) 冒烟
HOST=<host> PORT=8081 ./deploy/smoke-test.sh      # PASS=29
```

要点：

- 首次启动前必须先建 StarRocks 库，否则应用报 `Unknown database 'tag_platform'`；两张表由应用 `init-schema` 自动创建。
- `deploy/test/wait-deps.sh` 在启动前等待 MySQL 端口与 StarRocks `/api/health`（`online_backend_num>=1`），避免重启后因 FE 未就绪而失败重启。
- 整机重启实测 65 秒恢复（MySQL 5s、StarRocks FE/BE 25s、应用 8.6s，`NRestarts=0`）。
- 升级：替换 `/opt/tag-platform-test/tag-platform-server.jar` 后 `systemctl restart tag-platform-test`；Flyway 会在启动时自动执行新增迁移。

### 6.3 三套环境配置位置速查

| 环境 | 应用配置 | 进程托管 | 组件形态 | 数据落盘 | 日志 |
|---|---|---|---|---|---|
| 本地 | `application.yml` 默认值（仅端口用命令行覆盖） | 手动 `java -jar` | `deploy/docker-compose.yml`（`tp-*` 容器） | 外接盘 `docker-volumes/` | `server-prod.log` |
| 测试环境 | `/etc/tag-platform-test/tag-platform-test.env`（640） | `tag-platform-test.service` | `deploy/docker-compose.test.yml` + `.env.test`（600） | Docker 卷 `tag-platform-test_*` | `/var/log/tag-platform-test/` |
| 生产环境 | `/etc/tag-platform/tag-platform.env`（640）+ `ob-migrate.env`（600） | `tag-platform.service` | OceanBase + StarRocks + 本机 Redis | 外部集群 | `/var/log/tag-platform/` |

仓库内对应模板：`deploy/.env.example`（本地 compose）、`deploy/.env.test.example` + `deploy/test/tag-platform-test.env.example`（测试）、`deploy/prod/{tag-platform.env.example,ob-migrate.env.example,tag-platform.service}`（生产）。样例中所有 `CHANGE_ME` 均须替换，真实口令与主密钥只保留在部署机。

三套环境共用的关键变量：

| 变量 | 作用 |
|---|---|
| `SERVER_PORT` | 应用端口（本地 8081 / 测试 8081 / 生产 8080） |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | 元数据库连接 |
| `SPRING_DATA_REDIS_HOST` / `_PORT` | Redis 连接（幂等 / nonce / 频控 / 会话） |
| `TAG_PLATFORM_STARROCKS_URL` / `_USERNAME` / `_PASSWORD` / `_INIT_SCHEMA` | StarRocks 连接与自动建表 |
| `TAG_PLATFORM_KV` / `TAG_PLATFORM_STORE` | 存储实现选择（`redis`/`memory`、`starrocks`/`h2`） |
| `TAG_PLATFORM_CRYPTO_MASTER_KEY` | 凭证 Secret 解密主密钥，各环境独立 |
| `SPRING_FLYWAY_ENABLED` | 是否启动时执行迁移（生产为 `false`，见 6.1） |

变更约定：

- 测试 / 生产改完 env 文件后执行 `systemctl restart tag-platform-test` 或 `systemctl restart tag-platform` 生效；本地重启 `java -jar` 进程即可。
- 主密钥不可跨环境复用：`tenant_credential` 中的 `enc:v1:` 密文只能用写入时那把主密钥解开，换密钥会导致租户凭证解密失败。
- 仓库只提交 `deploy/.env.example` 与 `deploy/.env.test.example`；真实的 `.env`、`.env.test` 已在 `.gitignore` 中，仅存在于部署机。

## 7. 测试

```bash
./build.sh test        # 全部 86 个测试
./build.sh test -pl server      # 71 个：单测 + 业务集成测试 + 权限覆盖检查（H2）
./build.sh test -pl sdk-java    # 15 个：签名已知向量 + Mock 服务端回环（签名一致性/重试换 nonce/路径编码）
./deploy/smoke-test.sh          # 端到端冒烟：29 项断言（PASS=29，含 2 条旧前缀兼容断言）
```

## 8. 数据看板与接口埋点

开放 API 的每次调用由 `ApiCallAnalyticsFilter` 记录一条 `api_call_log`（鉴权失败也会记录），包含租户、AppKey、HTTP 方法、归一化端点、HTTP 状态、业务码、耗时、操作员，以及本次调用的打标/查询/作废数量；标签级次数按天聚合到 `tag_access_daily`（`query` / `write` / `delete`）。

控制台新增「数据看板」页（`GET /api/v1/console/dashboard/overview?days=1|7|30`），包含：

- 6 个 KPI：调用量、打标次数、标签查询、活跃租户、成功率、平均耗时；
- 调用趋势（今日按小时 / 多日按天）、接口调用分布（GET/POST）、打标 TOP、查询 TOP、租户调用排行、最近 15 条调用；
- 30 秒自动刷新与全屏展示。

统计口径：

- 只统计 `/api/v1/open/*` 与兼容前缀 `/open/v1/*`，控制台接口不计入；
- 标签查询次数只统计画像/组合查询中**有值**的标签（`status=none` 占位不计）；
- 打标次数按写入条目计，作废单独计入 `delete`；
- 鉴权失败会记录，租户可能为空，看板显示「未鉴权调用」。

迁移与部署：

- `V9002__api_call_analytics.sql`（通用 DDL）与 `V9003__api_call_analytics_comments.sql`（MySQL/OceanBase 注释）；
- 生产 OceanBase 需按 6.1 预执行迁移后重启应用；
- 当前实现为同步落库，便于实时查看；高吞吐场景可在 V2 将埋点写入切到异步队列/Kafka，不影响接口响应。

## 9. 打标工作台与标签版本管理

### 9.1 打标工作台 / 任务管理

面向非 API 用户（客服、风控专员、审核员）的站内打标能力，试点租户 `risk_control`：

- 角色：`TAGGER`、`TASK_MANAGER`、`TENANT_ADMIN`；每个租户最多一个租户管理员，由平台管理员分配；租户管理员只能管理本租户的 AUDITOR / VIEWER / TAGGER / TASK_MANAGER 账号，并拥有本租户工作台/任务权限；TAGGER / TASK_MANAGER 需绑定 `tenant_id / operator_id / team`，写入操作员完全取自登录会话；
- 工作台：对象搜索、画像 overlay（`latest` + 待审核提议值 + 已生效待同步值）、按标签字典动态渲染 boolean/number/enum 单选/多选/date/string；
- 任务：创建、对象导入、发布、任务池/指定人/班组派发、领取、提交、驳回返工、取消、监控；对象来源默认“全部对象（创建时对象索引快照）”，也可按关键字筛选指定对象或手动输入；
- 审核：普通标签提交即写 `effective`；`need_value_audit=true` 强制先审后写；任务级审核可按任务开启；审核通过后复用 `TagValueWriteService` 写入 history + Outbox，`tag_value_latest` 仍由外部程序维护；
- 页面：`#/workbench`、`#/my-tasks`、`#/tasks`、`#/task-monitor`；接口位于 `/api/v1/console/workbench/*` 与 `/api/v1/console/tasks/*`。

### 9.2 角色与权限矩阵

- 系统管理新增只读「角色与权限」页，展示 6 个内置角色、页面访问与业务操作矩阵；
- 角色分配仍使用内置角色，租户管理员只能分配 AUDITOR / VIEWER / TAGGER / TASK_MANAGER；
- 权限矩阵由后端 `RolePermissionCatalog` 统一提供，接口 `GET /api/v1/console/roles/permissions`（平台管理员/租户管理员）；
- 后端接口通过 `@RequirePermission` + `PermissionInterceptor` 统一校验，未声明权限的控制台接口默认拒绝；前端通过 `GET /api/v1/console/auth/permissions` 获取权限码与菜单；
- 前端已拆分为 `static/console/js/core.js`、`js/shared.js`、`js/ui.js`（自绘下拉与日历时间选择）、`js/pages/{tags,governance,analytics,market,workbench,tasks}.js` 与 `js/main.js`，无需前端构建步骤；
- 自定义角色与权限点不在本阶段范围，后续按需求评估动态 RBAC。

### 9.3 标签市场

- 租户管理员可将本租户可见的在线标签上架市场，填写用途说明；所有登录用户可浏览；
- 其他租户管理员提交使用申请，平台管理员审批；审批通过后 `TagMetaService.grantTenant` 将申请租户追加到 `tag_meta.tenant_id`，并生成 `market` 类型版本快照；
- 上架本身不改变标签共享范围，避免租户管理员直接越权扩大适用范围；
- 接口位于 `/api/v1/console/tag-market/*`，迁移见 `V9009__tag_market.sql`。

### 9.4 标签归属与编辑权限

- 标签归属取 `tag_meta.tenant_id` 的**第一个租户**；`PUBLIC` 标签归属平台。
- 平台管理员可管理全部标签；租户管理员只能编辑/上下线/归档/回滚**归属本租户**的标签，且不能修改适用租户列表（避免改变归属或越权扩大共享）。
- 其他适用租户（非第一个租户）在标签字典中为只读，可查看版本历史但不能编辑。
- 「租户与凭证」仅平台管理员/租户管理员可见；「审计日志、待审核队列」仅平台管理员/租户管理员/审核员可见；打标员、任务管理员、只读角色不可见。
- 租户管理员新建标签固定归属本租户，审核仍由平台管理员处理。

### 9.5 标签定义版本

- 快照表 `tag_meta_version`：注册、修改定义、切换写入开关、回滚时保存完整定义；存量标签迁移时回填 `baseline`；
- 查询：`GET /api/v1/console/tags/{tagCode}/versions` 与 `/versions/{version}`（含上一版本，用于 diff）；
- 回滚：`POST /api/v1/console/tags/{tagCode}/versions/{version}/rollback`，仅管理员；生成新版本，不删除历史、不改变上下线状态；
- 控制台标签编码旁显示 `vN`，点击可查看版本列表、字段 diff 并回滚；审计日志记录 `TAG_ROLLBACK`。

## 10. 演进路线

当前版本 → V2（Kafka/批量画像/血缘）→ V3（Flink 实时/倒排圈选/订阅回调）→ V4（Iceberg 回流入湖/多语言 SDK/OceanBase/K8s）。

扩展性不变式：契约先行（`/api/v1/open` 版本化、字段只增不删）、宽表全字段一次建齐（含 `ext_json/ver` 预留列）、Outbox 第一天落表、模块边界禁止跨模块访表、租户强制过滤、`request_id + operator_id` 全链路透传。

---

## License

本项目采用 [MIT License](./LICENSE) 开源。

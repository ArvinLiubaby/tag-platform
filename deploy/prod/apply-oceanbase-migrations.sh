#!/usr/bin/env bash
# ============================================================================
# OceanBase（MySQL 模式）迁移执行器
#
# 背景：OceanBase 4.x 不支持 MySQL 的 GET_LOCK，而 Flyway 的 MySQL 实现依赖
#       命名锁，启动时会报 "Unable to acquire MySQL named lock"（错误码 1235）；
#       Flyway 11 已移除 flyway-database-oceanbase 模块。
#       因此生产环境关闭 spring.flyway，改由本脚本按版本顺序执行迁移。
#
# 特性：
#   - 自动在多个 OBServer 地址中挑选可用节点；
#   - 以 schema_migration 表登记已执行文件，可重复执行（幂等）；
#   - 已执行文件的 sha256 变化时中止，避免静默改表。
#
# 用法：
#   OB_HOSTS="ob-node-1 ob-node-2 ob-node-3" \
#   OB_USER='root@tagprofile' OB_PASSWORD='***' \
#   ./apply-oceanbase-migrations.sh /opt/tag-platform/db tag_platform
# ============================================================================
set -euo pipefail

SQL_ROOT="${1:-/opt/tag-platform/db}"
DB_NAME="${2:-tag_platform}"
OB_PORT="${OB_PORT:-2883}"
OB_USER="${OB_USER:?必须设置 OB_USER}"
OB_PASSWORD="${OB_PASSWORD:?必须设置 OB_PASSWORD}"
OB_HOSTS="${OB_HOSTS:-ob-node-1 ob-node-2 ob-node-3}"

MYSQL_ARGS=(--default-character-set=utf8mb4 --connect-timeout=8 -P "$OB_PORT" -u "$OB_USER" "$DB_NAME")

mysql_at() {
  local host="$1"
  shift
  MYSQL_PWD="$OB_PASSWORD" mysql -h "$host" "${MYSQL_ARGS[@]}" "$@"
}

pick_host() {
  local h
  for h in $OB_HOSTS; do
    if mysql_at "$h" -N -e "SELECT 1" >/dev/null 2>&1; then
      echo "$h"
      return 0
    fi
  done
  echo "[migrate] 无法连接 OceanBase：$OB_HOSTS:$OB_PORT" >&2
  return 1
}

HOST="$(pick_host)"
echo "[migrate] OceanBase 节点 $HOST:$OB_PORT，库 $DB_NAME"

mysql_at "$HOST" <<'SQL'
CREATE TABLE IF NOT EXISTS schema_migration (
  version    VARCHAR(128) NOT NULL COMMENT '迁移文件名（V<版本>__<描述>.sql）',
  checksum   CHAR(64)     NOT NULL COMMENT '迁移文件 sha256（内容变化即中止）',
  applied_at DATETIME     NOT NULL COMMENT '执行时间',
  PRIMARY KEY (version)
) DEFAULT CHARSET = utf8mb4 COMMENT = '生产迁移执行记录（Flyway 关闭后由 deploy/prod 脚本维护）';
SQL

FILES=()
while IFS= read -r f; do
  FILES+=("$f")
done < <(find "$SQL_ROOT/migration" "$SQL_ROOT/migration-mysql" -maxdepth 1 -name 'V*__*.sql' -type f -printf '%f\t%p\n' \
           | sort -V -k1,1 | cut -f2-)

if [ "${#FILES[@]}" -eq 0 ]; then
  echo "[migrate] 未在 $SQL_ROOT 下找到迁移脚本" >&2
  exit 1
fi

for f in "${FILES[@]}"; do
  name="$(basename "$f")"
  checksum="$(sha256sum "$f" | awk '{print $1}')"
  applied="$(mysql_at "$HOST" -N -B -e "SELECT checksum FROM schema_migration WHERE version='${name}'")"
  if [ -z "$applied" ]; then
    echo "[migrate] 执行 $name"
    mysql_at "$HOST" < "$f"
    mysql_at "$HOST" -e "INSERT INTO schema_migration (version, checksum, applied_at) VALUES ('${name}', '${checksum}', NOW())"
  elif [ "$applied" = "$checksum" ]; then
    echo "[migrate] 跳过 $name（已执行）"
  else
    echo "[migrate] $name 已执行但内容已变化（checksum 不一致），中止" >&2
    exit 2
  fi
done

echo "[migrate] 完成，共 ${#FILES[@]} 个迁移文件"

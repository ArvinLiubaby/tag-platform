#!/usr/bin/env bash
# =============================================================================
# 标签画像采集平台 — 本地一键启动
#
#   ./start-local.sh            启动（容器 → StarRocks 自愈 → 应用 8081 → 健康校验）
#   ./start-local.sh stop       停止应用（容器保留）
#   ./start-local.sh restart    重启应用
#   ./start-local.sh status     查看状态
#   BUILD=1 ./start-local.sh    先构建再启动
#   PORT=8082 ./start-local.sh  换端口启动
#
# 说明：
#   - 本机未安装 docker compose 插件，容器已存在，统一用 docker start 恢复；
#   - StarRocks 常见故障：容器 Up 但 9030 不通，原因是 fe/bin/fe.pid 残留
#     且 pid 被 BE 复用，start_fe.sh 误判“FE 已在运行”。脚本会检测并自愈；
#   - 应用日志追加到 server-prod.log，pid 记录在 server.pid。
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")"

PORT="${PORT:-8081}"
JAR="server/target/tag-platform-server.jar"
PIDFILE="server.pid"
LOGFILE="server-prod.log"
CONTAINERS=(tp-mysql tp-redis tp-starrocks)
SR_CONF="/data/deploy/starrocks/supervisor/supervisord.conf"
SR_PIDFILE="/data/deploy/starrocks/fe/bin/fe.pid"

info() { echo "==> $*"; }
ok()   { echo "    [OK] $*"; }
warn() { echo "    [!]  $*" >&2; }
die()  { echo "    [x]  $*" >&2; exit 1; }

# ---------------------------------------------------------------- JDK 探测
detect_jdk() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then echo "${JAVA_HOME}"; return; fi
  local c
  for c in "$HOME/.sdkman/candidates/java/current" "$HOME"/.sdkman/candidates/java/21* \
           /usr/lib/jvm/* "$HOME"/.jdks/*; do
    [ -x "${c}/bin/java" ] && { echo "${c}"; return; }
  done
  echo ""
}

# ---------------------------------------------------------------- 端口探测
port_open() {
  (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

wait_port() {
  local p="$1" timeout="${2:-30}" i
  for ((i = 0; i < timeout; i++)); do
    port_open "$p" && return 0
    sleep 1
  done
  return 1
}

http_up() {
  curl -sf --max-time 3 "http://127.0.0.1:${PORT}/actuator/health" >/dev/null 2>&1
}

app_pid() {
  [ -f "${PIDFILE}" ] && cat "${PIDFILE}" 2>/dev/null || true
}

pid_clear() {
  : > "${PIDFILE}" 2>/dev/null || true
}

# ---------------------------------------------------------------- 容器
ensure_containers() {
  info "检查依赖容器（MySQL / Redis / StarRocks）"
  local name running
  for name in "${CONTAINERS[@]}"; do
    if ! docker inspect "${name}" >/dev/null 2>&1; then
      die "容器 ${name} 不存在。本机无 compose 插件，请先执行 deploy/local/setup-docker.sh 初始化环境"
    fi
    running="$(docker inspect -f '{{.State.Running}}' "${name}" 2>/dev/null || echo false)"
    if [ "${running}" != "true" ]; then
      docker start "${name}" >/dev/null 2>&1 || die "启动容器 ${name} 失败"
      ok "${name} 已启动"
    else
      ok "${name} 运行中"
    fi
  done
}

wait_databases() {
  info "等待 MySQL(3306) / Redis(6379) 就绪"
  wait_port 3306 40 || die "MySQL 未在 40s 内就绪，查看：docker logs --tail 50 tp-mysql"
  ok "MySQL 就绪"
  wait_port 6379 30 || die "Redis 未在 30s 内就绪，查看：docker logs --tail 50 tp-redis"
  ok "Redis 就绪"
}

# ---------------------------------------------------------------- StarRocks
sr_health() {
  curl -sf --max-time 4 "http://127.0.0.1:8030/api/health" 2>/dev/null | grep -q '"status":"OK"'
}

# FE 自愈：pid 文件残留 + pid 被 BE 占用时，清 pid 并重启 feservice
fix_starrocks_fe() {
  docker exec tp-starrocks test -f "${SR_PIDFILE}" >/dev/null 2>&1 || return 1
  local pid comm
  pid="$(docker exec tp-starrocks cat "${SR_PIDFILE}" 2>/dev/null | tr -d '[:space:]')"
  [ -n "${pid}" ] || { docker exec tp-starrocks rm -f "${SR_PIDFILE}" >/dev/null 2>&1; return 0; }
  comm="$(docker exec tp-starrocks sh -c "ps -p ${pid} -o comm= 2>/dev/null | tr -d '[:space:]'" 2>/dev/null)"
  if [ "${comm}" != "java" ]; then
    warn "FE pid 残留：fe.pid=${pid}，而该进程是「${comm:-不存在}」——清理并重启 feservice"
    docker exec tp-starrocks rm -f "${SR_PIDFILE}" >/dev/null 2>&1
    docker exec tp-starrocks supervisorctl -c "${SR_CONF}" restart feservice >/dev/null 2>&1 || true
    return 0
  fi
  return 1
}

wait_starrocks() {
  info "等待 StarRocks(8030 health / 9030 query) 就绪"
  local i state
  for ((i = 0; i < 20; i++)); do
    sr_health && { ok "StarRocks 就绪"; return 0; }
    sleep 2
  done

  # 区分两类情况：FE 正在启动（RUNNING 但端口未通，继续等）vs FE 已挂/pid 残留（自愈）
  state="$(docker exec tp-starrocks bash -c "supervisorctl -c ${SR_CONF} status feservice" 2>/dev/null | awk '{print $2}')"
  warn "40s 未就绪，feservice 状态：${state:-未知}"
  if [ "${state}" = "RUNNING" ]; then
    warn "FE 进程存在，判定为启动中，继续等待（不重启）"
  elif fix_starrocks_fe; then
    warn "已执行 FE 自愈（清理 pid 残留 + 重启 feservice）"
  else
    warn "尝试重启 feservice"
    docker exec tp-starrocks supervisorctl -c "${SR_CONF}" restart feservice >/dev/null 2>&1 || true
  fi

  for ((i = 0; i < 60; i++)); do
    sr_health && { ok "StarRocks 就绪（恢复后）"; return 0; }
    sleep 2
  done
  die "StarRocks 仍未就绪。排查：docker logs --tail 40 tp-starrocks && docker exec tp-starrocks supervisorctl -c ${SR_CONF} status"
}

# ---------------------------------------------------------------- 应用
build_if_needed() {
  [ "${BUILD:-0}" = "1" ] || return 0
  info "构建应用（BUILD=1）"
  ./build.sh clean package -DskipTests || die "构建失败"
  ok "构建完成"
}

start_app() {
  if http_up; then
    ok "应用已在 ${PORT} 运行（pid $(app_pid)）"
    return 0
  fi
  if port_open "${PORT}"; then
    die "端口 ${PORT} 已被其它进程占用，请先释放或改用 PORT=xxxx ./start-local.sh"
  fi
  [ -f "${JAR}" ] || die "缺少 ${JAR}，请先执行 ./build.sh clean package（或 BUILD=1 ./start-local.sh）"

  local java_home
  java_home="$(detect_jdk)"
  [ -n "${java_home}" ] || die "未找到 JDK（需要 21）。请设置 JAVA_HOME 或通过 SDKMAN 安装"

  # 日志超过 50MB 时轮转，避免无限增长
  if [ -f "${LOGFILE}" ] && [ "$(stat -c %s "${LOGFILE}" 2>/dev/null || echo 0)" -gt 52428800 ]; then
    mv "${LOGFILE}" "${LOGFILE}.$(date +%Y%m%d%H%M%S)"
    ok "日志已轮转"
  fi

  info "启动应用（端口 ${PORT}，JDK ${java_home##*/}）"
  nohup "${java_home}/bin/java" -jar "${JAR}" --server.port="${PORT}" >> "${LOGFILE}" 2>&1 &
  echo $! > "${PIDFILE}"

  local i
  for ((i = 0; i < 60; i++)); do
    if http_up; then ok "应用就绪（pid $(app_pid)，耗时约 ${i}s）"; return 0; fi
    if ! kill -0 "$(app_pid)" 2>/dev/null; then
      echo "---- ${LOGFILE} 最后 25 行 ----" >&2
      tail -25 "${LOGFILE}" >&2
      pid_clear
      die "应用启动失败（进程已退出）"
    fi
    sleep 1
  done
  die "应用 60s 未通过健康检查，查看 ${LOGFILE}"
}

stop_app() {
  local pid
  pid="$(app_pid)"
  if [ -z "${pid}" ] || ! kill -0 "${pid}" 2>/dev/null; then
    warn "未发现运行中的应用（pid 文件：${PIDFILE}）"
    pid_clear
    return 0
  fi
  info "停止应用（pid ${pid}）"
  kill "${pid}" 2>/dev/null || true
  local i
  for ((i = 0; i < 20; i++)); do
    kill -0 "${pid}" 2>/dev/null || { pid_clear; ok "已停止"; return 0; }
    sleep 1
  done
  warn "优雅停止超时，强制结束"
  kill -9 "${pid}" 2>/dev/null || true
  pid_clear
  ok "已强制停止"
}

# ---------------------------------------------------------------- 状态
show_status() {
  info "容器"
  local name running
  for name in "${CONTAINERS[@]}"; do
    running="$(docker inspect -f '{{.State.Running}}' "${name}" 2>/dev/null || echo missing)"
    case "${running}" in
      true)    ok "${name} 运行中" ;;
      false)   warn "${name} 已停止" ;;
      *)       warn "${name} 不存在" ;;
    esac
  done

  info "依赖端口"
  port_open 3306 && ok "MySQL 3306" || warn "MySQL 3306 不通"
  port_open 6379 && ok "Redis 6379" || warn "Redis 6379 不通"
  sr_health && ok "StarRocks 健康" || warn "StarRocks 未就绪"

  info "应用"
  if http_up; then
    ok "http://127.0.0.1:${PORT}  健康（pid $(app_pid)）"
  else
    warn "应用未运行（${PORT}）"
  fi
}

print_summary() {
  cat <<EOF

────────────────────────────────────────────────────────
  控制台   http://127.0.0.1:${PORT}/console/index.html
  账号     admin / admin123
  健康     http://127.0.0.1:${PORT}/actuator/health
  日志     $(pwd)/${LOGFILE}
  停止     ./start-local.sh stop
────────────────────────────────────────────────────────
EOF
}

# ---------------------------------------------------------------- 入口
case "${1:-start}" in
  start)
    info "本地启动 标签画像采集平台（端口 ${PORT}）"
    ensure_containers
    wait_databases
    wait_starrocks
    build_if_needed
    start_app
    print_summary
    ;;
  stop)
    stop_app
    ;;
  restart)
    stop_app
    ensure_containers
    wait_databases
    wait_starrocks
    start_app
    print_summary
    ;;
  status)
    show_status
    ;;
  *)
    echo "用法：$0 [start|stop|restart|status]" >&2
    echo "      BUILD=1 $0      先构建再启动" >&2
    echo "      PORT=8082 $0    指定端口" >&2
    exit 1
    ;;
esac
